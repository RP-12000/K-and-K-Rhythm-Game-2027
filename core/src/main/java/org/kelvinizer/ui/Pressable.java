package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;

/**
 * 所有可按控件的基类：按下时缩小、变亮、从触点泛起波纹并轻震，在控件内（含容差）抬起时才生效，拖出范围则取消。
 * 还处理桌面悬停、键盘焦点框和禁用时点击的摇头动画；触摸区域至少有最小触摸目标那么大。
 *
 * <p>Base of every pressable control: the interaction primitives of DESIGN §1.5.
 *
 * <ul>
 *   <li><b>Down:</b> scale → 0.94 (snappy spring), brightness +12 %, ripple from the touch point,
 *       light haptic.</li>
 *   <li><b>Release inside</b> (within {@link Ui#slop()} of the hit rect): springs to 1.0 with a
 *       bouncy overshoot (≈ 1.04) and the action fires ({@link #setOnTap} callback and the
 *       {@link #consumeTap()} flag).</li>
 *   <li><b>Drag out beyond slop:</b> the press cancels and springs back with a small wobble.</li>
 *   <li><b>Hover</b> (desktop, {@link #mouseMoved}): lift 2 units, brightness +8 %.</li>
 *   <li><b>Keyboard focus:</b> 2 unit {@code ink1} ring, α .5 ↔ 1 at 1 Hz.</li>
 *   <li><b>Disabled:</b> α .40; a tap plays the "no" shake (±8, 3 cycles, 240 ms).</li>
 * </ul>
 *
 * <p>Coordinates are world units; {@code (x, y)} is the bottom-left of the visual rect. The hit
 * rect is centred on it and at least {@link Ui#touchTarget()} in both dimensions. Input methods
 * return true when the event was consumed. Subclasses draw in {@link #draw(Batch, float)} using
 * {@link #visualScale()}, {@link #shakeX()}, {@link #liftY()}, {@link #brightness()} and
 * {@link #alphaMul()}. No allocation after construction.
 */
public abstract class Pressable {
    /** Scale while held. */
    public static final float PRESS_SCALE = 0.94f;
    private static final float RELEASE_KICK = 2.0f;
    private static final float SHAKE_DUR = 0.24f;

    /** Shared drawing service. */
    protected final Draw draw;
    /** Visual rect (world units). */
    protected float x, y, w, h;
    /** State flags. */
    protected boolean enabled = true, visible = true, focused;
    /** Pointer currently pressing, or −1. */
    protected int pointer = -1;
    /** Press-scale spring (snappy while pressing, bouncy on release). */
    protected final Spring scale = new Spring(Spring.Preset.SNAPPY, 1f);
    /** Press ripple. */
    protected final Ripple ripple = new Ripple();
    /** 0..1 press brightness ramp (micro) and hover ramp. */
    protected float press, hover;
    /** Seconds since construction (focus pulse, idle effects). */
    protected float time;

    private float minHitW = -1f, minHitH = -1f;
    private float shakeT = SHAKE_DUR;
    private boolean hovered, tapped, haptic = true;
    private Runnable onTap;

    /** Creates a control drawing with the shared {@link Draw}. */
    protected Pressable(Draw draw) {
        this.draw = draw;
    }

    // ------------------------------------------------------------------ geometry
    /** Sets the visual rect (bottom-left origin). */
    public Pressable setBounds(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        return this;
    }

    /** Moves the visual rect so its centre is (cx, cy). */
    public Pressable setCenter(float cx, float cy) {
        x = cx - w * 0.5f;
        y = cy - h * 0.5f;
        return this;
    }

    /** Sets the size, keeping the centre. */
    public Pressable setSize(float w, float h) {
        float cx = centerX(), cy = centerY();
        this.w = w;
        this.h = h;
        return setCenter(cx, cy);
    }

    /**
     * Overrides the minimum hit size (default {@link Ui#touchTarget()} in both dimensions); pass
     * a negative value to restore the default.
     */
    public Pressable setMinHit(float minW, float minH) {
        minHitW = minW;
        minHitH = minH;
        return this;
    }

    /** Left edge of the visual rect. */
    public float x() {
        return x;
    }

    /** Bottom edge of the visual rect. */
    public float y() {
        return y;
    }

    /** Visual width. */
    public float width() {
        return w;
    }

