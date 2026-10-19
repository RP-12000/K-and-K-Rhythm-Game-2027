package org.kelvinizer.map;

import org.kelvinizer.ui.Ui;

/**
 * 地图的手势识别：按下、拖动、双指缩放、长按（450 ms）、点击和双击（300 ms 内）。
 * 不用 libGDX 的 {@code GestureDetector}，这样镜头从第一个像素起就跟手，
 * 双指缩放和平移能同时进行，滑行中按下能“接住”镜头。
 *
 * <p>Touch recogniser of the Dive Chart (DESIGN §3.6–§3.8). A hand-rolled recogniser instead of
 * libGDX's {@code GestureDetector} so the camera tracks fingers 1:1 from the first moved pixel,
 * pinch and two-finger pan run together, a touch during a fling "catches" the camera, and the
 * fling velocity is a least-squares fit over the last 80 ms ({@link MapCamera#release}).
 *
 * <p>Positions are HUD coordinates (the screen viewport's world units). States: a single finger
 * starts as PRESS; moving beyond {@link Ui#slop()} turns it into PAN; a second finger turns any
 * state into PINCH (and back to PAN when one finger lifts); holding still for 450 ms turns a
 * PRESS into LONG. Taps (release without becoming a pan, not a catch) are reported on release;
 * two taps within 300 ms and close together report a double tap. No allocation.
 */
final class MapGestures {
    /** Callbacks into the screen. */
    interface Listener {
        /** A finger went down on the map; {@code caught} = it stopped a fast camera (never selects). */
        void mapDown(float hx, float hy, boolean caught);

        /** The press turned into a pan or pinch: cancel any node press. */
        void mapPanStart();

        /** A tap (released within slop). */
        void mapTap(float hx, float hy);

        /** The second tap of a double tap (the first one was reported as a tap). */
        void mapDoubleTap(float hx, float hy);

        /** The press was held still for 450 ms. */
        void mapLongPress(float hx, float hy);

        /** The last finger lifted (or the gesture was cancelled). */
        void mapUp();

        /** The camera was dragged by {@code dist} HUD units this event (bubble wake). */
        void mapDragged(float hx, float hy, float dist);
    }

    private static final int IDLE = 0, PRESS = 1, PAN = 2, PINCH = 3, LONG = 4;

    private final MapCamera cam;
    private final Listener listener;
    private int state = IDLE;
    private boolean caught;
    /** Up to two tracked pointers. */
    private final int[] ptr = {-1, -1};
    private final float[] px = new float[2], py = new float[2];
    private float downX, downY, downTime;
    private float lastTapTime = -10f, lastTapX, lastTapY;
    private float clock;
    private long frameNanos = System.nanoTime();

    MapGestures(MapCamera cam, Listener listener) {
        this.cam = cam;
        this.listener = listener;
    }

    /** True while at least one finger is on the map. */
    boolean active() {
        return state != IDLE;
    }

    /** True while the gesture is a pan or pinch (the camera follows the fingers). */
    boolean panning() {
        return state == PAN || state == PINCH;
    }

    /** True if {@code pointer} belongs to the map gesture. */
    boolean owns(int pointer) {
        return pointer >= 0 && (ptr[0] == pointer || ptr[1] == pointer);
    }

    /** Event time: the screen clock plus the real time since the frame started. */
    private float now() {
        float since = (System.nanoTime() - frameNanos) / 1e9f;
        return clock + Math.max(0f, Math.min(0.1f, since));
    }

    /** Per frame: advances the clock and detects long presses. */
    void update(float dt) {
        clock += dt;
        frameNanos = System.nanoTime();
        if (state == PRESS && !caught && clock - downTime >= MapStyle.LONG_PRESS) {
            state = LONG;
            listener.mapLongPress(px[0], py[0]);
        }
    }

