package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;

/**
 * 水平滑块：轨道、填充和圆形旋钮；按住时旋钮放大，上方气泡显示当前数值，每过 10 % 轻震一次。
 * 点轨道任意位置就跳到该值，拖动时跟随手指；构造后不分配内存。
 *
 * <p>Horizontal slider (DESIGN §1.6): 6-unit {@code ink3 @ .5} track, {@code groundSig} fill, a
 * 44 knob that scales to 1.3 while grabbed (snappy spring), a value bubble above it (NUMBER 24)
 * while grabbed, and a light haptic every 10 %. Touching anywhere on the track (hit height
 * {@code P}) grabs the knob and jumps to that value; dragging follows the finger.
 *
 * <p>World units; the track spans {@code x .. x + w} at centre height {@code cy}. No allocation
 * after construction: the bubble text is built in a reused {@link StringBuilder}.
 */
public class Slider {
    /** Track thickness, knob diameter and grabbed knob scale. */
    public static final float TRACK = 6f, KNOB = 44f, GRAB_SCALE = 1.3f;

    /** Change listener. */
    public interface Listener {
        /** Called when the value changes (while dragging) and once more with finished = true. */
        void changed(Slider slider, float value, boolean finished);
    }

    private final Draw draw;
    private final Fonts fonts;
    private float x, cy, w;
    private float min, max, step;
    private float value;
    private float displayScale = 1f;
    private int decimals;
    private CharSequence suffix = "";
    private boolean enabled = true, visible = true;
    private int pointer = -1;
    private int lastDecile;
    private float bubble;
    private Listener listener;
    private final Spring knobScale = new Spring(Spring.Preset.SNAPPY, 1f);
    private final Spring knobPos = new Spring(Spring.Preset.SNAPPY, 0f);
    private final StringBuilder text = new StringBuilder(16);
    private final Color tmp = new Color();

    /** Creates a slider over {@code [min, max]}, snapping to {@code step} (0 = continuous). */
    public Slider(Draw draw, Fonts fonts, float min, float max, float step) {
        this.draw = draw;
        this.fonts = fonts;
        this.min = min;
        this.max = Math.max(max, min + 1e-6f);
        this.step = Math.max(0f, step);
        this.value = min;
        w = 600f;
    }

    /** Positions the track: from {@code x} to {@code x + w} at centre height {@code cy}. */
    public Slider setBounds(float x, float cy, float w) {
        this.x = x;
        this.cy = cy;
        this.w = Math.max(1f, w);
        return this;
    }

    /** Sets the listener. */
    public Slider setListener(Listener l) {
        listener = l;
        return this;
    }

    /**
     * Bubble format: shows {@code value × scale} with {@code decimals} decimals followed by
     * {@code suffix} (e.g. scale 100, 0 decimals, "%").
     */
    public Slider setDisplay(float scale, int decimals, CharSequence suffix) {
        displayScale = scale;
        this.decimals = Math.max(0, decimals);
        this.suffix = suffix == null ? "" : suffix;
        return this;
    }

    /** Sets the value (clamped and snapped) without calling the listener. */
    public Slider setValue(float v) {
        value = snap(v);
        knobPos.snap(value01());
        lastDecile = (int) (value01() * 10f);
        return this;
    }

    /** Current value. */
    public float value() {
        return value;
    }

    /** Value mapped to 0..1. */
    public float value01() {
        return (value - min) / (max - min);
    }

    /** Enables or disables input (disabled: α .40). */
    public Slider setEnabled(boolean on) {
        enabled = on;
        if (!on) pointer = -1;
        return this;
    }

    /** Shows or hides the slider. */
    public Slider setVisible(boolean on) {
        visible = on;
        if (!on) pointer = -1;
        return this;
    }

    /** True while grabbed. */
    public boolean isGrabbed() {
        return pointer >= 0;
    }

    /** True when (px, py) is on the track's hit band. */
    public boolean hit(float px, float py) {
        float half = Math.max(Ui.touchTarget(), KNOB) * 0.5f;
        return px >= x - KNOB && px <= x + w + KNOB && Math.abs(py - cy) <= half;
    }

    /** Touch down; grabs and jumps to the touch position. */
    public boolean touchDown(float px, float py, int ptr) {
        if (!visible || !enabled || pointer >= 0 || !hit(px, py)) return false;
        pointer = ptr;
        knobScale.setPreset(Spring.Preset.SNAPPY).setTarget(GRAB_SCALE);
        setFromX(px, false);
        Haptics.light();
        return true;
    }

