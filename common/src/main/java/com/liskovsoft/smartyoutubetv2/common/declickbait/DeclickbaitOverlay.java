package com.liskovsoft.smartyoutubetv2.common.declickbait;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.common.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen wash over the paused video. Shows progress while the transcript is read and Claude works, then the
 * overview to read. The moments to jump to wait in a rail that peeks in from the right edge and slides out over
 * the text when focus moves into it. When the summary flagged claims based on research, a button under it offers
 * to check them against the papers; the result replaces the summary until Back or the button returns to it.
 */
public class DeclickbaitOverlay {
    /** How much of the rail stays on screen while the overview has focus: its times and the start of each label. */
    private static final int RAIL_PEEK_DP = 150;
    private static final float OVERVIEW_DIMMED = 0.35f;
    private static final int SLIDE_MS = 180;

    public interface Listener {
        void onJump(long positionMs);
        /** @param userClosed closed with Back / Close (not by a jump, Settings, or the app tearing it down) */
        void onClosed(boolean userClosed);
        void onOpenSettings();
        void onResultShown(DeclickbaitResult result);
        /** Run the research check; answer with {@link #showResearch} or {@link #setResearchError}. */
        void onCheckResearch(DeclickbaitResult result);
    }

    private final Activity mActivity;
    private final Dialog mDialog;
    private final Listener mListener;
    private final View mProgressPanel;
    private final View mResultPanel;
    private final ProgressBar mSpinner1;
    private final TextView mStatus1;
    private final View mStatus2Row;
    private final ProgressBar mSpinner2;
    private final TextView mStatus2;
    private final Button mSettingsButton;
    private final Button mCloseButton;
    private final View mOverview;
    private final View mSummaryScroll;
    private final View mRail;
    private final LinearLayout mMoments;
    private final TextView mHeadline;
    private final TextView mSummary;
    private final Button mResearchButton;
    private DeclickbaitResult mResult;
    /** Showing the research check (or its progress) instead of the summary. */
    private boolean mResearchMode;
    private boolean mResearchRunning;
    /** The row that takes focus when the rail opens: where the title is answered at first, then the last one used. */
    private View mJumpFocus;
    private boolean mRailOpen;
    /** Set when the overlay closes for a reason other than the person dismissing it. */
    private boolean mQuiet;

