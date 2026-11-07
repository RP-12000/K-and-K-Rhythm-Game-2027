package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 旧谱面（v5 及更早，按轨道写的键）换成每个键自己的横坐标（从屏幕中线量起，左负右正）：x = 轨道中心 + 横移；键一样大，暂不做大小键。
 * 轨道不动时完全精确；插入、删除或改宽轨道的那几拍按 1/8 拍采样。全局缩放和镜头平移不算进去，游戏照旧按判定线的缩放和镜头摆。
 *
 * <p>Places the notes of a lane chart (format v5 and older) across the screen, for the laneless game (chart format
 * v6, Kelvin 2026-11-05: 「八轨改无轨，每个键横坐标也带移动数据」): each tap, hold and mine gets
 * {@code x = its lane's centre + its sideways shift}, in reference units from the screen's centre line (Kelvin
 * 2026-11-05: 「以屏幕中线为y轴设置X坐标」) at the lines' scale 1 and camera 0 (the game
 * applies the scale and camera to every note, {@link LaneLayout#x}). Every key is one size for now (Kelvin
 * 2026-11-05: 「我们先不做大小键之类的」), so a chart whose keys change size with their lanes (lane widths, inserts,
 * deletions while keys show) plays with keys of the usual size.
 *
 * <p>Where the lanes stand still this is exact: the shift events become x events with the lane centre added, the
 * same ease. Where lanes are inserted, deleted or change width, the lane centres move: over those beats the x is
 * sampled every {@link #STEP} beats into straight pieces (the note's visible span only). The lane model (lanes, lane
 * ids) is left as it was, for the chart editor.
 */
public final class LanePlacement {
    /** Sampling step (beats) where the lanes move. */
    public static final float STEP = 0.125f;

    private LanePlacement() {
    }

    /**
     * Places every note of {@code chart} that has a lane but no x of its own yet (taps, holds, mines of a lane
     * chart); others are left alone. {@link Chart}'s constructor calls it.
     */
    public static void place(Chart chart) {
        if (chart.laneCount() == 0) return;
        LaneLayout lay = null;
        float[] moving = null;
        for (Note n : chart.notes()) {
            if (!n.hasX() || n.laneIndex() < 0 || !Float.isNaN(n.x())) continue;
            if (lay == null) {
                lay = new LaneLayout();
                lay.setLanesUnscaled(chart);
                moving = movingSpans(chart.lanes());
            }
            place(n, lay, moving);
        }
    }

    /** @return the beat spans [from, to] (pairs, merged, ascending) in which some lane's width changes */
    static float[] movingSpans(Lane[] lanes) {
        List<float[]> spans = new ArrayList<>();
        for (Lane lane : lanes) {
            for (Events e : lane.widthEvents()) spans.add(new float[] {e.startTime(), e.endTime()});
            if (lane.insert() != null) spans.add(new float[] {lane.insert().startTime(), lane.insert().endTime()});
            if (lane.deletion() != null) {
                spans.add(new float[] {lane.deletion().startTime(), lane.deletion().endTime()});
            }
        }
        spans.sort((a, b) -> Float.compare(a[0], b[0]));
        float[] out = new float[2 * spans.size()];
        int k = 0;
        for (float[] s : spans) {
            if (!(s[1] > s[0])) continue; // a jump: the lanes stand still on both sides of it
            if (k > 0 && s[0] <= out[k - 1]) {
                out[k - 1] = Math.max(out[k - 1], s[1]);
            } else {
                out[k++] = s[0];
                out[k++] = s[1];
            }
        }
        return Arrays.copyOf(out, k);
    }

    private static boolean movingAt(float[] moving, float beat) {
        for (int i = 0; i < moving.length; i += 2) if (beat > moving[i] && beat < moving[i + 1]) return true;
        return false;
    }

    /** Lane {@code lane}'s centre at {@code beat}, from the screen's centre line. */
    private static float centreAt(LaneLayout lay, int lane, float beat) {
        lay.update(beat, LaneLayout.VIEW_LEFT, LaneLayout.VIEW_RIGHT);
        return lay.centre(lane) - LaneLayout.TILE_CENTRE;
    }

    private static void place(Note n, LaneLayout lay, float[] moving) {
        int lane = n.laneIndex();
        float judge = n.judgement();
        float centre = centreAt(lay, lane, judge);
        List<Events> shift = n.offsetXEvents();
        float from = Math.min(n.visibleFrom(), judge);
        float to = Math.max(n.endBeat(), judge);
        boolean moves = false;
        for (int i = 0; i < moving.length; i += 2) if (moving[i] < to && moving[i + 1] > from) moves = true;
        if (!moves) {
            // Exact: the lane stands still while the note shows.
            if (shift.isEmpty()) {
                n.place(centre + n.offsetX(), null);
                return;
            }
            List<Events> xs = new ArrayList<>(shift.size());
            for (Events e : shift) {
                xs.add(new Events(null, e.startTime(), e.endTime(), centre + e.initialValue(),
                    centre + e.finalValue(), e.exponent()));
            }
            n.place(centre, xs); // outside its events a shifted key is back on its lane's centre
            return;
        }
        // The lane moves while the note shows: pieces between the breakpoints, sampled where the lanes move or a
        // shift event is cut; the base x is where it is judged.
        float base = centre + n.offsetXAt(judge);
        float[] cuts = breakpoints(from, to, moving, shift);
        List<Events> xs = new ArrayList<>();
        for (int c = 0; c + 1 < cuts.length; c++) {
            float a = cuts[c];
            float b = cuts[c + 1];
            if (!(b > a)) continue;
            float mid = 0.5f * (a + b);
            if (!movingAt(moving, mid)) {
                float c0 = centreAt(lay, lane, mid);
                Events e = shiftAt(shift, mid);
                if (e == null) {
                    float v = c0 + (shift.isEmpty() ? n.offsetX() : 0f);
                    if (v != base) xs.add(new Events(null, a, b, v, v, 1f));
                    continue;
                }
                if (e.startTime() == a && e.endTime() == b) {
                    xs.add(new Events(null, a, b, c0 + e.initialValue(), c0 + e.finalValue(), e.exponent()));
                    continue;
                }
            }
            int steps = Math.max(1, (int) Math.ceil((b - a) / STEP - 1e-4f));
            float prev = centreAt(lay, lane, a) + n.offsetXAt(a);
            for (int s = 1; s <= steps; s++) {
                float t0 = a + (b - a) * (s - 1) / steps;
                float t1 = s == steps ? b : a + (b - a) * s / steps;
                float v = centreAt(lay, lane, t1) + n.offsetXAt(t1);
                xs.add(new Events(null, t0, t1, prev, v, 1f));
                prev = v;
            }
        }
        n.place(base, xs);
    }

    /** The shift event containing {@code beat}, or null. */
    private static Events shiftAt(List<Events> shift, float beat) {
        Events found = null;
        for (Events e : shift) {
            if (e.startTime() > beat) break;
            if (e.contains(beat)) found = e;
        }
        return found;
    }

    /** Sorted distinct cut beats in [from, to]: the ends, the moving spans' ends and the shift events' ends. */
    private static float[] breakpoints(float from, float to, float[] moving, List<Events> shift) {
        float[] all = new float[2 + moving.length + 2 * shift.size()];
        int k = 0;
        all[k++] = from;
        all[k++] = to;
        for (float m : moving) if (m > from && m < to) all[k++] = m;
        for (Events e : shift) {
            if (e.startTime() > from && e.startTime() < to) all[k++] = e.startTime();
            if (e.endTime() > from && e.endTime() < to) all[k++] = e.endTime();
        }
        float[] cuts = Arrays.copyOf(all, k);
        Arrays.sort(cuts);
        int d = 0;
        for (int i = 0; i < cuts.length; i++) if (d == 0 || cuts[i] != cuts[d - 1]) cuts[d++] = cuts[i];
        return Arrays.copyOf(cuts, d);
    }
}
