package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PitchShifterTest {
    private static final int RATE = 48_000;
    private static final int FRAME = 240;
    private static final float SEMITONE = (float) Math.pow(2, 1 / 12.0);

    @Test
    public void unityRatioIsAPureDelay() {
        Random random = new Random(3);
        float[] in = new float[RATE / 4];
        for (int i = 0; i < in.length; i++) {
            in[i] = random.nextFloat() - 0.5f;
        }

        PitchShifter shifter = new PitchShifter(640);
        int delay = (int) shifter.getDelay();
        float[] out = run(shifter, in, 1, 1, 200);

        for (int i = delay; i < in.length; i++) {
            assertEquals(in[i - delay], out[i], 0);
        }
    }

    @Test
    public void shiftsBySemitoneUpAndDown() {
        for (float ratio : new float[] {SEMITONE, 1 / SEMITONE, (float) Math.pow(2, 0.4 / 12)}) {
            float[] out = run(new PitchShifter(640), sine(220, RATE * 2), ratio, ratio, RATE / 220f);
            float measured = zeroCrossingHz(out, RATE / 2, out.length);
            assertEquals("ratio " + ratio, 0, PitchDetectorTest.cents(measured, 220 * ratio), 1);
        }
    }

    @Test
    public void delayStaysInRange() {
        float period = RATE / 110f; // low voice: the widest range
        for (float ratio : new float[] {1.06f, 0.94f, PitchShifter.MAX_RATIO, PitchShifter.MIN_RATIO}) {
            PitchShifter shifter = new PitchShifter(640);
            float[] in = sine(110, FRAME);
            float[] out = new float[FRAME];
            double sum = 0;
            int frames = 1000;
            for (int k = 0; k < frames; k++) {
                shifter.process(in, out, FRAME, ratio, ratio, period);
                double delay = shifter.getDelay();
                assertTrue("delay " + delay, delay > 3 && delay < PitchShifter.MIN_DELAY + period + 32 + 40);
                sum += delay;
            }
            // About half a period plus the minimum: ~5 ms for this 110 Hz voice
            assertTrue(sum / frames < (PitchShifter.MIN_DELAY + period) * 1.05);
        }
    }

    @Test
    public void splicesDoNotClick() {
        float[] voice = PitchDetectorTest.harmonics(150, new float[] {0.3f, 0.15f, 0.1f, 0.05f}, 3);
        float[] out = run(new PitchShifter(640), voice, 1.06f, 1.06f, RATE / 150f);

        // Reading 6% faster makes the waveform 6% steeper, a splice that clicked would be much steeper
        assertTrue(maxStep(out, RATE / 10) < maxStep(voice, 0) * 1.06f * 1.1f);
    }

    @Test
    public void wrongPeriodStillDoesNotClick() {
        float[] voice = PitchDetectorTest.harmonics(150, new float[] {0.3f, 0.15f, 0.1f, 0.05f}, 3);
        float[] out = run(new PitchShifter(640), voice, 0.95f, 0.95f, RATE / 150f + 7);

        assertTrue(maxStep(out, RATE / 10) < maxStep(voice, 0) * 1.5f);
    }

    private static float[] run(PitchShifter shifter, float[] in, float ratioStart, float ratioEnd, float period) {
        float[] out = new float[in.length];
        float[] block = new float[FRAME];
        float[] result = new float[FRAME];
        for (int start = 0; start + FRAME <= in.length; start += FRAME) {
            System.arraycopy(in, start, block, 0, FRAME);
            shifter.process(block, result, FRAME, ratioStart, ratioEnd, period);
            System.arraycopy(result, 0, out, start, FRAME);
        }
        return out;
    }

    static float[] sine(float hz, int length) {
        float[] signal = new float[length];
        for (int i = 0; i < length; i++) {
            signal[i] = (float) (0.3 * Math.sin(2 * Math.PI * hz * i / RATE));
        }
        return signal;
    }

    /**
     * Precise frequency of a clean tone: rising zero crossings, interpolated, over a long stretch.
     */
    static float zeroCrossingHz(float[] signal, int from, int to) {
        double first = -1;
        double last = -1;
        int crossings = 0;
        for (int i = from + 1; i < to; i++) {
            if (signal[i - 1] < 0 && signal[i] >= 0) {
                double position = i - 1 + signal[i - 1] / (signal[i - 1] - signal[i]);
                if (first < 0) {
                    first = position;
                } else {
                    crossings++;
                }
                last = position;
            }
        }
        return (float) (crossings * RATE / (last - first));
    }

    static float maxStep(float[] signal, int from) {
        float max = 0;
        for (int i = Math.max(from, 1); i < signal.length; i++) {
            max = Math.max(max, Math.abs(signal[i] - signal[i - 1]));
        }
        return max;
    }
}
