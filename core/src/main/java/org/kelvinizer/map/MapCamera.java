package org.kelvinizer.map;

import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.ui.Spring;

/**
 * 地图镜头的二维物理：手指拖动时 1:1 跟随，松手后惯性滑行，越界时橡皮筋回弹，双指以中点缩放并有软上下限。
 * 镜头从不自动吸附，只有明确的操作才会让它平滑飞到目标（{@link #flyTo}）。
 *
 * <p>Free 2D camera physics of the Dive Chart (DESIGN §3.6). The camera never snaps: it moves 1:1
 * with the finger, flings with inertia, rubber-bands past its bounds, zooms about the pinch
 * midpoint with soft limits and only glides on explicit intent ({@link #flyTo}).
 *
 * <p>State is kept twice: a <em>raw</em> position the finger and the physics integrate freely, and
 * the <em>displayed</em> position ({@link #x}, {@link #y}, {@link #zoom}) where every overscroll
 * {@code d} beyond a bound is shown as {@code 0.55·d·D / (D + 0.55·d)} (D = view size). On release
 * the raw position springs back to the bound with the critically damped camera spring; a fling
 * that hits a bound turns its leftover velocity into overscroll and bounces.
 *
 * <p>Coordinates: map world units. "HUD" coordinates are the screen viewport's world units
 * (1920×1080 extended, centre (960, 540)); a HUD point h maps to the world point
 * {@code cam + (h − centre) · zoom}. No allocation after construction.
 */
final class MapCamera {
    private static final float HUD_CX = 960f, HUD_CY = 540f;
    private static final int SAMPLES = 32;

    /** Level bounds (world) and the overview zoom (the zoom-out limit of this level). */
    private float bx0 = MapStyle.BOUND_LEFT, bx1 = MapStyle.BOUND_RIGHT, by0 = -3000f, by1 = MapStyle.BOUND_TOP;
    private float zMax = MapStyle.ZOOM_MAX;
    /** Displayed camera centre and zoom (OrthographicCamera semantics: larger zoom = see more). */
    float x = MapStyle.CENTRE_X, y = 0f, zoom = 1f;
    private float rx = MapStyle.CENTRE_X, ry = 0f, rz = 1f;
    private float vx, vy, vz;
    private boolean held, pinching;
    private float viewHudW = 1920f, viewHudH = 1080f;
    private float minX, maxX, minY, maxY;
    /** A camera centre the bounds must also reach (NaN = none): see {@link #keepReachable}. */
    private float keepX = Float.NaN, keepY = Float.NaN;

    // glide ---------------------------------------------------------------------------------
    private boolean gliding;
    private float gT, gDur, gx0, gy0, gz0, gx1, gy1, gz1, gHop;
    private Interpolation gInterp = Interpolation.pow3;
    /** A dive glide (no clamping; the camera holds its final state until the next snap or glide). */
    private boolean diving;

    // display velocity / idle drift ----------------------------------------------------------
    /** Displayed velocity (world units / s), for parallax, streaks and jelly lag. */
    float dispVx, dispVy;
    private float prevX, prevY;
    private float idle;
    private float driftT;
    private boolean reduceMotion;

    // fling samples (time, raw x, raw y) -----------------------------------------------------
    private final float[] sT = new float[SAMPLES], sX = new float[SAMPLES], sY = new float[SAMPLES];
    private int sHead, sCount;

    private final Spring zoomSpring = new Spring(Spring.Preset.CAMERA, 1f);

    /** True on a level that takes no zoom (the hub): pinches only pan, the wheel and keys do nothing. */
    boolean zoomLocked;
    /** ALL SONGS: the camera only scrolls up and down (fingers move no x) and never drifts when idle. */
    boolean lockX;

    MapCamera() {
    }

    /** Sets the level's world bounds and its overview zoom (the farthest zoom-out). */
    void setLevel(float minX, float maxX, float minY, float maxY, float zoomMax) {
        bx0 = minX;
        bx1 = maxX;
        by0 = minY;
        by1 = maxY;
        zMax = Math.max(MapStyle.ZOOM_MIN + 0.1f, zoomMax);
        computeBounds(zoom);
    }

