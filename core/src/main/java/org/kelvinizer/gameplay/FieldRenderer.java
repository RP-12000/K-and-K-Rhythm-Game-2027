package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Theme;

/**
 * 判定线：两条判定线、判定区提示、命中涟漪与浪花命中时整条线的涌起、线上一直冒出又消失的量子小泡泡；无轨后没有轨道分隔线和刻度，
 * 光柱、闪光和接近提示都在键自己的位置。Judgement lines (DESIGN §5.7, §5.10 column glow, §19.2 line swell),
 * laneless since Kelvin 2026-11-05 (「八轨改无轨」): no lane separators or ticks; column glows, flashes and approach
 * glows sit where the keys are, one key wide. Since 2026-11-05 23:11 the lines are a little thicker
 * ({@link #LINE_HALF}) and small bubbles keep welling up on them and vanishing ({@link #drawBubbles}).
 *
 * <p>Three passes, matching the blend blocks of §5.1:
 * <ul>
 *   <li>{@link #drawUnder} (normal block A): zone hint, and the dividers and ticks of the fixed lanes where the chart
 *       has them (定轨, Kelvin 2026-11-06: the old separators come back, {@link #drawFixedLanes});</li>
 *   <li>{@link #drawLines} (normal block B): line cores (with the High-quality hit ripple and the
 *       TIDE line swell);</li>
 *   <li>{@link #drawAdditive} (additive block): line glows with combo tiers and beat pulse, the
 *       swell glow, approach glows, line flashes (LOST / mine), hit beads and column glows (under each finger
 *       in a zone, at its hold while it holds one; autoplay pulses and holds).</li>
 * </ul>
 * Times are in "run seconds" (frozen while paused). No allocation.
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
     * Zone hint α at the line: .07 since the zone was ±0.4 of the height (Kelvin 2026-10-18 19:52), the strength the
     * old settings preview drew; .035 over the ±0.1 band before, which spread over ±0.4 left no visible tint. Kept for
     * the ±160 zone of 7.184–7.188 and since.
     */
    static final float ZONE_HINT_A = 0.07f;
    /**
     * Fixed lane dividers (定轨, Kelvin 2026-11-06 14:55: 「定轨模式下还是有那些竖着的分割」), the old lane separators
     * (DESIGN §21.2): α at each line and at mid-field (quadratic between), a soft glow (total width, peak α), the
     * linear pieces of the α curve, and the ticks on the lines.
     */
    static final float SEPARATOR_LINE_A = 0.34f;
    static final float SEPARATOR_MID_A = 0.16f;
    static final float SEPARATOR_GLOW_W = 10f;
    static final float SEPARATOR_GLOW_A = 0.05f;
    private static final int SEPARATOR_SEGMENTS = 6;
    static final float TICK_A = 0.75f;
    /** Where the lane count changes the old dividers fade out and the new ones in, this many beats each side. */
    static final float LANE_FADE_BEATS = 0.25f;
    private static final int SEGMENTS = 48;
    private static final int MAX_RIPPLES = 4;
    private static final int MAX_BEADS = 8;
    private static final float RIPPLE_SEC = 0.22f;
    private static final float BEAD_SEC = 0.20f;
    /** LOST / mine flashes under keys, autoplay column pulses and approach glows kept at once. */
    private static final int MAX_FLASHES = 16;
    private static final int MAX_PULSES = 16;
    private static final int MAX_APPROACH = 64;
    private static final int MAX_AUTO_HOLDS = 16;
    /** TIDE line swell (DESIGN §19.2): settles over 320 ms after a 60 ms rise; 4 px lift. */
    static final float SWELL_SEC = 0.32f;
    static final float SWELL_RISE_SEC = 0.06f;
    static final float SWELL_LIFT = 4f;
    /** Extra core thickness at the swell's peak. */
    static final float SWELL_THICKEN = 2f;
    static final float SWELL_GLOW_A = 0.35f;
    /** Half the thickness of a line's core (Kelvin 2026-11-05 23:11 「稍微加粗一点判定线」: 6, was 4). */
    static final float LINE_HALF = 3f;
    /**
     * Quantum bubbles (Kelvin 2026-11-05 23:11, 「像量子涨落一样…一直有这种小泡泡浮现…浮现完以后直接消失，逼真一点」):
     * slots per line, each slot's cycle (seconds, a slot's own between the two), a bubble's life within a cycle,
     * its largest radius (small ones are likelier), how far off the line it wells up, how far it rises meanwhile
     * (times its radius: it leaves the line as it grows), its opacity and how much lighter than the line it is.
     */
    static final int BUBBLE_SLOTS = 22;
    static final float BUBBLE_CYCLE_MIN = 0.9f, BUBBLE_CYCLE_MAX = 2.2f, BUBBLE_LIFE_MIN = 0.30f,
        BUBBLE_LIFE_MAX = 0.85f, BUBBLE_R_MIN = 3f, BUBBLE_R_MAX = 10f, BUBBLE_OFF = 2f, BUBBLE_RISE = 1.4f,
        BUBBLE_A = 0.9f, BUBBLE_LIGHT = 0.45f;
    private static final float LOST_FLASH_SEC = 0.20f;
    private static final float MINE_FLASH_SEC = 0.22f;
    private static final float ATTACK_SEC = 0.04f;
    private static final float RELEASE_SEC = 0.16f;

    private final Draw draw;
    private final Batch batch;
    private final TextureRegion core;
    private final TextureRegion glowUp;
    private final TextureRegion glowDown;
    /** The quantum bubble sprite. */
    private final Draw.Skin bubble;
    private final float[] quad = new float[20];
    private final float[] offs = new float[SEGMENTS + 1];
    private final Color c0 = new Color();
    private final Color c1 = new Color();

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

    // LOST / mine flashes under a key: x, width, line, start, mine (a ring)
    private final float[] flashX = new float[MAX_FLASHES];
    private final float[] flashW = new float[MAX_FLASHES];
    private final int[] flashLine = new int[MAX_FLASHES];
    private final float[] flashT = new float[MAX_FLASHES];
    private final boolean[] flashMine = new boolean[MAX_FLASHES];
    /** The engine's layout as of the last {@link #update}, for the chart editor's lane-index calls. */
    private LaneLayout editorLay;
    private int flashNext;
    // column glow per pointer: x, width, line; level with its attack and release
    private final float[] colX = new float[GameEngine.MAX_POINTERS];
    private final float[] colW = new float[GameEngine.MAX_POINTERS];
    private final int[] colLine = new int[GameEngine.MAX_POINTERS];
    private final float[] level = new float[GameEngine.MAX_POINTERS];
    private final float[] relFrom = new float[GameEngine.MAX_POINTERS];
    private final float[] relT = new float[GameEngine.MAX_POINTERS];
    private final boolean[] holding = new boolean[GameEngine.MAX_POINTERS];
    // autoplay: column pulses on hits (a ring; seconds since each) and the running holds of this frame
    private final float[] pulseX = new float[MAX_PULSES];
    private final float[] pulseW = new float[MAX_PULSES];
    private final int[] pulseLine = new int[MAX_PULSES];
    private final float[] pulseAge = new float[MAX_PULSES];
    private int pulseNext;
    private final float[] autoX = new float[MAX_AUTO_HOLDS];
    private final float[] autoW = new float[MAX_AUTO_HOLDS];
    private final int[] autoLine = new int[MAX_AUTO_HOLDS];
    private int autoCount;
    // approach glows of this frame (one per place: the strongest, a mine's warning first)
    private final float[] apX = new float[MAX_APPROACH];
    private final float[] apW = new float[MAX_APPROACH];
    private final int[] apLine = new int[MAX_APPROACH];
    private final float[] apA = new float[MAX_APPROACH];
    private final boolean[] apMine = new boolean[MAX_APPROACH];
    private int apCount;

    /** Creates the renderer over the shared atlas. */
    FieldRenderer(Draw draw) {
        this.draw = draw;
        this.batch = draw.batch();
        core = new TextureRegion(draw.white(), 1, 1, 2, 2);
        TextureRegion g = draw.glowV();
        int h = g.getRegionHeight() / 2;
        glowUp = new TextureRegion(g, 0, 0, g.getRegionWidth(), h);
        glowDown = new TextureRegion(g, 0, h, g.getRegionWidth(), h);
        bubble = draw.skin(Draw.BUBBLE);
    }

    /** Clears every effect for a new chart. */
    void begin(Chart chart) {
        clear();
    }

    /** Clears every running effect. */
    void clear() {
        java.util.Arrays.fill(flashT, -100f);
        java.util.Arrays.fill(level, 0f);
        java.util.Arrays.fill(relT, -1f);
        java.util.Arrays.fill(holding, false);
        java.util.Arrays.fill(pulseAge, RELEASE_SEC);
        autoCount = 0;
        for (int l = 0; l < Line.COUNT; l++) {
            java.util.Arrays.fill(ripT[l], -100f);
            java.util.Arrays.fill(beadT[l], -100f);
            winFlashT[l] = -100f;
            swellT[l] = -100f;
        }
        clearApproach();
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
     *         the {@link #LINE_HALF} half stroke, a TIDE swell's lift and thickening, the reach of its running
     *         ripples and one unit of edge; a veil leaves this band as it is, so the line stays crisp above the water
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
        return LINE_HALF + (SWELL_LIFT + 0.5f * SWELL_THICKEN) * sw + reach + 1f;
    }

    /**
     * Flashes the line under a key, {@code w} wide at x (world): LOST (#FF5C7A @ .40, 200 ms) or mine (@ .45, 220 ms);
     * {@code w} 0 (a TIDE) flashes the whole window.
     */
    void flash(Line line, float x, float w, boolean mine, float now) {
        if (line == null) return;
        if (!(w > 0f)) {
            flashWindow(line, mine, now);
            return;
        }
        int k = flashNext;
        flashNext = (k + 1) % MAX_FLASHES;
        flashX[k] = x;
        flashW[k] = w;
        flashLine[k] = line.ordinal();
        flashT[k] = now;
        flashMine[k] = mine;
    }

    /** Flashes the whole window of a line (a missed TIDE). */
    void flashWindow(Line line, boolean mine, float now) {
        winFlashT[line.ordinal()] = now;
        winFlashMine[line.ordinal()] = mine;
    }

    /** A short column pulse at a key with no pointer (autoplay hits): full level, then the release fade. */
    void pulseColumn(Line line, float x, float w) {
        if (line == null || !(w > 0f)) return;
        int k = pulseNext;
        pulseNext = (k + 1) % MAX_PULSES;
        pulseX[k] = x;
        pulseW[k] = w;
        pulseLine[k] = line.ordinal();
        pulseAge[k] = 0f;
    }

    // The chart editor's 实机 view (its CharterPreview) still passes lane indices; kept until it moves to x.

    /** The chart editor's old call: flashes the centre of lane {@code lane} (chart lane index), one key wide. */
    void flash(Line line, int lane, boolean mine, float now) {
        if (editorLay != null && lane >= 0 && lane < editorLay.count()) {
            flash(line, editorLay.centre(lane), editorLay.defaultWidth(), mine, now);
        }
    }

    /** The chart editor's old call: a column pulse at the centre of lane {@code lane}, one key wide. */
    void pulseColumn(Line line, int lane) {
        if (editorLay != null && lane >= 0 && lane < editorLay.count()) {
            pulseColumn(line, editorLay.centre(lane), editorLay.defaultWidth());
        }
    }

    /** The chart editor's old call ({@code introT} is no longer used). */
    void drawLines(GameEngine e, float now, float introT) {
        drawLines(e, now);
    }

    /** Clears the per-frame approach state (call before {@link NoteRenderer#prepare}). */
    void clearApproach() {
        apCount = 0;
        tideApproach[0] = 0f;
        tideApproach[1] = 0f;
    }

    /**
     * Raises the approach glow of the line under a key this frame, {@code w} wide at x (world; mine = warning
     * colour). Keys in the same place share one glow: a mine's warning wins, else the strongest.
     */
    void approach(Line line, float x, float w, float alpha, boolean mine) {
        if (!(w > 0f)) return;
        int l = line.ordinal();
        int i = 0;
        while (i < apCount && !(apLine[i] == l && Math.abs(apX[i] - x) < 0.5f * Math.max(w, apW[i]))) i++;
        if (i == apCount) {
            if (apCount == MAX_APPROACH) return;
            apCount++;
            apX[i] = x;
            apW[i] = w;
            apLine[i] = l;
            apA[i] = 0f;
            apMine[i] = false;
        }
        if (mine) {
            if (alpha > apA[i] || !apMine[i]) {
                apA[i] = Math.max(alpha, apMine[i] ? apA[i] : 0f);
                apMine[i] = true;
            }
        } else if (!apMine[i] && alpha > apA[i]) {
            apA[i] = alpha;
        }
    }

    /** Raises the TIDE approach (whole zone hint toward {@code tide @ .06}) of a line, 0..1. */
    void tideApproach(Line line, float k) {
        int l = line.ordinal();
        if (k > tideApproach[l]) tideApproach[l] = k;
    }

    // ------------------------------------------------------------------ update

    /**
     * Updates the column glows from the engine's pointers and running holds (DESIGN §5.10): one under each finger
     * in a zone, one key wide (at its hold while it holds one, brighter), 40 ms attack while down, 160 ms
     * {@code pow2Out} release; autoplay's running holds glow at their keys.
     */
    void update(float dt, GameEngine e) {
        Chart chart = e.chart();
        if (chart == null) return;
        LaneLayout lay = e.layout();
        editorLay = lay;
        for (int p = 0; p < GameEngine.MAX_POINTERS; p++) {
            Line line = e.pointerDown(p) ? e.pointerLine(p) : null;
            if (line != null) {
                int h = e.pointerHold(p);
                holding[p] = h >= 0 && e.isHoldHeld(h);
                Note n = holding[p] ? chart.note(h) : null;
                colX[p] = n != null ? lay.noteX(n) : e.pointerX(p);
                colW[p] = n != null ? lay.noteWidth(n) : lay.defaultWidth();
                colLine[p] = n != null ? n.line().ordinal() : line.ordinal();
                relT[p] = -1f;
                level[p] = Math.min(1f, level[p] + dt / ATTACK_SEC);
            } else if (level[p] > 0f) {
                if (relT[p] < 0f) {
                    relT[p] = 0f;
                    relFrom[p] = level[p];
                }
                relT[p] += dt;
                if (relT[p] >= RELEASE_SEC) {
                    level[p] = 0f;
                    relT[p] = -1f;
                } else {
                    level[p] = relFrom[p] * (1f - Anim.POW2_OUT.apply(relT[p] / RELEASE_SEC));
                }
            }
        }
        for (int k = 0; k < MAX_PULSES; k++) if (pulseAge[k] < RELEASE_SEC) pulseAge[k] += dt;
        autoCount = 0;
        if (!e.autoplay()) return;
        for (int k = 0; k < e.activeHoldCount() && autoCount < MAX_AUTO_HOLDS; k++) {
            int i = e.activeHold(k);
            Note n = chart.note(i);
            if (!n.hasX() || !e.isHoldHeld(i)) continue;
            autoX[autoCount] = lay.noteX(n);
            autoW[autoCount] = lay.noteWidth(n);
            autoLine[autoCount++] = n.line().ordinal();
        }
    }

    // ------------------------------------------------------------------ drawing

    private static Color lineColor(Line line) {
        return line == Line.UPPER ? Theme.SKY_SIG : Theme.GROUND_SIG;
    }

    /**
     * Normal block A: zone hint (two gradient quads per line, α {@link #ZONE_HINT_A} at the line → 0 at the edge
     * of the touch zone, {@link Field#zoneHalf} ±0.4 of the height (Kelvin 2026-10-18 19:52; ±160 in 7.184–7.188,
     * the ±0.1 band before), times {@code zoneK}; a TIDE approach adds {@code tide @ .06} over the band), then
     * the fixed lanes' dividers and ticks ({@link #drawFixedLanes}).
     *
     * @param zoneK  zone hint strength (0 = hidden; 1 = ZONE HINT 50 %, 2 at 100 %; larger in the tutorial pulse)
     * @param introK 0..1 field intro (zone hint fade-in)
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
            if (a > 0.001f) zoneQuads(wl, ww, y, Field.zoneHalf(), c, a);
            float t = tideApproach[li];
            if (t > 0f) zoneQuads(wl, ww, y, zone(), Theme.TIDE, 0.06f * t);
        }
        drawFixedLanes(e, introK);
    }

    /**
     * The fixed lanes in force ({@link Chart#fixedLanesAt}, none for 无轨): at each lane boundary, the field's two edges
     * included, a separator from line to line and a 2×20 tick on each line ({@code ink1 @} {@link #TICK_A}), where the
     * keys are ({@link LaneLayout#x}: lane i of k ends at {@code −960 + i·1920/k}). Around a change of the lane count
     * the two sets crossfade over {@link #LANE_FADE_BEATS} each side of its beat.
     */
    private void drawFixedLanes(GameEngine e, float introK) {
        Chart chart = e.chart();
        if (chart == null || !(introK > 0f)) return;
        float[] beats = chart.fixedLaneBeats();
        if (beats.length == 0) return;
        int[] counts = chart.fixedLaneCounts();
        LaneLayout lay = e.layout();
        float beat = lay.beat();
        // the nearest change of the count within the fade
        int near = -1;
        float best = LANE_FADE_BEATS;
        for (int j = 0; j < beats.length; j++) {
            float d = Math.abs(beat - beats[j]);
            if (d < best) {
                best = d;
                near = j;
            }
        }
        if (near < 0) {
            fixedLanes(e, lay, chart.fixedLanesAt(beat), introK);
            return;
        }
        int before = near > 0 ? counts[near - 1] : beats[0] <= 0f ? counts[0] : 0;
        int after = counts[near];
        if (before == after) {
            fixedLanes(e, lay, after, introK);
            return;
        }
        float u = (beat - beats[near] + LANE_FADE_BEATS) / (2f * LANE_FADE_BEATS);
        u = u < 0f ? 0f : u > 1f ? 1f : u;
        fixedLanes(e, lay, before, (1f - u) * introK);
        fixedLanes(e, lay, after, u * introK);
    }

    /** Draws the dividers and ticks of {@code k} fixed lanes at opacity {@code op} (nothing for k 0). */
    private void fixedLanes(GameEngine e, LaneLayout lay, int k, float op) {
        if (k <= 0 || op <= 0.01f) return;
        float y0 = e.lineY(Line.LOWER);
        float y1 = e.lineY(Line.UPPER);
        float step = LaneLayout.VIEW_RIGHT / k;
        for (int i = 0; i <= k; i++) {
            float x = lay.x(i * step - LaneLayout.TILE_CENTRE);
            separator(x, y0, y1, op);
            draw.rect(x - 1f, y0 - 10f, 2f, 20f, Theme.INK1, TICK_A * op);
            draw.rect(x - 1f, y1 - 10f, 2f, 20f, Theme.INK1, TICK_A * op);
        }
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

    /** Colour {@code c} at α {@code a} on the line at y, fading to 0 at {@code z} above and below it. */
    private void zoneQuads(float x, float w, float y, float z, Color c, float a) {
        Theme.withAlpha(c, a, c0);
        Theme.withAlpha(c, 0f, c1);
        draw.rectGradientV(x, y, w, z, c0, c1, 1f);
        draw.rectGradientV(x, y - z, w, z, c1, c0, 1f);
    }

    /**
     * Normal block B: line cores ({@link #LINE_HALF} × 2 thick, α .95, across the window; drawn as 48 rippling
     * segments while a High-quality ripple runs; lifted up to 4 px toward the field and up to 2 px thicker
     * during a TIDE swell), then the quantum bubbles on them ({@link #drawBubbles}).
     */
    void drawLines(GameEngine e, float now) {
        LaneLayout lay = e.layout();
        float wl = lay.windowLeft();
        float wr = lay.windowRight();
        if (wr <= wl) return;
        for (int li = 0; li < Line.COUNT; li++) {
            Line line = Line.of(li);
            float y = e.lineY(line);
            Color c = lineColor(line);
            float sw = swellEnvelope(li, now);
            float yc = y + (line == Line.UPPER ? -1f : 1f) * SWELL_LIFT * sw;
            float half = LINE_HALF + 0.5f * SWELL_THICKEN * sw;
            if (rippling(li, now)) rippleCore(li, wl, wr, yc, half, c, now);
            else draw.rect(wl, yc - half, wr - wl, 2f * half, c, 0.95f);
            drawBubbles(li, wl, wr, yc, c, now);
        }
    }

    /**
     * The quantum bubbles of line {@code l} (at {@code y} across {@code [wl, wr]}): each of {@link #BUBBLE_SLOTS}
     * slots runs cycles of its own length; in each cycle, at a moment, place and size drawn from the slot and the
     * cycle, a bubble wells up out of nothing on the line (its radius rising fast, then slower, its light fading in
     * over the first moments), rising off the line as it grows, and, once it has fully welled up, is simply gone.
     * Drawn with the {@link Draw#BUBBLE} sprite (a clear body, a bright rim, a highlight and a glint) in the line
     * colour, lightened by {@link #BUBBLE_LIGHT} toward white. Stateless: the run clock
     * {@code now} decides everything, so the bubbles stand still while the game is paused. No allocation.
     */
    private void drawBubbles(int l, float wl, float wr, float y, Color c, float now) {
        Draw.Skin b = bubble;
        int salt = (l + 1) * 0x51ED27;
        for (int k = 0; k < BUBBLE_SLOTS; k++) {
            int slot = salt + k * 0x2F0B3;
            float period = BUBBLE_CYCLE_MIN + (BUBBLE_CYCLE_MAX - BUBBLE_CYCLE_MIN) * Anim.hash01(slot, 1);
            float tt = now + period * Anim.hash01(slot, 2);
            float cyc = (float) Math.floor(tt / period);
            int ci = (int) cyc;
            float u = tt - cyc * period;
            float life = BUBBLE_LIFE_MIN + (BUBBLE_LIFE_MAX - BUBBLE_LIFE_MIN) * Anim.hash01(slot + 3, ci);
            life = Math.min(life, period);
            float p = (u - (period - life) * Anim.hash01(slot + 4, ci)) / life;
            if (p < 0f || p >= 1f) continue;
            float x = wl + (wr - wl) * Anim.hash01(slot + 5, ci);
            float h = Anim.hash01(slot + 6, ci);
            float rMax = BUBBLE_R_MIN + (BUBBLE_R_MAX - BUBBLE_R_MIN) * h * h;
            float grow = 1f - (1f - p) * (1f - p);
            float r = rMax * (0.12f + 0.88f * grow);
            float a = BUBBLE_A * Math.min(1f, p / 0.2f);
            float cy = y + BUBBLE_OFF * (2f * Anim.hash01(slot + 7, ci) - 1f) + BUBBLE_RISE * rMax * grow;
            float half = r / Draw.BUBBLE_R;
            float col = Color.toFloatBits(c.r + (1f - c.r) * BUBBLE_LIGHT, c.g + (1f - c.g) * BUBBLE_LIGHT,
                c.b + (1f - c.b) * BUBBLE_LIGHT, a);
            put(0, x - half, cy - half, col, b.uA, b.vB);
            put(5, x - half, cy + half, col, b.uA, b.vT);
            put(10, x + half, cy + half, col, b.uS, b.vT);
            put(15, x + half, cy - half, col, b.uS, b.vB);
            batch.draw(b.region.getTexture(), quad, 0, 20);
        }
    }

    private boolean rippling(int l, float now) {
        for (int k = 0; k < MAX_RIPPLES; k++) {
            float t = now - ripT[l][k];
            if (t >= 0f && t < RIPPLE_SEC) return true;
        }
        return false;
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
        for (int s = 0; s <= SEGMENTS; s++) offs[s] = rippleOffset(l, wl + step * s, now);
        float col = Color.toFloatBits(c.r, c.g, c.b, c.a * 0.95f);
        float u = 0.5f * (core.getU() + core.getU2());
        float v = 0.5f * (core.getV() + core.getV2());
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
     * LOST / mine flashes, hit beads and column glows.
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
        float shimmer = 0.04f * MathUtils.sin(now * MathUtils.PI2 * 2f);
        for (int li = 0; li < Line.COUNT; li++) {
            Line line = Line.of(li);
            float y = e.lineY(line);
            Color c = lineColor(line);
            draw.sprite(draw.glowV(), (wl + wr) * 0.5f, y, wr - wl, 48f * tier, c, 0.18f + 0.10f * beatPulse);
            float sw = swellEnvelope(li, now);
            if (sw > 0.002f && swellC[li] != null) {
                float ys = y + (line == Line.UPPER ? -1f : 1f) * SWELL_LIFT * sw;
                draw.sprite(draw.glowV(), (wl + wr) * 0.5f, ys, wr - wl, 48f * (1f + 0.5f * sw), swellC[li],
                    SWELL_GLOW_A * sw * swellK[li]);
            }
            // window flash (missed TIDE)
            float wt = now - winFlashT[li];
            float wdur = winFlashMine[li] ? MINE_FLASH_SEC : LOST_FLASH_SEC;
            if (wt >= 0f && wt < wdur) {
                Color fc = winFlashMine[li] ? Theme.MINE : Theme.LOST;
                float a = (winFlashMine[li] ? 0.45f : 0.40f) * (1f - wt / wdur);
                draw.sprite(draw.glowV(), (wl + wr) * 0.5f, y, wr - wl, 40f, fc, a);
                draw.rect(wl, y - LINE_HALF, wr - wl, 2f * LINE_HALF, fc, a);
            }
            for (int k = 0; k < apCount; k++) {
                if (apLine[k] != li || apA[k] <= 0.002f) continue;
                Color ac = apMine[k] ? Theme.MINE : c;
                draw.sprite(draw.glowV(), apX[k], y, apW[k], 40f, ac, apA[k]);
                draw.rect(apX[k] - 0.5f * apW[k], y - LINE_HALF, apW[k], 2f * LINE_HALF, ac, apA[k] * 0.8f);
            }
            for (int k = 0; k < MAX_FLASHES; k++) {
                float ft = now - flashT[k];
                float fdur = flashMine[k] ? MINE_FLASH_SEC : LOST_FLASH_SEC;
                if (flashLine[k] != li || ft < 0f || ft >= fdur) continue;
                Color fc = flashMine[k] ? Theme.MINE : Theme.LOST;
                float a = (flashMine[k] ? 0.45f : 0.40f) * (1f - ft / fdur);
                draw.sprite(draw.glowV(), flashX[k], y, flashW[k], 40f, fc, a);
                draw.rect(flashX[k] - 0.5f * flashW[k], y - LINE_HALF, flashW[k], 2f * LINE_HALF, fc, a);
            }
            for (int p = 0; p < GameEngine.MAX_POINTERS; p++) {
                if (colLine[p] == li && level[p] > 0.002f) {
                    column(line, y, colX[p], colW[p], (holding[p] ? 0.28f + shimmer : 0.20f) * level[p], c);
                }
            }
            for (int k = 0; k < MAX_PULSES; k++) {
                if (pulseLine[k] != li || pulseAge[k] >= RELEASE_SEC) continue;
                float lv = 1f - Anim.POW2_OUT.apply(pulseAge[k] / RELEASE_SEC);
                column(line, y, pulseX[k], pulseW[k], 0.20f * lv, c);
            }
            for (int k = 0; k < autoCount; k++) {
                if (autoLine[k] == li) column(line, y, autoX[k], autoW[k], 0.28f + shimmer, c);
            }
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

    /** One column glow from the line at y into the field, {@code w} wide at x, at α {@code a}. */
    private void column(Line line, float y, float x, float w, float a, Color c) {
        batch.setColor(c.r, c.g, c.b, a);
        if (line == Line.UPPER) batch.draw(glowDown, x - 0.5f * w, y - COLUMN_H, w, COLUMN_H);
        else batch.draw(glowUp, x - 0.5f * w, y, w, COLUMN_H);
    }
}
