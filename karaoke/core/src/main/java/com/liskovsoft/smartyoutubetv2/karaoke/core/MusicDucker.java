package com.liskovsoft.smartyoutubetv2.karaoke.core;

/**
 * Lowers the music while someone sings, like a radio DJ talking over a song: the voice comes first.<br/>
 * Goes down quickly when singing starts, stays down through breaths and short gaps between lines, and comes back
 * slowly after the singing ends, so the music doesn't pump with every word.
 */
public final class MusicDucker {
    /** Music level while singing: -6 dB. */
    public static final float DEPTH = 0.5f;
    private static final float ATTACK_MS = 120;
    private static final float HOLD_MS = 1_500;
    private static final float RELEASE_MS = 900;
    private float mGain = 1;
    private float mHoldLeftMs;

    /**
     * @param isSinging someone sang since the last call
     * @param elapsedMs time since the last call
     * @return music volume multiplier, {@link #DEPTH} to 1
     */
    public float update(boolean isSinging, float elapsedMs) {
        if (isSinging) {
            mHoldLeftMs = HOLD_MS;
        } else {
            mHoldLeftMs = Math.max(0, mHoldLeftMs - elapsedMs);
        }

        float target = mHoldLeftMs > 0 ? DEPTH : 1;
        float timeConstantMs = target < mGain ? ATTACK_MS : RELEASE_MS;
        mGain += (target - mGain) * (float) (1 - Math.exp(-elapsedMs / timeConstantMs));

        return mGain;
    }

    public float getGain() {
        return mGain;
    }

    public void reset() {
        mGain = 1;
        mHoldLeftMs = 0;
    }
}
