package com.liskovsoft.smartyoutubetv2.karaoke.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

/**
 * Wire format shared by the SmartTube TV receiver and the phone microphone app.<br/>
 * One UDP datagram carries one message. Header fields are big-endian, PCM samples are little-endian.
 * <pre>
 * offset size field
 * 0      4    magic "STKM"
 * 4      1    protocol version
 * 5      1    message type
 * 6      1    flags
 * 7      1    reserved
 * 8      4    session id (random, picked by the mic for every streaming session)
 * 12     4    sequence number
 * 16     4    sender clock, ms (echoed back by the TV, so the mic can show round trip time)
 * 20     ...  payload
 * </pre>
 * HELLO (mic to TV, every {@link #HELLO_INTERVAL_MS}): int sample rate, short frame size, byte channels, name,
 * PIN proof (byte length + bytes, see {@link KaraokePairing}), followed by the challenge it answers when present.<br/>
 * WELCOME (TV to mic, reply to HELLO, header carries the mic's session id): int echoed sender clock, byte status,
 * byte mic slot, TV name, long pairing challenge, long TV id (random, stable per TV).<br/>
 * AUDIO (mic to TV): mono 16 bit PCM at {@link #SAMPLE_RATE}.<br/>
 * BYE (mic to TV): no payload.
 */
public final class KaraokeProtocol {
    public static final int DEFAULT_PORT = 47900;
    /** DNS-SD (Android NSD) service type the TV advertises. */
    public static final String SERVICE_TYPE = "_stkaraoke._udp";
    public static final int SAMPLE_RATE = 48_000;
    public static final int CHANNELS = 1;
    /** 5 ms of audio per datagram. */
    public static final int FRAME_SAMPLES = 240;
    public static final int MAX_FRAME_SAMPLES = 960;
    public static final int HELLO_INTERVAL_MS = 1_000;
    /** The TV drops a mic that sends nothing for that long. */
    public static final int TIMEOUT_MS = 4_000;

    public static final int MAGIC = 0x53544B4D; // "STKM"
    public static final int VERSION = 3;
    public static final int HEADER_SIZE = 20;
    public static final int MAX_NAME_BYTES = 48;
    public static final int MAX_PACKET_SIZE = HEADER_SIZE + MAX_FRAME_SAMPLES * 2;

    public static final int TYPE_HELLO = 1;
    public static final int TYPE_WELCOME = 2;
    public static final int TYPE_AUDIO = 3;
    public static final int TYPE_BYE = 4;

    public static final int STATUS_OK = 0;
    /** All mic slots are taken. */
    public static final int STATUS_FULL = 1;
    /** Audio format not supported by this TV. */
    public static final int STATUS_UNSUPPORTED = 2;
    /** Send HELLO again with a proof of the PIN shown on the TV. */
    public static final int STATUS_PIN_REQUIRED = 3;
    public static final int STATUS_PIN_WRONG = 4;
    /** Too many wrong PINs, try again in a minute. */
    public static final int STATUS_PIN_LOCKED = 5;

    public static final int FLAG_MUTED = 1;

    // NOTE: StandardCharsets requires API 19
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private KaraokeProtocol() {
    }

    public static final class Header {
        public int type;
        public int flags;
        public int sessionId;
        public int seq;
        public int timeMs;
    }

    public static final class Hello {
        public int sampleRate;
        public int frameSamples;
        public int channels;
        public String name;
        /** Null when the mic has no PIN yet. */
        public byte[] pinProof;
        /** The challenge the proof was made for (a restarted TV has a new one). */
        public long challengeEcho;
    }

    public static final class Welcome {
        public int echoTimeMs;
        public int status;
        public int slot;
        public String tvName;
        public long challenge;
        public long tvId;
    }

    /**
     * Reads the header and leaves the buffer positioned at the payload.
     * @return false for foreign or malformed datagrams
     */
    public static boolean readHeader(ByteBuffer buffer, Header out) {
        if (buffer.remaining() < HEADER_SIZE) {
            return false;
        }

        buffer.order(ByteOrder.BIG_ENDIAN);

        if (buffer.getInt() != MAGIC || buffer.get() != VERSION) {
            return false;
        }

        out.type = buffer.get();
        out.flags = buffer.get() & 0xFF;
        buffer.get(); // reserved
        out.sessionId = buffer.getInt();
        out.seq = buffer.getInt();
        out.timeMs = buffer.getInt();

        return true;
    }

