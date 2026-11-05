package org.kelvinizer.fx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.ui.Draw;

/**
 * 水波纹：水花的正圆波纹（DROP、STREAM 头、按住和放开时），从中心荡开、慢慢变淡（Kelvin 2026-10-26：圆形的，不要椭圆）。
 *
 * <p>The round ripples of the DROP / STREAM splash ({@link Splash}), drawn by {@link HitFx} in its additive block:
 * perfect circles growing r 0 → R ({@code pow2Out}) while α falls to 0 (linear) over their life. Each ring is a line of
 * the wanted stroke plus a soft glow copy ({@value #GLOW} of its α, a gaussian {@value #GLOW_WIDTH} strokes wide at
 * half height), as the approved mock-up draws it: two quads per ring.
 *
 * <p><b>Stroke.</b> A sprite ring's stroke grows with its size, but the design wants the same water line at every
 * radius. Every ring therefore comes from the {@code ring_water} levels of the shared atlas ({@link Draw#ringWater}):
 * each frame a ring is drawn from the level whose radius makes its stroke closest to the wanted width (levels are 1.35×
 * apart, so the stroke stays within about ± 16 % of it; below the smallest level's radius the line thins with the
 * ring).
 *
 * <p>State is a fixed struct of arrays ({@value #CAPACITY} rings, more than the densest play needs); a full set
 * recycles its oldest ring. Spawning, updating and drawing allocate nothing. Times are effect seconds, advanced by
 * {@link #update} with the game delta (frozen while paused). The spawning and timing logic needs no GL, so it can be
 * tested on the plain JVM; {@link #setRegions} must run before {@link #draw}.
 */
final class WaterRings {
    /** Ring slots. */
    static final int CAPACITY = 160;
    /** α of a ring's glow copy, relative to its line (the mock-up's glow). */
    static final float GLOW = 0.35f;
    /** Width at half height of the glow copy in strokes: the mock-up's exp(−(d / 1.6 w)²) is 2 · 1.6 · √ln 2 wide. */
    static final float GLOW_WIDTH = 2.664f;

    private static final float LN_LOD_STEP = (float) Math.log(1.35);

    private final boolean[] live = new boolean[CAPACITY];
    private final Color[] colour = new Color[CAPACITY];
    private final float[] x = new float[CAPACITY];
    private final float[] y = new float[CAPACITY];
    private final float[] start = new float[CAPACITY];
    private final float[] life = new float[CAPACITY];
    private final float[] radius = new float[CAPACITY];
    private final float[] alpha = new float[CAPACITY];
    private final float[] stroke = new float[CAPACITY];

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
     * Binds the {@link Draw#RING_WATER_LODS} {@code ring_water} levels directly (tests draw into a
     * recording batch without GL).
     */
    void setRegions(TextureRegion[] ringWater) {
        for (int i = 0; i < Draw.RING_WATER_LODS; i++) water[i] = ringWater[i];
    }

    // ------------------------------------------------------------------ spawning

    /**
     * A ring centred at (x, y), starting {@code delaySec} after {@code atSec} on the ring clock (a ring born between
     * two frames keeps its own timing).
     *
     * @param r       final radius
     * @param a       α at birth (of its line; the glow copy takes {@value #GLOW} of it)
     * @param strokeW wanted stroke (world units)
     * @param c       colour (a shared {@code Theme} constant, never copied)
     */
    void spawn(float px, float py, float atSec, float delaySec, float lifeSec, float r, float a, float strokeW,
               Color c) {
        int i = obtain();
        live[i] = true;
        colour[i] = c;
        x[i] = px;
        y[i] = py;
        start[i] = atSec + delaySec;
        life[i] = lifeSec;
        radius[i] = r;
        alpha[i] = a;
        stroke[i] = strokeW;
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
                colour[i] = null;
                liveCount--;
            }
        }
    }

    /** Removes every ring. */
    void clear() {
        for (int i = 0; i < CAPACITY; i++) {
            live[i] = false;
            colour[i] = null;
        }
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
            float a = alpha[i] * (1f - p);
            if (r < 0.5f || a <= 0.002f) continue;
            ring(batch, x[i], y[i], r, stroke[i], colour[i], a);
            ring(batch, x[i], y[i], r, stroke[i] * GLOW_WIDTH, colour[i], a * GLOW);
        }
    }

    /** One {@code ring_water} quad: radius {@code r}, stroke near {@code strokeW}, colour {@code c} at α {@code a}. */
    private void ring(Batch batch, float cx, float cy, float r, float strokeW, Color c, float a) {
        batch.setPackedColor(Color.toFloatBits(c.r, c.g, c.b, Math.min(1f, a)));
        int lod = lod(r, strokeW);
        TextureRegion reg = water[lod];
        float k = r / lodRadius[lod]; // world units per texel
        float w = reg.getRegionWidth() * k;
        float h = reg.getRegionHeight() * k;
        batch.draw(reg, cx - 0.5f * w, cy - 0.5f * h, w, h);
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

    /** Rings alive, started or waiting for their delay. */
    int live() {
        return liveCount;
    }

    /** Quads the next {@link #draw} submits (two per started, unfinished ring). */
    int quads() {
        if (liveCount == 0) return 0;
        int n = 0;
        for (int i = 0; i < CAPACITY; i++) {
            if (!live[i]) continue;
            float age = time - start[i];
            if (age >= 0f && age < life[i]) n += 2;
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

    /**
     * Fills {@code out} (length ≥ 7) with ring {@code slot}'s start time, life, final radius, starting α, wanted
     * stroke and centre x, y, or returns false when the slot is free (tests).
     */
    boolean describe(int slot, float[] out) {
        if (slot < 0 || slot >= CAPACITY || !live[slot]) return false;
        out[0] = start[slot];
        out[1] = life[slot];
        out[2] = radius[slot];
        out[3] = alpha[slot];
        out[4] = stroke[slot];
        out[5] = x[slot];
        out[6] = y[slot];
        return true;
    }

    /** Colour of the ring in {@code slot} (tests). */
    Color colourOf(int slot) {
        return colour[slot];
    }
}
