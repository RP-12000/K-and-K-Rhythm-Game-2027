package org.kelvinizer.fx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Theme;

/**
 * 水波纹：DROP 和 STREAM 命中时从命中点荡开的同心水环（DESIGN §20.5）。The water rings of DROP and
 * STREAM hits (DESIGN §20.5), drawn by {@link HitFx} in its additive block, one quad per ring.
 *
 * <ul>
 *   <li><b>RESONANCE head</b> ({@link #resonance}): five rings staggered 65 ms; ring k grows
 *       r 0 → 160 − 20k over 480 ms ({@code pow2Out}), α .62 → 0 (linear), about 5 units thick,
 *       radius wobbling ± 2 at 18 Hz; Low quality keeps rings 0 and 2.</li>
 *   <li><b>ECHO head</b> ({@link #echo}): one thin ring, r 0 → 100 over 420 ms, α .55 → 0.</li>
 *   <li><b>Held STREAM</b> ({@link #hold}): a small thin ring, r 0 → 70 over 360 ms, α .35 → 0
 *       (the caller spawns one every {@value #HOLD_PERIOD_SEC} s).</li>
 * </ul>
 * Colours follow the judgement on both lines: {@code resonance} cyan or {@code echo} violet
 * (DESIGN §21.1).
 *
 * <p><b>Stroke.</b> A sprite ring's stroke grows with its size, but the design wants the same
 * water line at every radius. Every ring therefore comes from the {@code ring_water} levels of the
 * shared atlas ({@link Draw#ringWater}): each frame a ring is drawn from the level whose radius
 * makes its stroke closest to the wanted width (levels are 1.35× apart, so the stroke stays within
 * about ± 16 % of it; below the smallest level's radius the line thins with the ring).
 *
 * <p><b>Mock-up match.</b> The approved mock-up ({@code preview/reference-look/v4_ripples.mp4})
 * draws every ring twice, a blurred glow copy and then the sharp line, so its rings read brighter
 * than one quad at the design α. The strokes and the α factors here ({@link #RES_GLOW},
 * {@link #ECHO_GLOW}, {@link #HOLD_GLOW}, capped at 1) were set so that the recorded game matches
 * that clip, measured on the same column of both at 1280×720 (line width at half maximum, and
 * starting α from the peak over the local background): RESONANCE 3.6 px and α .81, ECHO 2.4 px
 * and .60, held-STREAM rings 2.7 px. Factors of 1 give the plain design α.
 *
 * <p>State is a fixed struct of arrays ({@value #CAPACITY} rings, more than the densest play needs);
 * a full set recycles its oldest ring. Spawning, updating and drawing allocate nothing. Times are
 * effect seconds, advanced by {@link #update} with the game delta (frozen while paused). The
 * spawning and timing logic needs no GL, so it can be tested on the plain JVM;
 * {@link #setRegions} must run before {@link #draw}.
 */
final class WaterRings {
    /** Ring slots. */
    static final int CAPACITY = 128;
    /** Rings of a RESONANCE head hit at High quality. */
    static final int RESONANCE_RINGS = 5;
    /** Rings of a RESONANCE head hit at Low quality (indices into the High set). */
    static final int[] LOW_RINGS = {0, 2};
    static final float RES_STAGGER_SEC = 0.065f;
    static final float RES_LIFE_SEC = 0.48f;
    /** Final radius of ring 0; ring k ends at {@code RES_RADIUS − k·RES_RADIUS_STEP}. */
    static final float RES_RADIUS = 160f;
    static final float RES_RADIUS_STEP = 20f;
    /** Design α of the RESONANCE rings at birth (DESIGN §20.5). */
    static final float RES_ALPHA = 0.62f;
    /** Mock-up match: α factor of the RESONANCE rings (see the class notes). */
    static final float RES_GLOW = 1.45f;
    /** Wanted stroke of the RESONANCE rings (world units at scale 1). */
    static final float RES_STROKE = 5f;
    static final float WOBBLE = 2f;
    static final float WOBBLE_HZ = 18f;
    static final float ECHO_RADIUS = 100f;
    static final float ECHO_LIFE_SEC = 0.42f;
    /** Design α of the ECHO ring at birth. */
    static final float ECHO_ALPHA = 0.55f;
    /** Wanted stroke of the ECHO ring (world units at scale 1). */
    static final float ECHO_STROKE = 2.75f;
    /** Mock-up match: α factor of the ECHO ring (see the class notes). */
    static final float ECHO_GLOW = 1.25f;
    static final float HOLD_RADIUS = 70f;
    static final float HOLD_LIFE_SEC = 0.36f;
    /** Design α of a held-STREAM ring at birth. */
    static final float HOLD_ALPHA = 0.35f;
    /** Wanted stroke of the small held-STREAM ring (world units at scale 1). */
    static final float HOLD_STROKE = 3.25f;
    /** Mock-up match: α factor of the held-STREAM rings (see the class notes). */
    static final float HOLD_GLOW = 1.4f;
    /** Period of the held-STREAM rings (s). */
    static final float HOLD_PERIOD_SEC = 0.32f;

