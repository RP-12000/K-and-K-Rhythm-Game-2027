package org.kelvinizer.screens.result;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Theme;

/**
 * 结算画面的分数：各位数字先乱跳，再从左到右依次锁定，锁定时闪一下强调色；分数落定时变亮并带一次 G2 故障。
 * 随后在分数下方显示与最高分对比的一行（NEW BEST 和差值、BEST、FIRST DIVE 等）。
 *
 * <p>The score decode and the best line (DESIGN §6.2, §6.3).
 *
 * <ul>
 *   <li>0.60–1.72: all cells scramble (random digits at 30 Hz, {@code ink3}) and lock left to
 *       right every 0.125 s; a lock flashes {@code ink1 → accent → ink1} over 120 ms with a small
 *       pop; leading zeros lock dim.</li>
 *   <li>1.80 (land): brightness 1 → 1.6 → 1 over 180 ms (additive redraw) and a G2 burst on the
 *       fixed cells: face swap NUMBER → DISPLAY → DISPLAY_SOFT, split, 1–3 sub-UV slices and tear
 *       bars. The camera kick is the screen's.</li>
 *   <li>2.70: the best line (NEW BEST pop + counted diff + pulsing gold border, BEST in
 *       {@code ink3}, FIRST DIVE), a pill above the score since round 7 (Kelvin 2026-10-19
 *       06:28); an autoplay run shows the AUTOPLAY pill there instead (round 8, {@link StatsBlock}).</li>
 * </ul>
 *
 * Everything is a pure function of the timeline time; no allocation. Outside the land burst the
 * cells are deferred into the score page's group of the {@link TextQueue}.
 */
public final class ScorePanel implements TextQueue.Deferred {
    private static final float LAND_D = 0.18f;
    private static final float BURST_D = 0.12f;
    private static final Fonts.Role[] FACES = {Fonts.Role.NUMBER, Fonts.Role.DISPLAY, Fonts.Role.DISPLAY_SOFT};

    private final ResultKit k;
    private final float[] lo = new float[3], hi = new float[3], dx = new float[3];
    private final Color cell = new Color();

    /** Creates the panel over the shared kit. */
    public ScorePanel(ResultKit kit) {
        this.k = kit;
    }

    /** Total width of the 11 score cells (9 digits, 2 commas) at {@code cap}. */
    public float scoreWidth(ResultModel m, float cap) {
        float w = 0f;
        for (int i = 0; i < ResultModel.SCORE_CELLS; i++) {
            w += k.fonts.cellWidth(Fonts.Role.NUMBER, cap, m.scoreChars[i]);
        }
        return w;
    }

    /** Left x of the score row (left-aligned over the jacket since round 7). */
    public float scoreLeft(ResultModel m, ResultLayout l) {
        return l.scoreX;
    }

