package com.liskovsoft.smartyoutubetv2.common.karaoke;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.helpers.PermissionHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.KaraokeData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokeProtocol;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Karaoke mode: phones (or a TV mic) stream the singer's voice, the TV mixes it on top of the video sound.<br/>
 * Stays on across videos until turned off, so mics don't have to reconnect between songs.
 * While SmartTube is in the background the mics are silent, and the TV mic is released while voice search
 * (or another app) needs it.
 */
public class KaraokeManager {
    private static final String TAG = KaraokeManager.class.getSimpleName();
    private static final long FOREGROUND_CHECK_MS = 1_000;
    /** A busy network can't fill the screen with "can't connect" messages. */
    private static final long REJECTION_MESSAGE_INTERVAL_MS = 5_000;
    @SuppressLint("StaticFieldLeak")
    private static KaraokeManager sInstance;
    private final Context mContext;
    private final KaraokeData mKaraokeData;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final String mTvName;
    private KaraokeServer mServer;
    private KaraokeMixer mMixer;
    private LocalMicCapture mLocalCapture;
    private volatile MicSource mLocalSource;
    private volatile boolean mIsRunning;
    /** The TV mic didn't work this session, don't retry until the user turns it on again. */
    private boolean mIsLocalMicFailed;
    private long mLastRejectionMessageMs;

    public interface Listener {
        /**
         * Karaoke was started/stopped or a mic connected/disconnected. Main thread.
         */
        void onKaraokeChanged();
    }

    private final KaraokeServer.Callback mServerCallback = new KaraokeServer.Callback() {
        @Override
        public void onMicConnected(MicSource source) {
            KaraokeMixer mixer = mMixer;
            if (mixer != null) {
                mixer.wakeUp();
            }
            mHandler.post(() -> {
                MessageHelpers.showMessage(mContext, R.string.karaoke_mic_connected, source.getSlot(), source.getName());
                notifyChanged();
            });
        }

        @Override
        public void onMicDisconnected(MicSource source, boolean announce) {
            mHandler.post(() -> {
                if (mIsRunning && announce) {
                    MessageHelpers.showMessage(mContext, R.string.karaoke_mic_disconnected, source.getName());
                }
                notifyChanged();
            });
        }

        @Override
        public void onMicRejected(String name, int status) {
            int message;

            switch (status) {
                case KaraokeProtocol.STATUS_FULL:
                    message = R.string.karaoke_mic_rejected_full;
                    break;
                case KaraokeProtocol.STATUS_PIN_WRONG:
                    message = R.string.karaoke_mic_rejected_pin;
                    break;
                case KaraokeProtocol.STATUS_PIN_LOCKED:
                    message = R.string.karaoke_mic_rejected_locked;
                    break;
                default:
                    message = R.string.karaoke_mic_rejected_unsupported;
                    break;
            }

            mHandler.post(() -> {
                long nowMs = SystemClock.elapsedRealtime();
                if (nowMs - mLastRejectionMessageMs > REJECTION_MESSAGE_INTERVAL_MS) {
                    mLastRejectionMessageMs = nowMs;
                    MessageHelpers.showMessage(mContext, message, name);
                }
            });
        }
    };

    private final Runnable mForegroundCheck = new Runnable() {
        @Override
        public void run() {
            if (mIsRunning) {
                updateForegroundState();
                mHandler.postDelayed(this, FOREGROUND_CHECK_MS);
            }
        }
    };

    private KaraokeManager(Context context) {
        mContext = context;
        mKaraokeData = KaraokeData.instance(context);
        mTvName = readDeviceName(context);
    }

    public static KaraokeManager instance(Context context) {
        if (sInstance == null) {
            sInstance = new KaraokeManager(context.getApplicationContext());
        }

        return sInstance;
    }

