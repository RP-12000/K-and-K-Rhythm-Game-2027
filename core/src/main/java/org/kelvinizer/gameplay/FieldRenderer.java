package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.Sea;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Theme;

/**
 * 判定线与轨道区：两条判定线、整高的轨道分隔线、命中涟漪与浪花命中时整条线的涌起。Judgement lines and
 * the lane field (DESIGN §5.7, §5.10 column glow, §5.15 lanes v2, §19.2 line swell, §21.2
 * separators).
 *
 * <p>Three passes, matching the blend blocks of §5.1:
 * <ul>
 *   <li>{@link #drawUnder} (normal block A): zone hint and the full-height lane separators;</li>
 *   <li>{@link #drawLines} (normal block B): line cores (with the High-quality hit ripple and the
 *       TIDE line swell) and lane ticks;</li>
 *   <li>{@link #drawAdditive} (additive block): line glows with combo tiers and beat pulse, the
 *       swell glow, approach glows, line flashes (LOST / mine), hit beads and lane column glows.</li>
 * </ul>
 * Every lane visual uses the lane's current span and is multiplied by its opacity and by how much of the lanes the
 * chart shows ({@link Chart#lanesShown}: they fade out for laneless time, Kelvin 2026-11-07). Times are in
 * "run seconds" (frozen while paused). No allocation after {@link #begin}.
 */
final class FieldRenderer {
    /**
     * @return half height of the visual band around a line in the world ({@link Field#bandHalf}: 120·ky);
     *         the input zone ({@link Field#zoneHalf}) is wider
     */
    static float zone() {
        return Field.bandHalf();
    }
    /** Column glow height into the field. */
    static final float COLUMN_H = 220f;
    /**
     * Zone hint α at the line: .07 since the zone is ±0.4 of the height (Kelvin 2026-10-18 19:52), the strength the
     * old settings preview drew; .035 over the ±0.1 band before, which spread over ±0.4 left no visible tint.
     */
    static final float ZONE_HINT_A = 0.07f;
    private static final int SEGMENTS = 48;
    private static final int MAX_RIPPLES = 4;
    private static final int MAX_BEADS = 8;
    private static final float RIPPLE_SEC = 0.22f;
    private static final float BEAD_SEC = 0.20f;
    /** Lane separator α at each line and at mid-field (quadratic between, DESIGN §21.2). */
    static final float SEPARATOR_LINE_A = 0.34f;
    static final float SEPARATOR_MID_A = 0.16f;
    /** Soft glow around each separator: total width and peak α. */
    static final float SEPARATOR_GLOW_W = 10f;
    static final float SEPARATOR_GLOW_A = 0.05f;
    /** Linear pieces of the separators' quadratic α curve. */
    private static final int SEPARATOR_SEGMENTS = 6;
    /** Lane tick α (DESIGN §5.7, §21.2). */
    static final float TICK_A = 0.75f;
    /** TIDE line swell (DESIGN §19.2): settles over 320 ms after a 60 ms rise; 4 px lift. */
    static final float SWELL_SEC = 0.32f;
    static final float SWELL_RISE_SEC = 0.06f;
    static final float SWELL_LIFT = 4f;
    /** Extra core thickness at the swell's peak. */
    static final float SWELL_THICKEN = 2f;
    static final float SWELL_GLOW_A = 0.35f;
    private static final float LOST_FLASH_SEC = 0.20f;
    private static final float MINE_FLASH_SEC = 0.22f;
    private static final float ATTACK_SEC = 0.04f;
    private static final float RELEASE_SEC = 0.16f;

    private final Draw draw;
    private final Batch batch;
    private final TextureRegion core;
    private final TextureRegion glowUp;
    private final TextureRegion glowDown;
    private final float[] quad = new float[20];
    private final float[] offs = new float[SEGMENTS + 1];
    private final Color c0 = new Color();
    /** A lane's piece of a line at its opacity ({@link LaneLayout#lineOpacity}). */
    private final Color lineC = new Color();
    private final Color c1 = new Color();
    /** A mine lane's piece of a line ({@link #across}): as red as the lane is ({@link LaneLayout#mine}). */
    private final Color mineC = new Color();
    /** The view lanes' depth is drawn through (Kelvin 2026-11-07 22:54), or null while the field is flat. */
    private View3D space;
    /** The column {@link #columnPose} last read: how far its lines are lifted (world). */
    private float liftG, liftS;
    /** True while the batch draws through a lane's frame ({@link #columnPose}, {@link #columnEnd}). */
    private boolean framed;

    private final float[][] ripX = new float[Line.COUNT][MAX_RIPPLES];
    private final float[][] ripT = new float[Line.COUNT][MAX_RIPPLES];
    private final float[][] ripA = new float[Line.COUNT][MAX_RIPPLES];
    private final int[] ripNext = new int[Line.COUNT];
    private final float[][] beadX = new float[Line.COUNT][MAX_BEADS];
    private final float[][] beadT = new float[Line.COUNT][MAX_BEADS];
    private final int[] beadNext = new int[Line.COUNT];
    private final float[] winFlashT = new float[Line.COUNT];
    private final boolean[] winFlashMine = new boolean[Line.COUNT];
    private final float[] tideApproach = new float[Line.COUNT];
    private final float[] swellT = new float[Line.COUNT];
    private final Color[] swellC = new Color[Line.COUNT];
    private final float[] swellK = new float[Line.COUNT];