    /**
     * Draws the score at timeline time {@code t}.
     *
     * @param accent lock flash colour (the layer accent)
     */
    public void drawScore(ResultModel m, ResultLayout l, float t, Color accent, float alpha) {
        if (t < Choreo.SCORE_T) return;
        Batch b = k.batch;
        float cap = l.scoreCap;
        float left = scoreLeft(m, l);
        float base = l.scoreBase;
        float appear = ResultKit.clamp01((t - Choreo.SCORE_T) / 0.08f) * alpha;
        float I = GlitchText.intensity();
        boolean reduce = GlitchText.reduceFlashing();

        float u = t - Choreo.LAND_T;
        boolean burst = u >= 0f && u < BURST_D;
        Fonts.Role face = Fonts.Role.NUMBER;
        int bands = 0;
        float split = 0f;
        float dip = 1f;
        int h = 0;
        if (burst) {
            float bt = u / BURST_D;
            if (I <= 0.001f) {
                dip = 1f - 0.4f * (float) Math.sin(bt * Math.PI);
            } else {
                int idx = Math.min(3, (int) (bt * 4f));
                face = idx < 3 ? FACES[idx] : Fonts.Role.NUMBER;
                float sizeK = Math.max(1f, cap / 60f);
                split = 6f * I * sizeK;
                h = 0x5C0E * 31 + (int) Math.floor(t * 24f);
                int n = 1 + (int) (Anim.hash01(h, 1) * 2.999f);
                for (int i = 0; i < n; i++) {
                    float bh = cap * (0.12f + 0.18f * Anim.hash01(h, 20 + i));
                    float third = cap / 3f;
                    float y0 = base + third * i + (third - Math.min(bh, third)) * Anim.hash01(h, 30 + i);
                    lo[bands] = y0;
                    hi[bands] = y0 + Math.min(bh, third);
                    float mag = (4f + 20f * Anim.hash01(h, 40 + i)) * I * sizeK * 0.6f;
                    dx[bands] = Anim.hash01(h, 50 + i) < 0.5f ? -mag : mag;
                    bands++;
                }
            }
        }
        BitmapFont font = k.fonts.get(face, cap, Fonts.GlyphSet.DIGITS);
        boolean landing = u >= 0f && u < LAND_D;
        if (!burst && !landing && k.text.isDeferring()) {
            // decode and rest: one page, drawn with the other text after the frame's shapes
            dm = m;
            dLeft = left;
            dBase = base;
            dCap = cap;
            dT = t;
            passAlpha = appear;
            passAccent = accent;
            k.text.deferred(font.getRegion().getTexture(), this, 0);
            return;
        }
        float old = b.getPackedColor();
        if (split > 0f) {
            float ca = 0.35f * appear;
            b.setColor(Theme.GLITCH_C.r, Theme.GLITCH_C.g, Theme.GLITCH_C.b, ca);
            cells(m, font, left - split, base, cap, t, bands, false);
            b.setColor(Theme.GLITCH_M.r, Theme.GLITCH_M.g, Theme.GLITCH_M.b, ca);
            cells(m, font, left + split, base, cap, t, bands, false);
        }
        // main pass: per-cell colours
        this.passAlpha = appear * dip;
        this.passAccent = accent;
        cells(m, font, left, base, cap, t, bands, true);
        // tear bars over the score box
        if (split > 0f && !reduce) {
            float w = scoreWidth(m, cap);
            float sizeK = Math.max(1f, cap / 60f);
            int bars = 1 + (int) (Anim.hash01(h, 70) * 2.999f);
            for (int i = 0; i < bars; i++) {
                float bh = (4f + 10f * Anim.hash01(h, 80 + i)) * sizeK * 0.8f;
                float by = base - cap * 0.1f + (cap * 1.2f - bh) * Anim.hash01(h, 90 + i);
                float bw = (w + 48f) * (0.35f + 0.65f * Anim.hash01(h, 110 + i));
                float bx = left - 24f + (w + 48f - bw) * Anim.hash01(h, 120 + i)
                    + (Anim.hash01(h, 100 + i) * 2f - 1f) * 24f;
                Color c = (i & 1) == 0 ? Theme.GLITCH_C : Theme.GLITCH_M;
                k.draw.rect(bx, by, bw, bh, c, 0.25f * appear);
            }
        }
        // land brightness 1 → 1.6 → 1: an additive redraw of the locked digits
        if (u >= 0f && u < LAND_D) {
            float glow = 0.6f * (float) Math.sin(Math.PI * u / LAND_D);
            if (reduce) glow = Math.min(glow, 0.10f);
            k.additive();
            b.setColor(1f, 1f, 1f, glow * appear);
            cells(m, font, left, base, cap, t, 0, false);
            k.normal();
        }
        b.setPackedColor(old);
    }

    private float passAlpha;
    private Color passAccent;
    private ResultModel dm;
    private float dLeft, dBase, dCap, dT;

    /** Deferred score cells (decode and rest), drawn with the score page. */
    @Override
    public void drawDeferred(int arg) {
        BitmapFont font = k.fonts.get(Fonts.Role.NUMBER, dCap, Fonts.GlyphSet.DIGITS);
        float old = k.batch.getPackedColor();
        cells(dm, font, dLeft, dBase, dCap, dT, 0, true);
        k.batch.setPackedColor(old);
    }

    /**
     * Draws the 11 cells; {@code colored} applies the per-cell lock colours, else the batch
     * colour is kept (copies and glow passes).
     */
    private void cells(ResultModel m, BitmapFont font, float left, float base, float cap, float t, int bands,
                       boolean colored) {
        Batch b = k.batch;
        float x = left;
        for (int i = 0; i < ResultModel.SCORE_CELLS; i++) {
            char c = m.scoreChars[i];
            float w = k.fonts.cellWidth(Fonts.Role.NUMBER, cap, c);
            int j = m.scoreLockIndex[i];
            float lockT = Choreo.SCORE_T + (j + 1) * Choreo.SCORE_LOCK;
            boolean locked = t >= lockT;
            boolean lead = m.scoreLead[i];
            char shown = c;
            float scale = 1f;
            if (!locked) {
                if (c != ',') {
                    int tick = (int) Math.floor(t * 30f);
                    shown = (char) ('0' + (int) (Anim.hash01(i * 7 + 3, tick) * 9.999f));
                }
                if (colored) cell.set(Theme.INK3);
            } else if (colored) {
                float p = (t - lockT) / 0.12f;
                if (lead) {
                    cell.set(Theme.LEADING_ZERO);
                } else if (p < 1f) {
                    float f = (float) Math.sin(Math.PI * p);
                    Theme.mix(Theme.INK1, passAccent, f, cell);
                    scale = 1f + 0.10f * f;
                } else {
                    cell.set(Theme.INK1);
                }
            }
            if (colored) b.setColor(cell.r, cell.g, cell.b, cell.a * passAlpha);
            k.quads.cell(b, font, shown, x, w, base, cap, scale, lo, hi, dx, bands);
            x += w;
        }
    }

    // ------------------------------------------------------------------ best line
    /** Height of the NEW BEST pill the sizes below were drawn for (the layout's pill is {@code bestH}). */
    private static final float PILL_H0 = 46f;