    /**
     * Opens the mic port, advertises the TV on the LAN and starts the mixer.
     * @return false if the network isn't available
     */
    public synchronized boolean start() {
        if (mIsRunning) {
            return true;
        }

        KaraokeServer server = new KaraokeServer(mContext, mTvName, mKaraokeData.getTvId(), mKaraokeData.getLatencyMs(), mServerCallback);

        server.setPin(mKaraokeData.isPinRequired() ? mKaraokeData.getPin() : null);

        if (!server.start(mKaraokeData.getPort())) {
            return false;
        }

        mServer = server;
        mMixer = new KaraokeMixer(out -> {
            int count = server.getSources(out, 0);
            MicSource localSource = mLocalSource;
            if (localSource != null && count < out.length) {
                out[count++] = localSource;
            }
            return count;
        }, getFramesPerBurst(), () -> mHandler.post(() -> { // don't pretend karaoke works when nothing can be heard
            if (mIsRunning) {
                stop();
                MessageHelpers.showLongMessage(mContext, R.string.karaoke_audio_error);
            }
        }));
        mIsRunning = true;
        mIsLocalMicFailed = false;
        applySettings();
        mMixer.start();

        updateForegroundState(); // also opens the TV mic if it's on
        mHandler.postDelayed(mForegroundCheck, FOREGROUND_CHECK_MS);

        notifyChanged();

        return true;
    }

    public synchronized void stop() {
        if (!mIsRunning) {
            return;
        }

        mIsRunning = false;
        mHandler.removeCallbacks(mForegroundCheck);

        // Music processing is a part of the karaoke session only
        VocalRemoverAudioProcessor.setStrength(0);

        stopLocalMic();
        mMixer.stop();
        mMixer = null;
        mServer.stop();
        mServer = null;

        notifyChanged();
    }

    public boolean isRunning() {
        return mIsRunning;
    }

    /**
     * Someone is singing right now. Any thread.
     */
    public boolean isVoiceActive() {
        KaraokeMixer mixer = mMixer;
        return mixer != null && mixer.isVoiceActive();
    }

    /**
     * Pushes {@link KaraokeData} values to the running session.
     */
    public synchronized void applySettings() {
        if (!mIsRunning) {
            return;
        }

        mMixer.setVolume(mKaraokeData.getMicVolumePercent() / 100f);
        mMixer.setVoiceEffect(mKaraokeData.getVoiceEffect());
        mMixer.setNoiseGateEnabled(mKaraokeData.isNoiseGateEnabled());
        mMixer.setAutoLevelEnabled(mKaraokeData.isAutoLevelEnabled());
        mServer.setLatencyMs(mKaraokeData.getLatencyMs());
        VocalRemoverAudioProcessor.setStrength(mKaraokeData.getVocalRemoverPercent() / 100f);
    }

    /**
     * Use a mic attached to the TV. RECORD_AUDIO permission should be granted before.
     * @return false if this device can't record at all (a mic that fails to open later is reported by a message)
     */
    public synchronized boolean setLocalMicEnabled(boolean enabled) {
        mKaraokeData.setLocalMicEnabled(enabled);
        mIsLocalMicFailed = false;

        if (!mIsRunning) {
            return true;
        }

        boolean result = true;

        if (enabled) {
            result = startLocalMic();
        } else {
            stopLocalMic();
        }

        notifyChanged();

        return result;
    }

    public String getTvName() {
        return mTvName;
    }

    /**
     * @return null when any phone on the network may connect
     */
    public String getPin() {
        return mKaraokeData.isPinRequired() ? mKaraokeData.getPin() : null;
    }

    public synchronized void setPinRequired(boolean required) {
        mKaraokeData.setPinRequired(required);

        if (mServer != null) {
            mServer.setPin(getPin()); // turning it on drops mics that joined without a PIN
        }

        notifyChanged();
    }

    /**
     * New PIN, phones paired with the old one have to enter it again.
     */
    public synchronized String renewPin() {
        String pin = mKaraokeData.renewPin();

        if (mServer != null && mKaraokeData.isPinRequired()) {
            mServer.setPin(pin);
        }

        notifyChanged();

        return pin;
    }

    public int getPort() {
        KaraokeServer server = mServer;
        return server != null ? server.getPort() : mKaraokeData.getPort();
    }

