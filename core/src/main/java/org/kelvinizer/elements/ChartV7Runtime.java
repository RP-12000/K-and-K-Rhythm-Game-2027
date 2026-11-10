package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 v7 谱面（{@link Chart7}）变成游戏跑的谱面：轨道的函数原样交给轨道（两段之间保持），键的 h、H 变成它的移动和长度，镜头放在 P。
 *
 * <p>Builds the {@link Chart} the game plays from a chart of format v7 ({@link Chart7}, Kelvin 2026-11-09 18:44–18:45).
 * Every function holds its value between its segments and after the last one ({@link Fn}):
 * <ul>
 *   <li>a lane keeps its functions ({@link Channels#LANE7}, read with {@link Events#heldValue}): {@link LaneLayout}
 *       places, sizes and turns its column by them each frame ({@link LaneLayout#pose7});</li>
 *   <li>a key's {@code h} becomes its movement: its segments, each held to the next one's start, so it shows from
 *       the first segment on and is judged where the last one ends; a stream's or a tide's {@code H} becomes its
 *       height, held from when it shows to its end, which ends it;</li>
 *   <li>a wave or a tide spans its lanes ({@link Note#spanCount()}); one with none left spans the field;</li>
 *   <li>the camera is at {@code P} ({@link Chart#setCamera7}), looking at the origin;</li>
 *   <li>a key's trails, glitches and distortions go to its note as they are (Kelvin 2026-11-10 02:17);</li>
 *   <li>the veils and curtains go to the chart as they are ({@link Chart#setVolumes7}, copies);</li>
 *   <li>a lane's opacity {@code o} fades the lane ({@link Lane#opacityAt}), a key's is its {@code o} times its
 *       lane's ({@link Note#opacityAt}; a wave's or tide's, the most of its lanes'), copies (Kelvin 2026-11-09 21:37
 *       「加回透明度」).</li>
 * </ul>
 * The judgement lines stay at their defaults (each lane's faces are its own), the lanes have no gap and no other
 * object is made: no seas, full-screen ranges, laneless ranges or view events. The song values (title, BPM and its
 * changes, offset, level) come from the chart the caller passes.
 */
public final class ChartV7Runtime {
    /** The shortest stream or tide, beats: one whose height ends at or before its judgement still is one. */
    static final float MIN_DURATION = 1f / 64f;

    private ChartV7Runtime() {
    }

    /**
     * @param c7   the v7 chart
     * @param song a chart with the song values (title, artist, illustrator, level, BPM and its changes, offset; its
     *             charter and constant when {@code c7} has none); its lanes and keys are not read
     * @return the chart the game plays
     */
    public static Chart build(Chart7 c7, Chart song) {
        List<Lane> lanes = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        Map<Integer, Fn> opacity = new HashMap<>(); // each lane's o, copied (the editor changes its own)
        for (Lane7 l : c7.lanes) {
            if (l == null || !ids.add(l.id)) continue;
            Fn o = l.o.copy();
            opacity.put(l.id, o);
            lanes.add(Lane.v7(l.id, functions(l), o));
        }
        List<Note> notes = new ArrayList<>();
        for (Key7 k : c7.keys) {
            Note n = k != null ? note(k, ids) : null;
            if (n == null) continue;
            notes.add(n);
            Fn[] on;
            if (k.kind.spans()) {
                on = new Fn[n.spanCount()];
                for (int i = 0; i < on.length; i++) on[i] = opacity.get(n.spanValue(i));
            } else {
                on = new Fn[] {opacity.get(k.lane)};
            }
            n.setOpacity7(k.o.copy(), on);
        }
        Chart c = new Chart(song.title(), song.artist(), song.illustrator(),
            c7.charter != null ? c7.charter : song.charter(), song.level(),
            c7.constant != null ? c7.constant : song.chartConstant(), song.bpm(), song.offsetMs(), lanes,
            JudgementLine.defaults(), notes, song.bpmListBeats(), song.bpmListValues(), -1);
        c.setLaneWidth(Lane.DEFAULT_WIDTH); // a lane is as wide as its base: no factor
        c.setCamera7(Channels.held(Channels.CAMERA7, bases(c7.camera.x, c7.camera.y, c7.camera.z),
            segments(c7.camera.x, c7.camera.y, c7.camera.z)));
        c.setFov7(c7.fov.copy());
        List<Volume7> vols = new ArrayList<>(c7.volumes.size());
        for (Volume7 v : c7.volumes) if (v != null) vols.add(v.copy());
        c.setVolumes7(vols);
        c.sourceFormat = song.sourceFormat;
        c.report = song.report;
        return c;
    }

    /** @return lane l's functions ({@link Channels#LANE7} order) */
    static Channels functions(Lane7 l) {
        Fn[] f = {l.u.x, l.u.y, l.u.z, l.v.x, l.v.y, l.v.z, l.w, l.h, l.S, l.s, l.F, l.f};
        return Channels.held(Channels.LANE7, bases(f), segments(f));
    }

    /** @return each function's value before its first segment (its constant when it has none) */
    private static float[] bases(Fn... f) {
        float[] b = new float[f.length];
        for (int i = 0; i < f.length; i++) b[i] = f[i].isEmpty() ? f[i].constant : f[i].segs.get(0).xi;
        return b;
    }

    /** @return each function's segments as events ({@link #event}), to read with {@link Events#heldValue} */
    private static List<List<Events>> segments(Fn... f) {
        List<List<Events>> out = new ArrayList<>(f.length);
        for (Fn fn : f) {
            List<Events> l = new ArrayList<>(fn.segs.size());
            for (Seg s : fn.segs) l.add(event(Events.TYPE.Movement, s));
            out.add(l);
        }
        return out;
    }

    /** @return segment s as an event: a segment ending before it starts jumps at its start; e not above 0 is 1 */
    static Events event(Events.TYPE type, Seg s) {
        return new Events(type, s.ti, Math.max(s.ti, s.tf), s.xi, s.xf, s.e > 0f ? s.e : 1f);
    }

    /**
     * @return f from its first segment's start to its last one's end as events read with
     *         {@link Events#calculateValue}: each segment, and where one ends before the next starts, a constant one
     *         holding its end value to there; from {@code from} on to the first segment, a constant one at its start
     *         value (none when {@code from} is NaN or not before it)
     */
    static List<Events> heldEvents(Fn f, Events.TYPE type, float from) {
        List<Seg> s = f.segs;
        List<Events> out = new ArrayList<>(2 * s.size() + 1);
        if (s.isEmpty()) return out;
        if (from < s.get(0).ti) out.add(new Events(type, from, s.get(0).ti, s.get(0).xi, s.get(0).xi, 1f));
        for (int i = 0; i < s.size(); i++) {
            Events e = event(type, s.get(i));
            out.add(e);
            if (i + 1 < s.size() && e.endTime() < s.get(i + 1).ti) {
                out.add(new Events(type, e.endTime(), s.get(i + 1).ti, e.finalValue(), e.finalValue(), 1f));
            }
        }
        return out;
    }

    /** @return key k as a note, or null when it has no {@code h} or its lane is not in {@code ids} */
    static Note note(Key7 k, Set<Integer> ids) {
        if (k.kind == null || k.h.isEmpty()) return null;
        NoteType type;
        switch (k.kind) {
            case TAP: type = NoteType.TAP; break;
            case DRAG: type = NoteType.DRAG; break;
            case MINE: case STREAM_MINE: type = NoteType.MINE; break;
            case STREAM: type = NoteType.HOLD; break;
            case WAVE: case TIDE: type = NoteType.UNIVERSAL; break;
            default: type = NoteType.TIDE_MINE; break; // WAVE_MINE, TIDE_MINE
        }
        if (type.inLane() && !ids.contains(k.lane)) return null;
        float j = k.judgeBeat();
        List<Events> move = heldEvents(k.h, Events.TYPE.Movement, Float.NaN);
        float from = move.get(0).startTime();
        if (from <= 0f) { // unseen before it too (an event from beat 0 or before would show its start value)
            move.add(0, new Events(Events.TYPE.Movement, from, from, Note.HIDDEN_POSITION, Note.HIDDEN_POSITION, 1f));
        }
        float duration = 0f;
        float height = 0f;
        List<Events> heights = null;
        if (k.kind.hasHeight()) {
            float end = k.H.isEmpty() ? j : k.H.lastTf();
            duration = Math.max(end - j, MIN_DURATION);
            height = Math.max(0f, k.H.at(j));
            heights = heldEvents(k.H, Events.TYPE.Height, k.showBeat());
            if (k.H.isEmpty()) {
                heights.add(new Events(Events.TYPE.Height, k.showBeat(), j + duration, height, height, 1f));
            }
        }
        Note n = new Note(type, k.sky ? Line.UPPER : Line.LOWER, type.inLane() ? (float) k.lane : 0f, Float.NaN, j,
            duration, height, move, null, null, heights, k.trails, k.glitches, 0f, 0f, k.distortions, 0f, 0f, null,
            null, k.fake, false, null);
        if (k.kind.spans()) {
            int[] spans = new int[2 * k.lanes.length];
            int m = 0;
            for (int id : k.lanes) {
                if (ids.contains(id)) {
                    spans[m++] = 0; // a lane
                    spans[m++] = id;
                }
            }
            n.spans = m > 0 ? Arrays.copyOf(spans, m) : null;
        }
        return n;
    }
}
