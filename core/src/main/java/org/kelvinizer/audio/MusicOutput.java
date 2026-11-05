package org.kelvinizer.audio;

import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;

/**
 * 平台自己的放歌输出（安卓 8.0 起）：游戏自己把歌解码，混进打击音那条流里放，播放位置是真正放出声音的那一刻。
 * 没有时用 libGDX 的 {@link Music}（安卓上是系统播放器）。
 *
 * <p>The platform's own song output (Android 8.0+, set once at start through {@link AudioService#setMusicOutput}):
 * the song is decoded by the game and mixed into the hit sound stream, so music, chart and hit sounds share one
 * clock and the position is that of the sound being heard (Kelvin 2026-10-26 20:43: Android's player reported a
 * position that differed from its sound by a different amount after every pause). Without one, libGDX
 * {@link Music} is used.
 */
public interface MusicOutput {
    /**
     * @return the song's music on this output, paused at its start, or null when it cannot play the file
     *         (libGDX music is used then). Called on the game thread; may take a moment to start decoding.
     */
    Music open(FileHandle file);

    /** @return how long after {@link Music#play()} the music is heard (ms) */
    float startDelayMs();
}