    /** Ring kinds: one of a RESONANCE set, the ECHO ring, a held-STREAM ring. */
    static final int RESONANCE = 0, ECHO = 1, HOLD = 2;

    private static final float LN_LOD_STEP = (float) Math.log(1.35);

    private final boolean[] live = new boolean[CAPACITY];
    private final int[] kind = new int[CAPACITY];
    private final int[] ringIndex = new int[CAPACITY];
    private final boolean[] echoColour = new boolean[CAPACITY];
    private final float[] x = new float[CAPACITY];
    private final float[] y = new float[CAPACITY];
    private final float[] start = new float[CAPACITY];
    private final float[] life = new float[CAPACITY];
    private final float[] radius = new float[CAPACITY];
    private final float[] alpha = new float[CAPACITY];
    private final float[] stroke = new float[CAPACITY];
    private final float[] wobble = new float[CAPACITY];

    private int liveCount;
    private float time;
    private long spawned;

    private final TextureRegion[] water = new TextureRegion[Draw.RING_WATER_LODS];
    private final float[] lodRadius = new float[Draw.RING_WATER_LODS];

    WaterRings() {
        for (int i = 0; i < Draw.RING_WATER_LODS; i++) lodRadius[i] = Draw.ringWaterRadius(i);
    }

    /** Binds the {@code ring_water} levels of the shared atlas; GL thread, load time. */
    void setRegions(Draw draw) {
        for (int i = 0; i < Draw.RING_WATER_LODS; i++) water[i] = draw.ringWater(i);
    }

    /**
     * Binds the {@link Draw#RING_WATER_LODS} {@code ring_water} levels directly (harness tests draw
     * into a recording batch without GL).
     */
    void setRegions(TextureRegion[] ringWater) {
        for (int i = 0; i < Draw.RING_WATER_LODS; i++) water[i] = ringWater[i];
    }

    // ------------------------------------------------------------------ spawning

    /**
     * The RESONANCE head rings at (x, y): all five at High quality, rings 0 and 2 at Low
     * ({@code low}). Ring k starts {@code 65·k} ms from now (ring 2 of the Low set too, so its
     * timing matches the High set).
     *
     * @param scale size multiplier (1 = design size)
     * @param max   most rings to spawn (the budget guard's limit; at least ring 0 always spawns)
     * @return rings spawned
     */
    int resonance(float x, float y, float scale, boolean low, int max) {
        int n = 0;
        int sets = low ? LOW_RINGS.length : RESONANCE_RINGS;
        for (int s = 0; s < sets && (n == 0 || n < max); s++) {
            int k = low ? LOW_RINGS[s] : s;
            int i = obtain();
            set(i, RESONANCE, k, false, x, y, time + RES_STAGGER_SEC * k, RES_LIFE_SEC,
                (RES_RADIUS - RES_RADIUS_STEP * k) * scale, startAlpha(RESONANCE), RES_STROKE * strokeScale(scale),
                WOBBLE * scale);
            n++;
        }
        return n;
    }

    /** The ECHO head ring at (x, y): one thin violet ring. */
    void echo(float x, float y, float scale) {
        set(obtain(), ECHO, 0, true, x, y, time, ECHO_LIFE_SEC, ECHO_RADIUS * scale, startAlpha(ECHO),
            ECHO_STROKE * strokeScale(scale), 0f);
    }

    /** A small held-STREAM ring at (x, y), in {@code echo} violet after an ECHO head. */
    void hold(float x, float y, float scale, boolean echo) {
        set(obtain(), HOLD, 0, echo, x, y, time, HOLD_LIFE_SEC, HOLD_RADIUS * scale, startAlpha(HOLD),
            HOLD_STROKE * strokeScale(scale), 0f);
    }

    /** α at birth of a ring of {@code kind}: the design α times its mock-up factor, at most 1. */
    static float startAlpha(int kind) {
        switch (kind) {
            case RESONANCE: return Math.min(1f, RES_ALPHA * RES_GLOW);
            case ECHO: return Math.min(1f, ECHO_ALPHA * ECHO_GLOW);
            default: return Math.min(1f, HOLD_ALPHA * HOLD_GLOW);
        }
    }

    private static float strokeScale(float scale) {
        return MathUtils.clamp(scale, 0.6f, 1.5f);
    }

    private void set(int i, int k, int index, boolean echo, float px, float py, float t0, float lifeSec, float r,
                     float a, float strokeW, float wob) {
        live[i] = true;
        kind[i] = k;
        ringIndex[i] = index;
        echoColour[i] = echo;
        x[i] = px;
        y[i] = py;
        start[i] = t0;
        life[i] = lifeSec;
        radius[i] = r;
        alpha[i] = a;
        stroke[i] = strokeW;
        wobble[i] = wob;
        spawned++;
    }

