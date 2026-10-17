package com.liskovsoft.smartyoutubetv2.karaoke.core;

import java.nio.ByteBuffer;

/**
 * Karaoke vocal remover for already mixed music.<br/>
 * Lead vocals are usually panned to the centre, i.e. they're identical in the left and right channels.
 * The centre (mid) signal (L + R) / 2 is split by Linkwitz-Riley crossovers into low (&lt; 150 Hz: bass, kick),
 * vocal band and high (&gt; 7 kHz: cymbals, air). The bands add back up to an allpass copy of the mid, so turning
 * the vocal band down removes the voice without boosting or notching anything else, at any strength.
 * Stereo-wide instruments (the side signal) are untouched.<br/>
 * Multichannel streams (5.1 etc.) get the same treatment for front L/R plus the vocal band of the centre channel.<br/>
 * Works with interleaved 16 bit PCM. Turning it on or off crossfades over ~20 ms, no clicks.
 */
public final class VocalRemover {
    public static final float LOW_CROSSOVER_HZ = 150;
    public static final float HIGH_CROSSOVER_HZ = 7_000;
    /** Ramp per frame: ~20 ms from off to full at 48 kHz. */
    private static final float RAMP_STEP = 1f / 1_000;
    private final int mChannelCount;
    private final int mCenterIndex;
    private final Splitter mMid;
    private final Splitter mCenter;
    /** How much of the vocal band is removed, smoothed. */
    private float mStrength;
    /** Crossfade between the untouched input (0) and the processed signal (1). */
    private float mWet;

    public VocalRemover(int sampleRate, int channelCount) {
        mChannelCount = channelCount;
        // Android channel order: FL, FR, FC, LFE, BL, BR... Quad (4 channels) has no centre.
        mCenterIndex = channelCount == 3 || channelCount >= 5 ? 2 : -1;
        mMid = new Splitter(sampleRate);
        mCenter = mCenterIndex != -1 ? new Splitter(sampleRate) : null;
    }

    public static boolean isSupported(int channelCount) {
        return channelCount >= 2;
    }

    /**
     * Consumes all whole frames of the input and writes the same amount of frames to the output.
     * @param targetStrength 0 (off) .. 1 (full removal)
     */
    public void process(ByteBuffer input, ByteBuffer output, float targetStrength) {
        int frameBytes = 2 * mChannelCount;
        int frames = input.remaining() / frameBytes;
        float target = Math.max(0, Math.min(targetStrength, 1f));

        if (target == 0 && mWet == 0) {
            // Bypass: plain copy. Filters restart from silence next time (the crossfade hides it).
            resetFilters();
            mStrength = 0;
            int limit = input.limit();
            input.limit(input.position() + frames * frameBytes);
            output.put(input);
            input.limit(limit);
            input.position(limit);
            return;
        }

        float wetTarget = target > 0 ? 1 : 0;

        for (int i = 0; i < frames; i++) {
            mStrength = approach(mStrength, target);
            mWet = approach(mWet, wetTarget);

            int base = input.position();
            float left = input.getShort(base) / 32768f;
            float right = input.getShort(base + 2) / 32768f;
            float side = (left - right) * 0.5f;
            float mid = mMid.keep((left + right) * 0.5f, mStrength);

            output.putShort(toPcm(left + ((side + mid) - left) * mWet));
            output.putShort(toPcm(right + ((mid - side) - right) * mWet));

            for (int channel = 2; channel < mChannelCount; channel++) {
                short sample = input.getShort(base + channel * 2);

                if (channel == mCenterIndex) {
                    float center = sample / 32768f;
                    float processed = mCenter.keep(center, mStrength);
                    output.putShort(toPcm(center + (processed - center) * mWet));
                } else {
                    output.putShort(sample);
                }
            }

            input.position(base + frameBytes);
        }

        input.position(input.limit()); // drop a partial frame, if any
    }

    /**
     * After a seek: forget the old position, keep the current strength (no vocals flashing back).
     */
    public void resetFilters() {
        mMid.reset();
        if (mCenter != null) {
            mCenter.reset();
        }
    }

    /**
     * Back to bypass.
     */
    public void reset() {
        resetFilters();
        mStrength = 0;
        mWet = 0;
    }

    private static float approach(float value, float target) {
        if (value < target) {
            return Math.min(target, value + RAMP_STEP);
        } else if (value > target) {
            return Math.max(target, value - RAMP_STEP);
        }
        return value;
    }

    private static short toPcm(float value) {
        int sample = Math.round(value * 32768f);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
    }

    /**
     * 3-band Linkwitz-Riley split of one signal. low + vocal + high is an allpass copy of the input:
     * the low band runs through the same 7 kHz crossover pair the other bands see, so phases line up.
     */
    private static final class Splitter {
        private final LinkwitzRiley mLowLp;
        private final LinkwitzRiley mLowHp;
        private final LinkwitzRiley mHighLp;
        private final LinkwitzRiley mHighHp;
        private final LinkwitzRiley mAlignLp;
        private final LinkwitzRiley mAlignHp;

        Splitter(int sampleRate) {
            mLowLp = LinkwitzRiley.lowPass(sampleRate, LOW_CROSSOVER_HZ);
            mLowHp = LinkwitzRiley.highPass(sampleRate, LOW_CROSSOVER_HZ);
            mHighLp = LinkwitzRiley.lowPass(sampleRate, HIGH_CROSSOVER_HZ);
            mHighHp = LinkwitzRiley.highPass(sampleRate, HIGH_CROSSOVER_HZ);
            mAlignLp = LinkwitzRiley.lowPass(sampleRate, HIGH_CROSSOVER_HZ);
            mAlignHp = LinkwitzRiley.highPass(sampleRate, HIGH_CROSSOVER_HZ);
        }

        /**
         * @return the signal with its vocal band reduced by strength
         */
        float keep(float x, float strength) {
            float low = mLowLp.process(x);
            float rest = mLowHp.process(x);
            float vocal = mHighLp.process(rest);
            float high = mHighHp.process(rest);
            float alignedLow = mAlignLp.process(low) + mAlignHp.process(low);
            return alignedLow + high + vocal * (1 - strength);
        }

        void reset() {
            mLowLp.reset();
            mLowHp.reset();
            mHighLp.reset();
            mHighHp.reset();
            mAlignLp.reset();
            mAlignHp.reset();
        }
    }

    /**
     * 4th order Linkwitz-Riley filter: two cascaded Butterworth sections. LP + HP at the same frequency is flat.
     */
    private static final class LinkwitzRiley {
        private final Biquad mFirst;
        private final Biquad mSecond;

        private LinkwitzRiley(Biquad first, Biquad second) {
            mFirst = first;
            mSecond = second;
        }

        static LinkwitzRiley lowPass(int sampleRate, float frequencyHz) {
            return new LinkwitzRiley(Biquad.lowPass(sampleRate, frequencyHz, Biquad.Q_BUTTERWORTH),
                    Biquad.lowPass(sampleRate, frequencyHz, Biquad.Q_BUTTERWORTH));
        }

        static LinkwitzRiley highPass(int sampleRate, float frequencyHz) {
            return new LinkwitzRiley(Biquad.highPass(sampleRate, frequencyHz, Biquad.Q_BUTTERWORTH),
                    Biquad.highPass(sampleRate, frequencyHz, Biquad.Q_BUTTERWORTH));
        }

        float process(float x) {
            return mSecond.process(mFirst.process(x));
        }

        void reset() {
            mFirst.reset();
            mSecond.reset();
        }
    }
}
