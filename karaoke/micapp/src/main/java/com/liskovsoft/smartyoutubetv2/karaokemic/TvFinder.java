package com.liskovsoft.smartyoutubetv2.karaokemic;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokeProtocol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;

/**
 * Finds TVs with SmartTube karaoke mode on via NSD (mDNS / DNS-SD).
 */
final class TvFinder {
    private static final String TAG = TvFinder.class.getSimpleName();
    private final NsdManager mNsdManager;
    private final WifiManager.MulticastLock mMulticastLock;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Listener mListener;
    private final List<Tv> mTvs = new ArrayList<>();
    // Older Androids resolve one service at a time
    private final Queue<NsdServiceInfo> mResolveQueue = new ArrayDeque<>();
    private boolean mIsResolving;
    private NsdManager.DiscoveryListener mDiscoveryListener;

    interface Listener {
        /**
         * Main thread.
         */
        void onTvsChanged(List<Tv> tvs);
    }

    static final class Tv {
        final String name;
        final String host;
        final int port;

        Tv(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }

        String getAddress() {
            return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
        }

        @Override
        public String toString() {
            return name + "\n" + getAddress();
        }
    }

    TvFinder(Context context, Listener listener) {
        mNsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mMulticastLock = wifiManager != null ? wifiManager.createMulticastLock("SmartTubeMic:nsd") : null;
        mListener = listener;
    }

    void start() {
        if (mDiscoveryListener != null || mNsdManager == null) {
            return;
        }

        if (mMulticastLock != null) {
            mMulticastLock.setReferenceCounted(false);
            mMulticastLock.acquire();
        }

        mDiscoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Stop discovery failed: " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Looking for TVs");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "Discovery stopped");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                mHandler.post(() -> resolve(serviceInfo));
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                mHandler.post(() -> remove(serviceInfo.getServiceName()));
            }
        };

        try {
            mNsdManager.discoverServices(KaraokeProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);
        } catch (RuntimeException e) {
            Log.e(TAG, "NSD isn't available: " + e.getMessage());
            mDiscoveryListener = null;
        }
    }

    void stop() {
        if (mDiscoveryListener != null) {
            try {
                mNsdManager.stopServiceDiscovery(mDiscoveryListener);
            } catch (RuntimeException e) {
                Log.e(TAG, "Stop discovery error: " + e.getMessage());
            }
            mDiscoveryListener = null;
        }

        if (mMulticastLock != null && mMulticastLock.isHeld()) {
            mMulticastLock.release();
        }

        mResolveQueue.clear();
        mIsResolving = false;
        mTvs.clear();
    }

    private void resolve(NsdServiceInfo serviceInfo) {
        if (mDiscoveryListener == null) {
            return;
        }

        mResolveQueue.add(serviceInfo);
        resolveNext();
    }

    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (mIsResolving || mResolveQueue.isEmpty()) {
            return;
        }

        mIsResolving = true;
        NsdServiceInfo next = mResolveQueue.poll();

        try {
            mNsdManager.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.e(TAG, "Resolve failed: " + errorCode);
                    mHandler.post(() -> {
                        mIsResolving = false;
                        resolveNext();
                    });
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    mHandler.post(() -> {
                        mIsResolving = false;
                        if (serviceInfo.getHost() != null && mDiscoveryListener != null) {
                            add(new Tv(serviceInfo.getServiceName(), serviceInfo.getHost().getHostAddress(), serviceInfo.getPort()));
                        }
                        resolveNext();
                    });
                }
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "Resolve error: " + e.getMessage());
            mIsResolving = false;
        }
    }

    private void add(Tv tv) {
        remove(tv.name);
        mTvs.add(tv);
        mListener.onTvsChanged(new ArrayList<>(mTvs));
    }

    private void remove(String name) {
        Iterator<Tv> iterator = mTvs.iterator();
        boolean changed = false;

        while (iterator.hasNext()) {
            if (iterator.next().name.equals(name)) {
                iterator.remove();
                changed = true;
            }
        }

        if (changed) {
            mListener.onTvsChanged(new ArrayList<>(mTvs));
        }
    }
}