    /** A free slot, or the slot of the oldest ring when every slot is busy. */
    private int obtain() {
        if (liveCount < CAPACITY) {
            for (int i = 0; i < CAPACITY; i++) {
                if (!live[i]) {
                    liveCount++;
                    return i;
                }
            }
        }
        int oldest = 0;
        for (int i = 1; i < CAPACITY; i++) {
            if (start[i] < start[oldest]) oldest = i;
        }
        return oldest;
    }

    // ------------------------------------------------------------------ frame

    /** Advances the ring clock (0 while paused) and retires finished rings. */
    void update(float delta) {
        if (delta <= 0f) return;
        time += delta;
        if (liveCount == 0) return;
        for (int i = 0; i < CAPACITY; i++) {
            if (live[i] && time - start[i] >= life[i]) {
                live[i] = false;
                liveCount--;
            }
        }
    }

    /** Removes every ring. */
    void clear() {
        for (int i = 0; i < CAPACITY; i++) live[i] = false;
        liveCount = 0;
    }

    /**
     * Draws every started ring into {@code batch} (the caller has set additive blending). Leaves
     * the batch colour changed; the caller restores it.
     */
    void draw(Batch batch) {
        if (liveCount == 0 || water[0] == null) return;
        for (int i = 0; i < CAPACITY; i++) {
            if (!live[i]) continue;
            float age = time - start[i];
            if (age < 0f || age >= life[i]) continue;
            float p = age / life[i];
            float r = radius[i] * (1f - (1f - p) * (1f - p));
            if (wobble[i] != 0f) {
                r += wobble[i] * MathUtils.sin(MathUtils.PI2 * WOBBLE_HZ * age + 1.2566f * ringIndex[i]);
            }
            float a = alpha[i] * (1f - p);
            if (r < 0.5f || a <= 0.002f) continue;
            Color c = echoColour[i] ? Theme.ECHO : Theme.RESONANCE;
            batch.setPackedColor(Color.toFloatBits(c.r, c.g, c.b, a));
            int lod = lod(r, stroke[i]);
            TextureRegion reg = water[lod];
            float k = r / lodRadius[lod]; // world units per texel
            float w = reg.getRegionWidth() * k;
            float h = reg.getRegionHeight() * k;
            batch.draw(reg, x[i] - 0.5f * w, y[i] - 0.5f * h, w, h);
        }
    }

    /**
     * The {@code ring_water} level that draws a ring of radius {@code r} with a stroke closest to
     * {@code strokeW} (a level of texel radius ρ drawn at radius r has stroke FWHM·r/ρ).
     */
    int lod(float r, float strokeW) {
        float ideal = Draw.RING_WATER_FWHM * r / Math.max(0.5f, strokeW);
        if (ideal <= lodRadius[0] || lodRadius[0] == 0f) return 0;
        int i = (int) Math.floor(Math.log(ideal / lodRadius[0]) / LN_LOD_STEP + 0.5);
        return MathUtils.clamp(i, 0, Draw.RING_WATER_LODS - 1);
    }

    // ------------------------------------------------------------------ diagnostics

    /** Rings alive, started or waiting for their stagger. */
    int live() {
        return liveCount;
    }

    /** Quads the next {@link #draw} submits (started, unfinished rings). */
    int quads() {
        if (liveCount == 0) return 0;
        int n = 0;
        for (int i = 0; i < CAPACITY; i++) {
            if (!live[i]) continue;
            float age = time - start[i];
            if (age >= 0f && age < life[i]) n++;
        }
        return n;
    }

    /** Rings spawned since construction (diagnostics; never reset). */
    long spawned() {
        return spawned;
    }

    /** Ring clock (s). */
    float time() {
        return time;
    }

    /** Live rings of {@code kind} ({@link #RESONANCE}, {@link #ECHO}, {@link #HOLD}). */
    int live(int ofKind) {
        int n = 0;
        for (int i = 0; i < CAPACITY; i++) {
            if (live[i] && kind[i] == ofKind) n++;
        }
        return n;
    }

    /**
     * Fills {@code out} (length ≥ 5) with ring {@code slot}'s start time, life, final radius,
     * starting α and wanted stroke, or returns false when the slot is free (tests).
     */
    boolean describe(int slot, float[] out) {
        if (slot < 0 || slot >= CAPACITY || !live[slot]) return false;
        out[0] = start[slot];
        out[1] = life[slot];
        out[2] = radius[slot];
        out[3] = alpha[slot];
        if (out.length > 4) out[4] = stroke[slot];
        return true;
    }

    /** Kind of the ring in {@code slot} (tests). */
    int kindOf(int slot) {
        return kind[slot];
    }

    /** Index of the ring in its RESONANCE set (tests). */
    int indexOf(int slot) {
        return ringIndex[slot];
    }

    /** True when the ring in {@code slot} is violet (tests). */
    boolean echoOf(int slot) {
        return echoColour[slot];
    }
}
