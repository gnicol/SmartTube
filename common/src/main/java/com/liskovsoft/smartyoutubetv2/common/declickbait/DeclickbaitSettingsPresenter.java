package com.liskovsoft.smartyoutubetv2.common.declickbait;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.util.ArrayList;
import java.util.List;

public class DeclickbaitSettingsPresenter extends BasePresenter<Void> {
    private final DeclickbaitPrefs mPrefs;

    private DeclickbaitSettingsPresenter(Context context) {
        super(context);
        mPrefs = DeclickbaitPrefs.instance(context);
    }

    public static DeclickbaitSettingsPresenter instance(Context context) {
        return new DeclickbaitSettingsPresenter(context);
    }

    public void show() {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());

        appendKey(dialog);
        appendModels(dialog);
        appendButtonSwitch(dialog);
        appendClear(dialog);

        dialog.showDialog(getContext().getString(R.string.declickbait_title));
    }

    private void appendKey(AppDialogPresenter dialog) {
        String masked = mPrefs.getMaskedKey();
        String keyTitle = getContext().getString(R.string.declickbait_settings_key);
        String status = masked != null ? "Set · ends in " + masked : "Not set";

        dialog.appendSingleButton(UiOptionItem.from(keyTitle, status, option ->
                SimpleEditDialog.showPassword(getContext(), keyTitle, mPrefs.getApiKey(), newValue -> {
                    if (!mPrefs.setApiKey(newValue)) {
                        // Never echo what was typed.
                        MessageHelpers.showLongMessage(getContext(), "That doesn't look like a Claude API key. Nothing was saved.");
                        return false;
                    }
                    MessageHelpers.showMessage(getContext(), mPrefs.hasApiKey()
                            ? "Key saved (ends in " + mPrefs.getMaskedKey() + ")" : "Key removed");
                    return true;
                })));

        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.declickbait_settings_test), option -> {
            MessageHelpers.showMessage(getContext(), "Testing key…");
            RxHelper.execute(RxHelper.fromCallable(() -> {
                String error = ClaudeClient.testKey(mPrefs);
                return error == null ? "The key works." : error;
            }), message -> MessageHelpers.showLongMessage(getContext(), message), error ->
                    MessageHelpers.showMessage(getContext(), "Test failed: " + error.getMessage()));
        }));
    }

    private void appendModels(AppDialogPresenter dialog) {
        List<OptionItem> options = new ArrayList<>();
        options.add(UiOptionItem.from("Claude Opus 5.5 · best summaries, about 3¢ per video",
                option -> mPrefs.setModel(DeclickbaitPrefs.MODEL_OPUS),
                DeclickbaitPrefs.MODEL_OPUS.equals(mPrefs.getModel())));
        options.add(UiOptionItem.from("Claude Haiku 5.5 · wordier, about 0.1¢ per video",
                option -> mPrefs.setModel(DeclickbaitPrefs.MODEL_HAIKU),
                DeclickbaitPrefs.MODEL_HAIKU.equals(mPrefs.getModel())));
        dialog.appendRadioCategory(getContext().getString(R.string.declickbait_settings_model), options);
    }

    private void appendButtonSwitch(AppDialogPresenter dialog) {
        dialog.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.declickbait_settings_button),
                option -> mPrefs.setButtonEnabled(option.isSelected()), mPrefs.isButtonEnabled()));
    }

    private void appendClear(AppDialogPresenter dialog) {
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.declickbait_settings_clear), option -> {
            DeclickbaitCache.clear(getContext());
            MessageHelpers.showMessage(getContext(), "Saved summaries cleared");
        }));
    }
}
