package org.kelvinizer.fx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.RandomXS128;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Theme;

/**
 * 水花：DROP 和长条头打中、长条按到最后放开时，圆形的闪光、圆波纹和溅起的水珠；按住长条时 base 上闪动的光、往外喷的水珠和一圈圈波纹。
 * 样子由 DROP 或长条头的判定决定（共鸣或回响，{@link SplashForm}），长条一直保持头的样子：共鸣溅水滴，回响只有波纹，单点的共鸣另有三个
 * 淡蓝圈、回响是三个紫圈。
 *
 * <p>The round splash of DROP and lane STREAM hits (DESIGN §5.9; Kelvin 2026-10-26 13:15 「水花，但是圆形的，不要椭圆。
 * 并且如果是resonance打中长条就一直以resonance的形式出现后面的打击特效」), drawn by {@link HitFx} in its additive block:
 * <ul>
 *   <li><b>Splash</b> ({@link #splash}): a round flash ({@value #FLASH_SEC} s: a white gaussian dot and one twice as
 *       wide in the form colour, growing as they fade), round ripples ({@link WaterRings}, per form and kind:
 *       {@link SplashForm#DROP_RING_RADIUS}, {@link SplashForm#HIT_RING_RADIUS}) and, in RESONANCE only
 *       ({@link SplashForm#DROPLETS}), drops thrown
 *       away from the line (up from the ground line, down from the sky line) that gravity pulls back. A drop ends when
 *       it is back at the height it was thrown from and fades over the last quarter of its flight; it is a core dot
 *       (white mixed into the form colour), a halo in the form colour and three trail copies 10, 20 and 30 ms
 *       behind.</li>
 *   <li><b>Held stream</b> ({@link #hold}), from a STREAM's head to its release: a flickering light at the note's
 *       base, a fountain of drops in RESONANCE (births evenly spaced, each jittered within its slot) and a ripple every
 *       {@link SplashForm#HELD_RING_BEATS} beats at the current tempo ({@link #setBeatLength}). The light follows the
 *       base ({@link #moveHold}); each drop and ripple
 *       starts where the base is at its birth and then moves on its own (13:25
 *       「如果hold击打后base发生了位移，则大细粒子效果始终生成在note的base」).</li>
 *   <li>A completed STREAM ends with the splash at {@value #RELEASE_BIG}× with its own, more drops
 *       ({@link #endHold}); an early release only stops the stream.</li>
 * </ul>
 * Low quality ({@link #setLow}) halves the drops and the fountain rate and leaves out the second ripple and the
 * trails. Through a 3D view ({@link #setView}) the ripples are shells and the flashes, lights and drops balls, drawn
 * where their points show at the size they show at; each drop also flies in depth, at most as fast as sideways (its
 * own random number, so the flat picture stays as it was), and a ball (a flash, a held light, a drop) gets a white
 * glint towards the light ({@link HitFx#LIGHT_X}; 17:52, the design thread's 3D page v17). The numbers are the
 * approved mock-up's
 * ({@code hit_mock4.py}, 1920×1080 px, the same in world units); the ones that differ between RESONANCE and ECHO are
 * in {@link SplashForm}.
 *
 * <p>State is fixed arrays: {@value #DROPS} drops and {@value #FLASHES} flashes in ring buffers (a full one
 * overwrites its oldest entry), {@value #HOLDS} held streams. Spawning, updating and drawing allocate nothing. Times
 * are effect seconds, advanced by {@link #update} with the game delta (frozen while paused). The spawning and timing
 * logic needs no GL, so it can be tested on the plain JVM; {@link #setRegions} must run before {@link #draw}.
 */
final class Splash {
    /** Drop slots. */
    static final int DROPS = 1024;
    /** Flash slots. */
    static final int FLASHES = 64;
    /** Held stream slots (one per {@link HitFx} hold slot). */
    static final int HOLDS = 16;

