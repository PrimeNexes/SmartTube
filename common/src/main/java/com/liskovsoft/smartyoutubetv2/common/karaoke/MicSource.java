package com.liskovsoft.smartyoutubetv2.common.karaoke;

import android.os.SystemClock;

import com.liskovsoft.smartyoutubetv2.karaoke.core.AutoLevel;
import com.liskovsoft.smartyoutubetv2.karaoke.core.JitterBuffer;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokeProtocol;
import com.liskovsoft.smartyoutubetv2.karaoke.core.NoiseGate;

import java.net.InetAddress;

/**
 * One microphone (phone over Wi-Fi or a mic plugged into the TV) feeding the karaoke mixer.
 */
public final class MicSource {
    public static final int LOCAL_ID = 0;
    private final int mId;
    private final int mSlot;
    private final JitterBuffer mBuffer;
    private final NoiseGate mGate = new NoiseGate(KaraokeProtocol.SAMPLE_RATE);
    private final AutoLevel mAutoLevel = new AutoLevel(KaraokeProtocol.SAMPLE_RATE);
    private volatile String mName;
    private volatile InetAddress mHost;
    private volatile int mPort;
    private volatile long mLastSeenMs;
    private volatile boolean mMuted;

    MicSource(int id, int slot, String name, int targetLatencyMs) {
        mId = id;
        mSlot = slot;
        mName = name;
        mBuffer = new JitterBuffer(KaraokeProtocol.SAMPLE_RATE, toSamples(targetLatencyMs));
        touch();
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

    void setPeer(InetAddress host, int port) {
        mHost = host;
        mPort = port;
    }

    /**
     * Allocation free check that a packet came from this mic's phone.
     */
    boolean isFrom(InetAddress host, int port) {
        return port == mPort && host.equals(mHost);
    }

    void setMuted(boolean muted) {
        mMuted = muted;
    }

    /**
     * Monotonic clock: wall clock jumps (NTP) must not drop or keep mics.
     */
    void touch() {
        mLastSeenMs = SystemClock.elapsedRealtime();
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

    /**
     * Mixer thread only.
     */
    AutoLevel getAutoLevel() {
        return mAutoLevel;
    }

    private static int toSamples(int latencyMs) {
        return KaraokeProtocol.SAMPLE_RATE * latencyMs / 1_000;
    }
}
