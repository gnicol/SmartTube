package com.liskovsoft.smartyoutubetv2.common.declickbait;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * One direct POST to the Claude Messages API per video. Plain OkHttp + org.json on purpose:
 * the official Java SDK needs Android 8+ (Jackson 2.19) and OkHttp 4, while SmartTube pins OkHttp 3.12
 * and the bedroom stick runs Android 7.1.
 */
public class ClaudeClient {
    private static final String TAG = ClaudeClient.class.getSimpleName();
    private static final String URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2026-10-21";
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static OkHttpClient sClient;

    /** Video facts sent alongside the transcript. */
    public static class VideoInfo {
        public String videoId;
        public String title;
        public String channel;
        public long durationMs;
        public String description;
        /** [startMs, title] pairs. */
        public final List<Object[]> chapters = new ArrayList<>();
    }

    public static class ClaudeException extends Exception {
        public final boolean isKeyProblem;

        ClaudeException(String message, boolean isKeyProblem) {
            super(message);
            this.isKeyProblem = isKeyProblem;
        }
    }

    static final String SYSTEM_PROMPT = "You help viewers get past clickbait on YouTube. You'll receive a video's title, "
            + "channel, thumbnail, any chapters and description, and its timestamped transcript. The title usually teases a "
            + "specific payoff (a food, a supplement, a rule) that the video holds back. Find that payoff in the transcript.\n\n"
            + "- The viewer plays a guessing game before seeing your answer, so `tease` restates the question the title and "
            + "thumbnail pose without hinting at the answer.\n"
            + "- Thumbnail text is part of the tease and can narrow what the title means.\n"
            + "- `answer` is the payoff itself in 2-6 words, with no explanation; put specifics in `answer_detail`.\n"
            + "- Answer only from what the video actually says. If it never delivers, set `found` to not_found and say so in "
            + "`title_check`.\n"
            + "- `answer_at` and every moment's `at` must be copied exactly from a transcript line's [m:ss] stamp: the line where "
            + "that point starts. For `answer_at`, use the line where the answer is first stated, not where it is teased.\n"
            + "- `moments`: 1-3 other stretches genuinely worth watching, not including the answer itself. Labels of six words "
            + "or fewer. Skip intros, recaps, sponsor reads and calls to subscribe.\n"
            + "- Write plainly and briefly; this is read on a TV from the couch. Always write in English.";

    static JSONObject schema() throws JSONException {
        JSONObject moment = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("at", str("m:ss copied from a transcript line."))
                        .put("label", str("Six words or fewer.")))
                .put("required", new JSONArray().put("at").put("label"))
                .put("additionalProperties", false);

        JSONObject props = new JSONObject()
                .put("tease", str("The question the title and thumbnail pose, without revealing the answer. About 12 words."))
                .put("answer", str("The payoff in 2-6 words."))
                .put("answer_detail", str("One short line of specifics: names, forms, doses."))
                .put("summary", str("2-3 sentences of the most useful nuance or caveats."))
                .put("title_check", str("One sentence on whether the video supports the title and thumbnail claims."))
                .put("answer_at", str("m:ss copied from the transcript line where the answer is first stated."))
                .put("moments", new JSONObject().put("type", "array").put("items", moment))
                .put("decoys", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string"))
                        .put("description", "Three plausible wrong answers for a multiple-choice guess, same style and length as `answer`."))
                .put("found", new JSONObject().put("type", "string")
                        .put("enum", new JSONArray().put("clear").put("partial").put("not_found")));

        JSONArray required = new JSONArray();
        for (String key : new String[] {"tease", "answer", "answer_detail", "summary", "title_check", "answer_at", "moments", "decoys", "found"}) {
            required.put(key);
        }

