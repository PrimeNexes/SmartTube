package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.helpers.PermissionHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.util.MimeTypes;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.karaoke.KaraokeManager;
import com.liskovsoft.smartyoutubetv2.common.karaoke.MicSource;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.prefs.KaraokeData;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokePairing;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokeSearch;
import com.liskovsoft.smartyoutubetv2.karaoke.core.MusicDucker;
import com.liskovsoft.smartyoutubetv2.karaoke.core.VoiceReverb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Karaoke player button: phone mics, voice effects, vocal remover, key change, karaoke version search.
 */
public class KaraokeController extends BasePlayerController implements KaraokeManager.Listener {
    private static final int[] VOCAL_REMOVER_LEVELS = {0, 50, 75, 100};
    private static final long DUCK_TICK_MS = 50;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final MusicDucker mDucker = new MusicDucker();
    private final Runnable mDuckTick = this::duckTick;
    private boolean mIsDuckTickScheduled;
    /** Music multiplier while someone sings, 1 otherwise. */
    private float mDuckGain = 1;
    private KaraokeManager mKaraokeManager;
    private KaraokeData mKaraokeData;
    /** Player volume SmartTube set for the current video, before the karaoke music level. -1 if not applied. */
    private float mMusicBaseVolume = -1;

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
        updateButtonState();

        // Runs after VideoStateController restored the regular pitch and volume of the new video
        if (isRunning()) {
            mMusicBaseVolume = -1;
            applyKey();
            applyMusicVolume();
        }

