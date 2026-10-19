package org.kelvinizer.screens.result;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;

/**
 * 结算画面的判定统计：共鸣、回响、迷失各行的数量和进度条（有水雷时加 MINES 行），
 * 以及 PERFECT 小计、共鸣连击和 EARLY / LATE 行。
 * 还有准确率和最大连击两块数据卡，以及通关标记（CLEAR、FULL COMBO、ALL RESONANCE 等）。
 *
 * <p>Judgement rows (+ the MINES row of DESIGN §5.15), the PERFECT sub-count under RESONANCE and
 * the RESONANCE CHAIN row (scoring v2, DESIGN §18.2), the early / late line, the accuracy and max
 * combo tiles and the clear badge (DESIGN §6.2, §6.3). The labels are English in both languages (Kelvin
 * 2026-10-18 16:45 「结算界面的英文也全部保留」; 中文 drew Chinese subtitles until 03:1x, then Chinese labels).
 */
public final class StatsBlock {
    private static final String[] LABELS = {"RESONANCE", "ECHO", "LOST", "MINES"};
    private static final Color CLEAR = new Color(1f, 1f, 1f, 0f);

    private final ResultKit k;
    private final Color tmp = new Color();
    private float accCap = 30f, comboCap = 30f;
    private boolean comboTotalFits;
    /** Row label cap. */
    private static final float LABEL_CAP = 22f;
    /** Cap of the {@code / 1,693} combo total (secondary text minimum). */
    private static final float TOTAL_CAP = 18f;
    /** RESONANCE CHAIN row label (DESIGN §18.2). */
    private static final String CHAIN_LABEL = "RESONANCE CHAIN";
    /** Cap of the PERFECT sub-count (DESIGN §18.2: UI 18, ink2). */
    private static final float PERFECT_CAP = 18f;
    /** Chain row label cap (22, shrunk to fit beside the value, never below 16). */
    private float chainLabelCap = 22f;

    /** Creates the block. */
    public StatsBlock(ResultKit kit) {
        this.k = kit;
    }

    /** Fits the tile values and the chain row label for the current layout. */
    public void measure(ResultModel m, ResultLayout l) {
        TextQueue q = k.text;
        float inner = l.tileW - 36f;
        float wAcc = q.fixedWidth(30f, m.accuracyText);
        accCap = wAcc > inner ? 30f * inner / wAcc : 30f;
        k.sb.setLength(0);
        Fonts.appendGrouped(k.sb, m.maxCombo);
        float wCombo = q.fixedWidth(30f, k.sb);
        comboCap = wCombo > inner ? 30f * inner / wCombo : 30f;
        float wTotal = q.fixedWidth(TOTAL_CAP, m.comboTotalText);
        comboTotalFits = wCombo * comboCap / 30f + 10f + wTotal <= inner;
        // chain row: label left, "m_r / x" right-aligned at the bar end
        k.sb.setLength(0);
        Fonts.appendGrouped(k.sb, m.maxChain);
        float valueW = q.fixedWidth(30f, k.sb) + 10f + q.fixedWidth(TOTAL_CAP, m.chainTotalText);
        float labelW = q.width(Fonts.Role.UI_LABEL, 22f, CHAIN_LABEL);
        float room = l.barX1 - valueW - 24f - l.rowLabelX;
        chainLabelCap = Math.max(16f, Math.min(22f, 22f * room / Math.max(1f, labelW)));
    }

