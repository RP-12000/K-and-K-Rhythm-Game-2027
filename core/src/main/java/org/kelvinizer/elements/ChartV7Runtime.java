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
 *       places, sizes and turns its column by them each frame ({@link LaneLayout#pose7}), and by its groups'
 *       ({@link Group7}, chart version 2.1) when it is in one;</li>
 *   <li>a key's {@code h} becomes its movement: its segments, each held to the next one's start, so it shows from
 *       the first segment on and is judged where the last one ends; a stream's or a tide's {@code H} becomes its
 *       height, held from when it shows to its end, which ends it;</li>
 *   <li>a wave or a tide spans its lanes ({@link Note#spanCount()}); one with none left spans the field;</li>
 *   <li>the camera is at {@code P} ({@link Chart#setCamera7}), looking at the origin;</li>
 *   <li>a key's trails, glitches and distortions go to its note as they are (Kelvin 2026-11-10 02:17);</li>
 *   <li>the veils and curtains go to the chart as they are ({@link Chart#setVolumes7}, copies);</li>
 *   <li>a lane's opacity {@code o} and its groups' fade the lane ({@link Lane#opacityAt}), a key's is its {@code o}
 *       times those ({@link Note#opacityAt}; a wave's or tide's, the most of its lanes'), copies (Kelvin 2026-11-09
 *       21:37 「加回透明度」), and its own opacity {@code l} ({@link Lane7#l}, Kelvin 2026-11-11 02:12) fades only what
 *       the lane itself draws, not its keys;</li>
 *   <li>a lane's mine {@code m} ({@link Lane7#m}, Kelvin 2026-11-10 23:01 「这些轨道会变红」) reddens it
 *       ({@link Lane#mineAt}), and each stretch where it is above 0 becomes a red stretch on each of its two faces
 *       ({@link Note#fromLane}: a LANE MINE in form, never drawn, in no sync group and no mine; Kelvin 2026-11-12 「红色
 *       轨道只是强制严判轨道」), after the keys: the keys on it can only be RESONANCE.</li>
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
        Map<Integer, Group7.Pose> poses = poses(c7.groups);
        Map<Integer, Group7.Pose> grouped = new HashMap<>(); // each grouped lane's group
        List<Lane7> mined = new ArrayList<>();
        for (Lane7 l : c7.lanes) {
            if (l == null || !ids.add(l.id)) continue;
            Fn o = l.o.copy();
            opacity.put(l.id, o);
            boolean m = !l.m.isEmpty() || l.m.constant > 0f;
            if (m) mined.add(l);
            Fn own = l.l.isEmpty() && l.l.constant == 1f ? null : l.l.copy();
            Group7.Pose g = l.g != 0 ? poses.get(l.g) : null;
            if (g != null) grouped.put(l.id, g);
            lanes.add(Lane.v7(l.id, functions(l), o, own, m ? l.m.copy() : null, g));
        }
        List<Note> notes = new ArrayList<>();
        for (Key7 k : c7.keys) {
            Note n = k != null ? note(k, ids) : null;
            if (n == null) continue;
            notes.add(n);
            int[] on;
            if (k.kind.spans()) {
                on = new int[n.spanCount()];
                for (int i = 0; i < on.length; i++) on[i] = n.spanValue(i);
            } else {
                on = new int[] {k.lane};
            }
            Fn[] fade = new Fn[on.length];
            Group7.Pose[] in = null;
            for (int i = 0; i < on.length; i++) {
                fade[i] = opacity.get(on[i]);
                Group7.Pose g = grouped.get(on[i]);
                if (g == null) continue;
                if (in == null) in = new Group7.Pose[on.length];
                in[i] = g;
            }
            n.setOpacity7(k.o.copy(), fade, in);
        }
        float last = 0f; // a stretch still on after its lane's last segment ends with the last key
        for (Note n : notes) last = Math.max(last, n.endBeat());
        for (Lane7 l : mined) mines(l, positive(l.m, last), notes);
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

    /**
     * @return the pose of each group by its id ({@link Group7.Pose}), of copies of the groups (the editor changes its
     *         own), each with the pose of the group it is in
     */
    public static Map<Integer, Group7.Pose> poses(List<Group7> groups) {
        Map<Integer, Group7> byId = new HashMap<>();
        for (Group7 g : groups) {
            if (g != null && g.id != 0) byId.putIfAbsent(g.id, g.copy());
        }
        Map<Integer, Group7.Pose> out = new HashMap<>();
        for (Group7 g : byId.values()) pose(g, byId, out, 0);
        return out;
    }

    /** @return group g's pose, made with those of the groups it is in (a ring, which the reader breaks, ends) */
    private static Group7.Pose pose(Group7 g, Map<Integer, Group7> byId, Map<Integer, Group7.Pose> out, int depth) {
        Group7.Pose p = out.get(g.id);
        if (p != null) return p;
        Group7 up = g.p != 0 && depth < byId.size() ? byId.get(g.p) : null;
        p = new Group7.Pose(g, up != null ? pose(up, byId, out, depth + 1) : null);
        out.put(g.id, p);
        return p;
    }

    /** The first beat a mine lane's stretch can start: one from before its {@code m}'s first segment starts here. */
    static final float MINE_FROM = -8f;

    /** Adds a red stretch on each face of lane l for each stretch {@code spans} (from, to pairs), never drawn. */
    private static void mines(Lane7 l, float[] spans, List<Note> notes) {
        for (int i = 0; i + 1 < spans.length; i += 2) {
            for (Line line : Line.values()) {
                Note n = new Note(NoteType.MINE, line, (float) l.id, Float.NaN, spans[i], spans[i + 1] - spans[i], 0f,
                    null, null, null, null, null, null, 0f, 0f, null, 0f, 0f, null, null, false, false, null);
                n.setOpacity7(new Fn(0f), null);
                n.fromLane = true;
                notes.add(n);
            }
        }
    }

    /**
     * @return where f is above 0, as from, to pairs (beats; touching ones joined): from {@link #MINE_FROM} at the
     *         earliest; held above 0 after its last segment (or as a constant), up to {@code last}. A segment's curve
     *         goes one way ({@link Seg#at}), so it crosses 0 once at most.
     */
    static float[] positive(Fn f, float last) {
        float[] out = new float[8];
        int n = 0;
        if (f.isEmpty()) {
            if (f.constant > 0f) n = span(out, n, MINE_FROM, last);
            return Arrays.copyOf(out, n);
        }
        List<Seg> s = f.segs;
        if (s.get(0).xi > 0f) n = span(out, n, MINE_FROM, s.get(0).ti);
        for (int k = 0; k < s.size(); k++) {
            Seg g = s.get(k);
            float tf = Math.max(g.ti, g.tf);
            float next = k + 1 < s.size() ? s.get(k + 1).ti : Math.max(tf, last);
            float a = g.ti, b = Math.min(tf, next);
            if (tf > g.ti && (g.xi > 0f || g.xf > 0f)) { // its curve
                if (!(g.xi > 0f && g.xf > 0f)) { // where it crosses 0
                    double p = Math.pow(-g.xi / (g.xf - g.xi), 1.0 / (g.e > 0f ? g.e : 1f));
                    float c = g.ti + (float) p * (tf - g.ti);
                    if (g.xi > 0f) b = Math.min(b, c);
                    else a = c;
                }
                if (out.length < n + 2) out = Arrays.copyOf(out, 2 * out.length);
                n = span(out, n, a, b);
            }
            if (g.xf > 0f) { // held after it
                if (out.length < n + 2) out = Arrays.copyOf(out, 2 * out.length);
                n = span(out, n, tf, next);
            }
        }
        return Arrays.copyOf(out, n);
    }

    /** Adds [a, b) to the pairs in {@code out} (n floats so far), joined to the last one when they touch. */
    private static int span(float[] out, int n, float a, float b) {
        if (!(b > a)) return n;
        if (n >= 2 && a <= out[n - 1]) {
            out[n - 1] = Math.max(out[n - 1], b);
            return n;
        }
        out[n] = a;
        out[n + 1] = b;
        return n + 2;
    }

    /** @return lane l's functions ({@link Channels#LANE7} order) */
    static Channels functions(Lane7 l) {
        Fn[] f = {l.u.x, l.u.y, l.u.z, l.v.x, l.v.y, l.v.z, l.w, l.h, l.S, l.s, l.F, l.f, l.n.x, l.n.y, l.n.z};
        // n not written: the lane turns the shortest way (Channels#LANE7)
        if (!l.facing()) f = Arrays.copyOf(f, Channels.N_X);
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
