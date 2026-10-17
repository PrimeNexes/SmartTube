package com.liskovsoft.smartyoutubetv2.karaoke.core;

/**
 * Peak limiter with instant attack and smooth release. Keeps loud singing (or several mics at once) from clipping.
 */
public final class Limiter {
    public static final float DEFAULT_CEILING = 0.9f;
    private final float mRelease;
    private final float mCeiling;
    private float mGain = 1;

    public Limiter(int sampleRate) {
        this(sampleRate, DEFAULT_CEILING);
    }

    public Limiter(int sampleRate, float ceiling) {
        mRelease = 1 - NoiseGate.timeConstant(sampleRate, 0.06f);
        mCeiling = ceiling;
    }

    public void process(float[] buffer, int count) {
        for (int i = 0; i < count; i++) {
            float level = Math.abs(buffer[i]);
            float target = level > mCeiling ? mCeiling / level : 1;

            if (target < mGain) {
                mGain = target;
            } else {
                mGain += (target - mGain) * mRelease;
            }

            float value = buffer[i] * mGain;
            buffer[i] = Math.max(-1f, Math.min(1f, value));
        }
    }

    public float getGain() {
        return mGain;
    }
}
