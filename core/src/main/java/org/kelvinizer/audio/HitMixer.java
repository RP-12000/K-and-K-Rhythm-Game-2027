package org.kelvinizer.audio;

import java.util.Arrays;

/**
 * 打击音混音器：按设备报告的“哪一帧在什么时刻放出来”，把每一下放到正好在要求的时刻响的那一帧；
 * 已经来不及的就在下一段立刻响。同一个声音 {@link #MERGE_MS} 以内的几下只响一下（多押不叠成两声）。
 * 歌曲也混在同一条流里（{@link #songOpen}），音乐和打击音用同一个时钟，暂停、继续、跳转都对到采样。
 *
 * <p>Mixes hit sounds into a 16-bit stereo stream for a platform output thread. That thread calls
 * {@link #setTimestamp} with a recent (frame, nanoTime) pair of what the device is presenting, then
 * {@link #mix} for each block it writes; a request starts at the frame that is heard at its instant, or at the
 * block being written when that frame is already gone. Requests of one sound whose starts lie within
 * {@link #MERGE_MS} play once, at the louder volume: the hits of a chord sound together, not as a flam
 * (Kelvin 2026-10-26 16:12).
 *
 * <p>The song plays in the same stream ({@link #songOpen}, Kelvin 2026-10-26 20:43: after every pause the music
 * came back at a different place against the chart). It plays in segments: a {@link #songPlay play} starts one
 * at the stream frame heard {@link #SONG_START_MS} after the call (sooner is impossible: frames up to the block
 * being written are already queued), and a {@link #songPause pause} or {@link #songSeek seek} ends it with a
 * {@link #FADE_MS} fade so it does not click. {@link #songHeard} maps the frame heard at an instant back to the
 * song through the segment: it is the song's place in what is being heard, to the sample, not what was decoded
 * or written; before a segment sounds it stays at the segment's start. The stream itself never stops, so the
 * mapping holds across pauses. A song whose data is not decoded that far yet waits (the position holds).
 *
 * <p>{@link #load}, {@link #play}, {@link #cancelPending} and the {@code song…} methods may be called from one
 * other thread; requests are handed over under the mixer's lock. {@link #mix} allocates nothing once its buffer
 * has grown, and reads the song's data outside the lock.
 */
public class HitMixer implements HitOutput {
    /** Requests of one sound starting within this many ms of each other play once. */
    public static final float MERGE_MS = 20f;
    private static final int MAX_SOUNDS = 8;
    private static final int MAX_REQUESTS = 64;
    private static final int MAX_VOICES = 32;
    /** A {@link #songPlay play} is heard this long after the call, when the stream allows (ms). */
    public static final float SONG_START_MS = 100f;
    /** A song segment fades in and out over this long (ms). */
    public static final float FADE_MS = 5f;
    /** Above this level the mixed stream is rounded off softly instead of clipping hard (of full scale). */
    private static final float SOFT_KNEE = 0.89f * 32767f;

    private final int rate;
    private final short[][] sounds = new short[MAX_SOUNDS][]; // stereo interleaved at the mixer's rate
    private int soundCount;

    private final int[] rqSound = new int[MAX_REQUESTS];
    private final float[] rqVolume = new float[MAX_REQUESTS];
    private final long[] rqNanos = new long[MAX_REQUESTS];
    private int rqCount;
    private boolean cancel;

    // output thread only
    private final int[] vSound = new int[MAX_VOICES];
    private final float[] vVolume = new float[MAX_VOICES];
    private final long[] vStart = new long[MAX_VOICES];
    private int vCount;
    private long tsFrame;
    private long tsNanos;
    private boolean haveTimestamp;
    private float[] acc = new float[0];

    // song: the game thread's requests
    private PcmBuffer song;
    private boolean songWant;
    private long songPlayNanos;
    /** A pause or seek since the last block: the sounding segment ends. */
    private boolean songCut;
    /** A seek since the last segment started: the song's place is {@link #idleSong} until the next one sounds. */
    private boolean songSeeked;
    private float songVolTarget = 1f;
    private boolean songEnded;
    // song: the current segment (set by the output thread, read by queries; under the lock)
    private long segStream = -1L;
    private long segSong;
    private long segEnd = Long.MAX_VALUE;
    /** Where the next segment starts: the paused or sought place. */
    private long idleSong;
    // output thread only
    private float songVol;
    private final long fadeFrames;
    private final long startDelayNanos = (long) (SONG_START_MS * 1e6);

    /** @param rate the output's sample rate in Hz */
    public HitMixer(int rate) {
        this.rate = rate;
        fadeFrames = Math.max(1L, Math.round(FADE_MS * rate / 1000.0));
    }

    /** @return the output sample rate in Hz */
    public int rate() {
        return rate;
    }

