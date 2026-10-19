package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Pressable;
import org.kelvinizer.ui.Spring;
import org.kelvinizer.ui.Theme;

/**
 * 歌曲卡片上的一个难度按钮：上面是难度名，下面是谱面定数。
 * 选中时难度色从左往右扫满、按钮弹大一点；缺这张谱面时是虚线框和 —，不能选，点了只抖一下。
 *
 * <p>A difficulty chip of the song card (DESIGN §4.5): the difficulty name (UI_BOLD 20, fitted)
 * over its chart constant (NUMBER 30: the whole number with a superscript mark, {@code ConstantLabel}).
 *
 * <ul>
 *   <li><b>Selected:</b> difficulty fill swept in left to right (160 ms), scale 1.06 on the
 *   bouncy spring, a soft glow in the difficulty accent, the constant slot-rolls in and the name
 *   does a G2.</li>
 *   <li><b>Unselected:</b> {@code panelHi} with a 2 px outline in the fill at α .6.</li>
 *   <li><b>Missing chart:</b> dashed {@code ink3} outline and {@code —}; disabled, a tap shakes.</li>
 *   <li>ABYSS: black fill, 3 px {@code #FF2E88} border plus a 2 px {@code #00E5FF} copy offset
 *   (+2, 0) and an idle G2 every 4–7 s; DEEP: 2 px {@code #004AAD} border.</li>
 * </ul>
 * Drawn in four phases (atlas, UI, NUMBER, UI_BOLD) by the card. No allocation per frame.
 */
final class CardChip extends Pressable {
    private static final float SWEEP_T = 0.16f;

    final Difficulty d;
    private final int o;
    private final MapRenderer r;
    /** Name glitch (G2 on selection; ABYSS idles). */
    final GlitchState label = new GlitchState();
    private final Spring sel = new Spring(Spring.Preset.BOUNCY, 1f);
    private final Color c1 = new Color(), c2 = new Color();
    private boolean selected, missing;
    private float sweep = 1f, roll = 1f;
    private CharSequence constant = "", mark = "";
    /** The card scale (DESIGN §0.3), set by the card every frame; bounds are already scaled. */
    float k = 1f;

    CardChip(Draw draw, MapRenderer r, Difficulty d) {
        super(draw);
        this.r = r;
        this.d = d;
        this.o = d.ordinal();
        if (o == 3) label.idleBursts(5.5f);
    }

    /**
     * Sets the chart constant as shown, its whole number ({@code --} when unknown) and superscript
     * mark ({@code ConstantLabel}), and whether the chart is missing.
     */
    void setChart(CharSequence base, CharSequence mark, boolean missingChart) {
        constant = base != null ? base : "--";
        this.mark = base != null && mark != null ? mark : "";
        missing = missingChart;
        setEnabled(!missingChart);
    }

    /**
     * Selects or deselects the chip. {@code animate} plays the fill sweep, the slot roll and the
     * G2 (a real change); otherwise the state is set at rest.
     */
    void setSelected(boolean on, boolean animate, float now) {
        if (on == selected && animate) return;
        selected = on;
        sel.setTarget(on ? 1.06f : 1f);
        if (!animate) {
            sel.snap(on ? 1.06f : 1f);
            sweep = 1f;
            return;
        }
        if (on) {
            sweep = 0f;
            roll = 0f;
            label.trigger(GlitchText.Level.G2, now);
            sel.impulse(1.2f);
        }
    }

    /** Rolls the constant in again (the song changed). */
    void rollIn() {
        roll = 0f;
    }

    boolean isSelected() {
        return selected;
    }

    @Override
    protected void onUpdate(float dt) {
        sel.update(dt);
        if (sweep < 1f) sweep = Math.min(1f, sweep + dt / SWEEP_T);
        if (roll < 1f) roll = Math.min(1f, roll + dt / SWEEP_T);
    }

    private float scaleNow() {
        return visualScale() * sel.value();
    }

    /** Atlas phase: background, fill sweep, borders, glow, ripple. */
    @Override
    public void draw(Batch b, float alpha) {
        if (!visible || alpha <= 0.002f) return;
        float s = scaleNow();
        float bw = w * s, bh = h * s, bx = centerX() + shakeX() - bw * 0.5f, by = centerY() - bh * 0.5f;
        float rad = MapStyle.CHIP_RADIUS * k;
        if (missing) {
            dashedRoundRect(draw, bx, by, bw, bh, rad, 2f, Theme.INK3, 0.85f * alpha);
            return;
        }
        Color fill = Theme.difficultyFill(o);
        Color acc = Theme.difficultyAccent(o);
        if (selected) {
            float e = Anim.POW2_OUT.apply(sweep);
            draw.glowDot(centerX(), centerY(), bw * 1.5f, acc, 0.28f * alpha * e);
            if (e < 1f) draw.roundRect(bx, by, bw, bh, rad, Theme.PANEL_HI, alpha);
            float fw = Math.max(Math.min(bw, 2f * rad), bw * e);
            draw.roundRect(bx, by, fw, bh, rad, fill, alpha * Math.min(1f, e * 4f));
            Color border = Theme.difficultyBorder(o);
            if (o == 3) draw.roundRectStroke(bx + 2f * k, by, bw, bh, rad, 2f, Theme.ABYSS_BORDER_COPY, 0.9f * alpha);
            if (border != null) draw.roundRectStroke(bx, by, bw, bh, rad, Theme.difficultyBorderWidth(o), border, alpha);
        } else {
            draw.roundRect(bx, by, bw, bh, rad, Theme.PANEL_HI, alpha);
            if (o == 3) {
                draw.roundRectStroke(bx + 2f * k, by, bw, bh, rad, 2f, Theme.ABYSS_BORDER_COPY, 0.45f * alpha);
                draw.roundRectStroke(bx, by, bw, bh, rad, 2f, acc, 0.6f * alpha);
            } else {
                draw.roundRectStroke(bx, by, bw, bh, rad, 2f, fill, 0.6f * alpha);
            }
        }
        if (press > 0f || hover > 0f) {
            draw.roundRect(bx, by, bw, bh, rad, Theme.INK1, (0.10f * press + 0.05f * hover) * alpha);
        }
        ripple.color(selected && o == 0 ? Theme.INK3 : Theme.INK1);
        drawRipple(alpha);
        drawFocus(bx, by, bw, bh, rad, alpha);
    }

