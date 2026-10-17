package com.liskovsoft.smartyoutubetv2.karaoke.core;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * PIN pairing between a mic and the TV. The PIN never travels over the network:<br/>
 * 1. A mic says HELLO, the TV answers PIN_REQUIRED with a challenge bound to the mic's session id and address.<br/>
 * 2. The mic proves it knows the PIN with HMAC-SHA256(PIN, challenge + session id) in its next HELLO.<br/>
 * NOTE: someone who can capture the Wi-Fi traffic of a pairing could still brute force a 6 digit PIN offline.
 * The goal is keeping other devices on the network from joining, not protecting against a Wi-Fi sniffer.
 */
public final class KaraokePairing {
    public static final int PIN_DIGITS = 6;
    public static final int PROOF_BYTES = 32;
    private static final String HMAC = "HmacSHA256";
    // NOTE: StandardCharsets requires API 19
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private KaraokePairing() {
    }

    public static String generatePin(SecureRandom random) {
        int max = (int) Math.pow(10, PIN_DIGITS);
        return String.format(Locale.US, "%0" + PIN_DIGITS + "d", random.nextInt(max));
    }

    /**
     * Keeps digits only ("123 456" -> "123456").
     * @return null if the result isn't a full PIN
     */
    public static String normalizePin(String input) {
        if (input == null) {
            return null;
        }

        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }

        return digits.length() == PIN_DIGITS ? digits.toString() : null;
    }

    /**
     * What the mic sends to show it knows the PIN.
     */
    public static byte[] proof(String pin, long challenge, int sessionId) {
        return hmac(pin.getBytes(UTF_8), ByteBuffer.allocate(12).putLong(challenge).putInt(sessionId).array());
    }

    /**
     * Constant time check of a mic's proof.
     */
    public static boolean verify(String pin, long challenge, int sessionId, byte[] proof) {
        return proof != null && proof.length == PROOF_BYTES && MessageDigest.isEqual(proof(pin, challenge, sessionId), proof);
    }

    /**
     * Challenge for one mic session, derived from a per-run TV secret, so the TV keeps no per-mic state.
     * Binding it to the sender address means a spoofed sender never sees the challenge it would need.
     */
    public static long challenge(byte[] tvSecret, int sessionId, byte[] senderAddress) {
        byte[] data = ByteBuffer.allocate(4 + senderAddress.length).putInt(sessionId).put(senderAddress).array();
        return ByteBuffer.wrap(hmac(tvSecret, data)).getLong();
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 isn't available", e);
        }
    }
}
