package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Spring;
import org.kelvinizer.ui.Theme;

/**
 * 校准画面的点击散点条：−300 到 +300 ms 的横轴，左边 EARLY、右边 LATE，中间高亮 ±66 ms 的共鸣 RESONANCE 窗口；
 * ±150 ms 以内占四分之三的宽度，150 ms 以外的两段压窄、画淡一些（Kelvin 2026-10-21，以前只有 ±150 ms）。
 * 每次有效点击落下一个圆点，窗口内是共鸣的青色、窗口外到 ±150 ms 是回响 ECHO 的紫色，150 ms 以外是灰色空心圈；
 * 上方的三角标跟随当前的中位数。
 * 目押校准用 px 版：还是 −150 到 +150 的均匀刻度，单位是 px，左边「线下方」、右边「线上方」，没有共鸣窗口。
 *
 * <p>The calibration scatter strip (DESIGN §9.4): a horizontal axis from −300 to +300 ms
 * (EARLY on the left, LATE on the right) with a highlighted ±66 ms band (the RESONANCE window). The axis has two
 * scales (Kelvin 2026-10-21 02:52: 「要做成+-300ms的，+-150ms的区域可以稍微大一点，超过150ms的edge case也要出现在
 * 那个坐标轴上，但是占的区域可以小一点」): ±{@link #INNER_MS} ms takes {@link #INNER_SHARE} of the width and the two
 * stretches beyond it share the rest, drawn fainter. It was −150..+150 ms on one scale before.
 * Each valid tap drops a dot onto the axis on a bouncy spring, {@code resonance} (#66F0FF) inside
 * the band and {@code echo} (#B49CFF) beyond it up to ±{@link #INNER_MS} ms; a tap beyond that is a hollow grey
 * ring ({@code ink2}; Kelvin 2026-10-21 03:04: 「然后+-150~+-300那片区间如果有点击，显示空心的灰色圆圈」), and in px
 * mode a tap beyond the ends sits on the end as a ring.
 * A caret above the axis follows the running median with its value. Preallocated; nothing is
 * allocated per frame.
 *
 * <p>{@link #inPixels()} turns it into the VISUAL HIT POINT strip (Kelvin 2026-10-20 22:37: 「下面的散点图指标应该是px」):
 * −150..+150 px on one scale, BELOW THE LINE on the left and ABOVE THE LINE on the right, no band, every dot in the
 * ground line's colour.
 */
public final class TapStrip {
    private static final CharSequence EARLY = Lang.of("EARLY", "早"), LATE = Lang.of("LATE", "晚"),
        MS = Lang.of("MS", "毫秒"), BELOW = Lang.of("BELOW THE LINE", "线下方"), ABOVE = Lang.of("ABOVE THE LINE", "线上方"),
        PX = "px";
    /** Half range of the ms axis (150 until Kelvin 2026-10-21 02:52). */
    public static final float RANGE_MS = 300f;
    /** Half range of the px axis ({@link #inPixels()}). */
    public static final float RANGE_PX = 150f;
    /** The ms axis changes scale at ±INNER_MS: that inner stretch takes INNER_SHARE of the width. */
    private static final float INNER_MS = 150f, INNER_SHARE = 0.75f;
    private static final float DROP = 76f;
    private static final String[] TICKS_MS = {"–300", "–150", "–100", "–50", "0", "+50", "+100", "+150", "+300"},
        TICKS_PX = {"–150", "–100", "–50", "0", "+50", "+100", "+150"};
    private static final float[] AT_MS = {-300f, -150f, -100f, -50f, 0f, 50f, 100f, 150f, 300f},
        AT_PX = {-150f, -100f, -50f, 0f, 50f, 100f, 150f};

    private final Draw draw;
    private final Fonts fonts;
    private final float[] deltas;
    private final Spring[] drops;
    private final Spring caret = new Spring(Spring.Preset.SOFT, 0f);
    private final StringBuilder caretText = new StringBuilder(8);
    private int count;
    private boolean hasMedian;
    private int caretFor = Integer.MIN_VALUE;
    private float cx, y, w;
    private boolean pixels;

    /** Creates a strip for at most {@code capacity} dots. */
    public TapStrip(Main game, int capacity) {
        draw = game.draw();
        fonts = game.fonts();
        deltas = new float[capacity];
        drops = new Spring[capacity];
        for (int i = 0; i < capacity; i++) drops[i] = new Spring(Spring.Preset.BOUNCY, 0f);
    }

    /** Switches to px (VISUAL HIT POINT): unit and end labels, no RESONANCE band, one dot colour. */
    public TapStrip inPixels() {
        pixels = true;
        return this;
    }

    /** Places the axis: centre x, axis y and total width. */
    public void setBounds(float centerX, float axisY, float width) {
        cx = centerX;
        y = axisY;
        w = width;
    }

    /** Removes every dot and the caret. */
    public void clear() {
        count = 0;
        hasMedian = false;
    }

    /** Drops a dot for a tap {@code deltaMs} late (negative = early), or at {@code deltaMs} px in px mode. */
    public void add(float deltaMs) {
        if (count >= deltas.length) return;
        deltas[count] = deltaMs;
        drops[count].snap(1f).setTarget(0f);
        count++;
    }

