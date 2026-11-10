package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;

/**
 * 按钮：PRIMARY（渐变实心）、SECONDARY（描边）、
 * DESTRUCTIVE（红色描边，要按住到圆环填满才生效）和 ICON（圆形图标）四种样式。
 * 按下缩放、波纹和触感来自 {@link Pressable}；文字可以含中文，放不下时先缩小再截断。
 *
 * <p>Tappable button (DESIGN §1.6, ARCHITECTURE §3) with the §1.5 interaction primitives from
 * {@link Pressable}.
 *
 * <ul>
 *   <li>{@link Variant#PRIMARY}: radius 18, vertical gradient {@code btnTop → btnBot}, label
 *       UI_BOLD 26 {@code #04142B}, soft glow at α .25.</li>
 *   <li>{@link Variant#SECONDARY}: 2-unit {@code ink1 @ .6} outline over a faint panel, label
 *       {@code ink1}.</li>
 *   <li>{@link Variant#DESTRUCTIVE}: 2-unit {@code #FF5C7A} outline; <b>hold to confirm</b>: a
 *       ring (and a fill wash) fills over {@link #setHoldTime the hold time} (1.5 s by default)
 *       and fires when full; releasing early drains it in 150 ms.</li>
 *   <li>{@link Variant#ICON}: 88-unit visual circle ({@code panel} fill, 2-unit
 *       {@code ink1 @ .7} stroke) with an {@link Icons.Icon}; hit size {@code P}.</li>
 * </ul>
 *
 * <p>Use {@link #standardHeight()} ({@code max(P, 112)}) for text buttons. The label may contain CJK; it
 * is shrunk then truncated to fit.
 *
 * <p><b>Shared label raster.</b> {@link #draw} draws the label from its own UI_BOLD page in the
 * middle of the shapes, which costs a texture switch per button. A screen that groups its text by
 * page calls {@link #drawBody} instead and draws {@link #label()} itself from its shared raster,
 * centred at ({@link #labelX()}, {@link #labelY()}), cap {@link #labelCap()} × {@link #labelScale()},
 * fitted to {@link #labelMaxWidth()}, in {@link #labelColor()} at {@code alpha × alphaMul()}.
 */
public class Button extends Pressable {
    /** Visual variants. */
    public enum Variant { PRIMARY, SECONDARY, DESTRUCTIVE, ICON }

    /** Corner radius of text buttons. */
    public static final float RADIUS = 18f;
    /** Label cap height. */
    public static final float LABEL_CAP = 26f;
    /** Visual diameter of icon buttons. */
    public static final float ICON_DIAMETER = 88f;
    /** Default hold time for destructive confirmation (s). */
    public static final float HOLD_TIME = 1.5f;
    private static final float DRAIN_TIME = 0.15f;

    private final Fonts fonts;
    private Variant variant;
    private CharSequence label;
    private Icons.Icon icon;
    private Color accent;
    private float labelCap = LABEL_CAP;
    private float holdTime = HOLD_TIME;
    private float hold, confirmFlash;
    private final Color c1 = new Color(), c2 = new Color(), c3 = new Color();

    /** Creates a button; set its bounds with {@link #setBounds} or {@link #setCenter}. */
    public Button(Draw draw, Fonts fonts, Variant variant, CharSequence label) {
        super(draw);
        this.fonts = fonts;
        this.variant = variant;
        this.label = label;
        if (variant == Variant.ICON) {
            w = ICON_DIAMETER;
            h = ICON_DIAMETER;
        } else {
            h = standardHeight();
            w = 360f;
        }
    }

    /** Creates an icon button (88 visual, hit P). */
    public Button(Draw draw, Fonts fonts, Icons.Icon icon) {
        this(draw, fonts, Variant.ICON, null);
        this.icon = icon;
    }

    /** Standard text-button height {@code max(P, 112)} in world units. */
    public static float standardHeight() {
        return Math.max(112f, Ui.touchTarget());
    }

    /** Sets the label (CharSequence is referenced; keep it stable or reuse a StringBuilder). */
    public Button setLabel(CharSequence text) {
        label = text;
        return this;
    }

    /** Current label. */
    public CharSequence label() {
        return label;
    }

    /** Changes the variant. */
    public Button setVariant(Variant v) {
        variant = v;
        return this;
    }

    /** Variant. */
    public Variant variant() {
        return variant;
    }

    /** Overrides the accent colour (secondary outline / destructive colour); null = default. */
    public Button setAccent(Color c) {
        accent = c;
        return this;
    }

    /** Overrides the label cap height (default 26). */
    public Button setLabelCap(float cap) {
        labelCap = cap;
        return this;
    }

    /** Sets how long a {@link Variant#DESTRUCTIVE} press must be held to fire (s, default 1.5). */
    public Button setHoldTime(float seconds) {
        holdTime = Math.max(0.1f, seconds);
        return this;
    }

