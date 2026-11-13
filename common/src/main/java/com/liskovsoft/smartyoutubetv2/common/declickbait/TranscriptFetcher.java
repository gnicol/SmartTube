package com.liskovsoft.smartyoutubetv2.common.declickbait;

import androidx.annotation.Nullable;

import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads a video's captions as text.
 * <p>
 * First uses the caption tracks SmartTube already has for playback. Their URLs can need a PO token, which some
 * devices (e.g. Fire OS 6) can't produce: YouTube then answers 200 with an empty body and even the built-in
 * player fails. So when that comes back empty, ask YouTube's visionOS and Android VR clients for the tracks,
 * whose caption URLs work without a token.
 */
public class TranscriptFetcher {
    private static final String TAG = TranscriptFetcher.class.getSimpleName();
    private static final Pattern VTT_TIME = Pattern.compile("^(\\d+:)?(\\d{2}):(\\d{2})\\.(\\d{3})\\s+-->");
    private static final Pattern VTT_TAG = Pattern.compile("<[^>]+>");

    /** Why the captions couldn't be read, short enough to show on the TV. */
    public static class FetchException extends Exception {
        FetchException(String reason) {
            super(reason);
        }
    }

    /**
     * Blocking. Tries the playback tracks, then tracks from token-free clients.
     *
     * @param primary the subtitles from the format info SmartTube loaded (may be null)
     */
    public static Transcript fetchForVideo(String videoId, @Nullable List<MediaSubtitle> primary) throws FetchException {
        Set<String> reasons = new LinkedHashSet<>();
        Set<String> tried = new LinkedHashSet<>();

        String url = pickUrl(primary);
        if (url == null) {
            reasons.add("no caption tracks");
        } else {
            tried.add(url);
            try {
                return fetch(url);
            } catch (FetchException e) {
                reasons.add(e.getMessage());
            }
        }

        // AppClient is Kotlin-internal to MediaServiceCore, but public at the JVM level.
        for (AppClient client : new AppClient[] {AppClient.VISIONOS, AppClient.ANDROID_VR}) {
            List<MediaSubtitle> subs = subtitlesFrom(client, videoId);
            String altUrl = pickUrl(subs);
            if (altUrl == null || tried.contains(altUrl)) {
                continue;
            }
            tried.add(altUrl);
            try {
                Transcript t = fetch(altUrl);
                Log.w(TAG, "captions came from the %s client", client.name());
                return t;
            } catch (FetchException e) {
                reasons.add(client.name().toLowerCase() + ": " + e.getMessage());
            }
        }

        throw new FetchException(join(reasons));
    }

    /**
     * VideoInfoService.getVideoInfo(AppClient, String, String) is private in SmartTube 32.71 (stable) and public
     * from 32.74, so look it up reflectively: one code path for both, and a quiet skip if it ever disappears.
     */
    @Nullable
    private static List<MediaSubtitle> subtitlesFrom(AppClient client, String videoId) {
        try {
            Method method = VideoInfoService.class.getDeclaredMethod("getVideoInfo", AppClient.class, String.class, String.class);
            method.setAccessible(true);
            Object videoInfo = method.invoke(VideoInfoService.instance(), client, videoId, null);
            MediaItemFormatInfo info = videoInfo instanceof VideoInfo ? YouTubeMediaItemFormatInfo.from((VideoInfo) videoInfo) : null;
            return info != null ? info.getSubtitles() : null;
        } catch (Exception e) {
            Log.w(TAG, "%s client: %s", client.name(), e);
            return null;
        }
    }

    /** Choose English captions: manual first, then auto-generated, then a translation to English, then anything. */
    @Nullable
    static String pickUrl(@Nullable List<MediaSubtitle> subtitles) {
        if (subtitles == null || subtitles.isEmpty()) {
            return null;
        }

        StringBuilder tracks = new StringBuilder();
        for (MediaSubtitle sub : subtitles) {
            tracks.append(sub.getLanguageCode()).append('/').append(sub.getVssId()).append('/').append(sub.getType())
                    .append(sub.getBaseUrl() == null ? "(no url)" : "").append(' ');
        }
        Log.w(TAG, "caption tracks: %s", tracks);

        MediaSubtitle manualEn = null;
        MediaSubtitle autoEn = null;
        MediaSubtitle translatable = null;

        for (MediaSubtitle sub : subtitles) {
            if (sub.getBaseUrl() == null) {
                continue;
            }
            String lang = sub.getLanguageCode() != null ? sub.getLanguageCode() : "";
            String vssId = sub.getVssId() != null ? sub.getVssId() : "";
            boolean isAuto = vssId.startsWith("a.") || "asr".equals(sub.getType());
            if (lang.startsWith("en")) {
                if (isAuto) {
                    if (autoEn == null) autoEn = sub;
                } else if (manualEn == null) {
                    manualEn = sub;
                }
            } else if (translatable == null && sub.isTranslatable()) {
                translatable = sub;
            }
        }

        if (manualEn != null) return manualEn.getBaseUrl();
        if (autoEn != null) return autoEn.getBaseUrl();
        if (translatable != null) return withParam(translatable.getBaseUrl(), "tlang", "en");

        for (MediaSubtitle sub : subtitles) {
            if (sub.getBaseUrl() != null) {
                return sub.getBaseUrl();
            }
        }
        return null;
    }

