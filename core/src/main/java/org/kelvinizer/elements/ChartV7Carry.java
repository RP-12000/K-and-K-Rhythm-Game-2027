package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.List;
import org.kelvinizer.Params;

/**
 * 旧谱换成 v7 时，画面离开自己轨道的键（横移、自己的 x、绕 pivot 转、公转）放到看不见的载体轨道上：载体轨道跟着旧画面里这个键的位置和
 * 转向走，键停在它的底面上；载体轨道自己不画（l 为 0），没有键的时候 o 为 0，时间不重叠的键轮流用同一条。
 *
 * <p>Carrier lanes for {@link ChartV7Convert} (Kelvin 2026-11-11 02:22 「然后把别的谱面也要这样改，下一次下发给别人的时候也要能同步修改别人的旧谱」, 02:27:
 * the keys that shift and turn come out as in the old picture, as in My Soul Abyss's port). A format v7 key moves only
 * along its lane, so a key of an older format whose picture leaves its lane (its sideways shift {@code offsetX}, its
 * own x, its turn about its pivot, its orbit) rides a lane of its own: while the key shows, the lane's origin is where
 * the old key's leading edge was drawn and its fall is turned by the key's turn, sampled every {@link #STEP} beats and
 * at every change and fitted with straight pieces; the key sits on the lane's face ({@code h} 0 while it shows, parked
 * off screen while it does not), so it is judged at its old beat where it is drawn. The lane draws nothing of its own
 * ({@link Lane7#l} 0) and shows its key only while the key shows ({@link Lane7#o}); keys that do not show at the same
 * time share one. Positions are those of a 16 : 10 screen, where the old picture is the reference one (the old
 * drawing's sizes as {@code NoteShapes} has them at note size 1).
 */
final class ChartV7Carry {
    /** How often a carried key's picture is sampled, beats. */
    static final float STEP = 1f / 32f;
    /** How far from its lane's picture a key may be drawn without being carried, reference units. */
    static final float DRIFT = 0.5f;
    /** The same for its turn: the largest sine of it. */
    static final float DRIFT_SIN = 1e-3f;
    /** How far a straight piece may miss a sampled position, reference units. */
    static final float TOL = 0.5f;
    /** Beats a shared carrier keeps free between two keys' shown stretches. */
    static final float PAD = STEP;
    /** A key's and a mine's drawn height at note size 1 ({@code NoteRenderer.NOTE_H}, {@code MINE_H}). */
    private static final float NOTE_H = 40f, MINE_H = 32f;
    /**
     * A wave's or tide's bar: its height at note size 1, its inset from the window's ends and the least cosine its
     * stretch along a turned bar takes ({@code NoteRenderer.tideHeight()}, {@code TIDE_INSET},
     * {@code NoteShapes.TIDE_TURN_MIN_COS}).
     */
    private static final float TIDE_H = 18f, TIDE_INSET = 12f, TIDE_MIN_COS = 0.2f;
    /**
     * How much nearer its line a key is drawn than its distance says, at the default hit point
     * ({@code NoteRenderer.hitShift()}): along its line's up axis in the old picture, along its carrier's when carried.
     */
    private static final float HIT_SHIFT = NOTE_H * 0.5f;
    /** {@code NoteShapes.BODY_REF_K} and {@code NoteShapes.CENTRE_EASE}: a key's edge eases in near its line. */
    private static final float BODY_REF_K = Params.REF_H / 1080f, CENTRE_EASE = 1000f / 3f;
    /** How long the old picture faded a key in where it appeared on screen, ms ({@code NoteRenderer.FADE_IN_MS}). */
    private static final float FADE_IN_MS = 150f;
    /** Below this a key is shown ({@link Note#HIDDEN_POSITION} less 1, as the drawing parks it). */
    private static final float SHOWN = Note.HIDDEN_POSITION - 1f;
    /** Functions fitted for a carrier: fall x, y; origin x, y, z; width; length. */
    private static final int FUNCTIONS = 7;
    /** Floats per sample: its beat, then the functions. */
    private static final int ROW = FUNCTIONS + 1;