    /**
     * Draws the judgement rows and the early / late line (bars first; the text joins the page
     * groups of the {@link TextQueue}).
     *
     * @param elHide 0..1: fades the early / late line out (the expanded echo trace covers it)
     */
    public void drawRows(ResultModel m, ResultLayout l, float t, float alpha, float elHide) {
        TextQueue q = k.text;
        int rows = m.hasMines ? 4 : 3;
        int total = Math.max(1, m.totalNotes);
        float barW = l.barX1 - l.barX0;
        for (int r = 0; r < rows; r++) {
            float start = Choreo.ROWS_T + r * Choreo.ROWS_STAGGER;
            if (t < start) continue;
            float a = alpha * Anim.progress(t, start, 0.15f);
            float slide = -12f * (1f - Anim.POW3_OUT.apply(Anim.progress(t, start, 0.25f)));
            float cp = Anim.POW3_OUT.apply(Anim.progress(t, start, Choreo.ROWS_D));
            float base = l.rowBase[r];
            Color c;
            if (r == 0) c = Theme.RESONANCE;
            else if (r == 1) c = Theme.ECHO;
            else if (r == 2) c = Theme.LOST;
            else c = m.minesTouched > 0 ? Theme.MINE : Theme.INK3;
            if (r < 3) {
                int n = m.counts[r];
                float by = base + 11f - 5f;
                k.draw.roundRect(l.barX0, by, barW, ResultLayout.BAR_H, 5f, Theme.WHITE, 0.07f * a);
                float fw = barW * Math.min(1f, n / (float) total) * cp;
                if (n > 0) fw = Math.max(fw, 6f * cp);
                if (fw > 0.5f) {
                    Theme.withAlpha(c, 0.55f, tmp);
                    k.draw.rectGradientH(l.barX0 + 2f, by, Math.max(0f, fw - 2f), ResultLayout.BAR_H, tmp, c, a);
                    k.draw.roundRect(l.barX0, by, Math.min(fw, 10f), ResultLayout.BAR_H, 5f, tmp, a);
                    k.draw.glowDot(l.barX0 + fw, by + 5f, 26f, c, 0.45f * a * (1f - cp * 0.6f));
                }
            }
            q.text(Fonts.Role.UI_LABEL, LABEL_CAP, LABELS[r], l.rowLabelX + slide, base, Align.left, c, a);
            if (r == 3) {
                q.text(Fonts.Role.UI_LABEL, 18f, m.minesTouched > 0 ? "TOUCHED" : "NONE TOUCHED", l.barX0, base,
                    Align.left, Theme.INK3, a);
            }
            if (r < 3) {
                q.digits(30f, Math.round(m.counts[r] * cp), true, l.rowCountRight, base, Align.right, Theme.INK1, a);
            } else {
                q.fixed(30f, m.minesText, l.rowCountRight, base, Align.right, c, a);
            }
        }
        drawPerfect(m, l, t, alpha);
        drawChain(m, l, t, alpha, rows);
        // early / late line (fades while the expanded trace card covers it)
        float ea = alpha * Anim.progress(t, Choreo.EL_T, Choreo.EL_D) * (1f - ResultKit.clamp01(elHide));
        if (ea > 0.002f) {
            float x = l.earlyLateX, y = l.earlyLateBase;
            float cap = 20f;
            x += q.text(Fonts.Role.UI, cap, "EARLY", x, y, Align.left, Theme.EARLY, ea) + 8f;
            x += q.text(Fonts.Role.UI, cap, m.earlyText, x, y, Align.left, Theme.INK1, ea) + 14f;
            x += q.text(Fonts.Role.UI, cap, "·", x, y, Align.left, Theme.INK3, ea) + 14f;
            x += q.text(Fonts.Role.UI, cap, "LATE", x, y, Align.left, Theme.LATE, ea) + 8f;
            x += q.text(Fonts.Role.UI, cap, m.lateText, x, y, Align.left, Theme.INK1, ea) + 14f;
            x += q.text(Fonts.Role.UI, cap, "·", x, y, Align.left, Theme.INK3, ea) + 14f;
            x += q.text(Fonts.Role.UI, cap, "AVG", x, y, Align.left, Theme.INK2, ea) + 8f;
            q.text(Fonts.Role.UI, cap, m.meanText, x, y, Align.left, Theme.INK1, ea);
        }
    }

    /**
     * The PERFECT sub-count (DESIGN §18.2): {@code PERFECT 1,580},
     * UI 18 {@code ink2}, right-aligned under the RESONANCE count; rolls with that row.
     */
    private void drawPerfect(ResultModel m, ResultLayout l, float t, float alpha) {
        float start = Choreo.ROWS_T;
        if (t < start) return;
        float a = alpha * Anim.progress(t, start + 0.1f, 0.2f);
        float cp = Anim.POW3_OUT.apply(Anim.progress(t, start, Choreo.ROWS_D));
        StringBuilder sb = k.sb;
        sb.setLength(0);
        sb.append(m.perfectLabel).append("  ");
        Fonts.appendGrouped(sb, Math.round(m.perfect * cp));
        k.text.text(Fonts.Role.UI, PERFECT_CAP, sb, l.rowCountRight, l.perfectBase, Align.right, Theme.INK2, a);
    }

    /** @return the width of the PERFECT sub-count text (the tap target of its EARLY / LATE popover). */
    public float perfectWidth(ResultModel m) {
        StringBuilder sb = k.sb;
        sb.setLength(0);
        sb.append(m.perfectLabel).append("  ");
        Fonts.appendGrouped(sb, m.perfect);
        return k.text.width(Fonts.Role.UI, PERFECT_CAP, sb);
    }

