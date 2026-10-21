package org.kelvinizer.screens.result;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;

/**
 * 结算画面曲绘下面的统计：左边共鸣连击和最大连击（同一条线，上面蓝条、下面紫条，线右端是音符总数）、早晚和平均偏差，
 * 右边共鸣、回响、迷失和水雷的数量（共鸣下面一行 PERFECT），以及自动游玩时 NEW BEST 位置上的 AUTOPLAY 标签。
 * 比 16:8.5 宽的手机上这些统计排成中间一栏，判定数上面一条分隔线（第八版）。
 *
 * <p>The numbers under the jacket (Kelvin 2026-10-19 06:28, round 7): RESONANCE CHAIN and MAX COMBO on one line
 * (05:12 「resonance chain/max combo可以了」: the chain's bar above it, the combo's below, the note count at its right
 * end), the EARLY / LATE / AVG line under them (it opens nothing since 17:48), and right of them the
 * RESONANCE, ECHO, LOST and MINES counts with PERFECT under RESONANCE (tap → its early / late split); MINES shows
 * 0 / 0 on a chart without mines too (02:40 「没有水雷也表0/0」). Plus the AUTOPLAY pill in the best line's place: no
 * clear badge since round 8 (14:43 「结算界面的“clear/full combo/all resonance”也去掉吧」; a full combo shows in the
 * stamp's purple, ALL RESONANCE in the R / R+ grade). With three columns (round 8) they stack in the middle column,
 * larger, with a divider over the counts. The labels are English in both languages (2026-10-18 16:45
 * 「结算界面的英文也全部保留」).
 */
public final class StatsBlock {
    private static final String[] LABELS = {"RESONANCE", "ECHO", "LOST", "MINES"};
    private static final String CHAIN_LABEL = "RESONANCE CHAIN", COMBO_LABEL = "MAX COMBO";
    private static final String EARLY = "EARLY", LATE = "LATE", AVG = "AVG";
    private static final Color CLEAR = new Color(1f, 1f, 1f, 0f);
    private static final Color CHAIN_LINE = new Color(160 / 255f, 170 / 255f, 205 / 255f, 1f);
    private static final String AUTOPLAY = "AUTOPLAY";
    /** The AUTOPLAY pill's dark base (the clear badge's of round 7) and the divider (white α 40, round 8). */
    private static final Color AUTO_FILL = new Color(32 / 255f, 41 / 255f, 98 / 255f, 0.92f);
    private static final Color DIVIDER = new Color(1f, 1f, 1f, 40 / 255f);

    private final ResultKit k;
    /** Width of the early / late line as last laid out (its tap target). */
    private float earlyLateW;
    /** Scale that fits the early / late line into {@link ResultLayout#earlyLateMaxW} (1 when it fits). */
    private float earlyLateFit = 1f;

    /** Creates the block. */
    public StatsBlock(ResultKit kit) {
        this.k = kit;
    }

    /** Lays out the early / late line for the current layout (its width is the tap target). */
    public void measure(ResultModel m, ResultLayout l) {
        TextQueue q = k.text;
        float lc = l.earlyLateLabelCap, vc = l.earlyLateCap, g1 = 8f * l.earlyLateK, g2 = 34f * l.earlyLateK;
        earlyLateW = q.width(Fonts.Role.UI_LABEL, lc, EARLY) + g1 + q.width(Fonts.Role.NUMBER, vc, m.earlyText) + g2
            + q.width(Fonts.Role.UI_LABEL, lc, LATE) + g1 + q.width(Fonts.Role.NUMBER, vc, m.lateText) + g2
            + q.width(Fonts.Role.UI_LABEL, lc, AVG) + g1 + q.width(Fonts.Role.NUMBER, vc, m.meanText);
        // a narrow middle column (three columns just past 16:8.5) shrinks the whole line to its width
        earlyLateFit = l.earlyLateMaxW > 0f && earlyLateW > l.earlyLateMaxW ? l.earlyLateMaxW / earlyLateW : 1f;
        earlyLateW *= earlyLateFit;
    }

    /** @return the width of the EARLY / LATE / AVG line. */
    public float earlyLateWidth() {
        return earlyLateW;
    }

