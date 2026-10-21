package org.kelvinizer.gameplay;

import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.Events;
import org.kelvinizer.elements.JudgementLine;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteType;

import java.util.List;

/**
 * 屏幕边缘入场：出生点在参考画面之外、音符本体在出生时却会露进实际可见区域的音符（场地拉伸到屏幕之后，就是任何比例下
 * 转了角度的音符），不再在画面里凭空出现，而是按第一段移动的平均速度从真正的屏幕边缘滑进来，再接上谱面写好的路径。只改绘制，不改判定。
 *
 * <p>Edge entry (DESIGN §5.4; Kelvin 2026-10-12 12:42 「我想要整个谱面出现得更自然」), pure Java: no GL,
 * no allocation after {@link #begin}. A note <em>enters from the edge</em> when
 * <ul>
 *   <li>its first movement (its first {@code MovementEvent}, or the default approach) moves toward
 *       its line: {@code v = (initial − final) / (end − start) > 0} ref units per beat;</li>
 *   <li>its authored spawn {@code sp} (its distance at that start beat) lies outside the reference
 *       1920 × 1200 frame: ground {@code lineY + sp ≥ 1200}, sky {@code lineY − sp ≤ 0}, with the
 *       line's y at the start beat;</li>
 *   <li>and the body it draws at the spawn, in the pose of that beat and at the current note
 *       size, reaches inside the actual visible area ({@link #update}): its leading edge, or
 *       what lies beyond it toward the line, which is the turned head's corners, a tail turned
 *       toward the line, a LANE MINE column, the studs of a mine, a TIDE's pearls or the base
 *       line of its wave. Soft glows do not count. With the playfield stretched to the screen
 *       ({@link Field}) the reference frame's edges are the screen's at every aspect, so this is a
 *       turned note whose corner would show inside on its first frame (and, unstretched, every spawn
 *       point in the extra band of a 16:10, 3:2 or 4:3 screen).</li>
 * </ul>
 * Before its first movement such a note is drawn at {@code sp + v·(start − beat)}: it slides in
 * from beyond the visible edge and joins its authored path at the start beat at the same distance
 * (at the event's average speed). Its pose (angle, opacity, tail length) keeps the values of the
 * start beat ({@link #look}) and it does not fade in. The slide begins at the note's <em>entry
 * beat</em>, where everything it draws toward its line is still off screen: its leading edge
 * {@link #MARGIN} beyond the edge (the largest draw-cull margin, which also clears every glow),
 * plus what a turned body reaches, with the line at its nearest to that edge on the way. Before
 * the entry beat the note stays parked at {@link Note#HIDDEN_POSITION}, as before its first
 * movement. Spawns inside the reference frame (deliberate on-screen appearances) and notes whose
 * first movement does not approach the line keep the 80-unit fade-in. An upright note spawning
 * on or just beyond the reference frame's edge (the default 1080) keeps its body off screen, so the
 * entry changes only turned notes that used to show a corner. The engine and judging never read
 * this class; the screen starts the run before the first note can be drawn ({@link #firstDrawBeat}:
 * the earliest entry, or the earliest visibleFrom).
 *
 * <p><b>Stretch.</b> Distances, spawns, speeds and line y here are reference units (what {@link #position}
 * returns; the renderer stretches it with {@link Field#dist}). {@link #update} takes the visible extent in
 * the world and works in the reference frame: the extent through {@link Field#refY}, and what a body
 * reaches (note thickness, glow and cull margins: world units, not stretched; lane widths and tails:
 * stretched) divided by {@code ky}.
 */
public final class EdgeEntry {
    /**
     * How far beyond the visible edge a note's leading edge is at its entry beat (ref units): the
     * largest draw-cull margin toward the line ({@link NoteRenderer#LANE_CULL}); every glow reaches
     * less far.
     */
    public static final float MARGIN = 60f;
    /** Extra reach of a turned note: glow beyond its sides, and behind a body turned toward its line. */
    private static final float TURN_PAD = 120f;
    /** Passes that widen the line's range over the slide before falling back to the whole chart. */
    private static final int PASSES = 4;
    /**
     * Ref units a body may poke past the visible edge and still count as off screen: float noise, since the default
     * fall 1080 from the lines at 1080 / 120 (2026-10-19) spawns upright notes exactly on the frame's edge.
     */
    private static final float EDGE_EPS = 1e-3f;
    /** Half the side of a MINE stud, which sits across the body's long edges. */
    private static final float STUD_HALF = 3f;
    /** Half the diagonal of a TIDE STREAM bar's ping diamond. */
    private static final float DIAMOND_HALF = 5f;
    // body kinds, for what lies beyond the leading edge toward the line
    private static final byte K_LANE = 0;
    private static final byte K_MINE = 1;
    private static final byte K_LANE_MINE = 2;
    private static final byte K_T4_GROUND = 3;
    private static final byte K_T4_SKY = 4;
    private static final byte K_TIDE_STREAM = 5;