    /** @return the cap height of the PERFECT sub-count text. */
    public static float perfectCap() {
        return PERFECT_CAP;
    }

    /**
     * The RESONANCE CHAIN row (DESIGN §18.2): label in the RESONANCE colour,
     * {@code m_r / x} right-aligned at the bar end, and under them a 3 px bar of {@code m_r / x}
     * in the RESONANCE colour, the MAX COMBO tile's bar treatment. Starts after the count rows.
     */
    private void drawChain(ResultModel m, ResultLayout l, float t, float alpha, int rows) {
        float start = Choreo.ROWS_T + rows * Choreo.ROWS_STAGGER;
        if (t < start) return;
        TextQueue q = k.text;
        float a = alpha * Anim.progress(t, start, 0.15f);
        float slide = -12f * (1f - Anim.POW3_OUT.apply(Anim.progress(t, start, 0.25f)));
        float cp = Anim.POW3_OUT.apply(Anim.progress(t, start, Choreo.ROWS_D));
        float x0 = l.rowLabelX, x1 = l.barX1, base = l.chainBase;
        float barW = x1 - x0;
        k.draw.rect(x0, l.chainBarY, barW, 3f, Theme.WHITE, 0.08f * a);
        float frac = m.totalNotes > 0 ? Math.min(1f, m.maxChain / (float) m.totalNotes) : 0f;
        Color bar = m.autoplay ? Theme.INK2 : Theme.RESONANCE;
        k.draw.rect(x0, l.chainBarY, barW * frac * cp, 3f, bar, 0.9f * a);
        q.text(Fonts.Role.UI_LABEL, chainLabelCap, CHAIN_LABEL, x0 + slide, base, Align.left, Theme.RESONANCE, a);
        float tw = q.fixed(TOTAL_CAP, m.chainTotalText, x1, base, Align.right, Theme.INK3, a);
        q.digits(30f, Math.round(m.maxChain * cp), true, x1 - tw - 10f, base, Align.right, Theme.INK1, a);
    }

    /** Draws the accuracy and max combo tiles (shapes now, text into the page groups). */
    public void drawTiles(ResultModel m, ResultLayout l, float t, float alpha) {
        for (int i = 0; i < 2; i++) {
            float start = Choreo.TILES_T + i * Choreo.TILES_STAGGER;
            if (t < start) continue;
            float p = Anim.progress(t, start, Anim.POP);
            float s = Math.max(0.001f, Anim.POP_EASE.apply(p));
            float cx = l.tileX[i] + l.tileW * 0.5f, cy = l.tileY[i] + l.tileH * 0.5f;
            boolean px = k.pushIf(cx, cy, 0f, s, s);
            drawTile(m, l, i, t, alpha * Math.min(1f, p * 4f));
            k.popIf(px);
        }
    }

    /** One tile around the local origin {@code (k.ox, k.oy)}. */
    private void drawTile(ResultModel m, ResultLayout l, int i, float t, float a) {
        TextQueue q = k.text;
        float start = Choreo.TILES_T + i * Choreo.TILES_STAGGER;
        float cp = Anim.POW3_OUT.apply(Anim.progress(t, start, Choreo.TILES_D));
        float w = l.tileW, h = l.tileH;
        float x0 = k.ox - w * 0.5f, y0 = k.oy - h * 0.5f;
        float vb = y0 + 34f;
        float barY = y0 + 14f, barW = w - 36f;
        // autoplay is not an achievement: neutral values, no all-resonance / full-combo tints
        Color c;
        if (i == 0) c = m.allResonance && !m.autoplay ? Theme.ALL_RESONANCE : Theme.INK1;
        else c = m.fullCombo && !m.autoplay ? Theme.FULL_COMBO : Theme.INK1;
        k.draw.panel(x0, y0, w, h, 16f, a);
        k.draw.rect(x0 + 18f, barY, barW, 3f, Theme.WHITE, 0.08f * a);
        if (i == 0) {
            Color bar = m.autoplay ? Theme.INK2 : (m.allResonance ? Theme.ALL_RESONANCE : Theme.RESONANCE);
            k.draw.rect(x0 + 18f, barY, barW * m.accuracy * cp, 3f, bar, 0.9f * a);
        } else {
            float frac = m.totalNotes > 0 ? Math.min(1f, m.maxCombo / (float) m.totalNotes) : 0f;
            Color bar = m.fullCombo && !m.autoplay ? Theme.FULL_COMBO : Theme.INK2;
            k.draw.rect(x0 + 18f, barY, barW * frac * cp, 3f, bar, 0.9f * a);
        }
        q.textFit(Fonts.Role.UI_LABEL, 18f, i == 0 ? "ACCURACY" : "MAX COMBO", x0 + 18f, y0 + h - 16f,
            Align.left | Align.top, w - 36f, 0.8f, Theme.INK3, a);
        if (i == 0) {
            StringBuilder sb = k.sb;
            sb.setLength(0);
            ResultModel.appendPercent(sb, Math.round(m.accuracyHundredths * cp));
            q.fixed(accCap, sb, x0 + 18f, vb, Align.left, c, a);
        } else {
            float vw = q.digits(comboCap, Math.round(m.maxCombo * cp), true, x0 + 18f, vb, Align.left, c, a);
            if (comboTotalFits) {
                q.fixed(TOTAL_CAP, m.comboTotalText, x0 + 18f + vw + 10f, vb, Align.left, Theme.INK3, a);
            }
        }
    }

