package com.liskovsoft.smartyoutubetv2.common.karaoke;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokeProtocol;

/**
 * Captures a microphone the TV itself exposes (USB mic, USB wireless mic receiver, headset, and on some TVs
 * the remote / Google TV app mic) and feeds it to the mixer like any phone mic.<br/>
 * NOTE: remote and Google TV app mics usually stream only while their mic button is held.<br/>
 * The mic is opened on the capture thread (USB audio can take a while), failures are reported through a callback.
 */
final class LocalMicCapture {
    private static final String TAG = LocalMicCapture.class.getSimpleName();
    private static final int RATE = KaraokeProtocol.SAMPLE_RATE;
    private static final int FRAME = KaraokeProtocol.FRAME_SAMPLES;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private final MicSource mSource;
    private final FailureListener mFailureListener;
    private volatile boolean mIsRunning;
    private volatile AudioRecord mRecord;
    private Thread mThread;

    interface FailureListener {
        /**
         * Capture thread: no mic could be opened or it stopped working.
         */
        void onFailed(LocalMicCapture capture);
    }

    LocalMicCapture(MicSource source, FailureListener failureListener) {
        mSource = source;
        mFailureListener = failureListener;
    }

    /**
     * NOTE: RECORD_AUDIO should be granted already.
     * @return false if this device can't record at all
     */
    boolean start() {
        if (AudioRecord.getMinBufferSize(RATE, CHANNEL_MASK, ENCODING) <= 0) {
            Log.e(TAG, "TV mic doesn't support %s Hz mono", RATE);
            return false;
        }

        mIsRunning = true;
        mThread = new Thread(this::captureLoop, "KaraokeLocalMic");
        mThread.start();

        return true;
    }

    void stop() {
        mIsRunning = false;

        if (mThread == null) {
            return;
        }

        try {
            mThread.join(300);
            AudioRecord record = mRecord;
            if (mThread.isAlive() && record != null) {
                record.stop(); // unblocks read()
                mThread.join(300);
            }
        } catch (InterruptedException | IllegalStateException e) {
            Log.e(TAG, "TV mic stop error: %s", e.getMessage());
        }

        mThread = null;
    }

    private void captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        AudioRecord record = open();

        if (record == null) {
            if (mIsRunning) {
                mFailureListener.onFailed(this);
            }
            return;
        }

        mRecord = record;
        short[] buffer = new short[FRAME];

        try {
            while (mIsRunning) {
                int read = record.read(buffer, 0, FRAME);

                if (read > 0) {
                    mSource.getBuffer().writeSamples(buffer, 0, read);
                    mSource.touch();
                } else if (read < 0) {
                    Log.e(TAG, "TV mic read error: %s", read);
                    if (mIsRunning) {
                        mFailureListener.onFailed(this);
                    }
                    break;
                }
            }
        } finally {
            mRecord = null;
            release(record);
        }
    }

    /**
     * Android TV routes remote (and Google TV app) voice to the voice recognition source.
     * A plugged in USB mic wins the routing for any source.
     */
    private static AudioRecord open() {
        int bufferBytes = Math.max(AudioRecord.getMinBufferSize(RATE, CHANNEL_MASK, ENCODING), FRAME * 2 * 8);

        for (int source : new int[] {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC}) {
            AudioRecord record = open(source, bufferBytes);
            if (record != null) {
                return record;
            }
        }

        return null;
    }

    @SuppressLint("MissingPermission") // KaraokeManager checks RECORD_AUDIO before starting
    private static AudioRecord open(int source, int bufferBytes) {
        AudioRecord record = null;

        try {
            record = new AudioRecord(source, RATE, CHANNEL_MASK, ENCODING, bufferBytes);

            if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                record.startRecording();

                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    Log.d(TAG, "TV mic opened, source %s", source);
                    return record;
                }
            }
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            Log.e(TAG, "Can't open TV mic source %s: %s", source, e.getMessage());
        }

        release(record); // busy (taken by another app) or not supported
        return null;
    }

    private static void release(AudioRecord record) {
        if (record == null) {
            return;
        }

        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
        } catch (IllegalStateException e) {
            // Not started
        }

        record.release();
    }
}