        updateDucking();
    }

    @Override
    public void onSpeedChanged(float speed) {
        // Without audio time stretching a speed change also resets the pitch
        if (isRunning() && mKaraokeData.getKeySemitones() != 0) {
            applyKey();
        }
    }

    @Override
    public void onKaraokeChanged() {
        updateButtonState();
        // Karaoke may also stop on its own (audio output failure): music goes back to normal either way
        applyKey();
        applyMusicVolume();
        updateDucking();
    }

    @Override
    public void onButtonClicked(int buttonId, int buttonState) {
        if (buttonId != R.id.action_karaoke || mKaraokeManager == null) {
            return;
        }

        if (!isRunning() && !startKaraoke()) {
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
        if (isRunning()) {
            stopKaraoke();
        } else {
            startKaraoke();
        }
    }

    private boolean isRunning() {
        return mKaraokeManager != null && mKaraokeManager.isRunning();
    }

    private boolean startKaraoke() {
        if (!mKaraokeManager.start()) {
            MessageHelpers.showLongMessage(getContext(), R.string.karaoke_start_error);
            return false;
        }

        if (mKaraokeData.isLocalMicEnabled() && !PermissionHelpers.hasMicPermissions(getContext())) {
            enableTvMic(true);
        }

        String pin = mKaraokeManager.getPin();
        if (pin != null) {
            MessageHelpers.showLongMessage(getContext(), getContext().getString(R.string.karaoke_started_pin, KaraokePairing.formatPin(pin)));
        } else {
            MessageHelpers.showMessage(getContext(), R.string.karaoke_started);
        }

        return true;
    }

    private void stopKaraoke() {
        mKaraokeManager.stop(); // onKaraokeChanged() restores pitch and volume
        MessageHelpers.showMessage(getContext(), R.string.karaoke_stopped);
    }

    private void updateButtonState() {
        if (getPlayer() == null || mKaraokeManager == null) {
            return;
        }

        getPlayer().setButtonState(R.id.action_karaoke, isRunning() ? PlayerUI.BUTTON_ON : PlayerUI.BUTTON_OFF);
    }

    /**
     * Key change is a pitch shift without tempo change (ExoPlayer's Sonic). Outside karaoke mode
     * the regular pitch is restored. Without audio time stretching the pitch follows the speed, like SmartTube does.
     * Tunneled playback can't take audio processing that changes timing, so no key change there.
     */
    private void applyKey() {
        if (getPlayer() == null || mKaraokeManager == null) {
            return;
        }

        boolean shifted = isRunning() && mKaraokeData.getKeySemitones() != 0 && !isTunneling();
        float pitch = shifted ? mKaraokeData.getKeyPitch() : getPlayerData().getPitch();

        if (!getPlayerTweaksData().isAudioTimeStretchingEnabled() && getPlayer().getSpeed() > 0) {
            pitch *= getPlayer().getSpeed();
        }

        if (Math.abs(getPlayer().getPitch() - pitch) > 0.0001f) {
            getPlayer().setPitch(pitch);
        }
    }

    /**
     * Karaoke's own music level: scales what SmartTube set for the video, doesn't touch the saved player volume.
     */
    private void applyMusicVolume() {
        if (getPlayer() == null || mKaraokeManager == null) {
            return;
        }

        if (!isRunning()) {
            if (mMusicBaseVolume >= 0) {
                getPlayer().setVolume(mMusicBaseVolume);
                mMusicBaseVolume = -1;
            }
            return;
        }

        if (mMusicBaseVolume < 0) {
            mMusicBaseVolume = getPlayer().getVolume();
        }

        getPlayer().setVolume(mMusicBaseVolume * mKaraokeData.getMusicVolumePercent() / 100f * mDuckGain);
    }

    /**
     * Voice first: the music steps back while someone sings (see {@link MusicDucker}).
     */
    private void updateDucking() {
        boolean isWanted = isRunning() && mKaraokeData.isMusicDuckingEnabled() && getPlayer() != null;

        if (isWanted && !mIsDuckTickScheduled) {
            mIsDuckTickScheduled = true;
            mHandler.postDelayed(mDuckTick, DUCK_TICK_MS);
        } else if (!isWanted && mIsDuckTickScheduled) {
            mIsDuckTickScheduled = false;
            mHandler.removeCallbacks(mDuckTick);
            resetDucking();
        }
    }

    private void duckTick() {
        if (!isRunning() || !mKaraokeData.isMusicDuckingEnabled() || getPlayer() == null) {
            mIsDuckTickScheduled = false;
            resetDucking();
            return;
        }

        float gain = mDucker.update(mKaraokeManager.isVoiceActive(), DUCK_TICK_MS);

        // AudioTrack volume changes are ramped by the system, small steps are smooth
        if (Math.abs(gain - mDuckGain) > 0.005f || (gain > 0.995f && mDuckGain != 1)) {
            mDuckGain = gain > 0.995f ? 1 : gain;
            applyMusicVolume();
        }

        mHandler.postDelayed(mDuckTick, DUCK_TICK_MS);
    }

    private void resetDucking() {
        mDucker.reset();

        if (mDuckGain != 1) {
            mDuckGain = 1;
            applyMusicVolume();
        }
    }

    private boolean isTunneling() {
        return getPlayerTweaksData().isTunneledPlaybackEnabled();
    }

    /**
     * Dolby tracks may go straight to the TV/soundbar (passthrough), past any audio processing.
     */
    private boolean isDolbyAudio() {
        FormatItem audio = getPlayer() != null ? getPlayer().getAudioFormat() : null;
        Format format = audio != null && audio.getTrack() != null ? audio.getTrack().format : null;
        String mimeType = format != null ? format.sampleMimeType : null;

        return MimeTypes.AUDIO_AC3.equals(mimeType) || MimeTypes.AUDIO_E_AC3.equals(mimeType) || MimeTypes.AUDIO_E_AC3_JOC.equals(mimeType);
    }

    /**
     * Title plus why the option may have no effect right now.
     */
    private String withLimit(int titleResId, boolean isKey) {
        Context context = getContext();
        String title = context.getString(titleResId);

        if (isKey && isTunneling()) {
            return title + " · " + context.getString(R.string.karaoke_not_with_tunneling);
        }

        return isDolbyAudio() ? title + " · " + context.getString(R.string.karaoke_not_with_dolby) : title;
    }

    private void showKaraokeDialog() {
        Context context = getContext();
        AppDialogPresenter dialog = getAppDialogPresenter();
        String pin = mKaraokeManager.getPin();

        dialog.appendSingleSwitch(UiOptionItem.from(context.getString(R.string.karaoke_mode),
                option -> {
                    if (option.isSelected()) {
                        startKaraoke();
                    } else {
                        stopKaraoke();
                    }
                }, isRunning()));

        // Pages built on click, so they're fresh (new mics, karaoke turned on in the meantime)
        String connectTitle = pin != null ? context.getString(R.string.karaoke_how_to_connect_pin, KaraokePairing.formatPin(pin)) :
                context.getString(R.string.karaoke_how_to_connect);
        dialog.appendSingleButton(UiOptionItem.from(connectTitle, option -> showConnectPage()));
        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.karaoke_mics_count, mKaraokeManager.getMics().size()),
                option -> showMicsPage()));

        appendMicVolumeCategory(dialog);
        appendVoiceEffectCategory(dialog);
        appendVocalRemoverCategory(dialog);
        appendKeyCategory(dialog);
        appendMusicVolumeCategory(dialog);
        appendLatencyCategory(dialog);
        appendOptionsCategory(dialog);

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.karaoke_new_pin), option -> {
            String newPin = mKaraokeManager.renewPin();
            getAppDialogPresenter().closeDialog(); // the menu shows the old PIN, reopening it shows the new one
            MessageHelpers.showLongMessage(context, context.getString(R.string.karaoke_new_pin_done, KaraokePairing.formatPin(newPin)));
        }));
        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.karaoke_find_version), option -> searchKaraokeVersion()));

        dialog.showDialog(context.getString(R.string.karaoke));
    }

    private void showConnectPage() {
        Context context = getContext();
        String pin = mKaraokeManager.getPin();
        String addresses = TextUtils.join(", ", mKaraokeManager.getAddresses());
        String text = context.getString(R.string.karaoke_how_to_connect_desc, mKaraokeManager.getTvName(), addresses,
                pin != null ? KaraokePairing.formatPin(pin) : context.getString(R.string.karaoke_pin_not_needed));

        if (!isRunning()) {
            text = context.getString(R.string.karaoke_turn_on_first) + "\n\n" + text;
        }

        AppDialogPresenter page = getAppDialogPresenter();
        String title = context.getString(R.string.karaoke_how_to_connect);
        page.appendLongTextCategory(title, UiOptionItem.from(text));
        page.showDialog(title);
    }

    private void showMicsPage() {
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
            options.add(UiOptionItem.from(context.getString(isRunning() ? R.string.karaoke_no_mics : R.string.karaoke_turn_on_first)));
        }

        AppDialogPresenter page = getAppDialogPresenter();
        String title = context.getString(R.string.karaoke_mics);
        page.appendStringsCategory(title, options);
        page.showDialog(title);
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

        dialog.appendRadioCategory(withLimit(R.string.karaoke_vocal_remover, false), options);
    }

    private void appendKeyCategory(AppDialogPresenter dialog) {
        Context context = getContext();
        List<OptionItem> options = new ArrayList<>();

        for (int semitones = -KaraokeData.MAX_KEY_SEMITONES; semitones <= KaraokeData.MAX_KEY_SEMITONES; semitones++) {
            int value = semitones;
            String title = semitones == 0 ? context.getString(R.string.karaoke_key_original) : String.format(Locale.US, "%+d", semitones);
            options.add(UiOptionItem.from(title, option -> {
                mKaraokeData.setKeySemitones(value);
                if (isRunning()) {
                    applyKey();
                }
            }, mKaraokeData.getKeySemitones() == semitones));
        }

        dialog.appendRadioCategory(withLimit(R.string.karaoke_key, true), options);
    }

    private void appendMusicVolumeCategory(AppDialogPresenter dialog) {
        List<OptionItem> options = new ArrayList<>();

        for (int percent = 0; percent <= 100; percent += 10) {
            int value = percent;
            options.add(UiOptionItem.from(String.format(Locale.US, "%d%%", percent), option -> {
                mKaraokeData.setMusicVolumePercent(value);
                applyMusicVolume();
            }, mKaraokeData.getMusicVolumePercent() == percent));
        }

        dialog.appendRadioCategory(getContext().getString(R.string.karaoke_music_volume), options);
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

        options.add(UiOptionItem.from(context.getString(R.string.karaoke_auto_level), option -> {
            mKaraokeData.setAutoLevelEnabled(option.isSelected());
            mKaraokeManager.applySettings();
        }, mKaraokeData.isAutoLevelEnabled()));

        options.add(UiOptionItem.from(context.getString(R.string.karaoke_duck_music), option -> {
            mKaraokeData.setMusicDuckingEnabled(option.isSelected());
            updateDucking();
        }, mKaraokeData.isMusicDuckingEnabled()));

        options.add(UiOptionItem.from(context.getString(R.string.karaoke_noise_gate), option -> {
            mKaraokeData.setNoiseGateEnabled(option.isSelected());
            mKaraokeManager.applySettings();
        }, mKaraokeData.isNoiseGateEnabled()));

        options.add(UiOptionItem.from(context.getString(R.string.karaoke_use_tv_mic),
                option -> enableTvMic(option.isSelected()), mKaraokeData.isLocalMicEnabled()));

        options.add(UiOptionItem.from(context.getString(R.string.karaoke_require_pin), option -> {
            mKaraokeManager.setPinRequired(option.isSelected());
            String pin = mKaraokeManager.getPin();
            if (pin != null) { // phones that joined without it have to enter it now
                MessageHelpers.showLongMessage(context, context.getString(R.string.karaoke_pin_required_now, KaraokePairing.formatPin(pin)));
            }
        }, mKaraokeData.isPinRequired()));

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
