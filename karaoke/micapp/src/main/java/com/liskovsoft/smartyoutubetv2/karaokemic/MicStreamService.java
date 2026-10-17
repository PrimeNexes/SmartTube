package com.liskovsoft.smartyoutubetv2.karaokemic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground service that owns the {@link MicStreamer}, so singing goes on with the screen off.
 */
public class MicStreamService extends Service {
    private static final String ACTION_START = "com.liskovsoft.smartyoutubetv2.karaokemic.START";
    private static final String ACTION_STOP = "com.liskovsoft.smartyoutubetv2.karaokemic.STOP";
    private static final String EXTRA_HOST = "host";
    private static final String EXTRA_PORT = "port";
    private static final String EXTRA_TV_LABEL = "tv_label";
    private static final String EXTRA_SINGER = "singer";
    private static final String CHANNEL_ID = "mic";
    private static final int NOTIFICATION_ID = 1;
    private static final String PINS_PREFS = "tv_pins";
    private static final long MAX_SESSION_MS = 4 * 60 * 60 * 1_000L;
    private static volatile MicStreamer sStreamer;
    private static volatile float sGain = 1;
    private static volatile boolean sIsMuted;
    private WifiManager.WifiLock mWifiLock;
    private PowerManager.WakeLock mWakeLock;

    public static void start(Context context, String host, int port, String tvLabel, String singer) {
        Intent intent = new Intent(context, MicStreamService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_TV_LABEL, tvLabel)
                .putExtra(EXTRA_SINGER, singer);

        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, MicStreamService.class));
    }

    /**
     * Current session or null.
     */
    static MicStreamer getStreamer() {
        return sStreamer;
    }

    static void setGain(float gain) {
        sGain = gain;
        MicStreamer streamer = sStreamer;
        if (streamer != null) {
            streamer.setGain(gain);
        }
    }

    static void setMuted(boolean muted) {
        sIsMuted = muted;
        MicStreamer streamer = sStreamer;
        if (streamer != null) {
            streamer.setMuted(muted);
        }
    }

    static boolean isMuted() {
        return sIsMuted;
    }

    static void setPin(String pin) {
        MicStreamer streamer = sStreamer;
        if (streamer != null) {
            streamer.setPin(pin);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String host = intent.getStringExtra(EXTRA_HOST);
        String tvLabel = intent.getStringExtra(EXTRA_TV_LABEL);

        startForegroundCompat(createNotification(tvLabel));

        if (sStreamer != null) {
            sStreamer.stop();
        }

        acquireLocks();

        MicStreamer streamer = new MicStreamer(host, intent.getIntExtra(EXTRA_PORT, 0), intent.getStringExtra(EXTRA_SINGER),
                new PrefsPinStore(getSharedPreferences(PINS_PREFS, MODE_PRIVATE)));
        streamer.setGain(sGain);
        streamer.setMuted(sIsMuted);
        streamer.start();
        sStreamer = streamer;

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        MicStreamer streamer = sStreamer;
        sStreamer = null;

        if (streamer != null) {
            streamer.stop();
        }

        releaseLocks();

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Remembers the PIN of every TV the phone paired with, so it's typed once.
     */
    private static final class PrefsPinStore implements MicStreamer.PinStore {
        private final SharedPreferences mPrefs;

        PrefsPinStore(SharedPreferences prefs) {
            mPrefs = prefs;
        }

        @Override
        public String getPin(String tvName) {
            return mPrefs.getString(key(tvName), null);
        }

        @Override
        public void savePin(String tvName, String pin) {
            mPrefs.edit().putString(key(tvName), pin).apply();
        }

        @Override
        public void clearPin(String tvName) {
            mPrefs.edit().remove(key(tvName)).apply();
        }

        private static String key(String tvName) {
            return "pin_" + (tvName != null ? tvName : "");
        }
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 30) { // microphone type appeared in Android 11
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification createNotification(String tvLabel) {
        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel),
                        NotificationManager.IMPORTANCE_LOW));
            }
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }

        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), immutable);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, MicStreamService.class).setAction(ACTION_STOP), immutable);

        return builder
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text, tvLabel))
                .setContentIntent(openApp)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(0, getString(R.string.stop), stop).build())
                .build();
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        if (mWifiLock == null) {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager != null) {
                // Wi-Fi power save delays packets by up to a beacon interval (~100 ms)
                int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                mWifiLock = wifiManager.createWifiLock(mode, "SmartTubeMic:wifi");
                mWifiLock.setReferenceCounted(false);
                mWifiLock.acquire();
            }
        }

        if (mWakeLock == null) {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartTubeMic:mic");
                mWakeLock.setReferenceCounted(false);
                mWakeLock.acquire(MAX_SESSION_MS); // a forgotten mic doesn't drain the battery forever
            }
        }
    }

    private void releaseLocks() {
        if (mWifiLock != null) {
            mWifiLock.release();
            mWifiLock = null;
        }

        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }
    }
}
