package com.liskovsoft.smartyoutubetv2.common.karaoke;

import com.liskovsoft.smartyoutubetv2.karaoke.core.JitterBuffer;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokeProtocol;
import com.liskovsoft.smartyoutubetv2.karaoke.core.NoiseGate;

import java.net.SocketAddress;

/**
 * One microphone (phone over Wi-Fi or a mic plugged into the TV) feeding the karaoke mixer.
 */
public final class MicSource {
    public static final int LOCAL_ID = 0;
    private final int mId;
    private final int mSlot;
    private final JitterBuffer mBuffer;
    private final NoiseGate mGate = new NoiseGate(KaraokeProtocol.SAMPLE_RATE);
    private volatile String mName;
    private volatile SocketAddress mAddress;
    private volatile long mLastSeenMs;
    private volatile boolean mMuted;

    MicSource(int id, int slot, String name, int targetLatencyMs) {
        mId = id;
        mSlot = slot;
        mName = name;
        mBuffer = new JitterBuffer(KaraokeProtocol.SAMPLE_RATE, toSamples(targetLatencyMs));
        mLastSeenMs = System.currentTimeMillis();
    }

    public int getId() {
        return mId;
    }

    /**
     * 1-based number shown to the user.
     */
    public int getSlot() {
        return mSlot;
    }

    public String getName() {
        return mName;
    }

    public boolean isLocal() {
        return mId == LOCAL_ID;
    }

    public boolean isMuted() {
        return mMuted;
    }

    public long getLostPackets() {
        return mBuffer.getLostCount();
    }

    public long getReceivedPackets() {
        return mBuffer.getPacketCount();
    }

    public long getUnderruns() {
        return mBuffer.getUnderrunCount();
    }

    /**
     * Current mic delay added by buffering, ms.
     */
    public int getBufferedMs() {
        return mBuffer.getLevel() * 1_000 / KaraokeProtocol.SAMPLE_RATE;
    }

    void setName(String name) {
        if (name != null && !name.isEmpty()) {
            mName = name;
        }
    }

    SocketAddress getAddress() {
        return mAddress;
    }

    void setAddress(SocketAddress address) {
        mAddress = address;
    }

    void setMuted(boolean muted) {
        mMuted = muted;
    }

    void touch() {
        mLastSeenMs = System.currentTimeMillis();
    }

    long getLastSeenMs() {
        return mLastSeenMs;
    }

    void setTargetLatencyMs(int latencyMs) {
        mBuffer.setTargetSamples(toSamples(latencyMs));
    }

    JitterBuffer getBuffer() {
        return mBuffer;
    }

    /**
     * Mixer thread only.
     */
    NoiseGate getGate() {
        return mGate;
    }

    private static int toSamples(int latencyMs) {
        return KaraokeProtocol.SAMPLE_RATE * latencyMs / 1_000;
    }
}
