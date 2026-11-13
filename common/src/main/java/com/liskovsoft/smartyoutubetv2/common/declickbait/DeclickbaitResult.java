package com.liskovsoft.smartyoutubetv2.common.declickbait;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What Claude found for one video, with every timestamp snapped to a real transcript line. */
public class DeclickbaitResult {
    public static final String FOUND_CLEAR = "clear";
    public static final String FOUND_PARTIAL = "partial";
    public static final String FOUND_NOT_FOUND = "not_found";

    public static class Moment {
        public final long ms;
        public final String label;

        public Moment(long ms, String label) {
            this.ms = ms;
            this.label = label;
        }
    }

    public String videoId;
    public String model;
    public String tease;
    public String answer;
    public String answerDetail;
    public String summary;
    public String titleCheck;
    public String found;
    /** -1 when the answer isn't in the video. */
    public long answerAtMs = -1;
    /** Extra moments worth watching, in time order. The answer itself is not repeated here. */
    public final List<Moment> moments = new ArrayList<>();
    public final List<String> decoys = new ArrayList<>();
    public int wordCount;
    public long durationMs;

    public boolean isAnswerFound() {
        return !FOUND_NOT_FOUND.equals(found) && answerAtMs >= 0;
    }

    /** Parse Claude's structured output. */
    static DeclickbaitResult fromClaude(String videoId, String model, JSONObject json, Transcript transcript) {
        DeclickbaitResult result = new DeclickbaitResult();
        result.videoId = videoId;
        result.model = model;
        result.tease = json.optString("tease");
        result.answer = json.optString("answer");
        result.answerDetail = json.optString("answer_detail");
        result.summary = json.optString("summary");
        result.titleCheck = json.optString("title_check");
        result.found = json.optString("found", FOUND_CLEAR);
        result.answerAtMs = transcript.snapToLine(json.optString("answer_at"));
        result.wordCount = transcript.getWordCount();

        JSONArray moments = json.optJSONArray("moments");
        if (moments != null) {
            for (int i = 0; i < moments.length(); i++) {
                JSONObject m = moments.optJSONObject(i);
                if (m == null) continue;
                long ms = transcript.snapToLine(m.optString("at"));
                String label = m.optString("label");
                // Drop invalid stamps and anything that just repeats the answer's position.
                if (ms < 0 || label.isEmpty() || Math.abs(ms - result.answerAtMs) < Transcript.GROUP_MS) continue;
                result.moments.add(new Moment(ms, label));
            }
        }
        Collections.sort(result.moments, (a, b) -> a.ms < b.ms ? -1 : (a.ms == b.ms ? 0 : 1));

        JSONArray decoys = json.optJSONArray("decoys");
        if (decoys != null) {
            for (int i = 0; i < decoys.length(); i++) {
                String d = decoys.optString(i);
                if (!d.isEmpty()) result.decoys.add(d);
            }
        }
        return result;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("videoId", videoId);
        json.put("model", model);
        json.put("tease", tease);
        json.put("answer", answer);
        json.put("answerDetail", answerDetail);
        json.put("summary", summary);
        json.put("titleCheck", titleCheck);
        json.put("found", found);
        json.put("answerAtMs", answerAtMs);
        json.put("wordCount", wordCount);
        json.put("durationMs", durationMs);
        JSONArray moments = new JSONArray();
        for (Moment m : this.moments) {
            moments.put(new JSONObject().put("ms", m.ms).put("label", m.label));
        }
        json.put("moments", moments);
        json.put("decoys", new JSONArray(decoys));
        return json;
    }

    @Nullable
    static DeclickbaitResult fromJson(JSONObject json) {
        if (json == null || json.optString("answer").isEmpty()) {
            return null;
        }
        DeclickbaitResult result = new DeclickbaitResult();
        result.videoId = json.optString("videoId");
        result.model = json.optString("model");
        result.tease = json.optString("tease");
        result.answer = json.optString("answer");
        result.answerDetail = json.optString("answerDetail");
        result.summary = json.optString("summary");
        result.titleCheck = json.optString("titleCheck");
        result.found = json.optString("found", FOUND_CLEAR);
        result.answerAtMs = json.optLong("answerAtMs", -1);
        result.wordCount = json.optInt("wordCount");
        result.durationMs = json.optLong("durationMs");
        JSONArray moments = json.optJSONArray("moments");
        if (moments != null) {
            for (int i = 0; i < moments.length(); i++) {
                JSONObject m = moments.optJSONObject(i);
                if (m != null) result.moments.add(new Moment(m.optLong("ms"), m.optString("label")));
            }
        }
        JSONArray decoys = json.optJSONArray("decoys");
        if (decoys != null) {
            for (int i = 0; i < decoys.length(); i++) result.decoys.add(decoys.optString(i));
        }
        return result;
    }
}
