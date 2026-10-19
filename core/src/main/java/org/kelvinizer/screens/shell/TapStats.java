package org.kelvinizer.screens.shell;

import java.util.Arrays;

/**
 * 校准时的点击误差统计：记录每次点击比节拍早或晚多少毫秒，求出中位数（测得的延迟）和中位绝对偏差（点击的离散度）。
 * 数组预先分配，之后不再分配内存。
 *
 * <p>Tap-error statistics of a calibration run (DESIGN §9.4): the offsets {@code tap − beat} in ms,
 * their median (the measured offset) and the median absolute deviation (the spread). Fixed
 * capacity, preallocated arrays; {@link #median()} and {@link #mad()} sort a scratch copy, so
 * nothing is allocated after construction.
 */
public final class TapStats {
    private final float[] values;
    private final float[] scratch;
    private int count;

    /** Creates statistics for at most {@code capacity} taps. */
    public TapStats(int capacity) {
        values = new float[capacity];
        scratch = new float[capacity];
    }

    /** Forgets every tap. */
    public void clear() {
        count = 0;
    }

    /** Adds one tap error (ignored when full). */
    public void add(float deltaMs) {
        if (count < values.length) values[count++] = deltaMs;
    }

    /** Number of taps. */
    public int count() {
        return count;
    }

    /** Tap error {@code i} in insertion order. */
    public float get(int i) {
        return values[i];
    }

    /** Median tap error in ms, or {@link Float#NaN} without taps. */
    public float median() {
        if (count == 0) return Float.NaN;
        System.arraycopy(values, 0, scratch, 0, count);
        return sortedMedian();
    }

    /** Median absolute deviation from the median in ms, or {@link Float#NaN} without taps. */
    public float mad() {
        if (count == 0) return Float.NaN;
        float m = median();
        for (int i = 0; i < count; i++) scratch[i] = Math.abs(values[i] - m);
        return sortedMedian();
    }

    /** Mean tap error in ms, or {@link Float#NaN} without taps. */
    public float mean() {
        if (count == 0) return Float.NaN;
        double sum = 0;
        for (int i = 0; i < count; i++) sum += values[i];
        return (float) (sum / count);
    }

    private float sortedMedian() {
        Arrays.sort(scratch, 0, count);
        int mid = count >> 1;
        return (count & 1) == 1 ? scratch[mid] : 0.5f * (scratch[mid - 1] + scratch[mid]);
    }
}
