package com.liskovsoft.smartyoutubetv2.karaoke.core;

/**
 * TV side of PIN pairing: decides what to answer a HELLO from a mic that isn't connected yet.<br/>
 * Wrong proofs count as guesses (see {@link PairingGuard}), except proofs of the PIN the TV had before New PIN:
 * those come from phones that paired before and don't know the new PIN yet, and they tell nothing about the new one.
 */
public final class PinChecker {
    private final PairingGuard mGuard = new PairingGuard();
    private volatile String mPin;
    private volatile String mOldPin;

    /**
     * @param pin null lets any mic join
     * @return true if mics paired with the old PIN (or without one) must pair again
     */
    public synchronized boolean setPin(String pin) {
        String oldPin = mPin;

        if (oldPin != null && !oldPin.equals(pin)) {
            mOldPin = oldPin;
        }

        mPin = pin;
        mGuard.reset();

        return pin != null && !pin.equals(oldPin);
    }

    public boolean isPinRequired() {
        return mPin != null;
    }

    /**
     * @param challenge the TV's challenge for this session and sender, see {@link KaraokePairing#challenge}
     * @return {@link KaraokeProtocol#STATUS_OK} or one of the PIN statuses
     */
    public int check(long challenge, int sessionId, KaraokeProtocol.Hello hello, String sender, long nowMs) {
        String pin = mPin;

        if (pin == null) {
            return KaraokeProtocol.STATUS_OK;
        }

        if (mGuard.isLocked(sender, nowMs)) {
            return KaraokeProtocol.STATUS_PIN_LOCKED;
        }

        // No proof yet, or one for an old challenge (TV restarted, phone got another IP): ask again, it's not a guess
        if (hello.pinProof == null || hello.challengeEcho != challenge) {
            return KaraokeProtocol.STATUS_PIN_REQUIRED;
        }

        if (!KaraokePairing.verify(pin, challenge, sessionId, hello.pinProof)) {
            if (!isOldPin(challenge, sessionId, hello)) {
                mGuard.onFailure(sender, nowMs); // a guess
            }
            return KaraokeProtocol.STATUS_PIN_WRONG;
        }

        mGuard.onSuccess(sender);

        return KaraokeProtocol.STATUS_OK;
    }

    /**
     * The mic proved the PIN from before New PIN: no lockout, no "wrong PIN" alarm on the TV.
     */
    public boolean isOldPin(long challenge, int sessionId, KaraokeProtocol.Hello hello) {
        String oldPin = mOldPin;
        return oldPin != null && KaraokePairing.verify(oldPin, challenge, sessionId, hello.pinProof);
    }
}
