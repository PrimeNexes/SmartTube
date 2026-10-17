package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PairingGuardTest {
    @Test
    public void locksSenderAfterTooManyWrongPins() {
        PairingGuard guard = new PairingGuard();

        for (int i = 0; i < PairingGuard.MAX_FAILURES_PER_SENDER - 1; i++) {
            guard.onFailure("10.0.0.5", 1_000);
        }
        assertFalse(guard.isLocked("10.0.0.5", 1_000));

        guard.onFailure("10.0.0.5", 1_000);
        assertTrue(guard.isLocked("10.0.0.5", 1_000));
        assertFalse(guard.isLocked("10.0.0.6", 1_000)); // others can still pair
        assertFalse(guard.isLocked("10.0.0.5", 1_000 + PairingGuard.LOCK_MS)); // lock expires
    }

    @Test
    public void successClearsFailures() {
        PairingGuard guard = new PairingGuard();

        for (int i = 0; i < PairingGuard.MAX_FAILURES_PER_SENDER - 1; i++) {
            guard.onFailure("10.0.0.5", 0);
        }
        guard.onSuccess("10.0.0.5");
        guard.onFailure("10.0.0.5", 0);

        assertFalse(guard.isLocked("10.0.0.5", 0));
    }

    @Test
    public void manySendersTriggerGlobalPause() {
        PairingGuard guard = new PairingGuard();

        for (int i = 0; i < PairingGuard.MAX_FAILURES_TOTAL; i++) {
            guard.onFailure("10.0.1." + i, 5_000);
        }

        assertTrue(guard.isLocked("10.0.2.1", 5_000));
        assertFalse(guard.isLocked("10.0.2.1", 5_000 + PairingGuard.LOCK_MS));
    }
}
