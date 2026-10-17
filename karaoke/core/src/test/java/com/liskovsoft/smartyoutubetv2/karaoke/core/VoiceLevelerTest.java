package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VoiceLevelerTest {
    private static final int RATE = 48_000;
    private static final int FRAME = 240;
    private static final float PHRASE_S = 3;
    private static final float GAP_S = 1.5f;
    /** TV sound only before the first line, like when the mic is turned on. */
    private static final float LEAD_IN_S = 1;

    @Test
    public void quietPhoneMicIsBroughtUp() {
        // Low latency capture: singing at -45 dBFS RMS, the TV heard 23 dB lower
        Song song = new Song(-45, -68, 4);
        float[] out = run(new VoiceLeveler(RATE, 0), song.signal);

        assertEquals(dbOf(VoiceLeveler.TARGET_RMS), song.lastPhraseDb(out), 2.5);
    }

    @Test
    public void loudSingingIsTurnedDown() {
        Song song = new Song(-6, -29, 4);
        float[] out = run(new VoiceLeveler(RATE, 0), song.signal);

        assertEquals(dbOf(VoiceLeveler.TARGET_RMS), song.lastPhraseDb(out), 2.5);
    }

    @Test
    public void tvSoundBetweenPhrasesIsTurnedDown() {
        Song song = new Song(-40, -63, 4);
        float[] out = run(new VoiceLeveler(RATE, 15), song.signal);

        // 23 dB apart at the mic, the expander adds its 15 dB
        assertTrue(song.lastPhraseDb(out) - song.lastGapDb(out) > 23 + VoiceLeveler.EXPANDER_DEPTH_DB - 3);
    }

    @Test
    public void tvSoundAloneDoesNotRaiseTheGain() {
        Song song = new Song(-40, -63, 3);
        VoiceLeveler leveler = new VoiceLeveler(RATE, 15);
        run(leveler, song.signal);
        float gainDb = leveler.getGainDb();

        // Instrumental break: 10 s of TV sound only
        run(leveler, new Song(-200, -63, 0, 10).signal);

        assertEquals(gainDb, leveler.getGainDb(), 1);
    }

    @Test
    public void steadySingingDoesNotPump() {
        Song song = new Song(-35, -60, 4);
        VoiceLeveler leveler = new VoiceLeveler(RATE, 10);
        float[] block = new float[FRAME];
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        boolean isSinging = true;
        int lastPhraseStart = song.phraseStart(3);

        for (int start = 0; start + FRAME <= song.signal.length; start += FRAME) {
            System.arraycopy(song.signal, start, block, 0, FRAME);
            leveler.process(block, FRAME);
            if (start >= lastPhraseStart + RATE / 2 && start < lastPhraseStart + (int) (PHRASE_S * RATE)) {
                min = Math.min(min, leveler.getGainDb());
                max = Math.max(max, leveler.getGainDb());
                isSinging &= leveler.isSinging();
            }
        }

        assertTrue("gain moved " + (max - min) + " dB", max - min < 1.5f);
        assertTrue(isSinging); // no expander dips inside the phrase
    }

    @Test
    public void removesRumble() {
        float[] signal = new float[RATE * 3];
        for (int i = 0; i < signal.length; i++) {
            signal[i] = (float) (0.05 * Math.sin(2 * Math.PI * 40 * i / RATE) + 0.05 * Math.sin(2 * Math.PI * 1000 * i / RATE));
        }

        float[] out = run(new VoiceLeveler(RATE, 0), signal);
        int from = RATE * 2;

        assertTrue(amplitude(out, from, 1000) / amplitude(out, from, 40) > 4); // > 12 dB
    }

    @Test
    public void silenceStaysSilent() {
        float[] out = run(new VoiceLeveler(RATE, 20), new float[RATE]);
        for (float value : out) {
            assertEquals(0, value, 0);
        }
    }

    private static float[] run(VoiceLeveler leveler, float[] signal) {
        float[] out = signal.clone();
        float[] block = new float[FRAME];
        for (int start = 0; start + FRAME <= out.length; start += FRAME) {
            System.arraycopy(out, start, block, 0, FRAME);
            leveler.process(block, FRAME);
            System.arraycopy(block, 0, out, start, FRAME);
        }
        return out;
    }

    /**
     * Phrases of a sung vowel-like tone with syllables, gaps where the phone only hears the TV (noisy music).
     */
    private static final class Song {
        final float[] signal;
        private final int mPhrases;

        Song(float voiceDb, float tvDb, int phrases) {
            this(voiceDb, tvDb, phrases, LEAD_IN_S + phrases * (PHRASE_S + GAP_S));
        }

        Song(float voiceDb, float tvDb, int phrases, float seconds) {
            mPhrases = phrases;
            signal = new float[(int) (seconds * RATE)];
            Random random = new Random(7);
            float voiceAmp = (float) Math.pow(10, voiceDb / 20) / 0.184f; // RMS of the sung tone below
            float tvAmp = (float) Math.pow(10, tvDb / 20) / 0.42f; // RMS of the TV sound below
            double phase = 0;
            float noise = 0;

            for (int i = 0; i < signal.length; i++) {
                double t = (double) i / RATE;
                double sung = t - LEAD_IN_S;
                boolean singing = sung >= 0 && sung < phrases * (PHRASE_S + GAP_S) && sung % (PHRASE_S + GAP_S) < PHRASE_S;

                phase += 2 * Math.PI * 196 / RATE;
                double syllables = 0.65 + 0.35 * Math.sin(2 * Math.PI * 3.5 * t);
                double voice = singing ? syllables * (0.3 * Math.sin(phase) + 0.2 * Math.sin(2 * phase + 1) + 0.1 * Math.sin(3 * phase + 2)) : 0;

                noise = noise * 0.9f + (random.nextFloat() - 0.5f) * 0.6f; // muffled music from across the room
                double tv = noise + 0.3 * Math.sin(2 * Math.PI * 330 * t) * Math.sin(2 * Math.PI * 0.7 * t);

                signal[i] = (float) (voice * voiceAmp + tv * tvAmp);
            }
        }

        int phraseStart(int index) {
            return (int) ((LEAD_IN_S + index * (PHRASE_S + GAP_S)) * RATE);
        }

        float lastPhraseDb(float[] out) {
            int start = phraseStart(mPhrases - 1) + RATE / 2; // after the expander opened
            return dbOf(rms(out, start, phraseStart(mPhrases - 1) + (int) (PHRASE_S * RATE)));
        }

        float lastGapDb(float[] out) {
            int start = phraseStart(mPhrases - 1) + (int) ((PHRASE_S + 0.6f) * RATE); // after the expander closed
            return dbOf(rms(out, start, phraseStart(mPhrases)));
        }
    }

    private static float rms(float[] signal, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += signal[i] * signal[i];
        }
        return (float) Math.sqrt(sum / (to - from));
    }

    private static float dbOf(float rms) {
        return (float) (20 * Math.log10(Math.max(rms, 1e-9f)));
    }

    /**
     * Amplitude of one frequency (single bin DFT).
     */
    private static float amplitude(float[] signal, int from, float hz) {
        double re = 0;
        double im = 0;
        for (int i = from; i < signal.length; i++) {
            double angle = 2 * Math.PI * hz * i / RATE;
            re += signal[i] * Math.cos(angle);
            im += signal[i] * Math.sin(angle);
        }
        return (float) (2 * Math.hypot(re, im) / (signal.length - from));
    }
}
