package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.profile.Depth;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Panel;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;
import space.earlygrey.shapedrawer.ShapeDrawer;

import java.util.Arrays;

/**
 * 玩家档案的 STATS 标签页（Kelvin 2026-10-17 14:24 改版，14:41、14:47 和 15:25 再改）：第一排是四个难度开关，只写难度名
 * （SURFACE / SHALLOW / DEEP / ABYSS，可以同时点亮几个看合计，至少亮一个，只剩一个亮着时点它不会灭）；
 * 第二排是点亮的难度合计：通过（D 及以上）的谱面数和这些难度一共几张谱（13/31）、FULL COMBO 和 ALL RESONANCE 的谱面数；下面一直到屏幕底部是这些谱的
 * 最高分直方图，样子和结算画面的判定时间分布图一样（每 100 万分一格，上下对称，96M 及以上青色、以下紫色，
 * 扫线扫过时升起，μ 是平均分）。游玩次数和时长挪到了左栏，下潜曲线挪到了 TOP 10 页。
 *
 * <p>STATS (DESIGN §23.8; Kelvin 2026-10-17 14:24, 14:41, 14:47, 15:25). Row one: four difficulty
 * toggles showing only the name (in the difficulty colour, with a lit lamp when on); any combination
 * can be lit but at least one: tapping the last lit toggle only shakes it. All four start lit. Row
 * two, summed over the lit difficulties with a stripe of their colours: CHARTS CLEARED (grade D or
 * better) out of their charts ({@code 13/31}; at 15:25 the toggles' {@code 6/9 PLAYED} gave way to it),
 * FULL COMBOS and ALL RESONANCE. Below, down to the bottom of the tab, BEST SCORES: the lit
 * difficulties' best scores as a histogram drawn like the result screen's {@code EchoTrace}: 41
 * mirrored bins of 1,000,000 from 60,000,000 (lower scores in the first, R in the last),
 * {@code RESONANCE} cyan from {@link Depth#BONUS_FROM} (where a chart counts exactly 40 × c²: lower
 * scores take from the constant, higher ones add; marked by a 2 px line) and {@code ECHO} purple below, ticks
 * 60M … 100M and the mean caret {@code μ 94.27M}. The bins rise as a sonar sweep crosses after a show or a toggle; an
 * idle sweep repeats every 4 s. PLAYS and PLAY TIME are in {@link ProfileHeader}, the dive profile in {@link TopTab}.
 * Values follow the live (counting) {@link DiverStats}.
 */
public final class StatsTab extends ProfileTab {
    private static final float TOGGLE_H = 112f, TILE_H = 140f, GAP = 24f, SECTION = 64f, MIN_TRACE_H = 300f;
    /** Tile labels, English in both languages since Kelvin 2026-10-18 14:29 (ALL RESONANCE reads RESONATED). */
    private static final CharSequence[] TILE_LABELS = {"CHARTS CLEARED", "FULL COMBOS", "RESONATED"};
    private static final CharSequence BEST = Lang.of("BEST SCORES", "最高分");
    /**
     * Score trace sweep: starts this long after a show or a toggle, crosses in {@code SWEEP_D}; idle sweeps every 4 s.
     */
    private static final float SWEEP_T = 0.3f, SWEEP_D = 0.8f, IDLE_PERIOD = 4f;
    /** Tallest bin counts at least this many charts, so a few charts do not fill the trace. */
    private static final int MIN_PEAK = 3;
    /** Ticks under the trace, with their labels (96M is the bonus line). */
    private static final int[] TICK_SCORE = {60_000_000, 70_000_000, 80_000_000, 90_000_000, Depth.BONUS_FROM,
        100_000_000};
    private static final String[] TICK_TEXT = {"60M", "70M", "80M", "90M", "96M", "100M"};

