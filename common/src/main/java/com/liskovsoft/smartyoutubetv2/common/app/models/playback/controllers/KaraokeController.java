package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.helpers.PermissionHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.karaoke.KaraokeManager;
import com.liskovsoft.smartyoutubetv2.common.karaoke.MicSource;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.prefs.KaraokeData;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokeSearch;
import com.liskovsoft.smartyoutubetv2.karaoke.core.VoiceReverb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Karaoke player button: phone mics, voice effects, vocal remover, key change, karaoke version search.
 */
public class KaraokeController extends BasePlayerController implements KaraokeManager.Listener {
    private static final int[] VOCAL_REMOVER_LEVELS = {0, 50, 75, 100};
    private KaraokeManager mKaraokeManager;
    private KaraokeData mKaraokeData;

    @Override
    public void onInit() {
        mKaraokeManager = KaraokeManager.instance(getContext());
        mKaraokeData = KaraokeData.instance(getContext());
        mKaraokeManager.addListener(this);
        updateButtonState();
    }

    @Override
    public void onViewResumed() {
        updateButtonState();
    }

    @Override
    public void onVideoLoaded(Video item) {
        // Runs after VideoStateController restored the regular pitch
        updateButtonState();
        applyKey();
    }

    @Override
    public void onKaraokeChanged() {
        updateButtonState();
    }

    @Override
    public void onButtonClicked(int buttonId, int buttonState) {
        if (buttonId != R.id.action_karaoke || mKaraokeManager == null) {
            return;
        }

        if (!mKaraokeManager.isRunning() && !startKaraoke()) {
            return;
        }

        showKaraokeDialog();
    }

    @Override
    public void onButtonLongClicked(int buttonId, int buttonState) {
        if (buttonId != R.id.action_karaoke || mKaraokeManager == null) {
            return;
        }

        // Quick toggle
        if (mKaraokeManager.isRunning()) {
            stopKaraoke();
        } else {
            startKaraoke();
        }
    }

    private boolean startKaraoke() {
        if (!mKaraokeManager.start()) {
            MessageHelpers.showLongMessage(getContext(), R.string.karaoke_start_error);
            return false;
        }

        if (mKaraokeData.isLocalMicEnabled() && !PermissionHelpers.hasMicPermissions(getContext())) {
            enableTvMic(true);
        }

        applyKey();

        String pin = mKaraokeManager.getPin();
        if (pin != null) {
            MessageHelpers.showLongMessage(getContext(), getContext().getString(R.string.karaoke_started_pin, pin));
        } else {
            MessageHelpers.showMessage(getContext(), R.string.karaoke_started);
        }

        return true;
    }

    private void stopKaraoke() {
        mKaraokeManager.stop();
        applyKey(); // back to the regular pitch
        MessageHelpers.showMessage(getContext(), R.string.karaoke_stopped);
    }

    private void updateButtonState() {
        if (getPlayer() == null || mKaraokeManager == null) {
            return;
        }

        getPlayer().setButtonState(R.id.action_karaoke, mKaraokeManager.isRunning() ? PlayerUI.BUTTON_ON : PlayerUI.BUTTON_OFF);
    }

    /**
     * Key change is a pitch shift without tempo change (ExoPlayer's Sonic).
     */
    private void applyKey() {
        if (getPlayer() == null || mKaraokeManager == null) {
            return;
        }

        boolean shifted = mKaraokeManager.isRunning() && mKaraokeData.getKeySemitones() != 0;
        getPlayer().setPitch(shifted ? mKaraokeData.getKeyPitch() : getPlayerData().getPitch());
    }