    /** The overview zoom of the level. */
    float zoomMax() {
        return zMax;
    }

    /** The zoom the camera is at, or is gliding to. */
    float targetZoom() {
        return gliding ? gz1 : zoom;
    }

    // ------------------------------------------------------------------ configuration
    /** Size of the HUD (screen) world, i.e. the visible area at zoom 1. */
    void setViewport(float hudW, float hudH) {
        viewHudW = hudW;
        viewHudH = hudH;
        computeBounds(zoom);
        if (!held && !gliding) {
            rx = clampX(rx);
            ry = clampY(ry);
            display();
        }
    }

    /**
     * Widens the bounds so the camera can rest at centre (cx, cy): a song near the level's edge
     * still sits in the middle of the area the song card leaves uncovered (Kelvin 2026-10-13).
     * Kept until {@link #clearReachable()}.
     */
    void keepReachable(float cx, float cy) {
        keepX = cx;
        keepY = cy;
        computeBounds(zoom);
    }

    /** Drops {@link #keepReachable}; a camera outside the level's own bounds springs back. */
    void clearReachable() {
        if (Float.isNaN(keepX)) return;
        keepX = keepY = Float.NaN;
        computeBounds(zoom);
    }

    /** Disables idle drift (Reduce motion). */
    void setReduceMotion(boolean value) {
        reduceMotion = value;
    }

    /** Jumps to a position and zoom (clamped), stopping all motion. */
    void snap(float cx, float cy, float z) {
        gliding = false;
        diving = false;
        held = false;
        pinching = false;
        vx = vy = vz = 0f;
        rz = MathUtils.clamp(z, MapStyle.ZOOM_MIN, zMax);
        zoom = rz;
        computeBounds(zoom);
        rx = clampX(cx);
        ry = clampY(cy);
        display();
        prevX = x;
        prevY = y;
        dispVx = dispVy = 0f;
    }

    /** World x under HUD x. */
    float worldX(float hx) {
        return renderX() + (hx - HUD_CX) * zoom;
    }

    /** World y under HUD y. */
    float worldY(float hy) {
        return renderY() + (hy - HUD_CY) * zoom;
    }

    /** HUD x of world x. */
    float hudX(float wx) {
        return HUD_CX + (wx - renderX()) / zoom;
    }

    /** HUD y of world y. */
    float hudY(float wy) {
        return HUD_CY + (wy - renderY()) / zoom;
    }

    /** Camera x used for rendering (displayed position plus idle drift). */
    float renderX() {
        return x;
    }

    /** Camera y used for rendering (displayed position plus idle drift). */
    float renderY() {
        return y + drift();
    }

    private float drift() {
        if (reduceMotion || lockX || idle < MapStyle.IDLE_DELAY) return 0f;
        float ramp = Math.min(1f, (idle - MapStyle.IDLE_DELAY) / 1.5f);
        return MapStyle.IDLE_AMP * ramp * MathUtils.sin(driftT * MathUtils.PI2 / MapStyle.IDLE_PERIOD);
    }

    /** Camera centre x that puts world x at HUD x {@code hx} at zoom {@code z}. */
    static float centreFor(float wx, float hx, float z) {
        return wx - (hx - HUD_CX) * z;
    }

    /** Camera centre y that puts world y at HUD y {@code hy} at zoom {@code z}. */
    static float centreForY(float wy, float hy, float z) {
        return wy - (hy - HUD_CY) * z;
    }

    // ------------------------------------------------------------------ state queries
    /** Speed of the displayed camera (world units / s). */
    float speed() {
        return (float) Math.sqrt(dispVx * dispVx + dispVy * dispVy);
    }

    /** True while a glide runs. */
    boolean isGliding() {
        return gliding;
    }

    /** Resets the idle timer (any touch). */
    void poke() {
        idle = 0f;
    }

    // ------------------------------------------------------------------ gestures
    /** A finger grabs the camera: stops flings and glides (the "catch"). */
    void grab() {
        held = true;
        gliding = false;
        vx = vy = vz = 0f;
        sCount = 0;
        idle = 0f;
    }

