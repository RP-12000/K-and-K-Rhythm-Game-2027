package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.List;

/**
 * 谱面格式 v7 的键：只有离底面/天面的位置 h（水流、潮汐还有物件高度 H，浪花、潮汐还有跨越的轨道）；残影、故障、扭曲和 v5 一样。
 *
 * <p>A key of chart format v7 (Kelvin 2026-11-09 18:44, items 2–5). {@link #h} is its distance from its face into
 * the column: a ground key sits at {@code F + h} on the lane's up axis, a sky key at {@code S − h}. It is judged where
 * {@code h}'s last segment ends ({@link #judgeBeat()}, where {@code h} reaches 0) and shows from {@code h}'s first
 * segment ({@link #showBeat()}). A stream or tide also has a height {@link #H} along the column: it starts when
 * {@code h} reaches 0 and ends where {@code H}'s last segment ends. Waves and tides span the lanes in {@link #lanes};
 * the other kinds sit in {@link #lane}. Its afterimages, glitches and distortions are as in format v5
 * ({@link #trails}).
 */
public final class Key7 {
    public KeyKind kind;
    /** The lane's id (kinds that do not span). */
    public int lane;
    /** The spanned lanes' ids (waves and tides), any set. */
    public int[] lanes = {};
    /** True on the sky face, false on the base (ground). */
    public boolean sky;
    /**
     * A fake key (Kelvin 2026-11-09 20:37 「假键的逻辑倒是可以保留」): drawn like a real one until its judge beat (a stream's
     * or tide's end), never judged or scored; wave and tide mines are never fake.
     */
    public boolean fake;
    /** Distance from its face. */
    public final Fn h = new Fn(0f);
    /** Height along the column (streams and tides). */
    public final Fn H = new Fn(0f);
    /**
     * Opacity (default 1; Kelvin 2026-11-09 21:37 「加回透明度」), clamped to 0..1 where used and times its lane's
     * {@link Lane7#o}; 0 is not drawn. Judging does not change.
     */
    public final Fn o = new Fn(1f);
    /**
     * Afterimage trails, as a v5 key's {@code trails} (Kelvin 2026-11-10 02:17 「故障效果和残影要加上啊，还有那个distortion，这些
     * 还是保留」); like {@link #glitches} and {@link #distortions}, none by default.
     */
    public List<NoteTrail> trails = new ArrayList<>();
    /** Glitch segments, as a v5 key's {@code glitches}. */
    public List<NoteGlitch> glitches = new ArrayList<>();
    /** Distortion segments, as a v5 key's {@code distortions}. */
    public List<NoteDistortion> distortions = new ArrayList<>();

    /** @return the judge beat (the end of {@code h}'s last segment), or NaN when {@code h} has none */
    public float judgeBeat() {
        return h.lastTf();
    }

    /** @return the beat it shows from ({@code h}'s first segment), or NaN when {@code h} has none */
    public float showBeat() {
        return h.firstTi();
    }

    /** @return the beat it is over: the end of {@code H}'s last segment for streams and tides, else the judge beat */
    public float endBeat() {
        float j = judgeBeat();
        if (kind == null || !kind.hasHeight()) return j;
        float e = H.lastTf();
        return Float.isNaN(e) ? j : Math.max(j, e);
    }
}