    private final Chart old;
    private final LaneLayout lay;
    private final List<Lane7> carriers = new ArrayList<>();
    /** Each carrier's busy times, as {from, to} pairs. */
    private final List<List<float[]>> busy = new ArrayList<>();
    private final int firstId;
    private final float[] pose = new float[3];
    /** The distance of the last shown sample, held where a stretch ends as its key is parked. */
    private float lastPos;

    /** @param lanes the chart's own lanes (the carriers' ids follow theirs) */
    ChartV7Carry(Chart old, LaneLayout lay, List<Lane7> lanes) {
        this.old = old;
        this.lay = lay;
        int top = 0;
        for (Lane7 l : lanes) top = Math.max(top, l.id);
        firstId = (top / 100 + 1) * 100 + 1;
    }

    /** @return the carriers made so far, to add after the chart's own lanes */
    List<Lane7> lanes() {
        return carriers;
    }

    /**
     * Puts key k (made from old key n) on a carrier when its old picture leaves its lane while it shows: its lane
     * becomes the carrier and its {@code h} 0 where it shows. A wave or tide over the field that turns gets one carrier
     * a lane, each lane's piece where the old turned bar drew it ({@link #tide}). Keys in a sea, on a path through
     * space or on a lane turned in space stay as they are.
     */
    void carry(Note n, Key7 k) {
        if (!candidate(n) || k.h.isEmpty()) return;
        float a = k.h.firstTi(), b = k.endBeat();
        if (!(b >= a)) return;
        Fn faced = face(k.h); // shown or parked, cut where it crosses
        List<float[]> shown = shown(faced, a, b);
        if (shown.isEmpty()) return;
        boolean span = k.kind.spans();
        if (span && (n.spanCount() > 0 || !moves(n.rotationEvents()))) return;
        int lane = span ? -1 : lay.indexOf(k.lane);
        if (!span && lane < 0) return;
        int pieces = span ? lay.count() : 1;
        // samples: beat, then the functions, a run per shown stretch and piece; does the key leave its lane anywhere?
        List<List<float[]>> runs = new ArrayList<>();
        for (int p = 0; p < pieces; p++) runs.add(new ArrayList<>());
        boolean drifts = false;
        for (float[] w : shown) {
            lastPos = k.h.at(0.5f * (w[0] + w[1])); // shown there
            float[] ts = times(n, w[0], w[1]);
            float[][] s = new float[pieces][ts.length * ROW];
            for (int i = 0; i < ts.length; i++) {
                float t = ts[i];
                for (float[] r : s) r[i * ROW] = t;
                if (span) {
                    drifts |= tide(n, k, t, s, i * ROW + 1);
                } else {
                    if (!sample(n, k, lane, t, s[0], i * ROW + 1)) return; // a lane turned in space: as it is
                    drifts |= drift(n, lane, t, k.h.at(t));
                }
            }
            for (int p = 0; p < pieces; p++) runs.get(p).add(s[p]);
        }
        if (!drifts) return;
        int[] ids = new int[pieces];
        for (int p = 0; p < pieces; p++) {
            Lane7 c = carrier(shown); // busy only while its key shows: parked, the key leaves it to others
            for (float[] s : runs.get(p)) write(c, s);
            for (float[] w : shown) { // shows its key while it shows
                c.o.segs.add(new Seg(w[0], w[0], 0f, 1f, 1f));
                c.o.segs.add(new Seg(w[1], w[1], 1f, 0f, 1f));
            }
            c.o.sort();
            ids[p] = c.id;
        }
        k.h.segs.clear(); // on the carrier's face while it shows
        k.h.segs.addAll(faced.segs);
        if (span) k.lanes = ids;
        else k.lane = ids[0];
        float in = fadeIn(n);
        if (in > 0f) { // on its face it no longer fades in where it appears: in its opacity instead
            k.o.segs.clear();
            ChartV7Convert.fade(n, k, in);
        }
    }