    /** Pans by a HUD-space finger delta (1:1 tracking; the rubber band adds resistance). */
    void panBy(float dxHud, float dyHud) {
        if (!lockX) rx -= dxHud * zoom;
        ry -= dyHud * zoom;
        display();
    }

    /**
     * Two-finger transform: the world point under the previous midpoint stays under the new one
     * while the zoom scales by {@code ratio} (previous distance / new distance).
     */
    void pinchBy(float lastMidX, float lastMidY, float midX, float midY, float ratio) {
        pinching = true;
        float wx = x + (lastMidX - HUD_CX) * zoom;
        float wy = y + (lastMidY - HUD_CY) * zoom;
        if (!zoomLocked) rz = MathUtils.clamp(rz * ratio, 0.2f, 6f);
        float z = softZoom(rz);
        computeBounds(z);
        float tx = wx - (midX - HUD_CX) * z;
        float ty = wy - (midY - HUD_CY) * z;
        if (!lockX) rx += tx - x;
        ry += ty - y;
        zoom = z;
        display();
    }

    /** Ends a pinch (the zoom springs back inside its range when released). */
    void endPinch() {
        pinching = false;
        zoomSpring.snap(rz);
    }

    /** Records a sample of the raw position for the fling fit (time in seconds). */
    void sample(float t) {
        sT[sHead] = t;
        sX[sHead] = rx;
        sY[sHead] = ry;
        sHead = (sHead + 1) % SAMPLES;
        if (sCount < SAMPLES) sCount++;
    }

    /**
     * Releases the camera at time {@code t}: velocity = least-squares fit over the last 80 ms of
     * samples, capped at 8,000 / s.
     */
    void release(float t, boolean fling) {
        held = false;
        if (pinching) endPinch();
        vx = vy = 0f;
        if (fling && sCount >= 2) {
            float st = 0f, sx = 0f, sy = 0f;
            int n = 0;
            for (int k = 0; k < sCount; k++) {
                int i = (sHead - 1 - k + SAMPLES) % SAMPLES;
                if (t - sT[i] > MapStyle.FLING_WINDOW) break;
                st += sT[i];
                sx += sX[i];
                sy += sY[i];
                n++;
            }
            if (n >= 2) {
                float mt = st / n, mx = sx / n, my = sy / n;
                float num1 = 0f, num2 = 0f, den = 0f;
                for (int k = 0; k < n; k++) {
                    int i = (sHead - 1 - k + SAMPLES) % SAMPLES;
                    float dt = sT[i] - mt;
                    num1 += dt * (sX[i] - mx);
                    num2 += dt * (sY[i] - my);
                    den += dt * dt;
                }
                if (den > 1e-6f) {
                    vx = lockX ? 0f : num1 / den;
                    vy = num2 / den;
                    float sp = (float) Math.sqrt(vx * vx + vy * vy);
                    if (sp > MapStyle.FLING_MAX) {
                        vx *= MapStyle.FLING_MAX / sp;
                        vy *= MapStyle.FLING_MAX / sp;
                    }
                    if (sp < MapStyle.FLING_STOP * 3f) vx = vy = 0f;
                }
            }
        }
        sCount = 0;
    }

    /** Stops every motion where it is (a catch, or before a programmatic move). */
    void stop() {
        gliding = false;
        vx = vy = 0f;
    }

    /**
     * Glides to camera centre (cx, cy) and zoom z (clamped to the bounds at that zoom) over
     * {@code dur} seconds; {@code hop} &gt; 0 raises the zoom by up to that much at the midpoint
     * along a sine arc (long jumps hop out and back in).
     */
    void flyTo(float cx, float cy, float z, float dur, Interpolation interp, float hop) {
        float tz = MathUtils.clamp(z, MapStyle.ZOOM_MIN, zMax);
        float oldMinX = minX, oldMaxX = maxX, oldMinY = minY, oldMaxY = maxY;
        computeBounds(tz);
        gx1 = clampX(cx);
        gy1 = clampY(cy);
        minX = oldMinX;
        maxX = oldMaxX;
        minY = oldMinY;
        maxY = oldMaxY;
        gz1 = tz;
        gx0 = x;
        gy0 = y + drift();
        gz0 = zoom;
        gT = 0f;
        gDur = Math.max(0.01f, dur);
        gInterp = interp;
        gHop = hop;
        gliding = true;
        diving = false;
        held = false;
        pinching = false;
        vx = vy = vz = 0f;
        idle = 0f;
    }

