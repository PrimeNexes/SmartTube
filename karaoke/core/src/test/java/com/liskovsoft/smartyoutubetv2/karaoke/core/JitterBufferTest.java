package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
    public void lostPacketsBecomeSilence() {
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

    private static ByteBuffer packet(int value) {
        ByteBuffer buffer = ByteBuffer.allocate(FRAME * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < FRAME; i++) {
            buffer.putShort((short) value);
        }
        buffer.flip();
        return buffer;
    }
}