    /**
     * Draws the counts, PERFECT, the divider over them (three columns), the chain line and the early / late line
     * (shapes now; the text joins the page groups of the {@link TextQueue}).
     */
    public void drawRows(ResultModel m, ResultLayout l, float t, float alpha) {
        TextQueue q = k.text;
        if (l.dividerX1 > l.dividerX0 && t >= Choreo.ROWS_T) {
            float lw = 1.2f;
            k.draw.rect(l.dividerX0, l.dividerY - lw * 0.5f, l.dividerX1 - l.dividerX0, lw, DIVIDER,
                alpha * Anim.progress(t, Choreo.ROWS_T, 0.25f));
        }
        for (int r = 0; r < 4; r++) {
            float start = Choreo.ROWS_T + r * Choreo.ROWS_STAGGER;
            if (t < start) continue;
            float a = alpha * Anim.progress(t, start, 0.15f);
            float slide = -12f * l.s * (1f - Anim.POW3_OUT.apply(Anim.progress(t, start, 0.25f)));
            float cp = Anim.POW3_OUT.apply(Anim.progress(t, start, Choreo.ROWS_D));
            float base = l.rowBase[r];
            Color c;
            if (r == 0) c = Theme.RESONANCE;
            else if (r == 1) c = Theme.ECHO;
            else if (r == 2) c = Theme.LOST;
            else c = m.minesTouched > 0 ? Theme.LOST : Theme.INK3;
            q.text(Fonts.Role.UI_LABEL, l.rowLabelCap, LABELS[r], l.rowLabelX + slide, base, Align.left, c, a);
            if (r < 3) {
                q.digits(l.rowValueCap, Math.round(m.counts[r] * cp), true, l.rowCountRight, base, Align.right,
                    Theme.INK1, a);
            } else {
                q.fixed(l.rowValueCap, m.minesText, l.rowCountRight, base, Align.right, c, a);
            }
        }
        drawPerfect(m, l, t, alpha);
        drawChain(m, l, t, alpha);
        float ea = alpha * Anim.progress(t, Choreo.EL_T, Choreo.EL_D);
        if (ea > 0.002f) {
            float fit = earlyLateFit, x = l.earlyLateX, y = l.earlyLateBase;
            float lc = l.earlyLateLabelCap * fit, vc = l.earlyLateCap * fit;
            float g1 = 8f * l.earlyLateK * fit, g2 = 34f * l.earlyLateK * fit;
            x += q.text(Fonts.Role.UI_LABEL, lc, EARLY, x, y, Align.left, Theme.EARLY, ea) + g1;
            x += q.text(Fonts.Role.NUMBER, vc, m.earlyText, x, y, Align.left, Theme.INK1, ea) + g2;
            x += q.text(Fonts.Role.UI_LABEL, lc, LATE, x, y, Align.left, Theme.LATE, ea) + g1;
            x += q.text(Fonts.Role.NUMBER, vc, m.lateText, x, y, Align.left, Theme.INK1, ea) + g2;
            x += q.text(Fonts.Role.UI_LABEL, lc, AVG, x, y, Align.left, Theme.INK2, ea) + g1;
            q.text(Fonts.Role.NUMBER, vc, m.meanText, x, y, Align.left, Theme.INK1, ea);
        }
    }

    /** {@code PERFECT 1,580} under RESONANCE (DESIGN §18.2), {@code ink2}; rolls with that row. */
    private void drawPerfect(ResultModel m, ResultLayout l, float t, float alpha) {
        float start = Choreo.ROWS_T;
        if (t < start) return;
        float a = alpha * Anim.progress(t, start + 0.1f, 0.2f);
        float cp = Anim.POW3_OUT.apply(Anim.progress(t, start, Choreo.ROWS_D));
        StringBuilder sb = k.sb;
        sb.setLength(0);
        sb.append(m.perfectLabel).append(' ');
        Fonts.appendGrouped(sb, Math.round(m.perfect * cp));
        k.text.text(Fonts.Role.UI_LABEL, l.perfectCap, sb, l.rowLabelX, l.perfectBase, Align.left, Theme.INK2, a);
    }

    /** @return the width of the PERFECT sub-count text (the tap target of its EARLY / LATE popover). */
    public float perfectWidth(ResultModel m, ResultLayout l) {
        StringBuilder sb = k.sb;
        sb.setLength(0);
        sb.append(m.perfectLabel).append(' ');
        Fonts.appendGrouped(sb, m.perfect);
        return k.text.width(Fonts.Role.UI_LABEL, l.perfectCap, sb);
    }

