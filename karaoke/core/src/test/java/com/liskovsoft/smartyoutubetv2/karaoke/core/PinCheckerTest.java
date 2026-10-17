package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PinCheckerTest {
    private static final String OLD_PIN = "111111";
    private static final String NEW_PIN = "222222";
    private static final int SESSION = 42;
    private static final long CHALLENGE = 0x1234_5678_9ABCL;
    private static final String PHONE = "10.0.0.42";
    private PinChecker mChecker;

    @Before
    public void setUp() {
        mChecker = new PinChecker();
    }

    @Test
    public void noPinLetsAnyoneJoin() {
        assertFalse(mChecker.setPin(null));
        assertFalse(mChecker.isPinRequired());
        assertEquals(KaraokeProtocol.STATUS_OK, mChecker.check(0, SESSION, hello(null, 0), PHONE, 0));
    }

    @Test
    public void pairing() {
        assertTrue(mChecker.setPin(NEW_PIN));

        assertEquals(KaraokeProtocol.STATUS_PIN_REQUIRED, mChecker.check(CHALLENGE, SESSION, hello(null, 0), PHONE, 0));
        assertEquals(KaraokeProtocol.STATUS_OK, mChecker.check(CHALLENGE, SESSION, hello(NEW_PIN, CHALLENGE), PHONE, 0));
    }

    @Test
    public void questionsAreNotGuesses() {
        mChecker.setPin(NEW_PIN);

        for (int i = 0; i < PairingGuard.MAX_FAILURES_TOTAL * 2; i++) {
            // No proof yet, and a proof for another challenge (TV restarted)
            mChecker.check(CHALLENGE, SESSION, hello(null, 0), PHONE, i);
            mChecker.check(CHALLENGE, SESSION, hello("999999", CHALLENGE + 1), PHONE, i);
        }

        assertEquals(KaraokeProtocol.STATUS_OK, mChecker.check(CHALLENGE, SESSION, hello(NEW_PIN, CHALLENGE), PHONE, 100));
    }

    @Test
    public void guessesLockTheSender() {
        mChecker.setPin(NEW_PIN);

        for (int i = 0; i < PairingGuard.MAX_FAILURES_PER_SENDER; i++) {
            assertEquals(KaraokeProtocol.STATUS_PIN_WRONG, mChecker.check(CHALLENGE, SESSION, hello(guess(i), CHALLENGE), PHONE, 0));
        }

        // Even the right PIN waits now
        assertEquals(KaraokeProtocol.STATUS_PIN_LOCKED, mChecker.check(CHALLENGE, SESSION, hello(NEW_PIN, CHALLENGE), PHONE, 0));
        assertEquals(KaraokeProtocol.STATUS_OK,
                mChecker.check(CHALLENGE, SESSION, hello(NEW_PIN, CHALLENGE), PHONE, PairingGuard.LOCK_MS));
    }

    @Test
    public void oldPinAfterNewPinIsNotAGuess() {
        mChecker.setPin(OLD_PIN);
        assertTrue(mChecker.setPin(NEW_PIN)); // paired mics must pair again

        KaraokeProtocol.Hello stale = hello(OLD_PIN, CHALLENGE);
        for (int i = 0; i < PairingGuard.MAX_FAILURES_TOTAL * 2; i++) {
            assertEquals(KaraokeProtocol.STATUS_PIN_WRONG, mChecker.check(CHALLENGE, SESSION, stale, PHONE, 0));
            assertTrue(mChecker.isOldPin(CHALLENGE, SESSION, stale));
        }

        assertEquals(KaraokeProtocol.STATUS_OK, mChecker.check(CHALLENGE, SESSION, hello(NEW_PIN, CHALLENGE), PHONE, 0));
    }

    @Test
    public void revokedPhoneCanNotGuessFreely() {
        mChecker.setPin(OLD_PIN);
        mChecker.setPin(NEW_PIN);

        // Knowing the old PIN doesn't buy free guesses at the new one
        for (int i = 0; i < PairingGuard.MAX_FAILURES_PER_SENDER; i++) {
            KaraokeProtocol.Hello guess = hello(guess(i), CHALLENGE);
            mChecker.check(CHALLENGE, SESSION, guess, PHONE, 0);
            assertFalse(mChecker.isOldPin(CHALLENGE, SESSION, guess));
        }

        assertEquals(KaraokeProtocol.STATUS_PIN_LOCKED, mChecker.check(CHALLENGE, SESSION, hello(NEW_PIN, CHALLENGE), PHONE, 0));
    }

    @Test
    public void samePinAgainKeepsMics() {
        mChecker.setPin(OLD_PIN);

        assertFalse(mChecker.setPin(OLD_PIN));
        assertFalse(mChecker.setPin(null)); // PIN off: connected mics stay
        assertTrue(mChecker.setPin(OLD_PIN)); // PIN on again: mics that joined without it must pair
    }

    private static String guess(int i) {
        return String.format("%06d", 300000 + i);
    }

    private static KaraokeProtocol.Hello hello(String pin, long challenge) {
        KaraokeProtocol.Hello hello = new KaraokeProtocol.Hello();
        hello.pinProof = pin != null ? KaraokePairing.proof(pin, challenge, SESSION) : null;
        hello.challengeEcho = challenge;
        return hello;
    }
}
