package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AutoLevelTest {
    private static final int RATE = 48_000;
    private static final int FRAME = KaraokeProtocol.FRAME_SAMPLES;

    @Test
    public void quietVoiceIsBroughtUp() {
        AutoLevel level = new AutoLevel(RATE);
        float rms = run(level, 0.02f, 8);
        assertTrue("rms " + rms, rms > 0.1f); // was 0.014
    }

    @Test
    public void loudVoiceIsBroughtDown() {
        AutoLevel level = new AutoLevel(RATE);
        float rms = run(level, 0.8f, 2);
        assertTrue("rms " + rms, rms < 0.2f); // was 0.57
        assertTrue(level.getGain() >= AutoLevel.MIN_GAIN);
    }

    @Test
    public void silenceDoesNotRaiseGain() {
        AutoLevel level = new AutoLevel(RATE);
        run(level, 0.0005f, 5); // room noise, nobody sings
        assertEquals(1f, level.getGain(), 1e-6);
    }

    @Test
    public void gainStaysInRange() {
        AutoLevel level = new AutoLevel(RATE);
        run(level, 0.009f, 20); // just above the activity threshold, wants more than MAX_GAIN
        assertTrue(level.getGain() <= AutoLevel.MAX_GAIN + 1e-4);
    }

    /**
     * Feeds a 300 Hz sine for the given seconds.
     * @return output RMS over the last second
     */
    private static float run(AutoLevel level, float amplitude, int seconds) {
        float[] frame = new float[FRAME];
        int frames = seconds * RATE / FRAME;
        double sum = 0;
        int count = 0;
        long n = 0;

        for (int f = 0; f < frames; f++) {
            for (int i = 0; i < FRAME; i++, n++) {
                frame[i] = (float) Math.sin(2 * Math.PI * 300 * n / RATE) * amplitude;
            }

            level.process(frame, FRAME);

            if (f >= frames - RATE / FRAME) {
                for (float sample : frame) {
                    sum += sample * sample;
                }
                count += FRAME;
            }
        }

        return (float) Math.sqrt(sum / count);
    }
}