    public static void writeHeader(ByteBuffer buffer, int type, int flags, int sessionId, int seq, int timeMs) {
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC)
                .put((byte) VERSION)
                .put((byte) type)
                .put((byte) flags)
                .put((byte) 0)
                .putInt(sessionId)
                .putInt(seq)
                .putInt(timeMs);
    }

    /**
     * @param pinProof null if the mic doesn't know the PIN (yet)
     * @param challenge the challenge the proof answers
     */
    public static void writeHello(ByteBuffer buffer, int sessionId, int seq, int timeMs, String name, byte[] pinProof, long challenge) {
        writeHeader(buffer, TYPE_HELLO, 0, sessionId, seq, timeMs);
        buffer.putInt(SAMPLE_RATE)
                .putShort((short) FRAME_SAMPLES)
                .put((byte) CHANNELS);
        putName(buffer, name);
        putBytes(buffer, pinProof);
        if (pinProof != null) {
            buffer.putLong(challenge);
        }
    }

    /**
     * @return null if payload is truncated
     */
    public static Hello readHello(ByteBuffer buffer) {
        if (buffer.remaining() < 7) {
            return null;
        }

        Hello hello = new Hello();
        hello.sampleRate = buffer.getInt();
        hello.frameSamples = buffer.getShort() & 0xFFFF;
        hello.channels = buffer.get() & 0xFF;
        hello.name = getName(buffer);
        hello.pinProof = getBytes(buffer);

        if (hello.pinProof != null) {
            if (buffer.remaining() < 8) {
                hello.pinProof = null; // a proof without its challenge is no proof
            } else {
                hello.challengeEcho = buffer.getLong();
            }
        }

        return hello;
    }

    public static void writeWelcome(ByteBuffer buffer, int sessionId, int seq, int timeMs,
                                    int echoTimeMs, int status, int slot, String tvName, long challenge, long tvId) {
        writeHeader(buffer, TYPE_WELCOME, 0, sessionId, seq, timeMs);
        buffer.putInt(echoTimeMs)
                .put((byte) status)
                .put((byte) slot);
        putName(buffer, tvName);
        buffer.putLong(challenge)
                .putLong(tvId);
    }

    /**
     * @return null if payload is truncated
     */
    public static Welcome readWelcome(ByteBuffer buffer) {
        if (buffer.remaining() < 6) {
            return null;
        }

        Welcome welcome = new Welcome();
        welcome.echoTimeMs = buffer.getInt();
        welcome.status = buffer.get() & 0xFF;
        welcome.slot = buffer.get() & 0xFF;
        welcome.tvName = getName(buffer);

        if (buffer.remaining() < 16) {
            return null;
        }

        welcome.challenge = buffer.getLong();
        welcome.tvId = buffer.getLong();

        return welcome;
    }

    public static void writeAudio(ByteBuffer buffer, int sessionId, int seq, int timeMs, int flags, short[] samples, int count) {
        writeHeader(buffer, TYPE_AUDIO, flags, sessionId, seq, timeMs);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            buffer.putShort(samples[i]);
        }
        buffer.order(ByteOrder.BIG_ENDIAN);
    }

    /**
     * Switches the buffer (positioned at AUDIO payload) to sample order.
     * @return samples available for reading
     */
    public static int prepareAudioPayload(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return Math.min(buffer.remaining() / 2, MAX_FRAME_SAMPLES);
    }

    public static void writeBye(ByteBuffer buffer, int sessionId, int seq, int timeMs) {
        writeHeader(buffer, TYPE_BYE, 0, sessionId, seq, timeMs);
    }

    private static void putBytes(ByteBuffer buffer, byte[] bytes) {
        if (bytes == null) {
            buffer.put((byte) 0);
            return;
        }

        buffer.put((byte) bytes.length).put(bytes);
    }

    private static byte[] getBytes(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return null;
        }

        int length = buffer.get() & 0xFF;

        if (length == 0 || length > buffer.remaining()) {
            return null;
        }

        byte[] bytes = new byte[length];
        buffer.get(bytes);

        return bytes;
    }

    private static void putName(ByteBuffer buffer, String name) {
        byte[] bytes = encodeName(name);
        buffer.put((byte) bytes.length).put(bytes);
    }

    private static String getName(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return "";
        }

        int length = Math.min(buffer.get() & 0xFF, buffer.remaining());
        byte[] bytes = new byte[length];
        buffer.get(bytes);

        // Peers may send up to 255 bytes, names shown on screen are kept short
        String name = new String(bytes, UTF_8);
        return length > MAX_NAME_BYTES ? new String(encodeName(name), UTF_8) : name;
    }

    static byte[] encodeName(String name) {
        if (name == null) {
            return new byte[0];
        }

        String result = name.trim();
        byte[] bytes = result.getBytes(UTF_8);

        // Cut on a character boundary, don't leave half of a surrogate pair
        while (bytes.length > MAX_NAME_BYTES) {
            int end = result.length() - 1;
            if (end > 0 && Character.isHighSurrogate(result.charAt(end - 1))) {
                end--;
            }
            result = result.substring(0, end);
            bytes = result.getBytes(UTF_8);
        }

        return bytes;
    }
}
