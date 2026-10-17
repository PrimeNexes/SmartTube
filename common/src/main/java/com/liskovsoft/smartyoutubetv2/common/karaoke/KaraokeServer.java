package com.liskovsoft.smartyoutubetv2.common.karaoke;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Process;
import android.os.SystemClock;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokePairing;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokeProtocol;
import com.liskovsoft.smartyoutubetv2.karaoke.core.PinChecker;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives phone mics over UDP (see {@link KaraokeProtocol}) and advertises itself on the LAN via NSD (mDNS),
 * so the phone app finds the TV without typing an address.<br/>
 * The audio path (finding the mic, checking the sender) doesn't allocate.
 */
final class KaraokeServer {
    private static final String TAG = KaraokeServer.class.getSimpleName();
    static final int MAX_MICS = 4;
    private static final int SOCKET_TIMEOUT_MS = 500;
    private static final int RECEIVE_BUFFER_BYTES = 256 * 1024;
    private static final int MAX_REMEMBERED_SESSIONS = 64;
    private final Context mContext;
    private final String mTvName;
    private final long mTvId;
    private final Callback mCallback;
    private final Map<Integer, MicSource> mSources = new ConcurrentHashMap<>();
    /** Copy of mSources sorted by slot, for the mixer and the audio path that must not allocate. */
    private volatile MicSource[] mSnapshot = new MicSource[0];
    private final PinChecker mPinChecker = new PinChecker();
    private final byte[] mChallengeSecret = new byte[16];
    // Receive thread only
    private final Set<Integer> mRejected = new HashSet<>();
    private final ByteBuffer mSendBuffer = ByteBuffer.allocate(KaraokeProtocol.MAX_PACKET_SIZE);
    private final DatagramPacket mSendPacket = new DatagramPacket(mSendBuffer.array(), 0);
    private int mSendSeq;
    private volatile int mLatencyMs;
    private volatile boolean mIsRunning;
    private DatagramSocket mSocket;
    private Thread mThread;
    private NsdManager mNsdManager;
    private NsdManager.RegistrationListener mRegistrationListener;

    interface Callback {
        void onMicConnected(MicSource source);
        /**
         * @param announce false when the TV dropped the mic on purpose (new PIN), true when the mic left or went silent
         */
        void onMicDisconnected(MicSource source, boolean announce);
        void onMicRejected(String name, int status);
    }

    @SuppressLint("TrulyRandom") // Android 4.3 and older seed it weakly: the PIN rate limit still applies
    KaraokeServer(Context context, String tvName, long tvId, int latencyMs, Callback callback) {
        mContext = context;
        mTvName = tvName;
        mTvId = tvId;
        mLatencyMs = latencyMs;
        mCallback = callback;
        new SecureRandom().nextBytes(mChallengeSecret);
    }

    /**
     * @param pin null lets any mic on the network join. A new PIN drops the mics paired with the old one.
     */
    void setPin(String pin) {
        if (mPinChecker.setPin(pin)) {
            Iterator<MicSource> iterator = mSources.values().iterator();
            while (iterator.hasNext()) {
                MicSource source = iterator.next();
                iterator.remove();
                mCallback.onMicDisconnected(source, false);
            }
            updateSnapshot();
        }
    }

    boolean start(int preferredPort) {
        try {
            mSocket = bind(preferredPort);
            mSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            mSocket.setReceiveBufferSize(RECEIVE_BUFFER_BYTES);
        } catch (SocketException e) {
            Log.e(TAG, "Can't open karaoke socket: %s", e.getMessage());
            if (mSocket != null) {
                mSocket.close();
                mSocket = null;
            }
            return false;
        }

        mIsRunning = true;
        mThread = new Thread(this::receiveLoop, "KaraokeServer");
        mThread.start();

        registerService(mSocket.getLocalPort());

        Log.d(TAG, "Karaoke server is listening on port %s", mSocket.getLocalPort());

        return true;
    }