    private Note[] notes = new Note[0];
    private int count;
    private JudgementLine lines = JudgementLine.defaults();
    /** Beat of the first movement of a note that may enter from the edge; NaN for the others. */
    private float[] start = new float[0];
    /** Distance at the start beat (the authored spawn). */
    private float[] spawn = new float[0];
    /** Average speed of the first movement toward the line (ref units per beat, &gt; 0). */
    private float[] speed = new float[0];
    /** |sin| of the angle at the start beat. */
    private float[] turnSin = new float[0];
    /** The authored angle at the start beat (degrees, without a sky note's 180°). */
    private float[] turnDeg = new float[0];
    /**
     * {@code max(0, −cos)} of the angle at the start beat: how much the body is turned toward its line (0 when
     * upright).
     */
    private float[] turnBack = new float[0];
    /** Body kind ({@code K_*}). */
    private byte[] kind = new byte[0];
    /** Width of the note's lane (a TIDE: its window) at the start beat in reference units (0 without a lane). */
    private float[] laneW = new float[0];
    /** cos of the angle at the start beat. */
    private float[] turnCos = new float[0];
    /** Tail length at the start beat in reference units (≥ 0). */
    private float[] tailAt = new float[0];
    /**
     * How far the body at the spawn reaches beyond its leading edge toward the line, at the current note size and
     * stretch (world units).
     */
    private float[] bodyReach = new float[0];
    /** The lane layout at a start beat (load time). */
    private final LaneLayout layout = new LaneLayout();
    /** Entry beat at the current extent; NaN when the note does not enter from the edge. */
    private float[] from = new float[0];
    /** First beat the note can draw: its visibleFrom, or its entry beat when that is earlier. */
    private float[] drawFrom = new float[0];
    private float bottom = Float.NaN;
    private float top = Float.NaN;
    private float width = Float.NaN;
    private float size = Float.NaN;
    private float kx = Float.NaN;
    private float ky = Float.NaN;
    private float firstEntry = Float.POSITIVE_INFINITY;
    private float firstDraw = Float.POSITIVE_INFINITY;
    private int entering;
    private int generation;

    /** Prepares a chart: the notes that may enter from the edge (load time, may allocate). */
    public void begin(Chart chart) {
        notes = chart.notes();
        count = notes.length;
        lines = chart.lineEvents();
        layout.setLanes(chart);
        if (start.length < count) {
            start = new float[count];
            spawn = new float[count];
            speed = new float[count];
            turnSin = new float[count];
            turnDeg = new float[count];
            turnBack = new float[count];
            kind = new byte[count];
            laneW = new float[count];
            turnCos = new float[count];
            tailAt = new float[count];
            bodyReach = new float[count];
            from = new float[count];
            drawFrom = new float[count];
        }
        for (int i = 0; i < count; i++) {
            Note n = notes[i];
            start[i] = Float.NaN;
            spawn[i] = 0f;
            speed[i] = 0f;
            turnSin[i] = 0f;
            turnDeg[i] = 0f;
            turnBack[i] = 0f;
            kind[i] = K_LANE;
            laneW[i] = 0f;
            turnCos[i] = 1f;
            tailAt[i] = 0f;
            bodyReach[i] = 0f;
            from[i] = Float.NaN;
            drawFrom[i] = n.visibleFrom();
            float s;
            float e;
            float a;
            float b;
            if (n.usesDefaultApproach()) {
                s = n.judgement() - Note.DEFAULT_APPROACH_BEATS;
                e = n.judgement();
                a = Note.DEFAULT_SPAWN_DISTANCE;
                b = 0f;
            } else if (n.movementEvents().isEmpty()) {
                continue; // no approach: a hidden judgement point, or a bar that appears on its line
            } else {
                Events m = n.movementEvents().get(0);
                s = m.startTime();
                e = m.endTime();
                a = m.initialValue();
                b = m.finalValue();
            }
            if (!(e > s)) continue;
            float v = (a - b) / (e - s);
            if (!(v > 0f) || Float.isInfinite(v)) continue;
            float sp = n.positionAt(s);
            if (!(Math.abs(sp) < Note.HIDDEN_POSITION - 1f)) continue;
            float y = lines.yAt(n.line(), s);
            if (n.isUpper() ? y - sp > 0f : y + sp < Field.REF_H) continue; // inside the reference frame
            start[i] = s;
            spawn[i] = sp;
            speed[i] = v;
            tailAt[i] = Math.max(0f, n.tailHeightAt(s));
            layout.update(s, LaneLayout.VIEW_LEFT, LaneLayout.VIEW_RIGHT);
            if (n.type() == NoteType.UNIVERSAL) {
                kind[i] = n.isTideStream() ? K_TIDE_STREAM : n.isUpper() ? K_T4_SKY : K_T4_GROUND;
                laneW[i] = Math.max(0f, layout.windowRight() - layout.windowLeft() - 2f * NoteRenderer.TIDE_INSET);
            } else {
                kind[i] = n.type() != NoteType.MINE ? K_LANE : n.isLaneMine() ? K_LANE_MINE : K_MINE;
                int lane = n.laneIndex();
                laneW[i] = lane >= 0 && lane < layout.count() ? layout.width(lane) : 0f;
            }
            float rot = n.angleAt(s);
            turnDeg[i] = rot;
            turnSin[i] = Math.abs(MathUtils.sinDeg(rot));
            turnCos[i] = MathUtils.cosDeg(rot);
            turnBack[i] = Math.max(0f, -turnCos[i]);
        }
        bottom = Float.NaN;
        top = Float.NaN;
        width = Float.NaN;
        size = Float.NaN;
        kx = Float.NaN;
        ky = Float.NaN;
        firstEntry = Float.POSITIVE_INFINITY;
        firstDraw = Float.POSITIVE_INFINITY;
        entering = 0;
        generation++;
    }