    /** Touch down; returns true (the map takes every touch that reaches it). */
    boolean touchDown(float hx, float hy, int pointer) {
        float t = now();
        if (state == IDLE) {
            ptr[0] = pointer;
            ptr[1] = -1;
            px[0] = downX = hx;
            py[0] = downY = hy;
            downTime = clock;
            caught = cam.speed() > MapStyle.CATCH_SPEED;
            cam.grab();
            cam.poke();
            cam.sample(t);
            state = PRESS;
            listener.mapDown(hx, hy, caught);
            return true;
        }
        if (ptr[1] >= 0) return true; // a third finger is ignored
        ptr[1] = pointer;
        px[1] = hx;
        py[1] = hy;
        if (state == PRESS || state == LONG) listener.mapPanStart();
        state = PINCH;
        cam.grab();
        return true;
    }

    /** Touch dragged. */
    boolean touchDragged(float hx, float hy, int pointer) {
        int i = ptr[0] == pointer ? 0 : ptr[1] == pointer ? 1 : -1;
        if (i < 0) return false;
        float t = now();
        if (state == PINCH) {
            float lmx = 0.5f * (px[0] + px[1]), lmy = 0.5f * (py[0] + py[1]);
            float ldx = px[0] - px[1], ldy = py[0] - py[1];
            float lastDist = (float) Math.sqrt(ldx * ldx + ldy * ldy);
            px[i] = hx;
            py[i] = hy;
            float mx = 0.5f * (px[0] + px[1]), my = 0.5f * (py[0] + py[1]);
            float dx = px[0] - px[1], dy = py[0] - py[1];
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float ratio = dist > 1f && lastDist > 1f ? lastDist / dist : 1f;
            cam.pinchBy(lmx, lmy, mx, my, ratio);
            cam.sample(t);
            return true;
        }
        float lx = px[0], ly = py[0];
        px[0] = hx;
        py[0] = hy;
        if (state == PRESS || state == LONG) {
            float ddx = hx - downX, ddy = hy - downY;
            if (ddx * ddx + ddy * ddy <= Ui.slop() * Ui.slop()) return true;
            state = PAN;
            listener.mapPanStart();
            // 1:1 tracking: the world point under the finger at touchDown stays under it
            lx = downX;
            ly = downY;
        }
        if (state == PAN) {
            float dx = hx - lx, dy = hy - ly;
            cam.panBy(dx, dy);
            cam.sample(t);
            listener.mapDragged(hx, hy, (float) Math.sqrt(dx * dx + dy * dy));
        }
        return true;
    }

    /** Touch up. */
    boolean touchUp(float hx, float hy, int pointer) {
        int i = ptr[0] == pointer ? 0 : ptr[1] == pointer ? 1 : -1;
        if (i < 0) return false;
        float t = now();
        if (state == PINCH) {
            // keep panning with the remaining finger
            cam.endPinch();
            int keep = 1 - i;
            ptr[0] = ptr[keep];
            px[0] = px[keep];
            py[0] = py[keep];
            ptr[1] = -1;
            state = PAN;
            cam.grab();
            cam.sample(t);
            return true;
        }
        px[0] = hx;
        py[0] = hy;
        int s = state;
        state = IDLE;
        ptr[0] = ptr[1] = -1;
        if (s == PAN) {
            cam.sample(t);
            cam.release(t, true);
        } else {
            cam.release(t, false);
            if ((s == PRESS || s == LONG) && !caught) {
                float ddx = hx - lastTapX, ddy = hy - lastTapY;
                float near = 3f * Math.max(Ui.slop(), 24f);
                if (s == PRESS && clock - lastTapTime <= MapStyle.DOUBLE_TAP && ddx * ddx + ddy * ddy <= near * near) {
                    lastTapTime = -10f;
                    listener.mapDoubleTap(hx, hy);
                } else {
                    if (s == PRESS) {
                        lastTapTime = clock;
                        lastTapX = hx;
                        lastTapY = hy;
                    }
                    listener.mapTap(hx, hy);
                }
            }
        }
        listener.mapUp();
        return true;
    }

    /** Cancels the gesture (input locked, screen hidden). */
    void cancel() {
        if (state == IDLE) return;
        state = IDLE;
        ptr[0] = ptr[1] = -1;
        cam.release(now(), false);
        listener.mapUp();
    }
}