    private DiverStats stats;
    /** Lit difficulties (by ordinal): all four at first, kept while the app runs. */
    private final boolean[] lit = {true, true, true, true};
    private int litCount = DiverStats.DIFFS;
    /** Seconds since the last toggle (row two fades in, the trace sweeps again). */
    private float sinceSwitch = 10f;
    private final Tappable[] diffTaps = new Tappable[DiverStats.DIFFS];
    private final StringBuilder[] values = new StringBuilder[3];
    /** {@code /31}: the lit difficulties' charts, after the cleared count. */
    private final StringBuilder ofTotal = new StringBuilder(8);
    private final StringBuilder line = new StringBuilder(32);
    /** The lit difficulties' bins, summed each frame. */
    private final int[] bins = new int[DiverStats.SCORE_BINS];
    private float traceX0, binW;

    /** Creates the tab; the difficulty toggles go into the screen's {@code widgets}. */
    public StatsTab(Main game, ShellScreen host, WidgetSet widgets) {
        super(game, host);
        for (int i = 0; i < values.length; i++) values[i] = new StringBuilder(16);
        for (int d = 0; d < DiverStats.DIFFS; d++) {
            final int diff = d;
            Tappable t = widgets.add(new Tappable(draw));
            t.setClip(scroll);
            t.setOnTap(new Runnable() {
                @Override
                public void run() {
                    toggle(diff);
                }
            });
            t.setVisible(false);
            diffTaps[d] = t;
        }
    }

    /** Lights or darkens difficulty {@code d}; the last lit one stays lit and shakes. */
    private void toggle(int d) {
        if (lit[d] && litCount == 1) {
            diffTaps[d].shake();
            return;
        }
        lit[d] = !lit[d];
        litCount += lit[d] ? 1 : -1;
        sinceSwitch = 0f;
    }

    @Override
    public void refresh(Profile p, DiverStats s) {
        stats = s;
    }

    @Override
    public void update(float dt) {
        sinceSwitch += dt;
        super.update(dt);
    }

    @Override
    protected void onVisibility(boolean on) {
        for (Tappable t : diffTaps) t.setVisible(on);
    }

    @Override
    protected void place(float top) {
        float tw = toggleW();
        for (int d = 0; d < DiverStats.DIFFS; d++) {
            diffTaps[d].setBounds(x + d * (tw + GAP), top - TOGGLE_H, tw, TOGGLE_H);
        }
    }

    private float toggleW() {
        return (w - 3f * GAP) / DiverStats.DIFFS;
    }

    /** Height above the trace panel. */
    private static float rowsH() {
        return TOGGLE_H + GAP + TILE_H + SECTION + 12f;
    }

    /** The trace panel reaches the bottom of the tab (Kelvin 14:47), never shorter than {@link #MIN_TRACE_H}. */
    private float traceH() {
        return Math.max(MIN_TRACE_H, h - rowsH());
    }

    @Override
    protected float contentHeight() {
        return rowsH() + traceH();
    }

    private void buildValues() {
        DiverStats s = stats;
        float cleared = 0f, fc = 0f, ar = 0f;
        int total = 0;
        for (int d = 0; d < DiverStats.DIFFS; d++) {
            if (!lit[d]) continue;
            total += s.diffTotal[d];
            cleared += s.cleared[d];
            fc += s.fullCombos[d];
            ar += s.allResonance[d];
        }
        for (StringBuilder sb : values) sb.setLength(0);
        Fonts.appendGrouped(values[0], Math.round(cleared));
        Fonts.appendGrouped(values[1], Math.round(fc));
        Fonts.appendGrouped(values[2], Math.round(ar));
        ofTotal.setLength(0);
        Fonts.appendGrouped(ofTotal.append('/'), total);
    }

