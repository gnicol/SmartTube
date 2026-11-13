package com.liskovsoft.smartyoutubetv2.common.declickbait;

import android.app.Activity;

import androidx.core.content.ContextCompat;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.ChapterItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.SeekBarSegment;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.disposables.Disposable;

/**
 * Player button → captions → Claude → overlay. Runs only when someone presses the button.
 */
public class DeclickbaitController extends BasePlayerController {
    private static final String TAG = DeclickbaitController.class.getSimpleName();
    private static final int MOMENTS_GROUP_ID = "declickbait_moments".hashCode();
    private static final int MARKER_COLOR = 0xFFF5B83D;

    private MediaItemService mMediaItemService;
    private DeclickbaitPrefs mPrefs;
    private Disposable mFormatAction;
    private Disposable mTranscriptAction;
    private Disposable mClaudeAction;
    private DeclickbaitOverlay mOverlay;
    private MediaItemMetadata mMetadata;
    private VideoGroup mMomentsGroup;
    private boolean mWasPlaying;
    /** Video the background work is for, and how far it's got (shown again if the overlay is re-opened). */
    private String mWorkVideoId;
    private String mTranscriptStatus;
    private boolean mTranscriptDone;

    @Override
    public void onInit() {
        mMediaItemService = YouTubeServiceManager.instance().getMediaItemService();
        mPrefs = DeclickbaitPrefs.instance(getContext());
    }

    @Override
    public void onNewVideo(Video item) {
        cancel();
        mMetadata = null;
        // SuggestionsController clears every suggestion row (ours included) when the new video's
        // metadata arrives. Removing it here as well crashed the app mid-transition on the bedroom stick.
        mMomentsGroup = null;
    }

    @Override
    public void onPlay() {
        // Keep the video paused while the overlay is up, whatever else tries to resume it.
        if (mOverlay != null && mOverlay.isShowing() && getPlayer() != null) {
            getPlayer().setPlayWhenReady(false);
        }
    }

    @Override
    public void onMetadata(MediaItemMetadata metadata) {
        mMetadata = metadata;
    }

    @Override
    public void onEngineReleased() {
        // Let a running request finish and be saved; just take the overlay down.
        dismissOverlay();
    }

    @Override
    public void onFinish() {
        cancel();
    }

    @Override
    public void onButtonClicked(int buttonId, int buttonState) {
        if (buttonId == R.id.action_declickbait) {
            start();
        }
    }

    @Override
    public void onButtonLongClicked(int buttonId, int buttonState) {
        if (buttonId == R.id.action_declickbait) {
            DeclickbaitSettingsPresenter.instance(getContext()).show();
        }
    }

    private void start() {
        Video video = getVideo();
        Activity activity = getActivity();
        if (getPlayer() == null || video == null || video.videoId == null || activity == null) {
            return;
        }
        if (video.isLive) {
            MessageHelpers.showMessage(getContext(), "De-clickbait doesn't work on live streams.");
            return;
        }

        final String videoId = video.videoId;
        dismissOverlay();
        // Work for this video keeps running (and gets saved) even if the overlay was closed early.
        if (!videoId.equals(mWorkVideoId)) {
            cancelWork();
        }

        mWasPlaying = getPlayer().getPlayWhenReady();
        getPlayer().setPlayWhenReady(false);
        getPlayer().showControls(false);

        mOverlay = DeclickbaitOverlay.show(activity, video.getTitle(), mPrefs.isGuessFirst(), new DeclickbaitOverlay.Listener() {
            @Override
            public void onJump(long positionMs) {
                if (getPlayer() != null) {
                    getPlayer().setPositionMs(Math.min(positionMs, getPlayer().getDurationMs()));
                    getPlayer().setPlayWhenReady(true);
                }
            }

            @Override
            public void onClosed(boolean jumped) {
                mOverlay = null;
                if (!jumped && mWasPlaying && getPlayer() != null) {
                    getPlayer().setPlayWhenReady(true);
                }
            }

            @Override
            public void onOpenSettings() {
                DeclickbaitSettingsPresenter.instance(getContext()).show();
            }

            @Override
            public void onRevealed(DeclickbaitResult result) {
                // Extras only: never let them take the player down.
                try {
                    showMomentsRow(result);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Key moments row failed: %s", e);
                }
                try {
                    showSeekBarMarkers(result);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Seekbar marks failed: %s", e);
                }
            }
        });

        if (!mPrefs.hasApiKey()) {
            mOverlay.setError("Add your Claude API key in Settings → De-clickbait first.", true);
            return;
        }

        DeclickbaitResult cached = DeclickbaitCache.get(getContext(), videoId);
        if (cached != null) {
            mOverlay.setTranscriptStatus("Saved answer", true);
            mOverlay.setAnswerReady(cached);
            return;
        }

        if (isWorking(videoId)) {
            // Re-opened while a request is still running: show where it's up to; the callbacks finish the job.
            mOverlay.setTranscriptStatus(mTranscriptStatus, mTranscriptDone);
            if (mTranscriptDone) {
                mOverlay.setAnswerStatus("Finding the answer…", false);
            }
            return;
        }

        mWorkVideoId = videoId;
        setTranscriptStatus("Reading the transcript…", false);
        mFormatAction = mMediaItemService.getFormatInfoObserve(videoId).subscribe(
                formatInfo -> onFormatInfo(videoId, formatInfo),
                error -> fail("Couldn't load this video's details.", false, error)
        );
    }