    /** Hold time of a {@link Variant#DESTRUCTIVE} press (s). */
    public float holdTime() {
        return holdTime;
    }

    /** Destructive hold progress 0..1. */
    public float holdProgress() {
        return hold;
    }

    /** True for hold-to-confirm buttons ({@link Variant#DESTRUCTIVE}): a plain tap never fires. */
    public boolean holdsToConfirm() {
        return variant == Variant.DESTRUCTIVE;
    }

    @Override
    protected boolean firesOnRelease() {
        return variant != Variant.DESTRUCTIVE;
    }

    /** A quick tap on a hold-to-confirm button shakes it as a hint to hold. */
    @Override
    protected void onReleaseNoFire() {
        if (variant == Variant.DESTRUCTIVE && hold < 0.2f) shake();
    }

    @Override
    protected void onUpdate(float dt) {
        if (variant == Variant.DESTRUCTIVE) {
            if (pointer >= 0) {
                hold += dt / holdTime;
                if (hold >= 1f) {
                    hold = 0f;
                    confirmFlash = 1f;
                    cancel();
                    scale.impulse(2f);
                    Haptics.medium();
                    fire();
                }
            } else if (hold > 0f) {
                hold = Math.max(0f, hold - dt / DRAIN_TIME);
            }
        }
        if (confirmFlash > 0f) confirmFlash = Math.max(0f, confirmFlash - dt / Anim.MEDIUM);
    }

    /** Draws the button and its label (the label from its own UI_BOLD page). */
    @Override
    public void draw(Batch b, float alpha) {
        drawBody(b, alpha);
        if (!visible || variant == Variant.ICON || label == null || label.length() == 0) return;
        float a = alpha * alphaMul();
        if (a <= 0.002f) return;
        float s = visualScale();
        fonts.drawScaled(b, Fonts.Role.UI_BOLD, labelCap, s, label, labelX(), labelY(), Fonts.VCENTER, labelMaxWidth(),
            0.72f, labelColor(), a);
    }

    /**
     * Draws everything but the label text: plate, outline, glow, ripple, hold ring, the icon of an
     * icon or icon + text button. The caller then draws {@link #label()} from its own
     * raster (see the class comment); {@link #draw} does both.
     */
    public void drawBody(Batch b, float alpha) {
        if (!visible) return;
        float a = alpha * alphaMul();
        if (a <= 0.002f) return;
        float s = visualScale();
        float cx = centerX() + shakeX(), cy = centerY() + liftY();
        float bw = w * s, bh = h * s;
        float bx = cx - bw * 0.5f, by = cy - bh * 0.5f;
        switch (variant) {
            case PRIMARY:
                drawPrimary(a, s, cx, cy, bx, by, bw, bh);
                break;
            case SECONDARY:
            case DESTRUCTIVE:
                drawOutlined(a, s, cx, cy, bx, by, bw, bh, outlineColor(), variant == Variant.DESTRUCTIVE ? 1f : 0.6f);
                break;
            case ICON:
            default:
                drawIcon(a, s, cx, cy);
                break;
        }
    }

    // ------------------------------------------------------------------ label layout (this frame)
    /** Label cap height before {@link #labelScale()} (UI_BOLD, default {@value #LABEL_CAP}). */
    public float labelCap() {
        return labelCap;
    }

    /** Scale of the label this frame (the press / pop scale). */
    public float labelScale() {
        return visualScale();
    }

    /** Label colour of the variant ({@code btnLabel}, {@code ink1}, or the destructive accent). */
    public Color labelColor() {
        switch (variant) {
            case PRIMARY:
                return Theme.BTN_LABEL;
            case DESTRUCTIVE:
                return outlineColor();
            default:
                return Theme.INK1;
        }
    }

    /** World x of the label centre this frame (after the icon or hold ring, if any). */
    public float labelX() {
        float s = visualScale();
        float cx = centerX() + shakeX();
        if (variant == Variant.DESTRUCTIVE) {
            float ringR = 15f * s;
            float total = labelWidth() + 2f * ringR + 18f * s;
            return cx - total * 0.5f + 2f * ringR + 18f * s + labelWidth() * 0.5f;
        }
        if (hasIconAndLabel()) {
            float iconSize = iconSize(s);
            float lw = iconLabelWidth(s);
            float ix = cx - (iconSize + 14f * s + lw) * 0.5f + iconSize * 0.5f;
            return ix + iconSize * 0.5f + 14f * s + lw * 0.5f;
        }
        return cx;
    }

    /** World y of the label's vertical centre this frame. */
    public float labelY() {
        return centerY() + liftY();
    }

    /** Width the label must fit this frame (shrink, then truncate). */
    public float labelMaxWidth() {
        float s = visualScale();
        if (hasIconAndLabel() && variant != Variant.DESTRUCTIVE) return iconLabelWidth(s) + 1f;
        return w * s - 2f * 28f * s;
    }