    @Override
    protected void drawContent(Batch b, float top, float alpha) {
        if (stats == null) return;
        buildValues();
        float fade = Anim.ease(Anim.POW3_OUT, sinceSwitch, 0f, Anim.SHORT);
        float tw = toggleW(), cw = (w - 2f * GAP) / 3f;
        // every card first, then every text (one atlas / font switch, not one per tile)
        for (int pass = 0; pass < 2; pass++) {
            for (int d = 0; d < DiverStats.DIFFS; d++) {
                float e = rowIn(d);
                drawToggle(b, pass, d, x + d * (tw + GAP) + diffTaps[d].shakeX(), top - TOGGLE_H - 16f * (1f - e), tw,
                    alpha * e);
            }
            for (int i = 0; i < 3; i++) {
                float e = rowIn(DiverStats.DIFFS + i);
                float a = alpha * e;
                float tx = x + i * (cw + GAP);
                float ty = top - TOGGLE_H - GAP - TILE_H - 16f * (1f - e);
                if (pass == 0) {
                    Panel.card(draw, tx, ty, cw, TILE_H, a);
                    drawStripe(tx + 10f, ty + 22f, TILE_H - 44f, a);
                    continue;
                }
                float vw = fonts.drawFixed(b, Fonts.Role.NUMBER, 36f, values[i], tx + 28f, ty + TILE_H - 40f,
                    Align.left | Fonts.VCENTER, Theme.INK1, a * fade);
                if (i == 0) {
                    fonts.drawFixed(b, Fonts.Role.NUMBER, 36f, ofTotal, tx + 28f + vw, ty + TILE_H - 40f,
                        Align.left | Fonts.VCENTER, Theme.INK3, a * fade);
                }
                fonts.draw(b, Fonts.Role.UI_LABEL, 18f, TILE_LABELS[i], tx + 28f, ty + 28f, Align.left, Theme.INK2, a);
            }
        }
        float cy = top - rowsH() + 12f;
        float ba = alpha * rowIn(DiverStats.DIFFS + 3);
        Panel.header(b, draw, fonts, BEST, x, cy + 18f, x + w, ba);
        drawTrace(b, cy - 12f, traceH(), ba);
    }

    /**
     * One difficulty toggle, the name only: lit = tinted card with a border, name in its colour and a lamp;
     * dark = plain.
     */
    private void drawToggle(Batch b, int pass, int d, float tx, float ty, float tw, float a) {
        Tappable t = diffTaps[d];
        boolean on = lit[d];
        Color c = Theme.difficultyAccent(d);
        float s = t.visualScale();
        float sw = tw * s, sh = TOGGLE_H * s, sx = tx + (tw - sw) * 0.5f, sy = ty + (TOGGLE_H - sh) * 0.5f;
        if (pass == 0) {
            Panel.card(draw, sx, sy, sw, sh, a);
            float hover = t.hoverAmount();
            if (on) {
                draw.roundRect(sx, sy, sw, sh, Panel.RADIUS, c, (0.10f + 0.06f * hover) * a);
                draw.roundRectStroke(sx, sy, sw, sh, Panel.RADIUS, 2f, c, 0.85f * a);
            } else if (hover > 0f) {
                draw.roundRectStroke(sx, sy, sw, sh, Panel.RADIUS, 2f, c, 0.35f * hover * a);
            }
            t.drawPress(c, a);
            float lx = sx + sw - 30f, ly = sy + sh * 0.5f;
            if (on) {
                draw.glowDot(lx, ly, 36f, c, 0.5f * a);
                draw.disc(lx, ly, 8f, c, a);
            } else {
                draw.circle(lx, ly, 8f, 2f, c, 0.5f * a);
            }
            return;
        }
        fonts.drawFit(b, Fonts.Role.UI_BOLD, 30f, Theme.DIFFICULTY_NAMES[d], sx + 24f, sy + sh * 0.5f,
            Align.left | Fonts.VCENTER, sw - 84f, 0.6f, on ? c : Theme.INK3, a);
    }

