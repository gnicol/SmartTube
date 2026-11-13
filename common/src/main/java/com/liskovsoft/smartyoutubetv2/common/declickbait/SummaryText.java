package com.liskovsoft.smartyoutubetv2.common.declickbait;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns Claude's summary into styled text for a TextView. Handles only what the prompt allows (paragraphs,
 * "- " bullets, "## " headings, **bold**) plus *italic*; anything else is shown as written.
 */
class SummaryText {
    private static final int COLOR_ACCENT = 0xFFF5B83D;
    private static final int COLOR_STRONG = 0xFFFFFFFF;
    private static final int COLOR_FOOTNOTE = 0xFFA3A8B3;
    private static final int COLOR_MATCHES = 0xFF7BD88F;
    private static final int COLOR_MISREPRESENTS = 0xFFFF8A80;
    private static final Pattern BULLET = Pattern.compile("^[-*•]\\s+(.*)$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");
    private static final Pattern INLINE = Pattern.compile("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*");
    private static final int FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;

    /**
     * @param footnoteLead bold opening of the footnote, e.g. "Title holds up."
     * @param footnote     shown muted after the summary; skipped when empty
     */
    static CharSequence format(String summary, String footnoteLead, String footnote, float density) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        boolean lastWasBullet = false;

        for (String raw : (summary != null ? summary : "").split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher bullet = BULLET.matcher(line);
            Matcher heading = HEADING.matcher(line);
            boolean isBullet = bullet.matches();
            startBlock(out, isBullet && lastWasBullet);
            int start = out.length();

            if (isBullet) {
                appendInline(out, bullet.group(1));
                out.setSpan(new BulletSpan((int) (10 * density), COLOR_ACCENT), start, out.length(), FLAGS);
            } else if (heading.matches()) {
                out.append(heading.group(1).replace("*", "").toUpperCase(Locale.US));
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), FLAGS);
                out.setSpan(new ForegroundColorSpan(COLOR_ACCENT), start, out.length(), FLAGS);
                out.setSpan(new RelativeSizeSpan(0.82f), start, out.length(), FLAGS);
            } else {
                appendInline(out, line);
            }
            lastWasBullet = isBullet;
        }

        if (footnote != null && !footnote.isEmpty()) {
            startBlock(out, false);
            int start = out.length();
            out.append(footnoteLead);
            out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), FLAGS);
            out.append("  ").append(footnote);
            out.setSpan(new ForegroundColorSpan(COLOR_FOOTNOTE), start, out.length(), FLAGS);
            out.setSpan(new RelativeSizeSpan(0.88f), start, out.length(), FLAGS);
        }
        return out;
    }

    /** The research check as text: the overall verdict, then one card per claim. */
    static CharSequence formatResearch(DeclickbaitResult.ResearchCheck check) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (check.overall != null && !check.overall.isEmpty()) {
            out.append(check.overall);
        }
        for (DeclickbaitResult.Study s : check.studies) {
            startBlock(out, false);
            startBlock(out, true);

            // "2:31  Migraine 40–50%"
            int start = out.length();
            out.append(Transcript.formatTime(s.ms));
            out.setSpan(new ForegroundColorSpan(COLOR_ACCENT), start, out.length(), FLAGS);
            out.append("   ").append(s.label);
            out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), FLAGS);
            out.setSpan(new ForegroundColorSpan(COLOR_STRONG), start + Transcript.formatTime(s.ms).length(), out.length(), FLAGS);

            line(out, "Video: ", s.videoClaim, 0);

            startBlock(out, true);
            start = out.length();
            out.append(verdictText(s.verdict)).append(" · ").append(strengthText(s.strength));
            out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), FLAGS);
            out.setSpan(new ForegroundColorSpan(verdictColor(s.verdict)), start, out.length(), FLAGS);

            if (!s.citation.isEmpty()) {
                line(out, "Paper: ", s.citation + (s.citationNote.isEmpty() ? "" : " (" + s.citationNote + ")"), 0);
            }
            line(out, "Found: ", s.paperFinding, 0);
            line(out, "Study: ", s.design, COLOR_FOOTNOTE);
            line(out, "Catch: ", s.caveat, COLOR_FOOTNOTE);
        }
        startBlock(out, false);
        int start = out.length();
        out.append("Found by Claude with web search. Look up the paper before acting on health advice.");
        out.setSpan(new ForegroundColorSpan(COLOR_FOOTNOTE), start, out.length(), FLAGS);
        out.setSpan(new RelativeSizeSpan(0.82f), start, out.length(), FLAGS);
        return out;
    }

    /** The research progress: what is happening, then the steps so far with the newest one brightest. */
    static CharSequence formatProgress(String intro, java.util.List<String> steps) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        int start = out.length();
        out.append(intro);
        out.setSpan(new ForegroundColorSpan(COLOR_FOOTNOTE), start, out.length(), FLAGS);
        for (int i = 0; i < steps.size(); i++) {
            startBlock(out, i > 0);
            boolean newest = i == steps.size() - 1;
            start = out.length();
            out.append(newest ? "▸  " : "✓  ").append(steps.get(i));
            out.setSpan(new ForegroundColorSpan(newest ? COLOR_STRONG : COLOR_FOOTNOTE), start, out.length(), FLAGS);
            if (newest) {
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), FLAGS);
            }
        }
        return out;
    }

    private static void line(SpannableStringBuilder out, String lead, String text, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        startBlock(out, true);
        int start = out.length();
        out.append(lead);
        out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), FLAGS);
        appendInline(out, text);
        if (color != 0) {
            out.setSpan(new ForegroundColorSpan(color), start, out.length(), FLAGS);
        }
    }

    private static String verdictText(String verdict) {
        switch (verdict) {
            case DeclickbaitResult.Study.MATCHES: return "Matches the paper";
            case DeclickbaitResult.Study.OVERSTATES: return "Overstates it";
            case DeclickbaitResult.Study.MISREPRESENTS: return "Misrepresents it";
            default: return "Paper not found";
        }
    }

    private static int verdictColor(String verdict) {
        switch (verdict) {
            case DeclickbaitResult.Study.MATCHES: return COLOR_MATCHES;
            case DeclickbaitResult.Study.OVERSTATES: return COLOR_ACCENT;
            case DeclickbaitResult.Study.MISREPRESENTS: return COLOR_MISREPRESENTS;
            default: return COLOR_FOOTNOTE;
        }
    }

    private static String strengthText(String strength) {
        switch (strength) {
            case "strong": return "strong evidence";
            case "moderate": return "moderate evidence";
            case "weak": return "weak evidence";
            case "very_weak": return "very weak evidence";
            default: return "evidence unclear";
        }
    }

    /** Blocks are separated by a part-height blank line; consecutive bullets sit closer together. */
    private static void startBlock(SpannableStringBuilder out, boolean tight) {
        if (out.length() == 0) {
            return;
        }
        out.append('\n');
        if (!tight) {
            int gap = out.length();
            out.append('\n');
            out.setSpan(new RelativeSizeSpan(0.45f), gap, gap + 1, FLAGS);
        }
    }

    private static void appendInline(SpannableStringBuilder out, String text) {
        Matcher m = INLINE.matcher(text);
        int pos = 0;
        while (m.find()) {
            out.append(text, pos, m.start());
            int start = out.length();
            if (m.group(1) != null) {
                out.append(m.group(1));
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), FLAGS);
                out.setSpan(new ForegroundColorSpan(COLOR_STRONG), start, out.length(), FLAGS);
            } else {
                out.append(m.group(2));
                out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(), FLAGS);
            }
            pos = m.end();
        }
        out.append(text, pos, text.length());
    }
}