    private Color outlineColor() {
        if (accent != null) return accent;
        return variant == Variant.DESTRUCTIVE ? Theme.LOST : Theme.INK1;
    }

    private boolean hasIconAndLabel() {
        return icon != null && label != null && label.length() > 0;
    }

    private float iconSize(float s) {
        return labelCap * 1.6f * s;
    }

    private float iconLabelWidth(float s) {
        return Math.min(labelWidth() * s, w * s - 2f * 28f * s - iconSize(s) - 14f * s);
    }

    // ------------------------------------------------------------------ variants
    private void drawPrimary(float a, float s, float cx, float cy, float bx, float by, float bw, float bh) {
        float k = brightness();
        draw.sprite(draw.softDot(), cx, cy - 6f, bw + 120f, bh + 110f, Theme.BTN_TOP, 0.25f * a * (0.9f + 0.1f * k));
        Theme.brighten(Theme.BTN_TOP, k, c1);
        Theme.brighten(Theme.BTN_BOT, k, c2);
        draw.roundRectGradient(bx, by, bw, bh, RADIUS * s, c2, c1, a);
        // top inner highlight
        draw.rect(bx + RADIUS * s, by + bh - 3f * s, bw - 2f * RADIUS * s, 1.5f * s, Theme.WHITE, 0.35f * a);
        ripple.color(Theme.WHITE);
        drawRipple(a);
        drawLabelIcon(a, s, cx, cy);
    }

    private void drawOutlined(float a, float s, float cx, float cy, float bx, float by, float bw, float bh,
                              Color stroke, float strokeAlpha) {
        float k = brightness();
        draw.roundRect(bx, by, bw, bh, RADIUS * s, Theme.PANEL, 0.55f * a);
        if (variant == Variant.DESTRUCTIVE && hold > 0f) {
            // fill wash left → right with the hold progress
            float fw = bw * hold;
            draw.roundRect(bx, by, Math.max(fw, Math.min(bw, 2f * RADIUS * s)), bh, RADIUS * s, stroke,
                0.20f * a * Math.min(1f, hold * 6f));
        }
        if (confirmFlash > 0f) draw.roundRect(bx, by, bw, bh, RADIUS * s, stroke, 0.35f * confirmFlash * a);
        if (press > 0f || hover > 0f) {
            draw.roundRect(bx, by, bw, bh, RADIUS * s, stroke, (0.08f * press + 0.05f * hover) * a);
        }
        bright(stroke, c1);
        draw.roundRectStroke(bx, by, bw, bh, RADIUS * s, 2f, c1, Math.min(1f, strokeAlpha * k) * a);
        ripple.color(stroke);
        drawRipple(a);
        if (variant == Variant.DESTRUCTIVE) {
            float ringR = 15f * s;
            float total = labelWidth() + 2f * ringR + 18f * s;
            float rx = cx - total * 0.5f + ringR;
            draw.circle(rx, cy, ringR, 3f * s, stroke, 0.30f * a);
            if (hold > 0f) {
                draw.arc(rx, cy, ringR, MathUtils.HALF_PI, -MathUtils.PI2 * hold, 3.5f * s, stroke, a);
            }
        } else {
            drawLabelIcon(a, s, cx, cy);
        }
    }

    private void drawIcon(float a, float s, float cx, float cy) {
        float r = ICON_DIAMETER * 0.5f * Math.min(w, h) / ICON_DIAMETER * s;
        float k = brightness();
        draw.disc(cx, cy, r, Theme.PANEL, a);
        if (press > 0f || hover > 0f) draw.disc(cx, cy, r, Theme.INK1, (0.08f * press + 0.05f * hover) * a);
        ripple.color(Theme.INK1);
        drawRipple(a);
        Color stroke = accent != null ? accent : Theme.INK1;
        draw.circle(cx, cy, r - 1f, 2f, stroke, Math.min(1f, 0.7f * k) * a);
        if (icon != null) {
            Theme.brighten(stroke, k, c3);
            Icons.draw(draw.shapes(), icon, cx, cy, r * 0.95f, c3, a);
        }
    }

    private float labelWidth() {
        if (label == null || label.length() == 0) return 0f;
        return fonts.width(Fonts.Role.UI_BOLD, labelCap, label);
    }

    /** The icon left of the label of an icon + text button (a shape, part of the body). */
    private void drawLabelIcon(float a, float s, float cx, float cy) {
        if (!hasIconAndLabel()) return;
        float iconSize = iconSize(s);
        float lw = iconLabelWidth(s);
        float ix = cx - (iconSize + 14f * s + lw) * 0.5f + iconSize * 0.5f;
        Icons.draw(draw.shapes(), icon, ix, cy, iconSize, labelColor(), a);
    }
}
