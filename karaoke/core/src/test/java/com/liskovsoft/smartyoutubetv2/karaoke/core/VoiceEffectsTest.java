package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VoiceEffectsTest {
    private static final int RATE = 48_000;

    @Test
    public void reverbTailDecaysForEveryPreset() {
        int[] presets = {VoiceReverb.PRESET_ROOM, VoiceReverb.PRESET_HALL, VoiceReverb.PRESET_ECHO};

        for (int preset : presets) {
            VoiceReverb reverb = new VoiceReverb(RATE);
            reverb.setPreset(preset);

            float[] buffer = new float[RATE * 6];
            buffer[0] = 1;
            reverb.process(buffer, buffer.length);

            float early = peak(buffer, RATE / 50, RATE / 2);
            float late = peak(buffer, RATE * 5, RATE * 6);
            assertTrue("preset " + preset + " no tail", early > 0.001f);
            assertTrue("preset " + preset + " doesn't decay", late < early / 20);
            for (float value : buffer) {
                assertFalse(Float.isNaN(value));
            }
        }
    }

    @Test
    public void reverbOffIsTransparent() {
        VoiceReverb reverb = new VoiceReverb(RATE);
        float[] buffer = {0.1f, -0.2f, 0.3f};
        reverb.process(buffer, buffer.length);
        assertEquals(-0.2f, buffer[1], 0);
    }

    @Test
    public void limiterKeepsPeaksUnderCeiling() {
        Limiter limiter = new Limiter(RATE);
        float[] buffer = new float[RATE];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = (float) Math.sin(2 * Math.PI * 440 * i / RATE) * 3f;
        }

        limiter.process(buffer, buffer.length);

        assertTrue(peak(buffer, 0, buffer.length) <= Limiter.DEFAULT_CEILING + 1e-4f);
    }

    @Test
    public void gateClosesOnRoomNoiseAndOpensOnSinging() {
        NoiseGate gate = new NoiseGate(RATE);

        float[] noise = new float[RATE / 2];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (float) Math.sin(i * 0.3) * 0.003f;
        }
        gate.process(noise, noise.length);
        assertTrue(peak(noise, noise.length / 2, noise.length) < 0.0005f);

        float[] voice = new float[RATE / 2];
        for (int i = 0; i < voice.length; i++) {
            voice[i] = (float) Math.sin(2 * Math.PI * 300 * i / RATE) * 0.3f;
        }
        gate.process(voice, voice.length);
        assertTrue(gate.isOpen());
        assertTrue(peak(voice, voice.length / 2, voice.length) > 0.29f);
    }

    private static float peak(float[] buffer, int from, int to) {
        float peak = 0;
        for (int i = from; i < to; i++) {
            peak = Math.max(peak, Math.abs(buffer[i]));
        }
        return peak;
    }
}