    /**
     * @return the beats over which the old picture faded key n in where it appeared ({@code NoteRenderer.fadeIn}:
     *         {@link #FADE_IN_MS} from its first visible beat, when it appeared on screen), 0 when it did not
     */
    private float fadeIn(Note n) {
        float v = n.visibleFrom();
        if (!(v < Float.POSITIVE_INFINITY)) return 0f;
        float sp = n.positionAt(v);
        if (!(sp > 0f) || sp >= SHOWN) return 0f;
        float line = old.lineEvents().yAt(n.line(), v);
        float y = n.isUpper() ? line - sp : line + sp;
        if (y <= 0f || y >= Params.REF_H) return 0f;
        return (float) (old.msToBeat(old.beatToMs(v) + FADE_IN_MS) - v);
    }

    /** @return true when old key n may leave its lane: it shifts, has its own x, turns or has an orbit */
    private static boolean candidate(Note n) {
        if (n.sea() != null || n.spaceTrack() || n.space() != null) return false;
        if (n.orbit() != null || n.laneless() || n.between() || n.offsetX() != 0f) return true;
        return moves(n.offsetXEvents()) || moves(n.rotationEvents());
    }

    /** @return true when some event of {@code l} has a value other than 0 */
    private static boolean moves(List<Events> l) {
        if (l == null) return false;
        for (Events e : l) if (e.initialValue() != 0f || e.finalValue() != 0f) return true;
        return false;
    }

    /**
     * @return {@code h} with the key on its carrier's face (0) where it shows and parked where it does not: a segment
     *         that moves it across {@link #SHOWN} (flying off, Kelvin's 氷点 Abyss 397~399) cut where it crosses, so
     *         the key does not move along its carrier as well (the carrier follows its old picture already)
     */
    private static Fn face(Fn h) {
        Fn f = new Fn(h.def);
        int n = h.segs.size();
        for (int i = 0; i < n; i++) {
            Seg s = h.segs.get(i);
            float a = s.xi < SHOWN ? 0f : Note.HIDDEN_POSITION, b = s.xf < SHOWN ? 0f : Note.HIDDEN_POSITION;
            float t = a == b || !(s.tf > s.ti) ? Float.NaN : cross(s);
            if (!(t == t)) {
                f.segs.add(new Seg(s.ti, s.tf, a, b, s.e));
            } else if (i + 1 < n && h.segs.get(i + 1).ti <= t) { // the next one takes over before it crosses
                f.segs.add(new Seg(s.ti, s.tf, a, a, 1f));
            } else {
                f.segs.add(new Seg(s.ti, t, a, a, 1f));
                f.segs.add(new Seg(t, s.tf, b, b, 1f));
            }
        }
        return f;
    }

    /** @return where segment s, from one side of {@link #SHOWN} to the other, first is on the other side */
    private static float cross(Seg s) {
        boolean in = s.xi < SHOWN;
        float lo = s.ti, hi = s.tf;
        for (int i = 0; i < 32; i++) {
            float m = 0.5f * (lo + hi);
            if (s.at(m) < SHOWN == in) lo = m;
            else hi = m;
        }
        return hi;
    }

