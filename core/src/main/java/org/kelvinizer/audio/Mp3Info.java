package org.kelvinizer.audio;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * 只读 MP3 的文件头（第一帧、Xing/Info 头和 LAME 标签），不解码音频，得到时长和解码后开头多出来的延迟。
 * 电脑上 libGDX 的 MP3 解码会把 Info 帧当成一帧静音放出来，也不去掉编码器和解码器的延迟，所以音乐比原来的 WAV 晚；
 * 歌曲时钟用这里算出的延迟把它补回来。安卓按文件头里的粗目录跳转会跳偏，{@link #seekableCopy} 给恒定码率的文件
 * 做一份去掉目录标记的副本，让安卓按码率准确跳转。
 *
 * <p>Reads an MP3's header only (first frame, Xing / Info / VBRI header, LAME tag; no audio is
 * decoded) for its duration and for the delay a decoder adds before the first real sample.
 *
 * <p>Start delay of the decoded stream against the original audio, per platform:
 * <ul>
 *   <li><b>Desktop</b> (libGDX lwjgl3, JLayer): the Info frame is decoded as one frame of silence,
 *       and neither the encoder delay nor the decoder delay is trimmed, so the delay is
 *       {@code Info frame + encoder delay + 529}. Measured on marsh mally (ffmpeg / LAME, 44.1 kHz):
 *       2257 samples = 51.2 ms (1152 + 576 + 529), the same at the start and in the middle of the
 *       song when cross-correlated against the original WAV.</li>
 *   <li><b>Android</b> (MediaPlayer): the extractor skips the Info frame and the codec trims the
 *       decoder and encoder delays (gapless playback), so 0. Not measured on a device.</li>
 * </ul>
 */
public final class Mp3Info {
    /** Samples every MP3 decoder outputs before the first real one (synthesis filterbank). */
    public static final int DECODER_DELAY = 529;
    /** Encoder delay assumed when the file has no LAME tag (LAME and ffmpeg default). */
    public static final int DEFAULT_ENCODER_DELAY = 576;
    /** Bit of the Xing / Info flags word that says a table of contents follows. */
    private static final int TOC_FLAG = 4;
    /** Local folder of the copies made by {@link #seekableCopy}. */
    private static final String COPY_DIR = "cache/music/";

