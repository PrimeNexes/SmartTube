package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KaraokeProtocolTest {
    @Test
    public void helloRoundTrip() {
        ByteBuffer buffer = ByteBuffer.allocate(KaraokeProtocol.MAX_PACKET_SIZE);
        KaraokeProtocol.writeHello(buffer, 0x12345678, 7, 123_456, "Pixel 7", null);
        buffer.flip();

        KaraokeProtocol.Header header = new KaraokeProtocol.Header();
        assertTrue(KaraokeProtocol.readHeader(buffer, header));
        assertEquals(KaraokeProtocol.TYPE_HELLO, header.type);
        assertEquals(0x12345678, header.sessionId);
        assertEquals(7, header.seq);
        assertEquals(123_456, header.timeMs);

        KaraokeProtocol.Hello hello = KaraokeProtocol.readHello(buffer);
        assertNotNull(hello);
        assertEquals(KaraokeProtocol.SAMPLE_RATE, hello.sampleRate);
        assertEquals(KaraokeProtocol.FRAME_SAMPLES, hello.frameSamples);
        assertEquals(1, hello.channels);
        assertEquals("Pixel 7", hello.name);
        assertNull(hello.pinProof);
    }

    @Test
    public void helloCarriesPinProof() {
        byte[] proof = KaraokePairing.proof("123456", 42L, 7);
        ByteBuffer buffer = ByteBuffer.allocate(KaraokeProtocol.MAX_PACKET_SIZE);
        KaraokeProtocol.writeHello(buffer, 7, 0, 0, "Phone", proof);
        buffer.flip();

        assertTrue(KaraokeProtocol.readHeader(buffer, new KaraokeProtocol.Header()));
        KaraokeProtocol.Hello hello = KaraokeProtocol.readHello(buffer);
        assertNotNull(hello);
        assertArrayEquals(proof, hello.pinProof);
    }

    @Test
    public void welcomeRoundTrip() {
        ByteBuffer buffer = ByteBuffer.allocate(KaraokeProtocol.MAX_PACKET_SIZE);
        KaraokeProtocol.writeWelcome(buffer, 42, 1, 2, 999, KaraokeProtocol.STATUS_FULL, 3, "Living room TV", -5L);
        buffer.flip();

        KaraokeProtocol.Header header = new KaraokeProtocol.Header();
        assertTrue(KaraokeProtocol.readHeader(buffer, header));
        assertEquals(KaraokeProtocol.TYPE_WELCOME, header.type);

        KaraokeProtocol.Welcome welcome = KaraokeProtocol.readWelcome(buffer);
        assertNotNull(welcome);
        assertEquals(999, welcome.echoTimeMs);
        assertEquals(KaraokeProtocol.STATUS_FULL, welcome.status);
        assertEquals(3, welcome.slot);
        assertEquals("Living room TV", welcome.tvName);
        assertEquals(-5L, welcome.challenge);
        assertEquals(42, header.sessionId);
    }

    @Test
    public void audioSamplesAreLittleEndian() {
        short[] samples = {1, -2, Short.MAX_VALUE, Short.MIN_VALUE};
        ByteBuffer buffer = ByteBuffer.allocate(KaraokeProtocol.MAX_PACKET_SIZE);
        KaraokeProtocol.writeAudio(buffer, 1, 2, 3, KaraokeProtocol.FLAG_MUTED, samples, samples.length);
        buffer.flip();

        assertEquals(KaraokeProtocol.HEADER_SIZE + 8, buffer.remaining());
        assertEquals(1, buffer.get(KaraokeProtocol.HEADER_SIZE)); // low byte first
        assertEquals(0, buffer.get(KaraokeProtocol.HEADER_SIZE + 1));

        KaraokeProtocol.Header header = new KaraokeProtocol.Header();
        assertTrue(KaraokeProtocol.readHeader(buffer, header));
        assertEquals(KaraokeProtocol.FLAG_MUTED, header.flags);
        assertEquals(samples.length, KaraokeProtocol.prepareAudioPayload(buffer));
        for (short sample : samples) {
            assertEquals(sample, buffer.getShort());
        }
    }

    @Test
    public void rejectsForeignDatagrams() {
        KaraokeProtocol.Header header = new KaraokeProtocol.Header();
        assertFalse(KaraokeProtocol.readHeader(ByteBuffer.wrap(new byte[5]), header));
        assertFalse(KaraokeProtocol.readHeader(ByteBuffer.wrap(new byte[KaraokeProtocol.HEADER_SIZE]), header));
    }

    @Test
    public void longNamesAreCutOnCharBoundary() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            name.append("🎤"); // microphone emoji, 4 bytes in UTF-8
        }

        byte[] bytes = KaraokeProtocol.encodeName(name.toString());
        assertTrue(bytes.length <= KaraokeProtocol.MAX_NAME_BYTES);
        assertEquals(0, bytes.length % 4);
    }
}