    /**
     * What a splash belongs to: a DROP hit (its own ripples, {@link SplashForm#DROP_RING_RADIUS}), a STREAM's head,
     * or a completed STREAM's release (which also throws its own, more drops).
     */
    static final int DROP = 0, HEAD = 1, RELEASE = 2;

    /** Life of the round flash (s). */
    static final float FLASH_SEC = 0.20f;
    /** Quad side of the flash's white dot at size 1 (it grows from 0.6 to 1.15 of it). */
    static final float FLASH_WHITE = 150f;
    /** Quad side of the flash's colour dot at size 1. */
    static final float FLASH_COLOUR = 300f;
    /** α of the flash's colour dot at its start (times the form's brightness). */
    static final float FLASH_COLOUR_ALPHA = 0.55f;
    /** Life of a splash ripple (s). */
    static final float RING_SEC = 0.52f;
    /** Stroke of a splash ripple. */
    static final float RING_STROKE = 6f;
    /** Size of the splash at a completed release (positions and sizes). */
    static final float RELEASE_BIG = 1.2f;
    /** Gravity on the drops (world / s²). */
    static final float GRAVITY = 3600f;
    /** Throw angle range of a splash drop from the line (degrees). */
    static final float DROP_ANGLE_MIN = 20f, DROP_ANGLE_MAX = 160f;
    /** Start offset range of a splash drop along the line (±). */
    static final float DROP_SPREAD = 26f;
    /** Throw angle range of a fountain drop from the line (degrees). */
    static final float FOUNTAIN_ANGLE_MIN = 65f, FOUNTAIN_ANGLE_MAX = 115f;
    /** Start offset range of a fountain drop along the line (±). */
    static final float FOUNTAIN_SPREAD = 30f;
    /** α of a fountain drop (times the form's brightness). */
    static final float FOUNTAIN_ALPHA = 0.95f;
    /** Quad side of a drop's core dot, in drop radii. */
    static final float CORE = 2.2f;
    /** Quad side of a drop's halo, in drop radii. */
    static final float HALO = 6f;
    /** α of a drop's halo relative to its core. */
    static final float HALO_ALPHA = 0.35f;
    /** Share of a drop's flight over which it fades out, at its end. */
    static final float FADE_TAIL = 0.25f;
    /** Time between a drop and each of its trail copies (s). */
    static final float TRAIL_STEP = 0.010f;
    private static final float[] TRAIL_ALPHA = {0.55f, 0.30f, 0.15f};
    private static final float[] TRAIL_SIZE = {0.85f, 0.70f, 0.55f};
    /** Quad side of the held light's white dot at size 1. */
    static final float HELD_WHITE = 120f;
    /** Quad side of the held light's colour dot at size 1. */
    static final float HELD_COLOUR = 230f;
    /** α of the held light's white and colour dots (times the brightness, white share and flicker). */
    static final float HELD_WHITE_ALPHA = 0.80f, HELD_COLOUR_ALPHA = 0.45f;
    /** α of a held ripple at its start (times the form's brightness). */
    static final float HELD_RING_ALPHA = 0.8f;

    /** A drop's core colour per form: white mixed into the form colour ({@link SplashForm#MIX}). */
    private static final Color[] CORE_COLOUR = {
        new Color(SplashForm.COLOUR[0]).lerp(Theme.WHITE, SplashForm.MIX[0]),
        new Color(SplashForm.COLOUR[1]).lerp(Theme.WHITE, SplashForm.MIX[1])};