    /**
     * Connected mics, phones first.
     */
    public List<MicSource> getMics() {
        List<MicSource> result = new ArrayList<>();
        KaraokeServer server = mServer;

        if (server != null) {
            result.addAll(server.getSources());
        }

        MicSource localSource = mLocalSource;
        if (localSource != null) {
            result.add(localSource);
        }

        return result;
    }

    /**
     * "192.168.1.20:47900" style addresses the phone can connect to.
     */
    public List<String> getAddresses() {
        List<String> result = new ArrayList<>();
        int port = getPort();

        for (String ip : getLocalIpAddresses()) {
            result.add(ip + ":" + port);
        }

        return result;
    }

    public void addListener(Listener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    /**
     * Silences the mics while SmartTube is in the background and leaves the TV mic to voice search.
     */
    private synchronized void updateForegroundState() {
        if (!mIsRunning) {
            return;
        }

        boolean isAppVisible = Utils.isAppInForegroundFixed();
        mMixer.setSuspended(!isAppVisible);

        boolean isMicAllowed = isAppVisible && ViewManager.instance(mContext).getTopView() != SearchView.class;

        if (isMicAllowed && mKaraokeData.isLocalMicEnabled() && !mIsLocalMicFailed) {
            startLocalMic();
        } else if (!isMicAllowed) {
            stopLocalMic();
        }
    }

    private boolean startLocalMic() {
        if (mLocalCapture != null) {
            return true;
        }

        if (!PermissionHelpers.hasMicPermissions(mContext)) {
            return false; // the player asks for it, then calls setLocalMicEnabled() again
        }

        MicSource source = new MicSource(MicSource.LOCAL_ID, 0, mContext.getString(R.string.karaoke_tv_mic), 20);
        LocalMicCapture capture = new LocalMicCapture(source, failed -> mHandler.post(() -> onLocalMicFailed(failed)));

        if (!capture.start()) {
            return false;
        }

        mLocalCapture = capture;
        mLocalSource = source;
        mMixer.wakeUp();

        return true;
    }

    private synchronized void onLocalMicFailed(LocalMicCapture capture) {
        if (capture != mLocalCapture) {
            return; // already replaced or stopped
        }

        stopLocalMic();
        mIsLocalMicFailed = true;
        MessageHelpers.showLongMessage(mContext, R.string.karaoke_tv_mic_error);
        notifyChanged();
    }

    private void stopLocalMic() {
        mLocalSource = null;

        if (mLocalCapture != null) {
            mLocalCapture.stop();
            mLocalCapture = null;
        }
    }

    private void notifyChanged() {
        for (Listener listener : mListeners) {
            listener.onKaraokeChanged();
        }
    }

    /**
     * The device's audio output period, so the karaoke output buffer can start as small as possible.
     */
    private int getFramesPerBurst() {
        try {
            AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            String value = audioManager != null ? audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) : null;
            return value != null ? Integer.parseInt(value) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static List<String> getLocalIpAddresses() {
        List<String> lanAddresses = new ArrayList<>();
        List<String> otherAddresses = new ArrayList<>();

        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }

                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLinkLocalAddress()) {
                        continue;
                    }

                    (address.isSiteLocalAddress() ? lanAddresses : otherAddresses).add(address.getHostAddress());
                }
            }
        } catch (SocketException | NullPointerException e) {
            Log.e(TAG, "Can't read IP addresses: %s", e.getMessage());
        }

        return lanAddresses.isEmpty() ? otherAddresses : lanAddresses;
    }

    /**
     * Name the user gave to the TV ("Living room TV"), shown in the phone app.
     */
    private static String readDeviceName(Context context) {
        ContentResolver resolver = context.getContentResolver();
        String name = null;

        try {
            if (Build.VERSION.SDK_INT >= 25) {
                name = Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME);
            }
            if (name == null || name.isEmpty()) {
                name = Settings.Secure.getString(resolver, "bluetooth_name");
            }
        } catch (RuntimeException e) {
            // Restricted settings
        }

        if (name == null || name.isEmpty()) {
            name = Build.MODEL;
        }

        return name;
    }
}
