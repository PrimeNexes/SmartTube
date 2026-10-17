package com.liskovsoft.smartyoutubetv2.karaoke.core;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Audio jitter buffer for one network mic. One producer (network thread), one consumer (mixer thread).<br/>
 * - Holds playback until {@link #setTargetSamples target} samples are queued, again after every underrun.<br/>
 * - Fills lost packets with silence so timing stays aligned, drops late/duplicate packets.<br/>
 * - Compensates clock drift between the phone ADC and the TV DAC by consuming one sample more or less per frame
 *   (inaudible ~0.4% rate change) whenever the queue drifts away from the target.<br/>
 * Only the two audio threads take the lock. Stats and target changes from other threads (UI) go through
 * volatile/atomic fields, so a slow UI thread can't stall the mixer.
 */
public final class JitterBuffer {
    /** Bigger sequence gap means the sender restarted or the network was gone for a while. */
    private static final int MAX_GAP_PACKETS = 20;
    private final short[] mRing;
    private final int mMask;
    private final AtomicInteger mPendingTarget = new AtomicInteger(-1);
    private long mWritePos;
    private long mReadPos;
    private int mTargetSamples;
    private int mDriftWindow;
    private int mMaxSamples;
    private boolean mBuffering = true;
    private boolean mHasSeq;
    private int mExpectedSeq;
    // Written under the lock by one thread, read by anyone
    private volatile int mLevel;
    private volatile long mPacketCount;
    private volatile long mLostCount;
    private volatile long mLateCount;
    private volatile long mUnderrunCount;

    /**
     * @param capacitySamples rounded up to a power of two (cheap index math on 32 bit CPUs)
     */
    public JitterBuffer(int capacitySamples, int targetSamples) {
        int capacity = Integer.highestOneBit(Math.max(capacitySamples, 2) - 1) << 1;
        mRing = new short[capacity];
        mMask = capacity - 1;
        applyTarget(targetSamples);
    }

    /**
     * Any thread. Takes effect with the next packet or frame.
     */
    public void setTargetSamples(int targetSamples) {
        mPendingTarget.set(targetSamples);
    }

    public synchronized int getTargetSamples() {
        applyPendingTarget();
        return mTargetSamples;
    }

    /**
     * @param pcm buffer positioned at the samples, little-endian
     */
    public synchronized void writePacket(int seq, ByteBuffer pcm, int sampleCount) {
        applyPendingTarget();

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

        updateLevel();
    }

    /**
     * For local sources with no sequence numbers.
     */
    public synchronized void writeSamples(short[] samples, int offset, int count) {
        applyPendingTarget();
        mPacketCount++;

        for (int i = 0; i < count; i++) {
            put(samples[offset + i]);
        }

        updateLevel();
    }

    /**
     * Fills the output with the next frame, -1 .. 1 range. Missing audio is replaced with silence.
     * @return number of real (not padded) samples
     */
    public synchronized int read(float[] out, int count) {
        applyPendingTarget();

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
            updateLevel();
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
        updateLevel();

        return count;
    }

    /**
     * Queued samples. Any thread.
     */
    public int getLevel() {
        return mLevel;
    }

    public synchronized boolean isBuffering() {
        return mBuffering;
    }

    public synchronized void reset() {
        resetInt();
        updateLevel();
    }

    public long getPacketCount() {
        return mPacketCount;
    }

    public long getLostCount() {
        return mLostCount;
    }

    public long getLateCount() {
        return mLateCount;
    }

    public long getUnderrunCount() {
        return mUnderrunCount;
    }

    private void applyPendingTarget() {
        int target = mPendingTarget.getAndSet(-1);

        if (target >= 0) {
            applyTarget(target);
        }
    }

    private void applyTarget(int targetSamples) {
        mTargetSamples = Math.max(1, Math.min(targetSamples, mRing.length / 4));
        mDriftWindow = Math.max(mTargetSamples / 4, 96);
        mMaxSamples = Math.min(mRing.length, mTargetSamples * 3 + 4_800);
    }

    private void resetInt() {
        mReadPos = mWritePos;
        mBuffering = true;
        mHasSeq = false;
    }

    private void updateLevel() {
        mLevel = (int) (mWritePos - mReadPos);
    }

    private void put(short sample) {
        mRing[(int) (mWritePos & mMask)] = sample;
        mWritePos++;

        if (mWritePos - mReadPos > mRing.length) { // overflow, forget the oldest
            mReadPos = mWritePos - mRing.length;
        }
    }

    private float sampleAt(long pos) {
        return mRing[(int) (pos & mMask)] / 32768f;
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
