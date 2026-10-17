package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataChangeBase;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokePairing;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokeProtocol;
import com.liskovsoft.smartyoutubetv2.karaoke.core.VoiceReverb;

import java.security.SecureRandom;

/**
 * Karaoke mode settings. Music related ones (vocal remover, key) are applied only while karaoke mode is on.
 */
public class KaraokeData extends DataChangeBase {
    private static final String KARAOKE_DATA = "karaoke_data";
    public static final int LATENCY_LOW = 0;
    public static final int LATENCY_NORMAL = 1;
    public static final int LATENCY_STABLE = 2;
    public static final int MAX_KEY_SEMITONES = 6;
    @SuppressLint("StaticFieldLeak")
    private static KaraokeData sInstance;
    private final AppPrefs mAppPrefs;
    private int mMicVolumePercent;
    private int mVoiceEffect;
    private int mVocalRemoverPercent;
    private int mKeySemitones;
    private int mLatencyMode;
    private boolean mIsNoiseGateEnabled;
    private boolean mIsLocalMicEnabled;
    private int mPort;
    private boolean mIsPinRequired;
    private String mPin;
    private boolean mIsAutoLevelEnabled;
    private long mTvId;
    private int mMusicVolumePercent;

    private KaraokeData(Context context) {
        mAppPrefs = AppPrefs.instance(context);
        restoreState();
    }

    public static KaraokeData instance(Context context) {
        if (sInstance == null) {
            sInstance = new KaraokeData(context.getApplicationContext());
        }

        return sInstance;
    }

    public int getMicVolumePercent() {
        return mMicVolumePercent;
    }

    public void setMicVolumePercent(int percent) {
        mMicVolumePercent = percent;
        persistState();
    }

    /**
     * See {@link VoiceReverb} presets
     */
    public int getVoiceEffect() {
        return mVoiceEffect;
    }

    public void setVoiceEffect(int preset) {
        mVoiceEffect = preset;
        persistState();
    }

    public int getVocalRemoverPercent() {
        return mVocalRemoverPercent;
    }

    public void setVocalRemoverPercent(int percent) {
        mVocalRemoverPercent = percent;
        persistState();
    }

    public int getKeySemitones() {
        return mKeySemitones;
    }

    public void setKeySemitones(int semitones) {
        mKeySemitones = Math.max(-MAX_KEY_SEMITONES, Math.min(MAX_KEY_SEMITONES, semitones));
        persistState();
    }

    /**
     * Player pitch multiplier that matches the key shift.
     */
    public float getKeyPitch() {
        return (float) Math.pow(2, mKeySemitones / 12.0);
    }

    public int getLatencyMode() {
        return mLatencyMode;
    }

    public void setLatencyMode(int latencyMode) {
        mLatencyMode = latencyMode;
        persistState();
    }

    /**
     * How much audio is queued per mic to ride out Wi-Fi jitter.
     */
    public int getLatencyMs() {
        return toLatencyMs(mLatencyMode);
    }

    public static int toLatencyMs(int latencyMode) {
        switch (latencyMode) {
            case LATENCY_LOW:
                return 25;
            case LATENCY_STABLE:
                return 100;
            default:
                return 50;
        }
    }

    public boolean isNoiseGateEnabled() {
        return mIsNoiseGateEnabled;
    }

    public void setNoiseGateEnabled(boolean enabled) {
        mIsNoiseGateEnabled = enabled;
        persistState();
    }

    /**
     * Evens out quiet and loud mics/singers.
     */
    public boolean isAutoLevelEnabled() {
        return mIsAutoLevelEnabled;
    }

    public void setAutoLevelEnabled(boolean enabled) {
        mIsAutoLevelEnabled = enabled;
        persistState();
    }

    public boolean isLocalMicEnabled() {
        return mIsLocalMicEnabled;
    }

    public void setLocalMicEnabled(boolean enabled) {
        mIsLocalMicEnabled = enabled;
        persistState();
    }

    public int getPort() {
        return mPort;
    }

    /**
     * Random, stable id of this TV, so phones don't mix up TVs with the same name.
     */
    public long getTvId() {
        if (mTvId == 0) {
            long id;
            do {
                id = new SecureRandom().nextLong();
            } while (id == 0);
            mTvId = id;
            persistState();
        }

        return mTvId;
    }

    /**
     * Music level while karaoke mode is on, on top of the regular player volume. Not saved into the player settings.
     */
    public int getMusicVolumePercent() {
        return mMusicVolumePercent;
    }

    public void setMusicVolumePercent(int percent) {
        mMusicVolumePercent = Math.max(0, Math.min(100, percent));
        persistState();
    }

    public boolean isPinRequired() {
        return mIsPinRequired;
    }

    public void setPinRequired(boolean required) {
        mIsPinRequired = required;
        persistState();
    }

    /**
     * Phones enter it once and remember it, so it stays the same until {@link #renewPin()}.
     */
    public String getPin() {
        if (mPin == null) {
            renewPin();
        }

        return mPin;
    }

    public String renewPin() {
        mPin = KaraokePairing.generatePin(new SecureRandom());
        persistState();
        return mPin;
    }

    private void restoreState() {
        String data = mAppPrefs.getData(KARAOKE_DATA);

        String[] split = Helpers.splitData(data);

        mMicVolumePercent = Helpers.parseInt(split, 0, 100);
        mVoiceEffect = Helpers.parseInt(split, 1, VoiceReverb.PRESET_ROOM);
        mVocalRemoverPercent = Helpers.parseInt(split, 2, 0);
        mKeySemitones = Helpers.parseInt(split, 3, 0);
        mLatencyMode = Helpers.parseInt(split, 4, LATENCY_NORMAL);
        mIsNoiseGateEnabled = Helpers.parseBoolean(split, 5, true);
        mIsLocalMicEnabled = Helpers.parseBoolean(split, 6, false);
        mPort = Helpers.parseInt(split, 7, KaraokeProtocol.DEFAULT_PORT);
        mIsPinRequired = Helpers.parseBoolean(split, 8, true);
        mPin = KaraokePairing.normalizePin(Helpers.parseStr(split, 9));
        mIsAutoLevelEnabled = Helpers.parseBoolean(split, 10, true);
        mTvId = Helpers.parseLong(split, 11, 0);
        mMusicVolumePercent = Helpers.parseInt(split, 12, 100);
    }

    private void persistState() {
        mAppPrefs.setData(KARAOKE_DATA, Helpers.mergeData(
                mMicVolumePercent, mVoiceEffect, mVocalRemoverPercent, mKeySemitones, mLatencyMode,
                mIsNoiseGateEnabled, mIsLocalMicEnabled, mPort, mIsPinRequired, mPin, mIsAutoLevelEnabled,
                mTvId, mMusicVolumePercent
        ));

        onDataChange();
    }
}
