package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PitchDetectorTest {
    private static final int RATE = 48_000;
    private static final int FRAME = 240;

    @Test
    public void sinesFromBassToSoprano() {
        for (float hz : new float[] {82.41f, 110, 196, 261.63f, 440, 659.25f, 987.77f}) {
            float detected = detectLast(harmonics(hz, new float[] {0.3f}, 0.5f));
            assertEquals("detected " + detected + " for " + hz, 0, cents(detected, hz), 3);
        }
    }

    @Test
    public void voiceLikeToneHasNoOctaveError() {
        float[] sawtooth = new float[12];
        for (int k = 0; k < sawtooth.length; k++) {
            sawtooth[k] = 0.3f / (k + 1);
        }

        assertEquals(0, cents(detectLast(harmonics(130.81f, sawtooth, 0.5f)), 130.81f), 3);
    }

    @Test
    public void weakFundamentalStillCounts() {
        // Second harmonic twice as loud as the fundamental, like some sung vowels
        float detected = detectLast(harmonics(196, new float[] {0.15f, 0.3f, 0.18f, 0.09f}, 0.5f));
        assertEquals(0, cents(detected, 196), 3);
    }

    @Test
    public void noiseAndSilenceHaveNoPitch() {
        Random random = new Random(1);
        float[] noise = new float[RATE / 2];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (random.nextFloat() - 0.5f) * 0.2f;
        }

        assertEquals(0, detectLast(noise), 0);
        assertEquals(0, detectLast(new float[RATE / 2]), 0);
        assertEquals(0, detectLast(harmonics(220, new float[] {0.001f}, 0.5f)), 0); // -60 dBFS: too quiet
    }

    @Test
    public void followsAGlide() {
        // 200 Hz to 300 Hz in one second
        float[] glide = new float[RATE];
        double phase = 0;
        for (int i = 0; i < glide.length; i++) {
            double hz = 200 + 100.0 * i / glide.length;
            phase += 2 * Math.PI * hz / RATE;
            glide[i] = (float) (0.3 * Math.sin(phase) + 0.15 * Math.sin(2 * phase));
        }

        PitchDetector detector = new PitchDetector(RATE);
        int checked = 0;
        for (int start = 0; start + FRAME <= glide.length; start += FRAME) {
            detector.write(glide, start, FRAME);
            float period = detector.detect();
            if (start < RATE / 10) {
                continue; // warming up
            }
            // The estimate describes the last ~27 ms: compare with the pitch in its middle
            double expected = 200 + 100.0 * (start + FRAME - RATE * 0.014) / glide.length;
            assertTrue(period > 0);
            assertEquals(0, cents(RATE / period, (float) expected), 15);
            checked++;
        }
        assertTrue(checked > 150);
    }

    @Test
    public void periodIsGivenAtTheFullRate() {
        PitchDetector detector = new PitchDetector(RATE);
        float[] tone = harmonics(250, new float[] {0.3f}, 0.2f);
        detector.write(tone, 0, tone.length);

        assertEquals(RATE / 250f, detector.detect(), 0.05);
        assertTrue(detector.getConfidence() > 0.9f);
    }

    /**
     * Feeds the signal frame by frame like the phone does, returns the last pitch.
     */
    private static float detectLast(float[] signal) {
        PitchDetector detector = new PitchDetector(RATE);
        float period = 0;
        for (int start = 0; start + FRAME <= signal.length; start += FRAME) {
            detector.write(signal, start, FRAME);
            period = detector.detect();
        }
        return period > 0 ? RATE / period : 0;
    }

    static float[] harmonics(float hz, float[] amplitudes, float seconds) {
        float[] signal = new float[(int) (RATE * seconds)];
        for (int i = 0; i < signal.length; i++) {
            double value = 0;
            for (int k = 0; k < amplitudes.length; k++) {
                value += amplitudes[k] * Math.sin(2 * Math.PI * hz * (k + 1) * i / RATE + k * 0.7);
            }
            signal[i] = (float) value;
        }
        return signal;
    }

    static float cents(float hz, float reference) {
        if (hz <= 0) {
            return Float.MAX_VALUE;
        }
        return (float) (1200 * Math.log(hz / reference) / Math.log(2));
    }
}
