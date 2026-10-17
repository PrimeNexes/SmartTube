package com.liskovsoft.smartyoutubetv2.karaokemic;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.liskovsoft.smartyoutubetv2.karaoke.core.AutoTune;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokePairing;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokeProtocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Captures the phone mic and streams it to SmartTube over UDP (see {@link KaraokeProtocol}).<br/>
 * Control thread: says HELLO every second, listens for the TV's WELCOME (status, round trip time).<br/>
 * Capture thread: reads 5 ms frames, auto-tunes them if asked, and sends each one as a datagram right away.
 */
final class MicStreamer {
    private static final String TAG = MicStreamer.class.getSimpleName();
    static final int STATUS_CONNECTING = 0;
    static final int STATUS_CONNECTED = 1;
    static final int STATUS_NO_ANSWER = 2;
    static final int STATUS_FULL = 3;
    static final int STATUS_UNSUPPORTED = 4;
    static final int STATUS_MIC_ERROR = 5;
    static final int STATUS_NETWORK_ERROR = 6;
    static final int STATUS_NEEDS_PIN = 7;
    static final int STATUS_WRONG_PIN = 8;
    static final int STATUS_PIN_LOCKED = 9;
    private static final int FRAME = KaraokeProtocol.FRAME_SAMPLES;
    private static final int RECEIVE_TIMEOUT_MS = 250;
    private static final int ANSWER_TIMEOUT_MS = 3_000;
    private final String mHost;
    private final int mPort;
    private final String mSingerName;
    private final PinStore mPinStore;
    private final int mSessionId;
    private volatile boolean mIsRunning;
    private volatile int mStatus = STATUS_CONNECTING;
    private volatile String mTvName;
    private volatile int mSlot;
    private volatile int mRttMs = -1;
    private volatile float mLevel;
    private volatile float mGain = 1;
    private volatile boolean mIsMuted;
    private volatile int mAutoTuneMode = AutoTune.MODE_OFF;
    private volatile int mAutoTuneKey = AutoTune.KEY_CHROMATIC;
    private volatile float mSungNote = -1;
    private volatile int mTargetNote = -1;
    private volatile DatagramSocket mSocket;
    private volatile InetSocketAddress mTarget;
    private volatile String mPin;
    private volatile boolean mIsPinRejected;
    private volatile boolean mIsHelloNeeded;
    /** The TV accepted this mic, audio is worth sending. */
    private volatile boolean mIsPaired;
    /** Set when the TV turned down a PIN that worked before: the TV has a new PIN. */
    private volatile boolean mIsPinChanged;
    private long mChallenge;
    private boolean mHasChallenge;
    /** PIN the TV accepted before or the one saved for it (control thread). */
    private String mKnownPin;

    /**
     * PINs the user entered, per TV. Keyed by the TV's random id, not its name: TVs can share a name,
     * and a stranger can't get a proof of the PIN just by calling itself like the TV.
     */
    interface PinStore {
        String getPin(long tvId);
        void savePin(long tvId, String pin);
        void clearPin(long tvId);
    }

    MicStreamer(String host, int port, String singerName, PinStore pinStore) {
        mHost = host;
        mPort = port;
        mSingerName = singerName;
        mPinStore = pinStore;
        int sessionId;
        do {
            sessionId = new Random().nextInt();
        } while (sessionId == 0); // 0 is reserved for the TV's own mic
        mSessionId = sessionId;
    }

    void start() {
        mIsRunning = true;
        new Thread(this::controlLoop, "MicControl").start();
    }

    /**
     * Non blocking. Threads say BYE and release the mic on their own.
     */
    void stop() {
        mIsRunning = false;
    }

    boolean isRunning() {
        return mIsRunning;
    }

    int getStatus() {
        return mStatus;
    }

    String getTvName() {
        return mTvName;
    }

    int getSlot() {
        return mSlot;
    }

    int getRttMs() {
        return mRttMs;
    }

    /**
     * With {@link #STATUS_WRONG_PIN}: the rejected PIN wasn't a typo, it worked before.
     */
    boolean isPinChanged() {
        return mIsPinChanged;
    }

    /**
     * Peak of the last frame sent, 0..1.
     */
    float getLevel() {
        return mLevel;
    }

    void setGain(float gain) {
        mGain = gain;
    }

    void setMuted(boolean muted) {
        mIsMuted = muted;
    }

    /**
     * @param mode {@link AutoTune} mode, applied from the next frame
     * @param key {@link AutoTune} key
     */
    void setAutoTune(int mode, int key) {
        mAutoTuneMode = mode;
        mAutoTuneKey = key;
    }

    /**
     * What auto-tune hears, as a MIDI note (fractional), -1 when off or not singing.
     */
    float getSungNote() {
        return mSungNote;
    }

    /**
     * The note auto-tune pulls the voice to (MIDI), -1 when none.
     */
    int getTargetNote() {
        return mTargetNote;
    }