    /** Drag; follows the finger while grabbed. */
    public boolean touchDragged(float px, float py, int ptr) {
        if (pointer != ptr) return false;
        setFromX(px, false);
        return true;
    }

    /** Touch up; releases and reports the final value. */
    public boolean touchUp(float px, float py, int ptr) {
        if (pointer != ptr) return false;
        pointer = -1;
        knobScale.setPreset(Spring.Preset.BOUNCY).setTarget(1f);
        if (listener != null) listener.changed(this, value, true);
        return true;
    }

    /** Releases without a final callback. */
    public void cancel() {
        if (pointer >= 0) {
            pointer = -1;
            knobScale.setPreset(Spring.Preset.BOUNCY).setTarget(1f);
        }
    }

    private void setFromX(float px, boolean finished) {
        float t = MathUtils.clamp((px - x) / w, 0f, 1f);
        float v = snap(min + t * (max - min));
        if (v != value) {
            value = v;
            int d = (int) (value01() * 10f + 1e-4f);
            if (d != lastDecile) {
                lastDecile = d;
                Haptics.light();
            }
            if (listener != null) listener.changed(this, value, finished);
        }
        knobPos.setTarget(value01());
    }

    private float snap(float v) {
        v = MathUtils.clamp(v, min, max);
        if (step > 0f) v = min + Math.round((v - min) / step) * step;
        return MathUtils.clamp(v, min, max);
    }

    /** Advances springs and the bubble fade. */
    public void update(float dt) {
        knobScale.update(dt);
        knobPos.update(dt);
        float target = pointer >= 0 ? 1f : 0f;
        float rate = dt / (target > bubble ? Anim.SHORT : Anim.EXIT);
        bubble = target > bubble ? Math.min(target, bubble + rate) : Math.max(target, bubble - rate);
    }

    /** Draws the slider; the batch must be drawing. */
    public void draw(Batch b, float alpha) {
        if (!visible) return;
        float a = alpha * (enabled ? 1f : 0.4f);
        float t = MathUtils.clamp(knobPos.value(), 0f, 1f);
        float kx = x + w * t;
        draw.pill(x - TRACK * 0.5f, cy - TRACK * 0.5f, w + TRACK, TRACK, Theme.INK3, 0.5f * a);
        if (t > 0f) draw.pill(x - TRACK * 0.5f, cy - TRACK * 0.5f, w * t + TRACK, TRACK, Theme.GROUND_SIG, a);
        float s = knobScale.value();
        float r = KNOB * 0.5f * s;
        if (pointer >= 0) draw.glowDot(kx, cy, r * 4f, Theme.GROUND_SIG, 0.22f * a);
        draw.disc(kx, cy - 2f, r + 1f, Theme.SHADOW, 0.5f * a);
        draw.disc(kx, cy, r, Theme.INK1, a);
        draw.disc(kx, cy, r * 0.32f, Theme.GROUND_SIG, a * (0.6f + 0.4f * (s - 1f) / (GRAB_SCALE - 1f)));
        if (bubble > 0.01f) drawBubble(b, kx, a * Anim.POW2_OUT.apply(bubble));
    }

    private void drawBubble(Batch b, float kx, float a) {
        text.setLength(0);
        float v = value * displayScale;
        if (decimals == 0) Fonts.appendInt(text, Math.round(v));
        else Fonts.appendTruncated(text, v, decimals);
        text.append(suffix);
        float cap = 24f;
        float tw = fonts.width(Fonts.Role.NUMBER, cap, text);
        float bw = Math.max(72f, tw + 36f), bh = cap + 30f;
        float lift = 12f * bubble;
        float by = cy + KNOB * 0.5f * GRAB_SCALE + 14f + lift;
        float bx = MathUtils.clamp(kx - bw * 0.5f, Ui.left() + 8f, Ui.right() - bw - 8f);
        draw.roundRect(bx, by, bw, bh, 14f, Theme.PANEL_HI, a);
        draw.roundRectStroke(bx, by, bw, bh, 14f, 1f, Theme.STROKE, a);
        // pointer notch
        draw.shapes().setColor(Theme.withAlpha(Theme.PANEL_HI, a, tmp));
        draw.shapes().filledTriangle(kx - 8f, by + 0.5f, kx + 8f, by + 0.5f, kx, by - 8f);
        draw.shapes().setColor(Color.WHITE);
        fonts.draw(b, Fonts.Role.NUMBER, cap, text, bx + bw * 0.5f, by + bh * 0.5f, Fonts.VCENTER, Theme.INK1, a);
    }
}