    private DeclickbaitOverlay(Activity activity, String videoTitle, Listener listener) {
        mActivity = activity;
        mListener = listener;

        mDialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        View root = LayoutInflater.from(activity).inflate(R.layout.declickbait_overlay, null);
        mDialog.setContentView(root);
        mDialog.setCancelable(true);
        mDialog.setOnDismissListener(dialog -> mListener.onClosed(!mQuiet));

        mProgressPanel = root.findViewById(R.id.declickbait_progress_panel);
        mResultPanel = root.findViewById(R.id.declickbait_result_panel);
        mSpinner1 = root.findViewById(R.id.declickbait_spinner1);
        mStatus1 = root.findViewById(R.id.declickbait_status1);
        mStatus2Row = root.findViewById(R.id.declickbait_status2_row);
        mSpinner2 = root.findViewById(R.id.declickbait_spinner2);
        mStatus2 = root.findViewById(R.id.declickbait_status2);
        mSettingsButton = root.findViewById(R.id.declickbait_settings);
        mCloseButton = root.findViewById(R.id.declickbait_close);
        mOverview = root.findViewById(R.id.declickbait_overview);
        mSummaryScroll = root.findViewById(R.id.declickbait_summary_scroll);
        mRail = root.findViewById(R.id.declickbait_rail);
        mMoments = root.findViewById(R.id.declickbait_moments);
        mHeadline = root.findViewById(R.id.declickbait_headline);
        mSummary = root.findViewById(R.id.declickbait_summary);
        mResearchButton = root.findViewById(R.id.declickbait_research_button);

        ((TextView) root.findViewById(R.id.declickbait_video_title)).setText(videoTitle);

        mCloseButton.setOnClickListener(v -> dismiss());
        mSettingsButton.setOnClickListener(v -> {
            dismissQuietly();
            mListener.onOpenSettings();
        });

        mSummaryScroll.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                setRailOpen(false);
            }
        });
        mSummaryScroll.setOnKeyListener(this::onOverviewKey);
        mResearchButton.setOnClickListener(v -> onResearchButton());
        mResearchButton.setOnKeyListener(this::onResearchButtonKey);
        mResearchButton.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                setRailOpen(false);
            }
        });
        // Back leaves the research check before it closes the overlay. The rail handles its own Back.
        mDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && mResearchMode && !mRailOpen) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    showSummary();
                }
                return true;
            }
            return false;
        });
    }

    public static DeclickbaitOverlay show(Activity activity, String videoTitle, Listener listener) {
        DeclickbaitOverlay overlay = new DeclickbaitOverlay(activity, videoTitle, listener);
        overlay.mDialog.show();
        overlay.mCloseButton.requestFocus();
        return overlay;
    }

    public boolean isShowing() {
        return mDialog.isShowing();
    }

    public void dismiss() {
        // Dismiss even while the activity finishes, or the window leaks.
        if (mDialog.isShowing()) {
            try {
                mDialog.dismiss();
            } catch (IllegalArgumentException e) {
                // Window already detached from the activity.
            }
        }
    }

    /** Close without counting as the person dismissing it, so playback isn't resumed. */
    public void dismissQuietly() {
        mQuiet = true;
        dismiss();
    }

    /** First status line: reading the transcript. */
    public void setTranscriptStatus(String text, boolean done) {
        mStatus1.setText(done ? "✓  " + text : text);
        mSpinner1.setVisibility(done ? View.GONE : View.VISIBLE);
    }

    /** Second status line: Claude working. */
    public void setSummaryStatus(String text, boolean done) {
        mStatus2Row.setVisibility(View.VISIBLE);
        mStatus2.setText(done ? "✓  " + text : text);
        mSpinner2.setVisibility(done ? View.GONE : View.VISIBLE);
    }

    public void setError(String message, boolean offerSettings) {
        mStatus2Row.setVisibility(View.VISIBLE);
        mSpinner1.setVisibility(View.GONE);
        mSpinner2.setVisibility(View.GONE);
        mStatus2.setTextColor(0xFFFF8A80);
        mStatus2.setText(message);
        if (offerSettings) {
            mSettingsButton.setVisibility(View.VISIBLE);
            mSettingsButton.requestFocus();
        } else {
            mCloseButton.requestFocus();
        }
    }

    public void showResult(DeclickbaitResult r) {
        mResult = r;
        mRailOpen = false;
        mOverview.setAlpha(1f);
        showSummary();

        mProgressPanel.setVisibility(View.GONE);
        mResultPanel.setVisibility(View.VISIBLE);
        mSummaryScroll.requestFocus();
        mListener.onResultShown(r);
    }

    /** The summary view: headline, summary text, the video's moments in the rail. */
    private void showSummary() {
        DeclickbaitResult r = mResult;
        float density = mActivity.getResources().getDisplayMetrics().density;
        mResearchMode = false;
        mHeadline.setText(r.headline);
        // The title check rides at the end of the summary, so it costs no height of its own.
        mSummary.setText(SummaryText.format(r.summary, mActivity.getString(verdictText(r)), r.titleCheck, density));
        fillRail(r.moments);
        updateResearchButton();
        mSummaryScroll.scrollTo(0, 0);
        if (!mRailOpen && !mResearchButton.hasFocus()) {
            mSummaryScroll.requestFocus();
        }
    }

    private void updateResearchButton() {
        int claims = mResult.researchClaims.size();
        if (claims == 0) {
            mResearchButton.setVisibility(View.GONE);
            return;
        }
        mResearchButton.setVisibility(View.VISIBLE);
        if (mResearchMode) {
            mResearchButton.setText(R.string.declickbait_research_back);
        } else if (mResult.research != null || mResearchRunning) {
            mResearchButton.setText(R.string.declickbait_research_show);
        } else {
            mResearchButton.setText(claims == 1
                    ? mActivity.getString(R.string.declickbait_research_check_one)
                    : mActivity.getString(R.string.declickbait_research_check, claims));
        }
    }

    private void onResearchButton() {
        if (mResearchMode) {
            showSummary();
            mResearchButton.requestFocus();
            return;
        }
        if (mResult.research != null) {
            renderResearch(mResult.research);
            return;
        }
        if (!mResearchRunning) {
            mResearchRunning = true;
            mListener.onCheckResearch(mResult);
        }
        showResearchProgress();
    }

    private void showResearchProgress() {
        mResearchMode = true;
        mHeadline.setText(R.string.declickbait_research_working);
        mSummary.setText(R.string.declickbait_research_working_detail);
        updateResearchButton();
        mSummaryScroll.scrollTo(0, 0);
    }

    /** The research check is ready: show it if the viewer is waiting on it, otherwise keep it for the button. */
    public void showResearch(DeclickbaitResult.ResearchCheck check) {
        if (mResult == null) {
            return; // This overlay is still loading its summary; the saved check comes with it.
        }
        mResearchRunning = false;
        mResult.research = check;
        if (mResearchMode) {
            renderResearch(check);
        } else {
            updateResearchButton();
        }
    }

    private void renderResearch(DeclickbaitResult.ResearchCheck check) {
        mResearchMode = true;
        mHeadline.setText(R.string.declickbait_research_title);
        mSummary.setText(SummaryText.formatResearch(check));
        List<DeclickbaitResult.Moment> jumps = new ArrayList<>();
        for (DeclickbaitResult.Study s : check.studies) {
            jumps.add(new DeclickbaitResult.Moment(s.ms, s.label, false));
        }
        fillRail(jumps.isEmpty() ? mResult.moments : jumps);
        updateResearchButton();
        mSummaryScroll.scrollTo(0, 0);
    }

    /** Shown if the viewer is waiting on the check; either way the next press of the button tries again. */
    public void setResearchError(String message) {
        if (mResult == null) {
            return;
        }
        mResearchRunning = false;
        if (mResearchMode) {
            mHeadline.setText(R.string.declickbait_research_title);
            mSummary.setText(message);
        }
        updateResearchButton();
    }

    private void fillRail(List<DeclickbaitResult.Moment> moments) {
        float density = mActivity.getResources().getDisplayMetrics().density;
        boolean railHadFocus = mRail.hasFocus();
        mMoments.removeAllViews();
        mJumpFocus = null;
        for (DeclickbaitResult.Moment m : moments) {
            View row = addMoment(m, density);
            if (mJumpFocus == null && m.answersTitle) {
                mJumpFocus = row;
            }
        }
        if (mJumpFocus == null && mMoments.getChildCount() > 0) {
            mJumpFocus = mMoments.getChildAt(0);
        }
        mRail.setVisibility(mJumpFocus != null ? View.VISIBLE : View.GONE);
        if (railHadFocus && mJumpFocus != null) {
            mJumpFocus.requestFocus();
        } else if (!mRailOpen) {
            mRail.setTranslationX(railClosedX());
        }
    }

    private static int verdictText(DeclickbaitResult r) {
        if (DeclickbaitResult.SUPPORTED_YES.equals(r.titleSupported)) {
            return R.string.declickbait_title_supported;
        }
        if (DeclickbaitResult.SUPPORTED_NO.equals(r.titleSupported)) {
            return R.string.declickbait_title_unsupported;
        }
        return R.string.declickbait_title_partly;
    }

    // --- Focus: the overview scrolls with up/down; right opens the rail; left or Back returns to the overview ---

    private boolean onOverviewKey(View v, int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (event.getAction() == KeyEvent.ACTION_DOWN && mJumpFocus != null) {
                    mJumpFocus.requestFocus();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return true;
            // The ScrollView scrolls itself. At either end stop here, or focus search wanders into the rail;
            // past the bottom, the research button takes focus when there is one.
            case KeyEvent.KEYCODE_DPAD_UP:
                return !v.canScrollVertically(-1);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (v.canScrollVertically(1)) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && mResearchButton.getVisibility() == View.VISIBLE) {
                    mResearchButton.requestFocus();
                }
                return true;
            default:
                return false;
        }
    }

    private boolean onResearchButtonKey(View v, int keyCode, KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    mSummaryScroll.requestFocus();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && mJumpFocus != null) {
                    mJumpFocus.requestFocus();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            default:
                return false;
        }
    }

    private boolean onMomentKey(View row, int keyCode, KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) {
                    mSummaryScroll.requestFocus();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                // Back steps out of the rail first; from the overview it closes the overlay.
                if (!down) {
                    mSummaryScroll.requestFocus();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                return mMoments.indexOfChild(row) == 0;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return mMoments.indexOfChild(row) == mMoments.getChildCount() - 1;
            default:
                return false;
        }
    }

    private float railClosedX() {
        float density = mActivity.getResources().getDisplayMetrics().density;
        return mRail.getLayoutParams().width - RAIL_PEEK_DP * density;
    }

    /** Slides the rail over the text rather than squeezing the text, so nothing has to be laid out again. */
    private void setRailOpen(boolean open) {
        if (mRailOpen == open) {
            return;
        }
        mRailOpen = open;
        mRail.animate().translationX(open ? 0 : railClosedX()).setDuration(SLIDE_MS).start();
        mOverview.animate().alpha(open ? OVERVIEW_DIMMED : 1f).setDuration(SLIDE_MS).start();
    }

    private View addMoment(DeclickbaitResult.Moment moment, float density) {
        // Baseline-aligned (the LinearLayout default), so the time lines up with the first line of a wrapped label.
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackgroundResource(R.drawable.declickbait_moment_bg);
        int padH = (int) (12 * density), padV = (int) (9 * density);
        row.setPadding(padH, padV, padH, padV);

        TextView time = new TextView(mActivity);
        time.setText(Transcript.formatTime(moment.ms));
        time.setTextSize(16);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setTextColor(ContextCompat.getColorStateList(mActivity, R.color.declickbait_moment_time));
        time.setMinWidth((int) (56 * density));
        time.setDuplicateParentStateEnabled(true);

        TextView label = new TextView(mActivity);
        label.setText(moment.answersTitle ? "✦ " + moment.label : moment.label);
        label.setTextSize(16);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setTextColor(ContextCompat.getColorStateList(mActivity, R.color.declickbait_button_text));
        label.setDuplicateParentStateEnabled(true);

        row.addView(time);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> {
            dismissQuietly();
            mListener.onJump(moment.ms);
        });
        row.setOnKeyListener(this::onMomentKey);
        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                mJumpFocus = v;
                setRailOpen(true);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (6 * density);
        mMoments.addView(row, lp);
        return row;
    }
}
