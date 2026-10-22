package org.kelvinizer.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Disposable;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.profile.DeviceSettings;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.profile.Settings;

/**
 * 菜单这边的声音：菜单背景音乐、地图上的歌曲试听、结算音乐、可选的打击音效，以及校准时播放的节拍声。
 * 由 {@code Main} 持有，每帧调用 {@link #update(float)} 推进淡入淡出、试听片段的循环和音量的实时变化。
 *
 * <p>Menu-side audio slots (ARCHITECTURE §6, DESIGN §13). Owned by {@code Main};
 * {@link #update(float)} must be called once per frame (it runs fades, the preview window
 * loop and live volume changes, allocation-free).
 *
 * <ul>
 *   <li><b>MENU</b>: {@code background.(ogg|mp3|wav)} loop, 0.6 s fade-in, {@link #duckMenu},
 *       automatically silent while a preview plays.</li>
 *   <li><b>PREVIEW</b>: the song's preview window ({@code previewStartMs} to {@code previewEndMs}, by default
 *       20 s from 30 % of the song) looped with a 0.6 s fade-in and 0.8 s edge fades; the window's parts before the
 *       song's start and after its end are silent; never more than one preview {@link Music} alive.</li>
 *   <li><b>RESULT</b>: song {@code result.*} → {@code Audio/result.*} →
 *       {@code Introduction/Introduction.wav} (temporary) → silence; 1.2 s fade-in to
 *       musicVolume × 0.8; loops.</li>
 *   <li><b>HIT</b> (reserved): {@code Sounds/hit.(ogg|wav)} if present, else silent.</li>
 *   <li><b>FILE</b>: {@link #playFile} for calibration, through a {@link SongClock} on the
 *       same Music path as songs.</li>
 * </ul>
 * Volumes come live from {@link DeviceSettings} (musicVolume, sfxVolume) and the diver's
 * {@link Settings} (menuMusicOn, previewOn) of the bound {@link ProfileStore}.
 */
public final class AudioService implements Disposable {
    /** Music slots. HIT is a reserved Sound slot (see {@link #hitSound()}). */
    public enum Slot { MENU, PREVIEW, RESULT, HIT }

    /** Menu fade-in (s). */
    public static final float MENU_FADE_IN = 0.6f;
    /** Preview fade-in (s). */
    public static final float PREVIEW_FADE_IN = 0.6f;
    /** Fade at the preview window edges (s). */
    public static final float PREVIEW_EDGE_FADE = 0.8f;
    /** Result music fade-in (s). */
    public static final float RESULT_FADE_IN = 1.2f;
    /** Result music level relative to musicVolume. */
    public static final float RESULT_GAIN = 0.8f;
    /** Fade-in of menu slots after the app resumes (s). */
    public static final float RESUME_FADE = 0.3f;

    private static final String[] AUDIO_EXT = {"ogg", "mp3", "wav"};
    private static final String[] SOUND_EXT = {"ogg", "wav"};
    private static final String TAG = "AudioService";

    private final ProfileStore profiles;
    private final Channel menu = new Channel(1f);
    private final Channel preview = new Channel(1f);
    private final Channel result = new Channel(RESULT_GAIN);
    private final Channel[] channels = {menu, preview, result};

    private SongMeta previewSong;
    private float previewWindowStartMs;
    private float previewWindowEndMs;
    private boolean previewLooping;
    private float previewElapsed;
    /** Song time of the preview (ms): before 0 and past the music's end it runs on silently. */
    private float previewClockMs;
    /** True while the preview's music plays; false in the silent parts of the window. */
    private boolean previewPlaying;

    private SongClock fileClock;

    private boolean hitLoaded;
    private Sound hit;
    private Sound hitEcho;
    private Sound holdEnd;
    private Sound mine;

    private boolean appPaused;

    /**
     * @param profiles source of live volume settings; may be null (defaults: music 0.8,
     *                 sfx 0.8, menu music and preview on)
     */
    public AudioService(ProfileStore profiles) {
        this.profiles = profiles;
    }

    // ------------------------------------------------------------------ settings

