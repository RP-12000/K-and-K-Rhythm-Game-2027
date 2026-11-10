package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.TimeUtils;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Theme;
import space.earlygrey.shapedrawer.ShapeDrawer;

import java.util.Calendar;

/**
 * 下潜曲线 DIVE PROFILE：横轴是时间，带日期刻度；纵轴是深度，越往下越深。
 * 双指捏合（电脑上用滚轮）放大缩小、拖动平移，放大后刻度细到小时和分钟；点一个圆点，显示那次下潜的时间和之后的深度。
 *
 * <p>The dive profile on the DST 10 tab: the depth history ({@link DiverStats#history}, each point
 * stamped with its play time in {@link DiverStats#historyT}) on a time axis (Kelvin 2026-10-18 00:38:
 * 「Dive Profile横坐标要有日期」), depth going down, a polyline over a gradient fill with a dot per
 * play. Pinch, or the mouse wheel over the chart, zooms around the fingers / the cursor, a drag
 * pans, and a double tap shows everything again; the narrowest window is {@link #MIN_SPAN} (00:40:
 * 「中间可以放大查看具体时间点」). The axis picks its tick step from one minute to a year so labels
 * stay {@link #TICK_GAP} apart; below sub-day ticks the date is written under the first tick and
 * at every day change. A tap on a dot shows its date, time and depth in a callout. Ticks and the
 * callout are rebuilt only when the view, the selection or the language changes; nothing is
 * allocated per frame.
 */
public final class DiveChart {
    /** Narrowest window (ms). */
    public static final long MIN_SPAN = 10L * 60_000L;
    /** Minimum spacing of the axis labels (UI units). */
    public static final float TICK_GAP = 100f;
    /** Height of the axis band under the plot. */
    public static final float AXIS_H = 52f;
    private static final long MINUTE = 60_000L, HOUR = 60L * MINUTE, DAY = 24L * HOUR;
    private static final long[] STEPS = {MINUTE, 2 * MINUTE, 5 * MINUTE, 10 * MINUTE, 15 * MINUTE, 30 * MINUTE,
        HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, DAY, 2 * DAY, 7 * DAY, 14 * DAY};
    private static final int[] MONTH_STEPS = {1, 2, 3, 6, 12};
    private static final int MAX_TICKS = 32;
    private static final float SLOP = 12f, HIT = 40f, DOUBLE_TAP = 0.35f;
    private static final CharSequence EMPTY = Lang.of("DIVE A FEW CHARTS TO DRAW YOUR PROFILE",
        "多潜几张谱面，就能画出你的下潜曲线");
    private static final CharSequence DEEPER = Lang.of("DEEPER ↓", "更深 ↓");

    private final Draw draw;
    private final Fonts fonts;
    private final Color accent = new Color(), dot = new Color();
    private final Calendar cal = Calendar.getInstance();
    private final StringBuilder line = new StringBuilder(24), line2 = new StringBuilder(24);
    private final StringBuilder callout = new StringBuilder(40);
    private final double[] tickT = new double[MAX_TICKS];
    private final boolean[] tickDay = new boolean[MAX_TICKS];
    private final StringBuilder[] tickMain = new StringBuilder[MAX_TICKS];
    private final StringBuilder[] tickDate = new StringBuilder[MAX_TICKS];
    private int tickCount;
    private double tickA = Double.NaN, tickB = Double.NaN;
    private float tickW = -1f;
    private boolean tickZh;

    private DiverStats stats;
    private float x, y, w, h;
    // plot rect, derived from the panel rect in layout
    private float px0, pw, pTop, pBot;

    // view window (ms since epoch); follows the full range until the player zooms or pans
    private double viewA, viewB;
    private boolean following = true;

    // gesture state
    private int p0 = -1, p1 = -1;
    private float p0x, p0y, p1x, p1y, downX, downY, lastX;
    private boolean moved;
    private float pinchD0;
    private double pinchSpan0, pinchFocus;
    private float mouseX = -1f, mouseY = -1f;
    private long lastTapAt;
    private float lastTapX, lastTapY;

    // selection
    private int sel = -1;
    private boolean calloutZh;

    /** Creates the chart. */
    public DiveChart(Draw draw, Fonts fonts) {
        this.draw = draw;
        this.fonts = fonts;
        for (int i = 0; i < MAX_TICKS; i++) {
            tickMain[i] = new StringBuilder(8);
            tickDate[i] = new StringBuilder(10);
        }
    }

