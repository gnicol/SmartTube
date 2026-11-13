package com.liskovsoft.smartyoutubetv2.common.declickbait;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.common.R;

/**
 * Full-screen wash over the paused video. Opens straight into guessing while the transcript is read
 * and Claude works; Reveal lights up when the answer is ready. Nothing spoilery shows before Reveal.
 */
public class DeclickbaitOverlay {
    public interface Listener {
        void onJump(long positionMs);
        void onClosed(boolean jumped);
        void onOpenSettings();
        void onRevealed(DeclickbaitResult result);
    }

    private final Activity mActivity;
    private final Dialog mDialog;
    private final Listener mListener;
    private final boolean mGuessFirst;
    private final View mGuessPanel;
    private final View mAnswerPanel;
    private final TextView mKicker;
    private final TextView mPrompt;
    private final ProgressBar mSpinner1;
    private final TextView mStatus1;
    private final View mStatus2Row;
    private final ProgressBar mSpinner2;
    private final TextView mStatus2;
    private final Button mRevealButton;
    private final Button mSettingsButton;
    private final Button mCloseButton;
    private DeclickbaitResult mResult;
    private boolean mJumped;

    private DeclickbaitOverlay(Activity activity, String videoTitle, boolean guessFirst, Listener listener) {
        mActivity = activity;
        mListener = listener;
        mGuessFirst = guessFirst;

        mDialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        View root = LayoutInflater.from(activity).inflate(R.layout.declickbait_overlay, null);
        mDialog.setContentView(root);
        mDialog.setCancelable(true);
        mDialog.setOnDismissListener(dialog -> mListener.onClosed(mJumped));

        mGuessPanel = root.findViewById(R.id.declickbait_guess_panel);
        mAnswerPanel = root.findViewById(R.id.declickbait_answer_panel);
        mKicker = root.findViewById(R.id.declickbait_kicker);
        mPrompt = root.findViewById(R.id.declickbait_prompt);
        mSpinner1 = root.findViewById(R.id.declickbait_spinner1);
        mStatus1 = root.findViewById(R.id.declickbait_status1);
        mStatus2Row = root.findViewById(R.id.declickbait_status2_row);
        mSpinner2 = root.findViewById(R.id.declickbait_spinner2);
        mStatus2 = root.findViewById(R.id.declickbait_status2);
        mRevealButton = root.findViewById(R.id.declickbait_reveal);
        mSettingsButton = root.findViewById(R.id.declickbait_settings);
        mCloseButton = root.findViewById(R.id.declickbait_close);

        ((TextView) root.findViewById(R.id.declickbait_video_title)).setText(videoTitle);

        if (!guessFirst) {
            mKicker.setText(R.string.declickbait_kicker_working);
            mPrompt.setVisibility(View.GONE);
            mRevealButton.setVisibility(View.GONE);
        }

        mRevealButton.setOnClickListener(v -> reveal());
        mCloseButton.setOnClickListener(v -> dismiss());
        mSettingsButton.setOnClickListener(v -> {
            dismiss();
            mListener.onOpenSettings();
        });
    }

    public static DeclickbaitOverlay show(Activity activity, String videoTitle, boolean guessFirst, Listener listener) {
        DeclickbaitOverlay overlay = new DeclickbaitOverlay(activity, videoTitle, guessFirst, listener);
        overlay.mDialog.show();
        overlay.mCloseButton.requestFocus();
        return overlay;
    }

    public boolean isShowing() {
        return mDialog.isShowing();
    }

    public void dismiss() {
        if (mDialog.isShowing() && !mActivity.isFinishing()) {
            mDialog.dismiss();
        }
    }

    /** First status line: reading the transcript. */
    public void setTranscriptStatus(String text, boolean done) {
        mStatus1.setText(done ? "✓  " + text : text);
        mSpinner1.setVisibility(done ? View.GONE : View.VISIBLE);
    }

