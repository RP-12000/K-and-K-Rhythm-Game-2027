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
 * 判定线与轨道区：两条判定线、整高的轨道分隔线、命中涟漪与潮汐命中时整条线的涌起。Judgement lines and
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
 * Every lane visual uses the lane's current span and is multiplied by its opacity. Times are in
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

    private static Color lineColor(Line line) {
        return line == Line.UPPER ? Theme.SKY_SIG : Theme.GROUND_SIG;
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
     * @param zoneK  zone hint strength (0 = setting off; 1 = normal; larger in the tutorial pulse)
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
            if (a > 0.001f) zoneQuads(wl, ww, y, Field.zoneHalf(), c, a);
            float t = tideApproach[li];
            if (t > 0f) zoneQuads(wl, ww, y, zone(), Theme.TIDE, 0.06f * t);
        }
        float y0 = e.lineY(Line.LOWER);
        float y1 = e.lineY(Line.UPPER);
        for (int i = 0; i < lay.count(); i++) {
            if (!lay.active(i)) continue;
            float op = boundaryOpacity(lay, i);
            if (op > 0.01f) separator(lay.left(i), y0, y1, op * introK);
            if (i == lay.count() - 1 || !lay.active(i + 1)) {
                float o2 = lay.opacity(i);
                if (o2 > 0.01f) separator(lay.right(i), y0, y1, o2 * introK);
            }
        }
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
        for (int li = 0; li < Line.COUNT; li++) {
            Line line = Line.of(li);
            float y = e.lineY(line);
            Color c = lineColor(line);
            float sw = swellEnvelope(li, now);
            float yc = y + (line == Line.UPPER ? -1f : 1f) * SWELL_LIFT * sw;
            float half = 2f + 0.5f * SWELL_THICKEN * sw;
            if (rippling(li, now)) rippleCore(li, wl, wr, yc, half, c, now);
            else draw.rect(wl, yc - half, wr - wl, 2f * half, c, 0.95f);
            // ticks
            for (int i = 0; i < lay.count(); i++) {
                if (!lay.active(i)) continue;
                float k = tickK(introT, Math.abs(i - mid));
                float op = boundaryOpacity(lay, i);
                if (k > 0f && op > 0.01f) {
                    draw.rect(lay.left(i) - 1f, y - 10f * k, 2f, 20f * k, Theme.INK1, TICK_A * op);
                }
                if (i == lay.count() - 1 || !lay.active(i + 1)) {
                    float k2 = tickK(introT, Math.abs(i + 1 - mid));
                    float o2 = lay.opacity(i);
                    if (k2 > 0f && o2 > 0.01f) {
                        draw.rect(lay.right(i) - 1f, y - 10f * k2, 2f, 20f * k2, Theme.INK1, TICK_A * o2);
                    }
                }
            }
        }
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
                draw.rect(wl, y - 2f, wr - wl, 4f, fc, a);
            }
            for (int i = 0; i < lay.count(); i++) {
                if (!lay.active(i)) continue;
                int j = idx(line, i);
                float lx = lay.left(i);
                float lw = lay.width(i);
                float op = lay.opacity(i);
                float ap = approachA[j];
                if (ap > 0.002f) {
                    Color ac = approachMine[j] ? Theme.MINE : c;
                    draw.sprite(draw.glowV(), lx + lw * 0.5f, y, lw, 40f, ac, ap * op);
                    draw.rect(lx, y - 2f, lw, 4f, ac, ap * 0.8f * op);
                }
                float ft = now - flashT[j];
                float fdur = flashMine[j] ? MINE_FLASH_SEC : LOST_FLASH_SEC;
                if (ft >= 0f && ft < fdur) {
                    Color fc = flashMine[j] ? Theme.MINE : Theme.LOST;
                    float a = (flashMine[j] ? 0.45f : 0.40f) * (1f - ft / fdur);
                    draw.sprite(draw.glowV(), lx + lw * 0.5f, y, lw, 40f, fc, a);
                    draw.rect(lx, y - 2f, lw, 4f, fc, a);
                }
                float lv = level[j];
                if (lv > 0.002f && op > 0.01f) {
                    float a = (holding[j] ? 0.28f + shimmer : 0.20f) * lv * op;
                    Color cc = c;
                    batch.setColor(cc.r, cc.g, cc.b, a);
                    if (line == Line.UPPER) batch.draw(glowDown, lx, y - COLUMN_H, lw, COLUMN_H);
                    else batch.draw(glowUp, lx, y, lw, COLUMN_H);
                }
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
}
