package com.liskovsoft.smartyoutubetv2.karaoke.core;

/**
 * Gets a phone mic ready for the mix before it's sent, with the phone held near the mouth:<br/>
 * 1. High pass at 90 Hz: handling noise, "p" and "b" pops, rumble.<br/>
 * 2. Leveler: the low latency capture paths (VOICE_PERFORMANCE, UNPROCESSED) are recorded very quietly by spec,
 *    around -36 dBFS RMS for a loud voice, so the TV took normal singing for room noise. The singing is brought to
 *    {@link #TARGET_RMS}, learning only while someone sings and slowly enough not to pump.<br/>
 * 3. Expander: between phrases the phone mostly hears the TV (the music, and the singer's own voice from the
 *    speakers). That's turned down by {@link #EXPANDER_DEPTH_DB} dB so it doesn't muddy the mix or feed back.<br/>
 * Singing is told apart from the rest by its level: well above a slowly tracked floor (the room and the TV as heard
 * by the phone). Not thread safe: one audio thread.
 */
public final class VoiceLeveler {
    /** -18 dBFS RMS: room for loud notes, the TV fine tunes the level. */
    public static final float TARGET_RMS = 0.125f;
    public static final float MAX_GAIN_DB = 30;
    public static final float MIN_GAIN_DB = -12;
    public static final float EXPANDER_DEPTH_DB = 15;
    /** A phone near the mouth hears the singer 15-25 dB over the TV. */
    private static final float VOICE_OVER_FLOOR_DB = 9;
    /** Raw level below which it's silence whatever the floor says, ~ -66 dBFS RMS. */
    private static final float SILENCE_POWER = 0.0005f * 0.0005f;
    private static final float LEVEL_MS = 20;
    private static final float LOUDNESS_MS = 400;
    private static final float GAIN_DOWN_MS = 300;
    private static final float GAIN_UP_MS = 1_500;
    private static final float FLOOR_FALL_MS = 100;
    private static final float FLOOR_RISE_DB_PER_S = 3;
    private static final float FLOOR_RISE_SINGING_DB_PER_S = 0.5f;
    /** Keeps the expander open over consonants and short breaths. */
    private static final float HOLD_MS = 300;
    private static final float EXPANDER_CLOSE_MS = 120;
    private final int mSampleRate;
    private final Biquad mHighPass;
    private float mLevel = -1; // mean square, -1 until the first block
    private float mFloor;
    private float mLoudness;
    private float mGainDb;
    private float mExpanderDb = -EXPANDER_DEPTH_DB;
    private float mAppliedGain;
    private float mHoldMs;
    private boolean mIsSinging;

    /**
     * @param initialGainDb starting point for the capture path, so the first phrase is about right
     */
    public VoiceLeveler(int sampleRate, float initialGainDb) {
        mSampleRate = sampleRate;
        mHighPass = Biquad.highPass(sampleRate, 90, Biquad.Q_BUTTERWORTH);
        mGainDb = clamp(initialGainDb, MIN_GAIN_DB, MAX_GAIN_DB);
        mLoudness = TARGET_RMS * TARGET_RMS / dbToPower(mGainDb);
        mAppliedGain = dbToAmp(mGainDb + mExpanderDb);
    }

    /**
     * In place, any block size. Gain changes are ramped across the block.
     */
    public void process(float[] samples, int count) {
        if (count <= 0) {
            return;
        }

        float blockMs = 1000f * count / mSampleRate;
        float sum = 0;

        for (int i = 0; i < count; i++) {
            float value = mHighPass.process(samples[i]);
            samples[i] = value;
            sum += value * value;
        }

        float power = sum / count;

        if (mLevel < 0) { // whatever the phone hears at the start is the floor
            mLevel = power;
            mFloor = Math.max(power, SILENCE_POWER);
        }

        mLevel += (power - mLevel) * coefficient(blockMs, LEVEL_MS);

        // Floor: follows quiet moments fast, creeps up slowly, slower still while someone sings
        if (mLevel < mFloor) {
            mFloor += (mLevel - mFloor) * coefficient(blockMs, FLOOR_FALL_MS);
        } else {
            float riseDb = (mIsSinging ? FLOOR_RISE_SINGING_DB_PER_S : FLOOR_RISE_DB_PER_S) * blockMs / 1000;
            mFloor = Math.min(mLevel, mFloor * dbToPower(riseDb));
        }
        mFloor = Math.max(mFloor, SILENCE_POWER);

        boolean isVoice = mLevel > mFloor * dbToPower(VOICE_OVER_FLOOR_DB);
        mHoldMs = isVoice ? HOLD_MS : Math.max(0, mHoldMs - blockMs);
        mIsSinging = mHoldMs > 0;

        // Leveler learns from singing only, so the TV sound between phrases isn't pulled up
        if (isVoice) {
            mLoudness += (power - mLoudness) * coefficient(blockMs, LOUDNESS_MS);
            float wantedDb = clamp(powerToDb(TARGET_RMS * TARGET_RMS / mLoudness), MIN_GAIN_DB, MAX_GAIN_DB);
            mGainDb += (wantedDb - mGainDb) * coefficient(blockMs, wantedDb < mGainDb ? GAIN_DOWN_MS : GAIN_UP_MS);
        }

        // Expander opens within the block (no clipped word starts), closes smoothly
        if (mIsSinging) {
            mExpanderDb = 0;
        } else {
            mExpanderDb += (-EXPANDER_DEPTH_DB - mExpanderDb) * coefficient(blockMs, EXPANDER_CLOSE_MS);
        }

        float endGain = dbToAmp(mGainDb + mExpanderDb);
        float step = (endGain - mAppliedGain) / count;
        float gain = mAppliedGain;
        for (int i = 0; i < count; i++) {
            gain += step;
            samples[i] *= gain;
        }
        mAppliedGain = endGain;
    }

    /**
     * Leveler gain without the expander.
     */
    public float getGainDb() {
        return mGainDb;
    }

    /**
     * Someone sings now (or did a moment ago).
     */
    public boolean isSinging() {
        return mIsSinging;
    }

    private static float coefficient(float periodMs, float timeConstantMs) {
        return (float) (1 - Math.exp(-periodMs / timeConstantMs));
    }

    private static float dbToPower(float db) {
        return (float) Math.pow(10, db / 10);
    }

    private static float dbToAmp(float db) {
        return (float) Math.pow(10, db / 20);
    }

    private static float powerToDb(float power) {
        return (float) (10 * Math.log10(Math.max(power, 1e-12f)));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
