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
 * 校准画面的点击散点条：−150 到 +150 ms 的横轴，左边 EARLY、右边 LATE，中间高亮 ±66 ms 的共鸣 RESONANCE 窗口。
 * 每次有效点击落下一个圆点，窗口内是共鸣的青色、窗口外是回响 ECHO 的紫色；上方的三角标跟随当前的中位数。
 *
 * <p>The calibration scatter strip (DESIGN §9.4): a horizontal axis from −150 to +150 ms
 * (EARLY on the left, LATE on the right) with a highlighted ±66 ms band (the RESONANCE window).
 * Each valid tap drops a dot onto the axis on a bouncy spring, {@code resonance} (#66F0FF) inside
 * the band and {@code echo} (#B49CFF) beyond it; taps beyond ±150 ms sit on the end as rings.
 * A caret above the axis follows the running median with its value. Preallocated; nothing is
 * allocated per frame.
 */
public final class TapStrip {
    private static final CharSequence EARLY = Lang.of("EARLY", "早"), LATE = Lang.of("LATE", "晚"),
        MS = Lang.of("MS", "毫秒");
    /** Half range of the axis in ms. */
    public static final float RANGE_MS = 150f;
    private static final float DROP = 76f;
    private static final String[] TICKS = {"–150", "–100", "–50", "0", "+50", "+100", "+150"};

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

    /** Creates a strip for at most {@code capacity} dots. */
    public TapStrip(Main game, int capacity) {
        draw = game.draw();
        fonts = game.fonts();
        deltas = new float[capacity];
        drops = new Spring[capacity];
        for (int i = 0; i < capacity; i++) drops[i] = new Spring(Spring.Preset.BOUNCY, 0f);
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

    /** Drops a dot for a tap {@code deltaMs} late (negative = early). */
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

    private static float clamp(float ms) {
        return Math.max(-RANGE_MS, Math.min(RANGE_MS, ms));
    }

    private float xOf(float ms) {
        return cx + clamp(ms) / RANGE_MS * w * 0.5f;
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
        float band = Judgement.TIER1_MS / RANGE_MS * half;
        draw.roundRect(cx - band, y - 16f, band * 2f, 32f, 8f, Theme.RESONANCE, 0.08f * alpha);
        draw.rect(cx - half, y - 2f, w, 4f, Theme.INK1, 0.22f * alpha);
        for (int i = 0; i < TICKS.length; i++) {
            float tx = cx - half + i * w / (TICKS.length - 1);
            boolean zero = i == TICKS.length / 2;
            float th = zero ? 40f : 18f;
            draw.rect(tx - 1f, y - th * 0.5f, 2f, th, zero ? Theme.INK1 : Theme.INK2, (zero ? 0.7f : 0.45f) * alpha);
            fonts.draw(b, Fonts.Role.UI_LABEL, 14f, TICKS[i], tx, y - 40f, Align.center | Fonts.VCENTER, Theme.INK3,
                alpha);
        }
        fonts.draw(b, Fonts.Role.UI_LABEL, 16f, EARLY, cx - half, y - 74f, Align.left | Fonts.VCENTER, Theme.EARLY,
            0.9f * alpha);
        fonts.draw(b, Fonts.Role.UI_LABEL, 16f, LATE, cx + half, y - 74f, Align.right | Fonts.VCENTER, Theme.LATE,
            0.9f * alpha);
        fonts.draw(b, Fonts.Role.UI_LABEL, 16f, MS, cx, y - 74f, Align.center | Fonts.VCENTER, Theme.INK3,
            0.8f * alpha);
        for (int i = 0; i < count; i++) {
            float d = deltas[i];
            float dx = xOf(d);
            float dy = y + drops[i].value() * DROP + ((i * 7) % 5 - 2) * 3f;
            Color c = Math.abs(d) <= Judgement.TIER1_MS ? Theme.RESONANCE : Theme.ECHO;
            if (Math.abs(d) > RANGE_MS) draw.circle(dx, dy, 8f, 3f, c, 0.9f * alpha);
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
