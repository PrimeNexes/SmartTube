package com.liskovsoft.smartyoutubetv2.karaoke.core;

import java.util.Arrays;

/**
 * Classic karaoke voice effects: Freeverb style reverb (4 damped combs + 2 allpasses, mono) and a feedback echo.
 */
public final class VoiceReverb {
    public static final int PRESET_OFF = 0;
    public static final int PRESET_ROOM = 1;
    public static final int PRESET_HALL = 2;
    public static final int PRESET_ECHO = 3;
    private static final int[] COMB_TUNING_44K = {1116, 1188, 1277, 1356};
    private static final int[] ALLPASS_TUNING_44K = {556, 441};
    private static final float INPUT_GAIN = 0.03f;
    private static final float WET_SCALE = 3f;
    private static final float ALLPASS_FEEDBACK = 0.5f;
    private static final float DENORMAL_LIMIT = 1e-15f;
    private static final int ECHO_DELAY_MS = 180;
    private final float[][] mCombs;
    private final int[] mCombIndex;
    private final float[] mCombStore;
    private final float[][] mAllpasses;
    private final int[] mAllpassIndex;
    private final float[] mEcho;
    private int mEchoIndex;
    private int mPreset = PRESET_OFF;
    private float mFeedback;
    private float mDamp;
    private float mWet;
    private float mEchoFeedback;
    private float mEchoWet;

    public VoiceReverb(int sampleRate) {
        float scale = sampleRate / 44_100f;

        mCombs = new float[COMB_TUNING_44K.length][];
        mCombIndex = new int[COMB_TUNING_44K.length];
        mCombStore = new float[COMB_TUNING_44K.length];
        for (int i = 0; i < mCombs.length; i++) {
            mCombs[i] = new float[Math.round(COMB_TUNING_44K[i] * scale)];
        }

        mAllpasses = new float[ALLPASS_TUNING_44K.length][];
        mAllpassIndex = new int[ALLPASS_TUNING_44K.length];
        for (int i = 0; i < mAllpasses.length; i++) {
            mAllpasses[i] = new float[Math.round(ALLPASS_TUNING_44K[i] * scale)];
        }

        mEcho = new float[sampleRate * ECHO_DELAY_MS / 1_000];
    }

    public void setPreset(int preset) {
        if (preset == mPreset) {
            return;
        }

        clear(); // don't replay a stale tail

        mPreset = preset;

        switch (preset) {
            case PRESET_ROOM:
                apply(0.70f, 0.45f, 0.20f, 0, 0);
                break;
            case PRESET_HALL:
                apply(0.84f, 0.30f, 0.28f, 0, 0);
                break;
            case PRESET_ECHO:
                apply(0.70f, 0.45f, 0.12f, 0.35f, 0.35f);
                break;
            default:
                mPreset = PRESET_OFF;
                break;
        }
    }

    public int getPreset() {
        return mPreset;
    }

    /**
     * Adds the effect on top of the dry signal in place.
     */
    public void process(float[] buffer, int count) {
        if (mPreset == PRESET_OFF) {
            return;
        }

        for (int i = 0; i < count; i++) {
            float dry = buffer[i];
            float input = dry * INPUT_GAIN;
            float reverb = 0;

            for (int c = 0; c < mCombs.length; c++) {
                float[] comb = mCombs[c];
                int index = mCombIndex[c];
                float output = comb[index];
                float store = output * (1 - mDamp) + mCombStore[c] * mDamp;
                if (Math.abs(store) < DENORMAL_LIMIT) {
                    store = 0;
                }
                mCombStore[c] = store;
                comb[index] = input + store * mFeedback;
                mCombIndex[c] = index + 1 < comb.length ? index + 1 : 0;
                reverb += output;
            }

            for (int a = 0; a < mAllpasses.length; a++) {
                float[] allpass = mAllpasses[a];
                int index = mAllpassIndex[a];
                float delayed = allpass[index];
                float output = delayed - reverb;
                float store = reverb + delayed * ALLPASS_FEEDBACK;
                allpass[index] = Math.abs(store) < DENORMAL_LIMIT ? 0 : store;
                mAllpassIndex[a] = index + 1 < allpass.length ? index + 1 : 0;
                reverb = output;
            }

            float result = dry + reverb * mWet * WET_SCALE;

            if (mEchoWet > 0) {
                float delayed = mEcho[mEchoIndex];
                float store = dry + delayed * mEchoFeedback;
                mEcho[mEchoIndex] = Math.abs(store) < DENORMAL_LIMIT ? 0 : store;
                mEchoIndex = mEchoIndex + 1 < mEcho.length ? mEchoIndex + 1 : 0;
                result += delayed * mEchoWet;
            }

            buffer[i] = result;
        }
    }

    public void clear() {
        for (float[] comb : mCombs) {
            Arrays.fill(comb, 0);
        }
        for (float[] allpass : mAllpasses) {
            Arrays.fill(allpass, 0);
        }
        Arrays.fill(mCombStore, 0);
        Arrays.fill(mEcho, 0);
    }

    private void apply(float feedback, float damp, float wet, float echoFeedback, float echoWet) {
        mFeedback = feedback;
        mDamp = damp;
        mWet = wet;
        mEchoFeedback = echoFeedback;
        mEchoWet = echoWet;
    }
}