    /** @return the device music volume 0..1. */
    public float musicVolume() {
        DeviceSettings d = profiles != null ? profiles.device() : null;
        return d != null ? clamp01(d.musicVolume) : 0.8f;
    }

    /** @return the device hit-sound volume 0..1. */
    public float sfxVolume() {
        DeviceSettings d = profiles != null ? profiles.device() : null;
        return d != null ? clamp01(d.sfxVolume) : 0.8f;
    }

    private Settings diver() {
        return profiles != null && profiles.active() != null ? profiles.active().settings : null;
    }

    private boolean menuMusicOn() {
        Settings s = diver();
        return s == null || s.menuMusicOn;
    }

    private boolean previewOn() {
        Settings s = diver();
        return s == null || s.previewOn;
    }

    // ------------------------------------------------------------------ MENU

    /** Starts the menu loop with a 0.6 s fade-in (no-op when already playing or disabled). */
    public void playMenu() {
        if (!menuMusicOn()) return;
        if (menu.music == null) {
            FileHandle f = findAudio(Gdx.files.internal(""), "background");
            if (f == null) return;
            if (!menu.open(f, true)) return;
            menu.fade = 0f;
        }
        menu.fadeTo(1f, MENU_FADE_IN);
        menu.disposeWhenSilent = false;
        menu.play();
    }

    /**
     * Ducks the menu loop to {@code level} (1 = full) over {@code seconds}; e.g. 0.25 on node
     * select (0.3 s), back to 1 over 0.8 s on deselect.
     */
    public void duckMenu(float level, float seconds) {
        menu.duckTo(level, seconds);
    }

    // ------------------------------------------------------------------ PREVIEW

    /**
     * Plays a song preview from {@link SongMeta#previewStartMs()} to {@link SongMeta#previewEndMs()} and loops it,
     * fading in over 0.6 s while the menu fades to 0. A start before 0 waits silently for the song's start, and an
     * end past the music's end waits silently for the window's end, never wrapping to the song's start (Kelvin
     * 2026-10-21). Replaying the current preview is a no-op. A previous preview is disposed at once, so at most one
     * preview Music is alive (the caller fades it out during its 450 ms dwell with {@link #stopPreview(float)}).
     */
    public void playPreview(SongMeta song) {
        if (song == null || song.music() == null || !previewOn()) return;
        if (song == previewSong && preview.music != null && !preview.disposeWhenSilent) return;
        preview.close();
        previewSong = song;
        if (!preview.open(song.music(), false)) {
            previewSong = null;
            return;
        }
        previewWindowStartMs = song.previewStartMs();
        previewWindowEndMs = song.previewEndMs();
        previewLooping = false;
        preview.fade = 0f;
        preview.edge = 1f;
        preview.fadeTo(1f, PREVIEW_FADE_IN);
        startPreviewPass();
    }

    /** Starts one pass of the preview window: the music from the window's start, or silence until the song's start. */
    private void startPreviewPass() {
        previewClockMs = previewWindowStartMs;
        previewElapsed = 0f;
        float dur = previewSong != null ? previewSong.durationMs() : 0f;
        previewPlaying = previewWindowStartMs >= 0f && (dur <= 0f || previewWindowStartMs < dur);
        if (previewPlaying) {
            // play() before setPosition(): desktop ignores seeks on a stopped stream.
            preview.play();
            preview.seek(previewWindowStartMs);
        } else if (preview.music.isPlaying()) {
            preview.music.stop();
        }
    }

    /** Fades the preview out over {@code seconds} and disposes it (menu returns automatically). */
    public void stopPreview(float seconds) {
        preview.fadeOutAndDispose(seconds);
        if (seconds <= 0f) previewSong = null;
    }

    /** @return the song currently previewing (or fading out), or null. */
    public SongMeta previewSong() {
        return preview.music != null ? previewSong : null;
    }