    private static final int[] RATES_V1 = {44100, 48000, 32000}, RATES_V2 = {22050, 24000, 16000},
        RATES_V25 = {11025, 12000, 8000};
    private static final int[] KBPS_V1_L3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},
        KBPS_V2_L3 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};

    /** Sample rate (Hz), samples per frame and the frame count of the Xing / VBRI header (0 = none). */
    public final int sampleRate, samplesPerFrame, frames;
    /** Encoder delay and padding from the LAME tag (samples; delay is {@link #DEFAULT_ENCODER_DELAY} without one). */
    public final int encoderDelay, encoderPadding;
    /** True when the first frame is a Xing / Info / VBRI header frame rather than audio. */
    public final boolean infoFrame;
    /** Length of the original audio (ms). */
    public final float durationMs;
    /** File offset of the flags word of a constant-bitrate ("Info") header with a table of contents, else −1. */
    final long tocFlagsAt;

    private Mp3Info(int sampleRate, int samplesPerFrame, int frames, int encoderDelay, int encoderPadding,
                    boolean infoFrame, float durationMs, long tocFlagsAt) {
        this.sampleRate = sampleRate;
        this.samplesPerFrame = samplesPerFrame;
        this.frames = frames;
        this.encoderDelay = encoderDelay;
        this.encoderPadding = encoderPadding;
        this.infoFrame = infoFrame;
        this.durationMs = durationMs;
        this.tocFlagsAt = tocFlagsAt;
    }

    /** @return true for a {@code .mp3} file. */
    public static boolean isMp3(FileHandle f) {
        return f != null && "mp3".equalsIgnoreCase(f.extension());
    }

    /** @return how much later than the original audio the decoded stream starts on {@code android} or desktop (ms). */
    public float startDelayMs(boolean android) {
        if (android) return 0f;
        int samples = (infoFrame ? samplesPerFrame : 0) + encoderDelay + DECODER_DELAY;
        return samples * 1000f / sampleRate;
    }

    /** @return the start delay of {@code f} on this platform (ms), 0 for other formats or an unreadable file. */
    public static float startDelayMs(FileHandle f) {
        if (!isMp3(f)) return 0f;
        Mp3Info i = read(f);
        boolean android = Gdx.app != null && Gdx.app.getType() == Application.ApplicationType.Android;
        return i != null ? i.startDelayMs(android) : 0f;
    }

    /** @return the length of {@code f} in ms, or 0 when unknown. */
    public static float durationMs(FileHandle f) {
        Mp3Info i = isMp3(f) ? read(f) : null;
        return i != null ? i.durationMs : 0f;
    }

    /**
     * Android seeks an MP3 that has a table of contents (Xing / Info header) by that table, in steps of 1/256 of the
     * file: the music lands up to about a second away from the time the player then reports, and the song clock
     * follows that report (Kelvin 2026-10-21: after a pause the music was still off). A constant-bitrate file ("Info")
     * seeks exactly by its bitrate once the table's flag is cleared; the header keeps its frame count and LAME tag.
     *
     * @return a copy of {@code f} with that flag cleared in local storage (written once, then reused while its bytes
     *         match), or {@code f} itself when it has no such table or the copy cannot be made
     */
    public static FileHandle seekableCopy(FileHandle f) {
        Mp3Info i = isMp3(f) ? read(f) : null;
        if (i == null || i.tocFlagsAt < 0) return f;
        try {
            byte[] b = f.readBytes();
            int at = (int) i.tocFlagsAt + 3; // low byte of the big-endian flags word
            if (at >= b.length) return f;
            b[at] &= ~TOC_FLAG;
            FileHandle copy = Gdx.files.local(COPY_DIR + copyName(f));
            if (copy.length() != b.length || !Arrays.equals(copy.readBytes(), b)) copy.writeBytes(b, false);
            return copy;
        } catch (RuntimeException e) {
            if (Gdx.app != null) Gdx.app.error("Mp3Info", "No seekable copy of " + f.path() + ": " + e);
            return f;
        }
    }

    /** The copy's file name: the ASCII letters and digits of the song's name, then a hash of its whole path. */
    private static String copyName(FileHandle f) {
        String name = f.nameWithoutExtension();
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < name.length() && sb.length() < 40; k++) {
            char c = name.charAt(k);
            sb.append(c < 128 && Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.append('-').append(Integer.toHexString(f.path().hashCode())).append(".mp3").toString();
    }

    /** @return the header info of {@code f}, or null when it is not a readable MP3. Never throws. */
    public static Mp3Info read(FileHandle f) {
        InputStream in = null;
        try {
            in = f.read();
            byte[] head = new byte[10];
            if (readFully(in, head, 10) < 10) return null;
            long skipped = 0;
            byte[] buf;
            if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                long tag = ((head[6] & 0x7F) << 21) | ((head[7] & 0x7F) << 14) | ((head[8] & 0x7F) << 7)
                    | (head[9] & 0x7F);
                if ((head[5] & 0x10) != 0) tag += 10; // footer
                skipFully(in, tag);
                skipped = 10 + tag;
                buf = new byte[8192];
                int n = readFully(in, buf, buf.length);
                return parse(buf, n, skipped, f.length());
            }
            buf = new byte[8192];
            System.arraycopy(head, 0, buf, 0, 10);
            int n = 10 + readFully(in, buf, 10, buf.length - 10);
            return parse(buf, n, 0, f.length());
        } catch (IOException | RuntimeException e) {
            if (Gdx.app != null) Gdx.app.error("Mp3Info", "Cannot read " + f.path() + ": " + e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    /**
     * Parses the first frame found in {@code b[0..n)}; {@code base} is the file offset of b[0]. Package-private for
     * tests.
     */
    static Mp3Info parse(byte[] b, int n, long base, long fileLength) {
        for (int i = 0; i + 4 <= n; i++) {
            if ((b[i] & 0xFF) != 0xFF || (b[i + 1] & 0xE0) != 0xE0) continue;
            int version = (b[i + 1] >> 3) & 3, layer = (b[i + 1] >> 1) & 3;
            int brIdx = (b[i + 2] >> 4) & 0xF, srIdx = (b[i + 2] >> 2) & 3, pad = (b[i + 2] >> 1) & 1;
            if (version == 1 || layer != 1 || brIdx == 0 || brIdx == 15 || srIdx == 3) continue; // Layer III only
            boolean v1 = version == 3, mono = ((b[i + 3] >> 6) & 3) == 3;
            int rate = (v1 ? RATES_V1 : version == 2 ? RATES_V2 : RATES_V25)[srIdx];
            int kbps = (v1 ? KBPS_V1_L3 : KBPS_V2_L3)[brIdx];
            int spf = v1 ? 1152 : 576;
            int frameLen = (v1 ? 144 : 72) * kbps * 1000 / rate + pad;
            // a real frame is followed by another one: rejects stray 0xFFE bytes in leftover tag data
            int next = i + frameLen;
            if (next + 1 < n && ((b[next] & 0xFF) != 0xFF || (b[next + 1] & 0xE0) != 0xE0)) continue;

            int frames = 0, delay = DEFAULT_ENCODER_DELAY, padding = 0;
            boolean info = false;
            long tocAt = -1;
            int x = i + 4 + (v1 ? (mono ? 17 : 32) : (mono ? 9 : 17));
            if (x + 8 <= n && (tag(b, x, "Xing") || tag(b, x, "Info"))) {
                info = true;
                int flags = int32(b, x + 4), p = x + 8;
                if ((flags & TOC_FLAG) != 0 && tag(b, x, "Info")) tocAt = base + x + 4;
                if ((flags & 1) != 0 && p + 4 <= n) {
                    frames = int32(b, p);
                    p += 4;
                }
                if ((flags & 2) != 0) p += 4;
                if ((flags & 4) != 0) p += 100;
                if ((flags & 8) != 0) p += 4;
                // LAME tag: 9-byte encoder name, then the delay / padding (12 bits each) 21 bytes in
                if (p + 24 <= n && b[p] >= 'A' && b[p] <= 'Z') {
                    delay = ((b[p + 21] & 0xFF) << 4) | ((b[p + 22] & 0xFF) >> 4);
                    padding = ((b[p + 22] & 0x0F) << 8) | (b[p + 23] & 0xFF);
                }
            } else if (i + 4 + 32 + 18 <= n && tag(b, i + 4 + 32, "VBRI")) {
                info = true;
                int v = i + 4 + 32;
                delay = ((b[v + 6] & 0xFF) << 8) | (b[v + 7] & 0xFF);
                frames = int32(b, v + 14);
            }
            float dur;
            if (frames > 0) {
                long samples = (long) frames * spf - delay - padding;
                dur = Math.max(0L, samples) * 1000f / rate;
            } else {
                long audioBytes = fileLength - base - i - (info ? frameLen : 0);
                dur = kbps > 0 ? audioBytes * 8f / kbps : 0f;
            }
            return new Mp3Info(rate, spf, frames, delay, padding, info, dur, tocAt);
        }
        return null;
    }

    private static boolean tag(byte[] b, int at, String s) {
        for (int k = 0; k < 4; k++) if (b[at + k] != s.charAt(k)) return false;
        return true;
    }

    private static int int32(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    private static int readFully(InputStream in, byte[] b, int len) throws IOException {
        return readFully(in, b, 0, len);
    }

    private static int readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int r = in.read(b, off + total, len - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) return;
                s = 1;
            }
            n -= s;
        }
    }
}
