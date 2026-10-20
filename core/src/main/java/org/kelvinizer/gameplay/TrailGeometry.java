package org.kelvinizer.gameplay;

import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.JudgementLine;
import org.kelvinizer.elements.Lane;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteTrail;
import org.kelvinizer.elements.NoteType;

/**
 * 残影副本的位置：路径模式在该时刻的位置，水平模式保持音符当前的距离；水雷和轨道水雷也会旋转。
 *
 * <p>Where an afterimage copy sits (ARCHITECTURE §17), pure Java: no GL, no allocation after
 * {@link #begin}. {@link #place} fills the public fields for one copy; the renderer then sizes and
 * paints it, and the harness checks the numbers directly.
 *
 * <ul>
 *   <li>{@code path}: the note exactly as it was at the moment {@code s}: lane span and window of
 *       {@code s} (a private scratch {@link LaneLayout}, so the engine's frame layout used by input
 *       is never touched), line y of {@code s}, distance, angle and hold length of {@code s}.</li>
 *   <li>{@code level}: lane span and angle of {@code s}, but the note's current distance, line y
 *       and hold length: copies stay level with the note and fall straight down with it. A TIDE
 *       has no level copy (see {@link TrailPlan}).</li>
 * </ul>
 * {@link #sameAs} tells whether a placed copy would sit exactly on the note it trails (such a
 * copy is not drawn: it would only change the look of the live note).
 *
 * <p>With an {@link EdgeEntry} set ({@link #setEntry}), a note that slides in from the screen edge
 * is placed on its slide too: its distance from {@link EdgeEntry#position} and its angle and hold
 * length from {@link EdgeEntry#look}, so its copies stream in from the edge behind it.
 *
 * <p>Placements are in the world, stretched like the live note ({@link Field}): the window of a moment
 * with {@code kx}, its line y, the distance and the hold length with {@code ky} (hidden tested on the
 * raw distance).
 */
public final class TrailGeometry {
    /** Offset (world units) under which a copy counts as sitting on its note ({@link #sameAs}). */
    public static final float COINCIDE_UNITS = 0.5f;
    /** Angle (degrees) under which a copy counts as sitting on its note ({@link #sameAs}). */
    public static final float COINCIDE_DEG = 0.5f;

    /** Leading-edge centre x (lane notes and mines: the lane centre; TIDE: the window centre). */
    public float x;
    /** Leading-edge y. */
    public float y;
    /** Lane width of the moment (TIDE: window width), before the note-size rule. */
    public float laneWidth;
    /**
     * Rotation in degrees, +180 for sky notes (mines turn, owner 2026-10-12 03:03; TIDE and TIDE
     * STREAM too since 2026-10-16, about their pivot).
     */
    public float rot;
    /** Hold ribbon / TIDE STREAM band / LANE MINE column length in world units (0 for others), not yet clamped for drawing. */
    public float tail;
    /** Distance from the line used for the copy (world units). */
    public float pos;
    /** Line y used for the copy. */
    public float lineY;
    /** Window left of the moment (TIDE bar). */
    public float windowLeft;
    /** Window right of the moment (TIDE bar). */
    public float windowRight;
    /** The layout the copy was placed with (lane spans of the moment; for TIDE diamonds). */
    public LaneLayout layout;

    private final LaneLayout past = new LaneLayout();
    private EdgeEntry entry;
    private JudgementLine lines = JudgementLine.defaults();
    private boolean staticLayout = true;
    private float pastBeat = Float.NaN;
    private float shift;

    /** Prepares a chart (load time). */
    public void begin(Chart chart) {
        lines = chart.lineEvents();
        past.setLanes(chart);
        pastBeat = Float.NaN;
        staticLayout = lines.laneScaleEvents().isEmpty() && lines.cameraFrameEvents().isEmpty();
        Lane[] lanes = chart.lanes();
        for (int i = 0; i < lanes.length && staticLayout; i++) {
            // inserted and deleted lanes (owner 2026-10-15, 2026-10-17) move the others too
            if (!lanes[i].widthEvents().isEmpty() || lanes[i].inserted() || lanes[i].deleted()) staticLayout = false;
        }
    }

    /**
     * Places notes that slide in from the screen edge on their slide (the renderer's
     * {@link EdgeEntry}; null = the authored path only).
     */
    public void setEntry(EdgeEntry entry) {
        this.entry = entry;
    }

    /** Moves every copy {@code worldUnits} along its path toward its line, as {@link NoteRenderer#hitShift()} does. */
    public void setShift(float worldUnits) {
        shift = worldUnits;
    }

    /** @return true when no lane or window ever moves (the frame layout serves every moment). */
    public boolean staticLayout() {
        return staticLayout;
    }

    /** @return number of lanes of the chart. */
    public int laneCount() {
        return past.count();
    }

    /** @return the chart's bound on the lanes' worth of TIDE pattern ({@link LaneLayout#patternLanes}). */
    public int patternLanes() {
        return past.patternLanes();
    }

