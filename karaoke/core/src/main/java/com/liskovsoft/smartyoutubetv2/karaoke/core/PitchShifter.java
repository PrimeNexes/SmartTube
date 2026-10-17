package com.liskovsoft.smartyoutubetv2.karaoke.core;

import java.util.Arrays;

/**
 * Shifts the pitch of a voice by a small ratio (up to 2 semitones) with low delay.<br/>
 * The voice goes through a delay line that is read a bit faster (higher pitch) or slower (lower pitch) than it's
 * written. When the delay leaves its range, the read position jumps by exactly one pitch period with a short
 * crossfade: one period earlier the voice looks the same, so the jump is inaudible.<br/>
 * Delay: {@link #MIN_DELAY} samples plus half a period on average (~5 ms for most voices), where overlap-add methods
 * like PSOLA need two periods. Formants move with the pitch, by 3% for a half semitone correction, which is hard to
 * hear.<br/>
 * Not thread safe, use it from one audio thread.
 */
public final class PitchShifter {
    /** Two semitones. */
    public static final float MAX_RATIO = 1.122462f;
    public static final float MIN_RATIO = 1 / MAX_RATIO;
    /** Closest the read position gets to the newest sample: keeps room for interpolation and crossfades. */
    public static final int MIN_DELAY = 64;
    private static final int FADE = 240;
    private static final int MARGIN = 32;
    private static final float DEFAULT_PERIOD = 256;
    private final float[] mBuffer;
    private final int mMask;
    private final float mMaxPeriod;
    private int mWritten;
    /** Distance from the write position to the read position, in samples. */
    private double mDelay;
    /** Delay of the read position we're crossfading to. */
    private double mFadeDelay;
    private int mFadeLeft;
    private float mPeriod = DEFAULT_PERIOD;

    /**
     * @param maxPeriod longest pitch period in samples (lowest voice)
     */
    public PitchShifter(int maxPeriod) {
        mMaxPeriod = maxPeriod;
        int size = 1;
        while (size < MIN_DELAY + maxPeriod + MARGIN + FADE * 2 + 8) {
            size <<= 1;
        }
        mBuffer = new float[size];
        mMask = size - 1;
        reset();
    }

    /**
     * @param ratioStart pitch ratio at the first sample, ramps linearly to ratioEnd at the last one (no zipper noise)
     * @param period current pitch period in samples, 0 if unknown (the last known one is used)
     */
    public void process(float[] in, float[] out, int count, float ratioStart, float ratioEnd, float period) {
        if (period > 0) {
            mPeriod = Math.min(period, mMaxPeriod);
        }

        ratioStart = clamp(ratioStart);
        ratioEnd = clamp(ratioEnd);
        float ratioStep = count > 1 ? (ratioEnd - ratioStart) / count : 0;

        for (int i = 0; i < count; i++) {
            mBuffer[mWritten++ & mMask] = in[i];

            float ratio = ratioStart + ratioStep * i;
            float value = read(mDelay);

            if (mFadeLeft > 0) {
                float weight = (FADE - mFadeLeft + 0.5f) / FADE;
                value += (read(mFadeDelay) - value) * weight;
                mFadeDelay += 1 - ratio;
                if (--mFadeLeft == 0) {
                    mDelay = mFadeDelay;
                } else {
                    mDelay += 1 - ratio;
                }
            } else {
                mDelay += 1 - ratio;
                splice();
            }

            out[i] = value;
        }
    }

    /**
     * Keeps the history going without producing output, e.g. while the effect is off: turning it on then
     * continues from current audio. The delay stays the same.
     */
    public void write(float[] in, int count) {
        for (int i = 0; i < count; i++) {
            mBuffer[mWritten++ & mMask] = in[i];
        }
    }

    /**
     * Current delay of the output in samples, 0 would be the newest sample.
     */
    public double getDelay() {
        return mDelay;
    }

    public void reset() {
        Arrays.fill(mBuffer, 0);
        mWritten = 0;
        mDelay = MIN_DELAY + DEFAULT_PERIOD / 2;
        mFadeLeft = 0;
        mPeriod = DEFAULT_PERIOD;
    }

    /**
     * Keeps the delay between {@link #MIN_DELAY} and one period more.
     */
    private void splice() {
        if (mDelay < MIN_DELAY) {
            startFade(mDelay + mPeriod); // reading faster caught up with the writing: one period back
        } else if (mDelay > MIN_DELAY + mPeriod + MARGIN) {
            startFade(mDelay - mPeriod); // reading slower fell behind: one period forward
        }
    }

    private void startFade(double delay) {
        mFadeDelay = delay;
        mFadeLeft = FADE;
    }

    /**
     * Catmull-Rom interpolation at a fractional delay behind the newest sample.
     */
    private float read(double delay) {
        int whole = (int) delay;
        float t = 1 - (float) (delay - whole);
        int index = mWritten - 1 - whole; // the position lies between index - 1 (t = 0) and index (t = 1)

        float to = mBuffer[index & mMask];

        if (t == 1) {
            return to; // on a sample: exact, no rounding
        }

        float before = mBuffer[(index - 2) & mMask];
        float from = mBuffer[(index - 1) & mMask];
        float after = mBuffer[(index + 1) & mMask];

        return from + 0.5f * t * (to - before + t * (2 * before - 5 * from + 4 * to - after + t * (3 * (from - to) + after - before)));
    }

    private static float clamp(float ratio) {
        return Math.max(MIN_RATIO, Math.min(MAX_RATIO, ratio));
    }
}
