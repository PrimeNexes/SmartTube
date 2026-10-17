package com.liskovsoft.smartyoutubetv2.karaoke.core;

/**
 * Pitch correction for one singer: finds the sung pitch ({@link PitchDetector}), picks the nearest note of the
 * chosen key, and shifts the voice toward it ({@link PitchShifter}).<br/>
 * The retune speed sets the character: a slow correction fixes the average pitch of a note and keeps vibrato and
 * slides (natural), an instant one flattens every note and jumps between them (the robotic sound).<br/>
 * Consonants, breaths and silence pass through unchanged. Not thread safe: one audio thread calls
 * {@link #process}, settings are applied between calls.
 */
public final class AutoTune {
    public static final int MODE_OFF = 0;
    public static final int MODE_NATURAL = 1;
    public static final int MODE_STRONG = 2;
    public static final int MODE_ROBOT = 3;
    /** Any note: the nearest semitone. */
    public static final int KEY_CHROMATIC = -1;
    /** Keys 0..11 are C..B major, 12..23 are C..B minor. */
    public static final int KEY_COUNT = 24;
    private static final int MAJOR = 0b101010110101; // C D E F G A B, bit 0 is C
    private static final int MINOR = 0b010110101101; // C D Eb F G Ab Bb
    private static final int CHROMATIC = 0xFFF;
    /** How fast the correction reaches the target note per mode, ms. */
    private static final float[] RETUNE_MS = {0, 80, 25, 0};
    /** How fast the correction goes away when the voice stops, ms. */
    private static final float RELEASE_MS = 40;
    /** A new target note must be this much closer than the current one, semitones. Stops flapping. */
    private static final float HYSTERESIS = 0.2f;
    /** Dry/wet crossfade when turning on or off, samples. */
    private static final int MIX_FADE = 240;
    private final int mSampleRate;
    private final PitchDetector mDetector;
    private final PitchShifter mShifter;
    private final float[] mWet;
    private int mMode = MODE_OFF;
    private int mKey = KEY_CHROMATIC;
    private int mScale = CHROMATIC;
    private int mTarget = -1;
    private float mNote = -1;
    private float mCorrection;
    private float mRatio = 1;
    private float mMix;

    /**
     * @param maxBlock most samples one {@link #process} call gets
     */
    public AutoTune(int sampleRate, int maxBlock) {
        mSampleRate = sampleRate;
        mDetector = new PitchDetector(sampleRate);
        mShifter = new PitchShifter((int) Math.ceil(sampleRate / PitchDetector.MIN_HZ));
        mWet = new float[maxBlock];
    }

    public void setMode(int mode) {
        if (mode < MODE_OFF || mode > MODE_ROBOT) {
            mode = MODE_OFF;
        }

        mMode = mode;
    }

    public int getMode() {
        return mMode;
    }

    /**
     * @param key {@link #KEY_CHROMATIC}, or 0..11 for C..B major, 12..23 for C..B minor
     */
    public void setKey(int key) {
        if (key == mKey) {
            return;
        }

        if (key < 0 || key >= KEY_COUNT) {
            key = KEY_CHROMATIC;
        }

        mKey = key;
        mScale = key == KEY_CHROMATIC ? CHROMATIC : rotate(key < 12 ? MAJOR : MINOR, key % 12);
        mTarget = -1;
    }

    public int getKey() {
        return mKey;
    }

    /**
     * False when off and done fading out: {@link #process} leaves the audio alone.
     */
    public boolean isActive() {
        return mMode != MODE_OFF || mMix > 0;
    }

    /**
     * The sung pitch as a MIDI note (69 is A4, fractional), -1 when there's no voiced sound.
     */
    public float getNote() {
        return mNote;
    }

    /**
     * The note the voice is pulled to (MIDI), -1 when none.
     */
    public int getTargetNote() {
        return mTarget;
    }

    /**
     * In place. When not {@link #isActive()}, the audio is untouched (bit exact), only remembered, so turning the
     * effect on later starts from current audio.
     */
    public void process(float[] samples, int count) {
        count = Math.min(count, mWet.length);

        if (count <= 0) {
            return;
        }

        if (!isActive()) {
            mDetector.write(samples, 0, count);
            mShifter.write(samples, count);
            return;
        }

        mDetector.write(samples, 0, count);
        float period = mDetector.detect();
        float blockMs = 1000f * count / mSampleRate;
        float wanted;
        float speedMs;

        if (period > 0) {
            mNote = toNote(mSampleRate / period);
            mTarget = pickTarget(mNote);
            wanted = mTarget - mNote;
            speedMs = RETUNE_MS[mMode];
        } else {
            mNote = -1;
            mTarget = -1;
            wanted = 0;
            speedMs = RELEASE_MS;
        }

        if (mMode == MODE_OFF) {
            wanted = 0; // fading out
        }

        mCorrection += (wanted - mCorrection) * (speedMs > 0 ? (float) (1 - Math.exp(-blockMs / speedMs)) : 1);
        float ratio = (float) Math.pow(2, mCorrection / 12);

        mShifter.process(samples, mWet, count, mRatio, ratio, period);
        mRatio = ratio;

        float mixTarget = mMode != MODE_OFF ? 1 : 0;
        float mixStep = 1f / MIX_FADE;
        for (int i = 0; i < count; i++) {
            if (mMix != mixTarget) {
                mMix = mixTarget > mMix ? Math.min(mixTarget, mMix + mixStep) : Math.max(mixTarget, mMix - mixStep);
            }
            samples[i] += (mWet[i] - samples[i]) * mMix;
        }

        if (!isActive()) {
            mNote = -1;
            mTarget = -1;
        }
    }

    /**
     * MIDI note number of a frequency: 69 is A4 (440 Hz), one per semitone.
     */
    public static float toNote(float hz) {
        return (float) (69 + 12 * Math.log(hz / 440.0) / Math.log(2));
    }

    /**
     * Nearest note of the key, keeping the current one unless another is clearly closer.
     */
    private int pickTarget(float note) {
        int nearest = -1;
        float nearestDistance = Float.MAX_VALUE;
        int base = Math.round(note);

        // Scales here have no gaps wider than 2 semitones
        for (int candidate = base - 2; candidate <= base + 2; candidate++) {
            float distance = Math.abs(note - candidate);
            if (isInKey(candidate) && distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }

        if (mTarget >= 0 && isInKey(mTarget) && Math.abs(note - mTarget) <= nearestDistance + HYSTERESIS) {
            return mTarget;
        }

        return nearest;
    }

    private boolean isInKey(int note) {
        return note >= 0 && ((mScale >> (note % 12)) & 1) != 0;
    }

    private static int rotate(int scale, int root) {
        return ((scale << root) | (scale >>> (12 - root))) & CHROMATIC;
    }
}