    /**
     * The Descent (§3.12): glides to (cx, cy) at zoom {@code z} with no bounds or zoom limits and
     * then holds there (no spring-back) until the next {@link #snap} or {@link #flyTo}.
     */
    void diveTo(float cx, float cy, float z, float dur, Interpolation interp) {
        flyTo(cx, cy, z, dur, interp, 0f);
        gx1 = cx;
        gy1 = cy;
        gz1 = z;
        diving = true;
    }

    /** Standard fly-to duration for a distance (DESIGN §3.6). */
    static float flyDuration(float dist) {
        return MathUtils.clamp(0.35f + dist / 6000f, 0.45f, 1.1f);
    }

    /** Starts a glide from an explicit camera state to the current target (arrival animations). */
    void glideFrom(float fx, float fy, float fz, float dur, Interpolation interp) {
        float tx = x, ty = y, tz = zoom;
        x = rx = fx;
        y = ry = fy;
        zoom = rz = fz;
        // the jump to the start pose is not motion: no velocity spike on the first glide frame
        prevX = x;
        prevY = y;
        dispVx = dispVy = 0f;
        flyTo(tx, ty, tz, dur, interp, 0f);
    }

    /** Zooms by {@code factor} about HUD point (hx, hy) with a short glide (wheel, keys). */
    void zoomAbout(float hx, float hy, float factor) {
        if (zoomLocked) return;
        float base = gliding ? gz1 : zoom;
        float bx = gliding ? gx1 : x, by = gliding ? gy1 : y;
        float tz = MathUtils.clamp(base * factor, MapStyle.ZOOM_MIN, zMax);
        float wx = bx + (hx - HUD_CX) * base, wy = by + (hy - HUD_CY) * base;
        flyTo(wx - (hx - HUD_CX) * tz, wy - (hy - HUD_CY) * tz, tz, 0.16f, Interpolation.pow2Out, 0f);
    }

    /**
     * A zoom-out nudge at the overview (the wheel; Kelvin 2026-10-17 19:40): the zoom gives up to
     * {@code amount} (× the overview zoom, raw) past it and springs back, as a pinch pulling on
     * past the overview does.
     */
    void bump(float amount) {
        if (zoomLocked || gliding || held || pinching) return;
        rz = Math.max(rz, zMax + 1e-3f);
        zoomSpring.setValue(rz).setTarget(zMax).setVelocity(amount * zMax * Spring.Preset.CAMERA.omega * MathUtils.E);
    }

    /** Pans by a world delta immediately (keyboard), clamped. */
    void nudge(float dx, float dy) {
        gliding = false;
        rx = clampX(rx + dx);
        ry = clampY(ry + dy);
        display();
        idle = 0f;
    }