    private void updatePreviewWindow(float delta) {
        if (preview.music == null || preview.paused) return;
        previewElapsed += delta;
        if (previewPlaying && previewElapsed > 1f && !preview.music.isPlaying()) previewPlaying = false;
        if (previewPlaying) {
            previewClockMs = preview.music.getPosition() * 1000f;
        } else {
            // the silent parts of the window: the clock runs on its own while the music waits at the song's start
            float before = previewClockMs;
            previewClockMs += delta * 1000f;
            if (preview.music.isPlaying()) preview.music.stop();
            if (before < 0f && previewClockMs >= 0f) {
                preview.play();
                previewPlaying = true;
                previewElapsed = 0f;
            }
        }
        if (!previewLooping) {
            if (previewClockMs >= previewWindowEndMs - PREVIEW_EDGE_FADE * 1000f) {
                previewLooping = true;
                preview.edgeTo(0f, PREVIEW_EDGE_FADE);
            }
        } else if (preview.edge <= 0f) {
            preview.edgeTo(1f, PREVIEW_EDGE_FADE);
            previewLooping = false;
            startPreviewPass();
        }
    }

    // ------------------------------------------------------------------ RESULT

    /**
     * Starts the result music (DESIGN §6.7): song folder {@code result.*}, else
     * {@code Audio/result.*}, else {@code Introduction/Introduction.wav} (temporary), else
     * silence. Starts at {@code resultMusicStartMs}, fades in over 1.2 s to
     * musicVolume × 0.8 and loops.
     */
    public void playResult(SongMeta songOrNull) {
        result.close();
        FileHandle f = songOrNull != null ? songOrNull.resultMusic() : null;
        float startMs = songOrNull != null && f != null ? songOrNull.resultMusicStartMs() : 0f;
        if (f == null) f = findAudio(Gdx.files.internal("Audio"), "result");
        if (f == null) {
            FileHandle intro = Gdx.files.internal("Introduction/Introduction.wav");
            if (intro.exists()) f = intro;
        }
        if (f == null) return;
        if (!result.open(f, true)) return;
        result.fade = 0f;
        result.fadeTo(1f, RESULT_FADE_IN);
        result.play();
        if (startMs > 0f) result.seek(startMs);
    }

    /** Ducks the result music (e.g. 0.6 for 600 ms during level-up). */
    public void duckResult(float level, float seconds) {
        result.duckTo(level, seconds);
    }

    // ------------------------------------------------------------------ generic slot control

    /** Fades a slot out over {@code fadeSeconds} and disposes it (HIT: no-op). */
    public void stopSlot(Slot slot, float fadeSeconds) {
        switch (slot) {
            case MENU: menu.fadeOutAndDispose(fadeSeconds); break;
            case PREVIEW: stopPreview(fadeSeconds); break;
            case RESULT: result.fadeOutAndDispose(fadeSeconds); break;
            default: break;
        }
    }

    // ------------------------------------------------------------------ FILE (calibration)

    /**
     * Plays a file through a {@link SongClock} (the same Music path as songs), starting at
     * 0 ms immediately. Used by calibration with the synthesised click track.
     *
     * @return the running clock (owned by this service; valid until {@link #stopFile()})
     */
    public SongClock playFile(FileHandle file, boolean loop) {
        if (fileClock == null) fileClock = new SongClock();
        fileClock.load(file);
        if (fileClock.music() != null) fileClock.music().setLooping(loop);
        fileClock.setMonotonic(!loop);
        fileClock.setVolume(musicVolume());
        fileClock.prepare(0f);
        fileClock.start();
        return fileClock;
    }

    /** @return the calibration clock, or null if {@link #playFile} was never called. */
    public SongClock fileClock() {
        return fileClock;
    }

    /** Stops and releases the calibration file. */
    public void stopFile() {
        if (fileClock != null) fileClock.dispose();
    }

    // ------------------------------------------------------------------ HIT (reserved)

    /**
     * @return the hit sound loaded from {@code Sounds/hit.(ogg|wav)}, or null when not
     *         installed (not shipped now). Loaded and primed once, together with the other
     *         reserved slots ({@code hit_echo}, {@code hold_end}, {@code mine}).
     */
    public Sound hitSound() {
        if (!hitLoaded) {
            hitLoaded = true;
            hit = loadSound("hit");
            hitEcho = loadSound("hit_echo");
            holdEnd = loadSound("hold_end");
            mine = loadSound("mine");
            primeHit();
        }
        return hit;
    }