    private boolean isWorking(String videoId) {
        return videoId.equals(mWorkVideoId) && RxHelper.isAnyActionRunning(mFormatAction, mTranscriptAction, mClaudeAction);
    }

    private boolean isOverlayFor(String videoId) {
        Video video = getVideo();
        return mOverlay != null && mOverlay.isShowing() && video != null && videoId.equals(video.videoId);
    }

    private void setTranscriptStatus(String text, boolean done) {
        mTranscriptStatus = text;
        mTranscriptDone = done;
        if (mOverlay != null && mOverlay.isShowing()) {
            mOverlay.setTranscriptStatus(text, done);
        }
    }

    private void onFormatInfo(String videoId, MediaItemFormatInfo formatInfo) {
        final String url = TranscriptFetcher.pickUrl(formatInfo.getSubtitles());
        if (url == null) {
            fail("This video has no captions, so there's nothing to read.", false, null);
            return;
        }
        mTranscriptAction = RxHelper.fromCallable(() -> TranscriptFetcher.fetch(url)).subscribe(
                transcript -> onTranscript(videoId, transcript),
                error -> fail("Couldn't download the captions ("
                        + (error instanceof TranscriptFetcher.FetchException ? error.getMessage() : error.getClass().getSimpleName())
                        + ").", false, error)
        );
    }

    private void onTranscript(String videoId, Transcript transcript) {
        long durationMs = getPlayer() != null ? getPlayer().getDurationMs() : 0;
        setTranscriptStatus(String.format("Transcript: %s, %,d words",
                Transcript.formatTime(durationMs > 0 ? durationMs : lastLineMs(transcript)), transcript.getWordCount()), true);
        if (isOverlayFor(videoId)) {
            mOverlay.setAnswerStatus("Finding the answer…", false);
        }

        final ClaudeClient.VideoInfo info = buildVideoInfo(videoId, durationMs);
        mClaudeAction = RxHelper.<DeclickbaitResult>createLong(emitter -> {
            try {
                emitter.onNext(ClaudeClient.analyze(mPrefs, info, transcript));
                emitter.onComplete();
            } catch (ClaudeClient.ClaudeException e) {
                emitter.tryOnError(e);
            }
        }).subscribe(
                result -> {
                    // Saved even if the overlay was closed, so the next press is instant and free.
                    DeclickbaitCache.put(getContext(), result);
                    if (isOverlayFor(videoId)) {
                        mOverlay.setAnswerReady(result);
                    }
                },
                error -> {
                    boolean keyProblem = error instanceof ClaudeClient.ClaudeException && ((ClaudeClient.ClaudeException) error).isKeyProblem;
                    String msg = error instanceof ClaudeClient.ClaudeException ? error.getMessage() : "Something went wrong asking Claude.";
                    fail(msg, keyProblem, error);
                }
        );
    }

    private ClaudeClient.VideoInfo buildVideoInfo(String videoId, long durationMs) {
        ClaudeClient.VideoInfo info = new ClaudeClient.VideoInfo();
        Video video = getVideo();
        info.videoId = videoId;
        info.title = video != null ? video.getTitle() : null;
        info.channel = video != null ? video.getAuthor() : null;
        info.durationMs = durationMs;
        if (mMetadata != null) {
            if (info.title == null) info.title = mMetadata.getTitle();
            if (info.channel == null) info.channel = mMetadata.getAuthor();
            info.description = mMetadata.getDescription();
            List<ChapterItem> chapters = mMetadata.getChapters();
            if (chapters != null) {
                for (ChapterItem chapter : chapters) {
                    info.chapters.add(new Object[] {chapter.getStartTimeMs(), chapter.getTitle()});
                }
            }
        }
        return info;
    }

