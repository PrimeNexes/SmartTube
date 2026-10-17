package com.liskovsoft.smartyoutubetv2.karaoke.core;

import java.nio.ByteBuffer;

/**
 * Karaoke vocal remover for already mixed music.<br/>
 * Lead vocals are usually panned to the centre, i.e. they're identical in the left and right channels.
 * Subtracting the vocal band (~150 Hz - 7 kHz) of the mid signal (L + R) / 2 from both channels removes them,
 * while stereo-wide instruments (the side signal) and the low/high ends of the mid (bass, kick, cymbals) survive.<br/>
 * Multichannel streams (5.1 etc.) get the same treatment for front L/R plus the vocal band removed from the centre.<br/>
 * Works with interleaved 16 bit PCM.
 */
public final class VocalRemover {
    public static final float LOW_CUT_HZ = 150;
    public static final float HIGH_CUT_HZ = 7_000;
    /** Strength change per frame: ~20 ms fade between off and full at 48 kHz, so toggling doesn't click. */
    private static final float STRENGTH_STEP = 1f / 1_000;
    private final int mChannelCount;
    private final int mCenterIndex;
    private final Biquad mMidHighPass;
    private final Biquad mMidLowPass;
    private final Biquad mCenterHighPass;
    private final Biquad mCenterLowPass;
    private float mStrength;

    public VocalRemover(int sampleRate, int channelCount) {
        mChannelCount = channelCount;
        // Android channel order: FL, FR, FC, LFE, BL, BR... Quad (4 channels) has no centre.
        mCenterIndex = channelCount == 3 || channelCount >= 5 ? 2 : -1;
        mMidHighPass = Biquad.highPass(sampleRate, LOW_CUT_HZ, Biquad.Q_BUTTERWORTH);
        mMidLowPass = Biquad.lowPass(sampleRate, HIGH_CUT_HZ, Biquad.Q_BUTTERWORTH);
        mCenterHighPass = Biquad.highPass(sampleRate, LOW_CUT_HZ, Biquad.Q_BUTTERWORTH);
        mCenterLowPass = Biquad.lowPass(sampleRate, HIGH_CUT_HZ, Biquad.Q_BUTTERWORTH);
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

        if (targetStrength <= 0 && mStrength <= 0) {
            // Bypass: plain copy. Filters restart from silence once enabled again (strength fades in anyway).
            reset();
            int limit = input.limit();
            input.limit(input.position() + frames * frameBytes);
            output.put(input);
            input.limit(limit);
            input.position(limit);
            return;
        }

        float target = Math.min(targetStrength, 1f);

        for (int i = 0; i < frames; i++) {
            if (mStrength < target) {
                mStrength = Math.min(target, mStrength + STRENGTH_STEP);
            } else if (mStrength > target) {
                mStrength = Math.max(target, mStrength - STRENGTH_STEP);
            }

            int base = input.position();
            float left = input.getShort(base) / 32768f;
            float right = input.getShort(base + 2) / 32768f;
            float vocal = mMidLowPass.process(mMidHighPass.process((left + right) * 0.5f)) * mStrength;

            output.putShort(toPcm(left - vocal));
            output.putShort(toPcm(right - vocal));

            for (int channel = 2; channel < mChannelCount; channel++) {
                short sample = input.getShort(base + channel * 2);

                if (channel == mCenterIndex) {
                    float center = sample / 32768f;
                    float centerVocal = mCenterLowPass.process(mCenterHighPass.process(center)) * mStrength;
                    output.putShort(toPcm(center - centerVocal));
                } else {
                    output.putShort(sample);
                }
            }

            input.position(base + frameBytes);
        }

        input.position(input.limit()); // drop a partial frame, if any
    }

    public void reset() {
        mMidHighPass.reset();
        mMidLowPass.reset();
        mCenterHighPass.reset();
        mCenterLowPass.reset();
        mStrength = 0;
    }

    private static short toPcm(float value) {
        int sample = Math.round(value * 32768f);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
    }
}
