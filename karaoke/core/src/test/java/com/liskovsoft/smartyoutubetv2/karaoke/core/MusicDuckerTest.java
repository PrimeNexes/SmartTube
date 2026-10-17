package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MusicDuckerTest {
    private static final float TICK_MS = 50;

    @Test
    public void musicGoesDownWhenSingingStarts() {
        MusicDucker ducker = new MusicDucker();
        float gain = run(ducker, true, 300);

        assertTrue("gain " + gain, gain < MusicDucker.DEPTH + 0.1f);
    }

    @Test
    public void staysDownThroughBreaths() {
        MusicDucker ducker = new MusicDucker();
        run(ducker, true, 1_000);
        float gain = run(ducker, false, 1_000); // a breath between lines

        assertTrue("gain " + gain, gain < MusicDucker.DEPTH + 0.05f);
    }

    @Test
    public void comesBackAfterTheSinging() {
        MusicDucker ducker = new MusicDucker();
        run(ducker, true, 1_000);
        float gain = run(ducker, false, 5_000);

        assertEquals(1, gain, 0.03);
    }

    @Test
    public void neverGoesBelowTheDepth() {
        MusicDucker ducker = new MusicDucker();
        for (int i = 0; i < 1_000; i++) {
            assertTrue(ducker.update(true, TICK_MS) >= MusicDucker.DEPTH - 1e-6f);
        }
    }

    private static float run(MusicDucker ducker, boolean isSinging, float ms) {
        float gain = ducker.getGain();
        for (float elapsed = 0; elapsed < ms; elapsed += TICK_MS) {
            gain = ducker.update(isSinging, TICK_MS);
        }
        return gain;
    }
}
