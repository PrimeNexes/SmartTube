package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import java.security.SecureRandom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KaraokePairingTest {
    private static final byte[] TV_SECRET = {1, 2, 3, 4, 5, 6, 7, 8};
    private static final byte[] PHONE_IP = {(byte) 192, (byte) 168, 1, 20};

    @Test
    public void pinsHaveSixDigits() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 100; i++) {
            String pin = KaraokePairing.generatePin(random);
            assertEquals(KaraokePairing.PIN_DIGITS, pin.length());
            assertTrue(pin.matches("\\d+"));
        }
    }

    @Test
    public void formatsAndParsesBack() {
        assertEquals("123 456", KaraokePairing.formatPin("123456"));
        assertEquals("123456", KaraokePairing.normalizePin(KaraokePairing.formatPin("123456")));
    }

    @Test
    public void normalizesTypedPins() {
        assertEquals("123456", KaraokePairing.normalizePin(" 123 456 "));
        assertEquals("012345", KaraokePairing.normalizePin("012-345"));
        assertNull(KaraokePairing.normalizePin("12345"));
        assertNull(KaraokePairing.normalizePin(null));
    }

    @Test
    public void rightPinVerifies() {
        long challenge = KaraokePairing.challenge(TV_SECRET, 77, PHONE_IP);
        byte[] proof = KaraokePairing.proof("482193", challenge, 77);

        assertEquals(KaraokePairing.PROOF_BYTES, proof.length);
        assertTrue(KaraokePairing.verify("482193", challenge, 77, proof));
    }

    @Test
    public void wrongPinSessionOrChallengeFails() {
        long challenge = KaraokePairing.challenge(TV_SECRET, 77, PHONE_IP);
        byte[] proof = KaraokePairing.proof("482193", challenge, 77);

        assertFalse(KaraokePairing.verify("482194", challenge, 77, proof));
        assertFalse(KaraokePairing.verify("482193", challenge, 78, proof));
        assertFalse(KaraokePairing.verify("482193", challenge + 1, 77, proof));
        assertFalse(KaraokePairing.verify("482193", challenge, 77, null));
        assertFalse(KaraokePairing.verify("482193", challenge, 77, new byte[3]));
    }

    @Test
    public void challengeDependsOnSessionAddressAndSecret() {
        long base = KaraokePairing.challenge(TV_SECRET, 77, PHONE_IP);

        assertEquals(base, KaraokePairing.challenge(TV_SECRET, 77, PHONE_IP));
        assertNotEquals(base, KaraokePairing.challenge(TV_SECRET, 78, PHONE_IP));
        assertNotEquals(base, KaraokePairing.challenge(TV_SECRET, 77, new byte[] {(byte) 192, (byte) 168, 1, 21}));
        assertNotEquals(base, KaraokePairing.challenge(new byte[] {9}, 77, PHONE_IP));
    }
}