    private int lanes;
    private float[] flashT = new float[0];
    private boolean[] flashMine = new boolean[0];
    private float[] level = new float[0];
    private float[] relFrom = new float[0];
    private float[] relT = new float[0];
    private boolean[] held = new boolean[0];
    private boolean[] holding = new boolean[0];
    private float[] approachA = new float[0];
    private boolean[] approachMine = new boolean[0];

    /** Creates the renderer over the shared atlas. */
    FieldRenderer(Draw draw) {
        this.draw = draw;
        this.batch = draw.batch();
        core = new TextureRegion(draw.white(), 1, 1, 2, 2);
        TextureRegion g = draw.glowV();
        int h = g.getRegionHeight() / 2;
        glowUp = new TextureRegion(g, 0, 0, g.getRegionWidth(), h);
        glowDown = new TextureRegion(g, 0, h, g.getRegionWidth(), h);
    }

    /** Sizes the per-lane state for a chart and clears every effect (load time, may allocate). */
    void begin(Chart chart) {
        lanes = Math.max(1, chart.laneCount());
        int n = Line.COUNT * lanes;
        if (flashT.length < n) {
            flashT = new float[n];
            flashMine = new boolean[n];
            level = new float[n];
            relFrom = new float[n];
            relT = new float[n];
            held = new boolean[n];
            holding = new boolean[n];
            approachA = new float[n];
            approachMine = new boolean[n];
        }
        clear();
    }

    /** Clears every running effect. */
    void clear() {
        java.util.Arrays.fill(flashT, -100f);
        java.util.Arrays.fill(level, 0f);
        java.util.Arrays.fill(relT, -1f);
        java.util.Arrays.fill(held, false);
        java.util.Arrays.fill(holding, false);
        for (int l = 0; l < Line.COUNT; l++) {
            java.util.Arrays.fill(ripT[l], -100f);
            java.util.Arrays.fill(beadT[l], -100f);
            winFlashT[l] = -100f;
            swellT[l] = -100f;
        }
        clearApproach();
    }

    private int idx(Line line, int lane) {
        return line.ordinal() * lanes + lane;
    }

    // ------------------------------------------------------------------ effect triggers

    /** Starts a line ripple at x (High quality; at most 4 per line, the oldest is replaced). */
    void ripple(Line line, float x, float amplitude, float now) {
        int l = line.ordinal();
        int k = ripNext[l];
        ripNext[l] = (k + 1) % MAX_RIPPLES;
        ripX[l][k] = x;
        ripT[l][k] = now;
        ripA[l][k] = amplitude;
    }

    /** Starts a hit bead at x (Low / Off quality). */
    void bead(Line line, float x, float now) {
        int l = line.ordinal();
        int k = beadNext[l];
        beadNext[l] = (k + 1) % MAX_BEADS;
        beadX[l][k] = x;
        beadT[l][k] = now;
    }

    /**
     * A TIDE hit swells the whole line instead of a point ripple (DESIGN §19.2): it lifts 4 px
     * toward the field and glows in {@code colour}, then settles over 320 ms. A new swell on the
     * same line restarts it.
     *
     * @param alphaK glow strength (0.5 with {@code reduceFlashing})
     */
    void swell(Line line, Color colour, float alphaK, float now) {
        if (line == null) return;
        int l = line.ordinal();
        swellT[l] = now;
        swellC[l] = colour;
        swellK[l] = alphaK;
    }

    /** 0..1 envelope of the swell of line {@code l} at {@code now}: 60 ms rise, then it settles. */
    float swellEnvelope(int l, float now) {
        float t = now - swellT[l];
        if (t < 0f || t >= SWELL_SEC) return 0f;
        if (t < SWELL_RISE_SEC) return Anim.POW2_OUT.apply(t / SWELL_RISE_SEC);
        float k = 1f - (t - SWELL_RISE_SEC) / (SWELL_SEC - SWELL_RISE_SEC);
        return k * k;
    }

    /**
     * @return half the height of the band around {@code line}'s y that its core covers at {@code now} (world units):
     *         the 2-unit half stroke, a TIDE swell's lift and thickening, the reach of its running ripples and one
     *         unit of edge; a veil leaves this band as it is, so the line stays crisp above the water
     *         (CHART_FORMAT_v4 §6.10)
     */
    float strokeHalf(Line line, float now) {
        int l = line.ordinal();
        float sw = swellEnvelope(l, now);
        float reach = 0f;
        for (int k = 0; k < MAX_RIPPLES; k++) {
            float t = now - ripT[l][k];
            if (t >= 0f && t < RIPPLE_SEC) reach += Math.abs(ripA[l][k]) * (1f - t / RIPPLE_SEC);
        }
        return 2f + (SWELL_LIFT + 0.5f * SWELL_THICKEN) * sw + reach + 1f;
    }

    /** Flashes the line segment under a lane: LOST (#FF5C7A @ .40, 200 ms) or mine (@ .45, 220 ms). */
    void flash(Line line, int lane, boolean mine, float now) {
        if (line == null) return;
        if (lane < 0 || lane >= lanes) {
            flashWindow(line, mine, now);
            return;
        }
        int i = idx(line, lane);
        flashT[i] = now;
        flashMine[i] = mine;
    }

