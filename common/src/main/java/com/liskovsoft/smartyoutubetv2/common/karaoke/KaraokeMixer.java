package com.liskovsoft.smartyoutubetv2.common.karaoke;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.RequiresApi;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.karaoke.core.Biquad;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokeProtocol;
import com.liskovsoft.smartyoutubetv2.karaoke.core.Limiter;
import com.liskovsoft.smartyoutubetv2.karaoke.core.VoiceReverb;

import java.util.Arrays;

/**
 * Mixes all mics, shapes the voice to stand out from the music (less boom and mud, more presence), adds voice
 * effects and plays the result through its own low latency AudioTrack. The system mixer puts it on top of the video
 * sound, so the music player isn't touched at all.<br/>
 * The loop doesn't allocate, so the garbage collector can't cause audio glitches.
 */
final class KaraokeMixer {
    private static final String TAG = KaraokeMixer.class.getSimpleName();
    private static final int RATE = KaraokeProtocol.SAMPLE_RATE;
    private static final int FRAME = KaraokeProtocol.FRAME_SAMPLES;
    private static final int MAX_SOURCES = KaraokeServer.MAX_MICS + 1; // + TV mic
    private static final long LATENCY_TUNE_INTERVAL_MS = 100;
    /** The output dies when the audio route changes (HDMI, soundbar, Bluetooth). Reopen it a few times. */
    private static final int MAX_TRACK_RESTARTS = 3;
    private static final long TRACK_RESTART_WINDOW_MS = 60_000;
    private static final long TRACK_RESTART_DELAY_MS = 300;
    /** Without a gate: louder than this (~ -50 dBFS RMS) is someone singing. */
    private static final float VOICE_RMS = 0.003f;
    private final SourceProvider mProvider;
    private final int mFramesPerBurst;
    private final Runnable mOnFailure;
    private final Object mIdleLock = new Object();
    private volatile boolean mIsRunning;
    private volatile float mVolume = 1;
    private volatile int mVoiceEffect = VoiceReverb.PRESET_OFF;
    private volatile boolean mIsNoiseGateEnabled = true;
    private volatile boolean mIsAutoLevelEnabled = true;
    private volatile boolean mIsSuspended;
    private volatile boolean mIsVoiceActive;
    private Thread mThread;

    interface SourceProvider {
        /**
         * Must not allocate, called for every 5 ms frame.
         * @return number of sources copied into the array
         */
        int getSources(MicSource[] out);
    }