    /**
     * RESONANCE CHAIN and MAX COMBO (DESIGN §18.2) on one line: the chain's bar above it in the RESONANCE colour, the
     * combo's under it in the ECHO purple, each label (in its bar's colour) with the value after it, and the note
     * count at the line's right end. Starts after the count rows.
     */
    private void drawChain(ResultModel m, ResultLayout l, float t, float alpha) {
        float start = Choreo.ROWS_T + 4 * Choreo.ROWS_STAGGER;
        if (t < start) return;
        TextQueue q = k.text;
        float s = l.chainK;
        float a = alpha * Anim.progress(t, start, 0.15f);
        float slide = -12f * s * (1f - Anim.POW3_OUT.apply(Anim.progress(t, start, 0.25f)));
        float cp = Anim.POW3_OUT.apply(Anim.progress(t, start, Choreo.ROWS_D));
        float x0 = l.chainX0, w = l.chainX1 - l.chainX0, ly = l.chainLineY, bh = l.chainBarH, in = 1.5f * s;
        float total = Math.max(1, m.totalNotes);
        float wc = w * Math.min(1f, m.maxChain / total) * cp, wm = w * Math.min(1f, m.maxCombo / total) * cp;
        if (wc > 0.5f) k.draw.rect(x0, ly + in, wc, bh - in, Theme.RESONANCE, a);
        if (wm > 0.5f) k.draw.rect(x0, ly - bh, wm, bh - in, Theme.ECHO, a);
        k.draw.rect(x0, ly - in * 0.5f, w, in, CHAIN_LINE, a);
        float lw = q.text(Fonts.Role.UI_LABEL, l.chainLabelCap, CHAIN_LABEL, x0 + slide, l.chainBase, Align.left,
            Theme.RESONANCE, a);
        q.digits(l.chainValueCap, Math.round(m.maxChain * cp), true, x0 + slide + lw + 12f * s, l.chainBase,
            Align.left, Theme.INK1, a);
        lw = q.text(Fonts.Role.UI_LABEL, l.chainLabelCap, COMBO_LABEL, x0 + slide, l.comboBase, Align.left, Theme.ECHO,
            a);
        q.digits(l.chainValueCap, Math.round(m.maxCombo * cp), true, x0 + slide + lw + 12f * s, l.comboBase,
            Align.left, Theme.INK1, a);
        q.text(Fonts.Role.UI, 14f * s, m.notesText, l.chainX1, ly + 8f * s, Align.right, Theme.INK3, a);
    }

    /**
     * Draws the AUTOPLAY pill of an autoplay run where NEW BEST would be (round 8 「AUTOPLAY 标签保留，原样式」), left at
     * {@code autoX}, its bottom on the best line's: the round-7 badge's look in the AUTO colour. It expands from its
     * centre (300 ms {@code pow3Out}), the text decodes outward, then a shine sweeps across (500 ms).
     */
    public void drawAutoplay(ResultModel m, ResultLayout l, float t, float alpha) {
        if (!m.autoplay || t < Choreo.BADGE_T) return;
        float h = l.autoH, cap = l.autoCap, f = h / 40f;
        float full = Math.max(l.autoW, k.text.width(Fonts.Role.UI_LABEL, cap, AUTOPLAY) + 48f * f);
        float p = Anim.POW3_OUT.apply(Anim.progress(t, Choreo.BADGE_T, 0.3f));
        float w = Math.max(h, full * p);
        float cx = l.autoX + full * 0.5f, cy = l.autoY;
        float x = cx - w * 0.5f, y = cy - h * 0.5f;
        float a = alpha * Math.min(1f, Anim.progress(t, Choreo.BADGE_T, 0.1f));
        k.draw.pill(x, y, w, h, AUTO_FILL, a);
        k.draw.pill(x, y, w, h, Theme.AUTO, 0.10f * a);
        k.draw.roundRectStroke(x, y, w, h, h * 0.5f, 1.5f, Theme.AUTO, 0.8f * a);
        // text decodes outward from the centre
        float el = t - Choreo.BADGE_T - 0.12f;
        if (el >= 0f) {
            StringBuilder sb = k.sb;
            decodeOutward(sb, AUTOPLAY, el, 0.024f);
            k.text.text(Fonts.Role.UI_LABEL, cap, sb, cx, cy, Align.center | Fonts.VCENTER, Theme.AUTO, a);
        }
        // shine
        float sp = (t - Choreo.BADGE_T - 0.2f) / 0.5f;
        if (sp >= 0f && sp <= 1f) {
            float sx = x - 40f * f + (w + 80f * f) * Anim.POW2_OUT.apply(sp);
            float half = 30f * f;
            float lo = Math.max(x + h * 0.3f, sx - half), hi = Math.min(x + w - h * 0.3f, sx + half);
            if (hi > lo) {
                float mid = Math.max(lo, Math.min(hi, sx));
                k.additive();
                float gy = y + 3f * f, gh = h - 6f * f;
                if (mid > lo) k.draw.rectGradientH(lo, gy, mid - lo, gh, CLEAR, Theme.WHITE, 0.45f * a);
                if (hi > mid) k.draw.rectGradientH(mid, gy, hi - mid, gh, Theme.WHITE, CLEAR, 0.45f * a);
                k.normal();
            }
        }
    }

    /**
     * Writes {@code text} into {@code out} with characters revealed from the centre outward (one
     * {@code stagger} per step), unrevealed ones shown as random decode glyphs for 2 frames and
     * as spaces before that. No allocation.
     */
    static void decodeOutward(StringBuilder out, CharSequence text, float elapsed, float stagger) {
        out.setLength(0);
        int n = text.length();
        float mid = (n - 1) * 0.5f;
        int tick = (int) Math.floor(elapsed * 60f);
        String pool = "#%&0123456789ABCDEF";
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            float reveal = Math.abs(i - mid) * stagger;
            if (c == ' ' || elapsed >= reveal + 2f / 60f) {
                out.append(c);
            } else if (elapsed >= reveal) {
                out.append(pool.charAt((int) (Anim.hash01(i * 13 + 5, tick) * pool.length()) % pool.length()));
            } else {
                out.append(' ');
            }
        }
    }
}