    /** Second status line: Claude working. */
    public void setAnswerStatus(String text, boolean done) {
        mStatus2Row.setVisibility(View.VISIBLE);
        mStatus2.setText(done ? "✓  " + text : text);
        mSpinner2.setVisibility(done ? View.GONE : View.VISIBLE);
    }

    public void setAnswerReady(DeclickbaitResult result) {
        mResult = result;
        if (!mGuessFirst) {
            reveal();
            return;
        }
        if (result.tease != null && !result.tease.isEmpty()) {
            mPrompt.setText(result.tease);
        }
        setAnswerStatus("Answer ready", true);
        mRevealButton.setEnabled(true);
        mRevealButton.requestFocus();
    }

    public void setError(String message, boolean offerSettings) {
        mStatus2Row.setVisibility(View.VISIBLE);
        mSpinner1.setVisibility(View.GONE);
        mSpinner2.setVisibility(View.GONE);
        mStatus2.setTextColor(0xFFFF8A80);
        mStatus2.setText(message);
        mRevealButton.setVisibility(View.GONE);
        mCloseButton.setText(R.string.declickbait_close);
        if (offerSettings) {
            mSettingsButton.setVisibility(View.VISIBLE);
            mSettingsButton.requestFocus();
        } else {
            mCloseButton.requestFocus();
        }
    }

    private void reveal() {
        if (mResult == null) {
            return;
        }
        DeclickbaitResult r = mResult;
        View root = mAnswerPanel;

        ((TextView) root.findViewById(R.id.declickbait_answer)).setText(
                r.isAnswerFound() ? r.answer : mActivity.getString(R.string.declickbait_not_found));
        setTextOrHide(root.findViewById(R.id.declickbait_answer_detail), r.isAnswerFound() ? r.answerDetail : null);
        setTextOrHide(root.findViewById(R.id.declickbait_summary), r.summary);
        TextView check = root.findViewById(R.id.declickbait_title_check);
        check.setText(r.titleCheck);

        LinearLayout moments = root.findViewById(R.id.declickbait_moments);
        moments.removeAllViews();
        View first = null;
        if (r.isAnswerFound()) {
            first = addMoment(moments, r.answerAtMs, "The answer: " + r.answer);
        }
        for (DeclickbaitResult.Moment m : r.moments) {
            View row = addMoment(moments, m.ms, m.label);
            if (first == null) first = row;
        }

        mGuessPanel.setVisibility(View.GONE);
        mAnswerPanel.setVisibility(View.VISIBLE);
        if (first != null) {
            first.requestFocus();
        }
        mListener.onRevealed(r);
    }

    private static void setTextOrHide(TextView view, String text) {
        if (text == null || text.isEmpty()) {
            view.setVisibility(View.GONE);
        } else {
            view.setVisibility(View.VISIBLE);
            view.setText(text);
        }
    }

    private View addMoment(LinearLayout parent, long ms, String label) {
        float density = mActivity.getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackgroundResource(R.drawable.declickbait_moment_bg);
        int padH = (int) (14 * density), padV = (int) (11 * density);
        row.setPadding(padH, padV, padH, padV);

        TextView time = new TextView(mActivity);
        time.setText(Transcript.formatTime(ms));
        time.setTextSize(17);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setTextColor(ContextCompat.getColorStateList(mActivity, R.color.declickbait_moment_time));
        time.setMinWidth((int) (64 * density));
        time.setDuplicateParentStateEnabled(true);

        TextView text = new TextView(mActivity);
        text.setText(label);
        text.setTextSize(16);
        text.setTextColor(ContextCompat.getColorStateList(mActivity, R.color.declickbait_button_text));
        text.setDuplicateParentStateEnabled(true);

        row.addView(time);
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> {
            mJumped = true;
            dismiss();
            mListener.onJump(ms);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * density);
        parent.addView(row, lp);
        return row;
    }
}