    private final RandomXS128 rng = new RandomXS128(0x5A1A5L);
    /** The drops' depth speeds, apart from {@link #rng} so the flat picture keeps its numbers. */
    private final RandomXS128 depthRng = new RandomXS128(0xD3E7L);
    private HitFx.Projection view;
    private final float[] pt = new float[3];
    /** The light as the camera sees it this frame (3D). */
    private final float[] light = new float[3];
    /** A ball's glint in 3D: its offset towards the light and its size (in the ball's size), its α (in the ball's). */
    static final float GLINT_OFFSET = 0.22f, GLINT_SIZE = 0.3f, GLINT_ALPHA = 0.8f;
    private final WaterRings rings = new WaterRings();
    private TextureRegion dot;
    private boolean low;
    private float time;
    /** Seconds per beat at the song's current tempo; 0 when unknown (held ripples use the fixed periods). */
    private float beatLength;

    // drops ------------------------------------------------------------------------------------------
    private final boolean[] dLive = new boolean[DROPS];
    private final byte[] dForm = new byte[DROPS];
    /** Start point (offset included), velocity (dVy away from the line), gravity, direction (+1 up, −1 down). */
    private final float[] dX = new float[DROPS], dY = new float[DROPS];
    private final float[] dVx = new float[DROPS], dVy = new float[DROPS], dG = new float[DROPS];
    /** Speed in depth, toward the camera (used only through a 3D view). */
    private final float[] dVz = new float[DROPS];
    private final float[] dDir = new float[DROPS];
    private final float[] dBirth = new float[DROPS], dFlight = new float[DROPS];
    private final float[] dR = new float[DROPS], dAlpha = new float[DROPS];
    private int dNext, dCount;

    // flashes ----------------------------------------------------------------------------------------
    private final boolean[] fLive = new boolean[FLASHES];
    private final byte[] fForm = new byte[FLASHES];
    private final float[] fX = new float[FLASHES], fY = new float[FLASHES];
    private final float[] fStart = new float[FLASHES], fBig = new float[FLASHES];
    private int fNext, fCount;

    // held streams -----------------------------------------------------------------------------------
    private final boolean[] hActive = new boolean[HOLDS];
    private final byte[] hForm = new byte[HOLDS];
    private final boolean[] hSky = new boolean[HOLDS];
    private final float[] hX = new float[HOLDS], hY = new float[HOLDS], hScale = new float[HOLDS];
    /** Head time, start of the fountain's current slot, its birth in that slot, the next ripple's time. */
    private final float[] hStart = new float[HOLDS], hSlot = new float[HOLDS], hBirth = new float[HOLDS];
    private final float[] hRing = new float[HOLDS];

    /** Binds the {@code gauss_dot} and {@code ring_water} regions of the shared atlas; GL thread, load time. */
    void setRegions(Draw draw) {
        dot = draw.gaussDot();
        rings.setRegions(draw);
    }

    /** Binds the regions directly (tests draw into a recording batch without GL). */
    void setRegions(TextureRegion gaussDot, TextureRegion[] ringWater) {
        dot = gaussDot;
        rings.setRegions(ringWater);
    }

    /** Draws through the 3D view {@code p} (null: flat). */
    void setView(HitFx.Projection p) {
        view = p;
        rings.setView(p);
    }

    /** Low quality: half the drops and fountain rate, no second ripple, no trails. */
    void setLow(boolean low) {
        this.low = low;
    }

    // ------------------------------------------------------------------ spawning

