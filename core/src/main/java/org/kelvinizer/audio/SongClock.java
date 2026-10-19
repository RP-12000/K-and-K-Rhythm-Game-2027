package org.kelvinizer.audio;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Disposable;

/**
 * 以音频播放位置为准的歌曲时钟：在音频锚点之间用 {@link System#nanoTime()} 插值，处理开场留白、暂停和恢复。
 * 游玩中从不跳转音频，而是让时钟向音频位置校正；输入事件的时间戳用 {@link #timeAtNanos(long)} 换算成歌曲时间。
 *
 * <p>Audio-anchored song clock (ARCHITECTURE §6). One instance is owned by the gameplay engine;
 * {@link AudioService#playFile} owns another for calibration.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Song time is interpolated with {@link System#nanoTime()} from an anchor
 *       {@code (anchorSongMs, anchorNanos)}: {@code t = anchorSongMs + (now − anchorNanos) / 1e6}.</li>
 *   <li>During the lead-in (t &lt; 0) time runs from nanoTime alone; when t crosses 0,
 *       {@link #update()} starts the music. An MP3 on desktop starts {@link #audioDelayMs()}
 *       late (see {@link Mp3Info}), so it is started that much earlier and every audio position
 *       is shifted by it. A device whose player takes a while to start sounding (Android) is
 *       started {@link #setStartLeadMs its measured start latency} earlier too, so the first
 *       audio position agrees with the clock instead of pulling it back (a visible hitch);
 *       {@link #measuredStartLatencyMs()} reports each song start's latency, taken once the
 *       reported position has settled ({@link #SETTLE_MS}). With no latency known yet (the first
 *       song on a device) the lead-in plays the stream muted for that long to measure it, then
 *       rewinds, so the first song starts as smoothly as the later ones. A start with a known lead
 *       does not follow the still-settling positions of its first second, only a gross error.</li>
 *   <li>The stream is rewound with pause + seek, never stop(): Android's stop() unprepares the
 *       player and the next play() would prepare it again on the GL thread just as the song
 *       starts. A desktop MP3 is decoded ahead once at load ({@link #load(FileHandle, float)}).</li>
 *   <li>While playing, every {@link #update()} nudges the anchor toward
 *       {@code music.getPosition()} (only when the reported position changed) with gain
 *       {@link #NUDGE_GAIN}, and re-anchors hard when the drift exceeds {@link #HARD_RESYNC_MS}.
 *       <b>Audio is never seeked during play.</b></li>
 *   <li>{@link #nowMs()} never goes backwards while running (after a backward resync it holds
 *       until the audio catches up); {@link #timeAtNanos(long)} maps input-event timestamps
 *       ({@code Gdx.input.getCurrentEventTime()}, same nanoTime base) onto the raw
 *       audio-anchored timeline.</li>
 *   <li>Paused: time is frozen and the music paused. Seeking is only done on explicit state
 *       changes ({@link #prepare}, {@link #seek}, a resume on Android).</li>
 *   <li>Resumed: the clock stays at the frozen time until the reported position moves (at most
 *       {@link #MAX_START_LEAD_MS}), so the chart does not run ahead while the player starts
 *       sounding again. On Android the song is first seeked to the frozen time: MediaPlayer's own
 *       clock runs on from a plain resume while the sound takes a while to come back, and some
 *       devices never win that time back, so every pause left the music later than the chart
 *       (owner 2026-10-15). After a seek its position holds until the sound really starts.</li>
 *   <li>The audio position is only trusted while {@code music.isPlaying()}: without an audio
 *       device, or after the stream ended, time keeps running on nanoTime.</li>
 * </ul>
 * User offsets are applied by the caller: {@code judgeMs = timeAtNanos(event) − audioOffsetMs}.
 *
 * <p>All per-frame methods are allocation-free.
 */
