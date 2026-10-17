package com.liskovsoft.smartyoutubetv2.common.karaoke;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokeProtocol;

/**
 * Captures a microphone the TV itself exposes (USB mic, USB wireless mic receiver, headset, and on some TVs
 * the remote / Google TV app mic) and feeds it to the mixer like any phone mic.<br/>
 * NOTE: remote and Google TV app mics usually stream only while their mic button is held.
 */
final class LocalMicCapture {
    private static final String TAG = LocalMicCapture.class.getSimpleName();
    private static final int RATE = KaraokeProtocol.SAMPLE_RATE;
    private static final int FRAME = KaraokeProtocol.FRAME_SAMPLES;
    private final MicSource mSource;
    private volatile boolean mIsRunning;
    private AudioRecord mRecord;
    private Thread mThread;

    LocalMicCapture(MicSource source) {
        mSource = source;
    }

    /**
     * NOTE: RECORD_AUDIO should be granted already.
     * @return false if there's no usable mic or it's busy
     */
    boolean start() {
        int channelMask = AudioFormat.CHANNEL_IN_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minBufferBytes = AudioRecord.getMinBufferSize(RATE, channelMask, encoding);

        if (minBufferBytes <= 0) {
            Log.e(TAG, "TV mic doesn't support %s Hz mono", RATE);
            return false;
        }

        // Android TV routes remote (and Google TV app) voice to the voice recognition source.
        // A plugged in USB mic wins the routing for any source.
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};

        for (int source : sources) {
            if (open(source, channelMask, encoding, Math.max(minBufferBytes, FRAME * 2 * 8))) {
                break;
            }
        }

        if (mRecord == null) {
            return false;
        }

        mIsRunning = true;
        mThread = new Thread(this::captureLoop, "KaraokeLocalMic");
        mThread.start();

        return true;
    }

    private boolean open(int source, int channelMask, int encoding, int bufferBytes) {
        try {
            mRecord = new AudioRecord(source, RATE, channelMask, encoding, bufferBytes);

            if (mRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                mRecord.startRecording();

                if (mRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    Log.d(TAG, "TV mic opened, source %s", source);
                    return true;
                }
            }
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            Log.e(TAG, "Can't open TV mic source %s: %s", source, e.getMessage());
        }

        release(); // busy (taken by another app) or not supported
        return false;
    }

    /**
     * The capture thread releases the mic on exit, so release() never races a blocked read().
     */
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

        short[] buffer = new short[FRAME];

        try {
            while (mIsRunning) {
                int read = mRecord.read(buffer, 0, FRAME);

                if (read > 0) {
                    mSource.getBuffer().writeSamples(buffer, 0, read);
                    mSource.touch();
                } else if (read < 0) {
                    Log.e(TAG, "TV mic read error: %s", read);
                    break;
                }
            }
        } finally {
            release();
        }
    }

    private synchronized void release() {
        if (mRecord == null) {
            return;
        }

        try {
            if (mRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                mRecord.stop();
            }
        } catch (IllegalStateException e) {
            // Not started
        }

        mRecord.release();
        mRecord = null;
    }
}
