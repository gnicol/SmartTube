package com.liskovsoft.smartyoutubetv2.common.declickbait;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Caption text grouped into short timestamped lines, the way it's sent to Claude.
 * Claude must copy timestamps from these lines, so the app can snap any jump to a real line.
 */
public class Transcript {
    /** Lines start at least this far apart. Short enough for accurate jumps, long enough to keep tokens down. */
    public static final long GROUP_MS = 8_000;

    public static class Line {
        public final long startMs;
        public final String text;

        Line(long startMs, String text) {
            this.startMs = startMs;
            this.text = text;
        }
    }

    private final List<Line> mLines;
    private final String mLanguage;

    private Transcript(List<Line> lines, String language) {
        mLines = Collections.unmodifiableList(lines);
        mLanguage = language;
    }

    public List<Line> getLines() {
        return mLines;
    }

    public String getLanguage() {
        return mLanguage;
    }

    public boolean isEmpty() {
        return mLines.isEmpty();
    }

    public int getWordCount() {
        int count = 0;
        for (Line line : mLines) {
            count += line.text.split("\\s+").length;
        }
        return count;
    }

    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        for (Line line : mLines) {
            sb.append('[').append(formatTime(line.startMs)).append("] ").append(line.text).append('\n');
        }
        return sb.toString();
    }

    /** Timestamped lines starting within [fromMs, toMs], in the same form as {@link #toPromptText()}. */
    public String toPromptText(long fromMs, long toMs) {
        StringBuilder sb = new StringBuilder();
        for (Line line : mLines) {
            if (line.startMs >= fromMs && line.startMs <= toMs) {
                sb.append('[').append(formatTime(line.startMs)).append("] ").append(line.text).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Turn a "m:ss" / "h:mm:ss" stamp from Claude into the start of the transcript line at or before it.
     * Returns -1 if the stamp can't be parsed.
     */
    public long snapToLine(String stamp) {
        long ms = parseTime(stamp);
        if (ms < 0) {
            return -1;
        }
        // Stamps are whole seconds (formatTime truncates), so "0:11" names a line starting anywhere in 11.000–11.999 s.
        long endOfSecond = ms + 999;
        long best = mLines.isEmpty() ? ms : mLines.get(0).startMs;
        for (Line line : mLines) {
            if (line.startMs <= endOfSecond) {
                best = line.startMs;
            } else {
                break;
            }
        }
        return best;
    }

    public static String formatTime(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%d:%02d", m, s);
    }

    public static long parseTime(String stamp) {
        if (stamp == null) {
            return -1;
        }
        String[] parts = stamp.trim().split(":");
        if (parts.length < 2 || parts.length > 3) {
            return -1;
        }
        try {
            long total = 0;
            for (String part : parts) {
                total = total * 60 + Long.parseLong(part.trim());
            }
            return total * 1000;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Collects caption cues and groups them into lines of roughly {@link #GROUP_MS}. */
    static class Builder {
        private final List<Line> mLines = new ArrayList<>();
        private final String mLanguage;
        private final StringBuilder mBuf = new StringBuilder();
        private long mStartMs = -1;
        private String mLastCue;

        Builder(String language) {
            mLanguage = language;
        }

        void addCue(long startMs, String text) {
            if (text == null) {
                return;
            }
            String clean = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
            // Auto-generated captions repeat the previous line in rolling cues.
            if (clean.isEmpty() || clean.equals(mLastCue)) {
                return;
            }
            mLastCue = clean;

            // A cue that starts a group-length after the current line begins its own line, so every line's stamp
            // is within GROUP_MS of the words in it (appending first would label it up to a gap too early).
            if (mStartMs >= 0 && startMs - mStartMs >= GROUP_MS) {
                flush();
            }
            if (mStartMs < 0) {
                mStartMs = startMs;
            }
            if (mBuf.length() > 0) {
                mBuf.append(' ');
            }
            mBuf.append(clean);
        }

        private void flush() {
            if (mBuf.length() > 0) {
                mLines.add(new Line(mStartMs, mBuf.toString()));
            }
            mBuf.setLength(0);
            mStartMs = -1;
        }

        Transcript build() {
            flush();
            return new Transcript(mLines, mLanguage);
        }
    }
}
