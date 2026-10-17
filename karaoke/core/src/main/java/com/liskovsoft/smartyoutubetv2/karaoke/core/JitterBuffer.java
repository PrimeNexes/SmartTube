package com.liskovsoft.smartyoutubetv2.karaoke.core;

import java.nio.ByteBuffer;

/**
 * Audio jitter buffer for one network mic. One producer (network thread), one consumer (mixer thread).<br/>
 * - Holds playback until {@link #setTargetSamples target} samples are queued, again after every underrun.<br/>
 * - Fills lost packets with silence so timing stays aligned, drops late/duplicate packets.<br/>
 * - Compensates clock drift between the phone ADC and the TV DAC by consuming one sample more or less per frame
 *   (inaudible ~0.4% rate change) whenever the queue drifts away from the target.
 */
public final class JitterBuffer {
    /** Bigger sequence gap means the sender restarted or the network was gone for a while. */
    private static final int MAX_GAP_PACKETS = 20;
    private final short[] mRing;
    private long mWritePos;
    private long mReadPos;
    private int mTargetSamples;
    private int mDriftWindow;
    private int mMaxSamples;
    private boolean mBuffering = true;
    private boolean mHasSeq;
    private int mExpectedSeq;
    private long mPacketCount;
    private long mLostCount;
    private long mLateCount;
    private long mUnderrunCount;

    public JitterBuffer(int capacitySamples, int targetSamples) {
        mRing = new short[capacitySamples];
        setTargetSamples(targetSamples);
    }

    public synchronized void setTargetSamples(int targetSamples) {
        mTargetSamples = Math.max(1, Math.min(targetSamples, mRing.length / 4));
        mDriftWindow = Math.max(mTargetSamples / 4, 96);
        mMaxSamples = Math.min(mRing.length, mTargetSamples * 3 + 4_800);
    }

    public synchronized int getTargetSamples() {
        return mTargetSamples;
    }

    /**
     * @param pcm buffer positioned at the samples, little-endian
     */
    public synchronized void writePacket(int seq, ByteBuffer pcm, int sampleCount) {
        if (mHasSeq) {
            int gap = seq - mExpectedSeq; // overflow safe

            if (gap > MAX_GAP_PACKETS || gap < -MAX_GAP_PACKETS) {
                resetInt();
            } else if (gap < 0) {
                mLateCount++; // its time slot is already filled with silence or played
                return;
            } else if (gap > 0) {
                mLostCount += gap;
                for (int i = 0; i < gap * sampleCount; i++) {
                    put((short) 0);
                }
            }
        }

        mHasSeq = true;
        mExpectedSeq = seq + 1;
        mPacketCount++;

        for (int i = 0; i < sampleCount; i++) {
            put(pcm.getShort());
        }
    }

    /**
     * For local sources with no sequence numbers.
     */
    public synchronized void writeSamples(short[] samples, int offset, int count) {
        mPacketCount++;

        for (int i = 0; i < count; i++) {
            put(samples[offset + i]);
        }
    }

    /**
     * Fills the output with the next frame, -1 .. 1 range. Missing audio is replaced with silence.
     * @return number of real (not padded) samples
     */
    public synchronized int read(float[] out, int count) {
        int level = (int) (mWritePos - mReadPos);

        if (mBuffering) {
            if (level < mTargetSamples) {
                fillSilence(out, 0, count);
                return 0;
            }
            mBuffering = false;
        }

        if (level < count) { // underrun
            copy(out, level);
            fadeOut(out, level);
            fillSilence(out, level, count);
            mReadPos += level;
            mBuffering = true;
            mUnderrunCount++;
            return level;
        }

        if (level > mMaxSamples) { // way behind (e.g. a burst after a Wi-Fi stall), jump close to live
            mReadPos = mWritePos - mTargetSamples;
            level = mTargetSamples;
        }

        int consume = count;

        if (level > mTargetSamples + mDriftWindow) {
            consume = count + 1;
        } else if (level < mTargetSamples - mDriftWindow && level > count + 1) {
            consume = count - 1;
        }

        if (consume == count) {
            copy(out, count);
        } else {
            resample(out, count, consume);
        }

        mReadPos += consume;

        return count;
    }

    public synchronized int getLevel() {
        return (int) (mWritePos - mReadPos);
    }

    public synchronized boolean isBuffering() {
        return mBuffering;
    }

    public synchronized void reset() {
        resetInt();
    }

    public synchronized long getPacketCount() {
        return mPacketCount;
    }

    public synchronized long getLostCount() {
        return mLostCount;
    }

    public synchronized long getLateCount() {
        return mLateCount;
    }

    public synchronized long getUnderrunCount() {
        return mUnderrunCount;
    }

    private void resetInt() {
        mReadPos = mWritePos;
        mBuffering = true;
        mHasSeq = false;
    }

    private void put(short sample) {
        mRing[(int) (mWritePos % mRing.length)] = sample;
        mWritePos++;

        if (mWritePos - mReadPos > mRing.length) { // overflow, forget the oldest
            mReadPos = mWritePos - mRing.length;
        }
    }

    private float sampleAt(long pos) {
        return mRing[(int) (pos % mRing.length)] / 32768f;
    }

    private void copy(float[] out, int count) {
        for (int i = 0; i < count; i++) {
            out[i] = sampleAt(mReadPos + i);
        }
    }

    /**
     * Linear interpolation of {@code consume} input samples onto {@code count} output samples.
     */
    private void resample(float[] out, int count, int consume) {
        float step = (float) (consume - 1) / (count - 1);

        for (int i = 0; i < count; i++) {
            float pos = i * step;
            int index = (int) pos;
            float frac = pos - index;
            float s0 = sampleAt(mReadPos + index);
            float s1 = sampleAt(mReadPos + Math.min(index + 1, consume - 1));
            out[i] = s0 + (s1 - s0) * frac;
        }
    }

    /**
     * Avoids a click when audio stops abruptly.
     */
    private static void fadeOut(float[] out, int count) {
        int fade = Math.min(count, 48);
        for (int i = 0; i < fade; i++) {
            out[count - fade + i] *= 1f - (float) (i + 1) / fade;
        }
    }

    private static void fillSilence(float[] out, int from, int to) {
        for (int i = from; i < to; i++) {
            out[i] = 0;
        }
    }
}
