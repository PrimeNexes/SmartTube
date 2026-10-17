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
    public void removesCentredVoiceBand() {
        // 1 kHz tone panned to the centre (a "vocal")
        float rms = processStereo(1_000, 1_000, 1f, 1f);
        assertTrue("rms " + rms, rms < 0.05f);
    }

    @Test
    public void keepsCentredBass() {
        float rms = processStereo(50, 50, 1f, 1f);
        assertTrue("rms " + rms, rms > 0.6f);
    }

    @Test
    public void keepsSideInstruments() {
        // Same tone, opposite phase in L/R: a wide stereo instrument, mid is zero
        float rms = processStereo(1_000, 1_000, 1f, -1f);
        assertTrue("rms " + rms, rms > 0.95f);
    }

    @Test
    public void bypassIsBitExact() {
        VocalRemover remover = new VocalRemover(RATE, 2);
        ByteBuffer input = tone(1_000, 2, 1f, 1f);
        ByteBuffer output = ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer copy = input.duplicate();

        remover.process(input, output, 0);
        output.flip();

        assertEquals(copy, output);
        assertEquals(0, input.remaining());
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
        ByteBuffer output = ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN);

        remover.process(input, output, 1f);
        output.flip();

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
     * @return output RMS relative to the input amplitude, measured after the fade-in
     */
    private static float processStereo(int leftHz, int rightHz, float leftGain, float rightGain) {
        VocalRemover remover = new VocalRemover(RATE, 2);
        ByteBuffer input = tone(leftHz, rightHz, leftGain, rightGain);
        ByteBuffer output = ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN);

        remover.process(input, output, 1f);
        output.flip();

        double sum = 0;
        int count = 0;
        for (int i = FRAMES / 2; i < FRAMES; i++) {
            float left = output.getShort(i * 4) / 10_000f;
            sum += left * left;
            count++;
        }

        return (float) (Math.sqrt(sum / count) * Math.sqrt(2)); // sine RMS -> amplitude
    }

    private static ByteBuffer tone(int leftHz, int rightHz, float leftGain, float rightGain) {
        ByteBuffer buffer = ByteBuffer.allocate(FRAMES * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < FRAMES; i++) {
            buffer.putShort((short) (Math.sin(2 * Math.PI * leftHz * i / RATE) * 10_000 * leftGain));
            buffer.putShort((short) (Math.sin(2 * Math.PI * rightHz * i / RATE) * 10_000 * rightGain));
        }
        buffer.flip();
        return buffer;
    }
}