        return new JSONObject()
                .put("type", "object")
                .put("properties", props)
                .put("required", required)
                .put("additionalProperties", false);
    }

    private static JSONObject str(String description) throws JSONException {
        return new JSONObject().put("type", "string").put("description", description);
    }

    static String buildUserText(VideoInfo info, Transcript transcript) {
        StringBuilder sb = new StringBuilder();
        sb.append("Title: ").append(info.title).append("\n\n");
        if (info.channel != null) {
            sb.append("Channel: ").append(info.channel).append("\n\n");
        }
        if (info.durationMs > 0) {
            sb.append("Length: ").append(Transcript.formatTime(info.durationMs)).append("\n\n");
        }
        if (!info.chapters.isEmpty()) {
            sb.append("Chapters:\n");
            for (Object[] chapter : info.chapters) {
                sb.append('[').append(Transcript.formatTime((Long) chapter[0])).append("] ").append(chapter[1]).append('\n');
            }
            sb.append('\n');
        }
        if (info.description != null && !info.description.trim().isEmpty()) {
            String desc = info.description.trim();
            sb.append("Description (truncated):\n").append(desc.length() > 1000 ? desc.substring(0, 1000) : desc).append("\n\n");
        }
        sb.append("Transcript:\n").append(transcript.toPromptText());
        return sb.toString();
    }

    /** Blocking. Call off the main thread. */
    public static DeclickbaitResult analyze(DeclickbaitPrefs prefs, VideoInfo info, Transcript transcript) throws ClaudeException {
        if (!prefs.hasApiKey()) {
            throw new ClaudeException("Add your Claude API key in Settings first.", true);
        }

        try {
            JSONObject response;
            try {
                response = post(prefs, buildBody(prefs, info, transcript, true));
            } catch (ImageException e) {
                Log.d(TAG, "Thumbnail not usable, retrying without it: %s", e.getMessage());
                try {
                    response = post(prefs, buildBody(prefs, info, transcript, false));
                } catch (ImageException e2) {
                    throw new ClaudeException("Claude returned an error: " + e2.getMessage(), false);
                }
            }
            return parseResponse(prefs, info, transcript, response);
        } catch (JSONException e) {
            throw new ClaudeException("Couldn't read Claude's answer.", false);
        }
    }

    static JSONObject buildBody(DeclickbaitPrefs prefs, VideoInfo info, Transcript transcript, boolean withImage) throws JSONException {
        JSONArray content = new JSONArray();
        if (withImage && info.videoId != null) {
            content.put(new JSONObject()
                    .put("type", "image")
                    .put("source", new JSONObject()
                            .put("type", "url")
                            .put("url", "https://i.ytimg.com/vi/" + info.videoId + "/hqdefault.jpg")));
        }
        content.put(new JSONObject().put("type", "text").put("text", buildUserText(info, transcript)));

        JSONObject body = new JSONObject()
                .put("model", prefs.getModel())
                .put("max_tokens", 16000)
                .put("system", SYSTEM_PROMPT)
                .put("output_config", new JSONObject()
                        .put("effort", prefs.getEffort())
                        .put("format", new JSONObject().put("type", "json_schema").put("schema", schema())))
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", content)));

        if (usesFallbacks(prefs)) {
            body.put("fallbacks", "default");
        }
        return body;
    }

    /** Server-side refusal fallbacks exist for Opus, not Haiku. */
    private static boolean usesFallbacks(DeclickbaitPrefs prefs) {
        return DeclickbaitPrefs.MODEL_OPUS.equals(prefs.getModel());
    }

    private static class ImageException extends Exception {
        ImageException(String message) {
            super(message);
        }
    }

    private static synchronized OkHttpClient client() {
        if (sClient == null) {
            sClient = OkHttpManager.instance().getClient().newBuilder()
                    .readTimeout(90, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
        }
        return sClient;
    }

    private static JSONObject post(DeclickbaitPrefs prefs, JSONObject body) throws ClaudeException, ImageException, JSONException {
        Request.Builder request = new Request.Builder()
                .url(URL)
                .header("x-api-key", prefs.getApiKey())
                .header("anthropic-version", API_VERSION)
                .post(RequestBody.create(JSON, body.toString()));
        if (usesFallbacks(prefs)) {
            request.header("anthropic-beta", FALLBACK_BETA);
        }

        String text;
        int code;
        try (Response response = client().newCall(request.build()).execute()) {
            code = response.code();
            ResponseBody responseBody = response.body();
            text = responseBody != null ? responseBody.string() : "";
        } catch (IOException e) {
            throw new ClaudeException("Couldn't reach Claude. Check the internet connection.", false);
        }

        if (code == 200) {
            return new JSONObject(text);
        }

        String apiMessage = "";
        try {
            JSONObject error = new JSONObject(text).optJSONObject("error");
            apiMessage = error != null ? error.optString("message") : "";
        } catch (JSONException ignored) {
            // Non-JSON error body
        }
        Log.e(TAG, "Claude API error %s: %s", code, apiMessage);

        if (code == 400 && apiMessage.toLowerCase().contains("image")) {
            throw new ImageException(apiMessage);
        }
        if (code == 401) {
            throw new ClaudeException("Claude rejected the API key. Check it in Settings.", true);
        }
        if (code == 403) {
            throw new ClaudeException("This API key isn't allowed to use " + prefs.getModel() + ".", true);
        }
        if (code == 400 && apiMessage.toLowerCase().contains("credit")) {
            throw new ClaudeException("Your Claude API account is out of credit.", true);
        }
        if (code == 429) {
            throw new ClaudeException("Claude is rate limiting requests. Try again in a minute.", false);
        }
        if (code == 529 || code >= 500) {
            throw new ClaudeException("Claude is overloaded right now. Try again shortly.", false);
        }
        throw new ClaudeException("Claude returned an error (" + code + "): " + apiMessage, false);
    }

    static DeclickbaitResult parseResponse(DeclickbaitPrefs prefs, VideoInfo info, Transcript transcript, JSONObject response)
            throws ClaudeException, JSONException {
        String stopReason = response.optString("stop_reason");
        if ("refusal".equals(stopReason)) {
            throw new ClaudeException("Claude declined to answer for this video.", false);
        }
        if ("max_tokens".equals(stopReason)) {
            throw new ClaudeException("Claude's answer was cut off. Try again.", false);
        }

        StringBuilder text = new StringBuilder();
        JSONArray content = response.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.optJSONObject(i);
                if (block != null && "text".equals(block.optString("type"))) {
                    text.append(block.optString("text"));
                }
            }
        }
        if (text.length() == 0) {
            throw new ClaudeException("Claude didn't return an answer.", false);
        }

        String servedBy = response.optString("model", prefs.getModel());
        DeclickbaitResult result = DeclickbaitResult.fromClaude(info.videoId, servedBy, new JSONObject(text.toString()), transcript);
        result.durationMs = info.durationMs;
        return result;
    }

    /** Smallest possible request, for the "Test key" setting. Blocking. Returns null on success, else a message. */
    public static String testKey(DeclickbaitPrefs prefs) {
        if (!prefs.hasApiKey()) {
            return "No key set.";
        }
        try {
            JSONObject body = new JSONObject()
                    .put("model", DeclickbaitPrefs.MODEL_HAIKU)
                    .put("max_tokens", 16)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "Reply with OK.")));
            Request request = new Request.Builder()
                    .url(URL)
                    .header("x-api-key", prefs.getApiKey())
                    .header("anthropic-version", API_VERSION)
                    .post(RequestBody.create(JSON, body.toString()))
                    .build();
            try (Response response = client().newCall(request).execute()) {
                if (response.code() == 200) return null;
                if (response.code() == 401) return "Claude rejected this key.";
                ResponseBody responseBody = response.body();
                String msg = "";
                if (responseBody != null) {
                    JSONObject error = new JSONObject(responseBody.string()).optJSONObject("error");
                    msg = error != null ? error.optString("message") : "";
                }
                return "Error " + response.code() + (msg.isEmpty() ? "" : ": " + msg);
            }
        } catch (IOException e) {
            return "Couldn't reach Claude. Check the internet connection.";
        } catch (JSONException e) {
            return "Unexpected reply from Claude.";
        }
    }
}