public final class SongClock implements Disposable {
    /** Fraction of the audio/clock error corrected per position change. */
    public static final double NUDGE_GAIN = 0.1;
    /** Drift beyond which the clock re-anchors to the audio position at once. */
    public static final double HARD_RESYNC_MS = 60.0;
    /** Desktop OpenAL ignores seeks before the first play(); a mismatch above this re-applies them. */
    private static final double SEEK_CHECK_MS = 30.0;
    /** Largest start lead and start latency accepted (ms). */
    public static final float MAX_START_LEAD_MS = 400f;
    /** A start's latency is taken this long after play(), once the reported position has settled (ms). */
    public static final double SETTLE_MS = 1000.0;
    /** In the settle window of a start with a known lead, only an error beyond this re-anchors (ms). */
    public static final double SETTLE_GROSS_MS = 250.0;
    /** A muted pre-roll needs this much lead-in left before the song starts (ms). */
    public static final double PREROLL_NEED_MS = 1800.0;

    /** Source of nanosecond timestamps; replaceable for tests. */
    public interface TimeSource {
        /** @return a monotonic timestamp in ns on the same base as input event times. */
        long nanoTime();
    }

    private static final TimeSource SYSTEM_TIME = new TimeSource() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    };

    private TimeSource time = SYSTEM_TIME;
    private Music music;
    private boolean ownsMusic;
    private float durationMs;
    /** The decoded stream starts this much later than the song (MP3 on desktop, see {@link Mp3Info}): audio ms = song ms + this. */
    private float audioDelayMs;

    private boolean running;
    private boolean musicStarted;
    private double anchorSongMs;
    private long anchorNanos;
    private double lastAudioMs = -1;
    private double lastReturnedMs;
    private float pendingSeekMs = -1f;
    private boolean monotonic = true;
    /** The stream was started since it was loaded (a rewind then needs pause + seek). */
    private boolean streamUsed;
    /** How much earlier than its song time the music is started (ms): the device's start latency. */
    private float startLeadMs;
    /** The song start being measured: nanoTime and audio position at its play(), and the result. */
    private boolean measuringStart;
    private long playNanos;
    private double playAudioMs;
    private float measuredStartMs = Float.NaN;
    /** Until this nanoTime the start's positions are still settling (only a gross error re-anchors). */
    private long settleEndNanos;
    /** Muted pre-roll measuring the start latency in the lead-in: 0 not yet, 1 playing, 2 done. */
    private int preroll;
    private long prerollNanos;
    private float prerollPos;
    private float prerollLeadMs = Float.NaN;

    /** Re-seek a resumed song to the frozen time: −1 = by platform (Android), 0 = never, 1 = always. */
    private int reseekMode = -1;
    /** After a resume: the clock waits until the reported position leaves {@link #resumeAudioMs}. */
    private boolean resumeHold;
    private long resumeNanos;
    private double resumeAudioMs;

    private float volume = 1f;
    private float fadeFrom;
    private float fadeTo;
    private long fadeStartNanos;
    private long fadeDurNanos;
    private boolean fading;

    /** Creates an empty clock; call {@link #load(FileHandle)} or {@link #attach} before use. */
    public SongClock() {
    }

    /** Replaces the time source (tests only). Pass null to restore {@link System#nanoTime()}. */
    public void setTimeSource(TimeSource source) {
        time = source != null ? source : SYSTEM_TIME;
    }

    /**
     * Loads a music file (disposing the previous one) and prepares at 0 ms, paused. The
     * duration comes from the WAV or MP3 header, else it is 0 (unknown).
     *
     * @return false when the music could not be created (the clock still runs on nanoTime)
     */
    public boolean load(FileHandle file) {
        float dur = 0f;
        if (file != null && "wav".equalsIgnoreCase(file.extension())) {
            dur = WavDurationUtil.getDurationMillisSafe(file);
        } else if (Mp3Info.isMp3(file)) {
            dur = Mp3Info.durationMs(file);
        }
        return load(file, dur);
    }

    /**
     * Loads a music file with a known duration (e.g. from {@code SongMeta.durationMs()}).
     *
     * @return false when the music could not be created
     */
    public boolean load(FileHandle file, float knownDurationMs) {
        disposeMusic();
        Music m = null;
        if (file != null) {
            try {
                m = Gdx.audio.newMusic(file);
            } catch (RuntimeException e) {
                if (Gdx.app != null) Gdx.app.error("SongClock", "Cannot load music " + file.path(), e);
            }
        }
        attach(m, knownDurationMs, true);
        // MP3 on desktop starts late (Info frame, encoder and decoder delay): play it that much earlier
        if (m != null) audioDelayMs = Mp3Info.startDelayMs(file);
        if (m != null && audioDelayMs > 0f) primeStream();
        return m != null;
    }

    /**
     * Uses an existing {@link Music} (tests, calibration). Prepares at 0 ms, paused.
     *
     * @param takeOwnership true to dispose it in {@link #dispose()}
     */
    public void attach(Music m, float durationMs, boolean takeOwnership) {
        if (m != music) disposeMusic();
        music = m;
        ownsMusic = takeOwnership;
        this.durationMs = Math.max(0f, durationMs);
        audioDelayMs = 0f;
        streamUsed = false;
        measuredStartMs = Float.NaN;
        preroll = 0;
        prerollLeadMs = Float.NaN;
        if (music != null) music.setVolume(volume);
        prepare(0f);
    }

    /**
     * Desktop MP3: the first play() decodes the stream's first buffers (Java decoder) on the GL
     * thread. Doing that now, silently, and rewinding leaves the buffers queued, so the song's
     * play() only resumes the source.
     */
    private void primeStream() {
        try {
            music.setVolume(0f);
            music.play();
            music.pause();
            music.setPosition(0f);
        } catch (RuntimeException e) {
            if (Gdx.app != null) Gdx.app.error("SongClock", "prime: " + e.getMessage());
        }
        music.setVolume(volume);
    }

    /**
     * Starts each song's music {@code ms} earlier than its song time: the device's measured start
     * latency (DeviceSettings), 0..{@value #MAX_START_LEAD_MS} ms.
     */
    public void setStartLeadMs(float ms) {
        startLeadMs = Float.isNaN(ms) ? 0f : Math.max(0f, Math.min(MAX_START_LEAD_MS, ms));
    }

    /**
     * @return the last song start's latency (ms from play() until the music sounds: the time
     *         since play() less the position reached, {@link #SETTLE_MS} after play()), or NaN
     *         when no start was measured since the music loaded
     */
    public float measuredStartLatencyMs() {
        return measuredStartMs;
    }

    /**
     * Overrides whether a resume seeks the song to the frozen time first (by default only on
     * Android, see the class notes). For tests.
     */
    public void setReseekOnResume(boolean on) {
        reseekMode = on ? 1 : 0;
    }

    private boolean reseekOnResume() {
        if (reseekMode >= 0) return reseekMode == 1;
        return Gdx.app != null && Gdx.app.getType() == Application.ApplicationType.Android;
    }

    /** @return the attached music, or null. */
    public Music music() {
        return music;
    }

    /**
     * Sets the start time (negative = lead-in) and stops, rewinding the audio. Call
     * {@link #start()} to run.
     */
    public void prepare(float startMs) {
        endPreroll(false);
        measuringStart = false;
        running = false;
        musicStarted = false;
        fading = false;
        anchorSongMs = startMs;
        anchorNanos = time.nanoTime();
        lastReturnedMs = startMs;
        lastAudioMs = -1;
        pendingSeekMs = -1f;
        resumeHold = false;
        if (music != null) {
            if (streamUsed && startMs <= 0f) {
                music.pause();
                music.setPosition(0f);
            }
            if (startMs > 0f) {
                pendingSeekMs = startMs;
                music.setPosition((startMs + audioDelayMs) / 1000f); // honoured on Android; desktop re-applies after play()
            }
        }
    }

    /** Starts (or continues) running from the current time. */
    public void start() {
        if (running) return;
        running = true;
        anchorNanos = time.nanoTime();
        float lead = lead();
        if (anchorSongMs >= -audioDelayMs - lead && !musicStarted) {
            // A pause can land in the frame the lead-in crossed 0, after the input pass and before
            // update() started the stream; the frozen time is then a few ms past 0 while the stream
            // will start at its pending seek (or 0). Anchor there, exactly as update() does at the
            // crossing, so clock and audio agree from the first resumed frame (nowMs() holds the
            // frozen value until real time passes it, so time never goes back).
            if (music != null) anchorSongMs = (pendingSeekMs > 0f ? pendingSeekMs : -audioDelayMs) - lead;
            startMusic();
        } else if (musicStarted && music != null) {
            // Android: put the music where the chart froze; the seek also rebuilds MediaPlayer's
            // timing, which a plain resume leaves running ahead of the sound (class notes)
            double target = anchorSongMs + audioDelayMs;
            if (reseekOnResume() && !music.isLooping() && target >= 0.0 && (durationMs <= 0f || target < durationMs)) {
                music.setPosition((float) (target / 1000.0));
            }
            music.play();
            lastAudioMs = audioMs();
            resumeHold = music.isPlaying(); // no audio device: nothing to wait for
            resumeNanos = anchorNanos;
            resumeAudioMs = lastAudioMs;
        }
    }

    /** Freezes time and pauses the music. */
    public void pause() {
        if (!running) return;
        anchorSongMs = nowMs();
        anchorNanos = time.nanoTime();
        running = false;
        resumeHold = false;
        endPreroll(false); // measured again if enough lead-in is left after the resume
        measuringStart = false;
        if (music != null && musicStarted) music.pause();
    }

    /** Resumes after {@link #pause()} from the frozen time. */
    public void resume() {
        start();
    }

    /**
     * Jumps to {@code ms} (an explicit seek, e.g. retry or calibration restart). Keeps the
     * running state. Never call it to correct drift.
     */
    public void seek(float ms) {
        boolean wasRunning = running;
        pause();
        if (ms < 0f || music == null) {
            prepare(ms);
        } else {
            anchorSongMs = ms;
            lastReturnedMs = ms;
            lastAudioMs = -1;
            if (musicStarted) {
                music.setPosition((ms + audioDelayMs) / 1000f);
            } else {
                pendingSeekMs = ms;
                music.setPosition((ms + audioDelayMs) / 1000f);
            }
        }
        if (wasRunning) start();
    }

    /**
     * Advances the clock state once per frame: starts the music when the lead-in crosses 0,
     * folds the reported audio position into the anchor and runs volume fades.
     */
    public void update() {
        long now = time.nanoTime();
        if (fading) updateFade(now);
        if (!running) {
            // Android auto-resumes music on app resume (also a pre-roll it paused); keep it paused
            // while the clock is.
            if (music != null && music.isPlaying()) music.pause();
            return;
        }
        double t = anchorSongMs + (now - anchorNanos) / 1e6;
        if (!musicStarted) {
            float lead = lead();
            if (t < -audioDelayMs - lead) {
                if (music != null && preroll != 2) updatePreroll(now, t);
            } else {
                // The stream starts at position 0 now, so anchor song time 0 here: audio and clock
                // agree from the first frame instead of the clock leading by up to one frame until
                // the nudges converge. nowMs() returned < 0 last frame, so time never goes back.
                // Without music there is nothing to align with: keep the nanoTime timeline exact.
                // Started its start latency early, the stream should begin sounding exactly at -delay.
                anchorSongMs = music != null ? -audioDelayMs - lead : t;
                anchorNanos = now;
                startMusic();
            }
            return;
        }
        if (resumeHold && music != null) {
            // Resumed: stay at the frozen time until the reported position moves (the music sounds
            // again), then take that position. Running meanwhile would put the chart ahead of the
            // music by the player's start latency until the nudges pulled it back.
            double a = audioMs();
            if (a == resumeAudioMs) {
                if (now - resumeNanos < (long) (MAX_START_LEAD_MS * 1e6)) {
                    anchorNanos = now;
                    return;
                }
                resumeHold = false; // never moved: run on nanoTime, the nudges take over once it plays
            } else {
                resumeHold = false;
                if (music.isPlaying()) {
                    anchorSongMs = a;
                    anchorNanos = now;
                    lastAudioMs = a;
                    return;
                }
            }
        }
        // No music, no audio device, or the stream finished (position resets to 0 on desktop):
        // keep running on nanoTime alone so the chart can still reach its end.
        if (music == null || !music.isPlaying()) return;
        double audioMs = audioMs();
        if (audioMs == lastAudioMs) return;
        lastAudioMs = audioMs;
        if (measuringStart && (now - playNanos) / 1e6 >= SETTLE_MS) {
            measuringStart = false;
            float lat = plausibleLatency((now - playNanos) / 1e6 - (audioMs - playAudioMs));
            if (!Float.isNaN(lat)) measuredStartMs = lat;
        }
        double err = audioMs - t;
        anchorSongMs = t;
        anchorNanos = now;
        if (now < settleEndNanos) {
            // Android reports a settling position for a while after play(); the lead already lines
            // the sound up with the clock, so following it would only make the first notes judder
            if (Math.abs(err) > SETTLE_GROSS_MS) anchorSongMs = audioMs;
            return;
        }
        if (Math.abs(err) > HARD_RESYNC_MS) anchorSongMs = audioMs;
        else anchorSongMs += NUDGE_GAIN * err;
    }

    private void startMusic() {
        musicStarted = true;
        if (music == null) return;
        endPreroll(true);
        music.setVolume(volume);
        music.play();
        streamUsed = true;
        if (pendingSeekMs > 0f) {
            if (Math.abs(audioMs() - pendingSeekMs) > SEEK_CHECK_MS) {
                music.setPosition((pendingSeekMs + audioDelayMs) / 1000f);
            }
            pendingSeekMs = -1f;
        }
        // Wait for the position to move before correcting (start latency), and measure that wait.
        lastAudioMs = audioMs();
        playAudioMs = lastAudioMs;
        playNanos = time.nanoTime();
        measuringStart = true;
        settleEndNanos = lead() > 0f ? playNanos + (long) (SETTLE_MS * 1e6) : playNanos;
    }

    /** The start lead in use: the stored latency, else this stream's pre-roll measurement, else 0. */
    private float lead() {
        if (music == null) return 0f;
        if (startLeadMs > 0f) return startLeadMs;
        return Float.isNaN(prerollLeadMs) ? 0f : prerollLeadMs;
    }

    /**
     * No start latency known yet: plays the stream muted in the lead-in until its position has
     * settled, takes the latency as for a song start, then rewinds ({@link #endPreroll}).
     */
    private void updatePreroll(long now, double t) {
        if (preroll == 0) {
            if (startLeadMs > 0f || fading || t > -audioDelayMs - PREROLL_NEED_MS) return;
            prerollPos = music.getPosition();
            music.setVolume(0f);
            music.play();
            streamUsed = true;
            prerollNanos = now;
            preroll = 1;
            return;
        }
        double el = (now - prerollNanos) / 1e6;
        if (el < SETTLE_MS) return;
        prerollLeadMs = plausibleLatency(el - (music.getPosition() - prerollPos) * 1000.0);
        endPreroll(true);
    }

    /** A start latency (ms) if plausible, at least 1 so a measured device counts as known; else NaN. */
    private static float plausibleLatency(double lat) {
        return lat >= -20.0 && lat <= MAX_START_LEAD_MS ? (float) Math.max(1.0, lat) : Float.NaN;
    }

    /** Stops a running pre-roll and rewinds to where it began; {@code done}: not run again for this stream. */
    private void endPreroll(boolean done) {
        if (preroll != 1) return;
        preroll = done ? 2 : 0;
        try {
            music.pause();
            music.setPosition(prerollPos);
        } catch (RuntimeException e) {
            if (Gdx.app != null) Gdx.app.error("SongClock", "pre-roll: " + e.getMessage());
        }
    }

    /** The music position on the song timeline (ms): the reported position minus the decoder's start delay. */
    private double audioMs() {
        return music.getPosition() * 1000.0 - audioDelayMs;
    }

    /** @return how much later than the song the decoded music starts (ms; MP3 on desktop, else 0). */
    public float audioDelayMs() {
        return audioDelayMs;
    }

    /**
     * @return the song time in ms at this instant (smoothed, audio-anchored); never decreases
     *         while running unless {@link #setMonotonic(boolean)} disabled it
     */
    public double nowMs() {
        double raw = running ? anchorSongMs + (time.nanoTime() - anchorNanos) / 1e6 : anchorSongMs;
        if (monotonic && running) {
            if (raw < lastReturnedMs) raw = lastReturnedMs;
            lastReturnedMs = raw;
        } else {
            lastReturnedMs = raw;
        }
        return raw;
    }

    /**
     * The clock's current time source reading (System.nanoTime unless a test replaced it), the
     * base that {@link #timeAtNanos(long)} expects. Input code compares event timestamps
     * against this rather than against System.nanoTime directly.
     */
    public long nanoTime() {
        return time.nanoTime();
    }

    /**
     * Maps an input-event timestamp (System.nanoTime base, e.g.
     * {@code Gdx.input.getCurrentEventTime()}) to song time on the audio-anchored timeline.
     * While paused this returns the frozen time.
     */
    public double timeAtNanos(long nanos) {
        if (!running) return anchorSongMs;
        return anchorSongMs + (nanos - anchorNanos) / 1e6;
    }

    /**
     * Enables the never-backwards guard of {@link #nowMs()} (default on). Looping playback
     * (calibration verify) turns it off so time can wrap with the audio.
     */
    public void setMonotonic(boolean monotonic) {
        this.monotonic = monotonic;
    }

    /** @return music length in ms (0 when unknown). */
    public float durationMs() {
        return durationMs;
    }

    /** @return true while time advances (lead-in or playing). */
    public boolean isRunning() {
        return running;
    }

    /** @return true once the music has been started for this play-through (t reached 0). */
    public boolean isMusicStarted() {
        return musicStarted;
    }

    /** @return true when the underlying music reports playing. */
    public boolean isPlaying() {
        return music != null && music.isPlaying();
    }

    /** @return true when a music stream is attached. */
    public boolean hasMusic() {
        return music != null;
    }

    /** Sets the music volume (0..1) immediately and cancels a running fade. */
    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        fading = false;
        if (music != null && preroll != 1) music.setVolume(volume);
    }

    /** @return the current music volume. */
    public float volume() {
        return volume;
    }

    /** Fades the music volume to {@code target} over {@code seconds} (driven by {@link #update()}). */
    public void fadeTo(float target, float seconds) {
        if (seconds <= 0f) {
            setVolume(target);
            return;
        }
        fadeFrom = volume;
        fadeTo = Math.max(0f, Math.min(1f, target));
        fadeStartNanos = time.nanoTime();
        fadeDurNanos = (long) (seconds * 1e9);
        fading = true;
    }

    /** Fades the music out over {@code seconds} (the clock keeps running). */
    public void fadeOut(float seconds) {
        fadeTo(0f, seconds);
    }

    private void updateFade(long now) {
        float p = (float) ((now - fadeStartNanos) / (double) fadeDurNanos);
        if (p >= 1f) {
            p = 1f;
            fading = false;
        }
        volume = fadeFrom + (fadeTo - fadeFrom) * p;
        if (music != null && preroll != 1) music.setVolume(volume);
    }

    private void disposeMusic() {
        if (music != null) {
            try {
                music.stop();
                if (ownsMusic) music.dispose();
            } catch (RuntimeException e) {
                if (Gdx.app != null) Gdx.app.error("SongClock", "dispose failed", e);
            }
        }
        music = null;
        ownsMusic = false;
        preroll = 0;
    }

    /** Stops and releases the music (if owned). The clock can be reused with {@link #load}. */
    @Override
    public void dispose() {
        disposeMusic();
        running = false;
        musicStarted = false;
    }
}