    private static long lastLineMs(Transcript transcript) {
        List<Transcript.Line> lines = transcript.getLines();
        return lines.isEmpty() ? 0 : lines.get(lines.size() - 1).startMs;
    }

    private void fail(String message, boolean offerSettings, Throwable error) {
        if (error != null) {
            Log.e(TAG, "%s %s", message, error.getMessage());
        }
        if (mOverlay != null && mOverlay.isShowing()) {
            mOverlay.setError(message, offerSettings);
        }
    }

    /** Stop background work only; keep the overlay. */
    private void cancelWork() {
        RxHelper.disposeActions(mFormatAction, mTranscriptAction, mClaudeAction);
        mWorkVideoId = null;
    }

    private void dismissOverlay() {
        if (mOverlay != null) {
            DeclickbaitOverlay overlay = mOverlay;
            mOverlay = null;
            overlay.dismiss();
        }
    }

    private void cancel() {
        cancelWork();
        dismissOverlay();
    }

    // --- After the reveal: a "Key moments" row of chapter-style cards, and amber seekbar marks ---

    private void showMomentsRow(DeclickbaitResult result) {
        Video video = getVideo();
        if (getPlayer() == null || video == null) {
            return;
        }

        List<Video> cards = new ArrayList<>();
        if (result.isAnswerFound()) {
            cards.add(card(video, result.answerAtMs, getContext().getString(R.string.declickbait_the_answer, result.answer)));
        }
        for (DeclickbaitResult.Moment m : result.moments) {
            cards.add(card(video, m.ms, m.label));
        }
        if (cards.isEmpty()) {
            return;
        }
        // Chapters-style rows sort by time.
        java.util.Collections.sort(cards, (a, b) -> a.startTimeMs < b.startTimeMs ? -1 : (a.startTimeMs == b.startTimeMs ? 0 : 1));

        VideoGroup group = VideoGroup.from(cards);
        group.setTitle(getContext().getString(R.string.declickbait_moments_row));
        group.setId(MOMENTS_GROUP_ID);
        // Stay out of row 0 when YouTube chapters exist: SuggestionsController treats row 0 as the chapters row.
        boolean hasChapters = mMetadata != null && mMetadata.getChapters() != null && !mMetadata.getChapters().isEmpty();
        group.setPosition(hasChapters ? 1 : 0);
        group.setAction(VideoGroup.ACTION_REPLACE);
        mMomentsGroup = group;
        getPlayer().updateSuggestions(group);
    }

    private static Video card(Video source, long ms, String label) {
        Video card = new Video();
        card.isChapter = true;
        card.title = label;
        card.startTimeMs = ms;
        card.badge = Transcript.formatTime(ms);
        card.cardImageUrl = source.cardImageUrl;
        return card;
    }

    private void showSeekBarMarkers(DeclickbaitResult result) {
        if (getPlayer() == null) {
            return;
        }
        long durationMs = getPlayer().getDurationMs();
        if (durationMs <= 0) {
            return;
        }

        List<SeekBarSegment> segments = new ArrayList<>();

        // The seekbar holds one list, so redraw YouTube's chapter ticks alongside ours.
        if (mMetadata != null && mMetadata.getChapters() != null) {
            long tickMs = durationMs / 10000;
            int black = ContextCompat.getColor(getContext(), R.color.black);
            for (ChapterItem chapter : mMetadata.getChapters()) {
                if (chapter.getStartTimeMs() > 0) {
                    segments.add(segment(chapter.getStartTimeMs(), tickMs, durationMs, black));
                }
            }
        }

        long markMs = Math.max(durationMs / 250, 1500);
        if (result.isAnswerFound()) {
            segments.add(segment(result.answerAtMs, markMs, durationMs, MARKER_COLOR));
        }
        for (DeclickbaitResult.Moment m : result.moments) {
            segments.add(segment(m.ms, markMs, durationMs, MARKER_COLOR));
        }
        getPlayer().setSeekBarSegments(segments);
    }

    private static SeekBarSegment segment(long startMs, long lengthMs, long durationMs, int color) {
        SeekBarSegment s = new SeekBarSegment();
        s.startProgress = (float) startMs / durationMs;
        s.endProgress = Math.min(1f, (float) (startMs + lengthMs) / durationMs);
        s.color = color;
        return s;
    }
}
