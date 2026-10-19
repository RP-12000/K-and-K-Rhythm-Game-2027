package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Pressable;
import org.kelvinizer.ui.Theme;

/**
 * 浮层里的文字小按钮（排序面板的选项）：选中时填满青色，放不下时在中间的空格处折成两行。
 *
 * <p>A text chip / small button of the overlays: {@code panelHi} with a stroke, filled in
 * {@code groundSig} when selected, label UI_BOLD 20 (essential) with an optional icon. A label
 * that does not fit on one line at its cap wraps at the space nearest its middle (split once at
 * construction); only a single word too long for the chip is shrunk (to 0.85). Shapes in
 * {@link #draw}, the label in {@link #drawText}.
 */
final class TextChip extends Pressable {
    private final MapRenderer r;
    private final CharSequence label;
    /** The two lines of a wrapped label (null when the label has no space). */
    private final String line1, line2;
    private final Icons.Icon icon;
    boolean selected;
    private float selK;
    /** The overlay scale (set by the panel every frame); bounds are already scaled. */
    float k = 1f;
    /** Wrap decision cache (re-measured only when the cap or the room changes). */
    private float wrapCap = -1f, wrapAvail = -1f;
    private boolean wrap;

    TextChip(Draw draw, MapRenderer r, CharSequence label, Icons.Icon icon) {
        super(draw);
        this.r = r;
        this.label = label;
        this.icon = icon;
        String t = label.toString();
        int best = -1;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == ' ' && (best < 0 || Math.abs(i - t.length() / 2) < Math.abs(best - t.length() / 2))) {
                best = i;
            }
        }
        line1 = best > 0 ? t.substring(0, best) : null;
        line2 = best > 0 ? t.substring(best + 1) : null;
    }

    @Override
    protected void onUpdate(float dt) {
        selK = approach(selK, selected ? 1f : 0f, dt / Anim.MICRO);
    }

    @Override
    public void draw(Batch b, float alpha) {
        if (!visible || alpha <= 0.002f) return;
        float a = alpha * alphaMul();
        float s = visualScale();
        float cx = centerX() + shakeX(), cy = centerY() + liftY();
        float bw = w * s, bh = h * s, bx = cx - bw * 0.5f, by = cy - bh * 0.5f;
        float rad = MapStyle.CHIP_RADIUS * k * s;
        draw.roundRect(bx, by, bw, bh, rad, Theme.PANEL_HI, a);
        if (selK > 0f) {
            draw.glowDot(cx, cy, bw * 1.3f, Theme.GROUND_SIG, 0.18f * selK * a);
            draw.roundRect(bx, by, bw, bh, rad, Theme.GROUND_SIG, selK * a);
        }
        draw.roundRectStroke(bx, by, bw, bh, rad, 2f, selK > 0.5f ? Theme.GROUND_SIG : Theme.STROKE, a);
        if (press > 0f || hover > 0f) draw.roundRect(bx, by, bw, bh, rad, Theme.INK1, (0.10f * press + 0.05f * hover) * a);
        ripple.color(selK > 0.5f ? Theme.BTN_LABEL : Theme.INK1);
        drawRipple(a);
        if (icon != null) {
            Icons.draw(draw.shapes(), icon, bx + 40f * k * s, cy, 40f * k * s, Theme.INK1, 0.9f * a);
        }
        drawFocus(bx, by, bw, bh, MapStyle.CHIP_RADIUS * k, a);
    }

    /** UI_BOLD phase: the label (one line, or two when it does not fit). */
    void drawText(Batch b, float alpha) {
        if (!visible || alpha <= 0.002f) return;
        float a = alpha * alphaMul();
        float s = visualScale();
        float cx = centerX() + shakeX(), cy = centerY() + liftY();
        float left = (icon != null ? 72f : 12f) * k * s;
        float avail = w * s - left - 12f * k * s;
        float lx = icon != null ? cx - w * s * 0.5f + left + avail * 0.5f : cx;
        Color c = selK > 0.5f ? Theme.BTN_LABEL : Theme.INK1;
        float cap = MapUi.cap(20f, k) * s;
        if (cap != wrapCap || avail != wrapAvail) {
            wrapCap = cap;
            wrapAvail = avail;
            wrap = line1 != null && r.boldWidth(label, cap) > avail;
        }
        if (wrap) {
            float half = cap * 0.72f;
            r.boldFit(b, line1, cap, lx, cy + half, Align.center | Fonts.VCENTER, avail, 0.85f, c, a);
            r.boldFit(b, line2, cap, lx, cy - half, Align.center | Fonts.VCENTER, avail, 0.85f, c, a);
            return;
        }
        r.boldFit(b, label, cap, lx, cy, Align.center | Fonts.VCENTER, avail, 0.85f, c, a);
    }
}
