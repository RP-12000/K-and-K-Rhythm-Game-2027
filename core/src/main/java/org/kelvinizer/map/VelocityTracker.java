package org.kelvinizer.map;

/**
 * 拖动松手时的单轴速度估计，用于卡片滑动、底部卡片停靠和列表滚动。
 * 对最近 80 ms 的采样做最小二乘拟合，和地图镜头的甩动算法一样。
 *
 * <p>One-axis release velocity for drags (card swipe, sheet detents, list scroll): a least-squares
 * slope over the samples of the last 80 ms, like the map camera's fling fit (§3.6). Times are
 * event times in seconds (System.nanoTime based). No allocation.
 */
final class VelocityTracker {
    private static final int N = 24;
    private static final long BASE = System.nanoTime();
    private final float[] t = new float[N], v = new float[N];
    private int head, count;

    /** Forgets every sample. */
    void reset() {
        head = 0;
        count = 0;
    }

    /** Adds a sample of {@code value} now. */
    void add(float value) {
        t[head] = now();
        v[head] = value;
        head = (head + 1) % N;
        if (count < N) count++;
    }

    /** Current event time in seconds. */
    static float now() {
        return (System.nanoTime() - BASE) / 1e9f;
    }

    /** Velocity (units / s) over the last 80 ms; 0 with fewer than two samples. */
    float velocity() {
        if (count < 2) return 0f;
        float tn = now();
        float st = 0f, sv = 0f;
        int n = 0;
        for (int k = 0; k < count; k++) {
            int i = (head - 1 - k + N) % N;
            if (tn - t[i] > MapStyle.FLING_WINDOW && n >= 2) break;
            st += t[i];
            sv += v[i];
            n++;
        }
        if (n < 2) return 0f;
        float mt = st / n, mv = sv / n, num = 0f, den = 0f;
        for (int k = 0; k < n; k++) {
            int i = (head - 1 - k + N) % N;
            float dt = t[i] - mt;
            num += dt * (v[i] - mv);
            den += dt * dt;
        }
        if (den < 1e-7f) return 0f;
        // a finger that stopped before release has no velocity
        int last = (head - 1 + N) % N;
        if (tn - t[last] > MapStyle.FLING_WINDOW) return 0f;
        return num / den;
    }
}
