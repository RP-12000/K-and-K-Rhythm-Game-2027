package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 把旧格式（v1–v6）的谱面换成 v7 谱面（{@link Chart7}）：轨道照旧画面逐拍取样拟合成简单函数，键的移动和长度原样换成 h、H，
 * 视角换成镜头位置 P，水幕水帘换成长方体或椭球体，假键还是假键，残影、故障、扭曲照留；其他的（判定线、全局缩放、海面……）都不要了。
 *
 * <p>Converts a chart of an older format (a built {@link Chart}) to chart format v7 (Kelvin 2026-11-09 18:44–18:45),
 * so it looks and plays as before as far as v7 can say it:
 * <ul>
 *   <li><b>lanes</b>: what the old layout ({@link LaneLayout}) shows of each lane, its column's centre, width, depth,
 *       faces (the chart's lines and the lane's lift, their shakes included), reaches and fall, sampled between the
 *       moments anything about the lanes changes and fitted with simple functions (a run of one ease where it fits,
 *       else halves; within {@link #TOL} reference units). A lane never turned keeps its origin at the frame's height
 *       ({@code v.y} 0, the faces at the lines); one that turns has it at its box's middle;</li>
 *   <li><b>keys</b>: a key's movement becomes {@code h} exactly (each event as far as the next one or the judgement,
 *       parked at {@link Note#HIDDEN_POSITION} where none moves it or its opacity hides it), ending at its judgement;
 *       a stream's or tide's length and height events become {@code H} exactly, ending at its end. A key keeps its
 *       lane, one with its own x or between lanes takes the lane nearest to it at its judgement; waves and tides span
 *       their lanes (the field: every lane). A fake key stays fake, and its trails, glitches and distortions stay;
 *       every other key setting is dropped;</li>
 *   <li><b>camera</b>: the view's turn, distance and lens as the camera position P at the 11° lens;</li>
 *   <li><b>veils and curtains</b>: a circle as an ellipsoid, a polygon as the box around it (not turned), its centre
 *       {@link Sea#MID_Z} less deep (the v7 origin is halfway into the columns), sized 0 while hidden; their
 *       distortions and a veil's glitches stay.</li>
 * </ul>
 * Seas and laneless ranges are dropped. The charter and the constant are kept; the song values stay in song.json.
 */
public final class ChartV7Convert {
    /** How far a fitted function may stray from the old picture, reference units. */
    static final float TOL = 0.25f;
    /** The same for a unit fall's components. */
    static final float TOL_DIR = 1e-4f;
    /** Samples inside each stretch between two changes. */
    private static final int SAMPLES = 12;
    /** Most stretches one simple function may cover. */
    private static final int MAX_RUN = 24;
    /** Most times a stretch that fits no simple function is halved. */
    private static final int MAX_DEPTH = 6;
    /** A key drawn fainter than this is hidden ({@link #hide}). */
    static final float CLEAR = 0.05f;
    /** How often a key's opacity is looked at, beats. */
    static final float CLEAR_STEP = 1f / 64f;
    /** Where a camera at the 11° lens sees what an old view sees: tan 5.5°. */
    private static final double TAN_LENS = Math.tan(Math.toRadians(5.5));

    private ChartV7Convert() {
    }

    /** @return {@code old} as a chart of format v7 */
    public static Chart7 convert(Chart old) {
        Chart7 c = new Chart7();
        c.charter = old.charter();
        c.constant = old.chartConstant();
        LaneLayout lay = new LaneLayout(old);
        lanes(old, c, lay);
        for (Note n : old.notes()) {
            Key7 k = key(n, lay);
            if (k != null) c.keys.add(k);
        }
        camera(old, c);
        for (Veil v : old.veils()) volume(v, false, c);
        for (Veil v : old.curtains()) volume(v, true, c);
        return c;
    }

    // ------------------------------------------------------------------ lanes

    /** Adds each lane of {@code old} as it looks, fitted ({@link #fit}). */
    private static void lanes(Chart old, Chart7 c, LaneLayout lay) {
        Lane[] lanes = old.lanes();
        int n = lanes.length;
        if (n == 0) return;
        JudgementLine lines = old.lineEvents();
        Changes ch = new Changes();
        ch.add(lines.upperEvents());
        ch.add(lines.lowerEvents());
        ch.add(lines.laneScaleEvents());
        ch.add(lines.cameraFrameEvents());
        ch.add(old.upperExtendEvents());
        ch.add(old.lowerExtendEvents());
        boolean[] turns = new boolean[n];
        Fn[] fns = new Fn[12 * n];
        float[] tol = new float[12 * n];
        for (int i = 0; i < n; i++) {
            Lane l = lanes[i];
            ch.add(l.widthEvents());
            ch.add(l.insert());
            ch.add(l.deletion());
            Channels s = l.space();
            ch.add(s);
            turns[i] = s != null && s.keys() == Channels.LANE && (s.written(Channels.FALL_X)
                || s.written(Channels.FALL_Y) || s.written(Channels.FALL_Z));
            Lane7 l7 = new Lane7(l.id(), 0f);
            Fn[] f = {l7.u.x, l7.u.y, l7.u.z, l7.v.x, l7.v.y, l7.v.z, l7.w, l7.h, l7.S, l7.s, l7.F, l7.f};
            for (int k = 0; k < 12; k++) {
                fns[12 * i + k] = f[k];
                tol[12 * i + k] = k <= Channels.U_Z ? TOL_DIR : TOL;
            }
            c.lanes.add(l7);
        }
        fit((t, out) -> {
            lay.update(t, LaneLayout.VIEW_LEFT, LaneLayout.VIEW_RIGHT);
            float up = lines.upperAt(t), lo = lines.lowerAt(t);
            float ue = old.upperExtendAt(t), le = old.lowerExtendAt(t);
            for (int i = 0; i < n; i++) {
                int o = 12 * i;
                float len = lay.chartLength(i);
                float sky = up + lay.lift(i, Line.UPPER), ground = lo + lay.lift(i, Line.LOWER);
                out[o + Channels.U_X] = lay.pose(i, LaneLayout.P_FX);
                out[o + Channels.U_Y] = lay.pose(i, LaneLayout.P_FY);
                out[o + Channels.U_Z] = lay.pose(i, LaneLayout.P_FZ);
                out[o + Channels.V_X] = lay.centre(i) - LaneLayout.TILE_CENTRE;
                // a turned lane turns about its box's middle: its origin there; else at the frame's height
                float y = turns[i] ? 0.5f * (sky + ground) : Sea.MID_Y;
                out[o + Channels.V_Y] = y - Sea.MID_Y;
                out[o + Channels.V_Z] = lay.moveZ(i) - 0.5f * (LaneLayout.DEFAULT_LENGTH - len);
                out[o + Channels.A_W] = lay.columnWidth(i);
                out[o + Channels.A_H] = len;
                out[o + Channels.SKY] = sky - y;
                out[o + Channels.SKY_REACH] = lay.reachSky(i) * ue;
                out[o + Channels.GROUND] = ground - y;
                out[o + Channels.GROUND_REACH] = lay.reach(i) * le;
            }
        }, ch, fns, tol);
    }

    // ------------------------------------------------------------------ keys

    /** @return note n as a key of format v7 (a fake one stays fake), or null when it has no lane to go to */
    static Key7 key(Note n, LaneLayout lay) {
        Key7 k = new Key7();
        k.fake = n.fake();
        boolean d = n.duration() > 0f;
        switch (n.type()) {
            case TAP: k.kind = KeyKind.TAP; break;
            case DRAG: k.kind = KeyKind.DRAG; break;
            case HOLD: k.kind = KeyKind.STREAM; break;
            case MINE: k.kind = d ? KeyKind.STREAM_MINE : KeyKind.MINE; break;
            case UNIVERSAL: k.kind = d ? KeyKind.TIDE : KeyKind.WAVE; break;
            default: k.kind = d ? KeyKind.TIDE_MINE : KeyKind.WAVE_MINE; break;
        }
        k.sky = n.isUpper();
        float j = n.judgement();
        if (k.kind.spans()) {
            int[] ids = new int[n.spanCount()];
            int m = 0;
            for (int s = 0; s < n.spanCount(); s++) {
                if (!n.spanSea(s) && lay.indexOf(n.spanValue(s)) >= 0) ids[m++] = n.spanValue(s);
            }
            if (m == 0) { // the field: every lane
                ids = new int[lay.count()];
                for (int i = 0; i < ids.length; i++) ids[i] = lay.idAt(i);
                m = ids.length;
            }
            if (m == 0) return null;
            k.lanes = Arrays.copyOf(ids, m);
        } else {
            int i = lane(n, lay, j);
            if (i < 0) return null;
            k.lane = lay.idAt(i);
        }
        move(n, k.h);
        hide(n, k.h);
        if (k.kind.hasHeight()) height(n, k.H);
        k.trails = new ArrayList<>(n.trails()); // Kelvin 2026-11-10 02:17 「故障效果和残影要加上啊」
        k.glitches = new ArrayList<>(n.glitches());
        k.distortions = new ArrayList<>(n.distortions());
        return k;
    }

    /** @return the index of key n's lane: its own, else (its own x, between lanes, no such lane) the nearest at j */
    private static int lane(Note n, LaneLayout lay, float j) {
        if (lay.count() == 0) return -1;
        if (!n.laneless() && !n.between()) {
            int i = lay.keyLane(n);
            if (i >= 0) return i;
        }
        lay.update(j, LaneLayout.VIEW_LEFT, LaneLayout.VIEW_RIGHT);
        float x = lay.keyCentre(n, j);
        int best = 0;
        float far = Float.POSITIVE_INFINITY;
        for (int i = 0; i < lay.count(); i++) {
            float dx = Math.abs(lay.centre(i) - x) + (lay.active(i) ? 0f : 1e6f); // a lane that shows first
            if (dx < far) {
                far = dx;
                best = i;
            }
        }
        return best;
    }

    /**
     * Key n's movement as {@code h}: each event from its start as far as the next one, its end or the judgement
     * (cutting an eased event's end keeps its ease), {@link Note#HIDDEN_POSITION} where no event moves it before the
     * judgement (a moment short of it counts as reaching it), the last ending at the judgement.
     */
    static void move(Note n, Fn h) {
        float j = n.judgement();
        List<Events> m = n.usesDefaultApproach() ? Collections.singletonList(Note.defaultApproachEvent(j))
            : n.movementEvents();
        for (int i = 0; i < m.size(); i++) {
            Events e = m.get(i);
            float s = e.startTime();
            if (s > j) break;
            float next = i + 1 < m.size() ? m.get(i + 1).startTime() : Float.POSITIVE_INFINITY;
            float end = Math.max(s, Math.min(Math.min(e.endTime(), next), j));
            float xf = e.valueAt(end);
            h.segs.add(new Seg(s, end, end > s ? e.initialValue() : xf, xf, ease(e)));
            float gap = Math.min(next, j);
            boolean reaches = next > j && j - e.endTime() <= Note.APPROACH_PROBE_BEATS;
            if (e.endTime() < gap && !reaches && xf != Note.HIDDEN_POSITION) {
                float off = Math.max(s, e.endTime());
                h.segs.add(new Seg(off, off, xf, Note.HIDDEN_POSITION, 1f)); // parked off screen
            }
        }
        if (h.isEmpty()) { // never moved before its judgement: unseen (a stream shows on its face from it)
            float x = n.isSustained() ? 0f : Note.HIDDEN_POSITION;
            h.segs.add(new Seg(j, j, x, x, 1f));
        } else if (h.lastTf() < j) {
            float x = h.at(j);
            h.segs.add(new Seg(j, j, x, x, 1f));
        }
    }

    /**
     * Parks key n off screen ({@link Note#HIDDEN_POSITION}) in {@code h} while its old opacity hides it before its
     * judgement (below {@link #CLEAR}, looked at every {@link #CLEAR_STEP} beats): a key that appears in place shows
     * from then on, where its move has it; one hidden at its judgement is judged unseen.
     */
    static void hide(Note n, Fn h) {
        if (n.disappearEvents().isEmpty() || h.isEmpty()) return;
        float j = n.judgement(), from = h.firstTi();
        List<float[]> runs = new ArrayList<>();
        float start = Float.NaN;
        int steps = (int) Math.min(1 << 16, Math.ceil((j - from) / CLEAR_STEP));
        for (int i = 0; i <= steps; i++) {
            float t = i == steps ? j : from + i * CLEAR_STEP;
            boolean clear = Note.shownOpacity(n.opacityAt(t)) < CLEAR;
            if (clear && Float.isNaN(start)) start = t;
            if (!clear && !Float.isNaN(start)) {
                runs.add(new float[] {start, t});
                start = Float.NaN;
            }
        }
        boolean unseen = !Float.isNaN(start); // hidden at its judgement
        if (unseen) runs.add(new float[] {start, j});
        if (runs.isEmpty()) return;
        List<Seg> src = new ArrayList<>(h.segs);
        h.segs.clear();
        for (int i = 0; i < src.size(); i++) {
            Seg s = src.get(i);
            float to = i + 1 < src.size() ? src.get(i + 1).ti : Float.POSITIVE_INFINITY;
            if (!(to > s.ti) && i + 1 < src.size()) continue; // replaced at once
            float p = s.ti;
            for (float[] r : runs) {
                if (r[1] <= p || r[0] >= to) continue;
                if (r[0] > p) piece(h, s, p);
                p = Math.max(p, r[1]);
            }
            if (p < to && !(unseen && p >= j)) piece(h, s, p);
        }
        for (float[] r : runs) h.segs.add(new Seg(r[0], r[0], Note.HIDDEN_POSITION, Note.HIDDEN_POSITION, 1f));
        if (unseen) h.segs.add(new Seg(j, j, Note.HIDDEN_POSITION, Note.HIDDEN_POSITION, 1f));
        h.sort();
    }

    /**
     * Adds simple function s from beat p on: as it is from its start; else what is left of it (a straight one cut,
     * an eased one fitted by halves, since cutting its start changes its curve), or its end value.
     */
    private static void piece(Fn h, Seg s, float p) {
        if (p <= s.ti) h.segs.add(s);
        else if (p >= Math.max(s.ti, s.tf)) h.segs.add(new Seg(p, p, Note.HIDDEN_POSITION, s.xf, 1f));
        else if (s.e == 1f) h.segs.add(new Seg(p, s.tf, s.at(p), s.xf, 1f));
        else halve((t, out) -> out[0] = s.at(t), 0, p, s.tf, 0, TOL, new float[] {s.e, 1f, 2f, 3f, 0.5f, 1f / 3f},
            h.segs, new float[1]);
    }

    /**
     * Stream, stream mine or tide n's length as {@code H}: its height until its judgement, then down to 0 at its end
     * (the old rule), with its height events over it exactly; the last ending at its end.
     */
    static void height(Note n, Fn hh) {
        float j = n.judgement(), d = n.duration(), end = j + d, top = n.height();
        List<Events> ev = n.heightEvents();
        float at = j; // how far the old picture is written
        for (int i = 0; i < ev.size(); i++) {
            Events e = ev.get(i);
            float s = e.startTime();
            if (s >= end) break;
            float next = i + 1 < ev.size() ? ev.get(i + 1).startTime() : Float.POSITIVE_INFINITY;
            float stop = Math.max(s, Math.min(Math.min(e.endTime(), next), end));
            float xf = e.valueAt(stop), xi = stop > s ? e.initialValue() : xf;
            if (hh.isEmpty()) {
                if (s > j) rule(hh, j, s, j, d, top); // the old rule up to it
                else if (xi != top) hh.segs.add(new Seg(s, s, top, top, 1f)); // its height before it
            } else if (s > at) {
                rule(hh, at, s, j, d, top);
            }
            hh.segs.add(new Seg(s, stop, xi, xf, ease(e)));
            at = stop;
        }
        if (hh.isEmpty() || at < end) rule(hh, at, end, j, d, top);
    }

    /** Adds the old length rule over {@code [a, b]}: {@code top} until j, then down to 0 at j + d, linear. */
    private static void rule(Fn hh, float a, float b, float j, float d, float top) {
        float ya = ruleAt(a, j, d, top);
        if (a < j) {
            float m = Math.min(b, j);
            hh.segs.add(new Seg(a, a, top, top, 1f));
            if (m >= b) return;
            a = m;
            ya = top;
        }
        hh.segs.add(new Seg(a, b, ya, ruleAt(b, j, d, top), 1f));
    }

    private static float ruleAt(float t, float j, float d, float top) {
        if (!(d > 0f) || t <= j) return top;
        return Math.max(0f, top * (1f - (t - j) / d));
    }

    /** @return event e's ease as a simple function's exponent (a custom ease as linear) */
    private static float ease(Events e) {
        float k = e.exponent();
        return e.hasCurve() || !(k > 0f) || k == Float.POSITIVE_INFINITY ? 1f : k;
    }

    // ------------------------------------------------------------------ camera, veils, curtains

    /** Sets the camera position P from the old view's turn, distance and lens (none: the default). */
    private static void camera(Chart old, Chart7 c) {
        if (!old.hasView()) return;
        Changes ch = new Changes();
        ch.add(old.viewYaw());
        ch.add(old.viewPitch());
        ch.add(old.viewFov());
        ch.add(old.viewDist());
        fit((t, out) -> {
            float yaw = Events.calculateValue(old.viewYaw(), t, 0f);
            float pitch = Math.max(-89f, Math.min(89f, Events.calculateValue(old.viewPitch(), t, 0f)));
            float fov = Math.max(1f, Math.min(150f, Events.calculateValue(old.viewFov(), t, 11f)));
            double half = Math.tan(Math.toRadians(fov) * 0.5);
            float dist = Events.calculateValue(old.viewDist(), t, (float) (Sea.MID_Y / half));
            // as far as the 11° lens must be to show the columns' middle as large
            double r = (dist + Sea.MID_Z) * half / TAN_LENS;
            double cp = Math.cos(Math.toRadians(pitch));
            out[0] = (float) (-r * cp * Math.sin(Math.toRadians(yaw)));
            out[1] = (float) (r * Math.sin(Math.toRadians(pitch)));
            out[2] = (float) (-r * cp * Math.cos(Math.toRadians(yaw)));
        }, ch, new Fn[] {c.camera.x, c.camera.y, c.camera.z}, new float[] {TOL, TOL, TOL});
    }

    /**
     * Adds veil v (a curtain when {@code curtain}, with its blur): a circle as an ellipsoid, a polygon as its box; 0
     * while hidden.
     */
    private static void volume(Veil v, boolean curtain, Chart7 c) {
        if (!v.isValid()) return;
        Volume7 o = new Volume7();
        o.curtain = curtain;
        o.ellipsoid = v.shape() == Veil.Shape.CIRCLE;
        Changes ch = new Changes();
        ch.add(v.xEvents());
        ch.add(v.yEvents());
        ch.add(v.rotationEvents());
        ch.add(v.opacityEvents());
        ch.add(v.space());
        for (int i = 0; v.hasPointMoves() && i < v.pointCount(); i++) ch.add(v.pointMoves(i));
        if (curtain) ch.add(v.blurEvents());
        ch.at(v.startTime());
        ch.at(v.endTime());
        Fn[] fns = {o.V.x, o.V.y, o.V.z, o.p.x, o.p.y, o.p.z, o.b};
        float[] tol = {TOL, TOL, TOL, TOL, TOL, TOL, TOL};
        fit((t, out) -> {
            float sx = v.shapeX(t), sy = v.shapeY(t);
            float cx = 0f, cy = 0f, w, h, d;
            if (o.ellipsoid) {
                w = 2f * v.radius() * sx;
                h = 2f * v.radius() * sy;
                d = 2f * v.radius();
            } else {
                double a = Math.toRadians(Events.calculateValue(v.rotationEvents(), t, v.baseRotation()));
                float co = (float) Math.cos(a), si = (float) Math.sin(a);
                float x0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY;
                float y0 = Float.POSITIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
                for (int i = 0; i < v.pointCount(); i++) {
                    float px = v.pointXAt(i, t) * sx, py = v.pointYAt(i, t) * sy;
                    float x = px * co - py * si, y = px * si + py * co;
                    x0 = Math.min(x0, x);
                    x1 = Math.max(x1, x);
                    y0 = Math.min(y0, y);
                    y1 = Math.max(y1, y);
                }
                w = x1 - x0;
                h = y1 - y0;
                d = LaneLayout.DEFAULT_LENGTH;
                cx = 0.5f * (x0 + x1);
                cy = 0.5f * (y0 + y1);
            }
            boolean shown = t >= v.startTime() && t <= v.endTime() && v.opacityAt(t) > 0f;
            out[0] = shown ? w : 0f;
            out[1] = shown ? h : 0f;
            out[2] = shown ? d : 0f;
            out[3] = Events.calculateValue(v.xEvents(), t, v.baseX()) + cx - LaneLayout.TILE_CENTRE;
            out[4] = Events.calculateValue(v.yEvents(), t, v.baseY()) + cy - Sea.MID_Y;
            out[5] = v.zAt(t) - Sea.MID_Z;
            if (curtain) out[6] = v.blurAt(Math.max(v.startTime(), Math.min(v.endTime(), t))); // held outside
        }, ch, curtain ? fns : Arrays.copyOf(fns, 6), curtain ? tol : Arrays.copyOf(tol, 6));
        o.distortions = new ArrayList<>(v.distortions());
        if (!curtain) o.glitches = new ArrayList<>(v.glitches());
        c.volumes.add(o);
    }

    // ------------------------------------------------------------------ fitting

    /** Several functions of the beat at once. */
    interface Sampler {
        /** Puts each function's value at beat {@code t} into {@code out}. */
        void at(float t, float[] out);
    }

    /** The beats something changes at and the eases it changes by. */
    static final class Changes {
        private float[] times = new float[16];
        private int count;
        private float[] eases = {1f, 2f, 3f, 0.5f, 1f / 3f};
        private int easeCount = eases.length;

        void at(float t) {
            if (!(t > Float.NEGATIVE_INFINITY && t < Float.POSITIVE_INFINITY)) return;
            if (count == times.length) times = Arrays.copyOf(times, 2 * count);
            times[count++] = t;
        }

        void add(Events e) {
            if (e == null) return;
            at(e.startTime());
            at(e.endTime());
            float k = ease(e);
            for (int i = 0; i < easeCount; i++) if (eases[i] == k) return;
            if (easeCount == eases.length) eases = Arrays.copyOf(eases, 2 * easeCount);
            eases[easeCount++] = k;
        }

        void add(List<Events> l) {
            if (l != null) for (Events e : l) add(e);
        }

        void add(Channels c) {
            if (c != null) for (int i = 0; i < c.keys().length; i++) add(c.events(i));
        }

        /** @return the beats, sorted, each once */
        float[] beats() {
            float[] b = Arrays.copyOf(times, count);
            Arrays.sort(b);
            int m = 0;
            for (int i = 0; i < b.length; i++) if (m == 0 || b[i] > b[m - 1]) b[m++] = b[i];
            return Arrays.copyOf(b, m);
        }

        float[] eases() {
            return Arrays.copyOf(eases, easeCount);
        }
    }

    /**
     * Fits {@code fns} to the sampler: constant before the first change and after the last; between them each run of
     * stretches one simple function fits (within {@code tol}) is one, a stretch none fits is halved; a function that
     * never changes is its constant.
     */
    static void fit(Sampler s, Changes ch, Fn[] fns, float[] tol) {
        int q = fns.length;
        float[] b = ch.beats(), eases = ch.eases();
        float[] base = new float[q], after = new float[q], tmp = new float[q];
        s.at(b.length > 0 ? b[0] - 1f : 0f, base);
        int stretches = Math.max(0, b.length - 1);
        float[] ts = new float[stretches * SAMPLES];
        float[][] ys = new float[q][stretches * SAMPLES];
        for (int k = 0; k < stretches; k++) {
            for (int i = 0; i < SAMPLES; i++) {
                int x = k * SAMPLES + i;
                ts[x] = b[k] + (b[k + 1] - b[k]) * (i + 0.5f) / SAMPLES;
                s.at(ts[x], tmp);
                for (int g = 0; g < q; g++) ys[g][x] = tmp[g];
            }
        }
        if (b.length > 0) s.at(b[b.length - 1] + 1f, after);
        Fit f = new Fit(), f2 = new Fit();
        for (int g = 0; g < q; g++) {
            Fn fn = fns[g];
            float e = tol[g], held = base[g];
            int k = 0;
            while (k < stretches) {
                if (flat(ys[g], k, held, e)) {
                    k++;
                    continue;
                }
                fit(ts, ys[g], k * SAMPLES, (k + 1) * SAMPLES, b[k], b[k + 1], eases, f);
                if (f.err > e) { // no ease fits: halves
                    halve(s, g, b[k], b[k + 1], 0, e, eases, fn.segs, tmp);
                    held = fn.segs.get(fn.segs.size() - 1).xf;
                    k++;
                    continue;
                }
                int z = k;
                while (z + 1 < stretches && z + 1 - k < MAX_RUN && !flat(ys[g], z + 1, f.xf, e)) {
                    fit(ts, ys[g], k * SAMPLES, (z + 2) * SAMPLES, b[k], b[z + 2], eases, f2);
                    if (f2.err > e) break;
                    f.set(f2);
                    z++;
                }
                if (f.flat) { // a jump to a constant
                    fn.segs.add(new Seg(b[k], b[k], round(held), round(f.xi), 1f));
                } else {
                    fn.segs.add(new Seg(b[k], b[z + 1], round(f.xi), round(f.xf), f.e));
                }
                held = fn.segs.get(fn.segs.size() - 1).xf;
                k = z + 1;
            }
            if (b.length > 0 && Math.abs(after[g] - held) > e) {
                float t = b[b.length - 1];
                fn.segs.add(new Seg(t, t, round(held), round(after[g]), 1f));
            }
            if (fn.isEmpty()) {
                fn.constant = Math.abs(base[g] - fn.def) <= e ? fn.def : round(base[g]);
            } else if (Math.abs(fn.segs.get(0).xi - base[g]) > e) { // its value before the first change
                float t = fn.segs.get(0).ti;
                fn.segs.add(0, new Seg(t, t, round(base[g]), round(base[g]), 1f));
            }
        }
    }

    /** @return true when every sample of stretch k of {@code ys} is within {@code e} of {@code v} */
    private static boolean flat(float[] ys, int k, float v, float e) {
        for (int i = k * SAMPLES; i < (k + 1) * SAMPLES; i++) if (!(Math.abs(ys[i] - v) <= e)) return false;
        return true;
    }

    /** Fits function g over {@code [a, z]} by halves, at most {@link #MAX_DEPTH} deep, into {@code out}. */
    private static void halve(Sampler s, int g, float a, float z, int depth, float e, float[] eases, List<Seg> out,
                              float[] tmp) {
        float[] ts = new float[SAMPLES], ys = new float[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            ts[i] = a + (z - a) * (i + 0.5f) / SAMPLES;
            s.at(ts[i], tmp);
            ys[i] = tmp[g];
        }
        Fit f = new Fit();
        fit(ts, ys, 0, SAMPLES, a, z, eases, f);
        float m = 0.5f * (a + z);
        if (f.err <= e || depth >= MAX_DEPTH || !(m > a && m < z)) {
            out.add(new Seg(a, z, round(f.xi), round(f.xf), f.e));
            return;
        }
        halve(s, g, a, m, depth + 1, e, eases, out, tmp);
        halve(s, g, m, z, depth + 1, e, eases, out, tmp);
    }

    /** A simple function fitted to samples: its values, exponent and largest miss. */
    private static final class Fit {
        float xi, xf, e = 1f, err;
        /** True when it is a constant. */
        boolean flat;

        void set(Fit o) {
            flat = o.flat;
            xi = o.xi;
            xf = o.xf;
            e = o.e;
            err = o.err;
        }
    }

    /**
     * Fits {@code xi + (xf − xi)·p^e} over {@code [a, z]} to samples {@code from}..{@code to} (least squares for each
     * ease, the one missing least wins; a constant when the samples are within its miss).
     */
    private static void fit(float[] ts, float[] ys, int from, int to, float a, float z, float[] eases, Fit out) {
        float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
        for (int i = from; i < to; i++) {
            lo = Math.min(lo, ys[i]);
            hi = Math.max(hi, ys[i]);
        }
        out.xi = out.xf = 0.5f * (lo + hi);
        out.e = 1f;
        out.err = 0.5f * (hi - lo);
        out.flat = true;
        double len = z - a;
        for (float e : eases) {
            double suu = 0, sup = 0, spp = 0, suy = 0, spy = 0;
            for (int i = from; i < to; i++) {
                double p = Math.pow((ts[i] - a) / len, e), u = 1 - p;
                suu += u * u;
                sup += u * p;
                spp += p * p;
                suy += u * ys[i];
                spy += p * ys[i];
            }
            double det = suu * spp - sup * sup;
            if (!(Math.abs(det) > 1e-12)) continue;
            double xi = (suy * spp - spy * sup) / det, xf = (spy * suu - suy * sup) / det;
            double err = 0;
            for (int i = from; i < to; i++) {
                double p = Math.pow((ts[i] - a) / len, e);
                err = Math.max(err, Math.abs(xi + (xf - xi) * p - ys[i]));
            }
            if (err < out.err - 1e-6) {
                out.xi = (float) xi;
                out.xf = (float) xf;
                out.e = e;
                out.err = (float) err;
                out.flat = false;
            }
        }
    }

    /** @return v to a thousandth (what the file keeps), as is when far out */
    private static float round(float v) {
        return Math.abs(v) < 1e6f ? Math.round(v * 1000f) / 1000f : v;
    }
}
