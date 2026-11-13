package com.liskovsoft.smartyoutubetv2.common.declickbait;

import androidx.annotation.Nullable;

import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads a video's captions as text. Uses the same caption URLs SmartTube already plays
 * (they carry the PO token), asking YouTube for json3 and falling back to the WebVTT it serves by default.
 */
public class TranscriptFetcher {
    private static final String TAG = TranscriptFetcher.class.getSimpleName();
    private static final Pattern VTT_TIME = Pattern.compile("^(\\d+:)?(\\d{2}):(\\d{2})\\.(\\d{3})\\s+-->");
    private static final Pattern VTT_TAG = Pattern.compile("<[^>]+>");

    /** Choose English captions: manual first, then auto-generated, then a translation to English, then anything. */
    @Nullable
    public static String pickUrl(@Nullable List<MediaSubtitle> subtitles) {
        if (subtitles == null || subtitles.isEmpty()) {
            Log.w(TAG, "no caption tracks");
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
        if (translatable != null) return translatable.getBaseUrl() + "&tlang=en";

        for (MediaSubtitle sub : subtitles) {
            if (sub.getBaseUrl() != null) {
                return sub.getBaseUrl();
            }
        }
        return null;
    }

    /** Why the captions couldn't be read, short enough to show on the TV. */
    public static class FetchException extends Exception {
        FetchException(String reason) {
            super(reason);
        }
    }

    /**
     * Blocking. Tries json3 then WebVTT (what the player uses) on the URL as given, then the same without the
     * PO token: older devices can get a token YouTube rejects, while the signed URL works without one.
     */
    public static Transcript fetch(String baseUrl) throws FetchException {
        OkHttpClient client = OkHttpManager.instance().getClient();
        List<String> bases = new ArrayList<>();
        bases.add(baseUrl);
        String noToken = withoutParams(baseUrl, "pot", "c");
        if (!noToken.equals(baseUrl)) {
            bases.add(noToken);
        }

        String reason = "no response";
        for (String base : bases) {
            for (String fmt : new String[] {"json3", "vtt"}) {
                Download d = downloadWithRetry(client, withFormat(base, fmt));
                if (d.text == null) {
                    reason = d.reason;
                    continue;
                }
                Transcript t = "json3".equals(fmt) ? parseJson3(d.text) : parseVtt(d.text);
                if (t != null && !t.isEmpty()) {
                    return t;
                }
                reason = d.text.trim().isEmpty() ? "empty response" : "unreadable response";
            }
        }
        throw new FetchException(reason);
    }

    static String withoutParams(String url, String... names) {
        String result = url;
        for (String name : names) {
            result = result.replaceAll("([?&])" + name + "=[^&]*&?", "$1");
        }
        return result.replaceAll("[?&]$", "");
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

    static String withFormat(String url, String fmt) {
        if (url.matches(".*[?&]fmt=[^&]*.*")) {
            return url.replaceAll("([?&])fmt=[^&]*", "$1fmt=" + fmt);
        }
        return url + (url.contains("?") ? "&" : "?") + "fmt=" + fmt;
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
        int q = url.indexOf('?');
        if (q < 0) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url.substring(0, q)).append(" [");
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? pair.substring(0, eq) : pair;
            sb.append(name);
            if (name.equals("fmt") || name.equals("lang") || name.equals("kind") || name.equals("c") || name.equals("tlang")) {
                sb.append('=').append(eq >= 0 ? pair.substring(eq + 1) : "");
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

    @Nullable
    static Transcript parseVtt(@Nullable String content) {
        if (content == null || !content.startsWith("WEBVTT")) {
            return null;
        }
        Transcript.Builder builder = new Transcript.Builder(null);
        String[] lines = content.split("\r?\n");
        long cueStart = -1;
        StringBuilder cueText = new StringBuilder();

        for (String line : lines) {
            Matcher m = VTT_TIME.matcher(line);
            if (m.find()) {
                if (cueStart >= 0) {
                    builder.addCue(cueStart, cueText.toString());
                }
                long h = m.group(1) != null ? Long.parseLong(m.group(1).replace(":", "")) : 0;
                cueStart = ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000
                        + Long.parseLong(m.group(4));
                cueText.setLength(0);
            } else if (cueStart >= 0 && !line.trim().isEmpty()) {
                // Rolling auto-captions: keep only the newest line of each cue.
                cueText.setLength(0);
                cueText.append(VTT_TAG.matcher(line).replaceAll("").trim());
            }
        }
        if (cueStart >= 0) {
            builder.addCue(cueStart, cueText.toString());
        }
        return builder.build();
    }
}