    /**
     * The splash at (x, y) in {@code form}: its flash, drops ({@link SplashForm#HIT_DROPS}, or
     * {@link SplashForm#RELEASE_DROPS} at a {@link #RELEASE}) and ripples (none on a RESONANCE {@link #DROP}).
     *
     * @param sky  true on the sky line (the drops fly down)
     * @param big  size of the splash: flash, ripples, drop positions and drop sizes (1 = a DROP hit)
     * @param kind {@link #DROP}, {@link #HEAD} or {@link #RELEASE}
     */
    void splash(float x, float y, int form, boolean sky, float big, int kind) {
        int i = fNext;
        fNext = (fNext + 1) % FLASHES;
        if (!fLive[i]) fCount++;
        fLive[i] = true;
        fForm[i] = (byte) form;
        fX[i] = x;
        fY[i] = y;
        fStart[i] = time;
        fBig[i] = big;

        float ka = SplashForm.KA[form], s = big * SplashForm.SZ[form];
        Color c = SplashForm.COLOUR[form];
        float now = rings.time();
        float[] delay = kind == DROP ? SplashForm.DROP_RING_DELAY[form] : SplashForm.HIT_RING_DELAY[form];
        float[] radius = kind == DROP ? SplashForm.DROP_RING_RADIUS[form] : SplashForm.HIT_RING_RADIUS[form];
        // Low quality drops a RESONANCE head's extra ripple; a DROP's three circles always stay
        int ringN = low && form == SplashForm.RESONANCE && kind != DROP ? Math.min(1, delay.length) : delay.length;
        for (int k = 0; k < ringN; k++) {
            rings.spawn(x, y, now, delay[k], RING_SEC, radius[k] * s, ka, RING_STROKE, c);
        }

        if (!SplashForm.DROPLETS[form]) return;
        boolean release = kind == RELEASE;
        int n = release ? SplashForm.RELEASE_DROPS[form] : SplashForm.HIT_DROPS[form];
        if (low) n = (n + 1) / 2;
        float[] v = release ? SplashForm.RELEASE_SPEED[form] : SplashForm.HIT_SPEED[form];
        float[] r = SplashForm.DROP_RADIUS[form];
        for (int k = 0; k < n; k++) {
            throwDrop(x, y, sky, big, time, form, ka, DROP_ANGLE_MIN, DROP_ANGLE_MAX, DROP_SPREAD, v, r);
        }
    }

    /**
     * Starts the held stream of hold slot {@code slot} at its base (x, y), in the form of the STREAM's head. The
     * first fountain drop is born within the first slot, the first ripple one period after the head.
     *
     * @param scale size of the light, drops and ripples (1 in play)
     */
    void hold(int slot, float x, float y, int form, boolean sky, float scale) {
        hActive[slot] = true;
        hForm[slot] = (byte) form;
        hSky[slot] = sky;
        hX[slot] = x;
        hY[slot] = y;
        hScale[slot] = scale;
        hStart[slot] = time;
        hSlot[slot] = time;
        hBirth[slot] = time + rng.nextFloat() / fountainRate(form);
        hRing[slot] = time + ringPeriod(form);
    }

    /** Sets the seconds per beat at the song's current tempo (0, NaN or less: unknown). */
    void setBeatLength(float seconds) {
        beatLength = seconds > 0f && seconds < 60f ? seconds : 0f;
    }

    /** Seconds between two ripples of a held stream of {@code form} now. */
    private float ringPeriod(int form) {
        if (!(beatLength > 0f)) return SplashForm.HELD_RING_PERIOD[form];
        return Math.max(beatLength * SplashForm.HELD_RING_BEATS[form], SplashForm.HELD_RING_MIN_PERIOD);
    }

    /** Moves the base of a running held stream (its light; later drops and ripples start there). */
    void moveHold(int slot, float x, float y) {
        if (!hActive[slot]) return;
        hX[slot] = x;
        hY[slot] = y;
    }

    /**
     * Ends a held stream: the fountain and ripples stop, live drops and ripples finish. A {@code completed} STREAM
     * adds the release splash at its base ({@value #RELEASE_BIG}× and the release drops).
     */
    void endHold(int slot, boolean completed) {
        if (!hActive[slot]) return;
        hActive[slot] = false;
        if (completed) splash(hX[slot], hY[slot], hForm[slot], hSky[slot], RELEASE_BIG * hScale[slot], RELEASE);
    }

    /** Fountain drops per second in {@code form} at the current quality. */
    private float fountainRate(int form) {
        return SplashForm.FOUNTAIN_RATE[form] * (low ? 0.5f : 1f);
    }

