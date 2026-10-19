package org.kelvinizer.screens.result;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Panel;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 结算画面的判定时间分布图（ECHO TRACE）：
 * 把 ±135 ms 以内的命中偏差按 10 ms 一格画成上下对称的波形，共鸣窗口内为青色，其余为紫色。
 * 出场时声呐扫线从左扫到右、各格随之升起，然后显示平均偏差 μ；点击可展开，显示刻度和峰值。
 *
 * <p>The echo trace (DESIGN §6.2): the 27-bin hit-offset histogram (10 ms bins over −135..+135)
 * drawn as a mirrored waveform, RESONANCE-window bins in {@code #66F0FF} and the rest in
 * {@code #B49CFF}, a 2 px 0-ms line, EARLY / LATE end labels and the mean caret {@code μ +9 ms}.
 *
 * <p>1.10–1.90: a sonar sweep crosses left to right and each bin rises as it passes
 * ({@code SwingOut(1.6)}, 200 ms); then μ fades in. Idle: the sweep repeats every 4 s. Tapping
 * expands it (spring, P1) to show tick labels and the peak count. EARLY / LATE stay English in 中文; PEAK / MEAN
 * read 峰值 / 平均 (Kelvin 2026-10-18 20:34 「early late不必那，peak改为"峰值"，mean改为平均」).
 */
public final class EchoTrace {
    private static final int BINS = 27;
    /** Cap of the {@code μ +9 ms} label (decorative micro text, never the only carrier). */
    private static final float MEAN_CAP = 16f;
    private static final float IDLE_PERIOD = 4f;
    private static final String[] TICK_TEXT = {"-100", "-50", "0", "+50", "+100"};
    private static final int[] TICK_MS = {-100, -50, 0, 50, 100};
    private static final String PEAK_ZH = "峰值 ", MEAN_ZH = "  ·  平均 ";

    private final ResultKit k;
    private float ex0, ex1, ey0, ey1;

    /** Creates the trace renderer. */
    public EchoTrace(ResultKit kit) {
        this.k = kit;
    }

    /** Rasterises the 中文 words of the expanded line (峰值 / 平均) ahead of the first tap. */
    public void warm() {
        k.text.warm(Fonts.Role.UI_LABEL, 16f, PEAK_ZH + MEAN_ZH);
    }

    /** Extra height (downwards) when fully expanded, for the current layout. */
    public static float expandExtra(ResultLayout l) {
        float floor = l.stacked ? l.stripY + l.stripH + 40f : l.stripY + l.stripH + 44f;
        return Math.max(0f, Math.min(130f, l.traceY0 - floor - 30f));
    }

    /** Current box (after the last draw): x0, x1, y0, y1. */
    public boolean hit(float x, float y, float grow) {
        return x >= ex0 - grow && x <= ex1 + grow && y >= ey0 - grow && y <= ey1 + grow;
    }

    /**
     * Draws the trace.
     *
     * @param end    end of the choreography (idle sweeps start there)
     * @param expand expansion 0..1 (spring value)
     */
    public void draw(ResultModel m, ResultLayout l, float t, float end, float expand, Color accent, float alpha) {
        float x0 = l.traceX0, x1 = l.traceX1, y1 = l.traceY1;
        float ex = Math.max(0f, expand);
        float y0 = l.traceY0 - expandExtra(l) * ex;
        ex0 = x0;
        ex1 = x1;
        ey0 = y0;
        ey1 = y1;
        if (t < Choreo.TRACE_T - 0.15f) return;
        float a = alpha * Anim.progress(t, Choreo.TRACE_T - 0.15f, 0.2f);
        float w = x1 - x0, bw = w / BINS;
        // expanded: the top 22 of the box hold the PEAK / MEAN line, the bins shrink under it
        float top = y1 - 22f * Math.min(1f, ex);
        float mid = (y0 + top) * 0.5f;
        float half = (top - y0) * 0.5f - 4f;
        float px = Ui.worldPerPx();
        float gap = Math.max(1f, px);

        if (ex > 0.01f) {
            // 10 above the box (end labels sit inside it), 30 below for the tick labels
            Panel.card(k.draw, x0 - 16f, y0 - 30f * ex, w + 32f, y1 - y0 + 10f + 30f * ex, Math.min(1f, ex) * a);
        }
        // baseline
        k.draw.rect(x0, mid - px * 0.5f, w, px, Theme.INK1, 0.14f * a);
        // bins
        float peak = Math.max(1, m.histogramPeak);
        float sweep = (t - Choreo.TRACE_T) / Choreo.TRACE_D;
        float idleX = -1f;
        if (t >= end) {
            float ph = (t - end) % IDLE_PERIOD;
            if (ph < Choreo.TRACE_D) idleX = x0 + w * ph / Choreo.TRACE_D;
        }
        for (int i = 0; i < BINS; i++) {
            int count = m.histogram[i];
            float pass = Choreo.TRACE_T + Choreo.TRACE_D * (i + 0.5f) / BINS;
            float rise = Anim.SWING_OUT_16.apply(Anim.progress(t, pass, 0.2f));
            if (t < pass) rise = 0f;
            float hh = count > 0 ? Math.max(1.5f, half * count / peak) * rise : 0f;
            float centreMs = -130f + 10f * i;
            Color c = Math.abs(centreMs) < Judgement.TIER1_MS ? Theme.RESONANCE : Theme.ECHO;
            float bx = x0 + i * bw + gap * 0.5f;
            float ba = 0.9f;
            if (idleX >= 0f) {
                float d = Math.abs(bx + bw * 0.5f - idleX) / (bw * 2f);
                if (d < 1f) ba = Math.min(1f, 0.9f + 0.35f * (1f - d));
            }
            if (hh > 0f) k.draw.rect(bx, mid - hh, bw - gap, hh * 2f, c, ba * a);
            else if (rise > 0f) k.draw.rect(bx, mid - px, bw - gap, px * 2f, c, 0.25f * a);
        }
        // 0-ms line
        float zx = x0 + w * 0.5f;
        k.draw.rect(zx - 1f, y0, 2f, top - y0, Theme.INK1, 0.85f * a);
        // sweep line
        if (sweep >= 0f && sweep <= 1f) {
            float sx = x0 + w * sweep;
            float sa = a * (sweep < 0.9f ? 1f : (1f - sweep) * 10f);
            k.draw.sprite(k.draw.glowV(), sx, mid, 60f, y1 - y0 + 40f, accent, 0.35f * sa);
            k.draw.rect(sx - 1f, y0 - 6f, 2f, y1 - y0 + 12f, accent, 0.95f * sa);
        }
        if (idleX >= 0f) {
            k.draw.sprite(k.draw.glowV(), idleX, mid, 50f, y1 - y0 + 30f, accent, 0.18f * a);
            k.draw.rect(idleX - 0.75f, y0, 1.5f, y1 - y0, accent, 0.45f * a);
        }
        // mean caret (shape), expanded tick marks (shapes)
        float ma = a * Anim.progress(t, Choreo.TRACE_T + Choreo.TRACE_D, 0.25f);
        float labelA = ma * (1f - Math.min(1f, ex * 3f));
        boolean hasMean = ma > 0.002f && (m.counts[0] + m.counts[1]) > 0;
        float cx = Math.max(x0, Math.min(x1, zx + m.meanOffset / 10f * bw));
        float ty = y0 - 4f;
        if (hasMean) k.triangle(cx - 9f, ty - 13f, cx + 9f, ty - 13f, cx, ty, Theme.INK1, ma);
        float ta = a * Math.min(1f, (ex - 0.05f) * 2f);
        if (ex > 0.05f) {
            for (int i = 0; i < TICK_MS.length; i++) {
                float tx = zx + TICK_MS[i] / 10f * bw;
                k.draw.rect(tx - 0.5f, y0, 1f, 8f, Theme.INK2, 0.6f * ta);
            }
        }
        // text (page-grouped by the queue)
        TextQueue q = k.text;
        q.text(Fonts.Role.UI_LABEL, 16f, "EARLY", x0 + 4f, y1 - 2f, Align.left | Align.top, Theme.EARLY, 0.85f * a);
        q.text(Fonts.Role.UI_LABEL, 16f, "LATE", x1 - 4f, y1 - 2f, Align.right | Align.top, Theme.LATE, 0.85f * a);
        if (ex > 0.05f) {
            k.sb.setLength(0);
            k.sb.append(Lang.t("PEAK ", PEAK_ZH));
            Fonts.appendGrouped(k.sb, m.histogramPeak);
            k.sb.append(Lang.t("  ·  MEAN ", MEAN_ZH)).append(m.meanText);
            q.text(Fonts.Role.UI_LABEL, 16f, k.sb, zx, y1 - 2f, Align.center | Align.top, Theme.INK2, ta);
            for (int i = 0; i < TICK_MS.length; i++) {
                float tx = zx + TICK_MS[i] / 10f * bw;
                q.text(Fonts.Role.UI, 16f, TICK_TEXT[i], tx, y0 - 24f * ex, Align.center | Fonts.VCENTER, Theme.INK3, ta);
            }
        }
        if (hasMean && labelA > 0.002f) {
            float cap = MEAN_CAP;
            float muW = q.width(Fonts.Role.UI, cap, "μ");
            float total = muW + 5f + q.width(Fonts.Role.UI, cap, m.meanText);
            float left = Math.max(x0, Math.min(x1 - total, cx - total * 0.5f));
            float base = ty - 13f - 8f - cap;
            q.text(Fonts.Role.UI, cap, "μ", left, base, Align.left, Theme.INK2, labelA);
            q.text(Fonts.Role.UI, cap, m.meanText, left + muW + 5f, base, Align.left, Theme.INK1, labelA);
        }
    }

    /** Mean caret x for the current box (used by the hint layout). */
    public float meanX(ResultModel m) {
        float w = ex1 - ex0;
        return Math.max(ex0, Math.min(ex1, ex0 + w * 0.5f + m.meanOffset / 10f * (w / BINS)));
    }
}