    /**
     * PIN typed by the user, tried right away.
     */
    void setPin(String pin) {
        mPin = pin;
        mIsPinRejected = false;
        mStatus = STATUS_CONNECTING;
        mIsHelloNeeded = true;
    }

    private void controlLoop() {
        DatagramSocket socket = null;

        try {
            mTarget = new InetSocketAddress(InetAddress.getByName(mHost), mPort);
            socket = new DatagramSocket();
            socket.setSoTimeout(RECEIVE_TIMEOUT_MS);
            mSocket = socket;
        } catch (IOException | IllegalArgumentException e) { // unknown host, bad port
            Log.e(TAG, "Can't reach the TV: " + e.getMessage());
            if (socket != null) {
                socket.close();
            }
            mStatus = STATUS_NETWORK_ERROR;
            mIsRunning = false;
            return;
        }

        new Thread(this::captureLoop, "MicCapture").start();

        byte[] receiveData = new byte[KaraokeProtocol.MAX_PACKET_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
        ByteBuffer receiveBuffer = ByteBuffer.wrap(receiveData);
        ByteBuffer sendBuffer = ByteBuffer.allocate(KaraokeProtocol.MAX_PACKET_SIZE);
        KaraokeProtocol.Header header = new KaraokeProtocol.Header();
        long startMs = SystemClock.elapsedRealtime();
        long lastHelloMs = 0;
        long lastAnswerMs = 0;
        int seq = 0;

        while (mIsRunning) {
            long nowMs = SystemClock.elapsedRealtime();

            if (mIsHelloNeeded || nowMs - lastHelloMs >= KaraokeProtocol.HELLO_INTERVAL_MS) {
                mIsHelloNeeded = false;
                lastHelloMs = nowMs;
                String pin = mPin;
                byte[] proof = pin != null && mHasChallenge ? KaraokePairing.proof(pin, mChallenge, mSessionId) : null;
                sendBuffer.clear();
                KaraokeProtocol.writeHello(sendBuffer, mSessionId, seq++, (int) nowMs, mSingerName, proof, mChallenge);
                send(socket, sendBuffer);
            }

            try {
                receivePacket.setLength(receiveData.length);
                socket.receive(receivePacket);
                receiveBuffer.clear();
                receiveBuffer.limit(receivePacket.getLength());

                // The TV echoes our session id, ignore anything else on the port
                if (KaraokeProtocol.readHeader(receiveBuffer, header) && header.type == KaraokeProtocol.TYPE_WELCOME &&
                        header.sessionId == mSessionId) {
                    KaraokeProtocol.Welcome welcome = KaraokeProtocol.readWelcome(receiveBuffer);
                    if (welcome != null && mStatus != STATUS_MIC_ERROR) {
                        lastAnswerMs = SystemClock.elapsedRealtime();
                        mRttMs = (int) lastAnswerMs - welcome.echoTimeMs;
                        mTvName = welcome.tvName;
                        mSlot = welcome.slot;
                        mChallenge = welcome.challenge;
                        mHasChallenge = true;
                        onWelcome(welcome);
                    }
                }
            } catch (SocketTimeoutException e) {
                // Time for the next HELLO
            } catch (IOException | RuntimeException e) {
                if (mIsRunning) {
                    Log.e(TAG, "Receive error: " + e.getMessage());
                }
            }

            nowMs = SystemClock.elapsedRealtime();
            boolean silentTv = lastAnswerMs == 0 ? nowMs - startMs > ANSWER_TIMEOUT_MS : nowMs - lastAnswerMs > ANSWER_TIMEOUT_MS;
            if (silentTv && mStatus != STATUS_MIC_ERROR) {
                mStatus = STATUS_NO_ANSWER;
                mIsPaired = false; // stop sending audio into the void (battery, Wi-Fi airtime), resume on the next answer
            }
        }

        sendBuffer.clear();
        KaraokeProtocol.writeBye(sendBuffer, mSessionId, seq, (int) SystemClock.elapsedRealtime());
        send(socket, sendBuffer);
        socket.close();
    }

    private void onWelcome(KaraokeProtocol.Welcome welcome) {
        mIsPaired = welcome.status == KaraokeProtocol.STATUS_OK;

        String pin = mPin; // the UI thread may set a typed one any time

        switch (welcome.status) {
            case KaraokeProtocol.STATUS_OK:
                mStatus = STATUS_CONNECTED;
                if (pin != null && !pin.equals(mKnownPin)) {
                    mPinStore.savePin(welcome.tvId, pin);
                    mKnownPin = pin;
                }
                break;
            case KaraokeProtocol.STATUS_PIN_REQUIRED:
                if (pin == null && !mIsPinRejected) {
                    String saved = mPinStore.getPin(welcome.tvId); // paired before
                    if (saved != null) {
                        mPin = pin = mKnownPin = saved;
                    }
                }
                if (pin != null) {
                    mIsHelloNeeded = true; // now with the proof, the challenge is known
                    mStatus = STATUS_CONNECTING;
                } else {
                    mStatus = mIsPinRejected ? STATUS_WRONG_PIN : STATUS_NEEDS_PIN;
                }
                break;
            case KaraokeProtocol.STATUS_PIN_WRONG:
                mIsPinChanged = pin != null && pin.equals(mKnownPin); // New PIN on the TV
                mPinStore.clearPin(welcome.tvId);
                mPin = null;
                mKnownPin = null;
                mIsPinRejected = true;
                mStatus = STATUS_WRONG_PIN;
                break;
            case KaraokeProtocol.STATUS_PIN_LOCKED:
                mStatus = STATUS_PIN_LOCKED;
                break;
            case KaraokeProtocol.STATUS_FULL:
                mStatus = STATUS_FULL;
                break;
            default:
                mStatus = STATUS_UNSUPPORTED;
                break;
        }
    }

    private void captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        AudioRecord record = openRecord();

        if (record == null) {
            mStatus = STATUS_MIC_ERROR;
            mIsRunning = false;
            return;
        }

        short[] frame = new short[FRAME];
        float[] voice = new float[FRAME];
        AutoTune autoTune = new AutoTune(KaraokeProtocol.SAMPLE_RATE, FRAME);
        ByteBuffer packet = ByteBuffer.allocate(KaraokeProtocol.MAX_PACKET_SIZE);
        // Reused for every frame: no garbage in the audio loop
        DatagramPacket datagram = new DatagramPacket(packet.array(), packet.capacity(), mTarget);
        DatagramSocket socket = mSocket;
        int seq = 0;

        try {
            while (mIsRunning) {
                int read = record.read(frame, 0, FRAME);

                if (read < 0) {
                    Log.e(TAG, "Mic read error: " + read);
                    mStatus = STATUS_MIC_ERROR;
                    mIsRunning = false; // ends the control loop too, UI then stops the service
                    break;
                }

                // On the raw mic, so the mic level doesn't change what counts as silence. Off: untouched.
                autoTune.setMode(mAutoTuneMode);
                autoTune.setKey(mAutoTuneKey);
                for (int i = 0; i < read; i++) {
                    voice[i] = frame[i] * (1f / 32768);
                }
                autoTune.process(voice, read);
                mSungNote = autoTune.getNote();
                mTargetNote = autoTune.getTargetNote();

                boolean muted = mIsMuted;
                float gain = (muted ? 0 : mGain) * 32768;
                int peak = 0;

                for (int i = 0; i < read; i++) {
                    int sample = Math.round(voice[i] * gain);
                    sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
                    frame[i] = (short) sample;
                    peak = Math.max(peak, Math.abs(sample));
                }

                mLevel = peak / 32768f;

                if (read == 0 || !mIsPaired) {
                    continue; // the TV won't take it anyway
                }

                packet.clear();
                KaraokeProtocol.writeAudio(packet, mSessionId, seq++, (int) SystemClock.elapsedRealtime(),
                        muted ? KaraokeProtocol.FLAG_MUTED : 0, frame, read);
                datagram.setLength(packet.position());
                send(socket, datagram);
            }
        } finally {
            try {
                record.stop();
            } catch (IllegalStateException e) {
                // Not recording
            }
            record.release();
            mLevel = 0;
            mSungNote = -1;
            mTargetNote = -1;
        }
    }