    void stop() {
        mIsRunning = false;

        unregisterService();

        if (mSocket != null) {
            mSocket.close(); // unblocks receive()
        }

        if (mThread != null) {
            try {
                mThread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mThread = null;
        }

        mSocket = null;
        mSources.clear();
        updateSnapshot();
    }

    int getPort() {
        DatagramSocket socket = mSocket;
        return socket != null ? socket.getLocalPort() : -1;
    }

    void setLatencyMs(int latencyMs) {
        mLatencyMs = latencyMs;

        for (MicSource source : mSnapshot) {
            source.setTargetLatencyMs(latencyMs);
        }
    }

    /**
     * Mixer thread: copies currently connected mics into the array without allocating.
     * @return number of copied mics
     */
    int getSources(MicSource[] out, int offset) {
        MicSource[] snapshot = mSnapshot;
        int count = Math.min(snapshot.length, out.length - offset);
        System.arraycopy(snapshot, 0, out, offset, count);
        return count;
    }

    /**
     * Connected mics, sorted by slot.
     */
    List<MicSource> getSources() {
        return Arrays.asList(mSnapshot.clone());
    }

    private synchronized void updateSnapshot() {
        MicSource[] snapshot = mSources.values().toArray(new MicSource[0]);
        Arrays.sort(snapshot, (o1, o2) -> o1.getSlot() - o2.getSlot());
        mSnapshot = snapshot;
    }

    /**
     * Linear scan of at most {@link #MAX_MICS} items: no Integer boxing per packet.
     */
    private MicSource findSource(int sessionId) {
        for (MicSource source : mSnapshot) {
            if (source.getId() == sessionId) {
                return source;
            }
        }

        return null;
    }

    private static DatagramSocket bind(int port) throws SocketException {
        try {
            return new DatagramSocket(port);
        } catch (SocketException e) {
            Log.e(TAG, "Port %s is busy, using a random one", port);
            return new DatagramSocket(0);
        }
    }

    private void receiveLoop() {
        // The mixer pulls from the jitter buffers, so receiving needs low latency, not the highest priority
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

        DatagramSocket socket = mSocket;
        byte[] data = new byte[KaraokeProtocol.MAX_PACKET_SIZE];
        DatagramPacket packet = new DatagramPacket(data, data.length);
        ByteBuffer buffer = ByteBuffer.wrap(data);
        KaraokeProtocol.Header header = new KaraokeProtocol.Header();
        long lastCheckMs = 0;

        while (mIsRunning) {
            try {
                packet.setLength(data.length);
                socket.receive(packet);

                buffer.clear();
                buffer.limit(packet.getLength());

                if (KaraokeProtocol.readHeader(buffer, header)) {
                    handlePacket(header, buffer, packet.getAddress(), packet.getPort());
                }
            } catch (SocketTimeoutException e) {
                // Time to look for silent mics
            } catch (IOException e) {
                if (mIsRunning) {
                    Log.e(TAG, "Karaoke receive error: %s", e.getMessage());
                }
                if (socket.isClosed()) {
                    break;
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Malformed karaoke packet: %s", e.getMessage());
            }

            long nowMs = SystemClock.elapsedRealtime();
            if (nowMs - lastCheckMs > SOCKET_TIMEOUT_MS) {
                lastCheckMs = nowMs;
                dropSilentMics(nowMs);
            }
        }
    }

    private void handlePacket(KaraokeProtocol.Header header, ByteBuffer payload, InetAddress host, int port) {
        if (header.sessionId == MicSource.LOCAL_ID) {
            return;
        }

        switch (header.type) {
            case KaraokeProtocol.TYPE_AUDIO:
                onAudio(header, payload, host, port);
                break;
            case KaraokeProtocol.TYPE_HELLO:
                onHello(header, payload, host, port);
                break;
            case KaraokeProtocol.TYPE_BYE:
                onBye(header, host, port);
                break;
        }
    }

    private void onAudio(KaraokeProtocol.Header header, ByteBuffer payload, InetAddress host, int port) {
        MicSource source = findSource(header.sessionId);

        if (source == null || !source.isFrom(host, port)) {
            return; // not introduced yet (next HELLO registers it) or not from the mic's phone
        }

        source.touch();
        source.setMuted((header.flags & KaraokeProtocol.FLAG_MUTED) != 0);

        int sampleCount = KaraokeProtocol.prepareAudioPayload(payload);

        if (sampleCount > 0) {
            source.getBuffer().writePacket(header.seq, payload, sampleCount);
        }
    }

    private void onHello(KaraokeProtocol.Header header, ByteBuffer payload, InetAddress host, int port) {
        KaraokeProtocol.Hello hello = KaraokeProtocol.readHello(payload);

        if (hello == null) {
            return;
        }

        int sessionId = header.sessionId;
        MicSource source = findSource(sessionId);

        if (source != null && !source.isFrom(host, port)) {
            return; // session id reused from another host. A mic that really moved re-joins after the timeout.
        }

        int status = KaraokeProtocol.STATUS_OK;
        long challenge = 0; // only new mics need one

        if (source == null) {
            if (mPinChecker.isPinRequired()) {
                challenge = KaraokePairing.challenge(mChallengeSecret, sessionId, host.getAddress());
            }

            status = mPinChecker.check(challenge, sessionId, hello, host.getHostAddress(), SystemClock.elapsedRealtime());

            if (status == KaraokeProtocol.STATUS_OK) {
                source = addMic(sessionId, hello, host, port);
                status = source != null ? KaraokeProtocol.STATUS_OK : rejectionStatus(hello);
            }

            if (mRejected.size() > MAX_REMEMBERED_SESSIONS) {
                mRejected.clear();
            }

            // Once per mic (HELLO repeats every second). Asking for the PIN is a normal step, and a phone that still has
            // the old PIN after New PIN isn't guessing.
            boolean isExpected = status == KaraokeProtocol.STATUS_PIN_REQUIRED ||
                    (status == KaraokeProtocol.STATUS_PIN_WRONG && mPinChecker.isOldPin(challenge, sessionId, hello));
            if (status != KaraokeProtocol.STATUS_OK && !isExpected && mRejected.add(sessionId)) {
                mCallback.onMicRejected(hello.name, status);
            }
        } else {
            source.touch();
            source.setName(hello.name);
        }

        sendWelcome(host, port, sessionId, header.timeMs, status, source != null ? source.getSlot() : 0, challenge);
    }

    private void onBye(KaraokeProtocol.Header header, InetAddress host, int port) {
        MicSource source = findSource(header.sessionId);

        if (source != null && source.isFrom(host, port) && mSources.remove(header.sessionId) != null) {
            updateSnapshot();
            mCallback.onMicDisconnected(source, true);
        }
    }

    /**
     * @return null if the mic can't join, see {@link #rejectionStatus}
     */
    private MicSource addMic(int sessionId, KaraokeProtocol.Hello hello, InetAddress host, int port) {
        int slot = findFreeSlot();

        if (rejectionStatus(hello) != KaraokeProtocol.STATUS_OK) {
            return null;
        }

        String name = hello.name != null && !hello.name.isEmpty() ? hello.name : "Mic " + slot;
        MicSource source = new MicSource(sessionId, slot, name, mLatencyMs);
        source.setPeer(host, port);
        mSources.put(sessionId, source);
        updateSnapshot();
        mRejected.remove(sessionId);
        mCallback.onMicConnected(source);

        return source;
    }

    private int rejectionStatus(KaraokeProtocol.Hello hello) {
        if (hello.sampleRate != KaraokeProtocol.SAMPLE_RATE || hello.channels != KaraokeProtocol.CHANNELS) {
            return KaraokeProtocol.STATUS_UNSUPPORTED;
        }

        return findFreeSlot() == -1 ? KaraokeProtocol.STATUS_FULL : KaraokeProtocol.STATUS_OK;
    }

    private void sendWelcome(InetAddress host, int port, int sessionId, int echoTimeMs, int status, int slot, long challenge) {
        try {
            mSendBuffer.clear();
            KaraokeProtocol.writeWelcome(mSendBuffer, sessionId, mSendSeq++, (int) SystemClock.elapsedRealtime(),
                    echoTimeMs, status, slot, mTvName, challenge, mTvId);
            mSendPacket.setData(mSendBuffer.array(), 0, mSendBuffer.position());
            mSendPacket.setAddress(host);
            mSendPacket.setPort(port);
            mSocket.send(mSendPacket);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Can't answer karaoke mic: %s", e.getMessage());
        }
    }

    private void dropSilentMics(long nowMs) {
        boolean changed = false;

        for (MicSource source : mSnapshot) {
            if (nowMs - source.getLastSeenMs() > KaraokeProtocol.TIMEOUT_MS && mSources.remove(source.getId()) != null) {
                changed = true;
                mCallback.onMicDisconnected(source, true);
            }
        }

        if (changed) {
            updateSnapshot();
        }
    }

    private int findFreeSlot() {
        for (int slot = 1; slot <= MAX_MICS; slot++) {
            boolean taken = false;

            for (MicSource source : mSnapshot) {
                if (source.getSlot() == slot) {
                    taken = true;
                    break;
                }
            }

            if (!taken) {
                return slot;
            }
        }

        return -1;
    }

    private void registerService(int port) {
        try {
            mNsdManager = (NsdManager) mContext.getSystemService(Context.NSD_SERVICE);

            if (mNsdManager == null) {
                return;
            }

            NsdServiceInfo serviceInfo = new NsdServiceInfo();
            serviceInfo.setServiceName(mTvName);
            serviceInfo.setServiceType(KaraokeProtocol.SERVICE_TYPE);
            serviceInfo.setPort(port);

            mRegistrationListener = new NsdManager.RegistrationListener() {
                @Override
                public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.e(TAG, "Karaoke NSD registration failed: %s", errorCode);
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.e(TAG, "Karaoke NSD unregistration failed: %s", errorCode);
                }

                @Override
                public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                    Log.d(TAG, "Karaoke NSD service registered: %s", serviceInfo.getServiceName());
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                    Log.d(TAG, "Karaoke NSD service unregistered");
                }
            };

            mNsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, mRegistrationListener);
        } catch (RuntimeException e) { // some TV firmwares have broken NSD, phones can still connect by address
            Log.e(TAG, "Karaoke NSD isn't available: %s", e.getMessage());
            mRegistrationListener = null;
        }
    }

    private void unregisterService() {
        if (mNsdManager == null || mRegistrationListener == null) {
            return;
        }

        try {
            mNsdManager.unregisterService(mRegistrationListener);
        } catch (RuntimeException e) {
            Log.e(TAG, "Karaoke NSD unregister error: %s", e.getMessage());
        }

        mRegistrationListener = null;
    }
}
