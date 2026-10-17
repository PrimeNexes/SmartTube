package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AutoTuneTest {
    private static final int RATE = 48_000;
    private static final int FRAME = 240;
    private static final float A3 = 220;

    @Test
    public void offLeavesTheVoiceAlone() {
        float[] voice = voice(226, 0, 0.5f);
        float[] copy = voice.clone();

        AutoTune autoTune = new AutoTune(RATE, FRAME);
        run(autoTune, voice);

        assertArrayEquals(copy, voice, 0);
        assertEquals(-1, autoTune.getNote(), 0);
    }

    @Test
    public void robotSnapsToTheNearestNote() {
        // A3 + 46 cents
        AutoTune autoTune = tuner(AutoTune.MODE_ROBOT, AutoTune.KEY_CHROMATIC);
        float[] out = run(autoTune, voice(226, 0, 1));

        assertEquals(0, PitchDetectorTest.cents(lastPitch(out), A3), 3);
        assertEquals(57, autoTune.getTargetNote()); // A3
        assertEquals(57.47f, autoTune.getNote(), 0.02f);
    }

    @Test
    public void theKeyDecidesTheNote() {
        // 228 Hz is closest to A#3, which C major doesn't have: A3 then
        float[] chromatic = run(tuner(AutoTune.MODE_ROBOT, AutoTune.KEY_CHROMATIC), voice(228, 0, 1));
        float[] cMajor = run(tuner(AutoTune.MODE_ROBOT, 0), voice(228, 0, 1));
        float[] aMinor = run(tuner(AutoTune.MODE_ROBOT, 12 + 9), voice(228, 0, 1));
        float[] bFlatMajor = run(tuner(AutoTune.MODE_ROBOT, 10), voice(228, 0, 1));

        assertEquals(0, PitchDetectorTest.cents(lastPitch(chromatic), 233.08f), 3);
        assertEquals(0, PitchDetectorTest.cents(lastPitch(cMajor), A3), 3);
        assertEquals(0, PitchDetectorTest.cents(lastPitch(aMinor), A3), 3);
        assertEquals(0, PitchDetectorTest.cents(lastPitch(bFlatMajor), 233.08f), 3);
    }

    @Test
    public void naturalGetsThereSmoothly() {
        float[] out = run(tuner(AutoTune.MODE_NATURAL, AutoTune.KEY_CHROMATIC), voice(226, 0, 1.5f));
        float[] track = pitchTrack(out);

        // Off key at the start of the note, in tune after a while, never past the target
        assertTrue(PitchDetectorTest.cents(track[8], A3) > 25);
        assertEquals(0, PitchDetectorTest.cents(track[track.length - 1], A3), 3);
        for (float hz : track) {
            assertTrue(hz <= 0 || PitchDetectorTest.cents(hz, A3) > -3);
        }
    }

    @Test
    public void naturalKeepsVibratoRobotFlattensIt() {
        float input = vibratoDepth(voice(A3, 40, 2));
        float natural = vibratoDepth(run(tuner(AutoTune.MODE_NATURAL, AutoTune.KEY_CHROMATIC), voice(A3, 40, 2)));
        float strong = vibratoDepth(run(tuner(AutoTune.MODE_STRONG, AutoTune.KEY_CHROMATIC), voice(A3, 40, 2)));
        float robot = vibratoDepth(run(tuner(AutoTune.MODE_ROBOT, AutoTune.KEY_CHROMATIC), voice(A3, 40, 2)));

        assertTrue("natural " + natural + " of " + input, natural > input * 0.6f);
        assertTrue("strong " + strong + " of " + input, strong < natural);
        assertTrue("robot " + robot + " of " + input, robot < input * 0.4f);
    }

    @Test
    public void staysOnANoteSungBetweenTwo() {
        // A3 + 50 cents, wobbling 5 cents: exactly between A3 and A#3
        float[] voice = voice(A3 * (float) Math.pow(2, 0.5 / 12), 5, 2);
        AutoTune autoTune = tuner(AutoTune.MODE_ROBOT, AutoTune.KEY_CHROMATIC);
        float[] block = new float[FRAME];
        int changes = 0;
        int last = -1;

        for (int start = 0; start + FRAME <= voice.length; start += FRAME) {
            System.arraycopy(voice, start, block, 0, FRAME);
            autoTune.process(block, FRAME);
            int target = autoTune.getTargetNote();
            if (target >= 0 && last >= 0 && target != last) {
                changes++;
            }
            if (target >= 0) {
                last = target;
            }
        }

        assertTrue("target changed " + changes + " times", changes <= 1);
    }

    @Test
    public void noiseAndConsonantsPassThrough() {
        Random random = new Random(5);
        float[] noise = new float[RATE];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (random.nextFloat() - 0.5f) * 0.3f;
        }

        AutoTune autoTune = tuner(AutoTune.MODE_ROBOT, AutoTune.KEY_CHROMATIC);
        float[] out = run(autoTune, noise.clone());

        assertEquals(rms(noise), rms(out), rms(noise) * 0.05f);
        assertEquals(-1, autoTune.getTargetNote());
    }

    @Test
    public void switchingOnAndOffDoesNotClick() {
        float[] voice = voice(226, 0, 2);
        float[] original = voice.clone();
        AutoTune autoTune = new AutoTune(RATE, FRAME);
        float[] block = new float[FRAME];

        for (int start = 0, frame = 0; start + FRAME <= voice.length; start += FRAME, frame++) {
            autoTune.setMode(frame / 20 % 2 == 1 ? AutoTune.MODE_ROBOT : AutoTune.MODE_OFF); // every 100 ms, mid-song
            System.arraycopy(voice, start, block, 0, FRAME);
            autoTune.process(block, FRAME);
            System.arraycopy(block, 0, voice, start, FRAME);
        }

        assertTrue(PitchShifterTest.maxStep(voice, 0) < PitchShifterTest.maxStep(original, 0) * 1.3f);
    }

    @Test
    public void silenceStaysSilent() {
        float[] silence = new float[RATE / 2];
        float[] out = run(tuner(AutoTune.MODE_STRONG, 3), silence);

        for (float value : out) {
            assertEquals(0, value, 0);
        }
    }

    @Test
    public void keyCanChangeWhileSinging() {
        AutoTune autoTune = tuner(AutoTune.MODE_ROBOT, AutoTune.KEY_CHROMATIC);
        run(autoTune, voice(228, 0, 0.5f));
        assertEquals(58, autoTune.getTargetNote()); // A#3

        autoTune.setKey(0); // C major
        run(autoTune, voice(228, 0, 0.5f));
        assertEquals(57, autoTune.getTargetNote()); // A3
        assertEquals(0, autoTune.getKey());
    }

    private static AutoTune tuner(int mode, int key) {
        AutoTune autoTune = new AutoTune(RATE, FRAME);
        autoTune.setMode(mode);
        autoTune.setKey(key);
        return autoTune;
    }

    /**
     * In place, frame by frame like the phone.
     */
    private static float[] run(AutoTune autoTune, float[] signal) {
        float[] block = new float[FRAME];
        for (int start = 0; start + FRAME <= signal.length; start += FRAME) {
            System.arraycopy(signal, start, block, 0, FRAME);
            autoTune.process(block, FRAME);
            System.arraycopy(block, 0, signal, start, FRAME);
        }
        return signal;
    }

    /**
     * Sung-like tone: harmonics with a falling spectrum, optional vibrato (5.5 Hz, +-depth cents).
     */
    private static float[] voice(float hz, float vibratoCents, float seconds) {
        float[] signal = new float[(int) (RATE * seconds)];
        float[] amplitudes = {0.3f, 0.2f, 0.12f, 0.08f, 0.05f, 0.03f};
        double phase = 0;
        for (int i = 0; i < signal.length; i++) {
            double cents = vibratoCents * Math.sin(2 * Math.PI * 5.5 * i / RATE);
            phase += 2 * Math.PI * hz * Math.pow(2, cents / 1200) / RATE;
            double value = 0;
            for (int k = 0; k < amplitudes.length; k++) {
                value += amplitudes[k] * Math.sin((k + 1) * phase + k);
            }
            signal[i] = (float) value;
        }
        return signal;
    }

    private static float[] pitchTrack(float[] signal) {
        PitchDetector detector = new PitchDetector(RATE);
        float[] track = new float[signal.length / FRAME];
        for (int k = 0; k < track.length; k++) {
            detector.write(signal, k * FRAME, FRAME);
            float period = detector.detect();
            track[k] = period > 0 ? RATE / period : 0;
        }
        return track;
    }

    private static float lastPitch(float[] signal) {
        float[] track = pitchTrack(signal);
        return track[track.length - 1];
    }

    /**
     * Standard deviation of the pitch in cents over the second half.
     */
    private static float vibratoDepth(float[] signal) {
        float[] track = pitchTrack(signal);
        float[] half = Arrays.copyOfRange(track, track.length / 2, track.length);
        double mean = 0;
        for (float hz : half) {
            mean += PitchDetectorTest.cents(hz, A3);
        }
        mean /= half.length;
        double variance = 0;
        for (float hz : half) {
            double deviation = PitchDetectorTest.cents(hz, A3) - mean;
            variance += deviation * deviation;
        }
        return (float) Math.sqrt(variance / half.length);
    }

    private static float rms(float[] signal) {
        double sum = 0;
        for (float value : signal) {
            sum += value * value;
        }
        return (float) Math.sqrt(sum / signal.length);
    }
}
