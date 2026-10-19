package org.kelvinizer.screens.shell;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack;
import com.badlogic.gdx.utils.TimeUtils;
import com.badlogic.gdx.utils.viewport.Viewport;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 菜单列表的竖向滚动和裁剪：拖动一比一跟手，松手后按惯性滑行，拉过头会像橡皮筋一样弹回，手感与地图相同。
 * 手指移动超过 {@link Ui#slop()} 之前，按下照常交给列表里的行；超过后算作拖动，已按下的行被取消。
 *
 * <p>Vertical scroll physics and clipping for menu lists (DESIGN §8.2 "scrolls with map physics"):
 * 1:1 drag, fling with exponential friction, rubber-band overscroll (half resistance) and a
 * critically damped spring back (the {@code camera} preset, ω 12). A press inside the area is
 * passed through to the content until the finger moves beyond {@link Ui#slop()}; then
 * {@link #isDragging()} turns true and the owner cancels its pressed rows.
 *
 * <p>The fling velocity is measured over time, not per event: the drag samples of the last
 * {@value #FLING_WINDOW_MS} ms (input event timestamps) give a least-squares slope, so a flick
 * throws the list equally far on 60, 120 or 240 Hz digitisers and with a mouse; a finger that
 * rested for {@value #REST_MS} ms before lifting does not fling.
 *
 * <p>{@link #offset()} is how far the content has scrolled up (0 = top). The owner draws rows at
 * {@code top() + offset() − rowTop} between {@link #beginClip} and {@link #endClip} (one scissor,
 * two flushes). No allocation after construction.
 */
public final class ScrollArea {
    private static final float FRICTION = 3.2f;       // 1/s velocity decay
    private static final float OMEGA = 12f;            // spring back
    private static final float WHEEL_STEP = 90f;
    /** Drag history used for the fling velocity (ms). */
    static final int FLING_WINDOW_MS = 80;
    /** A finger still for this long before lifting does not fling (ms). */
    static final int REST_MS = 100;
    private static final float MAX_FLING = 9000f;       // world units / s
    private static final int SAMPLES = 16;

    private float x, y, w, h;
    private float contentH;
    private float offset, velocity;
    private int pointer = -1;
    private float downY, lastY;
    private final float[] sampleY = new float[SAMPLES];
    private final long[] sampleT = new long[SAMPLES];
    private int sampleNext, sampleCount;
    private boolean dragging;
    private boolean pushed;
    private final Rectangle clip = new Rectangle(), scissor = new Rectangle();

    /** Sets the visible rect (bottom-left origin). */
    public ScrollArea setBounds(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = Math.max(1f, h);
        clampHard();
        return this;
    }

    /** Sets the total content height (rows + padding). */
    public ScrollArea setContentHeight(float height) {
        contentH = Math.max(0f, height);
        return this;
    }

    /** Jumps back to the top and stops. */
    public void reset() {
        offset = 0f;
        velocity = 0f;
        pointer = -1;
        dragging = false;
    }

    /** Left edge. */
    public float x() {
        return x;
    }

    /** Bottom edge. */
    public float y() {
        return y;
    }

    /** Width. */
    public float width() {
        return w;
    }

    /** Height. */
    public float height() {
        return h;
    }

    /** Top edge. */
    public float top() {
        return y + h;
    }

    /** Current scroll offset (content moved up by this much; may be slightly outside while overscrolling). */
    public float offset() {
        return offset;
    }

    /** Largest regular offset. */
    public float maxOffset() {
        return Math.max(0f, contentH - h);
    }

    /** True when the content is taller than the area. */
    public boolean canScroll() {
        return contentH > h + 0.5f;
    }

    /** True once the tracked finger moved beyond slop (presses inside must cancel). */
    public boolean isDragging() {
        return dragging;
    }

    /** True when (px, py) is inside the visible rect. */
    public boolean contains(float px, float py) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    /** True when a row spanning [rowBottom, rowTop] (world y) is at least partly visible. */
    public boolean isVisible(float rowBottom, float rowTop) {
        return rowTop >= y && rowBottom <= y + h;
    }

    // ------------------------------------------------------------------ input
    /** Starts tracking a finger inside the area; never consumes (rows still get the down). */
    public void touchDown(float px, float py, int ptr) {
        if (pointer >= 0 || !contains(px, py)) return;
        pointer = ptr;
        downY = lastY = py;
        velocity = 0f;
        dragging = false;
        sampleCount = 0;
        sample(py);
    }

    /** Drags; returns true when this drag scrolls (beyond slop). */
    public boolean touchDragged(float px, float py, int ptr) {
        if (ptr != pointer) return false;
        if (!dragging && Math.abs(py - downY) > Ui.slop() && canScroll()) {
            dragging = true;
            lastY = py;
        }
        if (dragging) {
            float dy = py - lastY;
            float max = maxOffset();
            if (offset < 0f || offset > max) dy *= 0.5f;     // rubber band
            offset += dy;
            lastY = py;
        }
        sample(py);
        return dragging;
    }

    /** Ends tracking; returns true when the gesture was a scroll (the owner must not fire). */
    public boolean touchUp(float px, float py, int ptr) {
        if (ptr != pointer) return false;
        pointer = -1;
        boolean was = dragging;
        dragging = false;
        if (was) velocity = flingVelocity(eventNanos());
        return was;
    }

    // ------------------------------------------------------------------ fling estimate
    private static long eventNanos() {
        long t = Gdx.input != null ? Gdx.input.getCurrentEventTime() : 0L;
        return t != 0L ? t : TimeUtils.nanoTime();
    }

    private void sample(float py) {
        sampleY[sampleNext] = py;
        sampleT[sampleNext] = eventNanos();
        sampleNext = (sampleNext + 1) % SAMPLES;
        if (sampleCount < SAMPLES) sampleCount++;
    }

    /**
     * Least-squares slope (world units / s) of the finger's y over the samples within
     * {@link #FLING_WINDOW_MS} of the newest one; 0 when the finger rested before {@code upNanos}
     * or fewer than two samples span at least 8 ms.
     */
    private float flingVelocity(long upNanos) {
        if (sampleCount < 2) return 0f;
        int newest = (sampleNext - 1 + SAMPLES) % SAMPLES;
        long tNew = sampleT[newest];
        if (upNanos - tNew > REST_MS * 1_000_000L) return 0f;
        long window = FLING_WINDOW_MS * 1_000_000L;
        double st = 0, sy = 0, stt = 0, sty = 0;
        int n = 0;
        long oldest = tNew;
        for (int k = 0; k < sampleCount; k++) {
            int i = (newest - k + SAMPLES) % SAMPLES;
            long dtn = tNew - sampleT[i];
            if (dtn > window) break;
            double t = -dtn / 1e9;
            st += t;
            sy += sampleY[i];
            stt += t * t;
            sty += t * sampleY[i];
            oldest = sampleT[i];
            n++;
        }
        if (n < 2 || tNew - oldest < 8_000_000L) return 0f;
        double den = n * stt - st * st;
        if (den <= 1e-12) return 0f;
        float v = (float) ((n * sty - st * sy) / den);
        return Math.max(-MAX_FLING, Math.min(MAX_FLING, v));
    }

    /** Cancels tracking. */
    public void cancel() {
        pointer = -1;
        dragging = false;
    }

    /** Mouse wheel (desktop); returns true when the area could scroll. */
    public boolean scrolled(float amountY) {
        if (!canScroll()) return false;
        offset += amountY * WHEEL_STEP;
        velocity = 0f;
        return true;
    }

    /** Scrolls so that the band from {@code fromTop} to {@code toTop} (measured down from the content top) is visible. */
    public void reveal(float fromTop, float toTop) {
        if (fromTop < offset) offset = fromTop;
        else if (toTop > offset + h) offset = toTop - h;
        clampHard();
    }

    // ------------------------------------------------------------------ frame
    /** Advances fling and spring back. */
    public void update(float dt) {
        if (pointer >= 0 && dragging) return;
        float max = maxOffset();
        if (offset < 0f || offset > max) {
            float target = offset < 0f ? 0f : max;
            // critically damped spring toward the bound
            float a = -OMEGA * OMEGA * (offset - target) - 2f * OMEGA * velocity;
            velocity += a * dt;
            offset += velocity * dt;
            if (Math.abs(offset - target) < 0.3f && Math.abs(velocity) < 5f) {
                offset = target;
                velocity = 0f;
            }
        } else if (velocity != 0f) {
            offset += velocity * dt;
            velocity *= (float) Math.exp(-FRICTION * dt);
            if (Math.abs(velocity) < 4f) velocity = 0f;
        }
    }

    private void clampHard() {
        float max = maxOffset();
        if (offset > max) offset = max;
        if (offset < 0f) offset = 0f;
    }

    // ------------------------------------------------------------------ drawing
    /**
     * Flushes the batch and clips drawing to the area (grown by {@code pad} on each side, so
     * ripples and shadows can spill a little). Returns false when the clip is empty; call
     * {@link #endClip(Batch)} regardless.
     */
    public boolean beginClip(Batch batch, Viewport viewport, float pad) {
        clip.set(x - pad, y, w + 2f * pad, h);
        viewport.calculateScissors(batch.getTransformMatrix(), clip, scissor);
        batch.flush();
        pushed = ScissorStack.pushScissors(scissor);
        return pushed;
    }

    /** Ends the clip started by {@link #beginClip}. */
    public void endClip(Batch batch) {
        batch.flush();
        if (pushed) ScissorStack.popScissors();
        pushed = false;
    }

    /** Draws a thin scroll indicator at the right edge while the content overflows. */
    public void drawIndicator(Draw d, float alpha) {
        drawIndicator(d, alpha, -6f);
    }

    /**
     * Draws the scroll indicator with its left edge at {@code right + dx} ({@code dx > 0} places it
     * in the margin outside the content, so it never crosses full-width rows).
     */
    public void drawIndicator(Draw d, float alpha, float dx) {
        if (!canScroll()) return;
        float frac = h / contentH;
        float barH = Math.max(40f, h * frac);
        float max = maxOffset();
        float p = max <= 0f ? 0f : Math.max(0f, Math.min(1f, offset / max));
        float by = y + h - barH - (h - barH) * p;
        d.roundRect(x + w + dx, by, 4f, barH, 2f, Theme.INK2, 0.35f * alpha);
    }
}