    /** @return true when a hit sound file is installed. */
    public boolean hasHitSound() {
        return hitSound() != null;
    }

    /** Plays the hit sounds once at volume 0 to avoid the first-play delay. */
    public void primeHit() {
        if (hit != null) hit.play(0f);
        if (hitEcho != null) hitEcho.play(0f);
        if (holdEnd != null) holdEnd.play(0f);
        if (mine != null) mine.play(0f);
    }

    /**
     * Plays the hit sound for a judgement (call inside the input handler). No-op when no
     * sound is installed, for LOST, or at volume 0. Pass null for an "any zone tap" hit.
     */
    public void playHit(Judgement j) {
        if (j == Judgement.LOST) return;
        Sound s = hitSound();
        if (j == Judgement.ECHO && hitEcho != null) s = hitEcho;
        float v = sfxVolume();
        if (s != null && v > 0f) s.play(v);
    }

    /** Plays the optional hold-end sound. */
    public void playHoldEnd() {
        hitSound();
        float v = sfxVolume();
        if (holdEnd != null && v > 0f) holdEnd.play(v);
    }

    /**
     * Plays the optional touched-mine sound {@code Sounds/mine.(ogg|wav)} (DESIGN §5.15) at the
     * hit volume; silent when absent. A mine never plays the hit sound.
     */
    public void playMine() {
        hitSound();
        float v = sfxVolume();
        if (mine != null && v > 0f) mine.play(v);
    }