    /**
     * Works out the entry beats for the visible extent {@code [bottom, top]} (world y) of a world
     * {@code width} wide at note size {@code noteSize} (the setting, 0.8–1.3: a body's reach grows
     * with it) and the current {@link Field} stretch, when any of them differs from the last call (a
     * resize). No allocation.
     *
     * @return true when the entry beats changed (the window tables built on {@link #drawFrom}
     *         must be rebuilt)
     */
    public boolean update(float bottom, float top, float width, float noteSize) {
        float sx = Field.kx();
        float sy = Field.ky();
        if (bottom == this.bottom && top == this.top && width == this.width && noteSize == size && sx == kx
            && sy == ky) {
            return false;
        }
        if (noteSize != size || sx != kx || sy != ky) {
            kx = sx;
            ky = sy;
            for (int i = 0; i < count; i++) {
                bodyReach[i] = Float.isNaN(start[i]) ? 0f : reachAt(i, notes[i].isUpper(), noteSize);
            }
        }
        this.bottom = bottom;
        this.top = top;
        this.width = width;
        size = noteSize;
        firstEntry = Float.POSITIVE_INFINITY;
        firstDraw = Float.POSITIVE_INFINITY;
        entering = 0;
        for (int i = 0; i < count; i++) {
            Note n = notes[i];
            float f = Float.isNaN(start[i]) ? Float.NaN : entryBeat(i, n);
            from[i] = f;
            drawFrom[i] = Float.isNaN(f) ? n.visibleFrom() : Math.min(n.visibleFrom(), f);
            if (drawFrom[i] < firstDraw) firstDraw = drawFrom[i];
            if (!Float.isNaN(f)) {
                entering++;
                if (f < firstEntry) firstEntry = f;
            }
        }
        generation++;
        return true;
    }

    /**
     * @return how far note i's body at its spawn (pose of the start beat) reaches beyond its leading
     *         edge toward the line at note size {@code u}, as NoteRenderer paints it, glows left out:
     *         a lane note's turned head ({@code headWidth × NOTE_H u}) and ribbon, a mine's turned body
     *         ({@code MINE_BOX} of the lane × 32u) with the studs across its long edges, a LANE MINE's
     *         column, the base line of a ground TIDE, the big beads of a sky TIDE; a TIDE STREAM's bar
     *         and band lie behind its leading edge (world units: the lane width stretched by kx, the tail by ky)
     */
    private float reachAt(int i, boolean up, float u) {
        float r = bodyAt(i, up, u);
        Note n = notes[i];
        float rot = turnDeg[i] + (up ? 180f : 0f);
        if (kind[i] == K_LANE || kind[i] == K_MINE || kind[i] == K_LANE_MINE) {
            // the leading edge moves as the body turns about its pivot (Kelvin 2026-10-16)
            float refH = kind[i] == K_LANE ? NoteRenderer.NOTE_H : NoteRenderer.MINE_H;
            float y = NoteShapes.pivotShiftY(Float.MAX_VALUE, refH, refH * u, rot, up, n.pivotX(), n.pivotY());
            return r + (up ? y : -y);
        }
        if (!NoteShapes.turned(turnDeg[i])) return r;
        // a turned TIDE crosses the screen slanted, and its middle swings about its pivot
        float wx = -(up ? -1f : 1f) * n.pivotX() * kx;
        float wy = -(up ? -1f : 1f) * n.pivotY() * ky;
        float dy = MathUtils.sinDeg(turnDeg[i]) * wx + (MathUtils.cosDeg(turnDeg[i]) - 1f) * wy;
        float tail = Math.min(tailAt[i] * ky, NoteRenderer.MAX_TAIL_DRAW);
        return r + NoteShapes.tideTurnReach(0.5f * laneW[i] * kx, turnDeg[i]) + Math.max(0f, up ? dy : -dy)
            + 18f * u + turnBack[i] * tail;
    }

