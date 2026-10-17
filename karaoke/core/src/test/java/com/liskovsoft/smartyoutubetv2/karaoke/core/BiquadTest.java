package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BiquadTest {
    private static final int RATE = 48_000;

    @Test
    public void peakingBoostsAndCutsAroundItsFrequency() {
        assertEquals(6, gainDb(Biquad.peaking(RATE, 3_000, 0.9f, 6), 3_000), 0.2);
        assertEquals(0, gainDb(Biquad.peaking(RATE, 3_000, 0.9f, 6), 100), 0.3);
        assertEquals(-4, gainDb(Biquad.peaking(RATE, 300, 1, -4), 300), 0.2);
        assertEquals(0, gainDb(Biquad.peaking(RATE, 300, 1, -4), 8_000), 0.3);
    }

    @Test
    public void highPassRemovesRumble() {
        assertEquals(0, gainDb(Biquad.highPass(RATE, 100, Biquad.Q_BUTTERWORTH), 2_000), 0.1);
        assertEquals(-3, gainDb(Biquad.highPass(RATE, 100, Biquad.Q_BUTTERWORTH), 100), 0.2);
        assertEquals(-24, gainDb(Biquad.highPass(RATE, 100, Biquad.Q_BUTTERWORTH), 25), 1);
    }

    /**
     * Steady state gain of a sine through the filter.
     */
    private static float gainDb(Biquad filter, float hz) {
        double inSum = 0;
        double outSum = 0;
        for (int i = 0; i < RATE * 2; i++) {
            float x = (float) Math.sin(2 * Math.PI * hz * i / RATE);
            float y = filter.process(x);
            if (i > RATE) {
                inSum += x * x;
                outSum += y * y;
            }
        }
        return (float) (10 * Math.log10(outSum / inSum));
    }
}