    private boolean filledText() {
        return selected && sweep >= 0.5f;
    }

    /** UI phase: the missing dash. */
    void drawUi(Batch b, float alpha) {
        if (!visible || !missing || alpha <= 0.002f) return;
        r.ui(b, "—", MapUi.cap(26f, k), centerX() + shakeX(), centerY() - 20f * k, Align.center | Fonts.VCENTER, Theme.INK3,
            alpha);
    }

    /** UI_BOLD phase: the name (G2 on change). */
    void drawBold(Batch b, float alpha) {
        if (!visible || alpha <= 0.002f) return;
        float s = scaleNow();
        Color c = missing ? Theme.INK3 : filledText() ? Theme.difficultyText(o) : Theme.INK1;
        float cap = MapUi.cap(20f, k) * s;
        String name = Theme.DIFFICULTY_NAMES[o];
        float x = centerX() + shakeX(), y = centerY() + 22f * k * s;
        float maxW = (w - 16f * k) * s;
        if (!missing && label.isBursting(r.time)) {
            float fit = Math.min(cap, cap * maxW / Math.max(1f, r.boldWidth(name, cap)));
            r.glitch(b, Fonts.Role.UI_BOLD, MapRenderer.R_BOLD, name, Math.max(cap * 0.85f, fit), x, y,
                Align.center | Fonts.VCENTER, c, alpha, label);
            return;
        }
        r.boldFit(b, name, cap, x, y, Align.center | Fonts.VCENTER, maxW, 0.85f, c, (missing ? 0.8f : 1f) * alpha);
    }

    /** NUMBER phase: the constant (slot-rolls in). */
    void drawNumbers(Batch b, float alpha) {
        if (!visible || missing || alpha <= 0.002f) return;
        float s = scaleNow();
        Color c;
        if (filledText()) c = Theme.difficultyText(o);
        else c = o == 3 ? Theme.difficultyAccent(o) : Theme.difficultyFill(o);
        float cap = MapUi.cap(30f, k) * s, mc = cap * Theme.CONST_MARK_SCALE, cy = centerY() - 20f * k * s;
        float bw = r.fixedWidth(constant, cap);
        float mw = mark.length() == 0 ? 0f : cap * Theme.CONST_MARK_GAP + r.numWidth(mark, mc);
        float x0 = centerX() + shakeX() - (bw + mw) * 0.5f;
        r.fixed(b, constant, cap, x0, cy, Align.left | Fonts.VCENTER, c, null, 0, alpha, roll);
        if (mw > 0f) {
            r.num(b, mark, mc, x0 + bw + cap * Theme.CONST_MARK_GAP, cy + cap * Theme.CONST_MARK_RISE,
                Align.left | Fonts.VCENTER, c, alpha * roll);
        }
    }

    /**
     * Dashed rounded-rect outline (missing chart): straight dashes on the edges and solid quarter
     * arcs at the corners.
     */
    static void dashedRoundRect(Draw draw, float x, float y, float w, float h, float rad, float lw, Color c, float a) {
        float dash = 12f, gap = 8f;
        float half = lw * 0.5f;
        dashLine(draw, x + rad, y + half, x + w - rad, y + half, dash, gap, lw, c, a);
        dashLine(draw, x + rad, y + h - half, x + w - rad, y + h - half, dash, gap, lw, c, a);
        dashLine(draw, x + half, y + rad, x + half, y + h - rad, dash, gap, lw, c, a);
        dashLine(draw, x + w - half, y + rad, x + w - half, y + h - rad, dash, gap, lw, c, a);
        float rr = rad - half;
        draw.arc(x + rad, y + rad, rr, MathUtils.PI + MathUtils.HALF_PI * 0.2f, MathUtils.HALF_PI * 0.6f, lw, c, a);
        draw.arc(x + w - rad, y + rad, rr, -MathUtils.HALF_PI * 0.8f, MathUtils.HALF_PI * 0.6f, lw, c, a);
        draw.arc(x + w - rad, y + h - rad, rr, MathUtils.HALF_PI * 0.2f, MathUtils.HALF_PI * 0.6f, lw, c, a);
        draw.arc(x + rad, y + h - rad, rr, MathUtils.HALF_PI * 1.2f, MathUtils.HALF_PI * 0.6f, lw, c, a);
    }

    private static void dashLine(Draw draw, float x0, float y0, float x1, float y1, float dash, float gap, float lw,
                                 Color c, float a) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return;
        int n = Math.max(1, Math.round((len + gap) / (dash + gap)));
        float step = len / n, on = step - gap;
        if (on < 2f) on = step * 0.6f;
        float ux = dx / len, uy = dy / len;
        for (int i = 0; i < n; i++) {
            float s0 = i * step + (step - on) * 0.5f, s1 = s0 + on;
            draw.line(x0 + ux * s0, y0 + uy * s0, x0 + ux * s1, y0 + uy * s1, lw, c, a);
        }
    }
}