    /**
     * One drop thrown from (x, y) at {@code birth}: a random angle from the line, speed, radius and offset along the
     * line within the given ranges, positions and size times {@code big}.
     */
    private void throwDrop(float x, float y, boolean sky, float big, float birth, int form, float alpha,
                           float angMin, float angMax, float spread, float[] speed, float[] radius) {
        float ang = MathUtils.degreesToRadians * MathUtils.lerp(angMin, angMax, rng.nextFloat());
        float v = MathUtils.lerp(speed[0], speed[1], rng.nextFloat()) * big;
        float r = MathUtils.lerp(radius[0], radius[1], rng.nextFloat()) * big;
        float off = (2f * rng.nextFloat() - 1f) * spread * big;
        int i = dNext;
        dNext = (dNext + 1) % DROPS;
        if (!dLive[i]) dCount++;
        dLive[i] = true;
        dForm[i] = (byte) form;
        dX[i] = x + off;
        dY[i] = y;
        dVx[i] = v * MathUtils.cos(ang);
        dVy[i] = v * MathUtils.sin(ang);
        dVz[i] = (2f * depthRng.nextFloat() - 1f) * Math.abs(dVx[i]);
        dG[i] = GRAVITY * big;
        dDir[i] = sky ? -1f : 1f;
        dBirth[i] = birth;
        dFlight[i] = 2f * dVy[i] / dG[i];
        dR[i] = r;
        dAlpha[i] = alpha;
    }

    // ------------------------------------------------------------------ frame

    /**
     * Advances the effect clock (0 while paused), lets the held streams throw the drops and ripples due by now
     * (each at its own birth time, from the base's current position) and retires finished drops and flashes.
     */
    void update(float delta) {
        if (delta <= 0f) return;
        time += delta;
        rings.update(delta);
        for (int h = 0; h < HOLDS; h++) {
            if (hActive[h]) emit(h);
        }
        if (dCount > 0) {
            for (int i = 0; i < DROPS; i++) {
                if (dLive[i] && time - dBirth[i] >= dFlight[i]) {
                    dLive[i] = false;
                    dCount--;
                }
            }
        }
        if (fCount > 0) {
            for (int i = 0; i < FLASHES; i++) {
                if (fLive[i] && time - fStart[i] >= FLASH_SEC) {
                    fLive[i] = false;
                    fCount--;
                }
            }
        }
    }

    /** The fountain drops and held ripples of held stream {@code h} due by now. */
    private void emit(int h) {
        int form = hForm[h];
        float s = hScale[h];
        float step = 1f / fountainRate(form);
        while (SplashForm.DROPLETS[form] && hBirth[h] <= time) {
            throwDrop(hX[h], hY[h], hSky[h], s, hBirth[h], form, FOUNTAIN_ALPHA * SplashForm.KA[form],
                FOUNTAIN_ANGLE_MIN, FOUNTAIN_ANGLE_MAX, FOUNTAIN_SPREAD, SplashForm.FOUNTAIN_SPEED[form],
                SplashForm.FOUNTAIN_RADIUS[form]);
            hSlot[h] += step;
            hBirth[h] = hSlot[h] + rng.nextFloat() * step;
        }
        float period = ringPeriod(form);
        while (hRing[h] <= time) {
            rings.spawn(hX[h], hY[h], rings.time(), hRing[h] - time, SplashForm.HELD_RING_LIFE[form],
                SplashForm.HELD_RING_RADIUS[form] * s, HELD_RING_ALPHA * SplashForm.KA[form],
                SplashForm.HELD_RING_STROKE[form], SplashForm.COLOUR[form]);
            hRing[h] += period;
        }
    }

    /** Removes everything and stops every held stream. The clock keeps running. */
    void clear() {
        for (int i = 0; i < DROPS; i++) dLive[i] = false;
        for (int i = 0; i < FLASHES; i++) fLive[i] = false;
        for (int i = 0; i < HOLDS; i++) hActive[i] = false;
        dCount = 0;
        fCount = 0;
        rings.clear();
    }