    /** {@link #reachAt} of the body turned about its leading edge (the turn before 2026-10-16), TIDEs upright. */
    private float bodyAt(int i, boolean up, float u) {
        float sin = turnSin[i];
        float cos = turnCos[i];
        float back = Math.max(0f, -cos);
        float lw = laneW[i] * kx;
        float tail = Math.min(tailAt[i] * ky, NoteRenderer.MAX_TAIL_DRAW);
        switch (kind[i]) {
            case K_LANE: {
                float w = lw * 5f / 6f * Math.min(u, 1.15f);
                float r = 0.5f * w * sin + back * NoteRenderer.NOTE_H * u;
                if (tail > 0f) r = Math.max(r, 0.5f * NoteShapes.ribbonWidth(lw, up) * sin + back * tail);
                return r;
            }
            case K_MINE:
            case K_LANE_MINE: {
                float mw = lw * GameEngine.MINE_BOX;
                float mh = 32f * u;
                float r = 0.5f * mw * sin + back * mh;
                // studs: 6 × 6 at a quarter, half and three quarters of each long edge
                float studX = (0.25f * mw + STUD_HALF) * sin;
                r = Math.max(r, studX + STUD_HALF * Math.abs(cos));
                r = Math.max(r, studX + (cos < 0f ? (mh + STUD_HALF) * back : -(mh - STUD_HALF) * cos));
                float hw = NoteShapes.laneMineHalfWidth(lw);
                if (kind[i] == K_LANE_MINE && tail > 0f && hw > NoteShapes.LANE_MINE_RIM) {
                    float studOut = Math.max(0f, 0.5f * (NoteShapes.STUD - NoteShapes.LANE_MINE_RIM));
                    r = Math.max(r, (hw + studOut) * sin + back * tail);
                }
                return r;
            }
            case K_T4_GROUND:
                return 0.5f * NoteShapes.WAVE_LINE * u;
            case K_T4_SKY: {
                float big = Math.max(NoteShapes.T4_BEAD_BIG, NoteShapes.T4_BEAD_SMALL) * u;
                return Math.max(0f, 0.5f * big / NoteRenderer.DISC_R - NoteShapes.T4_THREAD_BEHIND * u);
            }
            default: // K_TIDE_STREAM: bar, white edge and band behind; the diamonds sit across the bar's middle
                return Math.max(0f, DIAMOND_HALF - 9f * u);
        }
    }

