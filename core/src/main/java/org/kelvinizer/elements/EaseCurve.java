package org.kelvinizer.elements;

/**
 * 事件的自定义缓动曲线：几段三次贝塞尔，横轴是事件里经过的时间比例 0..1，纵轴是从开始值到结束值的进度（可以超出 0..1）。
 *
 * <p>The custom ease of an event ({@code "curve"}, CHART_FORMAT §3; Kelvin 2026-11-07 19:48 「这样子能自定义捏合曲线」):
 * cubic Bezier segments through {@code [0, 0, c1x, c1y, c2x, c2y, x1, y1, …, 1, 1]}, 6n + 2 numbers for n ≥ 1
 * segments. x is the fraction of the event's time and grows; y is the progress from the start value (0) to the end
 * value (1) and may overshoot. {@link #clean} makes a list safe to evaluate: the ends are (0, 0) and (1, 1), every
 * anchor's x is at least the one before it and at most 1, and the control points' x lie between their segment's
 * anchors, so x grows along each segment and {@link #at} finds the point of a time by bisection.
 */
final class EaseCurve {
    /** Bisection steps: the curve parameter to about 1e-9. */
    private static final int STEPS = 30;

    private EaseCurve() {
    }

    /**
     * @return a safe copy of {@code raw} (see the class doc), or null when it is null, not 6n + 2 numbers long
     *         (n ≥ 1) or holds a number that is not finite
     */
    static float[] clean(float[] raw) {
        if (raw == null || raw.length < 8 || (raw.length - 2) % 6 != 0) return null;
        for (float f : raw) {
            if (!Float.isFinite(f)) return null;
        }
        float[] c = raw.clone();
        int end = c.length - 2;
        c[0] = 0f;
        c[1] = 0f;
        c[end] = 1f;
        c[end + 1] = 1f;
        for (int i = 6; i < end; i += 6) c[i] = clamp(c[i], c[i - 6], 1f);
        for (int i = 0; i < end; i += 6) {
            c[i + 2] = clamp(c[i + 2], c[i], c[i + 6]);
            c[i + 4] = clamp(c[i + 4], c[i], c[i + 6]);
        }
        return c;
    }

    /** @return the progress at time fraction {@code u} (held to 0..1) on a {@link #clean} curve */
    static float at(float[] c, float u) {
        double x = u > 0f ? (u < 1f ? u : 1.0) : 0.0;
        int end = c.length - 2;
        int i = 0;
        while (i + 6 < end && x > c[i + 6]) i += 6;
        double lo = 0.0, hi = 1.0;
        for (int k = 0; k < STEPS; k++) {
            double s = 0.5 * (lo + hi);
            if (bezier(c[i], c[i + 2], c[i + 4], c[i + 6], s) < x) lo = s;
            else hi = s;
        }
        return (float) bezier(c[i + 1], c[i + 3], c[i + 5], c[i + 7], 0.5 * (lo + hi));
    }

    /** @return dy/dx where a {@link #clean} curve ends, or 0 when it ends straight up or down */
    static float endSlope(float[] c) {
        int end = c.length - 2;
        for (int j = end - 2; j >= end - 6; j -= 2) { // the second control point, the first, the segment's start
            float dx = c[end] - c[j];
            if (dx > 1e-6f) return (c[end + 1] - c[j + 1]) / dx;
        }
        return 0f;
    }

    private static double bezier(double a, double b, double c, double d, double s) {
        double t = 1.0 - s;
        return t * t * t * a + 3.0 * t * t * s * b + 3.0 * t * s * s * c + s * s * s * d;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
