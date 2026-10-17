package com.liskovsoft.smartyoutubetv2.karaoke.core;

/**
 * Automatic mic level (slow AGC). Phone mics have no gain control on the karaoke capture path and every
 * phone and singer differs, so a quiet voice would drown in the music. Brings the singing level towards a
 * target, adapts slowly (no pumping) and only while someone sings, so room noise isn't pulled up between phrases.
 */
public final class AutoLevel {
    /** ~ -18 dBFS RMS: about as loud as typical YouTube music. */
    public static final float TARGET_RMS = 0.125f;
    public static final float MAX_GAIN = 8f; // +18 dB
    public static final float MIN_GAIN = 0.25f; // -12 dB
    /** Below ~ -45 dBFS nobody is singing: keep the current gain. */
    private static final float ACTIVITY_RMS = 0.0056f;
    private static final float DETECTOR_SECONDS = 0.1f;
    private static final float ATTACK_SECONDS = 0.05f; // turn down fast when it gets loud
    private static final float RELEASE_SECONDS = 1.5f; // turn up slowly
    private final int mSampleRate;
    private int mBlockSize;
    private float mDetectorCoef;
    private float mAttackCoef;
    private float mReleaseCoef;
    private float mMeanSquare;
    private float mGain = 1;

    public AutoLevel(int sampleRate) {
        mSampleRate = sampleRate;
    }

    public float getGain() {
        return mGain;
    }

    public void reset() {
        mMeanSquare = 0;
        mGain = 1;
    }

    /**
     * Processes one block in place. Gain changes are ramped across the block to avoid zipper noise.
     */
    public void process(float[] buffer, int count) {
        if (count <= 0) {
            return;
        }

        if (count != mBlockSize) {
            mBlockSize = count;
            float blockSeconds = (float) count / mSampleRate;
            mDetectorCoef = coefficient(blockSeconds, DETECTOR_SECONDS);
            mAttackCoef = coefficient(blockSeconds, ATTACK_SECONDS);
            mReleaseCoef = coefficient(blockSeconds, RELEASE_SECONDS);
        }

        float sum = 0;
        for (int i = 0; i < count; i++) {
            sum += buffer[i] * buffer[i];
        }

        mMeanSquare += (sum / count - mMeanSquare) * mDetectorCoef;

        float startGain = mGain;
        float rms = (float) Math.sqrt(mMeanSquare);

        if (rms > ACTIVITY_RMS) {
            float desired = Math.max(MIN_GAIN, Math.min(MAX_GAIN, TARGET_RMS / rms));
            mGain += (desired - mGain) * (desired < mGain ? mAttackCoef : mReleaseCoef);
        }

        float step = (mGain - startGain) / count;
        float gain = startGain;
        for (int i = 0; i < count; i++) {
            gain += step;
            buffer[i] *= gain;
        }
    }

    /**
     * One pole smoothing coefficient for the given update period and time constant.
     */
    private static float coefficient(float periodSeconds, float timeConstantSeconds) {
        return (float) (1 - Math.exp(-periodSeconds / timeConstantSeconds));
    }
}