    /**
     * Draws the best line at timeline time {@code t} ({@link Choreo#BEST_T}), its pill's top-left at
     * ({@code bestX}, {@code bestTop}) over the jacket.
     *
     * @param accent FIRST DIVE colour
     */
    public void drawBest(ResultModel m, ResultLayout l, float t, Color accent, float alpha) {
        if (m.bestKind == ResultModel.BestKind.NONE || t < Choreo.BEST_T) return;
        float x = l.bestX, bh = l.bestH, by = l.bestTop - bh;
        TextQueue q = k.text;
        switch (m.bestKind) {
            case NEW_BEST: {
                // the earlier 46-unit pill scaled to bestH
                float f = bh / PILL_H0;
                float p = Anim.progress(t, Choreo.BEST_T, Anim.POP);
                float s = Math.max(0.001f, Anim.POP_EASE.apply(p));
                float a = alpha * Math.min(1f, p * 4f);
                float cap = 30f * f, numCap = 26f * f, padX = 16f * f;
                float wWord = q.width(Fonts.Role.RUBIK, cap, "NEW BEST");
                float cp = Anim.ease(Anim.POW3_OUT, t, Choreo.BEST_T, 0.5f);
                StringBuilder sb = k.sb;
                sb.setLength(0);
                sb.append('+');
                // exact: a 100 M-scale diff passes 2^24, where a float count steps by 4 or more
                Fonts.appendGrouped(sb, Anim.count(0L, m.firstPlay ? 0L : m.bestDiff, cp));
                boolean showDiff = m.previousBest > 0;
                float wNum = showDiff ? q.fixedWidth(numCap, m.bestDiffText) : 0f;
                float gap = showDiff ? 18f * f : 0f;
                float bw = wWord + gap + wNum + 2f * padX;
                float base = by + 10f * f, left = x + padX;
                float cx = x + bw * 0.5f, cy = by + bh * 0.5f;
                boolean px = k.pushIf(cx, cy, 0f, s, s);
                float dx = k.ox - cx, dy = k.oy - cy;
                float pulse = t >= Choreo.BEST_T + Anim.POP
                    ? 0.5f + 0.5f * (float) Math.sin((t - Choreo.BEST_T - Anim.POP) * Math.PI * 2f / 1.2f) : 1f;
                k.draw.roundRect(x + dx, by + dy, bw, bh, 12f * f, Theme.GOLD, 0.07f * a);
                k.draw.roundRectStroke(x + dx, by + dy, bw, bh, 12f * f, Math.max(1.5f, 2f * f), Theme.GOLD,
                    (0.30f + 0.50f * pulse) * a);
                q.text(Fonts.Role.RUBIK, cap, "NEW BEST", left + dx, base + dy, Align.left, Theme.GOLD, a);
                if (showDiff) q.fixed(numCap, sb, left + wWord + gap + dx, base + dy, Align.left, Theme.GOLD, a);
                k.popIf(px);
                break;
            }
            case BEST: {
                float a = alpha * Anim.progress(t, Choreo.BEST_T, 0.25f);
                float cap = bh * 0.5f, base = by + (bh - cap) * 0.5f;
                float g0 = 10f * l.s, g1 = 16f * l.s;
                float wLabel = q.width(Fonts.Role.UI, cap, "BEST");
                float wBest = q.fixedWidth(cap, m.bestText);
                q.text(Fonts.Role.UI, cap, "BEST", x, base, Align.left, Theme.INK3, a);
                q.fixed(cap, m.bestText, x + wLabel + g0, base, Align.left, Theme.INK3, a);
                q.fixed(cap, m.bestDiffText, x + wLabel + g0 + wBest + g1, base, Align.left, Theme.INK3, a);
                break;
            }
            case FIRST_DIVE: {
                float p = Anim.progress(t, Choreo.BEST_T, Anim.POP);
                float s = Math.max(0.001f, Anim.POP_EASE.apply(p));
                float a = alpha * Math.min(1f, p * 4f);
                float cap = bh * 0.5f, padX = 12f * l.s;
                float w = q.width(Fonts.Role.UI_LABEL, cap, "FIRST DIVE");
                float bw = w + 2f * padX;
                float cx = x + bw * 0.5f, cy = by + bh * 0.5f;
                boolean px = k.pushIf(cx, cy, 0f, s, s);
                float ox = k.ox, oy = k.oy;
                k.draw.roundRect(ox - bw * 0.5f, oy - bh * 0.5f, bw, bh, 8f * l.s, accent, 0.10f * a);
                k.draw.roundRectStroke(ox - bw * 0.5f, oy - bh * 0.5f, bw, bh, 8f * l.s, 1.5f, accent, 0.6f * a);
                q.text(Fonts.Role.UI_LABEL, cap, "FIRST DIVE", ox - w * 0.5f, oy - cap * 0.5f, Align.left, accent, a);
                k.popIf(px);
                break;
            }
            case AUTO:
                // the AUTOPLAY pill has this place (round 8)
            default:
                break;
        }
    }
}