    /** @return the stretches of {@code [a, b]} where {@code h} shows its key (below {@link #SHOWN}), as {from, to} */
    private static List<float[]> shown(Fn h, float a, float b) {
        List<Float> cut = new ArrayList<>();
        cut.add(a);
        for (Seg s : h.segs) {
            if (s.ti > a && s.ti < b) cut.add(s.ti);
            if (s.tf > a && s.tf < b) cut.add(s.tf);
        }
        cut.add(b);
        cut.sort(null);
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < cut.size(); i++) {
            float p = cut.get(i), q = cut.get(i + 1);
            if (!(q > p) || !(h.at(0.5f * (p + q)) < SHOWN)) continue;
            float[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last[1] >= p) last[1] = q;
            else out.add(new float[] {p, q});
        }
        if (out.isEmpty() && h.at(a) < SHOWN) out.add(new float[] {a, b}); // shown at one beat only
        return out;
    }

    /**
     * @return the beats key n is sampled at over {@code [p, q]}: every {@link #STEP} and where its moves or its line's
     *         change
     */
    private float[] times(Note n, float p, float q) {
        ChartV7Convert.Changes ch = new ChartV7Convert.Changes();
        ch.add(n.movementEvents());
        ch.add(n.rotationEvents());
        ch.add(n.offsetXEvents());
        ch.add(n.isUpper() ? old.lineEvents().upperEvents() : old.lineEvents().lowerEvents()); // its line's shakes
        NoteOrbit o = n.orbit();
        if (o != null) {
            ch.add(o.xEvents());
            ch.add(o.yEvents());
            ch.add(o.radiusEvents());
            ch.add(o.angleEvents());
            ch.at(n.orbitFrom());
            ch.at(n.orbitTo());
        }
        for (int i = 0; p + i * STEP < q; i++) ch.at(p + i * STEP);
        ch.at(q);
        float[] all = ch.beats();
        int m = 0;
        for (float t : all) if (t >= p && t <= q) all[m++] = t;
        return java.util.Arrays.copyOf(all, m);
    }

    /**
     * Writes the carrier's functions for key n (lane index {@code lane}) at beat t into {@code s} from {@code o}: fall
     * x, y; origin x, y, z; width; length.
     *
     * @return false when the key's lane is turned in space then (it is left as it is)
     */
    private boolean sample(Note n, Key7 k, int lane, float t, float[] s, int o) {
        lay.update(t, LaneLayout.VIEW_LEFT, LaneLayout.VIEW_RIGHT);
        int i = lay.keyLane(n) >= 0 ? lay.keyLane(n) : lane;
        if (Math.abs(lay.pose(i, LaneLayout.P_FX)) > 1e-6f || Math.abs(lay.pose(i, LaneLayout.P_FZ)) > 1e-6f
            || !(lay.pose(i, LaneLayout.P_FY) < 0f)) {
            return false;
        }
        old(n, t, held(k, t), pose);
        double r = Math.toRadians(pose[2] - (n.isUpper() ? 180f : 0f));
        float len = lay.chartLength(i);
        put(s, o, (float) Math.sin(r), (float) Math.cos(r), pose[0], pose[1], n.isUpper(), lay.moveZ(i), len,
            n.laneless() || n.between() ? lay.keyWidth(n) : lay.columnWidth(i));
        return true;
    }

    /**
     * Writes the carriers' functions for wave or tide n at beat t, one piece a lane ({@code s[i]} from {@code o}): as
     * {@code NoteRenderer.tideTurn} draws a turned one, its bar turned about its pivot and stretched to cross the
     * window, each lane's piece over that lane.
     *
     * @return true when it is turned then
     */
    private boolean tide(Note n, Key7 k, float t, float[][] s, int o) {
        lay.update(t, LaneLayout.VIEW_LEFT, LaneLayout.VIEW_RIGHT);
        boolean up = n.isUpper();
        float pos = held(k, t) - HIT_SHIFT, line = old.lineEvents().yAt(n.line(), t);
        float y = up ? line - pos : line + pos; // the bar's leading edge
        double r = Math.toRadians(n.angleAt(t));
        float sin = (float) Math.sin(r), cos = (float) Math.cos(r);
        float x0 = lay.windowLeft() + TIDE_INSET, x1 = lay.windowRight() - TIDE_INSET;
        float oy = up ? y - TIDE_H * 0.5f : y + TIDE_H * 0.5f;
        float px = 0.5f * (x0 + x1) + (up ? -n.pivotX() : n.pivotX()), py = oy + (up ? -n.pivotY() : n.pivotY());
        float c = Math.abs(cos) >= TIDE_MIN_COS ? cos : cos < 0f ? -TIDE_MIN_COS : TIDE_MIN_COS;
        float tk = 1f / c, ta = px + (sin * (oy - py) - px) * tk;
        for (int i = 0; i < s.length; i++) {
            float cw = lay.active(i) ? lay.columnWidth(i) : 0f;
            float lx = ta + (lay.columnLeft(i) + 0.5f * cw) * tk - px, ly = y - py; // from the pivot, unturned
            put(s[i], o, sin, cos, px + cos * lx - sin * ly, py + sin * lx + cos * ly, up, lay.moveZ(i),
                lay.chartLength(i), cw * Math.abs(tk));
        }
        return Math.abs(sin) > DRIFT_SIN || cos < 0f;
    }

    /** @return key k's distance at beat t; where it is parked, its last shown one ({@link #lastPos}) */
    private float held(Key7 k, float t) {
        float pos = k.h.at(t);
        if (pos < SHOWN) lastPos = pos;
        return lastPos;
    }

    /**
     * Writes a carrier's sample from {@code o}: the fall turned (sin, cos), the origin where a key drawn with its
     * leading edge's centre at (x, y) has it ({@code up}: on the sky line), its depth, its length and width.
     */
    private static void put(float[] s, int o, float sin, float cos, float x, float y, boolean up, float z, float len,
                            float w) {
        s[o] = sin;
        s[o + 1] = -cos;
        // a carried key is drawn HIT_SHIFT towards its face along its carrier's up axis (−u): the origin goes back
        float back = up ? HIT_SHIFT : -HIT_SHIFT;
        s[o + 2] = x + back * sin - LaneLayout.TILE_CENTRE;
        s[o + 3] = y - back * cos - Sea.MID_Y;
        s[o + 4] = z - 0.5f * (LaneLayout.DEFAULT_LENGTH - len);
        s[o + 5] = w;
        s[o + 6] = len;
    }

    /**
     * Old key n's picture at beat t, {@code pos} from its line: its leading edge's centre (x, y, reference units) and
     * its drawn turn (degrees, +180 on the sky line) into {@code out}, as {@code NoteRenderer} and {@code NoteShapes}
     * draw it on a 16 : 10 screen.
     */
    private void old(Note n, float t, float pos, float[] out) {
        boolean up = n.isUpper();
        pos -= HIT_SHIFT; // as drawn
        float x = lay.keyCentre(n, t) + n.offsetXAt(t);
        float line = old.lineEvents().yAt(n.line(), t) + lay.keyLift(n);
        float y = up ? line - pos : line + pos;
        float rot = n.angleAt(t) + (up ? 180f : 0f);
        float h = n.type() == NoteType.MINE ? MINE_H : NOTE_H;
        out[2] = rot;
        anchor(n, t, x, y, pos, h, rot, out);
    }

    /** {@code NoteShapes.anchorX} and {@code anchorY} with the field unstretched (refH = h at note size 1). */
    private static void anchor(Note n, float t, float x, float y, float pos, float h, float rot, float[] out) {
        boolean up = n.isUpper();
        NoteOrbit o = n.orbit();
        double r = Math.toRadians(rot);
        float cos = (float) Math.cos(r), sin = (float) Math.sin(r);
        float lift = (h * 0.5f * BODY_REF_K - h * 0.5f) * ease(pos) + h * 0.5f; // its head's centre from its line
        if (o == null || (o.polar() && !n.inOrbitSpan(t))) { // about its pivot (公转 off: its head's centre)
            float px = o == null ? n.pivotX() : 0f, py = o == null ? n.pivotY() : 0f;
            float vy = py + h * 0.5f, d = lift + py;
            out[0] = x + (up ? -px : px) - cos * px + sin * vy;
            out[1] = y + (up ? -d : d) - sin * px - cos * vy;
        } else if (o.polar()) { // placed by its orbit's radius and angle
            double a = Math.toRadians(o.angleAt(t));
            float rad = o.radiusAt(t);
            out[0] = o.xAt(t) + rad * (float) Math.cos(a) + sin * h * 0.5f;
            float head = o.conflict() == NoteOrbit.CONFLICT_MOVE ? (up ? y - lift : y + lift)
                : o.yAt(t) + rad * (float) Math.sin(a);
            out[1] = head - cos * h * 0.5f;
        } else { // turned about its orbit's centre, which moves
            float vx = x - o.x(), vy = (up ? y - lift : y + lift) - o.y();
            double a = Math.toRadians(rot - (up ? 180f : 0f));
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            out[0] = o.xAt(t) + (c * vx - s * vy) + sin * h * 0.5f;
            out[1] = o.yAt(t) + (s * vx + c * vy) - cos * h * 0.5f;
        }
    }

    /** {@code NoteShapes.centreEase}: 0 at or behind the line, rising (smoothstep) to 1 at its distance. */
    private static float ease(float pos) {
        if (!(pos > 0f)) return 0f;
        float t = Math.min(pos / CENTRE_EASE, 1f);
        return t * t * (3f - 2f * t);
    }

    /** @return true when key n at beat t ({@code pos} from its line) is drawn off its lane or turned */
    private boolean drift(Note n, int lane, float t, float pos) {
        if (!(pos < SHOWN)) return false;
        old(n, t, pos, pose);
        float x = pose[0], y = pose[1];
        double r = Math.toRadians(pose[2]);
        if (Math.abs(Math.sin(r)) > DRIFT_SIN || Math.cos(r) * (n.isUpper() ? -1 : 1) < 0) return true;
        boolean up = n.isUpper();
        float h = n.type() == NoteType.MINE ? MINE_H : NOTE_H;
        float line = old.lineEvents().yAt(n.line(), t) + lay.lift(lane, n.line());
        float d = pos - HIT_SHIFT;
        float lift = (h * 0.5f * BODY_REF_K - h * 0.5f) * ease(d);
        float ly = up ? line - d - lift : line + d + lift;
        return Math.abs(x - lay.centre(lane)) > DRIFT || Math.abs(y - ly) > DRIFT;
    }

    /** @return a carrier free over the stretches (and {@link #PAD} round each), a new one when none is */
    private Lane7 carrier(List<float[]> stretches) {
        for (int i = 0; i < carriers.size(); i++) {
            if (free(busy.get(i), stretches)) {
                busy.get(i).addAll(stretches);
                return carriers.get(i);
            }
        }
        Lane7 c = new Lane7(firstId + carriers.size(), 0f);
        c.S.constant = c.F.constant = c.s.constant = c.f.constant = 0f; // a flat lane: the key on its origin
        c.l.constant = 0f; // draws nothing of its own
        carriers.add(c);
        busy.add(new ArrayList<>(stretches));
        return c;
    }

    /** @return true when no stretch meets a busy one (within {@link #PAD}) */
    private static boolean free(List<float[]> busy, List<float[]> stretches) {
        for (float[] w : busy) {
            for (float[] s : stretches) if (w[0] < s[1] + PAD && s[0] < w[1] + PAD) return false;
        }
        return true;
    }

    /**
     * Fits the samples {@code s} of one shown stretch into carrier c's functions from the stretch's start: the fall's
     * two components on shared pieces whose turn the key's half width (or its height) misses by at most {@link #TOL}
     * at its edge, the others by {@link #fit}.
     */
    private static void write(Lane7 c, float[] s) {
        int n = s.length / ROW;
        float reach = NOTE_H;
        for (int m = 0; m < n; m++) reach = Math.max(reach, 0.5f * s[m * ROW + 6]);
        fall(c.u.x, c.u.y, s, n, TOL / reach);
        Fn[] fns = {c.v.x, c.v.y, c.v.z, c.w, c.h};
        for (int g = 2; g < FUNCTIONS; g++) fit(fns[g - 2], s, ROW, g, TOL);
    }

    /**
     * Fits function g of samples {@code s} (rows of {@code row} floats: the beat, then the functions) into fn from the
     * first sample's beat: straight pieces each within tol of the samples they pass, one value when all are.
     */
    static void fit(Fn fn, float[] s, int row, int g, float tol) {
        int n = s.length / row;
        boolean flat = true;
        for (int m = 1; m < n && flat; m++) flat = Math.abs(s[m * row + 1 + g] - s[1 + g]) <= tol;
        if (flat) fn.segs.add(new Seg(s[0], s[0], s[1 + g], s[1 + g], 1f));
        for (int i = 0; i < n - 1 && !flat; ) {
            final int a = i;
            int j = reach(i, n, b -> straight(s, row, g, a, b, tol));
            fn.segs.add(new Seg(s[i * row], s[j * row], s[i * row + 1 + g], s[j * row + 1 + g], 1f));
            i = j;
        }
        fn.sort();
    }

    /** Writes the fall (x into {@code fx}, y into {@code fy}) so its turn stays within {@code tol} radians. */
    private static void fall(Fn fx, Fn fy, float[] s, int n, float tol) {
        boolean still = true;
        for (int m = 1; m < n && still; m++) still = turn(s, 0, s[m * ROW + 1], s[m * ROW + 2], tol);
        if (still) {
            fx.segs.add(new Seg(s[0], s[0], s[1], s[1], 1f));
            fy.segs.add(new Seg(s[0], s[0], s[2], s[2], 1f));
        }
        for (int i = 0; i < n - 1 && !still; ) {
            final int a = i;
            int j = reach(i, n, b -> turns(s, a, b, tol));
            float ti = s[i * ROW], tj = s[j * ROW];
            if (s[i * ROW + 1] * s[j * ROW + 1] + s[i * ROW + 2] * s[j * ROW + 2] > 0f) {
                fx.segs.add(new Seg(ti, tj, s[i * ROW + 1], s[j * ROW + 1], 1f));
                fy.segs.add(new Seg(ti, tj, s[i * ROW + 2], s[j * ROW + 2], 1f));
            } else { // turned past a right angle within a step: at once, not through no fall at all
                fx.segs.add(new Seg(ti, tj, s[i * ROW + 1], s[i * ROW + 1], 1f));
                fy.segs.add(new Seg(ti, tj, s[i * ROW + 2], s[i * ROW + 2], 1f));
                fx.segs.add(new Seg(tj, tj, s[j * ROW + 1], s[j * ROW + 1], 1f));
                fy.segs.add(new Seg(tj, tj, s[j * ROW + 2], s[j * ROW + 2], 1f));
            }
            i = j;
        }
        fx.sort();
        fy.sort();
    }

    /** @return true when the fall from sample i straight to sample j turns within tol of every sample between */
    private static boolean turns(float[] s, int i, int j, float tol) {
        float t0 = s[i * ROW], t1 = s[j * ROW];
        float x0 = s[i * ROW + 1], y0 = s[i * ROW + 2], x1 = s[j * ROW + 1], y1 = s[j * ROW + 2];
        if (!(t1 > t0) || !(x0 * x1 + y0 * y1 > 0f)) return j == i + 1;
        for (int m = i + 1; m < j; m++) {
            float p = (s[m * ROW] - t0) / (t1 - t0);
            if (!turn(s, m, x0 + (x1 - x0) * p, y0 + (y1 - y0) * p, tol)) return false;
        }
        return true;
    }

    /** @return true when fall (x, y) is within tol radians of sample i's */
    private static boolean turn(float[] s, int i, float x, float y, float tol) {
        float sx = s[i * ROW + 1], sy = s[i * ROW + 2];
        float cross = x * sy - y * sx, dot = x * sx + y * sy;
        return dot > 0f && Math.abs(cross) <= tol * dot;
    }

    /** @return the furthest sample after i a piece from i may reach, as {@code fits} says: doubling, then halving */
    private static int reach(int i, int n, java.util.function.IntPredicate fits) {
        int good = i + 1, step = 1;
        while (good + step < n && fits.test(good + step)) {
            good += step;
            step <<= 1;
        }
        int bad = Math.min(good + step, n);
        while (bad - good > 1) {
            int mid = (good + bad) >>> 1;
            if (fits.test(mid)) good = mid;
            else bad = mid;
        }
        return good;
    }

    /** @return true when the straight line from sample i to sample j of function g passes all between within tol */
    private static boolean straight(float[] s, int row, int g, int i, int j, float tol) {
        float t0 = s[i * row], t1 = s[j * row], y0 = s[i * row + 1 + g], y1 = s[j * row + 1 + g];
        if (!(t1 > t0)) return j == i + 1;
        for (int m = i + 1; m < j; m++) {
            float y = y0 + (y1 - y0) * (s[m * row] - t0) / (t1 - t0);
            if (!(Math.abs(y - s[m * row + 1 + g]) <= tol)) return false;
        }
        return true;
    }
}
