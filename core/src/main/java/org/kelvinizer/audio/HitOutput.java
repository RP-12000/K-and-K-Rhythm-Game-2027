package org.kelvinizer.audio;

/**
 * 打击音的专用输出：每一下排到指定的时刻才响，几根手指同一时刻的打击只响一下。
 * 手机上由平台提供（安卓 8.0 起的低延迟音频流），没有时打击音照旧用 libGDX 的 Sound。
 *
 * <p>A dedicated output for hit sounds that starts each one at a requested instant. Supplied by the platform
 * (Android 8.0+: a low-latency stream, see {@link HitMixer}); without one {@link AudioService} plays hit sounds
 * through libGDX {@code Sound} as before.
 */
public interface HitOutput {
    /**
     * Adds a sound.
     *
     * @param pcm      16-bit samples, interleaved when {@code channels} is 2
     * @param channels 1 or 2
     * @param rate     sample rate in Hz
     * @return the sound's id, or −1 when it cannot be used
     */
    int load(short[] pcm, int channels, int rate);

    /**
     * Plays sound {@code id} so that it is heard at {@code atNanos} ({@code System.nanoTime()} base); an instant
     * that has passed (or is too close to be met) plays as soon as possible.
     */
    void play(int id, float volume, long atNanos);

    /** Drops every sound that has not started yet (the run paused). */
    void cancelPending();
}
