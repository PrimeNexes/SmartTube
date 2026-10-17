package com.liskovsoft.smartyoutubetv2.common.karaoke;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.BaseAudioProcessor;
import com.liskovsoft.smartyoutubetv2.karaoke.core.VocalRemover;

import java.nio.ByteBuffer;

/**
 * ExoPlayer adapter for {@link VocalRemover}. Lives in every player's audio chain (before speed/pitch processing),
 * but stays a plain copy until karaoke mode turns the strength up. Strength changes apply on the fly, no reconfigure.
 */
public final class VocalRemoverAudioProcessor extends BaseAudioProcessor {
    private static volatile float sStrength;
    private VocalRemover mRemover;
    private boolean mIsActive;

    /**
     * @param strength 0 (off) .. 1 (full removal)
     */
    public static void setStrength(float strength) {
        sStrength = Math.max(0, Math.min(1, strength));
    }

    public static float getStrength() {
        return sStrength;
    }

    @Override
    public boolean configure(int sampleRateHz, int channelCount, @C.PcmEncoding int encoding) {
        boolean changed = setInputFormat(sampleRateHz, channelCount, encoding);

        // Mono can't be split into voice/music, other encodings never reach int PCM processors
        mIsActive = encoding == C.ENCODING_PCM_16BIT && VocalRemover.isSupported(channelCount);

        if (mIsActive && (changed || mRemover == null)) {
            mRemover = new VocalRemover(sampleRateHz, channelCount);
        }

        return changed;
    }

    @Override
    public boolean isActive() {
        return mIsActive;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        ByteBuffer outputBuffer = replaceOutputBuffer(inputBuffer.remaining());
        mRemover.process(inputBuffer, outputBuffer, sStrength);
        outputBuffer.flip();
    }

    @Override
    protected void onFlush() {
        if (mRemover != null) {
            // After a seek or a speed/key change: don't filter the old position into the new one,
            // but keep the current strength, so the original singer doesn't flash back
            mRemover.resetFilters();
        }
    }

    @Override
    protected void onReset() {
        mRemover = null;
        mIsActive = false;
    }
}