    @Override
    public synchronized int load(short[] pcm, int channels, int srcRate) {
        if (pcm == null || (channels != 1 && channels != 2) || srcRate <= 0 || soundCount == MAX_SOUNDS) return -1;
        int inFrames = pcm.length / channels;
        if (inFrames == 0) return -1;
        // linear resampling to the output rate (the low-latency path needs the device's own rate)
        int outFrames = (int) Math.max(1L, (long) inFrames * rate / srcRate);
        short[] out = new short[outFrames * 2];
        double step = (double) srcRate / rate;
        for (int i = 0; i < outFrames; i++) {
            double p = i * step;
            int k = (int) p;
            float f = (float) (p - k);
            int k1 = Math.min(k + 1, inFrames - 1);
            for (int c = 0; c < 2; c++) {
                int ch = channels == 1 ? 0 : c;
                float a = pcm[k * channels + ch];
                float b = pcm[k1 * channels + ch];
                out[i * 2 + c] = (short) Math.round(a + (b - a) * f);
            }
        }
        sounds[soundCount] = out;
        return soundCount++;
    }

    @Override
    public synchronized void play(int id, float volume, long atNanos) {
        if (id < 0 || id >= soundCount || !(volume > 0f) || rqCount == MAX_REQUESTS) return;
        rqSound[rqCount] = id;
        rqVolume[rqCount] = Math.min(volume, 1f);
        rqNanos[rqCount] = atNanos;
        rqCount++;
    }

    @Override
    public synchronized void cancelPending() {
        rqCount = 0;
        cancel = true;
    }

    /** Output thread: frame {@code frame} of the stream is (or was) heard at {@code nanos}. */
    public synchronized void setTimestamp(long frame, long nanos) {
        tsFrame = frame;
        tsNanos = nanos;
        haveTimestamp = true;
    }

    /** @return the stream frame heard at {@code nanos}, by the last {@link #setTimestamp timestamp} */
    long frameAt(long nanos) {
        return tsFrame + Math.round((nanos - tsNanos) * (double) rate / 1e9);
    }

    // ------------------------------------------------------------------ song

    /** Makes {@code pcm} (stereo at {@link #rate()}) the song, paused at its start; replaces any other. */
    public synchronized void songOpen(PcmBuffer pcm) {
        song = pcm;
        songWant = false;
        songCut = false;
        songSeeked = false;
        songEnded = false;
        segStream = -1L;
        segEnd = Long.MAX_VALUE;
        idleSong = 0L;
    }

    /** Closes {@code pcm} if it is the song (another song opened since is left alone). */
    public synchronized void songClose(PcmBuffer pcm) {
        if (song != pcm) return;
        song = null;
        songWant = false;
        segStream = -1L;
    }

    /** Plays the song on from its place, heard {@link #SONG_START_MS} after {@code nanos} if the stream allows. */
    public synchronized void songPlay(long nanos) {
        if (song == null || songWant) return;
        songWant = true;
        songEnded = false;
        songPlayNanos = nanos;
    }

    /** Pauses the song where it is heard (after a short fade). */
    public synchronized void songPause() {
        if (!songWant) return;
        songWant = false;
        songCut = true;
    }

    /** Puts the song at {@code frame}; a playing song goes on from there as from a {@link #songPlay play} now. */
    public synchronized void songSeek(long frame, long nanos) {
        if (song == null) return;
        idleSong = Math.max(0L, frame);
        songSeeked = true;
        songCut = true;
        songEnded = false;
        songPlayNanos = nanos;
    }

    /** Sets the song's volume (0..1), reached over the next block. */
    public synchronized void songVolume(float v) {
        songVolTarget = Math.max(0f, Math.min(1f, v));
    }

    /** @return true between {@link #songPlay} and a pause or the song's end */
    public synchronized boolean songPlaying() {
        return songWant;
    }

    /** @return true once the song played to its end (until it is played or sought again) */
    public synchronized boolean songEnded() {
        return songEnded;
    }

    /** @return the song frame heard at {@code nanos} (its place while it does not sound) */
    public synchronized long songHeard(long nanos) {
        if (song == null) return 0L;
        if (segStream < 0L || songSeeked) return idleSong;
        if (!haveTimestamp) return segSong;
        long f = frameAt(nanos);
        if (f < segStream) return segSong;
        return segSong + (Math.min(f, segEnd) - segStream);
    }

    /**
     * Prologue of {@link #mix} for the song (under the lock): ends and starts segments for the block
     * {@code [first, end)}. @return the song to mix in this block, or null
     */
    private PcmBuffer songBlock(long first, long end) {
        PcmBuffer p = song;
        if (p == null) return null;
        boolean sounding = segStream >= 0L && segEnd > first;
        if (sounding && segEnd == Long.MAX_VALUE && (songCut || !songWant)) {
            if (segStream >= first) {
                // nothing of it is written yet: drop it
                if (!songSeeked) idleSong = segSong;
                segStream = -1L;
            } else {
                // fade out from where this block starts
                segEnd = first + fadeFrames;
                if (!songSeeked) idleSong = segSong + (segEnd - segStream);
            }
        }
        songCut = false;
        sounding = segStream >= 0L && segEnd > first;
        if (!sounding && songWant) {
            long avail = p.frames();
            boolean ready = p.done() ? avail > idleSong : avail - idleSong >= rate / 2;
            if (p.done() && idleSong >= avail) {
                songWant = false; // nothing left to play
                songEnded = true;
            } else if (ready) {
                long at = haveTimestamp ? frameAt(songPlayNanos + startDelayNanos) : first;
                segStream = Math.max(first, at);
                segSong = idleSong;
                segEnd = Long.MAX_VALUE;
                songSeeked = false;
                songVol = songVolTarget;
            }
        }
        if (segStream < 0L || segStream >= end || segEnd <= first) return null;
        // the data ends inside this block: the segment stops there (the song's end, or decoding is behind)
        long avail = p.frames();
        long dataEnd = segStream + (avail - segSong);
        if (dataEnd < end && dataEnd < segEnd) {
            segEnd = Math.max(dataEnd, first);
            idleSong = segSong + (segEnd - segStream);
            if (p.done()) {
                songWant = false;
                songEnded = true;
            }
        }
        return p;
    }

