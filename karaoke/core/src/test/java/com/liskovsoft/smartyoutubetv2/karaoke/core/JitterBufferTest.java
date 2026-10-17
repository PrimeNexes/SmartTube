package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JitterBufferTest {
    private static final int FRAME = KaraokeProtocol.FRAME_SAMPLES;
    private static final int TARGET = FRAME * 4;

    @Test
    public void holdsPlaybackUntilTargetIsQueued() {
        JitterBuffer buffer = new JitterBuffer(48_000, TARGET);
        float[] out = new float[FRAME];

        for (int seq = 0; seq < 3; seq++) {
            buffer.writePacket(seq, packet(1000), FRAME);
            assertEquals(0, buffer.read(out, FRAME));
        }

        buffer.writePacket(3, packet(1000), FRAME);
        assertEquals(FRAME, buffer.read(out, FRAME));
        assertEquals(1000 / 32768f, out[0], 1e-6);
    }

    @Test
    public void lostPacketsKeepTheTiming() {
        JitterBuffer buffer = new JitterBuffer(48_000, FRAME);
        buffer.writePacket(0, packet(1000), FRAME);
        buffer.writePacket(3, packet(1000), FRAME); // 1 and 2 lost

        assertEquals(2, buffer.getLostCount());
        assertEquals(FRAME * 4, buffer.getLevel());
    }

    @Test
    public void latePacketsAreDropped() {
        JitterBuffer buffer = new JitterBuffer(48_000, FRAME);
        buffer.writePacket(5, packet(1), FRAME);
        buffer.writePacket(4, packet(1), FRAME);

        assertEquals(1, buffer.getLateCount());
        assertEquals(FRAME, buffer.getLevel());
    }

    @Test
    public void senderRestartResyncs() {
        JitterBuffer buffer = new JitterBuffer(48_000, FRAME);
        buffer.writePacket(10_000, packet(1), FRAME);
        buffer.writePacket(0, packet(1), FRAME); // phone restarted streaming

        assertEquals(0, buffer.getLateCount());
        assertEquals(FRAME, buffer.getLevel());
    }

    @Test
    public void underrunRebuffers() {
        JitterBuffer buffer = new JitterBuffer(48_000, FRAME * 2);
        float[] out = new float[FRAME];
        buffer.writePacket(0, packet(1000), FRAME);
        buffer.writePacket(1, packet(1000), FRAME);

        assertEquals(FRAME, buffer.read(out, FRAME));
        assertEquals(FRAME, buffer.read(out, FRAME));
        assertEquals(0, buffer.read(out, FRAME));
        assertEquals(1, buffer.getUnderrunCount());
        assertTrue(buffer.isBuffering());
    }

    @Test
    public void driftCorrectionPullsLevelTowardsTarget() {
        JitterBuffer buffer = new JitterBuffer(48_000, TARGET);
        float[] out = new float[FRAME];
        int seq = 0;

        // Sender clock is slightly faster: one extra packet every 50
        for (int i = 0; i < 2_000; i++) {
            buffer.writePacket(seq++, packet(500), FRAME);
            if (i % 50 == 0) {
                buffer.writePacket(seq++, packet(500), FRAME);
            }
            buffer.read(out, FRAME);
        }

        int level = buffer.getLevel();
        assertTrue("level " + level, Math.abs(level - TARGET) <= TARGET);
        assertEquals(0, buffer.getUnderrunCount());
    }

    @Test
    public void burstAfterStallJumpsCloseToLive() {
        JitterBuffer buffer = new JitterBuffer(48_000, TARGET);
        float[] out = new float[FRAME];

        for (int seq = 0; seq < 100; seq++) { // 500 ms arriving at once
            buffer.writePacket(seq, packet(1), FRAME);
        }
        buffer.read(out, FRAME);

        assertTrue(buffer.getLevel() <= TARGET);
    }

    @Test
    public void targetChangeFromAnotherThreadAppliesOnNextFrame() throws InterruptedException {
        JitterBuffer buffer = new JitterBuffer(48_000, TARGET);
        Thread ui = new Thread(() -> buffer.setTargetSamples(FRAME));
        ui.start();
        ui.join();

        buffer.writePacket(0, packet(1), FRAME); // one frame now reaches the new, smaller target
        assertEquals(FRAME, buffer.read(new float[FRAME], FRAME));
        assertEquals(FRAME, buffer.getTargetSamples());
    }

    @Test
    public void statsAreReadableWithoutTheLock() {
        JitterBuffer buffer = new JitterBuffer(48_000, TARGET);
        buffer.writePacket(0, packet(1), FRAME);
        buffer.writePacket(2, packet(1), FRAME);

        assertEquals(2, buffer.getPacketCount());
        assertEquals(1, buffer.getLostCount());
        assertEquals(FRAME * 3, buffer.getLevel());
    }

    @Test
    public void lostPacketContinuesTheVoice() {
        float[] tone = sine(220, FRAME * 40);
        float[] out = playThrough(tone, new int[] {20}, FRAME * 2);

        // The made up 5 ms is close to what was lost, and nothing clicks
        assertTrue(rmsError(tone, out, FRAME * 20, FRAME * 21) < 0.03f);
        assertTrue(maxStep(out) < maxStep(tone) * 1.2f);
    }

    @Test
    public void longLossFadesToSilence() {
        float[] tone = sine(220, FRAME * 60);
        int[] lost = new int[15]; // 75 ms
        for (int i = 0; i < lost.length; i++) {
            lost[i] = 20 + i;
        }
        float[] out = playThrough(tone, lost, FRAME * 2);

        assertTrue(rmsError(tone, out, FRAME * 20, FRAME * 22) < 0.03f); // first 10 ms: as if nothing happened
        for (int i = FRAME * 32; i < FRAME * 34; i++) {
            assertEquals(0, out[i], 1e-4); // after 60 ms: silent
        }
        assertTrue(maxStep(out) < maxStep(tone) * 2); // fades, a click would be 30x steeper
    }

    @Test
    public void underrunKeepsTheVoiceGoing() {
        JitterBuffer buffer = new JitterBuffer(48_000, FRAME * 2);
        float[] tone = sine(220, FRAME * 40);
        float[] out = new float[FRAME * 40];
        float[] frame = new float[FRAME];
        int written = 0;
        int read = 0;

        // Packets stop after 10 frames while the TV keeps reading, then come back
        for (int k = 0; k < 10; k++) {
            buffer.writePacket(written, packet(tone, written), FRAME);
            written++;
        }
        while (read < 16) {
            buffer.read(frame, FRAME);
            System.arraycopy(frame, 0, out, read++ * FRAME, FRAME);
        }
        assertTrue(buffer.getUnderrunCount() >= 1);

        for (int k = 0; k < 10; k++) {
            buffer.writePacket(written, packet(tone, written), FRAME);
            written++;
        }
        while (read < 24) {
            buffer.read(frame, FRAME);
            System.arraycopy(frame, 0, out, read++ * FRAME, FRAME);
        }
        out = Arrays.copyOf(out, read * FRAME);

        // First frame without data continues the tone instead of dropping to silence, no clicks anywhere
        float energy = 0;
        for (int i = FRAME * 10; i < FRAME * 11; i++) {
            energy += out[i] * out[i];
        }
        assertTrue(Math.sqrt(energy / FRAME) > 0.2f);
        assertTrue(maxStep(out) < maxStep(tone) * 2); // blends back, a click would be 30x steeper
    }

    /**
     * Sends the signal as packets (skipping the lost ones), reads it back frame by frame.
     */
    private static float[] playThrough(float[] signal, int[] lost, int target) {
        JitterBuffer buffer = new JitterBuffer(48_000, target);
        float[] out = new float[signal.length];
        float[] frame = new float[FRAME];
        int frames = signal.length / FRAME;
        int read = 0;

        for (int seq = 0; seq < frames; seq++) {
            // Like the TV: one frame out per frame in, once the target is queued (level stays at the target)
            if (seq * FRAME >= target) {
                buffer.read(frame, FRAME);
                System.arraycopy(frame, 0, out, read++ * FRAME, FRAME);
            }

            boolean isLost = false;
            for (int index : lost) {
                isLost |= index == seq;
            }
            if (!isLost) {
                buffer.writePacket(seq, packet(signal, seq), FRAME);
            }
        }

        return Arrays.copyOf(out, read * FRAME);
    }

    private static float[] sine(float hz, int length) {
        float[] signal = new float[length];
        for (int i = 0; i < length; i++) {
            signal[i] = (float) (0.5 * Math.sin(2 * Math.PI * hz * i / 48_000));
        }
        return signal;
    }

    private static ByteBuffer packet(float[] signal, int seq) {
        ByteBuffer buffer = ByteBuffer.allocate(FRAME * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < FRAME; i++) {
            buffer.putShort((short) Math.round(signal[seq * FRAME + i] * 32767));
        }
        buffer.flip();
        return buffer;
    }

    private static float rmsError(float[] expected, float[] actual, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            double delta = expected[i] - actual[i];
            sum += delta * delta;
        }
        return (float) Math.sqrt(sum / (to - from));
    }

    private static float maxStep(float[] signal) {
        float max = 0;
        for (int i = 1; i < signal.length; i++) {
            max = Math.max(max, Math.abs(signal[i] - signal[i - 1]));
        }
        return max;
    }

    private static ByteBuffer packet(int value) {
        ByteBuffer buffer = ByteBuffer.allocate(FRAME * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < FRAME; i++) {
            buffer.putShort((short) value);
        }
        buffer.flip();
        return buffer;
    }
}