    private void showKaraokeDialog() {
        Context context = getContext();
        AppDialogPresenter dialog = getAppDialogPresenter();
        boolean isRunning = mKaraokeManager.isRunning();
        String pin = mKaraokeManager.getPin();
        String modeDescription = isRunning && pin != null ?
                context.getString(R.string.karaoke_mode_desc_pin, pin) : context.getString(R.string.karaoke_mode_desc);

        dialog.appendSingleSwitch(UiOptionItem.from(context.getString(R.string.karaoke_mode), modeDescription,
                option -> {
                    if (option.isSelected()) {
                        startKaraoke();
                    } else {
                        stopKaraoke();
                    }
                }, isRunning));

        String addresses = isRunning ? TextUtils.join(", ", mKaraokeManager.getAddresses()) : context.getString(R.string.karaoke_turn_on_first);
        dialog.appendLongTextCategory(context.getString(R.string.karaoke_how_to_connect),
                UiOptionItem.from(context.getString(R.string.karaoke_how_to_connect_desc, mKaraokeManager.getTvName(), addresses,
                        pin != null ? pin : context.getString(R.string.karaoke_pin_not_needed))));

        appendMicsCategory(dialog);
        appendMicVolumeCategory(dialog);
        appendVoiceEffectCategory(dialog);
        appendVocalRemoverCategory(dialog);
        appendKeyCategory(dialog);
        appendMusicVolumeCategory(dialog);
        appendLatencyCategory(dialog);
        appendOptionsCategory(dialog);

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.karaoke_new_pin), option -> {
            String newPin = mKaraokeManager.renewPin();
            MessageHelpers.showLongMessage(context, context.getString(R.string.karaoke_new_pin_done, newPin));
        }));
        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.karaoke_find_version), option -> searchKaraokeVersion()));

        dialog.showDialog(context.getString(R.string.karaoke));
    }

    private void appendMicsCategory(AppDialogPresenter dialog) {
        Context context = getContext();
        List<OptionItem> options = new ArrayList<>();

        for (MicSource mic : mKaraokeManager.getMics()) {
            String name = mic.isLocal() ? mic.getName() : context.getString(R.string.karaoke_mic_name, mic.getSlot(), mic.getName());
            if (mic.isMuted()) {
                name = context.getString(R.string.karaoke_mic_muted, name);
            }
            long total = mic.getReceivedPackets() + mic.getLostPackets();
            String loss = total > 0 ? String.format(Locale.US, "%.1f", mic.getLostPackets() * 100f / total) : "0";
            options.add(UiOptionItem.from(context.getString(R.string.karaoke_mic_info, name, mic.getBufferedMs(), loss)));
        }

        if (options.isEmpty()) {
            options.add(UiOptionItem.from(context.getString(R.string.karaoke_no_mics)));
        }

        dialog.appendStringsCategory(context.getString(R.string.karaoke_mics), options);
    }

    private void appendMicVolumeCategory(AppDialogPresenter dialog) {
        List<OptionItem> options = new ArrayList<>();

        for (int percent = 0; percent <= 300; percent += 10) {
            int value = percent;
            options.add(UiOptionItem.from(String.format(Locale.US, "%d%%", percent), option -> {
                mKaraokeData.setMicVolumePercent(value);
                mKaraokeManager.applySettings();
            }, mKaraokeData.getMicVolumePercent() == percent));
        }

        dialog.appendRadioCategory(getContext().getString(R.string.karaoke_mic_volume), options);
    }

    private void appendVoiceEffectCategory(AppDialogPresenter dialog) {
        Context context = getContext();
        List<OptionItem> options = new ArrayList<>();

        for (int[] pair : new int[][] {
                {R.string.karaoke_off, VoiceReverb.PRESET_OFF},
                {R.string.karaoke_effect_room, VoiceReverb.PRESET_ROOM},
                {R.string.karaoke_effect_hall, VoiceReverb.PRESET_HALL},
                {R.string.karaoke_effect_echo, VoiceReverb.PRESET_ECHO}}) {
            options.add(UiOptionItem.from(context.getString(pair[0]), option -> {
                mKaraokeData.setVoiceEffect(pair[1]);
                mKaraokeManager.applySettings();
            }, mKaraokeData.getVoiceEffect() == pair[1]));
        }

        dialog.appendRadioCategory(context.getString(R.string.karaoke_voice_effect), options);
    }

    private void appendVocalRemoverCategory(AppDialogPresenter dialog) {
        Context context = getContext();
        List<OptionItem> options = new ArrayList<>();

        for (int percent : VOCAL_REMOVER_LEVELS) {
            String title = percent == 0 ? context.getString(R.string.karaoke_off) : String.format(Locale.US, "%d%%", percent);
            options.add(UiOptionItem.from(title, option -> {
                mKaraokeData.setVocalRemoverPercent(percent);
                mKaraokeManager.applySettings();
            }, mKaraokeData.getVocalRemoverPercent() == percent));
        }

        dialog.appendRadioCategory(context.getString(R.string.karaoke_vocal_remover), options);
    }

    private void appendKeyCategory(AppDialogPresenter dialog) {
        Context context = getContext();
        List<OptionItem> options = new ArrayList<>();

        for (int semitones = -KaraokeData.MAX_KEY_SEMITONES; semitones <= KaraokeData.MAX_KEY_SEMITONES; semitones++) {
            int value = semitones;
            String title = semitones == 0 ? context.getString(R.string.karaoke_key_original) : String.format(Locale.US, "%+d", semitones);
            options.add(UiOptionItem.from(title, option -> {
                mKaraokeData.setKeySemitones(value);
                applyKey();
            }, mKaraokeData.getKeySemitones() == semitones));
        }

        dialog.appendRadioCategory(context.getString(R.string.karaoke_key), options);
    }

    private void appendMusicVolumeCategory(AppDialogPresenter dialog) {
        OptionCategory category = AppDialogUtil.createAudioVolumeCategory(getContext(), () -> {
            if (getPlayer() != null) {
                getPlayer().setVolume(getPlayerData().getPlayerVolume());
            }
        });

        dialog.appendRadioCategory(getContext().getString(R.string.karaoke_music_volume), category.options);
    }

    private void appendLatencyCategory(AppDialogPresenter dialog) {
        Context context = getContext();
        List<OptionItem> options = new ArrayList<>();

        for (int[] pair : new int[][] {
                {R.string.karaoke_latency_low, KaraokeData.LATENCY_LOW},
                {R.string.karaoke_latency_normal, KaraokeData.LATENCY_NORMAL},
                {R.string.karaoke_latency_stable, KaraokeData.LATENCY_STABLE}}) {
            options.add(UiOptionItem.from(context.getString(pair[0], KaraokeData.toLatencyMs(pair[1])), option -> {
                mKaraokeData.setLatencyMode(pair[1]);
                mKaraokeManager.applySettings();
            }, mKaraokeData.getLatencyMode() == pair[1]));
        }

        dialog.appendRadioCategory(context.getString(R.string.karaoke_latency), options);
    }

    private void appendOptionsCategory(AppDialogPresenter dialog) {
        Context context = getContext();
        List<OptionItem> options = new ArrayList<>();

        options.add(UiOptionItem.from(context.getString(R.string.karaoke_noise_gate), option -> {
            mKaraokeData.setNoiseGateEnabled(option.isSelected());
            mKaraokeManager.applySettings();
        }, mKaraokeData.isNoiseGateEnabled()));

        options.add(UiOptionItem.from(context.getString(R.string.karaoke_use_tv_mic),
                option -> enableTvMic(option.isSelected()), mKaraokeData.isLocalMicEnabled()));

        options.add(UiOptionItem.from(context.getString(R.string.karaoke_require_pin),
                option -> mKaraokeManager.setPinRequired(option.isSelected()), mKaraokeData.isPinRequired()));

        dialog.appendCheckedCategory(context.getString(R.string.karaoke_options), options);
    }

    private void enableTvMic(boolean enable) {
        if (!enable || PermissionHelpers.hasMicPermissions(getContext())) {
            setTvMicEnabled(enable);
            return;
        }

        mKaraokeData.setLocalMicEnabled(true); // remember the choice while the system asks for permission

        Activity activity = getActivity();

        if (activity instanceof MotherActivity) {
            ((MotherActivity) activity).addOnPermissions((requestCode, permissions, grantResults) -> {
                if (requestCode != PermissionHelpers.REQUEST_MIC) {
                    return;
                }

                boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
                setTvMicEnabled(granted);
            });
            PermissionHelpers.verifyMicPermissions(activity);
        }
    }

    private void setTvMicEnabled(boolean enable) {
        if (!mKaraokeManager.setLocalMicEnabled(enable) && enable) {
            mKaraokeManager.setLocalMicEnabled(false);
            MessageHelpers.showLongMessage(getContext(), R.string.karaoke_tv_mic_error);
        }
    }

    private void searchKaraokeVersion() {
        Video video = getVideo();

        if (video == null) {
            return;
        }

        String query = KaraokeSearch.buildQuery(video.getTitle(), getContext().getString(R.string.karaoke_search_suffix));
        getAppDialogPresenter().closeDialog();
        getSearchPresenter().startSearch(query);
    }
}