    /**
     * @param framesPerBurst the device's output period (AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER), 0 if unknown
     * @param onFailure called on the mixer thread if the audio output can't be (re)opened
     */
    KaraokeMixer(SourceProvider provider, int framesPerBurst, Runnable onFailure) {
        mProvider = provider;
        mFramesPerBurst = framesPerBurst > 0 ? framesPerBurst : FRAME;
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

    void setAutoLevelEnabled(boolean enabled) {
        mIsAutoLevelEnabled = enabled;
    }

    /**
     * Someone is singing right now (a mic's gate is open). Any thread.
     */
    boolean isVoiceActive() {
        return mIsVoiceActive;
    }

    /**
     * SmartTube went to the background: stop playing the mics over other apps. They keep their connection.
     */
    void setSuspended(boolean suspended) {
        mIsSuspended = suspended;

        if (!suspended) {
            wakeUp();
        }
    }

    private void mixLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        MicSource[] sources = new MicSource[MAX_SOURCES];
        float[] mix = new float[FRAME];
        float[] voice = new float[FRAME];
        short[] pcm = new short[FRAME * 2]; // stereo: mono tracks may play 3 dB quieter on some TVs
        // Voice EQ: rumble and boom out, a little less mud, more presence so words cut through the music
        Biquad lowCut = Biquad.highPass(RATE, 100, Biquad.Q_BUTTERWORTH);
        Biquad mud = Biquad.peaking(RATE, 300, 1, -2);
        Biquad presence = Biquad.peaking(RATE, 3_000, 0.9f, 3.5f);
        VoiceReverb reverb = new VoiceReverb(RATE);
        Limiter limiter = new Limiter(RATE);
        AudioTrack track = null; // opened when the first mic arrives
        boolean isPlaying = false;
        long lastTuneMs = 0;
        int lastUnderruns = 0;
        int restarts = 0;
        long firstRestartMs = 0;

        try {
            while (mIsRunning) {
                int count = mIsSuspended ? 0 : mProvider.getSources(sources);

                if (count == 0) {
                    mIsVoiceActive = false;
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

                if (track == null) {
                    track = createTrack(mFramesPerBurst);

                    if (track == null) {
                        Arrays.fill(sources, null);
                        fail();
                        break;
                    }

                    lastUnderruns = 0;
                }

                if (!isPlaying) {
                    track.play();
                    isPlaying = true;
                }

                mixVoices(sources, count, mix, voice);

                for (int j = 0; j < FRAME; j++) {
                    mix[j] = presence.process(mud.process(lowCut.process(mix[j])));
                }

                reverb.setPreset(mVoiceEffect);
                reverb.process(mix, FRAME);
                limiter.process(mix, FRAME);

                for (int j = 0; j < FRAME; j++) {
                    short value = (short) (mix[j] * Short.MAX_VALUE);
                    pcm[j * 2] = value;
                    pcm[j * 2 + 1] = value;
                }

                // Blocking write paces the loop at the speed of the DAC
                int written = track.write(pcm, 0, pcm.length);

                if (written < 0) { // e.g. ERROR_DEAD_OBJECT after the output device changed
                    Log.e(TAG, "Karaoke audio output error %s, reopening", written);
                    release(track);
                    track = null;
                    isPlaying = false;

                    long nowMs = SystemClock.elapsedRealtime();
                    if (nowMs - firstRestartMs > TRACK_RESTART_WINDOW_MS) {
                        firstRestartMs = nowMs;
                        restarts = 0;
                    }

                    if (++restarts > MAX_TRACK_RESTARTS) {
                        fail();
                        break;
                    }

                    Thread.sleep(TRACK_RESTART_DELAY_MS); // let the new output device settle
                    continue;
                }

                if (Build.VERSION.SDK_INT >= 24) {
                    long nowMs = SystemClock.elapsedRealtime();
                    if (nowMs - lastTuneMs > LATENCY_TUNE_INTERVAL_MS) {
                        lastTuneMs = nowMs;
                        lastUnderruns = tuneLatency(track, lastUnderruns, mFramesPerBurst);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.e(TAG, "Karaoke mixer crashed: %s", e.getMessage());
            fail();
        } finally {
            if (track != null) {
                release(track);
            }
        }
    }

    /**
     * Sums all mics into the mix (volume applied), each one cleaned up by its own gate and auto level.
     */
    private void mixVoices(MicSource[] sources, int count, float[] mix, float[] voice) {
        Arrays.fill(mix, 0);
        boolean gateEnabled = mIsNoiseGateEnabled;
        boolean autoLevelEnabled = mIsAutoLevelEnabled;
        boolean isVoiceActive = false;

        for (int i = 0; i < count; i++) {
            MicSource source = sources[i];
            sources[i] = null;

            source.getBuffer().read(voice, FRAME);

            if (gateEnabled) {
                source.getGate().process(voice, FRAME);
                isVoiceActive |= source.getGate().isOpen();
            } else {
                isVoiceActive |= rms(voice) > VOICE_RMS;
            }

            if (autoLevelEnabled) {
                source.getAutoLevel().process(voice, FRAME);
            }

            for (int j = 0; j < FRAME; j++) {
                mix[j] += voice[j];
            }
        }

        float volume = mVolume;
        for (int j = 0; j < FRAME; j++) {
            mix[j] *= volume;
        }

        mIsVoiceActive = isVoiceActive;
    }

    private static float rms(float[] frame) {
        float sum = 0;
        for (float value : frame) {
            sum += value * value;
        }
        return (float) Math.sqrt(sum / frame.length);
    }

    private void fail() {
        if (mIsRunning) {
            mIsRunning = false;
            mOnFailure.run();
        }
    }

    private static void release(AudioTrack track) {
        try {
            track.stop();
        } catch (IllegalStateException e) {
            // Already stopped
        }
        track.release();
    }

    /**
     * Start with a tiny output buffer and grow it one device period every time it can't keep up (Oboe style).
     */
    private static int tuneLatency(AudioTrack track, int lastUnderruns, int framesPerBurst) {
        if (Build.VERSION.SDK_INT < 24) {
            return lastUnderruns;
        }

        int underruns = track.getUnderrunCount();

        if (underruns > lastUnderruns) {
            int size = track.getBufferSizeInFrames();
            int capacity = track.getBufferCapacityInFrames();
            if (size < capacity) {
                int newSize = track.setBufferSizeInFrames(Math.min(capacity, size + framesPerBurst));
                Log.d(TAG, "Karaoke output underrun, buffer %s -> %s frames", size, newSize);
            }
        }

        return underruns;
    }

    @SuppressWarnings("deprecation")
    private static AudioTrack createTrack(int framesPerBurst) {
        int channelMask = AudioFormat.CHANNEL_OUT_STEREO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minBufferBytes = AudioTrack.getMinBufferSize(RATE, channelMask, encoding);
        int bufferBytes = Math.max(minBufferBytes, FRAME * 4 * 4); // 4 frames of 16 bit stereo
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

        if (Build.VERSION.SDK_INT >= 24) {
            // Keep just two device periods queued (a regular track: four frames at least), tuneLatency() grows it if needed
            boolean isFast = Build.VERSION.SDK_INT >= 26 && track.getPerformanceMode() == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
            track.setBufferSizeInFrames(Math.max(framesPerBurst * 2, isFast ? FRAME * 2 : FRAME * 4));
        }

        Log.d(TAG, "Karaoke output buffer: %s bytes requested", bufferBytes);

        return track;
    }

    @RequiresApi(21)
    private static AudioAttributes createAttributes() {
        // Same stream as the video, so the remote's volume keys control both
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }
}
