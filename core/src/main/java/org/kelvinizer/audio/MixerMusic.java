package org.kelvinizer.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;

/**
 * 混在打击音那条流里放的歌曲，用 libGDX 的 {@link Music} 接口，所以 {@link SongClock} 照旧用它。
 * 报的位置是此刻真正放出声音的那一采样，暂停、继续、跳转都对到采样。
 *
 * <p>A song played inside a {@link HitMixer}'s stream, behind libGDX's {@link Music} interface so
 * {@link SongClock} drives it like any other music. {@link #getPosition()} is the song's place in what is heard
 * at this instant ({@link HitMixer#songHeard}), {@link #play()} is heard {@link HitMixer#SONG_START_MS} after the
 * call, and pause, resume and seek are exact to the sample (Kelvin 2026-10-26 20:43). Looping and panning are not
 * supported (gameplay uses neither). Called from the game thread.
 */
public final class MixerMusic implements Music {
    private final HitMixer mixer;
    private final PcmBuffer pcm;
    private final Runnable onDispose;
    private final SongClock.TimeSource time;
    private float volume = 1f;
    private boolean looping;
    private boolean ended;
    private boolean disposed;
    private OnCompletionListener listener;

    /**
     * @param pcm       the song, decoded (or being decoded) at the mixer's rate
     * @param onDispose run once on {@link #dispose()} (stops the decoder), or null
     * @param time      the clock the mixer's timestamps use ({@code System.nanoTime()} on a device)
     */
    public MixerMusic(HitMixer mixer, PcmBuffer pcm, Runnable onDispose, SongClock.TimeSource time) {
        this.mixer = mixer;
        this.pcm = pcm;
        this.onDispose = onDispose;
        this.time = time;
        mixer.songOpen(pcm);
        mixer.songVolume(volume);
    }

    @Override
    public void play() {
        if (disposed) return;
        ended = false;
        mixer.songPlay(time.nanoTime());
    }

    @Override
    public void pause() {
        if (!disposed) mixer.songPause();
    }

    @Override
    public void stop() {
        if (disposed) return;
        mixer.songPause();
        mixer.songSeek(0L, time.nanoTime());
    }

    @Override
    public boolean isPlaying() {
        if (disposed) return false;
        if (!ended && mixer.songEnded()) {
            ended = true;
            if (listener != null) listener.onCompletion(this);
        }
        return mixer.songPlaying();
    }

    @Override
    public void setLooping(boolean isLooping) {
        looping = isLooping;
    }

    @Override
    public boolean isLooping() {
        return looping;
    }

    @Override
    public void setVolume(float volume) {
        this.volume = Math.max(0f, Math.min(1f, volume));
        if (!disposed) mixer.songVolume(this.volume);
    }

    @Override
    public float getVolume() {
        return volume;
    }

    @Override
    public void setPan(float pan, float volume) {
        setVolume(volume);
    }

    @Override
    public void setPosition(float position) {
        if (disposed) return;
        ended = false;
        mixer.songSeek(Math.round(Math.max(0f, position) * (double) mixer.rate()), time.nanoTime());
    }

    @Override
    public float getPosition() {
        if (disposed) return 0f;
        return (float) (mixer.songHeard(time.nanoTime()) / (double) mixer.rate());
    }

    /** @return the decoded song (frames decoded so far, whether decoding ended) */
    public PcmBuffer pcm() {
        return pcm;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        mixer.songClose(pcm);
        if (onDispose != null) {
            try {
                onDispose.run();
            } catch (RuntimeException e) {
                if (Gdx.app != null) Gdx.app.error("MixerMusic", "dispose: " + e.getMessage());
            }
        }
    }

    @Override
    public void setOnCompletionListener(OnCompletionListener listener) {
        this.listener = listener;
    }
}
