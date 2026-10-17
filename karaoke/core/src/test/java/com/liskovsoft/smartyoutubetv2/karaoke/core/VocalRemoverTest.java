package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VocalRemoverTest {
    private static final int RATE = 48_000;
    private static final int FRAMES = RATE / 2;

    @Test
    public void removesCentredVoiceAcrossTheVocalRange() {
        // A tone panned to the centre (a "vocal"), output level relative to input
        for (int hz : new int[] {300, 1_000, 3_000}) {
            float level = centred(hz, 1f);
            assertTrue(hz + " Hz left " + level, level < 0.1f); // at least -20 dB
        }
    }

    @Test
    public void keepsCentredBassAndCymbalsWithoutBoost() {
        float bass = centred(50, 1f);
        assertTrue("bass " + bass, bass > 0.9f && bass < 1.05f);

        float cymbals = centred(12_000, 1f);
        assertTrue("cymbals " + cymbals, cymbals > 0.8f && cymbals < 1.05f);
    }

    @Test
    public void partialStrengthHasNoDipAtTheCrossover() {
        float level = centred((int) VocalRemover.LOW_CROSSOVER_HZ, 0.5f);
        assertTrue("level " + level, level > 0.6f && level < 1.05f);
    }

    @Test
    public void keepsSideInstruments() {
        // Same tone, opposite phase in L/R: a wide stereo instrument, mid is zero
        VocalRemover remover = new VocalRemover(RATE, 2);
        ByteBuffer output = process(remover, tone(1_000, 1f, -1f), 1f);
        assertTrue(rms(output, 0, 4) > 0.95f);
    }

    @Test
    public void bypassIsBitExact() {
        VocalRemover remover = new VocalRemover(RATE, 2);
        ByteBuffer input = tone(1_000, 1f, 1f);
        ByteBuffer copy = input.duplicate();

        ByteBuffer output = process(remover, input, 0);

        assertEquals(copy, output);
        assertEquals(0, input.remaining());
    }

    @Test
    public void turningOffReturnsToBypass() {
        VocalRemover remover = new VocalRemover(RATE, 2);
        process(remover, tone(1_000, 1f, 1f), 1f);
        process(remover, tone(1_000, 1f, 1f), 0); // fades back

        ByteBuffer input = tone(1_000, 1f, 1f);
        ByteBuffer copy = input.duplicate();
        assertEquals(copy, process(remover, input, 0));
    }

    @Test
    public void seekKeepsStrength() {
        VocalRemover remover = new VocalRemover(RATE, 2);
        process(remover, tone(1_000, 1f, 1f), 1f);
        remover.resetFilters(); // what a seek does

        // No fade-in again: the voice stays gone from the start (only the filters settle, ~1 ms)
        ByteBuffer output = process(remover, tone(1_000, 1f, 1f), 1f);
        assertTrue(rms(output, RATE / 100, 4) < 0.1f);
    }

    @Test
    public void surroundCentreChannelIsCleaned() {
        VocalRemover remover = new VocalRemover(RATE, 6);
        ByteBuffer input = ByteBuffer.allocate(FRAMES * 12).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < FRAMES; i++) {
            short voice = (short) (Math.sin(2 * Math.PI * 1_000 * i / RATE) * 10_000);
            input.putShort((short) 0).putShort((short) 0).putShort(voice)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0);
        }
        input.flip();

        ByteBuffer output = process(remover, input, 1f);

        double sum = 0;
        int count = 0;
        for (int i = FRAMES / 2; i < FRAMES; i++) { // skip the fade-in
            float centre = output.getShort(i * 12 + 4) / 10_000f;
            sum += centre * centre;
            count++;
        }
        assertTrue(Math.sqrt(sum / count) < 0.05);
    }

    /**
     * @return left channel level of a centred tone relative to the input, after the fade-in
     */
    private static float centred(int hz, float strength) {
        VocalRemover remover = new VocalRemover(RATE, 2);
        ByteBuffer output = process(remover, tone(hz, 1f, 1f), strength);
        return rms(output, FRAMES / 2, 4);
    }

    private static ByteBuffer process(VocalRemover remover, ByteBuffer input, float strength) {
        ByteBuffer output = ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        remover.process(input, output, strength);
        output.flip();
        return output;
    }

    /**
     * Left channel RMS from the given frame on, as a sine amplitude relative to 10000.
     */
    private static float rms(ByteBuffer output, int fromFrame, int frameBytes) {
        double sum = 0;
        int count = 0;
        for (int i = fromFrame; i < output.remaining() / frameBytes; i++) {
            float left = output.getShort(i * frameBytes) / 10_000f;
            sum += left * left;
            count++;
        }
        return (float) (Math.sqrt(sum / count) * Math.sqrt(2));
    }

    private static ByteBuffer tone(int hz, float leftGain, float rightGain) {
        ByteBuffer buffer = ByteBuffer.allocate(FRAMES * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < FRAMES; i++) {
            double sample = Math.sin(2 * Math.PI * hz * i / RATE) * 10_000;
            buffer.putShort((short) (sample * leftGain));
            buffer.putShort((short) (sample * rightGain));
        }
        buffer.flip();
        return buffer;
    }
}
