package com.liskovsoft.smartyoutubetv2.common.declickbait;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
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
    private static final Pattern VTT_TIME = Pattern.compile("^(\\d+:)?(\\d{2}):(\\d{2})\\.(\\d{3})\\s+-->");
    private static final Pattern VTT_TAG = Pattern.compile("<[^>]+>");

    /** Choose English captions: manual first, then auto-generated, then a translation to English, then anything. */
    @Nullable
    public static String pickUrl(@Nullable List<MediaSubtitle> subtitles) {
        if (subtitles == null || subtitles.isEmpty()) {
            return null;
        }

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

    /** Blocking. Returns null when the captions can't be read. */
    @Nullable
    public static Transcript fetch(String baseUrl) {
        OkHttpClient client = OkHttpManager.instance().getClient();

        String json3Url = withFormat(baseUrl, "json3");
        Transcript transcript = parseJson3(download(client, json3Url));
        if (transcript != null && !transcript.isEmpty()) {
            return transcript;
        }

        transcript = parseVtt(download(client, withFormat(baseUrl, "vtt")));
        return transcript != null && !transcript.isEmpty() ? transcript : null;
    }

    static String withFormat(String url, String fmt) {
        if (url.matches(".*[?&]fmt=[^&]*.*")) {
            return url.replaceAll("([?&])fmt=[^&]*", "$1fmt=" + fmt);
        }
        return url + (url.contains("?") ? "&" : "?") + "fmt=" + fmt;
    }

    @Nullable
    private static String download(OkHttpClient client, String url) {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                return null;
            }
            return body.string();
        } catch (IOException e) {
            return null;
        }
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
