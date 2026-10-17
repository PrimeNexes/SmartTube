package com.liskovsoft.smartyoutubetv2.common.karaoke;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Process;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokePairing;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokeProtocol;
import com.liskovsoft.smartyoutubetv2.karaoke.core.PairingGuard;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives phone mics over UDP (see {@link KaraokeProtocol}) and advertises itself on the LAN via NSD (mDNS),
 * so the phone app finds the TV without typing an address.
 */
final class KaraokeServer {
    private static final String TAG = KaraokeServer.class.getSimpleName();
    static final int MAX_MICS = 4;
    private static final int SOCKET_TIMEOUT_MS = 500;
    private static final int RECEIVE_BUFFER_BYTES = 256 * 1024;
    private final Context mContext;
    private final String mTvName;
    private final Callback mCallback;
    private final Map<Integer, MicSource> mSources = new ConcurrentHashMap<>();
    private final Set<Integer> mRejected = new HashSet<>();
    private final ByteBuffer mSendBuffer = ByteBuffer.allocate(KaraokeProtocol.MAX_PACKET_SIZE);
    private final PairingGuard mPairingGuard = new PairingGuard();
    private final byte[] mChallengeSecret = new byte[16];
    private volatile String mPin;
    private volatile int mLatencyMs;
    private volatile boolean mIsRunning;
    private DatagramSocket mSocket;
    private Thread mThread;
    private NsdManager mNsdManager;
    private NsdManager.RegistrationListener mRegistrationListener;
    private int mSendSeq;

    interface Callback {
        void onMicConnected(MicSource source);
        void onMicDisconnected(MicSource source);
        void onMicRejected(String name, int status);
    }

    KaraokeServer(Context context, String tvName, int latencyMs, Callback callback) {
        mContext = context;
        mTvName = tvName;
        mLatencyMs = latencyMs;
        mCallback = callback;
        new SecureRandom().nextBytes(mChallengeSecret);
    }

    /**
     * @param pin null lets any mic on the network join. A new PIN drops the mics paired with the old one.
     */
    void setPin(String pin) {
        String oldPin = mPin;
        mPin = pin;
        mPairingGuard.reset();

        if (pin != null && !pin.equals(oldPin)) {
            Iterator<MicSource> iterator = mSources.values().iterator();
            while (iterator.hasNext()) {
                MicSource source = iterator.next();
                iterator.remove();
                mCallback.onMicDisconnected(source);
            }
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
    }

    int getPort() {
        DatagramSocket socket = mSocket;
        return socket != null ? socket.getLocalPort() : -1;
    }

    void setLatencyMs(int latencyMs) {
        mLatencyMs = latencyMs;

        for (MicSource source : mSources.values()) {
            source.setTargetLatencyMs(latencyMs);
        }
    }

    /**
     * Mixer thread: copies currently connected mics into the array.
     */
    int getSources(MicSource[] out, int offset) {
        int count = offset;

        for (MicSource source : mSources.values()) {
            if (count == out.length) {
                break;
            }
            out[count++] = source;
        }

        return count - offset;
    }

    List<MicSource> getSources() {
        List<MicSource> result = new ArrayList<>(mSources.values());
        Collections.sort(result, (o1, o2) -> o1.getSlot() - o2.getSlot());
        return result;
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
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

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
                    handlePacket(header, buffer, packet.getSocketAddress());
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

            long nowMs = System.currentTimeMillis();
            if (nowMs - lastCheckMs > SOCKET_TIMEOUT_MS) {
                lastCheckMs = nowMs;
                dropSilentMics(nowMs);
            }
        }
    }

    private void handlePacket(KaraokeProtocol.Header header, ByteBuffer payload, SocketAddress address) {
        if (header.sessionId == MicSource.LOCAL_ID) {
            return;
        }

        switch (header.type) {
            case KaraokeProtocol.TYPE_HELLO:
                onHello(header, payload, address);
                break;
            case KaraokeProtocol.TYPE_AUDIO:
                onAudio(header, payload, address);
                break;
            case KaraokeProtocol.TYPE_BYE:
                MicSource source = mSources.get(header.sessionId);
                if (source != null && address.equals(source.getAddress()) && mSources.remove(header.sessionId) != null) {
                    mCallback.onMicDisconnected(source);
                }
                break;
        }
    }

