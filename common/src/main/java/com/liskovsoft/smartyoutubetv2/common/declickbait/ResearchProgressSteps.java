package com.liskovsoft.smartyoutubetv2.common.declickbait;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/** Turns the blocks of a streamed research check into short, readable steps, each reported once. */
class ResearchProgressSteps implements MessageStream.Listener {
    /** Paper titles can run to a paragraph; a TV line or two is plenty. */
    private static final int MAX_TITLE = 90;
    private final ClaudeClient.ResearchProgress mProgress;
    private final Set<String> mSeen = new HashSet<>();
    private boolean mWriting;

    ResearchProgressSteps(ClaudeClient.ResearchProgress progress) {
        mProgress = progress;
    }

    @Override
    public void onBlockStart(JSONObject block) {
        switch (block.optString("type")) {
            case "thinking":
                step("Working out where to look");
                break;
            case "server_tool_use":
                if ("code_execution".equals(block.optString("name"))) {
                    // Claude batches its searches in a short program; they run once it is written.
                    step("Planning the searches");
                } else {
                    // Searches run from that code arrive complete here; direct calls fill in by the block's end.
                    describeToolUse(block);
                }
                break;
            case "web_search_tool_result": {
                JSONArray results = block.optJSONArray("content");
                JSONObject top = results != null && results.length() > 0 ? results.optJSONObject(0) : null;
                if (top != null && !top.optString("title").isEmpty()) {
                    String host = host(top.optString("url"));
                    String title = top.optString("title").trim();
                    if (title.length() > MAX_TITLE) {
                        title = title.substring(0, MAX_TITLE).trim() + "…";
                    }
                    step("Found: " + title + (host.isEmpty() ? "" : " (" + host + ")"));
                }
                break;
            }
            case "text":
                if (!mWriting) {
                    mWriting = true;
                    step("Writing up the findings");
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onBlockStop(JSONObject block) {
        if ("server_tool_use".equals(block.optString("type"))) {
            describeToolUse(block);
        }
    }

    private void describeToolUse(JSONObject block) {
        JSONObject input = block.optJSONObject("input");
        if (input == null) {
            return;
        }
        String name = block.optString("name");
        if ("web_search".equals(name) && !input.optString("query").isEmpty()) {
            step("Searching: " + input.optString("query"));
        } else if ("web_fetch".equals(name) && !input.optString("url").isEmpty()) {
            step("Reading " + host(input.optString("url")));
        }
    }

    private void step(String text) {
        if (mSeen.add(text)) {
            mProgress.onStep(text);
        }
    }

    static String host(String url) {
        try {
            String host = new java.net.URI(url).getHost();
            return host == null ? "" : host.startsWith("www.") ? host.substring(4) : host;
        } catch (java.net.URISyntaxException e) {
            return "";
        }
    }
}
