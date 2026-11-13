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