    /** Row two's stripe: one segment per lit difficulty, in its colour. */
    private void drawStripe(float sx, float sy, float sh, float a) {
        float seg = (sh - 4f * (litCount - 1)) / litCount;
        float top = sy + sh;
        for (int d = 0; d < DiverStats.DIFFS; d++) {
            if (!lit[d]) continue;
            draw.roundRect(sx, top - seg, 4f, seg, 2f, Theme.difficultyAccent(d), a);
            top -= seg + 4f;
        }
    }

    private int firstLit() {
        for (int d = 0; d < DiverStats.DIFFS; d++) {
            if (lit[d]) return d;
        }
        return 0;
    }

    /** BEST SCORES: the lit difficulties' best scores as a trace like the result screen's echo trace. */
    private void drawTrace(Batch b, float top, float ch, float a) {
        float y0 = top - ch;
        draw.roundRect(x, y0, w, ch, 12f, Theme.PANEL, 0.6f * a);
        Arrays.fill(bins, 0);
        int n = 0;
        double sum = 0;
        for (int d = 0; d < DiverStats.DIFFS; d++) {
            if (!lit[d]) continue;
            n += stats.diffPlayed[d];
            sum += (double) stats.meanScore[d] * stats.diffPlayed[d];
            for (int i = 0; i < DiverStats.SCORE_BINS; i++) bins[i] += stats.scoreBins[d][i];
        }
        if (n == 0) {
            line.setLength(0);
            if (Lang.zh()) {
                line.append("还没有玩过");
                if (litCount == 1) line.append(' ').append(Theme.DIFFICULTY_NAMES[firstLit()]).append(' ');
                line.append("谱面");
            } else {
                line.append("NO ");
                if (litCount == 1) line.append(Theme.DIFFICULTY_NAMES[firstLit()]).append(' ');
                line.append("CHARTS PLAYED YET");
            }
            fonts.draw(b, Fonts.Role.UI_REGULAR, 20f, line, x + w * 0.5f, y0 + ch * 0.5f, Align.center | Fonts.VCENTER,
                Theme.INK3, a);
            return;
        }
        int peakCount = 0;
        for (int c : bins) peakCount = Math.max(peakCount, c);
        Color accent = litCount == 1 ? Theme.difficultyAccent(firstLit()) : Theme.INK1;
        float x0 = x + 48f, x1 = x + w - 48f, yTop = top - 70f, yBot = y0 + 62f;
        traceX0 = x0;
        binW = (x1 - x0) / DiverStats.SCORE_BINS;
        float mid = (yTop + yBot) * 0.5f, half = (yTop - yBot) * 0.5f - 4f;
        // a visible gap between bins: neighbouring single charts stay countable
        float px = Ui.worldPerPx(), gap = Math.max(px, binW * 0.12f);
        float t = Math.min(sinceSwitch, shown) - SWEEP_T;
        // baseline
        draw.rect(x0, mid - px * 0.5f, x1 - x0, px, Theme.INK1, 0.14f * a);
        // bins
        float peak = Math.max(MIN_PEAK, peakCount);
        float idleX = -1f;
        if (t >= IDLE_PERIOD && t % IDLE_PERIOD < SWEEP_D) idleX = x0 + (x1 - x0) * (t % IDLE_PERIOD) / SWEEP_D;
        for (int i = 0; i < DiverStats.SCORE_BINS; i++) {
            float pass = SWEEP_D * (i + 0.5f) / DiverStats.SCORE_BINS;
            float rise = t < pass ? 0f : Anim.SWING_OUT_16.apply(Anim.progress(t, pass, 0.2f));
            float hh = bins[i] > 0 ? Math.max(1.5f, half * bins[i] / peak) * rise : 0f;
            boolean bonus = DiverStats.BIN_FLOOR + i * DiverStats.BIN_WIDTH >= Depth.BONUS_FROM;
            Color c = bonus ? Theme.RESONANCE : Theme.ECHO;
            float bx = x0 + i * binW + gap * 0.5f;
            float ba = 0.9f;
            if (idleX >= 0f) {
                float d = Math.abs(bx + binW * 0.5f - idleX) / (binW * 2f);
                if (d < 1f) ba = Math.min(1f, 0.9f + 0.35f * (1f - d));
            }
            if (hh > 0f) draw.rect(bx, mid - hh, binW - gap, hh * 2f, c, ba * a);
            else if (rise > 0f) draw.rect(bx, mid - px, binW - gap, px * 2f, c, 0.25f * a);
        }
        // the bonus line (96M: from here a score adds depth), as the result screen's 0-ms line
        float lx = scoreX(Depth.BONUS_FROM);
        draw.rect(lx - 1f, yBot, 2f, yTop - yBot, Theme.INK1, 0.85f * a);
        // sweep lines
        float sweep = t / SWEEP_D;
        if (sweep >= 0f && sweep <= 1f) {
            float sx = x0 + (x1 - x0) * sweep;
            float sa = a * (sweep < 0.9f ? 1f : (1f - sweep) * 10f);
            draw.sprite(draw.glowV(), sx, mid, 60f, yTop - yBot + 40f, accent, 0.35f * sa);
            draw.rect(sx - 1f, yBot - 6f, 2f, yTop - yBot + 12f, accent, 0.95f * sa);
        }
        if (idleX >= 0f) {
            draw.sprite(draw.glowV(), idleX, mid, 50f, yTop - yBot + 30f, accent, 0.18f * a);
            draw.rect(idleX - 0.75f, yBot, 1.5f, yTop - yBot, accent, 0.45f * a);
        }
        for (int i = 0; i < TICK_SCORE.length; i++) {
            draw.rect(scoreX(TICK_SCORE[i]) - 0.5f, yBot - 8f, 1f, 8f, Theme.INK2, 0.6f * a);
        }
        // mean caret over the trace, pointing down
        float mean = (float) (sum / n);
        float ma = a * Anim.progress(t, SWEEP_D, 0.25f);
        float mx = Math.max(x0, Math.min(x1, scoreX(mean)));
        if (ma > 0.002f) {
            ShapeDrawer sd = draw.shapes();
            float old = sd.getPackedColor();
            sd.setColor(Theme.INK1.r, Theme.INK1.g, Theme.INK1.b, ma);
            sd.filledTriangle(mx - 9f, yTop + 17f, mx + 9f, yTop + 17f, mx, yTop + 4f);
            sd.setColor(old);
        }
        // text
        for (int i = 0; i < TICK_SCORE.length; i++) {
            boolean bonusLine = TICK_SCORE[i] == Depth.BONUS_FROM;
            fonts.draw(b, Fonts.Role.UI, 16f, TICK_TEXT[i], scoreX(TICK_SCORE[i]), yBot - 26f,
                Align.center | Fonts.VCENTER, bonusLine ? Theme.INK1 : Theme.INK3, a);
        }
        if (ma > 0.002f) {
            line.setLength(0);
            Fonts.appendTruncated(line, mean / 1_000_000.0, 2).append('M');
            float muW = fonts.width(Fonts.Role.UI, 16f, "μ");
            float total = muW + 5f + fonts.width(Fonts.Role.UI, 16f, line);
            float left = Math.max(x0, Math.min(x1 - total, mx - total * 0.5f));
            float base = yTop + 25f;
            fonts.draw(b, Fonts.Role.UI, 16f, "μ", left, base, Align.left, Theme.INK2, ma);
            fonts.draw(b, Fonts.Role.UI, 16f, line, left + muW + 5f, base, Align.left, Theme.INK1, ma);
        }
    }

    /** Trace x of {@code score} (bin edges every 1,000,000 from 60,000,000). */
    private float scoreX(float score) {
        return traceX0 + (score - DiverStats.BIN_FLOOR) / DiverStats.BIN_WIDTH * binW;
    }
}