    /** Flashes the whole window of a line (a missed TIDE). */
    void flashWindow(Line line, boolean mine, float now) {
        winFlashT[line.ordinal()] = now;
        winFlashMine[line.ordinal()] = mine;
    }

    /** A short column pulse with no pointer (autoplay hits): full level, then the release fade. */
    void pulseColumn(Line line, int lane) {
        if (line == null || lane < 0 || lane >= lanes) return;
        int i = idx(line, lane);
        if (held[i]) return;
        level[i] = 1f;
        relFrom[i] = 1f;
        relT[i] = 0f;
    }

    /** Clears the per-frame approach state (call before {@link NoteRenderer#prepare}). */
    void clearApproach() {
        java.util.Arrays.fill(approachA, 0f);
        java.util.Arrays.fill(approachMine, false);
        tideApproach[0] = 0f;
        tideApproach[1] = 0f;
    }

    /** Raises the approach glow of a lane segment this frame (mine = warning colour). */
    void approach(Line line, int lane, float alpha, boolean mine) {
        if (lane < 0 || lane >= lanes) return;
        int i = idx(line, lane);
        if (mine) {
            if (alpha > approachA[i] || !approachMine[i]) {
                approachA[i] = Math.max(alpha, approachMine[i] ? approachA[i] : 0f);
                approachMine[i] = true;
            }
        } else if (!approachMine[i] && alpha > approachA[i]) {
            approachA[i] = alpha;
        }
    }

    /** Raises the TIDE approach (whole zone hint toward {@code tide @ .06}) of a line, 0..1. */
    void tideApproach(Line line, float k) {
        int l = line.ordinal();
        if (k > tideApproach[l]) tideApproach[l] = k;
    }

    // ------------------------------------------------------------------ update

