package com.liskovsoft.smartyoutubetv2.karaoke.core;

/**
 * Second order IIR filter (RBJ audio EQ cookbook), transposed direct form II, one channel.
 */
public final class Biquad {
    public static final float Q_BUTTERWORTH = 0.70710677f;
    private static final float DENORMAL_LIMIT = 1e-15f;
    private final float mB0;
    private final float mB1;
    private final float mB2;
    private final float mA1;
    private final float mA2;
    private float mZ1;
    private float mZ2;

    private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
        mB0 = (float) (b0 / a0);
        mB1 = (float) (b1 / a0);
        mB2 = (float) (b2 / a0);
        mA1 = (float) (a1 / a0);
        mA2 = (float) (a2 / a0);
    }

    public static Biquad lowPass(int sampleRate, float frequencyHz, float q) {
        double w0 = 2 * Math.PI * frequencyHz / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2 * q);
        return new Biquad((1 - cos) / 2, 1 - cos, (1 - cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
    }

    public static Biquad highPass(int sampleRate, float frequencyHz, float q) {
        double w0 = 2 * Math.PI * frequencyHz / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2 * q);
        return new Biquad((1 + cos) / 2, -(1 + cos), (1 + cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
    }

    public float process(float x) {
        float y = mB0 * x + mZ1;
        mZ1 = mB1 * x - mA1 * y + mZ2;
        mZ2 = mB2 * x - mA2 * y;

        // Denormals are painfully slow on some TV SoCs
        if (Math.abs(mZ1) < DENORMAL_LIMIT) {
            mZ1 = 0;
        }
        if (Math.abs(mZ2) < DENORMAL_LIMIT) {
            mZ2 = 0;
        }

        return y;
    }

    public void reset() {
        mZ1 = 0;
        mZ2 = 0;
    }
}
