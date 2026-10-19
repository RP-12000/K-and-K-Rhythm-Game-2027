package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;

/**
 * 小标签（芯片）控件：40 高的圆角标签，可带前置色点；选中时有强调色底色和描边，160 ms 内平滑过渡。
 * 设为可切换后点一下就切换选中状态；难度标签由使用方自己绘制，不用这个类。
 *
 * <p>Chip (DESIGN §1.6): radius 12, small chips 40 tall (difficulty chips, 104 tall, are drawn by
 * their owner with {@link Theme#difficultyFill(int)} and friends). Optional selected state that
 * morphs over 160 ms {@code pow3Out}: selected = accent wash and 2-unit accent stroke, label
 * {@code ink1}; unselected = panel fill, 1-unit stroke, label {@code ink2}. An optional leading
 * dot shows a colour key. With {@link #setToggles(boolean)} a tap flips the selection.
 */
public class Chip extends Pressable {
    /** Small chip height and corner radius. */
    public static final float SMALL_H = 40f, RADIUS = 12f;

    private final Fonts fonts;
    private CharSequence label;
    private float labelCap = 16f;
    private boolean selected, toggles;
    private float sel;
    private Color accent = Theme.GROUND_SIG;
    private Color dot;
    private final Color c1 = new Color(), c2 = new Color();

    /** Creates a small chip; size it with {@link #fitWidth()} or {@link #setBounds}. */
    public Chip(Draw draw, Fonts fonts, CharSequence label) {
        super(draw);
        this.fonts = fonts;
        this.label = label;
        h = SMALL_H;
        w = 120f;
    }

    /** Sets the label (referenced). */
    public Chip setLabel(CharSequence text) {
        label = text;
        return this;
    }

    /** Label cap height (default 16). */
    public Chip setLabelCap(float cap) {
        labelCap = cap;
        return this;
    }

    /** Accent for the selected state (default {@code groundSig}). */
    public Chip setAccent(Color c) {
        accent = c == null ? Theme.GROUND_SIG : c;
        return this;
    }

    /** Leading colour dot (null = none). */
    public Chip setDot(Color c) {
        dot = c;
        return this;
    }

    /** Whether a tap flips the selection. */
    public Chip setToggles(boolean on) {
        toggles = on;
        return this;
    }

    /** Sets the selection; {@code animate = false} snaps. */
    public Chip setSelected(boolean on, boolean animate) {
        selected = on;
        if (!animate) sel = on ? 1f : 0f;
        return this;
    }

    /** Selected state. */
    public boolean isSelected() {
        return selected;
    }

    /** Width that fits the label with 20-unit padding (and the dot), keeping the centre. */
    public Chip fitWidth() {
        float tw = label == null ? 0f : fonts.width(Fonts.Role.UI_LABEL, labelCap, label);
        float dw = dot != null ? 20f : 0f;
        setSize(Math.max(h, tw + dw + 40f), h);
        return this;
    }

    @Override
    protected void onFire() {
        if (toggles) selected = !selected;
    }

    @Override
    protected void onUpdate(float dt) {
        float target = selected ? 1f : 0f;
        sel = approach(sel, target, dt / Anim.SHORT);
    }

    @Override
    public void draw(Batch b, float alpha) {
        if (!visible) return;
        float a = alpha * alphaMul();
        float s = visualScale();
        float cx = centerX() + shakeX(), cy = centerY() + liftY();
        float cw = w * s, ch = h * s;
        float x0 = cx - cw * 0.5f, y0 = cy - ch * 0.5f;
        float r = Math.min(RADIUS, ch * 0.5f) * s;
        float e = Anim.POW3_OUT.apply(sel);
        draw.roundRect(x0, y0, cw, ch, r, Theme.PANEL, 0.8f * a);
        if (e > 0f) draw.roundRect(x0, y0, cw, ch, r, accent, 0.20f * e * a);
        if (press > 0f || hover > 0f) draw.roundRect(x0, y0, cw, ch, r, Theme.INK1, (0.07f * press + 0.04f * hover) * a);
        Theme.mix(Theme.STROKE, accent, e, c1);
        bright(c1, c1);
        draw.roundRectStroke(x0, y0, cw, ch, r, 1f + e, c1, a);
        ripple.color(e > 0.5f ? accent : Theme.INK1);
        drawRipple(a);
        Theme.mix(Theme.INK2, Theme.INK1, e, c2);
        float lx = cx;
        if (dot != null) {
            float tw = label == null ? 0f : fonts.width(Fonts.Role.UI_LABEL, labelCap, label) * s;
            float total = tw + 20f * s;
            float dx = cx - total * 0.5f + 5f * s;
            draw.disc(dx, cy, 5f * s, dot, a);
            lx = dx + 15f * s + tw * 0.5f;
        }
        if (label != null) {
            fonts.drawScaled(b, Fonts.Role.UI_LABEL, labelCap, s, label, lx, cy, Fonts.VCENTER, cw - 24f * s, 0.7f,
                c2, a);
        }
        drawFocus(x0, y0, cw, ch, r, a);
    }
}