    /**
     * Draws everything into {@code batch} (the caller has set additive blending): ripples, flashes, held lights,
     * drops. Leaves the batch colour changed; the caller restores it.
     */
    void draw(Batch batch) {
        rings.draw(batch);
        if (dot == null) return;
        if (view != null) view.direction(HitFx.LIGHT_X, HitFx.LIGHT_Y, HitFx.LIGHT_Z, light);
        for (int i = 0; fCount > 0 && i < FLASHES; i++) {
            if (!fLive[i]) continue;
            float age = time - fStart[i];
            if (age < 0f || age >= FLASH_SEC) continue;
            int f = fForm[i];
            float q = 1f - age / FLASH_SEC;
            float s = (0.6f + 0.55f * (1f - q * q)) * fBig[i] * SplashForm.SZ[f];
            float ka = SplashForm.KA[f];
            float wa = ka * SplashForm.WHITE[f] * q * (float) Math.sqrt(q);
            dot(batch, fX[i], fY[i], 0f, FLASH_WHITE * s, Theme.WHITE, wa);
            dot(batch, fX[i], fY[i], 0f, FLASH_COLOUR * s, SplashForm.COLOUR[f], FLASH_COLOUR_ALPHA * ka * q);
            glint(batch, fX[i], fY[i], 0f, FLASH_COLOUR * s, FLASH_COLOUR_ALPHA * ka * q);
        }
        for (int h = 0; h < HOLDS; h++) {
            if (!hActive[h]) continue;
            int f = hForm[h];
            float fl = flicker(time - hStart[h]);
            float s = SplashForm.SZ[f] * hScale[h], ka = SplashForm.KA[f];
            dot(batch, hX[h], hY[h], 0f, HELD_WHITE * s, Theme.WHITE, HELD_WHITE_ALPHA * ka * SplashForm.WHITE[f] * fl);
            dot(batch, hX[h], hY[h], 0f, HELD_COLOUR * s, SplashForm.COLOUR[f], HELD_COLOUR_ALPHA * ka * fl);
            glint(batch, hX[h], hY[h], 0f, HELD_COLOUR * s, HELD_COLOUR_ALPHA * ka * fl);
        }
        for (int i = 0; dCount > 0 && i < DROPS; i++) {
            if (!dLive[i]) continue;
            float age = time - dBirth[i];
            float flight = dFlight[i];
            if (age < 0f || age >= flight) continue;
            int f = dForm[i];
            float fade = Math.min(1f, (flight - age) / (FADE_TAIL * flight)) * dAlpha[i];
            float r = dR[i];
            Color core = CORE_COLOUR[f];
            float x = dropX(i, age), y = dropY(i, age), z = dVz[i] * age;
            dot(batch, x, y, z, CORE * r, core, fade);
            dot(batch, x, y, z, HALO * r, SplashForm.COLOUR[f], HALO_ALPHA * fade);
            glint(batch, x, y, z, CORE * r, fade);
            if (low) continue;
            for (int k = 0; k < TRAIL_ALPHA.length; k++) {
                float a = age - TRAIL_STEP * (k + 1);
                if (a < 0f) break;
                dot(batch, dropX(i, a), dropY(i, a), dVz[i] * a, CORE * r * TRAIL_SIZE[k], core, fade * TRAIL_ALPHA[k]);
            }
        }
    }

    private float dropX(int i, float age) {
        return dX[i] + dVx[i] * age;
    }

    private float dropY(int i, float age) {
        return dY[i] + dDir[i] * (dVy[i] * age - 0.5f * dG[i] * age * age);
    }

