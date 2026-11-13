package com.liskovsoft.smartyoutubetv2.common.declickbait;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Reads a streamed Messages API response (server-sent events). Rebuilds the final message in the same shape as a
 * non-streamed response, so the content can be sent back unchanged after a pause, and reports each content block as
 * it starts and stops.
 */
class MessageStream {
    interface Listener {
        void onBlockStart(JSONObject block);

        void onBlockStop(JSONObject block);
    }

    /** An "error" event in the middle of the stream, e.g. overloaded. */
    static class StreamError extends Exception {
        final String errorType;

        StreamError(String errorType, String message) {
            super(message);
            this.errorType = errorType;
        }
    }

    private final Listener mListener;
    private boolean mStarted;
    private boolean mStopped;
    private JSONObject mMessage = new JSONObject();
    private final List<JSONObject> mBlocks = new ArrayList<>();
    private final List<StringBuilder> mPartialJson = new ArrayList<>();

    private MessageStream(Listener listener) {
        mListener = listener;
    }

    /**
     * Blocking until message_stop, which ends the message even if the connection lingers. A stream that ends before
     * it is incomplete and throws, so a cut-off answer is never taken as finished.
     */
    static JSONObject read(BufferedReader reader, Listener listener) throws IOException, JSONException, StreamError {
        MessageStream stream = new MessageStream(listener);
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    stream.onEvent(new JSONObject(data.toString()));
                    data.setLength(0);
                    if (stream.mStopped) {
                        return stream.message();
                    }
                }
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring(5).trim());
            }
            // "event:" lines repeat the type that the data carries; comments and other fields are ignored.
        }
        if (data.length() > 0) {
            stream.onEvent(new JSONObject(data.toString()));
        }
        if (!stream.mStarted || !stream.mStopped) {
            throw new IOException("stream ended before message_stop");
        }
        return stream.message();
    }

    private void onEvent(JSONObject event) throws JSONException, StreamError {
        switch (event.optString("type")) {
            case "message_start":
                JSONObject message = event.optJSONObject("message");
                if (message != null) {
                    mMessage = message;
                }
                mStarted = true;
                break;
            case "message_stop":
                mStopped = true;
                break;
            case "content_block_start": {
                int index = event.optInt("index");
                JSONObject block = event.optJSONObject("content_block");
                if (block == null) {
                    block = new JSONObject();
                }
                while (mBlocks.size() <= index) {
                    mBlocks.add(null);
                    mPartialJson.add(new StringBuilder());
                }
                mBlocks.set(index, block);
                mListener.onBlockStart(block);
                break;
            }
            case "content_block_delta":
                applyDelta(event.optInt("index"), event.optJSONObject("delta"));
                break;
            case "content_block_stop": {
                int index = event.optInt("index");
                JSONObject block = blockAt(index);
                if (block == null) {
                    break;
                }
                String partial = mPartialJson.get(index).toString();
                if (!partial.isEmpty()) {
                    block.put("input", new JSONObject(partial));
                }
                mListener.onBlockStop(block);
                break;
            }
            case "message_delta": {
                JSONObject delta = event.optJSONObject("delta");
                if (delta != null) {
                    // stop_reason, stop_sequence, container, stop_details…
                    for (Iterator<String> keys = delta.keys(); keys.hasNext(); ) {
                        String key = keys.next();
                        mMessage.put(key, delta.get(key));
                    }
                }
                JSONObject usage = event.optJSONObject("usage");
                if (usage != null) {
                    mMessage.put("usage", usage);
                }
                break;
            }
            case "error": {
                JSONObject error = event.optJSONObject("error");
                throw new StreamError(error != null ? error.optString("type") : "",
                        error != null ? error.optString("message") : "stream error");
            }
            default:
                // ping
                break;
        }
    }

    private void applyDelta(int index, JSONObject delta) throws JSONException {
        JSONObject block = blockAt(index);
        if (block == null || delta == null) {
            return;
        }
        switch (delta.optString("type")) {
            case "text_delta":
                block.put("text", block.optString("text") + delta.optString("text"));
                break;
            case "input_json_delta":
                mPartialJson.get(index).append(delta.optString("partial_json"));
                break;
            case "thinking_delta":
                block.put("thinking", block.optString("thinking") + delta.optString("thinking"));
                break;
            case "signature_delta":
                block.put("signature", delta.optString("signature"));
                break;
            case "citations_delta": {
                JSONArray citations = block.optJSONArray("citations");
                if (citations == null) {
                    citations = new JSONArray();
                    block.put("citations", citations);
                }
                citations.put(delta.opt("citation"));
                break;
            }
            default:
                break;
        }
    }

    private JSONObject blockAt(int index) {
        return index >= 0 && index < mBlocks.size() ? mBlocks.get(index) : null;
    }

    private JSONObject message() throws JSONException {
        JSONArray content = new JSONArray();
        for (JSONObject block : mBlocks) {
            if (block != null) {
                content.put(block);
            }
        }
        mMessage.put("content", content);
        return mMessage;
    }
}