    /**
     * Output thread: mixes stream frames {@code [first, first + frames)} into {@code out} (stereo interleaved,
     * at least {@code frames * 2} long), silence where nothing plays.
     */
    public void mix(short[] out, int frames, long first) {
        long end = first + frames;
        PcmBuffer p;
        long sStream, sSong, sEnd;
        float vol0, vol1;
        synchronized (this) {
            if (cancel) {
                cancel = false;
                int w = 0;
                for (int v = 0; v < vCount; v++) {
                    if (vStart[v] < first) keepVoice(v, w++); // already sounding: let it ring out
                }
                vCount = w;
            }
            for (int r = 0; r < rqCount; r++) {
                long f = haveTimestamp ? frameAt(rqNanos[r]) : first;
                addVoice(rqSound[r], rqVolume[r], Math.max(f, first));
            }
            rqCount = 0;
            p = songBlock(first, end);
            sStream = segStream;
            sSong = segSong;
            sEnd = segEnd;
            vol0 = songVol;
            vol1 = songVol = songVolTarget;
        }
        int n = frames * 2;
        if (acc.length < n) acc = new float[n];
        Arrays.fill(acc, 0, n, 0f);
        if (p != null) mixSong(p, first, frames, sStream, sSong, sEnd, vol0, vol1);
        int w = 0;
        for (int v = 0; v < vCount; v++) {
            short[] s = sounds[vSound[v]];
            int len = s.length / 2;
            long from = Math.max(first, vStart[v]);
            float vol = vVolume[v];
            for (long fr = from; fr < end; fr++) {
                int k = (int) (fr - vStart[v]);
                if (k >= len) break;
                int o = (int) (fr - first) * 2;
                acc[o] += s[k * 2] * vol;
                acc[o + 1] += s[k * 2 + 1] * vol;
            }
            if (end - vStart[v] < len) keepVoice(v, w++);
        }
        vCount = w;
        for (int i = 0; i < n; i++) {
            float a = acc[i];
            float m = Math.abs(a);
            if (m > SOFT_KNEE) {
                // round off the top (music and hits together can pass full scale) instead of clipping hard
                float room = 32767f - SOFT_KNEE;
                a = Math.signum(a) * (SOFT_KNEE + room * (float) Math.tanh((m - SOFT_KNEE) / room));
            }
            out[i] = (short) (a > 32767f ? 32767 : a < -32768f ? -32768 : a);
        }
    }

    /** Adds the song's frames for stream frames {@code [first, first + frames)} of its segment to the block. */
    private void mixSong(PcmBuffer p, long first, int frames, long sStream, long sSong, long sEnd, float vol0,
                         float vol1) {
        long from = Math.max(first, sStream);
        long to = Math.min(first + frames, sEnd);
        float dv = (vol1 - vol0) / frames;
        for (long fr = from; fr < to; fr++) {
            long sf = sSong + (fr - sStream);
            float g = vol0 + dv * (fr - first);
            long in = fr - sStream;
            if (in < fadeFrames) g *= (float) in / fadeFrames;
            if (sEnd != Long.MAX_VALUE && sEnd - fr < fadeFrames) g *= (float) (sEnd - fr) / fadeFrames;
            short[] c = p.chunk(sf);
            int k = PcmBuffer.offset(sf);
            int o = (int) (fr - first) * 2;
            acc[o] += c[k] * g;
            acc[o + 1] += c[k + 1] * g;
        }
    }

    private void addVoice(int sound, float volume, long start) {
        long merge = (long) (MERGE_MS * rate / 1000f);
        for (int v = 0; v < vCount; v++) {
            if (vSound[v] == sound && Math.abs(vStart[v] - start) <= merge) {
                vVolume[v] = Math.max(vVolume[v], volume);
                return;
            }
        }
        if (vCount == MAX_VOICES) return;
        vSound[vCount] = sound;
        vVolume[vCount] = volume;
        vStart[vCount] = start;
        vCount++;
    }

    private void keepVoice(int from, int to) {
        vSound[to] = vSound[from];
        vVolume[to] = vVolume[from];
        vStart[to] = vStart[from];
    }
}
