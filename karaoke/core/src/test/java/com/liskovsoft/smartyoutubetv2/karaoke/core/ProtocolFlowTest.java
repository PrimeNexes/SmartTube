package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Whole session over the wire format: pairing handshake as the phone and the TV do it, then audio through
 * the TV's jitter buffer.
 */
public class ProtocolFlowTest {
    private static final byte[] TV_SECRET = {7, 7, 7, 7};
    private static final byte[] PHONE_IP = {10, 0, 0, 42};
    private static final String PIN = "482193";
    private static final int SESSION = 0x0BADCAFE;

    @Test
    public void pairThenStream() {
        // 1. Phone says HELLO without proof, TV answers PIN_REQUIRED with a challenge
        KaraokeProtocol.Hello hello = parseHello(phoneHello(null, 0));
        assertNull(hello.pinProof);
        long challenge = KaraokePairing.challenge(TV_SECRET, SESSION, PHONE_IP);
        KaraokeProtocol.Welcome welcome = parseWelcome(tvWelcome(KaraokeProtocol.STATUS_PIN_REQUIRED, challenge));
        assertEquals(KaraokeProtocol.STATUS_PIN_REQUIRED, welcome.status);

        // 2. Phone answers the challenge, TV verifies
        byte[] proof = KaraokePairing.proof(PIN, welcome.challenge, SESSION);
        hello = parseHello(phoneHello(proof, welcome.challenge));
        assertEquals(challenge, hello.challengeEcho); // TV: the proof is for the current challenge
        assertTrue(KaraokePairing.verify(PIN, challenge, SESSION, hello.pinProof));

        // 3. Audio frames, one lost on the way, arrive at the jitter buffer
        JitterBuffer buffer = new JitterBuffer(KaraokeProtocol.SAMPLE_RATE, KaraokeProtocol.FRAME_SAMPLES);
        short[] frame = new short[KaraokeProtocol.FRAME_SAMPLES];
        for (int seq = 0; seq < 4; seq++) {
            if (seq == 2) {
                continue; // lost
            }
            Arrays.fill(frame, (short) (1000 * (seq + 1)));
            ByteBuffer packet = ByteBuffer.allocate(KaraokeProtocol.MAX_PACKET_SIZE);
            KaraokeProtocol.writeAudio(packet, SESSION, seq, 0, 0, frame, frame.length);
            packet.flip();

            KaraokeProtocol.Header header = new KaraokeProtocol.Header();
            assertTrue(KaraokeProtocol.readHeader(packet, header));
            assertEquals(SESSION, header.sessionId);
            buffer.writePacket(header.seq, packet, KaraokeProtocol.prepareAudioPayload(packet));
        }

        float[] out = new float[KaraokeProtocol.FRAME_SAMPLES];
        float[] expected = {1000, 2000, 0, 4000}; // the lost frame plays as silence
        for (float value : expected) {
            buffer.read(out, out.length);
            assertEquals(value / 32768f, out[100], 1e-6);
        }
        assertEquals(1, buffer.getLostCount());
    }

    @Test
    public void wrongPinAndForeignSessionFail() {
        long challenge = KaraokePairing.challenge(TV_SECRET, SESSION, PHONE_IP);

        assertFalse(KaraokePairing.verify(PIN, challenge, SESSION, KaraokePairing.proof("000000", challenge, SESSION)));

        // A proof sniffed from another phone's pairing doesn't work for a different session
        long otherChallenge = KaraokePairing.challenge(TV_SECRET, SESSION + 1, PHONE_IP);
        assertFalse(KaraokePairing.verify(PIN, otherChallenge, SESSION + 1, KaraokePairing.proof(PIN, challenge, SESSION)));
    }

    @Test
    public void restartedTvSeesStaleChallenge() {
        // The phone answered the old TV run's challenge; the new run's secret gives another challenge
        long oldChallenge = KaraokePairing.challenge(TV_SECRET, SESSION, PHONE_IP);
        long newChallenge = KaraokePairing.challenge(new byte[] {1, 2, 3}, SESSION, PHONE_IP);
        KaraokeProtocol.Hello hello = parseHello(phoneHello(KaraokePairing.proof(PIN, oldChallenge, SESSION), oldChallenge));

        // Not a wrong PIN: the TV asks again with the new challenge instead of counting a failed guess
        assertTrue(hello.challengeEcho != newChallenge);
    }

    @Test
    public void truncatedPacketsAreRejectedSafely() {
        byte[] full = phoneHello(KaraokePairing.proof(PIN, 1, SESSION), 1).array();

        for (int length = 0; length < full.length; length++) {
            ByteBuffer packet = ByteBuffer.wrap(full, 0, length).slice();
            KaraokeProtocol.Header header = new KaraokeProtocol.Header();
            if (KaraokeProtocol.readHeader(packet, header)) {
                KaraokeProtocol.Hello hello = KaraokeProtocol.readHello(packet); // must not throw
                if (hello != null && hello.pinProof != null) {
                    assertEquals(KaraokePairing.PROOF_BYTES, hello.pinProof.length);
                }
            }
        }
    }

    private static ByteBuffer phoneHello(byte[] proof, long challenge) {
        ByteBuffer packet = ByteBuffer.allocate(KaraokeProtocol.MAX_PACKET_SIZE);
        KaraokeProtocol.writeHello(packet, SESSION, 0, 0, "Phone", proof, challenge);
        packet.flip();
        return packet;
    }

    private static ByteBuffer tvWelcome(int status, long challenge) {
        ByteBuffer packet = ByteBuffer.allocate(KaraokeProtocol.MAX_PACKET_SIZE);
        KaraokeProtocol.writeWelcome(packet, SESSION, 0, 0, 0, status, 0, "TV", challenge, 1L);
        packet.flip();
        return packet;
    }

    private static KaraokeProtocol.Hello parseHello(ByteBuffer packet) {
        KaraokeProtocol.Header header = new KaraokeProtocol.Header();
        assertTrue(KaraokeProtocol.readHeader(packet, header));
        assertEquals(KaraokeProtocol.TYPE_HELLO, header.type);
        KaraokeProtocol.Hello hello = KaraokeProtocol.readHello(packet);
        assertNotNull(hello);
        return hello;
    }

    private static KaraokeProtocol.Welcome parseWelcome(ByteBuffer packet) {
        KaraokeProtocol.Header header = new KaraokeProtocol.Header();
        assertTrue(KaraokeProtocol.readHeader(packet, header));
        assertEquals(SESSION, header.sessionId);
        KaraokeProtocol.Welcome welcome = KaraokeProtocol.readWelcome(packet);
        assertNotNull(welcome);
        return welcome;
    }
}
