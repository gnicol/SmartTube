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
    private static final int VERSION = 3;
    /** Transcript sent with each research claim: a little before it is cited, and more after. */
    private static final long CONTEXT_BEFORE_MS = 30_000;
    private static final long CONTEXT_AFTER_MS = 90_000;

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

    /** A claim the video bases on research, flagged by the first pass for an optional check. */
    public static class ResearchClaim {
        public final long ms;
        public final String label;
        public final String claim;
        public final String citedAs;
        /** The transcript around the claim, kept so the check can run later from a saved result. */
        public final String context;

        public ResearchClaim(long ms, String label, String claim, String citedAs, String context) {
            this.ms = ms;
            this.label = label;
            this.claim = claim;
            this.citedAs = citedAs;
            this.context = context;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("ms", ms).put("label", label).put("claim", claim).put("citedAs", citedAs).put("context", context);
        }

        static ResearchClaim fromJson(JSONObject j) {
            return new ResearchClaim(j.optLong("ms"), j.optString("label"), j.optString("claim"), j.optString("citedAs"), j.optString("context"));
        }
    }

    /** One claim checked against its paper. */
    public static class Study {
        public static final String MATCHES = "matches";
        public static final String OVERSTATES = "overstates";
        public static final String MISREPRESENTS = "misrepresents";
        public static final String NOT_FOUND = "not_found";

        public long ms;
        public String label;
        public String videoClaim;
        public String citation;
        public String citationNote;
        public String url;
        public String paperFinding;
        public String verdict;
        public String strength;
        public String design;
        public String caveat;

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("ms", ms).put("label", label).put("videoClaim", videoClaim).put("citation", citation)
                    .put("citationNote", citationNote).put("url", url).put("paperFinding", paperFinding).put("verdict", verdict)
                    .put("strength", strength).put("design", design).put("caveat", caveat);
        }

        static Study fromJson(JSONObject j) {
            Study s = new Study();
            s.ms = j.optLong("ms");
            s.label = j.optString("label");
            s.videoClaim = j.optString("videoClaim");
            s.citation = j.optString("citation");
            s.citationNote = j.optString("citationNote");
            s.url = j.optString("url");
            s.paperFinding = j.optString("paperFinding");
            s.verdict = j.optString("verdict", NOT_FOUND);
            s.strength = j.optString("strength", "unknown");
            s.design = j.optString("design");
            s.caveat = j.optString("caveat");
            return s;
        }
    }

    /** The second pass: what the papers behind the video's claims actually show. */
    public static class ResearchCheck {
        public String model;
        public String overall;
        /** In the order of the claims. */
        public final List<Study> studies = new ArrayList<>();

        static ResearchCheck fromClaude(String model, JSONObject json, DeclickbaitResult result) {
            ResearchCheck check = new ResearchCheck();
            check.model = model;
            check.overall = json.optString("overall");
            JSONArray studies = json.optJSONArray("studies");
            if (studies != null) {
                for (int i = 0; i < studies.length(); i++) {
                    JSONObject j = studies.optJSONObject(i);
                    if (j == null) continue;
                    int index = j.optInt("claim_number", i + 1) - 1;
                    if (index < 0 || index >= result.researchClaims.size()) continue;
                    ResearchClaim claim = result.researchClaims.get(index);
                    Study s = new Study();
                    s.ms = claim.ms;
                    s.label = claim.label;
                    s.videoClaim = claim.claim;
                    s.citation = j.optString("citation");
                    s.citationNote = j.optString("citation_note");
                    s.url = j.optString("url");
                    s.paperFinding = j.optString("paper_finding");
                    s.verdict = j.optString("verdict", Study.NOT_FOUND);
                    s.strength = j.optString("strength", "unknown");
                    s.design = j.optString("design");
                    s.caveat = j.optString("caveat");
                    check.studies.add(s);
                }
            }
            Collections.sort(check.studies, (a, b) -> a.ms < b.ms ? -1 : (a.ms == b.ms ? 0 : 1));
            return check;
        }

        JSONObject toJson() throws JSONException {
            JSONArray studies = new JSONArray();
            for (Study s : this.studies) {
                studies.put(s.toJson());
            }
            return new JSONObject().put("model", model).put("overall", overall).put("studies", studies);
        }

        static ResearchCheck fromJson(JSONObject j) {
            ResearchCheck check = new ResearchCheck();
            check.model = j.optString("model");
            check.overall = j.optString("overall");
            JSONArray studies = j.optJSONArray("studies");
            if (studies != null) {
                for (int i = 0; i < studies.length(); i++) {
                    JSONObject s = studies.optJSONObject(i);
                    if (s != null) check.studies.add(Study.fromJson(s));
                }
            }
            return check;
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
    /** Empty when the video doesn't lean on research; then no check is offered. */
    public final List<ResearchClaim> researchClaims = new ArrayList<>();
    /** Set once the viewer has asked for the research check. */
    @Nullable
    public ResearchCheck research;
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

        JSONArray claims = json.optJSONArray("research_claims");
        if (claims != null) {
            for (int i = 0; i < claims.length(); i++) {
                JSONObject c = claims.optJSONObject(i);
                if (c == null || c.optString("claim").isEmpty()) continue;
                long ms = transcript.snapToLine(c.optString("at"));
                // Without a readable time there is no transcript to send with it and no honest place to jump to.
                if (ms < 0) continue;
                String context = transcript.toPromptText(Math.max(0, ms - CONTEXT_BEFORE_MS), ms + CONTEXT_AFTER_MS);
                String label = c.optString("label");
                result.researchClaims.add(new ResearchClaim(ms, label.isEmpty() ? c.optString("claim") : label,
                        c.optString("claim"), c.optString("cited_as"), context));
            }
        }
        Collections.sort(result.researchClaims, (a, b) -> a.ms < b.ms ? -1 : (a.ms == b.ms ? 0 : 1));
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
        JSONArray claims = new JSONArray();
        for (ResearchClaim c : researchClaims) {
            claims.put(c.toJson());
        }
        json.put("researchClaims", claims);
        if (research != null) {
            json.put("research", research.toJson());
        }
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
        JSONArray claims = json.optJSONArray("researchClaims");
        if (claims != null) {
            for (int i = 0; i < claims.length(); i++) {
                JSONObject c = claims.optJSONObject(i);
                if (c != null) result.researchClaims.add(ResearchClaim.fromJson(c));
            }
        }
        JSONObject research = json.optJSONObject("research");
        if (research != null) {
            result.research = ResearchCheck.fromJson(research);
        }
        return result;
    }
}