    // ------------------------------------------------------------------ frame
    /** Integrates the physics and updates the displayed state. */
    void update(float dt) {
        if (dt <= 0f) return;
        if (diving && !gliding) {
            dispVx = dispVy = 0f;
            return;
        }
        if (!held && !gliding) {
            idle += dt;
            driftT += dt;
        }
        if (gliding) {
            gT += dt;
            float p = Math.min(1f, gT / gDur);
            float e = gInterp.apply(p);
            float hopZ = gHop * MathUtils.sin(MathUtils.PI * p);
            zoom = rz = gz0 + (gz1 - gz0) * e + hopZ;
            computeBounds(zoom);
            x = rx = gx0 + (gx1 - gx0) * e;
            y = ry = gy0 + (gy1 - gy0) * e;
            if (p >= 1f) {
                gliding = false;
                zoom = rz = gz1;
                if (!diving) computeBounds(zoom);
                x = rx = gx1;
                y = ry = gy1;
            }
        } else {
            if (!pinching) {
                float lo = MapStyle.ZOOM_MIN, hi = zMax;
                if (rz < lo || rz > hi) {
                    zoomSpring.setValue(rz).setTarget(rz < lo ? lo : hi);
                    rz = zoomSpring.update(dt);
                    if (Math.abs(rz - zoomSpring.target()) < 0.002f) rz = zoomSpring.target();
                } else {
                    zoomSpring.snap(rz);
                }
            }
            zoom = softZoom(rz);
            computeBounds(zoom);
            if (!held) {
                vx = integrate(true, dt);
                vy = integrate(false, dt);
            }
            display();
        }
        dispVx = (x - prevX) / dt;
        dispVy = (y - prevY) / dt;
        prevX = x;
        prevY = y;
    }

    /** One axis of the free camera: friction inside the bounds, the camera spring outside. */
    private float integrate(boolean ax, float dt) {
        float p = ax ? rx : ry;
        float v = ax ? vx : vy;
        float lo = ax ? minX : minY, hi = ax ? maxX : maxY;
        int steps = Math.max(1, (int) Math.ceil(dt / (1f / 120f)));
        float h = dt / steps;
        float w = Spring.Preset.CAMERA.omega, z = Spring.Preset.CAMERA.zeta;
        for (int i = 0; i < steps; i++) {
            if (p < lo || p > hi) {
                float b = p < lo ? lo : hi;
                float a = -w * w * (p - b) - 2f * z * w * v;
                v += a * h;
                p += v * h;
                if (Math.abs(p - b) < 0.05f && Math.abs(v) < 2f) {
                    p = b;
                    v = 0f;
                }
            } else {
                p += v * h;
                v *= (float) Math.exp(-MapStyle.FLING_DECAY * h);
                if (Math.abs(v) < MapStyle.FLING_STOP) v = 0f;
            }
        }
        if (ax) rx = p;
        else ry = p;
        return v;
    }

    private void display() {
        x = rubber(rx, minX, maxX, viewHudW * zoom);
        y = rubber(ry, minY, maxY, viewHudH * zoom);
    }

    private void computeBounds(float z) {
        float vw = viewHudW * z, vh = viewHudH * z;
        minX = bx0 + vw * 0.5f;
        maxX = bx1 - vw * 0.5f;
        if (minX > maxX) minX = maxX = 0.5f * (bx0 + bx1);
        maxY = by1 - vh * 0.5f;
        minY = by0 + vh * 0.5f;
        if (minY > maxY) minY = maxY = 0.5f * (minY + maxY);
        if (!Float.isNaN(keepX)) {
            minX = Math.min(minX, keepX);
            maxX = Math.max(maxX, keepX);
            minY = Math.min(minY, keepY);
            maxY = Math.max(maxY, keepY);
        }
    }

    private float clampX(float v) {
        return MathUtils.clamp(v, minX, maxX);
    }

    private float clampY(float v) {
        return MathUtils.clamp(v, minY, maxY);
    }

    private static float rubber(float raw, float lo, float hi, float d) {
        if (raw < lo) return lo - band(lo - raw, d);
        if (raw > hi) return hi + band(raw - hi, d);
        return raw;
    }

    private static float band(float over, float d) {
        float k = MapStyle.RUBBER * over;
        return k * d / (d + k);
    }

    private float softZoom(float raw) {
        if (raw < MapStyle.ZOOM_MIN) {
            float d = MapStyle.ZOOM_MIN - raw, l = MapStyle.ZOOM_MIN - MapStyle.ZOOM_SOFT_MIN;
            return MapStyle.ZOOM_MIN - d * l / (l + d);
        }
        if (raw > zMax) {
            float d = raw - zMax, l = MapStyle.ZOOM_SOFT_MAX - MapStyle.ZOOM_MAX;
            return zMax + d * l / (l + d);
        }
        return raw;
    }
}
