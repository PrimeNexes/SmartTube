package com.liskovsoft.smartyoutubetv2.common.karaoke;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokeProtocol;
import com.liskovsoft.smartyoutubetv2.karaoke.core.Limiter;
import com.liskovsoft.smartyoutubetv2.karaoke.core.VoiceReverb;

import java.util.Arrays;

/**
 * Mixes all mics, adds voice effects and plays the result through its own low latency AudioTrack.
 * The system mixer puts it on top of the video sound, so the music player isn't touched at all.
 */
final class KaraokeMixer {
    private static final String TAG = KaraokeMixer.class.getSimpleName();
    private static final int RATE = KaraokeProtocol.SAMPLE_RATE;
    private static final int FRAME = KaraokeProtocol.FRAME_SAMPLES;
    private static final int MAX_SOURCES = KaraokeServer.MAX_MICS + 1; // + TV mic
    private static final long LATENCY_TUNE_INTERVAL_MS = 1_000;
    private final SourceProvider mProvider;
    private final Runnable mOnFailure;
    private final Object mIdleLock = new Object();
    private volatile boolean mIsRunning;
    private volatile float mVolume = 1;
    private volatile int mVoiceEffect = VoiceReverb.PRESET_OFF;
    private volatile boolean mIsNoiseGateEnabled = true;
    private Thread mThread;

    interface SourceProvider {
        /**
         * @return number of sources copied into the array
         */
        int getSources(MicSource[] out);
    }

    /**
     * @param onFailure called on the mixer thread if the audio output dies (e.g. no output device)
     */
    KaraokeMixer(SourceProvider provider, Runnable onFailure) {
        mProvider = provider;
        mOnFailure = onFailure;
    }

    void start() {
        mIsRunning = true;
        mThread = new Thread(this::mixLoop, "KaraokeMixer");
        mThread.start();
    }