    /**
     * @return the entry beat of note i at the current extent, or NaN when its body at the spawn stays off screen
     *         (worked out in reference units: the world extent and reaches are brought back by {@code ky})
     */
    private float entryBeat(int i, Note n) {
        boolean up = n.isUpper();
        Line line = n.line();
        float s = start[i];
        float sp = spawn[i];
        float y = lines.yAt(line, s);
        float body = bodyReach[i] / ky;
        // it already comes from off screen
        if (up ? y - sp + body <= refBottom() + EDGE_EPS : y + sp - body >= refTop() - EDGE_EPS) return Float.NaN;
        float tail = Math.min(tailAt[i] * ky, NoteRenderer.MAX_TAIL_DRAW);
        float turn = turnSin[i] * (width + TURN_PAD) * 0.5f + turnBack[i] * (tail + TURN_PAD);
        // a pivot far from the body, or a turned TIDE, may reach farther than that
        float margin = (MARGIN + Math.max(turn, Math.max(0f, bodyReach[i]) + turnSin[i] * TURN_PAD)) / ky;
        float v = speed[i];
        for (int k = 0; k < PASSES; k++) {
            float f = s - Math.max(0f, reach(up, y, margin) - sp) / v;
            // the line at its nearest to the edge during the slide: done when that is where we assumed
            float near = lineExtreme(up, f, s);
            if (up ? near <= y : near >= y) return f;
            y = near;
        }
        y = lineExtreme(up, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
        return s - Math.max(0f, reach(up, y, margin) - sp) / v;
    }

    /**
     * @return the distance (reference units) from a line at {@code y} to {@code margin} beyond the visible edge it
     * faces.
     */
    private float reach(boolean up, float y, float margin) {
        return up ? y - refBottom() + margin : refTop() - y + margin;
    }

    /** @return the visible bottom edge in reference units. */
    private float refBottom() {
        return Field.refY(bottom);
    }

    /** @return the visible top edge in reference units. */
    private float refTop() {
        return Field.refY(top);
    }

    /**
     * @return the line's y nearest to the edge its notes come from over the beats {@code [a, b]}:
     *         the highest sky line, the lowest ground line (its base and every event touching the
     *         range; an eased event stays between its end values)
     */
    private float lineExtreme(boolean up, float a, float b) {
        List<Events> ev = up ? lines.upperEvents() : lines.lowerEvents();
        float r = up ? lines.upperBase() : lines.lowerBase();
        for (int k = 0; k < ev.size(); k++) {
            Events e = ev.get(k);
            if (e.startTime() > b) break;
            if (e.endTime() < a) continue;
            if (up) r = Math.max(r, Math.max(e.initialValue(), e.finalValue()));
            else r = Math.min(r, Math.min(e.initialValue(), e.finalValue()));
        }
        return r;
    }

    /** @return true when the entries were prepared for {@code chart} ({@link #begin}). */
    public boolean covers(Chart chart) {
        return chart != null && chart.notes() == notes;
    }

    // ================================================================== per note

    /** @return true when note i enters from the edge at the current extent. */
    public boolean entering(int i) {
        return !Float.isNaN(from[i]);
    }

    /**
     * @return the note's distance from its line at {@code beat}: on its slide from the edge (from
     *         the entry beat to its first movement) {@code sp + v·(start − beat)}, parked at
     *         {@link Note#HIDDEN_POSITION} before the entry beat, else {@link NoteRenderer#position}
     */
    public float position(int i, Note n, float beat) {
        float f = from[i];
        if (Float.isNaN(f) || !(beat < start[i])) return NoteRenderer.position(n, beat);
        return beat >= f ? spawn[i] + speed[i] * (start[i] - beat) : Note.HIDDEN_POSITION;
    }

    /**
     * {@link #position(int, Note, float)} by the note's chart index (a note of another chart: its authored position).
     */
    public float position(Note n, float beat) {
        int i = n.index();
        if (i < 0 || i >= count || notes[i] != n) return NoteRenderer.position(n, beat);
        return position(i, n, beat);
    }

    /**
     * @return the beat whose angle, opacity and tail length note i shows at {@code beat}: its first
     *         movement's start beat while it slides in from the edge, else {@code beat}
     */
    public float look(int i, float beat) {
        return !Float.isNaN(from[i]) && beat < start[i] ? start[i] : beat;
    }

    /** {@link #look(int, float)} by the note's chart index. */
    public float look(Note n, float beat) {
        int i = n.index();
        if (i < 0 || i >= count || notes[i] != n) return beat;
        return look(i, beat);
    }

    /**
     * @return the first beat note i can be drawn at the current extent: {@link Note#visibleFrom},
     *         or its entry beat when that is earlier (the culling window opens here)
     */
    public float drawFrom(int i) {
        return drawFrom[i];
    }

    /** @return the entry beat of note i at the current extent (NaN when it does not enter from the edge). */
    public float entryBeat(int i) {
        return from[i];
    }

    /** @return the start beat of note i's first movement when it may enter from the edge, else NaN. */
    public float startBeat(int i) {
        return start[i];
    }

    /** @return the earliest entry beat at the current extent (+∞ when no note enters from the edge). */
    public float firstEntryBeat() {
        return firstEntry;
    }

    /**
     * @return the first beat any note can be drawn at the current extent: the smallest
     *         {@link #drawFrom} (+∞ for a chart without notes); the run starts before it
     */
    public float firstDrawBeat() {
        return firstDraw;
    }

    /** @return number of notes that enter from the edge at the current extent. */
    public int enteringCount() {
        return entering;
    }

    /** @return a counter bumped whenever the entry beats change ({@link #begin}, a new extent). */
    public int generation() {
        return generation;
    }
}