    /**
     * One {@code gauss_dot} quad of side {@code size} centred at (cx, cy), colour {@code c} at α {@code a}; through a
     * 3D view where (cx, cy) shows {@code dz} nearer than the keys' middle, at the size it shows at.
     */
    private void dot(Batch batch, float cx, float cy, float dz, float size, Color c, float a) {
        if (a <= 0.003f || size <= 0f) return;
        if (view != null) {
            if (!view.project(cx, cy, dz, pt)) return;
            cx = pt[0];
            cy = pt[1];
            size *= pt[2];
        }
        batch.setPackedColor(Color.toFloatBits(c.r, c.g, c.b, Math.min(1f, a)));
        batch.draw(dot, cx - 0.5f * size, cy - 0.5f * size, size, size);
    }

    /**
     * In 3D a ball's glint (the design thread's 3D page v17: shading and a highlight on the small balls too): a white
     * {@code gauss_dot} towards the light on the ball of side {@code size} at (cx, cy), {@code dz} in depth, α
     * {@link #GLINT_ALPHA} of the ball's {@code a}; flat, nothing.
     */
    private void glint(Batch batch, float cx, float cy, float dz, float size, float a) {
        if (view == null || a <= 0.003f || size <= 0f || !view.project(cx, cy, dz, pt)) return;
        float s = size * pt[2], g = GLINT_SIZE * s;
        float gx = pt[0] + GLINT_OFFSET * s * light[0], gy = pt[1] + GLINT_OFFSET * s * light[1];
        batch.setPackedColor(Color.toFloatBits(1f, 1f, 1f, Math.min(1f, GLINT_ALPHA * a)));
        batch.draw(dot, gx - 0.5f * g, gy - 0.5f * g, g, g);
    }

    /** The held light's flicker at {@code t} s after the head: 0.88 ± 0.12, a 12 Hz beat under a 3.1 Hz one. */
    static float flicker(float t) {
        return 0.88f + 0.12f * MathUtils.sin(MathUtils.PI2 * 12f * t) * MathUtils.sin(MathUtils.PI2 * 3.1f * t + 1f);
    }

    // ------------------------------------------------------------------ diagnostics

    /** Drops in flight. */
    int liveDrops() {
        return dCount;
    }

    /** Flashes not yet over. */
    int liveFlashes() {
        return fCount;
    }

    /** Ripples alive, started or waiting for their delay. */
    int liveRings() {
        return rings.live();
    }

    /** True while held stream {@code slot} runs. */
    boolean holding(int slot) {
        return hActive[slot];
    }

    /** Effect clock (s). */
    float time() {
        return time;
    }

    /** The ripples, for tests. */
    WaterRings rings() {
        return rings;
    }

    /**
     * Quads the next {@link #draw} submits at most: two per ripple, flash and held light, two per drop plus its
     * trail copies at High quality.
     */
    int quads() {
        int n = rings.quads();
        for (int i = 0; fCount > 0 && i < FLASHES; i++) {
            if (fLive[i] && time - fStart[i] < FLASH_SEC) n += 2;
        }
        for (int h = 0; h < HOLDS; h++) {
            if (hActive[h]) n += 2;
        }
        for (int i = 0; dCount > 0 && i < DROPS; i++) {
            if (!dLive[i]) continue;
            float age = time - dBirth[i];
            if (age < 0f || age >= dFlight[i]) continue;
            n += 2;
            if (!low) n += Math.min(TRAIL_ALPHA.length, (int) (age / TRAIL_STEP));
        }
        return n;
    }

    /**
     * Fills {@code out} (length ≥ 9) with drop {@code slot}'s birth, flight, radius, α, form, start x, start y,
     * direction and launch speed away from the line, or returns false when the slot is free (tests).
     */
    boolean describeDrop(int slot, float[] out) {
        if (slot < 0 || slot >= DROPS || !dLive[slot]) return false;
        out[0] = dBirth[slot];
        out[1] = dFlight[slot];
        out[2] = dR[slot];
        out[3] = dAlpha[slot];
        out[4] = dForm[slot];
        out[5] = dX[slot];
        out[6] = dY[slot];
        out[7] = dDir[slot];
        out[8] = dVy[slot];
        return true;
    }
}
