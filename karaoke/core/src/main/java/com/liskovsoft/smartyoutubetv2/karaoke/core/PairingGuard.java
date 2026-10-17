package com.liskovsoft.smartyoutubetv2.karaoke.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Slows down PIN guessing: a sender that gets the PIN wrong too often is locked out for a while,
 * and too many wrong PINs overall pause new pairings for everyone. Connected mics aren't affected.
 */
public final class PairingGuard {
    public static final int MAX_FAILURES_PER_SENDER = 5;
    public static final int MAX_FAILURES_TOTAL = 20;
    public static final long LOCK_MS = 60_000;
    private final Map<String, Sender> mSenders = new HashMap<>();
    private long mWindowStartMs;
    private int mWindowFailures;
    private long mGlobalLockUntilMs;

    private static final class Sender {
        int failures;
        long lockedUntilMs;
    }

    public synchronized boolean isLocked(String sender, long nowMs) {
        if (nowMs < mGlobalLockUntilMs) {
            return true;
        }

        Sender state = mSenders.get(sender);
        return state != null && nowMs < state.lockedUntilMs;
    }

    public synchronized void onFailure(String sender, long nowMs) {
        Sender state = mSenders.get(sender);

        if (state == null) {
            cleanup(nowMs);
            state = new Sender();
            mSenders.put(sender, state);
        }

        if (++state.failures >= MAX_FAILURES_PER_SENDER) {
            state.failures = 0;
            state.lockedUntilMs = nowMs + LOCK_MS;
        }

        if (nowMs - mWindowStartMs > LOCK_MS) {
            mWindowStartMs = nowMs;
            mWindowFailures = 0;
        }

        if (++mWindowFailures >= MAX_FAILURES_TOTAL) {
            mWindowFailures = 0;
            mGlobalLockUntilMs = nowMs + LOCK_MS;
        }
    }

    public synchronized void onSuccess(String sender) {
        mSenders.remove(sender);
    }

    public synchronized void reset() {
        mSenders.clear();
        mWindowFailures = 0;
        mGlobalLockUntilMs = 0;
    }

    /**
     * Bounded memory: forget idle senders.
     */
    private void cleanup(long nowMs) {
        if (mSenders.size() < 64) {
            return;
        }

        Iterator<Sender> iterator = mSenders.values().iterator();
        while (iterator.hasNext()) {
            if (nowMs >= iterator.next().lockedUntilMs) {
                iterator.remove();
            }
        }
    }
}
