package com.liskovsoft.smartyoutubetv2.karaoke.core;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Audio jitter buffer for one network mic. One producer (network thread), one consumer (mixer thread).<br/>
 * - Holds playback until {@link #setTargetSamples target} samples are queued, again after every underrun.<br/>
 * - Conceals lost packets and underruns: the voice goes on by repeating its last pitch period, fading out after
 *   10 ms, then blends back into the real audio. Silence instead would click on every lost 5 ms packet.<br/>
 * - Drops late/duplicate packets.<br/>
 * - Compensates clock drift between the phone ADC and the TV DAC by consuming one sample more or less per frame
 *   (inaudible ~0.4% rate change) whenever the queue drifts away from the target.<br/>
 * Only the two audio threads take the lock. Stats and target changes from other threads (UI) go through
 * volatile/atomic fields, so a slow UI thread can't stall the mixer.
 */
public final class JitterBuffer {
    /** Bigger sequence gap means the sender restarted or the network was gone for a while. */
    private static final int MAX_GAP_PACKETS = 20;
    // Concealment, in samples at 48 kHz
    private static final int MIN_PERIOD = 48; // 1 kHz
    private static final int MAX_PERIOD = 640; // 75 Hz
    private static final int MATCH = 160;
    private static final int CROSSFADE = 96; // 2 ms
    private static final int FULL_LEVEL = 480; // then fades out
    private static final int FADE = 2_400;
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
    /** Where audio of the current stream starts: concealment doesn't reach before it. */
    private long mHistoryStart;
    private final float[] mHistory = new float[MATCH + MAX_PERIOD];
    // Concealment of an underrun, continued while buffering
    private boolean mIsConcealing;
    private long mConcealEnd;
    private int mConcealPeriod;
    private int mConcealed;
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

        long concealEnd = -1;
        int period = 0;
        int concealed = 0;

        if (mHasSeq) {
            int gap = seq - mExpectedSeq; // overflow safe

            if (gap > MAX_GAP_PACKETS || gap < -MAX_GAP_PACKETS) {
                resetInt();
            } else if (gap < 0) {
                mLateCount++; // its time slot is already filled or played
                return;
            } else if (gap > 0 && mIsConcealing && mWritePos == mReadPos) {
                mLostCount += gap; // the reader ran dry and already made up audio for this gap: no second copy
            } else if (gap > 0) {
                mLostCount += gap;
                concealEnd = mWritePos;
                period = findPeriod(concealEnd);
                concealed = gap * sampleCount;
                for (int i = 0; i < concealed; i++) {
                    put(concealSample(concealEnd, period, i));
                }
            }
        }

        mHasSeq = true;
        mExpectedSeq = seq + 1;
        mPacketCount++;

        for (int i = 0; i < sampleCount; i++) {
            float value = pcm.getShort() / 32768f;
            if (concealEnd >= 0 && i < CROSSFADE) { // from the made up audio into the real one
                float weight = (i + 1f) / (CROSSFADE + 1);
                value = value * weight + concealSample(concealEnd, period, concealed + i) * (1 - weight);
            }
            put(value);
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
     * Fills the output with the next frame, -1 .. 1 range. Missing audio is concealed, then silent.
     * @return number of real (not made up) samples
     */
    public synchronized int read(float[] out, int count) {
        applyPendingTarget();

        int level = (int) (mWritePos - mReadPos);

        if (mBuffering) {
            if (level < mTargetSamples) {
                for (int i = 0; i < count; i++) {
                    out[i] = mIsConcealing ? nextConcealSample() : 0;
                }
                return 0;
            }
            mBuffering = false;
        }

        if (level < count) { // underrun: what's left, then the voice goes on while the buffer refills
            copy(out, level);
            startConcealing(mReadPos + level);
            for (int i = level; i < count; i++) {
                out[i] = nextConcealSample();
            }
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

        if (mIsConcealing) { // back from an underrun
            for (int i = 0; i < CROSSFADE && i < count; i++) {
                float weight = (i + 1f) / (CROSSFADE + 1);
                out[i] = out[i] * weight + nextConcealSample() * (1 - weight);
            }
            mIsConcealing = false;
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
        mHistoryStart = mWritePos;
        mBuffering = true;
        mHasSeq = false;
        mIsConcealing = false;
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

    private void put(float value) {
        put((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value * 32768))));
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

    private void startConcealing(long end) {
        mIsConcealing = true;
        mConcealEnd = end;
        mConcealPeriod = findPeriod(end);
        mConcealed = 0;
    }

    private float nextConcealSample() {
        float value = concealSample(mConcealEnd, mConcealPeriod, mConcealed);
        mConcealed = Math.min(mConcealed + 1, FULL_LEVEL + FADE);
        return value;
    }

    /**
     * Sample {@code index} after {@code end} of the made up continuation: the last period over and over, at full
     * level for 10 ms, then fading out over 50 ms (like G.711 appendix I).
     */
    private float concealSample(long end, int period, int index) {
        if (period <= 0 || index >= FULL_LEVEL + FADE) {
            return 0;
        }

        float gain = index < FULL_LEVEL ? 1 : 1 - (float) (index - FULL_LEVEL) / FADE;
        return sampleAt(end - period + index % period) * gain;
    }

    /**
     * Pitch period of the audio before {@code end}: where an earlier stretch best matches the last few ms.
     * Repeating from there continues the waveform without a jump. 0 if there's too little audio.
     */
    private int findPeriod(long end) {
        int maxPeriod = (int) Math.min(MAX_PERIOD, end - mHistoryStart - MATCH);

        if (maxPeriod < MIN_PERIOD) {
            return 0;
        }

        int length = MATCH + maxPeriod;
        float[] history = mHistory;
        for (int k = 0; k < length; k++) {
            history[k] = sampleAt(end - length + k);
        }

        int recent = length - MATCH;
        float recentEnergy = 0;
        for (int j = 0; j < MATCH; j++) {
            recentEnergy += history[recent + j] * history[recent + j];
        }

        int best = MIN_PERIOD;
        float bestScore = -Float.MAX_VALUE;

        for (int period = MIN_PERIOD; period <= maxPeriod; period++) {
            int earlier = recent - period;
            float dot = 0;
            float energy = 0;
            for (int j = 0; j < MATCH; j++) {
                float value = history[earlier + j];
                dot += history[recent + j] * value;
                energy += value * value;
            }
            float score = energy > 0 && recentEnergy > 0 ? dot / (float) Math.sqrt(recentEnergy * energy) : 0;
            if (score > bestScore) {
                bestScore = score;
                best = period;
            }
        }

        return best;
    }
}