    /**
     * Draws the clear badge: the pill expands from its centre (300 ms {@code pow3Out}), the text
     * decodes outward, then a shine sweeps across (500 ms).
     */
    public void drawBadge(ResultModel m, ResultLayout l, float t, float alpha) {
        if (t < Choreo.BADGE_T) return;
        float p = Anim.POW3_OUT.apply(Anim.progress(t, Choreo.BADGE_T, 0.3f));
        float w = Math.max(l.badgeH, l.badgeW * p), h = l.badgeH;
        float x = l.badgeX - w * 0.5f, y = l.badgeY - h * 0.5f;
        float a = alpha * Math.min(1f, Anim.progress(t, Choreo.BADGE_T, 0.1f));
        Color fill, stroke, text;
        float fillA;
        switch (m.badge) {
            case ALL_RESONANCE:
                fill = Theme.ALL_RESONANCE;
                stroke = Theme.ALL_RESONANCE;
                text = Theme.INK1;
                fillA = 0.24f;
                break;
            case FULL_COMBO:
                fill = Theme.FULL_COMBO;
                stroke = Theme.FULL_COMBO;
                text = Theme.FULL_COMBO;
                fillA = 0.16f;
                break;
            case AUTO:
                fill = Theme.AUTO;
                stroke = Theme.AUTO;
                text = Theme.AUTO;
                fillA = 0.10f;
                break;
            case CLEAR:
            default:
                fill = Theme.PANEL_HI;
                stroke = Theme.STROKE;
                text = Theme.INK2;
                fillA = 0.9f;
                break;
        }
        k.draw.pill(x, y, w, h, fill, fillA * a);
        k.draw.roundRectStroke(x, y, w, h, h * 0.5f, 2f, stroke, 0.8f * a);
        // text decodes outward from the centre
        float el = t - Choreo.BADGE_T - 0.12f;
        if (el >= 0f) {
            StringBuilder sb = k.sb;
            decodeOutward(sb, m.badgeText, el, 0.024f);
            k.text.text(Fonts.Role.UI_BOLD, 22f, sb, l.badgeX, l.badgeY, Align.center | Fonts.VCENTER, text, a);
        }
        // shine
        float sp = (t - Choreo.BADGE_T - 0.2f) / 0.5f;
        if (sp >= 0f && sp <= 1f) {
            float sx = x - 40f + (w + 80f) * Anim.POW2_OUT.apply(sp);
            float half = 34f;
            float lo = Math.max(x + h * 0.3f, sx - half), hi = Math.min(x + w - h * 0.3f, sx + half);
            if (hi > lo) {
                float mid = Math.max(lo, Math.min(hi, sx));
                k.additive();
                if (mid > lo) k.draw.rectGradientH(lo, y + 3f, mid - lo, h - 6f, CLEAR, Theme.WHITE, 0.45f * a);
                if (hi > mid) k.draw.rectGradientH(mid, y + 3f, hi - mid, h - 6f, Theme.WHITE, CLEAR, 0.45f * a);
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
            if (c == ' ' || elapsed >= reveal + 2f / 60f) out.append(c);
            else if (elapsed >= reveal) out.append(pool.charAt((int) (Anim.hash01(i * 13 + 5, tick) * pool.length()) % pool.length()));
            else out.append(' ');
        }
    }
}