    /**
     * Blocking. Tries json3 then WebVTT (what the player uses) on the URL as given, then the same without the
     * PO token params.
     */
    static Transcript fetch(String baseUrl) throws FetchException {
        OkHttpClient client = OkHttpManager.instance().getClient();
        List<String> bases = new ArrayList<>();
        bases.add(baseUrl);
        String noToken = withoutParams(baseUrl, "pot", "c");
        if (!noToken.equals(baseUrl)) {
            bases.add(noToken);
        }

        Set<String> reasons = new LinkedHashSet<>();
        for (String base : bases) {
            for (String fmt : new String[] {"json3", "vtt"}) {
                Download d = downloadWithRetry(client, withParam(base, "fmt", fmt));
                if (d.text == null) {
                    reasons.add(d.reason);
                    continue;
                }
                Transcript t = "json3".equals(fmt) ? parseJson3(d.text) : parseVtt(d.text);
                if (t != null && !t.isEmpty()) {
                    return t;
                }
                reasons.add(d.text.trim().isEmpty() ? "empty response" : "unreadable response");
            }
        }
        throw new FetchException(join(reasons));
    }

    static String withParam(String url, String name, String value) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed != null ? parsed.newBuilder().setQueryParameter(name, value).build().toString() : url;
    }

    static String withoutParams(String url, String... names) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            return url;
        }
        HttpUrl.Builder builder = parsed.newBuilder();
        for (String name : names) {
            builder.removeAllQueryParameters(name);
        }
        return builder.build().toString();
    }

    private static String join(Set<String> reasons) {
        StringBuilder sb = new StringBuilder();
        for (String r : reasons) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(r);
        }
        return sb.length() > 0 ? sb.toString() : "no response";
    }

    private static class Download {
        final String text;
        final String reason;

        Download(String text, String reason) {
            this.text = text;
            this.reason = reason;
        }
    }

    /** One retry after a short pause for rate limiting and server errors. */
    private static Download downloadWithRetry(OkHttpClient client, String url) {
        Download d = download(client, url);
        if (d.text == null && d.reason != null && (d.reason.equals("HTTP 429") || d.reason.startsWith("HTTP 5"))) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                return d;
            }
            d = download(client, url);
        }
        return d;
    }

    private static Download download(OkHttpClient client, String url) {
        // Same User-Agent the player uses for media and subtitle requests.
        Request request = new Request.Builder().url(url).header("User-Agent", DefaultHeaders.APP_USER_AGENT).get().build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body != null ? body.string() : null;
            Log.w(TAG, "captions %s -> HTTP %s, %s chars, starts: %s", describe(url), response.code(),
                    text != null ? text.length() : -1,
                    text != null ? text.substring(0, Math.min(80, text.length())).replace('\n', ' ') : "");
            return response.isSuccessful() ? new Download(text, null) : new Download(null, "HTTP " + response.code());
        } catch (IOException e) {
            Log.w(TAG, "captions %s -> %s", describe(url), e);
            return new Download(null, e.getClass().getSimpleName());
        }
    }

    /** Host, path and parameter names only (values include signatures and tokens). */
    private static String describe(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            return "(bad url)";
        }
        StringBuilder sb = new StringBuilder(parsed.host()).append(parsed.encodedPath()).append(" [");
        for (String name : parsed.queryParameterNames()) {
            sb.append(name);
            if (name.equals("fmt") || name.equals("lang") || name.equals("kind") || name.equals("c") || name.equals("tlang")) {
                sb.append('=').append(parsed.queryParameter(name));
            }
            sb.append(' ');
        }
        return sb.append(']').toString();
    }

    @Nullable
    static Transcript parseJson3(@Nullable String content) {
        if (content == null || !content.trim().startsWith("{")) {
            return null;
        }
        try {
            JSONArray events = new JSONObject(content).optJSONArray("events");
            if (events == null) {
                return null;
            }
            Transcript.Builder builder = new Transcript.Builder(null);
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                JSONArray segs = event != null ? event.optJSONArray("segs") : null;
                if (segs == null) {
                    continue;
                }
                StringBuilder text = new StringBuilder();
                for (int j = 0; j < segs.length(); j++) {
                    JSONObject seg = segs.optJSONObject(j);
                    if (seg != null) {
                        text.append(seg.optString("utf8", ""));
                    }
                }
                builder.addCue(event.optLong("tStartMs", 0), text.toString());
            }
            return builder.build();
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * WebVTT: a cue is a timing line plus the text lines up to the next blank line. Cue identifiers, NOTE and STYLE
     * blocks are skipped. YouTube's rolling auto-captions repeat the previous cue's lines, so those are dropped.
     */
    @Nullable
    static Transcript parseVtt(@Nullable String content) {
        if (content == null || !content.startsWith("WEBVTT")) {
            return null;
        }
        Transcript.Builder builder = new Transcript.Builder(null);
        List<String> previous = new ArrayList<>();
        List<String> current = null;
        long cueStart = -1;

        String[] lines = content.split("\r?\n", -1);
        for (int i = 0; i <= lines.length; i++) {
            String line = i < lines.length ? lines[i] : "";
            Matcher m = VTT_TIME.matcher(line);
            if (m.find()) {
                long h = m.group(1) != null ? Long.parseLong(m.group(1).replace(":", "")) : 0;
                cueStart = ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000
                        + Long.parseLong(m.group(4));
                current = new ArrayList<>();
            } else if (current != null && !line.trim().isEmpty()) {
                String text = VTT_TAG.matcher(line).replaceAll("").trim();
                if (!text.isEmpty()) {
                    current.add(text);
                }
            } else if (current != null) { // blank line or end: the cue is complete
                StringBuilder fresh = new StringBuilder();
                for (String text : current) {
                    if (!previous.contains(text)) {
                        if (fresh.length() > 0) fresh.append(' ');
                        fresh.append(text);
                    }
                }
                builder.addCue(cueStart, fresh.toString());
                if (!current.isEmpty()) {
                    previous = current;
                }
                current = null;
            }
        }
        return builder.build();
    }
}