    /** Sets the panel rect (bottom-left origin); the plot's right edge is set again in {@link #draw}. */
    public void setBounds(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        px0 = x + 28f;
        pw = Math.max(1f, w - 56f);
        pTop = y + h - 18f;
        pBot = y + AXIS_H;
    }

    /** The history to draw (read every frame; the screen counts it on a diver switch). */
    public void setStats(DiverStats stats) {
        this.stats = stats;
    }

    /** Shows the whole history again and clears the selection (diver switched, tab shown). */
    public void reset() {
        following = true;
        sel = -1;
        cancel(-1);
    }

    private int count() {
        return stats != null ? stats.historyCount : 0;
    }

    /** True when (px, py) lies on the panel. */
    public boolean contains(float px, float py) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    // ------------------------------------------------------------------ view
    private double fullA, fullB;

    /** Recomputes the whole-history window and clamps the view into it. */
    private void updateRange() {
        int n = count();
        long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            lo = Math.min(lo, stats.historyT[i]);
            hi = Math.max(hi, stats.historyT[i]);
        }
        if (n == 0) {
            lo = 0L;
            hi = 0L;
        }
        double span = Math.max(hi - lo, MIN_SPAN);
        double mid = (lo + (double) hi) * 0.5;
        double pad = span * 0.04;
        fullA = mid - span * 0.5 - pad;
        fullB = mid + span * 0.5 + pad;
        if (following) {
            viewA = fullA;
            viewB = fullB;
        } else {
            clampView();
        }
    }

    private void clampView() {
        double full = fullB - fullA;
        double span = Math.max(Math.min(viewB - viewA, full), Math.min(MIN_SPAN, full));
        double a = Math.max(fullA, Math.min(viewA, fullB - span));
        viewA = a;
        viewB = a + span;
        if (span >= full - 1.0) following = true;
    }

    private float xOf(double t) {
        return px0 + (float) ((t - viewA) / (viewB - viewA)) * pw;
    }

    private double tOf(float px) {
        return viewA + (px - px0) / pw * (viewB - viewA);
    }

    /** Zooms by {@code factor} (> 1 = out) keeping time {@code at} under screen x {@code px}. */
    private void zoom(double factor, double at, float px) {
        double span = (viewB - viewA) * factor;
        viewA = at - (px - px0) / pw * span;
        viewB = viewA + span;
        following = false;
        clampView();
    }

    // ------------------------------------------------------------------ input
    /** Touch down; true when the chart takes the pointer. */
    public boolean touchDown(float px, float py, int pointer) {
        if (count() < 2 || !contains(px, py)) return false;
        if (p0 < 0) {
            p0 = pointer;
            p0x = downX = lastX = px;
            p0y = downY = py;
            moved = false;
        } else if (p1 < 0 && pointer != p0) {
            p1 = pointer;
            p1x = px;
            p1y = py;
            beginPinch();
        }
        return true;
    }

    private void beginPinch() {
        pinchD0 = Math.max(8f, dist());
        pinchSpan0 = viewB - viewA;
        pinchFocus = tOf((p0x + p1x) * 0.5f);
        moved = true;
    }

    private float dist() {
        float dx = p1x - p0x, dy = p1y - p0y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Touch drag; true when it moved a chart pointer. */
    public boolean touchDragged(float px, float py, int pointer) {
        if (pointer == p0) {
            p0x = px;
            p0y = py;
        } else if (pointer == p1) {
            p1x = px;
            p1y = py;
        } else {
            return false;
        }
        if (p0 >= 0 && p1 >= 0) {
            double span = pinchSpan0 * pinchD0 / Math.max(8f, dist());
            float mid = (p0x + p1x) * 0.5f;
            viewA = pinchFocus - (mid - px0) / pw * span;
            viewB = viewA + span;
            following = false;
            clampView();
            return true;
        }
        if (!moved && Math.abs(px - downX) + Math.abs(py - downY) > SLOP) moved = true;
        if (moved) {
            double shift = (lastX - px) / pw * (viewB - viewA);
            viewA += shift;
            viewB += shift;
            following = false;
            clampView();
        }
        lastX = px;
        return true;
    }

    /** Touch up; true when it released a chart pointer (a still single tap selects a dot). */
    public boolean touchUp(float px, float py, int pointer) {
        if (pointer == p1) {
            p1 = -1;
            lastX = p0x;
            return true;
        }
        if (pointer != p0) return false;
        boolean tap = !moved && p1 < 0;
        if (p1 >= 0) {
            // the second finger stays: it pans from here
            p0 = p1;
            p0x = p1x;
            p0y = p1y;
            lastX = p1x;
            p1 = -1;
        } else {
            p0 = -1;
        }
        if (tap) tap(px, py);
        return true;
    }

    /** Drops {@code pointer} (−1 = every pointer). */
    public void cancel(int pointer) {
        if (pointer < 0 || pointer == p0 || pointer == p1) {
            p0 = -1;
            p1 = -1;
        }
    }

    /** Desktop hover position (for the wheel). */
    public void mouseMoved(float px, float py) {
        mouseX = px;
        mouseY = py;
    }

    /** Mouse wheel; zooms around the cursor while it is over the chart. */
    public boolean scrolled(float amountY) {
        if (count() < 2 || !contains(mouseX, mouseY) || amountY == 0f) return false;
        updateRange();
        float mx = Math.max(px0, Math.min(px0 + pw, mouseX));
        zoom(Math.pow(1.25, amountY), tOf(mx), mx);
        return true;
    }

    private void tap(float px, float py) {
        long now = TimeUtils.millis();
        boolean twice = now - lastTapAt < DOUBLE_TAP * 1000f && Math.abs(px - lastTapX) < HIT
            && Math.abs(py - lastTapY) < HIT;
        lastTapAt = twice ? 0L : now;
        lastTapX = px;
        lastTapY = py;
        if (twice) {
            following = true;
            return;
        }
        int best = -1;
        float bestD = HIT * HIT;
        int n = count();
        for (int i = 0; i < n; i++) {
            float dotX = xOf(stats.historyT[i]);
            if (dotX < left() || dotX > right()) continue;
            float dx = dotX - px, dy = yOf(stats.history[i]) - py;
            float d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        sel = best == sel ? -1 : best;
        calloutZh = !Lang.zh();
    }

    // ------------------------------------------------------------------ drawing
    private float minM, rangeM;

    private float yOf(float m) {
        return pTop - (pTop - pBot) * (m - minM) / rangeM;
    }

    // the curve and its dots stop at the depth labels' column
    private float left() {
        return x + 3f;
    }

    private float right() {
        return px0 + pw + 8f;
    }

    /** Draws the panel, the axis, the curve and the callout. */
    public void draw(Batch b, float a) {
        draw.roundRect(x, y, w, h, 12f, Theme.PANEL, 0.6f * a);
        int n = count();
        if (n < 2) {
            fonts.draw(b, Fonts.Role.UI_REGULAR, 20f, EMPTY, x + w * 0.5f, y + h * 0.5f, Align.center | Fonts.VCENTER,
                Theme.INK3, a);
            return;
        }
        updateRange();
        float min = Float.MAX_VALUE, max = 0f;
        for (int i = 0; i < n; i++) {
            min = Math.min(min, stats.history[i]);
            max = Math.max(max, stats.history[i]);
        }
        float range = Math.max(20f, max - min);
        min = Math.max(0f, min - range * 0.1f);
        max = max + range * 0.1f;
        minM = min;
        rangeM = max - min;
        line.setLength(0);
        ShellFormat.appendDepth(line, (long) min);
        line2.setLength(0);
        ShellFormat.appendDepth(line2, (long) max);
        // the depth labels get their own column, so the newest dots never sit under them
        float labelW = Math.max(fonts.width(Fonts.Role.NUMBER, 16f, line), fonts.width(Fonts.Role.NUMBER, 16f, line2));
        pw = Math.max(1f, x + w - 16f - labelW - 24f - px0);
        Theme.depthAccent(stats.depth, accent);
        drawAxis(b, a);
        drawCurve(a);
        if (sel >= n) sel = -1;
        if (sel >= 0) drawSelection(b, a);
        fonts.draw(b, Fonts.Role.NUMBER, 16f, line, x + w - 16f, y + h - 10f, Align.right | Align.top, Theme.INK3, a);
        fonts.draw(b, Fonts.Role.NUMBER, 16f, line2, x + w - 16f, pBot + 8f, Align.right, Theme.INK3, a);
        fonts.draw(b, Fonts.Role.UI_LABEL, 14f, DEEPER, x + 16f, pBot + 8f, Align.left, Theme.INK3, a);
    }

    private void drawCurve(float a) {
        ShapeDrawer sd = draw.shapes();
        float left = left(), right = right();
        float cTop = Color.toFloatBits(accent.r, accent.g, accent.b, 0.03f * a);
        float cLine = Color.toFloatBits(accent.r, accent.g, accent.b, 0.30f * a);
        int n = count();
        for (int i = 1; i < n; i++) {
            float x1 = xOf(stats.historyT[i - 1]), x2 = xOf(stats.historyT[i]);
            float y1 = yOf(stats.history[i - 1]), y2 = yOf(stats.history[i]);
            if (x2 < x1) {
                float t = x1;
                x1 = x2;
                x2 = t;
                t = y1;
                y1 = y2;
                y2 = t;
            }
            if (x2 < left || x1 > right) continue;
            // clip the segment to the panel so a deep zoom never sends vertices far off screen
            if (x1 < left) {
                y1 = y1 + (y2 - y1) * (left - x1) / Math.max(1e-3f, x2 - x1);
                x1 = left;
            }
            if (x2 > right) {
                y2 = y1 + (y2 - y1) * (right - x1) / Math.max(1e-3f, x2 - x1);
                x2 = right;
            }
            sd.filledTriangle(x1, pTop, x2, pTop, x1, y1, cTop, cTop, cLine);
            sd.filledTriangle(x2, pTop, x2, y2, x1, y1, cTop, cLine, cLine);
            draw.line(x1, y1, x2, y2, 3f, accent, a);
        }
        dot.set(accent);
        for (int i = 0; i < n; i++) {
            float px = xOf(stats.historyT[i]);
            if (px < left || px > right) continue;
            float py = yOf(stats.history[i]);
            if (i == n - 1) {
                draw.glowDot(px, py, 40f, accent, a);
                draw.disc(px, py, 5f, Theme.WHITE, a);
            } else {
                draw.disc(px, py, 4f, dot, 0.9f * a);
            }
        }
    }

    private void drawSelection(Batch b, float a) {
        float px = xOf(stats.historyT[sel]), py = yOf(stats.history[sel]);
        if (px < left() || px > right()) return;
        draw.rect(px - 1f, pBot, 2f, pTop - pBot, Theme.INK1, 0.25f * a);
        draw.disc(px, py, 9f, Theme.WHITE, 0.25f * a);
        draw.disc(px, py, 5f, Theme.WHITE, a);
        if (calloutZh != Lang.zh()) buildCallout();
        float tw = fonts.width(Fonts.Role.UI, 16f, callout) + 28f, th = 34f;
        // the callout stays left of the depth labels' column
        float cx = Math.max(x + 8f + tw * 0.5f, Math.min(right() - tw * 0.5f, px));
        float cy = py + 30f + th * 0.5f <= pTop + 10f ? py + 30f : py - 30f;
        draw.pill(cx - tw * 0.5f, cy - th * 0.5f, tw, th, Theme.SEAM, 0.92f * a);
        draw.roundRectStroke(cx - tw * 0.5f, cy - th * 0.5f, tw, th, th * 0.5f, 1.5f, accent, 0.8f * a);
        fonts.draw(b, Fonts.Role.UI, 16f, callout, cx, cy, Align.center | Fonts.VCENTER, Theme.INK1, a);
    }

    /** {@code OCT 17 · 14:32 · -2,560 m} ({@code 10月17日 14:32 · -2,560 m} in Chinese). */
    private void buildCallout() {
        calloutZh = Lang.zh();
        cal.setTimeInMillis(stats.historyT[sel]);
        callout.setLength(0);
        ShellFormat.appendDate(callout, cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        callout.append(calloutZh ? " " : " · ");
        appendTime(callout, cal);
        callout.append(" · ");
        ShellFormat.appendDepth(callout, (long) stats.history[sel]);
    }

    private static void appendTime(StringBuilder sb, Calendar c) {
        int hh = c.get(Calendar.HOUR_OF_DAY), mm = c.get(Calendar.MINUTE);
        if (hh < 10) sb.append('0');
        Fonts.appendInt(sb, hh).append(':');
        if (mm < 10) sb.append('0');
        Fonts.appendInt(sb, mm);
    }

    // ------------------------------------------------------------------ axis
    private void drawAxis(Batch b, float a) {
        if (viewA != tickA || viewB != tickB || pw != tickW || tickZh != Lang.zh()) buildTicks();
        float timeY = y + AXIS_H - 20f, dateY = y + 13f;
        for (int i = 0; i < tickCount; i++) {
            float tx = xOf(tickT[i]);
            draw.rect(tx - 0.75f, pBot, 1.5f, pTop - pBot, Theme.INK3, 0.10f * a);
            draw.rect(tx - 0.75f, pBot - 6f, 1.5f, 6f, Theme.INK3, 0.6f * a);
            CharSequence main = tickMain[i];
            float hw = fonts.width(Fonts.Role.UI_LABEL, 14f, main) * 0.5f;
            float lx = Math.max(x + 6f + hw, Math.min(x + w - 6f - hw, tx));
            fonts.draw(b, Fonts.Role.UI_LABEL, 14f, main, lx, timeY, Align.center | Fonts.VCENTER,
                tickDay[i] ? Theme.INK2 : Theme.INK3, a);
            if (tickDate[i].length() > 0) {
                CharSequence date = tickDate[i];
                float dw = fonts.width(Fonts.Role.UI_REGULAR, 14f, date) * 0.5f;
                float dx = Math.max(x + 6f + dw, Math.min(x + w - 6f - dw, tx));
                fonts.draw(b, Fonts.Role.UI_REGULAR, 14f, date, dx, dateY, Align.center | Fonts.VCENTER, Theme.INK2,
                    a);
            }
        }
        draw.rect(x + 12f, pBot - 0.5f, w - 24f, 1f, Theme.STROKE, a);
    }

    /** Picks the tick step for the view and writes the labels (only when the view changed). */
    private void buildTicks() {
        tickA = viewA;
        tickB = viewB;
        tickW = pw;
        tickZh = Lang.zh();
        tickCount = 0;
        double span = viewB - viewA;
        double perPx = span / pw;
        long step = 0L;
        for (long s : STEPS) {
            if (s / perPx >= TICK_GAP) {
                step = s;
                break;
            }
        }
        if (step > 0L && step < DAY) {
            buildClockTicks(step);
        } else if (step > 0L) {
            buildDayTicks(step);
        } else {
            int months = 12;
            for (int m : MONTH_STEPS) {
                if (m * 30.4 * DAY / perPx >= TICK_GAP) {
                    months = m;
                    break;
                }
            }
            buildMonthTicks(months);
        }
    }

    /** Ticks every {@code step} (under a day) on local clock time; the date goes under day changes. */
    private void buildClockTicks(long step) {
        long a = (long) Math.ceil(viewA), off = cal.getTimeZone().getOffset(a);
        long t = ((a + off + step - 1) / step) * step - off;
        int lastDay = -1;
        while (t <= viewB && tickCount < MAX_TICKS) {
            if (xOf(t) >= px0 - 1f && xOf(t) <= px0 + pw + 1f) {
                cal.setTimeInMillis(t);
                int day = cal.get(Calendar.YEAR) * 400 + cal.get(Calendar.DAY_OF_YEAR);
                StringBuilder main = tickMain[tickCount], date = tickDate[tickCount];
                main.setLength(0);
                date.setLength(0);
                appendTime(main, cal);
                if (day != lastDay) {
                    ShellFormat.appendDate(date, cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
                }
                tickDay[tickCount] = cal.get(Calendar.HOUR_OF_DAY) == 0 && cal.get(Calendar.MINUTE) == 0;
                tickT[tickCount++] = t;
                lastDay = day;
            }
            t += step;
        }
    }

    /** Ticks at local midnights every {@code step} days; the label is the date. */
    private void buildDayTicks(long step) {
        int days = (int) (step / DAY);
        cal.setTimeInMillis((long) viewA);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        // align multi-day steps to a fixed grid of days so ticks do not jump while panning
        int guard = 0;
        while (days > 1 && (cal.getTimeInMillis() / DAY) % days != 0 && guard++ < 2 * days) {
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        guard = 0;
        while (cal.getTimeInMillis() <= viewB && tickCount < MAX_TICKS && guard++ < 400) {
            long t = cal.getTimeInMillis();
            if (t >= viewA && xOf(t) <= px0 + pw + 1f && xOf(t) >= px0 - 1f) {
                StringBuilder main = tickMain[tickCount];
                main.setLength(0);
                tickDate[tickCount].setLength(0);
                ShellFormat.appendDate(main, cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
                tickDay[tickCount] = true;
                tickT[tickCount++] = t;
            }
            cal.add(Calendar.DAY_OF_MONTH, days);
        }
    }

    /** Ticks at the first of every {@code months}-th month; the label is the month. */
    private void buildMonthTicks(int months) {
        cal.setTimeInMillis((long) viewA);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        int guard = 0;
        while (cal.getTimeInMillis() <= viewB && tickCount < MAX_TICKS && guard++ < 400) {
            long t = cal.getTimeInMillis();
            if (t >= viewA && cal.get(Calendar.MONTH) % months == 0) {
                StringBuilder main = tickMain[tickCount];
                main.setLength(0);
                tickDate[tickCount].setLength(0);
                ShellFormat.appendDate(main, cal.get(Calendar.MONTH), 0);
                tickDay[tickCount] = true;
                tickT[tickCount++] = t;
            }
            cal.add(Calendar.MONTH, 1);
        }
    }
}