    /** Visual height. */
    public float height() {
        return h;
    }

    /** Centre x. */
    public float centerX() {
        return x + w * 0.5f;
    }

    /** Centre y. */
    public float centerY() {
        return y + h * 0.5f;
    }

    // ------------------------------------------------------------------ state
    /** Sets the tap callback (created once; never per frame). */
    public Pressable setOnTap(Runnable r) {
        onTap = r;
        return this;
    }

    /** Returns true once after each fired tap (polling alternative to the callback). */
    public boolean consumeTap() {
        boolean t = tapped;
        tapped = false;
        return t;
    }

    /** Enables or disables the control (disabled: α .40, taps shake). */
    public Pressable setEnabled(boolean on) {
        enabled = on;
        if (!on) cancel();
        return this;
    }

    /** True when enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Shows or hides the control (hidden controls ignore input and draw nothing). */
    public Pressable setVisible(boolean on) {
        visible = on;
        if (!on) cancel();
        return this;
    }

    /** True when visible. */
    public boolean isVisible() {
        return visible;
    }

    /** Sets keyboard focus (draws the focus ring). */
    public Pressable setFocused(boolean on) {
        focused = on;
        return this;
    }

    /** True when keyboard-focused. */
    public boolean isFocused() {
        return focused;
    }

    /** Enables the light haptic on press (default on; still gated by {@link Haptics}). */
    public Pressable setHaptic(boolean on) {
        haptic = on;
        return this;
    }

    /** True while a pointer holds the control. */
    public boolean isPressed() {
        return pointer >= 0;
    }

    /** True while the mouse hovers the control. */
    public boolean isHovered() {
        return hovered;
    }

    /** Fires the action programmatically (keyboard ENTER on the focused control). */
    public void fire() {
        if (!visible) return;
        if (!enabled) {
            shakeT = 0f;
            return;
        }
        tapped = true;
        onFire();
        if (onTap != null) onTap.run();
    }

    /** Cancels a press without firing (e.g. when a transition locks input). */
    public void cancel() {
        if (pointer >= 0) {
            pointer = -1;
            releaseSpring(false);
            onCancel();
        }
    }

    /** Plays the disabled "no" shake. */
    public void shake() {
        shakeT = 0f;
    }

    // ------------------------------------------------------------------ input
    /** True when (px, py) is inside the hit rect, grown by {@code grow} on every side. */
    public boolean hit(float px, float py, float grow) {
        float hw = Math.max(w, minHitW >= 0f ? minHitW : Ui.touchTarget()) * 0.5f + grow;
        float hh = Math.max(h, minHitH >= 0f ? minHitH : Ui.touchTarget()) * 0.5f + grow;
        return Math.abs(px - centerX()) <= hw && Math.abs(py - centerY()) <= hh;
    }

    /** True when (px, py) is inside the hit rect. */
    public boolean hit(float px, float py) {
        return hit(px, py, 0f);
    }

    /** Touch down in world coordinates; returns true when consumed. */
    public boolean touchDown(float px, float py, int ptr) {
        if (!visible || pointer >= 0 || !hit(px, py)) return false;
        if (!enabled) {
            shakeT = 0f;
            return true;
        }
        pointer = ptr;
        scale.setPreset(Spring.Preset.SNAPPY).setTarget(PRESS_SCALE);
        ripple.start(px, py, w, h);
        if (haptic) Haptics.light();
        onPress(px, py);
        return true;
    }

    /** Drag in world coordinates; cancels the press beyond slop. Returns true when ours. */
    public boolean touchDragged(float px, float py, int ptr) {
        if (pointer != ptr) return false;
        if (!hit(px, py, Ui.slop())) {
            cancel();
        } else {
            onDrag(px, py);
        }
        return true;
    }

    /** Touch up in world coordinates; fires when released inside (with slop). */
    public boolean touchUp(float px, float py, int ptr) {
        if (pointer != ptr) return false;
        pointer = -1;
        boolean inside = hit(px, py, Ui.slop());
        releaseSpring(inside);
        if (inside && firesOnRelease()) {
            fire();
        } else if (!inside) {
            onCancel();
        } else {
            onReleaseNoFire();
        }
        return true;
    }

    /** Single-pointer convenience for {@link #touchDown(float, float, int)} (pointer 0). */
    public boolean touchDown(float px, float py) {
        return touchDown(px, py, 0);
    }

