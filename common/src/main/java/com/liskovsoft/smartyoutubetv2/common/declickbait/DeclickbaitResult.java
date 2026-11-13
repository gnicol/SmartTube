package com.liskovsoft.smartyoutubetv2.common.declickbait;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What Claude found for one video: an overview to read, and separate moments to jump to. */
public class DeclickbaitResult {
    public static final String SUPPORTED_YES = "yes";
    public static final String SUPPORTED_PARTLY = "partly";
    public static final String SUPPORTED_NO = "no";
    /** Bumped when the saved shape changes, so results saved by an older build are asked for again. */
    private static final int VERSION = 2;

    /** A place worth jumping to, snapped to a real transcript line. */
    public static class Moment {
        public final long ms;
        /** Terse: it is also the card title in the player's Key moments row. */
        public final String label;
        /** Where the title's promise is delivered. */
        public final boolean answersTitle;

        public Moment(long ms, String label, boolean answersTitle) {
            this.ms = ms;
            this.label = label;
            this.answersTitle = answersTitle;
        }
    }

    public String videoId;
    public String model;
    /** The answer the title teases, or the video's main takeaway. */
    public String headline;
    /** Claude's overview in limited Markdown; see {@link SummaryText}. */
    public String summary;
    public String titleCheck;
    public String titleSupported;
    /** In time order. */
    public final List<Moment> moments = new ArrayList<>();
    public int wordCount;
    public long durationMs;

    /** Parse Claude's structured output. */
    static DeclickbaitResult fromClaude(String videoId, String model, JSONObject json, Transcript transcript) {
        DeclickbaitResult result = new DeclickbaitResult();
        result.videoId = videoId;
        result.model = model;
        result.headline = json.optString("headline");
        result.summary = json.optString("summary");
        result.titleCheck = json.optString("title_check");
        result.titleSupported = json.optString("title_supported", SUPPORTED_PARTLY);
        result.wordCount = transcript.getWordCount();

        JSONArray moments = json.optJSONArray("moments");
        if (moments != null) {
            boolean answerSeen = false;
            for (int i = 0; i < moments.length(); i++) {
                JSONObject m = moments.optJSONObject(i);
                if (m == null) continue;
                long ms = transcript.snapToLine(m.optString("at"));
                String label = m.optString("label");
                // A jump with no readable time or no name is no use.
                if (ms < 0 || label.isEmpty()) continue;
                boolean answersTitle = m.optBoolean("answers_title") && !answerSeen;
                answerSeen |= answersTitle;
                result.moments.add(new Moment(ms, label, answersTitle));
            }
        }
        Collections.sort(result.moments, (a, b) -> a.ms < b.ms ? -1 : (a.ms == b.ms ? 0 : 1));
        return result;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("v", VERSION);
        json.put("videoId", videoId);
        json.put("model", model);
        json.put("headline", headline);
        json.put("summary", summary);
        json.put("titleCheck", titleCheck);
        json.put("titleSupported", titleSupported);
        json.put("wordCount", wordCount);
        json.put("durationMs", durationMs);
        JSONArray moments = new JSONArray();
        for (Moment m : this.moments) {
            moments.put(new JSONObject().put("ms", m.ms).put("label", m.label).put("answersTitle", m.answersTitle));
        }
        json.put("moments", moments);
        return json;
    }

    @Nullable
    static DeclickbaitResult fromJson(JSONObject json) {
        if (json == null || json.optInt("v") != VERSION || json.optString("headline").isEmpty()) {
            return null;
        }
        DeclickbaitResult result = new DeclickbaitResult();
        result.videoId = json.optString("videoId");
        result.model = json.optString("model");
        result.headline = json.optString("headline");
        result.summary = json.optString("summary");
        result.titleCheck = json.optString("titleCheck");
        result.titleSupported = json.optString("titleSupported", SUPPORTED_PARTLY);
        result.wordCount = json.optInt("wordCount");
        result.durationMs = json.optLong("durationMs");
        JSONArray moments = json.optJSONArray("moments");
        if (moments != null) {
            for (int i = 0; i < moments.length(); i++) {
                JSONObject m = moments.optJSONObject(i);
                if (m != null) result.moments.add(new Moment(m.optLong("ms"), m.optString("label"), m.optBoolean("answersTitle")));
            }
        }
        return result;
    }
}