    private void send(DatagramSocket socket, ByteBuffer buffer) {
        send(socket, new DatagramPacket(buffer.array(), buffer.position(), mTarget));
    }

    private void send(DatagramSocket socket, DatagramPacket datagram) {
        try {
            socket.send(datagram);
        } catch (IOException | RuntimeException e) {
            // Wi-Fi hiccup, the TV just treats it as a lost packet
            if (mIsRunning) {
                Log.w(TAG, "Send error: " + e.getMessage());
            }
        }
    }

    /**
     * Prefers the capture path designed for karaoke (low latency, no voice call processing).
     */
    @SuppressWarnings("MissingPermission")
    private static AudioRecord openRecord() {
        int rate = KaraokeProtocol.SAMPLE_RATE;
        int channelMask = AudioFormat.CHANNEL_IN_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minBufferBytes = AudioRecord.getMinBufferSize(rate, channelMask, encoding);

        if (minBufferBytes <= 0) {
            return null;
        }

        int bufferBytes = Math.max(minBufferBytes, FRAME * 2 * 4);
        int[] sources = Build.VERSION.SDK_INT >= 29 ?
                new int[] {MediaRecorder.AudioSource.VOICE_PERFORMANCE, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC} :
                new int[] {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};

        for (int source : sources) {
            AudioRecord record = null;

            try {
                record = new AudioRecord(source, rate, channelMask, encoding, bufferBytes);

                if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                    record.startRecording();
                    if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        Log.d(TAG, "Recording from source " + source);
                        return record;
                    }
                }
            } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
                Log.e(TAG, "Mic source " + source + " failed: " + e.getMessage());
            }

            if (record != null) {
                record.release();
            }
        }

        return null;
    }
}