    /** Single-pointer convenience for {@link #touchDragged(float, float, int)} (pointer 0). */
    public boolean touchDragged(float px, float py) {
        return touchDragged(px, py, 0);
    }

    /** Single-pointer convenience for {@link #touchUp(float, float, int)} (pointer 0). */
    public boolean touchUp(float px, float py) {
        return touchUp(px, py, 0);
    }

    /** Mouse move (desktop hover); returns true while hovering. */
    public boolean mouseMoved(float px, float py) {
        hovered = visible && enabled && hit(px, py) && isOverVisual(px, py);
        return hovered;
    }

    private boolean isOverVisual(float px, float py) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private void releaseSpring(boolean inside) {
        scale.setPreset(Spring.Preset.BOUNCY).setTarget(1f);
        if (inside) scale.impulse(RELEASE_KICK);
    }

    // ------------------------------------------------------------------ frame
    /** Advances springs, ripple, shake and ramps. */
    public void update(float dt) {
        time += dt;
        scale.update(dt);
        ripple.update(dt);
        if (shakeT < SHAKE_DUR) shakeT += dt;
        float pt = pointer >= 0 ? 1f : 0f;
        press = approach(press, pt, dt / Anim.MICRO);
        float ht = hovered && pointer < 0 ? 1f : 0f;
        hover = approach(hover, ht, dt / Anim.SHORT);
        onUpdate(dt);
    }

    /** Draws the control; {@code alpha} multiplies everything. The batch must be drawing. */
    public abstract void draw(Batch b, float alpha);

    /** Draws with alpha 1. */
    public void draw(Batch b) {
        draw(b, 1f);
    }

    // ------------------------------------------------------------------ visual helpers
    /** Current press scale (≈ 0.94..1.04). */
    public float visualScale() {
        return scale.value();
    }

    /** Horizontal "no" shake offset. */
    public float shakeX() {
        return Anim.shake(shakeT, SHAKE_DUR, 8f, 3);
    }

    /** Hover lift (0..2 units). */
    public float liftY() {
        return 2f * hover;
    }

    /** Brightness multiplier: 1 + .12 press + .08 hover. */
    public float brightness() {
        return 1f + 0.12f * press + 0.08f * hover;
    }

    /** Alpha multiplier: 1, or .40 when disabled. */
    public float alphaMul() {
        return enabled ? 1f : 0.4f;
    }

    /** Draws the ripple; call after the control's background. */
    protected void drawRipple(float alpha) {
        ripple.draw(draw, alpha);
    }

    /**
     * Draws the keyboard focus ring (2 units, {@code ink1}, α .5 ↔ 1 at 1 Hz) around the given
     * rect with corner {@code radius}, when focused.
     */
    protected void drawFocus(float rx, float ry, float rw, float rh, float radius, float alpha) {
        if (!focused) return;
        float a = 0.75f + 0.25f * MathUtils.sin(time * MathUtils.PI2);
        draw.roundRectStroke(rx - 6f, ry - 6f, rw + 12f, rh + 12f, radius + 6f, 2f, Theme.INK1, a * alpha);
    }

    /** Writes {@code c} brightened by {@link #brightness()} into {@code out}. */
    protected Color bright(Color c, Color out) {
        return Theme.brighten(c, brightness(), out);
    }

    // ------------------------------------------------------------------ hooks
    /** Called on press down (after the ripple starts). */
    protected void onPress(float px, float py) { }

    /** Called while dragging inside slop. */
    protected void onDrag(float px, float py) { }

    /** Called when a press is cancelled (drag out, release outside, {@link #cancel()}). */
    protected void onCancel() { }

    /** Called on release inside when {@link #firesOnRelease()} is false. */
    protected void onReleaseNoFire() { }

    /** Called when the action fires, before the callback. */
    protected void onFire() { }

    /** Per-frame hook after the shared state advanced. */
    protected void onUpdate(float dt) { }

    /** Whether a release inside fires (false for hold-to-confirm controls). */
    protected boolean firesOnRelease() {
        return true;
    }

    /** Moves {@code v} toward {@code target} by at most {@code step}. */
    protected static float approach(float v, float target, float step) {
        if (v < target) return Math.min(target, v + step);
        return Math.max(target, v - step);
    }
}