    void stop() {
        mIsRunning = false;
        wakeUp();

        if (mThread != null) {
            try {
                mThread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mThread = null;
        }
    }

    /**
     * New mic connected, leave idle state.
     */
    void wakeUp() {
        synchronized (mIdleLock) {
            mIdleLock.notifyAll();
        }
    }

    void setVolume(float volume) {
        mVolume = volume;
    }

    void setVoiceEffect(int preset) {
        mVoiceEffect = preset;
    }

    void setNoiseGateEnabled(boolean enabled) {
        mIsNoiseGateEnabled = enabled;
    }

    private void mixLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        AudioTrack track = createTrack();

        if (track == null) {
            Log.e(TAG, "Can't create karaoke audio output");
            fail();
            return;
        }

        MicSource[] sources = new MicSource[MAX_SOURCES];
        float[] mix = new float[FRAME];
        float[] voice = new float[FRAME];
        short[] pcm = new short[FRAME];
        VoiceReverb reverb = new VoiceReverb(RATE);
        Limiter limiter = new Limiter(RATE);
        boolean isPlaying = false;
        long lastTuneMs = 0;
        int lastUnderruns = 0;

        try {
            while (mIsRunning) {
                int count = mProvider.getSources(sources);

                if (count == 0) {
                    if (isPlaying) { // nobody's singing, release the audio path
                        track.pause();
                        track.flush();
                        reverb.clear();
                        isPlaying = false;
                    }
                    synchronized (mIdleLock) {
                        if (mIsRunning) {
                            mIdleLock.wait(250);
                        }
                    }
                    continue;
                }

                if (!isPlaying) {
                    track.play();
                    isPlaying = true;
                }

                Arrays.fill(mix, 0);
                boolean gateEnabled = mIsNoiseGateEnabled;

                for (int i = 0; i < count; i++) {
                    MicSource source = sources[i];
                    sources[i] = null;

                    source.getBuffer().read(voice, FRAME);

                    if (gateEnabled) {
                        source.getGate().process(voice, FRAME);
                    }

                    for (int j = 0; j < FRAME; j++) {
                        mix[j] += voice[j];
                    }
                }

                float volume = mVolume;
                for (int j = 0; j < FRAME; j++) {
                    mix[j] *= volume;
                }

                reverb.setPreset(mVoiceEffect);
                reverb.process(mix, FRAME);
                limiter.process(mix, FRAME);

                for (int j = 0; j < FRAME; j++) {
                    pcm[j] = (short) (mix[j] * Short.MAX_VALUE);
                }

                // Blocking write paces the loop at the speed of the DAC
                int written = track.write(pcm, 0, FRAME);

                if (written < 0) { // e.g. ERROR_DEAD_OBJECT after the output device went away
                    Log.e(TAG, "Karaoke audio output error: %s", written);
                    fail();
                    break;
                }

                if (Build.VERSION.SDK_INT >= 24) {
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastTuneMs > LATENCY_TUNE_INTERVAL_MS) {
                        lastTuneMs = nowMs;
                        lastUnderruns = tuneLatency(track, lastUnderruns);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.e(TAG, "Karaoke mixer crashed: %s", e.getMessage());
            fail();
        } finally {
            try {
                track.stop();
            } catch (IllegalStateException e) {
                // Already stopped
            }
            track.release();
        }
    }

    private void fail() {
        if (mIsRunning) {
            mIsRunning = false;
            mOnFailure.run();
        }
    }

    /**
     * Start with a tiny output buffer and grow it a step every time the device can't keep up (Oboe style).
     */
    private static int tuneLatency(AudioTrack track, int lastUnderruns) {
        if (Build.VERSION.SDK_INT < 24) {
            return lastUnderruns;
        }

        int underruns = track.getUnderrunCount();

        if (underruns > lastUnderruns) {
            int size = track.getBufferSizeInFrames();
            int capacity = track.getBufferCapacityInFrames();
            if (size < capacity) {
                int newSize = track.setBufferSizeInFrames(Math.min(capacity, size + FRAME));
                Log.d(TAG, "Karaoke output underrun, buffer %s -> %s frames", size, newSize);
            }
        }

        return underruns;
    }

    @SuppressWarnings("deprecation")
    private static AudioTrack createTrack() {
        int channelMask = AudioFormat.CHANNEL_OUT_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minBufferBytes = AudioTrack.getMinBufferSize(RATE, channelMask, encoding);
        int bufferBytes = Math.max(minBufferBytes, FRAME * 2 * 4);
        AudioTrack track;

        try {
            if (Build.VERSION.SDK_INT >= 23) {
                AudioTrack.Builder builder = new AudioTrack.Builder()
                        .setAudioAttributes(createAttributes())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(RATE)
                                .setChannelMask(channelMask)
                                .setEncoding(encoding)
                                .build())
                        .setBufferSizeInBytes(bufferBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM);

                if (Build.VERSION.SDK_INT >= 26) {
                    builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
                }

                track = builder.build();
            } else if (Build.VERSION.SDK_INT >= 21) {
                track = new AudioTrack(createAttributes(),
                        new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(channelMask).setEncoding(encoding).build(),
                        bufferBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            } else {
                track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, channelMask, encoding, bufferBytes, AudioTrack.MODE_STREAM);
            }
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            Log.e(TAG, "Karaoke AudioTrack error: %s", e.getMessage());
            return null;
        }

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            return null;
        }

        if (Build.VERSION.SDK_INT >= 26 && track.getPerformanceMode() == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) {
            // Got a fast track: keep just a few ms queued, tuneLatency() grows it if needed
            track.setBufferSizeInFrames(FRAME * 3);
        }

        Log.d(TAG, "Karaoke output buffer: %s bytes requested", bufferBytes);

        return track;
    }

    private static AudioAttributes createAttributes() {
        // Same stream as the video, so the remote's volume keys control both
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }
}