    /**
     * @return the lane layout at moment {@code s}: {@code frame} (the engine's current layout,
     *         only read) when nothing ever moves sideways, else this object's scratch layout
     */
    public LaneLayout layoutAt(float s, LaneLayout frame) {
        if (staticLayout && frame != null) return frame;
        if (s != pastBeat) {
            past.update(s, Field.x(LaneLayout.VIEW_LEFT), Field.x(LaneLayout.VIEW_RIGHT), Field.kx());
            pastBeat = s;
        }
        return past;
    }

    /**
     * Places copy moment {@code s} of note {@code n} at the frame beat {@code beat}.
     *
     * @param frame     the engine's frame layout (read only; null to always use the scratch one)
     * @param liveLineY the line's y now (the engine's frame value)
     * @return false when there is nothing to draw: the note was hidden at {@code s} (or, in level
     *         mode, is hidden now), its lane was narrower than {@link LaneLayout#MIN_ACTIVE_WIDTH}
     *         or absent, or a level TIDE
     */
    public boolean place(Note n, float s, float beat, LaneLayout frame, float liveLineY) {
        return place(n, n.trail().mode(), s, beat, frame, liveLineY);
    }

    /** {@link #place(Note, float, float, LaneLayout, float)} for a trail segment of mode {@code mode}. */
    public boolean place(Note n, NoteTrail.Mode mode, float s, float beat, LaneLayout frame, float liveLineY) {
        boolean up = n.isUpper();
        boolean level = mode == NoteTrail.Mode.LEVEL;
        float posS = distance(n, s);
        if (hidden(posS)) return false;
        LaneLayout lay = layoutAt(s, frame);
        layout = lay;
        windowLeft = lay.windowLeft();
        windowRight = lay.windowRight();
        if (n.type() == NoteType.UNIVERSAL) {
            if (level) return false;
            x = (windowLeft + windowRight) * 0.5f;
            laneWidth = windowRight - windowLeft;
        } else {
            int lane = n.laneIndex();
            if (lane < 0 || lane >= lay.count()) return false;
            laneWidth = lay.width(lane);
            if (laneWidth < LaneLayout.MIN_ACTIVE_WIDTH) return false;
            x = lay.centre(lane);
        }
        boolean hold = n.isSustained();
        if (level) {
            float now = distance(n, beat);
            if (hidden(now)) return false;
            pos = Field.dist(now) - shift;
            lineY = liveLineY;
            tail = hold ? Field.dist(n.tailHeightAt(look(n, beat))) : 0f;
        } else {
            pos = Field.dist(posS) - shift;
            lineY = Field.y(lines.yAt(n.line(), s));
            tail = hold ? Field.dist(n.tailHeightAt(look(n, s))) : 0f;
        }
        y = up ? lineY - pos : lineY + pos;
        rot = n.angleAt(look(n, s)) + (up ? 180f : 0f);
        return true;
    }

    /** @return the note's distance at {@code beat}, on its slide from the screen edge when it has one. */
    private float distance(Note n, float beat) {
        return entry != null ? entry.position(n, beat) : NoteRenderer.position(n, beat);
    }

    /** @return the beat whose angle and hold length the note shows at {@code beat} ({@link EdgeEntry#look}). */
    private float look(Note n, float beat) {
        return entry != null ? entry.look(n, beat) : beat;
    }

    /**
     * @return true when the last placed copy would be drawn exactly over a reference placement
     *         ({@code rx, ry, rw, rrot, rtail}: normally the live note, placed with {@link #place}
     *         at the frame beat): same leading-edge centre, lane (TIDE: window) width, angle and
     *         hold (TIDE STREAM band, LANE MINE column) length, within {@link #COINCIDE_UNITS} /
     *         {@link #COINCIDE_DEG}. Every note turns (mines since 2026-10-12 03:03, TIDEs since
     *         2026-10-16), so the angle always counts. Such a
     *         copy shows nothing of the note's motion (a {@code level} copy with no sideways or
     *         rotation motion since its moment, a {@code path} copy of a note that stood still) but
     *         stacking it under the note would change the note's look, so the renderer leaves it
     *         out.
     */
    public boolean sameAs(NoteType type, float rx, float ry, float rw, float rrot, float rtail) {
        if (!(Math.abs(x - rx) < COINCIDE_UNITS && Math.abs(y - ry) < COINCIDE_UNITS
            && Math.abs(laneWidth - rw) < COINCIDE_UNITS)) {
            return false;
        }
        return Math.abs(tail - rtail) < COINCIDE_UNITS && angleGap(rot, rrot) < COINCIDE_DEG; // a TIDE drop has tail 0
    }

    /** @return the smaller angle between two directions in degrees (0..180). */
    public static float angleGap(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    /** @return true for a distance that parks the note off screen ({@link Note#HIDDEN_POSITION}). */
    public static boolean hidden(float pos) {
        return !(Math.abs(pos) < Note.HIDDEN_POSITION - 1f);
    }
}
