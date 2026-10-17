package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.tv.R;

public class KaraokeAction extends TwoStateAction {
    public KaraokeAction(Context context) {
        super(context, R.id.action_karaoke, R.drawable.action_karaoke);

        String label = context.getString(R.string.karaoke);
        String[] labels = new String[2];
        // Note, labels denote the action taken when clicked
        labels[INDEX_OFF] = label;
        labels[INDEX_ON] = label;
        setLabels(labels);
    }
}
