package org.kelvinizer.android;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import org.kelvinizer.audio.HitMixer;
import org.kelvinizer.audio.MixerMusic;
import org.kelvinizer.audio.MusicOutput;
import org.kelvinizer.audio.SongClock;

/**
 * 手机上的打击音输出（安卓 8.0 起）：自己开一条低延迟音频流，一直放着（没有打击时放静音，声音输出不会睡着，
 * 每一下的延迟都一样短），由 {@link HitMixer} 按设备时间戳把每一下排到准确的一刻。游玩时的歌曲也由游戏自己解码，
 * 混在这同一条流里放（{@link #open}），音乐和打击音共用一个时钟。
 *
 * <p>Hit sound output on Android 8.0+: a low-latency {@link AudioTrack} at the device's own rate that runs all
 * the time (silence between hits, so the output never goes to standby and every hit has the same short delay),
 * filled block by block by {@link HitMixer} on an audio-priority thread. Blocks are placed by
 * {@link AudioTrack#getTimestamp}, polled every block until one arrives, then about once a second and afresh after
 * a stall (background, underrun). The queue starts at two device bursts and grows by one after an underrun.
 *
 * <p>It is also the gameplay song output ({@link MusicOutput}, Kelvin 2026-10-26 20:43): {@link #open} decodes the
 * song with {@link SongDecoder} and mixes it into this stream ({@link MixerMusic}), so music, chart and hit
 * sounds run on one clock. While a song is open the queue is at least {@value #SONG_BURSTS} bursts, as a gap in
 * music is heard where one between hits is not.
 */
@TargetApi(26)
final class AndroidHitOutput extends HitMixer implements Runnable, MusicOutput {
    private static final String TAG = "HitOutput";
    /** Smallest queue (in bursts) while a song plays. */
    private static final int SONG_BURSTS = 4;
    /** How long {@link #open} waits for a song's first decoded audio (ms). */
    private static final long DECODE_WAIT_MS = 2000L;
    private static final SongClock.TimeSource NANO_TIME = new SongClock.TimeSource() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    };

    private final AudioTrack track;
    private final int burst;
    private final Thread thread;
    private volatile boolean alive = true;
    /** Set when the frame-to-time link has broken (back from the background): take timestamps again at once. */
    private volatile boolean restamp;
    private long written;

    /** @return the output, or null below Android 8.0 or when the device refuses the stream */
    static AndroidHitOutput create(Context context) {
        if (Build.VERSION.SDK_INT < 26) return null;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int rate = property(am, AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE, 48000);
            int burst = property(am, AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER, 192);
            return new AndroidHitOutput(rate, burst);
        } catch (RuntimeException e) {
            Log.w(TAG, "no low-latency stream, hit sounds use SoundPool", e);
            return null;
        }
    }

    private static int property(AudioManager am, String key, int fallback) {
        try {
            int v = Integer.parseInt(am.getProperty(key));
            return v > 0 ? v : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private AndroidHitOutput(int rate, int burst) {
        super(rate);
        this.burst = burst;
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(Math.max(min, burst * 8 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("AudioTrack not initialised");
        }
        track.setBufferSizeInFrames(burst * 2);
        thread = new Thread(this, "kk-hits");
        thread.start();
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] block = new short[burst * 2];
        AudioTimestamp ts = new AudioTimestamp();
        int underruns = 0;
        int perSecond = Math.max(1, rate() / burst);
        int sinceStamp = perSecond;
        boolean stamped = false;
        long freshAfter = 0L; // a timestamp taken before this is stale (from before a stall)
        track.play();
        while (alive) {
            if (restamp) {
                restamp = false;
                stamped = false;
                freshAfter = System.nanoTime();
            }
            // a timestamp every block until the first real one, then about once a second (the docs advise polling
            // it sparingly once it runs; frames are counted here meanwhile)
            if (!stamped || ++sinceStamp >= perSecond) {
                sinceStamp = 0;
                if (track.getTimestamp(ts) && ts.framePosition > 0 && ts.nanoTime > freshAfter) {
                    setTimestamp(ts.framePosition, ts.nanoTime);
                    stamped = true;
                } else if (!stamped) {
                    setTimestamp(track.getPlaybackHeadPosition() & 0xffffffffL, System.nanoTime());
                }
            }
            mix(block, burst, written);
            int n = track.write(block, 0, block.length); // blocks while the queue is full (also while paused)
            if (n < 0) break;
            written += n / 2;
            int u = track.getUnderrunCount();
            if (u > underruns) {
                underruns = u;
                stamped = false; // the stream stalled: frames and time have parted
                freshAfter = System.nanoTime();
                int size = track.getBufferSizeInFrames() + burst;
                if (size <= track.getBufferCapacityInFrames()) track.setBufferSizeInFrames(size);
            }
        }
    }

    @Override
    public Music open(FileHandle file) {
        final SongDecoder d = SongDecoder.start(file, rate(), DECODE_WAIT_MS);
        if (d == null) return null;
        int size = Math.min(burst * SONG_BURSTS, track.getBufferCapacityInFrames());
        if (track.getBufferSizeInFrames() < size) track.setBufferSizeInFrames(size);
        return new MixerMusic(this, d.pcm(), new Runnable() {
            @Override
            public void run() {
                d.cancel();
            }
        }, NANO_TIME);
    }

    @Override
    public float startDelayMs() {
        return SONG_START_MS;
    }

    /** The app went to the background: the stream stops until {@link #resumeOutput()}. */
    void pauseOutput() {
        cancelPending();
        track.pause();
    }

    /** The app is back in front. */
    void resumeOutput() {
        restamp = true;
        track.play();
    }

    /** Stops the thread and releases the stream. */
    void dispose() {
        alive = false;
        track.pause();
        track.flush();
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!thread.isAlive()) track.release();
    }
}
