package org.kelvinizer.android;

import android.annotation.TargetApi;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.backends.android.AndroidFileHandle;
import com.badlogic.gdx.files.FileHandle;
import org.kelvinizer.audio.Mp3Info;
import org.kelvinizer.audio.PcmBuffer;
import org.kelvinizer.audio.Resampler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * 在后台把一首歌解码成声音数据（系统自己的解码器 MediaExtractor + MediaCodec，和系统播放器用的是同一套，
 * 所以 MP3 开头结尾的裁剪也一样），转成混音器的采样率，边解边存。
 *
 * <p>Decodes a song on a background thread with the platform's own MediaExtractor and MediaCodec, configured
 * with the extractor's format exactly as the system player does (so an MP3's encoder delay and padding are
 * trimmed the same way and song times keep their meaning), converts it to the mixer's rate with
 * {@link Resampler} and appends it to a {@link PcmBuffer} that can be played while the rest is decoded. Mono is
 * doubled to stereo; only the first two channels of a wider file are kept.
 */
@TargetApi(26)
final class SongDecoder implements Runnable {
    private static final String TAG = "SongDecoder";
    private static final long DEQUEUE_US = 10_000L;

    private final FileHandle file;
    private final int outRate;
    private final PcmBuffer pcm = new PcmBuffer();
    private final Thread thread;
    private final Object lock = new Object();
    private volatile boolean cancelled;
    /** 0 still starting, 1 the first audio is decoded, −1 failed. */
    private int state;

    private SongDecoder(FileHandle file, int outRate) {
        this.file = file;
        this.outRate = outRate;
        thread = new Thread(this, "kk-decode");
    }

    /**
     * Starts decoding {@code file} to {@code outRate} and waits until its first audio is out.
     *
     * @return the running decoder, or null when the file cannot be decoded (or took longer than {@code waitMs})
     */
    static SongDecoder start(FileHandle file, int outRate, long waitMs) {
        SongDecoder d = new SongDecoder(file, outRate);
        d.thread.start();
        long until = SystemClock.uptimeMillis() + waitMs;
        synchronized (d.lock) {
            while (d.state == 0) {
                long left = until - SystemClock.uptimeMillis();
                if (left <= 0) break;
                try {
                    d.lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (d.state == 1) return d;
        }
        d.cancel();
        Log.w(TAG, "cannot decode " + file.path() + ": the system player plays it");
        return null;
    }

    /** @return the decoded song (growing while decoding runs) */
    PcmBuffer pcm() {
        return pcm;
    }

    /** Stops decoding (the song was closed). */
    void cancel() {
        cancelled = true;
    }

    private void signal(int s) {
        synchronized (lock) {
            if (state == 0) state = s;
            lock.notifyAll();
        }
    }

    @Override
    public void run() {
        long t0 = SystemClock.uptimeMillis();
        MediaExtractor ex = new MediaExtractor();
        AssetFileDescriptor afd = null;
        MediaCodec codec = null;
        boolean ok = false;
        try {
            if (file.type() == Files.FileType.Internal && file instanceof AndroidFileHandle) {
                afd = ((AndroidFileHandle) file).getAssetFileDescriptor();
                ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            } else if (file.type() == Files.FileType.Classpath) {
                throw new IOException("classpath file");
            } else {
                ex.setDataSource(file.file().getAbsolutePath());
            }
            MediaFormat format = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    ex.selectTrack(i);
                    format = f;
                    break;
                }
            }
            if (format == null) throw new IOException("no audio track");
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0); // the extractor's format: encoder delay/padding trimmed
            codec.start();
            ok = decode(ex, codec, format);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "decoding " + file.path() + " failed", e);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                    // already stopped or in error
                }
                codec.release();
            }
            ex.release();
            if (afd != null) {
                try {
                    afd.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
            pcm.finish(ok);
            signal(pcm.frames() > 0 ? 1 : -1);
        }
        if (ok) {
            // an MP3's header gives its length without the encoder delay and padding: equal lengths show the
            // decoder trimmed them as the system player does (song times unchanged)
            Mp3Info mp3 = Mp3Info.isMp3(file) ? Mp3Info.read(file) : null;
            String expect = mp3 != null && mp3.durationMs > 0f
                ? " (header: " + Math.round(mp3.durationMs / 1000.0 * outRate) + ")" : "";
            Log.i(TAG, "decoded " + file.name() + ": " + pcm.frames() + " frames at " + outRate + " Hz" + expect
                + " in " + (SystemClock.uptimeMillis() - t0) + " ms");
        }
    }

    /** @return true when the whole song was decoded */
    private boolean decode(MediaExtractor ex, MediaCodec codec, MediaFormat format) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        Resampler rs = null;
        short[] s16 = new short[0];
        float[] f32 = new float[0];
        float[] stereo = new float[0];
        boolean inEnd = false;
        while (!cancelled) {
            if (!inEnd) {
                int in = codec.dequeueInputBuffer(DEQUEUE_US);
                if (in >= 0) {
                    ByteBuffer b = codec.getInputBuffer(in);
                    int n = b != null ? ex.readSampleData(b, 0) : -1;
                    if (n < 0) {
                        codec.queueInputBuffer(in, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inEnd = true;
                    } else {
                        codec.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            int out = codec.dequeueOutputBuffer(info, DEQUEUE_US);
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                encoding = f.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    ? f.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                continue;
            }
            if (out < 0) continue;
            ByteBuffer b = codec.getOutputBuffer(out);
            if (b != null && info.size > 0 && channels > 0) {
                if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                    throw new IllegalStateException("PCM encoding " + encoding);
                }
                b.position(info.offset);
                b.limit(info.offset + info.size);
                ByteBuffer le = b.slice().order(ByteOrder.LITTLE_ENDIAN);
                int samples;
                if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
                    ShortBuffer sb = le.asShortBuffer();
                    samples = sb.remaining();
                    if (s16.length < samples) s16 = new short[samples];
                    sb.get(s16, 0, samples);
                    if (f32.length < samples) f32 = new float[samples];
                    for (int i = 0; i < samples; i++) f32[i] = s16[i] / 32768f;
                } else {
                    FloatBuffer fb = le.asFloatBuffer();
                    samples = fb.remaining();
                    if (f32.length < samples) f32 = new float[samples];
                    fb.get(f32, 0, samples);
                }
                int frames = samples / channels;
                if (stereo.length < frames * 2) stereo = new float[frames * 2];
                for (int i = 0; i < frames; i++) {
                    float l = f32[i * channels];
                    stereo[i * 2] = l;
                    stereo[i * 2 + 1] = channels > 1 ? f32[i * channels + 1] : l;
                }
                if (rs == null) rs = new Resampler(rate, outRate, pcm);
                rs.push(stereo, 0, frames);
                if (pcm.frames() > 0) signal(1);
            }
            codec.releaseOutputBuffer(out, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                if (rs != null) rs.finish();
                return true;
            }
        }
        return false;
    }
}
