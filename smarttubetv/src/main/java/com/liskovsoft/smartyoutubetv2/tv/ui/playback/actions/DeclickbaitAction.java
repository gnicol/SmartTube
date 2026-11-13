package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.Action;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * ✦ button: ask Claude for the answer a clickbait title is teasing. Long press opens its settings.
 */
public class DeclickbaitAction extends Action {
    public DeclickbaitAction(Context context) {
        super(R.id.action_declickbait);
        setIcon(ContextCompat.getDrawable(context, R.drawable.action_declickbait));
        setLabel1(context.getString(R.string.declickbait_title));
    }
}
