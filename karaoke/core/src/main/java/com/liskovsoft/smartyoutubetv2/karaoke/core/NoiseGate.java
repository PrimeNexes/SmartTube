package com.liskovsoft.smartyoutubetv2.karaoke.core;

/**
 * Mutes a mic between phrases, so room noise and the music picked up by the phone don't get amplified
 * (less mud and less risk of acoustic feedback).
 */
public final class NoiseGate {
    /** ~ -38 dBFS. Phone mics held close to the mouth are way louder while singing. */
    public static final float DEFAULT_THRESHOLD = 0.0125f;
    private final float mEnvelopeDecay;
    private final float mAttack;
    private final float mRelease;
    private final int mHoldSamples;
    private float mThreshold = DEFAULT_THRESHOLD;
    private float mEnvelope;
    private float mGain;
    private int mHold;

    public NoiseGate(int sampleRate) {
        mEnvelopeDecay = timeConstant(sampleRate, 0.07f);
        mAttack = 1 - timeConstant(sampleRate, 0.002f);
        mRelease = 1 - timeConstant(sampleRate, 0.08f);
        mHoldSamples = sampleRate / 5; // 200 ms
    }

    public void setThreshold(float threshold) {
        mThreshold = threshold;
    }

    public void process(float[] buffer, int count) {
        for (int i = 0; i < count; i++) {
            float level = Math.abs(buffer[i]);
            mEnvelope = level > mEnvelope ? level : mEnvelope * mEnvelopeDecay;

            if (mEnvelope > mThreshold) {
                mHold = mHoldSamples;
            } else if (mHold > 0) {
                mHold--;
            }

            if (mHold > 0) {
                mGain += (1 - mGain) * mAttack;
            } else {
                mGain -= mGain * mRelease;
            }

            buffer[i] *= mGain;
        }
    }

    public boolean isOpen() {
        return mHold > 0;
    }

    public void reset() {
        mEnvelope = 0;
        mGain = 0;
        mHold = 0;
    }

    /**
     * Per sample multiplier of an exponential decay with the given time constant.
     */
    static float timeConstant(int sampleRate, float seconds) {
        return (float) Math.exp(-1.0 / (seconds * sampleRate));
    }
}