    /**
     * Updates the column glows from the engine's pointers, keys and running holds
     * (DESIGN §5.10): 40 ms attack while down, 160 ms {@code pow2Out} release.
     */
    void update(float dt, GameEngine e) {
        Chart chart = e.chart();
        if (chart == null) return;
        int n = Line.COUNT * lanes;
        java.util.Arrays.fill(held, 0, n, false);
        java.util.Arrays.fill(holding, 0, n, false);
        for (int p = 0; p < GameEngine.MAX_POINTERS; p++) {
            if (!e.pointerDown(p)) continue;
            int lane = e.pointerLane(p);
            Line line = e.pointerLine(p);
            if (lane >= 0 && lane < lanes && line != null) held[idx(line, lane)] = true;
        }
        for (int s = 0; s < GameKeys.SLOTS; s++) {
            if (!e.keyDown(s)) continue;
            int lane = e.keyLane(s);
            Line line = GameKeys.line(s);
            if (lane >= 0 && lane < lanes && line != null) held[idx(line, lane)] = true;
        }
        for (int k = 0; k < e.activeHoldCount(); k++) {
            int i = e.activeHold(k);
            if (!e.isHoldHeld(i)) continue;
            Note note = chart.note(i);
            int lane = note.laneIndex();
            if (lane < 0 || lane >= lanes) continue;
            int j = idx(note.line(), lane);
            held[j] = true;
            holding[j] = true;
        }
        for (int i = 0; i < n; i++) {
            if (held[i]) {
                relT[i] = -1f;
                level[i] = Math.min(1f, level[i] + dt / ATTACK_SEC);
            } else if (level[i] > 0f) {
                if (relT[i] < 0f) {
                    relT[i] = 0f;
                    relFrom[i] = level[i];
                }
                relT[i] += dt;
                if (relT[i] >= RELEASE_SEC) {
                    level[i] = 0f;
                    relT[i] = -1f;
                } else {
                    level[i] = relFrom[i] * (1f - Anim.POW2_OUT.apply(relT[i] / RELEASE_SEC));
                }
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    private final float[] axes = new float[9];

    private static Color lineColor(Line line) {
        return line == Line.UPPER ? Theme.SKY_SIG : Theme.GROUND_SIG;
    }

    /** Half the thickness of a sea seen edge-on in the flat picture, world units (not stretched, as a line). */
    private static final float SEA_HALF = 8f;

    /**
     * Draws the seas in the flat picture (海面 seen edge-on, Kelvin 2026-11-07; the design thread: a thick deep-blue
     * line, heaving): along each sea's front edge, at its height and tilt, its edge line on it. In 3D
     * {@link View3D#drawSeas} draws them instead.
     */
    void drawSeas(GameEngine e, float seconds) {
        Chart chart = e.chart();
        if (chart == null || chart.seas().isEmpty()) return;
        LaneLayout lay = e.layout();
        float beat = lay.beat();
        for (Sea s : chart.seas()) {
            float a = s.shownAt(beat);
            float l = lay.freeX(s.xMin(beat));
            float r = lay.freeX(s.xMax(beat));
            if (a <= 0.002f || !(r > l)) continue;
            float y = Field.y(s.yAt(beat));
            // turned (Kelvin 2026-11-09 00:13): its across axis as the flat picture shows it, about its middle
            s.turn(beat, axes);
            float cos = Math.max(0.05f, Math.abs(axes[0]));
            float slope = axes[1] / (axes[0] < 0f ? -cos : cos);
            float mid = 0.5f * (l + r);
            l = mid + (l - mid) * cos;
            r = mid + (r - mid) * cos;
            float df = -0.5f * (s.zMax(beat) - s.zMin(beat));
            int n = MathUtils.clamp((int) Math.ceil((r - l) / (View3D.HEAVE_STEP * Field.kx())), 1, 96);
            float xa = l;
            float ya = y + slope * (l - mid) + View3D.seaHeave(0f, df, seconds) * Field.ky();
            for (int k = 1; k <= n; k++) {
                float xb = l + (r - l) * k / n;
                float yb = y + slope * (xb - mid) + View3D.seaHeave((xb - l) / Field.kx(), df, seconds) * Field.ky();
                draw.quadV(xa, ya - SEA_HALF, ya + SEA_HALF, xb, yb - SEA_HALF, yb + SEA_HALF, Theme.SEA, Theme.SEA,
                    0.5f * a);
                draw.quadV(xa, ya - 1.5f, ya + 1.5f, xb, yb - 1.5f, yb + 1.5f, Theme.SEA_EDGE, Theme.SEA_EDGE,
                    0.9f * a);
                xa = xb;
                ya = yb;
            }
        }
    }

    /**
     * Normal block A: zone hint (two gradient quads per line, α {@link #ZONE_HINT_A} at the line → 0 at the edge
     * of the touch zone, {@link Field#zoneHalf} ±0.4 of the height since Kelvin 2026-10-18 19:52 and the
     * ±0.1 band before, times {@code zoneK}; a TIDE approach adds {@code tide @ .06} over the band)
     * and the lane separators
     * (DESIGN §5.7, §21.2): at every lane boundary, from line to line, 2 px {@code ink1} at α .34
     * at each line easing to .16 mid-field (quadratic) inside a 10 px soft glow at .05, times the
     * brighter of the two lanes' opacity.
     *
     * @param zoneK  zone hint strength (0 = hidden; 1 = ZONE HINT 50 %, 2 at 100 %; larger in the tutorial pulse)
     * @param introK 0..1 field intro (zone hint and separator fade-in)
     */
    void drawUnder(GameEngine e, float zoneK, float introK) {
        LaneLayout lay = e.layout();
        float wl = lay.windowLeft();
        float ww = lay.windowRight() - wl;
        if (ww <= 0f) return;
        for (int li = 0; li < Line.COUNT; li++) {
            Line line = Line.of(li);
            float y = e.lineY(line);
            Color c = lineColor(line);
            float a = ZONE_HINT_A * zoneK * introK;
            if (a > 0.001f) zoneAcross(e, wl, wl + ww, y, line, c, a);
            float t = tideApproach[li];
            if (t > 0f) zoneQuads(wl, ww, y, zone(), Theme.TIDE, 0.06f * t);
        }
        float y0 = e.lineY(Line.LOWER);
        float y1 = e.lineY(Line.UPPER);
        float shown = lanesShown(e);
        boolean gapped = lay.separate();
        for (int i = 0; i < lay.count(); i++) {
            if (!lay.active(i)) continue;
            if (gapped) {
                // a gap between the lanes (Kelvin 2026-11-07 06:36): every column has its own two edges
                float op = lay.opacity(i) * shown * introK;
                float cl = lay.columnLeft(i);
                if (op > 0.01f && lay.columnWidth(i) > 0f && !hidden(i)) {
                    columnPose(lay, i);
                    separator(cl, cy(y0, Line.LOWER), cy(y1, Line.UPPER), op);
                    separator(cl + lay.columnWidth(i), cy(y0, Line.LOWER), cy(y1, Line.UPPER), op);
                }
                continue;
            }
            float op = boundaryOpacity(lay, i) * shown;
            if (op > 0.01f) separator(lay.left(i), y0, y1, op * introK);
            if (i == lay.count() - 1 || !lay.active(i + 1)) {
                float o2 = lay.opacity(i) * shown;
                if (o2 > 0.01f) separator(lay.right(i), y0, y1, o2 * introK);
            }
        }
        columnEnd();
    }

    /** @return how much of the lanes' look the chart shows at the frame's beat: 0 in laneless time */
    private static float lanesShown(GameEngine e) {
        Chart chart = e.chart();
        return chart != null ? chart.lanesShown(e.layout().beat()) : 1f;
    }

    /** Sets the view a moved or turned column is drawn through ({@link #columnPose}), or null while flat. */
    void setSpace(View3D v) {
        space = v;
    }

    /**
     * Reads lane i's pose this frame (Kelvin 2026-11-07 22:54, 11-08 23:10: {@link LaneLayout#lift},
     * {@link LaneLayout#moveZ}) for {@link #cy}: its line segments, edges and glows go with it; a lane turned or moved
     * in depth ({@link View3D#laneFrame}) through its frame until the next lane or {@link #columnEnd}.
     */
    private void columnPose(LaneLayout lay, int i) {
        liftG = lay.lift(i, Line.LOWER) * Field.ky();
        liftS = lay.lift(i, Line.UPPER) * Field.ky();
        int f = space != null ? space.laneFrame(i) : -1;
        if (f >= 0) {
            batch.setProjectionMatrix(space.frameCombined(f, 0f));
            framed = true;
            return;
        }
        columnEnd();
    }

    /**
     * @return true when lane i's column and field picture are wholly outside the 3D view this frame
     *         ({@link View3D#laneSeen}): its pieces are left out (Kelvin 2026-11-10 01:34, weaker phones)
     */
    private boolean hidden(int i) {
        return space != null && !space.laneSeen(i);
    }

    /** Ends {@link #columnPose}'s frame: the batch draws through the view again. */
    private void columnEnd() {
        if (!framed) return;
        framed = false;
        batch.setProjectionMatrix(space.combined());
    }

    /** @return world y of the posed column's point at y on {@code line}: at that line */
    private float cy(float y, Line line) {
        return y + (line == Line.UPPER ? liftS : liftG);
    }

    /** Edges of the pieces {@link #zoneAcross} draws (window ends, seas' ends, columns' edges); grown as needed. */
    private float[] cuts = new float[16];
    /** Left and right edges of the columns with a zone of their own ({@link #zoneAcross}), each sorted. */
    private float[] ownL = new float[16], ownR = new float[16];

    /**
     * The zone hint of the line at y across the window; in 3D not over a sea outside the lanes (Kelvin 2026-11-08
     * 17:23「让海面的判定区间不显示」: keys on a sea land on its surface, and the zone stood across it as a plane): a
     * column, or the gap between two, keeps it. It fades in and out with the sea and the columns.
     */
    private void zoneAcross(GameEngine e, float wl, float wr, float y, Line line, Color c, float a) {
        Chart chart = e.chart();
        LaneLayout lay = e.layout();
        boolean posed = false; // a lane with lines or a turn of its own (Kelvin 2026-11-08 23:10): its zone is its own
        for (int i = 0; lay.moving() && i < lay.count(); i++) posed |= posed(lay, i, line);
        boolean seas = space != null && chart != null && !chart.seas().isEmpty();
        if (!seas && !posed) {
            zoneQuads(wl, wr - wl, y, Field.zoneHalf(), c, a);
            return;
        }
        float beat = lay.beat();
        int n = 0;
        cuts = put(cuts, n++, wl);
        cuts = put(cuts, n++, wr);
        for (int j = 0; seas && j < chart.seas().size(); j++) {
            Sea s = chart.seas().get(j);
            cuts = put(cuts, n++, lay.freeX(s.xMin(beat)));
            cuts = put(cuts, n++, lay.freeX(s.xMax(beat)));
        }
        for (int i = 0; i < lay.count(); i++) {
            cuts = put(cuts, n++, lay.columnLeft(i));
            cuts = put(cuts, n++, lay.columnLeft(i) + lay.columnWidth(i));
        }
        java.util.Arrays.sort(cuts, 0, n);
        // the posed columns' edges, sorted: a piece's middle lies in one when more have begun than ended left of it
        // (one sweep instead of every column for every piece: 1079 lanes made that a million steps per line)
        int np = 0;
        for (int i = 0; posed && i < lay.count(); i++) {
            float cw = lay.columnWidth(i);
            if (!posed(lay, i, line) || !(cw > 0f)) continue;
            ownL = put(ownL, np, lay.columnLeft(i));
            ownR = put(ownR, np++, lay.columnLeft(i) + cw);
        }
        java.util.Arrays.sort(ownL, 0, np);
        java.util.Arrays.sort(ownR, 0, np);
        int begun = 0, ended = 0;
        float shown = lanesShown(e);
        for (int k = 0; k + 1 < n; k++) {
            float p = Math.max(wl, cuts[k]), q = Math.min(wr, cuts[k + 1]);
            if (!(q > p)) continue;
            float m = 0.5f * (p + q), sea = 0f, col = 0f, left = 0f, right = 0f;
            for (int j = 0; seas && j < chart.seas().size(); j++) {     // the seas over this piece
                Sea s = chart.seas().get(j);
                float l = lay.freeX(s.xMin(beat)), r = lay.freeX(s.xMax(beat));
                if (m > l && m < r) sea = Math.max(sea, s.shownAt(beat));
            }
            while (begun < np && ownL[begun] < m) begun++;
            while (ended < np && ownR[ended] < m) ended++;
            if (begun > ended) continue;                                 // drawn with its lane below
            for (int i = 0; i < lay.count() && sea > 0f; i++) {          // and a lane's column there keeps it
                if (!lay.active(i)) continue;
                float cl = lay.columnLeft(i), cr = cl + lay.columnWidth(i), o = lay.opacity(i) * shown;
                if (m > cl && m < cr) col = Math.max(col, o);
                else if (cr <= m) left = Math.max(left, o);
                else right = Math.max(right, o);
            }
            col = Math.max(col, Math.min(left, right));                 // a gap between two columns: theirs
            float f = 1f - sea * (1f - Math.min(1f, col));
            if (a * f > 0.001f) zoneQuads(p, q - p, y, Field.zoneHalf(), c, a * f);
        }
        float shownK = lanesShown(e);
        for (int i = 0; posed && i < lay.count(); i++) {
            float cw = lay.columnWidth(i);
            float op = lay.opacity(i) * shownK;
            if (!posed(lay, i, line) || !lay.active(i) || !(cw > 0f) || op * a <= 0.001f || hidden(i)) continue;
            columnPose(lay, i);
            float cl = lay.columnLeft(i);
            zoneQuads(cl, cw, cy(y, line), Field.zoneHalf(), c, a * op);
        }
        columnEnd();
    }

    /** @return true when lane i's {@code line} is apart from the chart's or it has a frame ({@link View3D#ownFrame}) */
    private boolean posed(LaneLayout lay, int i, Line line) {
        return lay.lift(i, line) != 0f || space != null && space.laneFrame(i) >= 0;
    }

    private static float[] put(float[] f, int i, float v) {
        if (i >= f.length) f = java.util.Arrays.copyOf(f, f.length * 2);
        f[i] = v;
        return f;
    }

    /** Colour {@code c} at α {@code a} on the line at y, fading to 0 at {@code z} above and below it. */
    private void zoneQuads(float x, float w, float y, float z, Color c, float a) {
        Theme.withAlpha(c, a, c0);
        Theme.withAlpha(c, 0f, c1);
        draw.rectGradientV(x, y, w, z, c0, c1, 1f);
        draw.rectGradientV(x, y - z, w, z, c1, c0, 1f);
    }

    /** One full-height separator at x from the line at y0 to the line at y1, times {@code op}. */
    private void separator(float x, float y0, float y1, float op) {
        float h = (y1 - y0) / SEPARATOR_SEGMENTS;
        for (int s = 0; s < SEPARATOR_SEGMENTS; s++) {
            Theme.withAlpha(Theme.INK1, separatorAlpha(s / (float) SEPARATOR_SEGMENTS) * op, c0);
            Theme.withAlpha(Theme.INK1, separatorAlpha((s + 1) / (float) SEPARATOR_SEGMENTS) * op, c1);
            draw.rectGradientV(x - 1f, y0 + h * s, 2f, h, c0, c1, 1f);
        }
        float gw = 0.5f * SEPARATOR_GLOW_W;
        Theme.withAlpha(Theme.INK1, 0f, c0);
        Theme.withAlpha(Theme.INK1, SEPARATOR_GLOW_A * op, c1);
        draw.rectGradientH(x - gw, y0, gw, y1 - y0, c0, c1, 1f);
        draw.rectGradientH(x, y0, gw, y1 - y0, c1, c0, 1f);
    }

    /** Separator α at {@code s} (0 at one line, 1 at the other): .34 at the lines, .16 mid-field, quadratic. */
    static float separatorAlpha(float s) {
        float t = 2f * s - 1f;
        return SEPARATOR_MID_A + (SEPARATOR_LINE_A - SEPARATOR_MID_A) * t * t;
    }

    /** Opacity of the left boundary of lane i (the brighter of the two lanes sharing it). */
    private static float boundaryOpacity(LaneLayout lay, int i) {
        float op = lay.opacity(i);
        if (i > 0 && lay.active(i - 1)) op = Math.max(op, lay.opacity(i - 1));
        return op;
    }

    /**
     * Normal block B: line cores (4 px, α .95, across the window; drawn as 48 rippling segments
     * while a High-quality ripple runs; lifted up to 4 px toward the field and up to 2 px thicker
     * during a TIDE swell) and lane ticks (2×20 {@code ink1 @ .75}), which pop in from the centre
     * outward during the first 0.35 s ({@code introT}).
     */
    void drawLines(GameEngine e, float now, float introT) {
        LaneLayout lay = e.layout();
        float wl = lay.windowLeft();
        float wr = lay.windowRight();
        if (wr <= wl) return;
        int bounds = lay.count() + 1;
        float mid = (bounds - 1) * 0.5f;
        float shown = lanesShown(e);
        for (int li = 0; li < Line.COUNT; li++) {
            Line line = Line.of(li);
            float y = e.lineY(line);
            Color c = lineColor(line);
            float sw = swellEnvelope(li, now);
            float yc = y + (line == Line.UPPER ? -1f : 1f) * SWELL_LIFT * sw;
            float half = 2f + 0.5f * SWELL_THICKEN * sw;
            boolean gapped = lay.separate() && shown > 0.5f;
            if (rippling(li, now)) {
                if (!gapped) rippleCore(li, wl, wr, yc, half, c, now);
                for (int i = 0; gapped && i < lay.count(); i++) {
                    float cl = lay.columnLeft(i);
                    if (lay.active(i) && lay.columnWidth(i) > 0f && lay.lineOpacity(i) > 0.002f && !hidden(i)) {
                        columnPose(lay, i);
                        lineC.set(c).a *= lay.lineOpacity(i);
                        rippleCore(li, cl, cl + lay.columnWidth(i), cy(yc, line), half, lineC, now);
                    }
                }
                columnEnd();
            } else {
                across(lay, true, yc, line, 2f * half, c, 0.95f, shown);
            }
            // ticks
            for (int i = 0; i < lay.count(); i++) {
                if (!lay.active(i)) continue;
                if (lay.separate()) { // at the column edges
                    float k = tickK(introT, Math.abs(i - mid));
                    float op = lay.opacity(i) * shown;
                    float cl = lay.columnLeft(i);
                    if (k > 0f && op > 0.01f && lay.columnWidth(i) > 0f && !hidden(i)) {
                        columnPose(lay, i);
                        float ty = cy(y, line) - 10f * k;
                        draw.rect(cl - 1f, ty, 2f, 20f * k, Theme.INK1, TICK_A * op);
                        draw.rect(cl + lay.columnWidth(i) - 1f, ty, 2f, 20f * k, Theme.INK1, TICK_A * op);
                    }
                    continue;
                }
                float k = tickK(introT, Math.abs(i - mid));
                float op = boundaryOpacity(lay, i) * shown;
                if (k > 0f && op > 0.01f) {
                    draw.rect(lay.left(i) - 1f, y - 10f * k, 2f, 20f * k, Theme.INK1, TICK_A * op);
                }
                if (i == lay.count() - 1 || !lay.active(i + 1)) {
                    float k2 = tickK(introT, Math.abs(i + 1 - mid));
                    float o2 = lay.opacity(i) * shown;
                    if (k2 > 0f && o2 > 0.01f) {
                        draw.rect(lay.right(i) - 1f, y - 10f * k2, 2f, 20f * k2, Theme.INK1, TICK_A * o2);
                    }
                }
            }
            columnEnd();
        }
    }

    /**
     * Draws a {@code glow_v} sprite (or a rect when {@code rect}) {@code h} tall centred on the line at y across the
     * window, or on gapped lanes ({@link LaneLayout#gap()}) once over each column, as much as the lanes are
     * {@code shown}; the rest across the window, so laneless time joins the line up again.
     */
    private void across(LaneLayout lay, boolean rect, float y, Line line, float h, Color c, float a, float shown) {
        float g = lay.separate() ? MathUtils.clamp(shown, 0f, 1f) : 0f;
        if (g < 1f) piece(rect, lay.windowLeft(), lay.windowRight(), y, h, c, a * (1f - g));
        for (int i = 0; g > 0f && i < lay.count(); i++) {
            if (!lay.active(i) || lay.columnWidth(i) <= 0f || hidden(i)) continue;
            float l = lay.columnLeft(i);
            columnPose(lay, i);
            float k = lay.mine(i);
            Color pc = k > 0f ? mineC.set(c).lerp(Theme.MINE, k) : c;
            piece(rect, l, l + lay.columnWidth(i), cy(y, line), h, pc, a * g * lay.lineOpacity(i));
        }
        columnEnd();
    }

    private void piece(boolean rect, float l, float r, float y, float h, Color c, float a) {
        if (rect) draw.rect(l, y - h * 0.5f, r - l, h, c, a);
        else draw.sprite(draw.glowV(), (l + r) * 0.5f, y, r - l, h, c, a);
    }

    private static float tickK(float introT, float rank) {
        float t = (introT - rank * 0.015f) / 0.20f;
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return Anim.POW3_OUT.apply(t);
    }

    private boolean rippling(int l, float now) {
        for (int k = 0; k < MAX_RIPPLES; k++) {
            float t = now - ripT[l][k];
            if (t >= 0f && t < RIPPLE_SEC) return true;
        }
        return false;
    }

    /**
     * @return true when no running ripple of line l reaches x from {@code x0} to {@code x1} ({@link #rippleOffset} is
     *         exactly 0 all along: every point is more than 4 widths from each ripple); many columns far from a hit
     *         then cost one quad instead of {@link #SEGMENTS}
     */
    private boolean stillOver(int l, float x0, float x1, float now) {
        float lo = Math.min(x0, x1), hi = Math.max(x0, x1);
        for (int k = 0; k < MAX_RIPPLES; k++) {
            float t = now - ripT[l][k];
            if (t < 0f || t >= RIPPLE_SEC) continue;
            if (!((lo - ripX[l][k]) / 120f > 4f) && !((hi - ripX[l][k]) / 120f < -4f)) return false;
        }
        return true;
    }

    private float rippleOffset(int l, float x, float now) {
        float sum = 0f;
        for (int k = 0; k < MAX_RIPPLES; k++) {
            float t = now - ripT[l][k];
            if (t < 0f || t >= RIPPLE_SEC) continue;
            float d = (x - ripX[l][k]) / 120f;
            if (d > 4f || d < -4f) continue;
            sum += ripA[l][k] * (float) Math.exp(-d * d) * MathUtils.sin(28f * t) * (1f - t / RIPPLE_SEC);
        }
        return sum;
    }

    private void rippleCore(int l, float wl, float wr, float y, float half, Color c, float now) {
        float step = (wr - wl) / SEGMENTS;
        float col = Color.toFloatBits(c.r, c.g, c.b, c.a * 0.95f);
        float u = 0.5f * (core.getU() + core.getU2());
        float v = 0.5f * (core.getV() + core.getV2());
        if (stillOver(l, wl, wl + step * SEGMENTS, now)) { // every segment level: one quad over the same span
            float xb = wl + step * (SEGMENTS - 1) + step;
            put(0, wl, y - half, col, u, v);
            put(5, wl, y + half, col, u, v);
            put(10, xb, y + half, col, u, v);
            put(15, xb, y - half, col, u, v);
            batch.draw(core.getTexture(), quad, 0, 20);
            return;
        }
        for (int s = 0; s <= SEGMENTS; s++) offs[s] = rippleOffset(l, wl + step * s, now);
        for (int s = 0; s < SEGMENTS; s++) {
            float xa = wl + step * s;
            float xb = xa + step;
            float ya = y + offs[s];
            float yb = y + offs[s + 1];
            put(0, xa, ya - half, col, u, v);
            put(5, xa, ya + half, col, u, v);
            put(10, xb, yb + half, col, u, v);
            put(15, xb, yb - half, col, u, v);
            batch.draw(core.getTexture(), quad, 0, 20);
        }
    }

    private void put(int i, float x, float y, float c, float u, float v) {
        quad[i] = x;
        quad[i + 1] = y;
        quad[i + 2] = c;
        quad[i + 3] = u;
        quad[i + 4] = v;
    }

    /**
     * Additive block: line glows ({@code glow_v} 48 tall × combo tier, α .18 + beat pulse), the
     * TIDE swell glow (hit colour, α .35 × its envelope, on the lifted line), approach glows,
     * LOST / mine flashes, hit beads and lane column glows.
     *
     * @param combo     current combo (glow height tiers 1.0 / 1.15 / 1.3 at 0 / 50 / 100)
     * @param beatPulse 0..1 beat pulse (adds α .10)
     */
    void drawAdditive(GameEngine e, float now, int combo, float beatPulse) {
        LaneLayout lay = e.layout();
        float wl = lay.windowLeft();
        float wr = lay.windowRight();
        if (wr <= wl) return;
        float tier = combo >= 100 ? 1.3f : combo >= 50 ? 1.15f : 1f;
        float shown = lanesShown(e);
        float shimmer = 0.04f * MathUtils.sin(now * MathUtils.PI2 * 2f);
        for (int li = 0; li < Line.COUNT; li++) {
            Line line = Line.of(li);
            float y = e.lineY(line);
            Color c = lineColor(line);
            across(lay, false, y, line, 48f * tier, c, 0.18f + 0.10f * beatPulse, shown);
            float sw = swellEnvelope(li, now);
            if (sw > 0.002f && swellC[li] != null) {
                float ys = y + (line == Line.UPPER ? -1f : 1f) * SWELL_LIFT * sw;
                across(lay, false, ys, line, 48f * (1f + 0.5f * sw), swellC[li], SWELL_GLOW_A * sw * swellK[li],
                    shown);
            }
            // window flash (missed TIDE)
            float wt = now - winFlashT[li];
            float wdur = winFlashMine[li] ? MINE_FLASH_SEC : LOST_FLASH_SEC;
            if (wt >= 0f && wt < wdur) {
                Color fc = winFlashMine[li] ? Theme.MINE : Theme.LOST;
                float a = (winFlashMine[li] ? 0.45f : 0.40f) * (1f - wt / wdur);
                across(lay, false, y, line, 40f, fc, a, shown);
                across(lay, true, y, line, 4f, fc, a, shown);
            }
            for (int i = 0; shown > 0.002f && i < lay.count(); i++) {
                if (!lay.active(i)) continue;
                int j = idx(line, i);
                boolean gapped = lay.separate();
                if (gapped && hidden(i)) continue;
                columnPose(lay, i); // its column's lines, depth and turn (Kelvin 2026-11-07 22:54, 11-08 23:10)
                float lx = gapped ? lay.columnLeft(i) : lay.left(i);
                float lw = gapped ? lay.columnWidth(i) : lay.width(i);
                float ly = cy(y, line);
                float op = lay.opacity(i) * shown;
                float ap = approachA[j];
                if (ap > 0.002f) {
                    Color ac = approachMine[j] ? Theme.MINE : c;
                    draw.sprite(draw.glowV(), lx + lw * 0.5f, ly, lw, 40f, ac, ap * op);
                    draw.rect(lx, ly - 2f, lw, 4f, ac, ap * 0.8f * op);
                }
                float ft = now - flashT[j];
                float fdur = flashMine[j] ? MINE_FLASH_SEC : LOST_FLASH_SEC;
                if (ft >= 0f && ft < fdur) {
                    Color fc = flashMine[j] ? Theme.MINE : Theme.LOST;
                    float a = (flashMine[j] ? 0.45f : 0.40f) * (1f - ft / fdur) * shown;
                    draw.sprite(draw.glowV(), lx + lw * 0.5f, ly, lw, 40f, fc, a);
                    draw.rect(lx, ly - 2f, lw, 4f, fc, a);
                }
                float lv = level[j];
                if (lv > 0.002f && op > 0.01f) {
                    float a = (holding[j] ? 0.28f + shimmer : 0.20f) * lv * op;
                    Color cc = c;
                    batch.setColor(cc.r, cc.g, cc.b, a);
                    if (line == Line.UPPER) batch.draw(glowDown, lx, ly - COLUMN_H, lw, COLUMN_H);
                    else batch.draw(glowUp, lx, ly, lw, COLUMN_H);
                }
            }
            columnEnd();
            // beads
            for (int k = 0; k < MAX_BEADS; k++) {
                float t = now - beadT[li][k];
                if (t < 0f || t >= BEAD_SEC) continue;
                float p = Anim.POW2_OUT.apply(t / BEAD_SEC);
                draw.sprite(draw.softDot(), beadX[li][k], y, 40f + 240f * p, 40f * (1f - 0.4f * p), c,
                    0.8f * (1f - t / BEAD_SEC));
            }
        }
        batch.setColor(Color.WHITE);
    }
}
