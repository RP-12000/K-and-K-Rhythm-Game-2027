package org.kelvinizer.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * 只读 WAV 文件的 RIFF 块头来得到时长，不读音频数据，桌面和 Android 上都能用。
 * 曲库和 {@link SongClock} 用它得到音乐的长度。
 *
 * <p>Reads a WAV file's duration by walking its RIFF chunk headers (no audio data is read).
 * Works on desktop and Android without {@code javax.sound.sampled}.
 *
 * <p>Robust against the problems of the original version: it never relies on
 * {@code InputStream.available()} (0 for some Android asset streams), honours RIFF pad
 * bytes after odd-sized chunks, loops until {@code skip} has really skipped, accepts a
 * {@code data} chunk that precedes {@code fmt }, and clamps a streamed/oversized data size
 * to the file length.
 */
public final class WavDurationUtil {

    private WavDurationUtil() {}

    /**
     * @return the duration in milliseconds
     * @throws IOException when the file is not a readable PCM/RIFF WAV
     */
    public static long getDurationMillis(FileHandle handle) throws IOException {
        long fileLength = -1;
        try {
            fileLength = handle.length();
        } catch (RuntimeException ignored) {
            // length unknown for some handle types
        }
        try (InputStream rawIn = handle.read();
             DataInputStream in = new DataInputStream(rawIn)) {
            return readDurationMillis(in, fileLength, handle.path());
        } catch (RuntimeException e) {
            throw new IOException("Cannot read WAV " + handle.path(), e);
        }
    }

    /**
     * Parses RIFF headers from a stream positioned at the start of the file (the stream is
     * not closed).
     *
     * @param fileLength total file length in bytes, or ≤ 0 when unknown
     * @param name       name used in error messages
     */
    public static long readDurationMillis(DataInputStream in, long fileLength, String name) throws IOException {
        byte[] header = new byte[12];
        in.readFully(header);
        if (!matches(header, 0, "RIFF") || !matches(header, 8, "WAVE")) {
            throw new IOException("Not a valid WAV file: " + name);
        }
        long offset = 12;
        long byteRate = 0;
        long dataSize = -1;
        byte[] chunk = new byte[8];
        byte[] fmt = new byte[16];
        try {
            while (byteRate == 0 || dataSize < 0) {
                in.readFully(chunk);
                offset += 8;
                long chunkSize = readIntLE(chunk, 4);
                long padded = chunkSize + (chunkSize & 1L);
                if (matches(chunk, 0, "fmt ")) {
                    if (chunkSize < 16) throw new IOException("Bad fmt chunk in " + name);
                    in.readFully(fmt);
                    byteRate = readIntLE(fmt, 8);
                    skipFully(in, padded - 16);
                } else if (matches(chunk, 0, "data")) {
                    dataSize = chunkSize;
                    if (fileLength > 0 && (dataSize > fileLength - offset || dataSize == 0xFFFFFFFFL)) {
                        dataSize = Math.max(0, fileLength - offset);
                    }
                    if (byteRate > 0) break;
                    skipFully(in, padded);
                } else {
                    skipFully(in, padded);
                }
                offset += padded;
            }
        } catch (EOFException eof) {
            // truncated file: use whatever was found
        }
        if (byteRate <= 0 || dataSize < 0) {
            throw new IOException("Cannot read WAV duration (fmt/data chunk missing): " + name);
        }
        return dataSize * 1000L / byteRate;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                // skip() may legally return 0; fall back to read() to detect EOF
                if (in.read() < 0) throw new EOFException();
                s = 1;
            }
            n -= s;
        }
    }

    private static boolean matches(byte[] b, int off, String tag) {
        for (int i = 0; i < 4; i++) {
            if (b[off + i] != (byte) tag.charAt(i)) return false;
        }
        return true;
    }

    private static long readIntLE(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16)
            | ((b[off + 3] & 0xFFL) << 24);
    }

    /** @return duration in milliseconds, or 0 when the file is missing or unreadable. */
    public static float getDurationMillisSafe(FileHandle handle) {
        if (handle == null) return 0f;
        try {
            if (!handle.exists()) return 0f;
            return getDurationMillis(handle);
        } catch (Exception e) {
            if (Gdx.app != null) Gdx.app.error("WavDurationUtil", "Failed: " + handle.path(), e);
            return 0f;
        }
    }
}
