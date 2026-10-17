package com.liskovsoft.smartyoutubetv2.karaoke.core;

import java.util.Arrays;

/**
 * Finds the pitch of a singing voice with YIN (de Cheveigné and Kawahara, 2002).<br/>
 * The period is searched on a 4x decimated copy, cheap enough to run for every 5 ms frame on any phone, then refined
 * at the full sample rate around the coarse result. Covers {@link #MIN_HZ} to {@link #MAX_HZ}: bass to soprano.<br/>
 * Not thread safe, feed it from one audio thread.
 */
public final class PitchDetector {
    public static final float MIN_HZ = 75;
    public static final float MAX_HZ = 1000;
    private static final int DECIMATION = 4;
    /** YIN: the first dip of the normalized difference below this is the period. Lower is stricter. */
    private static final float THRESHOLD = 0.15f;
    /** Quieter than about -50 dBFS RMS is silence. */
    private static final float MIN_RMS = 0.003f;
    private static final int REFINE_RADIUS = DECIMATION + 1;
    private final int mSampleRate;
    private final Biquad mHighPass;
    private final Biquad mAntiAlias1;
    private final Biquad mAntiAlias2;
    // Low passed signal at the full rate, for the refinement
    private final float[] mFull;
    private final int mFullMask;
    private int mFullWritten;
    // Decimated signal for the coarse search
    private final float[] mLow;
    private final int mLowMask;
    private int mLowWritten;
    private int mPhase;
    private final int mWindowLow;
    private final int mTauMinLow;
    private final int mTauMaxLow;
    private final int mWindowFull;
    private final float[] mFrame;
    private final float[] mDiff;
    private final float[] mRefine = new float[2 * REFINE_RADIUS + 3];
    private float mConfidence;

    public PitchDetector(int sampleRate) {
        mSampleRate = sampleRate;
        mHighPass = Biquad.highPass(sampleRate, 50, Biquad.Q_BUTTERWORTH); // rumble, handling noise
        // 4th order Butterworth low pass: no aliasing into the decimated band, and the period stands out
        mAntiAlias1 = Biquad.lowPass(sampleRate, 2_500, 0.5411961f);
        mAntiAlias2 = Biquad.lowPass(sampleRate, 2_500, 1.3065630f);

        int lowRate = sampleRate / DECIMATION;
        mTauMinLow = (int) Math.floor(lowRate / MAX_HZ);
        mTauMaxLow = (int) Math.ceil(lowRate / MIN_HZ);
        mWindowLow = mTauMaxLow;
        mWindowFull = mWindowLow * DECIMATION;

        mLow = new float[nextPowerOfTwo(mWindowLow + mTauMaxLow)];
        mLowMask = mLow.length - 1;
        mFull = new float[nextPowerOfTwo(mWindowFull + mTauMaxLow * DECIMATION + REFINE_RADIUS + 2)];
        mFullMask = mFull.length - 1;
        mFrame = new float[mWindowLow + mTauMaxLow];
        mDiff = new float[mTauMaxLow + 1];
    }

    public void write(float[] samples, int offset, int count) {
        for (int i = offset; i < offset + count; i++) {
            float filtered = mAntiAlias2.process(mAntiAlias1.process(mHighPass.process(samples[i])));
            mFull[mFullWritten++ & mFullMask] = filtered;

            if (++mPhase == DECIMATION) {
                mPhase = 0;
                mLow[mLowWritten++ & mLowMask] = filtered;
            }
        }
    }

    /**
     * Looks at the latest ~27 ms of audio.
     * @return the period in samples at the full rate (fractional), 0 if there's no clear pitch: silence, noise,
     * consonants, breaths
     */
    public float detect() {
        mConfidence = 0;

        int length = mFrame.length;
        if (mLowWritten < length || rms() < MIN_RMS) {
            return 0;
        }

        for (int i = 0; i < length; i++) {
            mFrame[i] = mLow[(mLowWritten - length + i) & mLowMask];
        }

        // Difference function, then cumulative mean normalized (YIN steps 2 and 3)
        float[] x = mFrame;
        float[] diff = mDiff;
        float runningSum = 0;
        diff[0] = 1;
        for (int tau = 1; tau <= mTauMaxLow; tau++) {
            float sum = 0;
            for (int j = 0; j < mWindowLow; j++) {
                float delta = x[j] - x[j + tau];
                sum += delta * delta;
            }
            runningSum += sum;
            diff[tau] = runningSum > 0 ? sum * tau / runningSum : 1;
        }

        // Absolute threshold (step 4): the first dip, which avoids picking a multiple of the period
        int tau = -1;
        for (int t = mTauMinLow; t <= mTauMaxLow; t++) {
            if (diff[t] < THRESHOLD) {
                while (t + 1 <= mTauMaxLow && diff[t + 1] < diff[t]) {
                    t++;
                }
                tau = t;
                break;
            }
        }

        if (tau < 0) {
            return 0;
        }

        mConfidence = 1 - diff[tau];

        return refine(interpolate(diff, tau, mTauMaxLow) * DECIMATION);
    }

    /**
     * 1 for a pure tone, toward 0 for noise. 0 when the last {@link #detect()} found nothing.
     */
    public float getConfidence() {
        return mConfidence;
    }

    public void reset() {
        mHighPass.reset();
        mAntiAlias1.reset();
        mAntiAlias2.reset();
        Arrays.fill(mFull, 0);
        Arrays.fill(mLow, 0);
        mFullWritten = 0;
        mLowWritten = 0;
        mPhase = 0;
        mConfidence = 0;
    }

    public int getSampleRate() {
        return mSampleRate;
    }

    /**
     * Difference function at the full rate near the coarse period: the coarse one is only good to about a sample
     * at 12 kHz, 5 cents at 1 kHz.
     */
    private float refine(float coarsePeriod) {
        int center = Math.round(coarsePeriod);
        int first = Math.max(center - REFINE_RADIUS - 1, 2);
        int count = 2 * REFINE_RADIUS + 3;
        int end = mFullWritten; // exclusive

        int best = -1;
        for (int k = 0; k < count; k++) {
            int tau = first + k;
            float sum = 0;
            int start = end - mWindowFull;
            for (int j = 0; j < mWindowFull; j++) {
                float delta = mFull[(start + j) & mFullMask] - mFull[(start + j - tau) & mFullMask];
                sum += delta * delta;
            }
            mRefine[k] = sum;
            if (k > 0 && k < count - 1 && (best < 0 || sum < mRefine[best])) {
                best = k;
            }
        }

        return first + interpolate(mRefine, best, count - 1);
    }

    private float rms() {
        float sum = 0;
        int start = mFullWritten - mWindowFull;
        for (int j = 0; j < mWindowFull; j++) {
            float value = mFull[(start + j) & mFullMask];
            sum += value * value;
        }
        return (float) Math.sqrt(sum / mWindowFull);
    }

    /**
     * Parabola through the minimum and its neighbours: sub-sample position of the true minimum.
     */
    private static float interpolate(float[] values, int index, int lastIndex) {
        if (index <= 0 || index >= lastIndex) {
            return index;
        }

        float left = values[index - 1];
        float middle = values[index];
        float right = values[index + 1];
        float curvature = left - 2 * middle + right;

        if (curvature <= 0) {
            return index;
        }

        float shift = 0.5f * (left - right) / curvature;
        return index + Math.max(-0.5f, Math.min(0.5f, shift));
    }

    private static int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }
}