    private Sound loadSound(String base) {
        FileHandle dir = Gdx.files.internal("Sounds");
        for (String ext : SOUND_EXT) {
            FileHandle f = dir.child(base + "." + ext);
            if (f.exists()) {
                try {
                    return Gdx.audio.newSound(f);
                } catch (RuntimeException e) {
                    Gdx.app.error(TAG, "Cannot load " + f.path(), e);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ frame / lifecycle

    /** Runs fades, ducks, the preview loop and live volumes. Call once per frame. */
    public void update(float delta) {
        if (appPaused) return;
        if (delta > 0.1f) delta = 0.1f;
        float music = musicVolume();
        if (!menuMusicOn() && menu.music != null && !menu.disposeWhenSilent) menu.fadeOutAndDispose(0.4f);
        for (Channel c : channels) c.step(delta);
        updatePreviewWindow(delta);
        // Only the preview's own fade-in/out moves the menu (DESIGN §13: MENU is 0 while a
        // preview plays); the 0.8 s dips at the 20 s loop points must not bring it back.
        float previewPresence = preview.music != null ? preview.fade : 0f;
        menu.apply(music * (1f - previewPresence));
        preview.apply(music);
        result.apply(music);
        if (preview.music == null) previewSong = null;
        if (fileClock != null && fileClock.hasMusic()) fileClock.update();
    }

    /** Pauses every slot (app backgrounded). */
    public void pause() {
        if (appPaused) return;
        appPaused = true;
        for (Channel c : channels) c.pauseForApp();
        if (fileClock != null) fileClock.pause();
    }

    /** Resumes paused menu slots with a 0.3 s fade-in. The calibration clock stays paused. */
    public void resume() {
        if (!appPaused) return;
        appPaused = false;
        for (Channel c : channels) c.resumeForApp(RESUME_FADE);
    }

    /** Releases every Music and Sound. */
    @Override
    public void dispose() {
        for (Channel c : channels) c.close();
        if (fileClock != null) fileClock.dispose();
        if (hit != null) hit.dispose();
        if (hitEcho != null) hitEcho.dispose();
        if (holdEnd != null) holdEnd.dispose();
        if (mine != null) mine.dispose();
        hit = hitEcho = holdEnd = mine = null;
        hitLoaded = false;
    }

    private static FileHandle findAudio(FileHandle dir, String base) {
        for (String ext : AUDIO_EXT) {
            FileHandle f = dir.child(base + "." + ext);
            if (f.exists()) return f;
        }
        return null;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** One looping/streaming music slot with fade, duck and edge multipliers. */
    private static final class Channel {
        final float gain;
        Music music;
        float fade;
        float fadeTarget;
        float fadeSpeed;
        float duck = 1f;
        float duckTarget = 1f;
        float duckSpeed;
        float edge = 1f;
        float edgeTarget = 1f;
        float edgeSpeed;
        boolean disposeWhenSilent;
        boolean paused;
        boolean wasActive;
        boolean playedOnce;
        float pendingSeekMs = -1f;
        float lastApplied = -1f;

        Channel(float gain) {
            this.gain = gain;
        }

        boolean open(FileHandle f, boolean loop) {
            close();
            try {
                music = Gdx.audio.newMusic(f);
            } catch (RuntimeException e) {
                if (Gdx.app != null) Gdx.app.error(TAG, "Cannot open " + f.path(), e);
                music = null;
                return false;
            }
            music.setLooping(loop);
            music.setVolume(0f);
            lastApplied = 0f;
            fade = 0f;
            fadeTarget = 0f;
            duck = duckTarget = 1f;
            edge = edgeTarget = 1f;
            disposeWhenSilent = false;
            paused = false;
            playedOnce = false;
            pendingSeekMs = -1f;
            return true;
        }

        void play() {
            if (music == null || paused) return;
            music.play();
            playedOnce = true;
            if (pendingSeekMs >= 0f) {
                music.setPosition(pendingSeekMs / 1000f);
                pendingSeekMs = -1f;
            }
        }

        /** Seeks; desktop ignores seeks before the first play(), so they are deferred. */
        void seek(float ms) {
            if (music == null) return;
            if (!playedOnce) {
                pendingSeekMs = ms;
                return;
            }
            music.setPosition(ms / 1000f);
        }

        void fadeTo(float target, float seconds) {
            fadeTarget = target;
            if (seconds <= 0f) {
                fade = target;
                fadeSpeed = 0f;
            } else {
                fadeSpeed = Math.abs(target - fade) / seconds;
            }
        }

        void duckTo(float target, float seconds) {
            duckTarget = Math.max(0f, Math.min(1f, target));
            if (seconds <= 0f) {
                duck = duckTarget;
                duckSpeed = 0f;
            } else {
                duckSpeed = Math.abs(duckTarget - duck) / seconds;
            }
        }

        void edgeTo(float target, float seconds) {
            edgeTarget = target;
            edgeSpeed = seconds <= 0f ? 0f : Math.abs(target - edge) / seconds;
            if (seconds <= 0f) edge = target;
        }

        void fadeOutAndDispose(float seconds) {
            if (music == null) return;
            disposeWhenSilent = true;
            fadeTo(0f, seconds);
            if (seconds <= 0f) close();
        }

        void step(float delta) {
            if (music == null || paused) return;
            fade = approach(fade, fadeTarget, fadeSpeed * delta);
            duck = approach(duck, duckTarget, duckSpeed * delta);
            edge = approach(edge, edgeTarget, edgeSpeed * delta);
            if (disposeWhenSilent && fade <= 0f) close();
        }

        void apply(float base) {
            if (music == null) return;
            float v = clamp01(base * gain * fade * duck * edge);
            if (Math.abs(v - lastApplied) > 1e-4f) {
                music.setVolume(v);
                lastApplied = v;
            }
        }

        void pauseForApp() {
            wasActive = music != null && !disposeWhenSilent;
            if (music != null) {
                music.pause();
                paused = true;
            }
        }

        void resumeForApp(float fadeSeconds) {
            if (music == null) return;
            paused = false;
            if (wasActive) {
                float target = fadeTarget;
                fade = 0f;
                fadeTo(target, fadeSeconds);
                music.setVolume(0f);
                lastApplied = 0f;
                music.play();
            } else {
                close();
            }
        }

        void close() {
            if (music != null) {
                try {
                    music.stop();
                    music.dispose();
                } catch (RuntimeException e) {
                    if (Gdx.app != null) Gdx.app.error(TAG, "dispose failed", e);
                }
            }
            music = null;
            fade = 0f;
            disposeWhenSilent = false;
            paused = false;
        }

        private static float approach(float v, float target, float step) {
            if (v < target) return Math.min(target, v + step);
            if (v > target) return Math.max(target, v - step);
            return v;
        }
    }
}