    private void onHello(KaraokeProtocol.Header header, ByteBuffer payload, SocketAddress address) {
        KaraokeProtocol.Hello hello = KaraokeProtocol.readHello(payload);

        if (hello == null) {
            return;
        }

        MicSource source = mSources.get(header.sessionId);
        String pin = mPin;
        long challenge = pin != null ? KaraokePairing.challenge(mChallengeSecret, header.sessionId,
                ((InetSocketAddress) address).getAddress().getAddress()) : 0;
        int status = KaraokeProtocol.STATUS_OK;

        if (source == null && pin != null) {
            status = checkPin(pin, challenge, header.sessionId, hello.pinProof, (InetSocketAddress) address);
        }

        if (source == null && status == KaraokeProtocol.STATUS_OK) {
            int slot = findFreeSlot();

            if (hello.sampleRate != KaraokeProtocol.SAMPLE_RATE || hello.channels != KaraokeProtocol.CHANNELS) {
                status = KaraokeProtocol.STATUS_UNSUPPORTED;
            } else if (slot == -1) {
                status = KaraokeProtocol.STATUS_FULL;
            } else {
                String name = hello.name != null && !hello.name.isEmpty() ? hello.name : "Mic " + slot;
                source = new MicSource(header.sessionId, slot, name, mLatencyMs);
                source.setAddress(address);
                mSources.put(header.sessionId, source);
                mRejected.remove(header.sessionId);
                mCallback.onMicConnected(source);
            }
        }

        if (mRejected.size() > 64) {
            mRejected.clear();
        }

        // Notify once per mic (HELLO repeats every second). Asking for the PIN is a normal step, not a rejection.
        if (status != KaraokeProtocol.STATUS_OK && status != KaraokeProtocol.STATUS_PIN_REQUIRED && mRejected.add(header.sessionId)) {
            mCallback.onMicRejected(hello.name, status);
        }

        if (source != null) {
            if (!address.equals(source.getAddress())) {
                return; // session id reused from another host. A mic that really moved re-joins after the timeout.
            }
            source.touch();
            source.setName(hello.name);
        }

        sendWelcome(address, header.sessionId, header.timeMs, status, source != null ? source.getSlot() : 0, challenge);
    }

    private int checkPin(String pin, long challenge, int sessionId, byte[] proof, InetSocketAddress address) {
        String sender = address.getAddress().getHostAddress();
        long nowMs = System.currentTimeMillis();

        if (mPairingGuard.isLocked(sender, nowMs)) {
            return KaraokeProtocol.STATUS_PIN_LOCKED;
        }

        if (proof == null) {
            return KaraokeProtocol.STATUS_PIN_REQUIRED;
        }

        if (!KaraokePairing.verify(pin, challenge, sessionId, proof)) {
            mPairingGuard.onFailure(sender, nowMs);
            return KaraokeProtocol.STATUS_PIN_WRONG;
        }

        mPairingGuard.onSuccess(sender);

        return KaraokeProtocol.STATUS_OK;
    }

    private void onAudio(KaraokeProtocol.Header header, ByteBuffer payload, SocketAddress address) {
        MicSource source = mSources.get(header.sessionId);

        if (source == null || !address.equals(source.getAddress())) {
            return; // not introduced yet (next HELLO registers it) or not from the mic's host
        }

        source.touch();
        source.setMuted((header.flags & KaraokeProtocol.FLAG_MUTED) != 0);

        int sampleCount = KaraokeProtocol.prepareAudioPayload(payload);

        if (sampleCount > 0) {
            source.getBuffer().writePacket(header.seq, payload, sampleCount);
        }
    }

    private void sendWelcome(SocketAddress address, int sessionId, int echoTimeMs, int status, int slot, long challenge) {
        try {
            mSendBuffer.clear();
            KaraokeProtocol.writeWelcome(mSendBuffer, sessionId, mSendSeq++, (int) System.currentTimeMillis(),
                    echoTimeMs, status, slot, mTvName, challenge);
            mSocket.send(new DatagramPacket(mSendBuffer.array(), mSendBuffer.position(), address));
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Can't answer karaoke mic: %s", e.getMessage());
        }
    }

    private void dropSilentMics(long nowMs) {
        Iterator<MicSource> iterator = mSources.values().iterator();

        while (iterator.hasNext()) {
            MicSource source = iterator.next();
            if (nowMs - source.getLastSeenMs() > KaraokeProtocol.TIMEOUT_MS) {
                iterator.remove();
                mCallback.onMicDisconnected(source);
            }
        }
    }

    private int findFreeSlot() {
        for (int slot = 1; slot <= MAX_MICS; slot++) {
            boolean taken = false;

            for (MicSource source : mSources.values()) {
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