    /** Moves the median caret to {@code ms} ({@link Float#NaN} hides it). */
    public void setMedian(float ms) {
        if (Float.isNaN(ms)) {
            hasMedian = false;
            return;
        }
        float v = clamp(ms);
        if (!hasMedian) caret.snap(v);
        else caret.setTarget(v);
        hasMedian = true;
    }

    private float range() {
        return pixels ? RANGE_PX : RANGE_MS;
    }

    private float clamp(float v) {
        return Math.max(-range(), Math.min(range(), v));
    }

    /** Where {@code v} sits on the axis: px on one scale, ms wider within ±INNER_MS and narrower beyond. */
    private float xOf(float v) {
        float half = w * 0.5f;
        if (pixels) return cx + clamp(v) / RANGE_PX * half;
        float a = Math.min(Math.abs(v), RANGE_MS);
        float f = a <= INNER_MS ? a / INNER_MS * INNER_SHARE
            : INNER_SHARE + (a - INNER_MS) / (RANGE_MS - INNER_MS) * (1f - INNER_SHARE);
        return cx + Math.copySign(f * half, v);
    }

    /** Advances the drop springs and the caret. */
    public void update(float dt) {
        for (int i = 0; i < count; i++) drops[i].update(dt);
        caret.update(dt);
    }

    /** Draws axis, band, ticks, dots and caret. */
    public void draw(Batch b, float alpha) {
        if (alpha <= 0.002f) return;
        float half = w * 0.5f;
        if (pixels) {
            draw.rect(cx - half, y - 2f, w, 4f, Theme.INK1, 0.22f * alpha);
        } else {
            float band = xOf(Judgement.TIER1_MS) - cx, in = xOf(INNER_MS) - cx;
            draw.roundRect(cx - band, y - 16f, band * 2f, 32f, 8f, Theme.RESONANCE, 0.08f * alpha);
            draw.rect(cx - in, y - 2f, in * 2f, 4f, Theme.INK1, 0.22f * alpha);
            // the narrower stretches beyond ±INNER_MS are fainter
            draw.rect(cx - half, y - 2f, half - in, 4f, Theme.INK1, 0.12f * alpha);
            draw.rect(cx + in, y - 2f, half - in, 4f, Theme.INK1, 0.12f * alpha);
        }
        String[] ticks = pixels ? TICKS_PX : TICKS_MS;
        float[] at = pixels ? AT_PX : AT_MS;
        for (int i = 0; i < ticks.length; i++) {
            float tx = xOf(at[i]);
            boolean zero = at[i] == 0f;
            float th = zero ? 40f : 18f;
            draw.rect(tx - 1f, y - th * 0.5f, 2f, th, zero ? Theme.INK1 : Theme.INK2, (zero ? 0.7f : 0.45f) * alpha);
            fonts.draw(b, Fonts.Role.UI_LABEL, 14f, ticks[i], tx, y - 40f, Align.center | Fonts.VCENTER, Theme.INK3,
                alpha);
        }
        fonts.draw(b, Fonts.Role.UI_LABEL, 16f, pixels ? BELOW : EARLY, cx - half, y - 74f, Align.left | Fonts.VCENTER,
            pixels ? Theme.INK2 : Theme.EARLY, 0.9f * alpha);
        fonts.draw(b, Fonts.Role.UI_LABEL, 16f, pixels ? ABOVE : LATE, cx + half, y - 74f, Align.right | Fonts.VCENTER,
            pixels ? Theme.INK2 : Theme.LATE, 0.9f * alpha);
        fonts.draw(b, Fonts.Role.UI_LABEL, 16f, pixels ? PX : MS, cx, y - 74f, Align.center | Fonts.VCENTER, Theme.INK3,
            0.8f * alpha);
        for (int i = 0; i < count; i++) {
            float d = deltas[i];
            float dx = xOf(d);
            float dy = y + drops[i].value() * DROP + ((i * 7) % 5 - 2) * 3f;
            float a = Math.abs(d);
            // ms taps beyond ±INNER_MS are hollow grey rings
            boolean outer = !pixels && a > INNER_MS;
            Color c = pixels ? Theme.GROUND_SIG
                : outer ? Theme.INK2 : a <= Judgement.TIER1_MS ? Theme.RESONANCE : Theme.ECHO;
            if (outer || a > range()) draw.circle(dx, dy, 8f, 3f, c, 0.9f * alpha);
            else draw.disc(dx, dy, 8f, c, 0.9f * alpha);
        }
        if (hasMedian) {
            float mx = xOf(caret.value());
            Icons.draw(draw.shapes(), Icons.Icon.TRIANGLE_DOWN, mx, y + 44f, 30f, Theme.INK1, alpha);
            int v = Math.round(caret.target());
            if (v != caretFor) {
                caretFor = v;
                caretText.setLength(0);
                if (v > 0) caretText.append('+');
                if (v < 0) {
                    caretText.append('-');
                    Fonts.appendInt(caretText, -v);
                } else {
                    Fonts.appendInt(caretText, v);
                }
            }
            fonts.drawFixed(b, Fonts.Role.NUMBER, 22f, caretText, mx, y + 80f, Align.center | Fonts.VCENTER,
                Theme.INK1, alpha);
        }
    }

    /** Number of dots. */
    public int count() {
        return count;
    }
}
