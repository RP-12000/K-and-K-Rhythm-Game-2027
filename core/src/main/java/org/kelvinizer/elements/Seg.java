package org.kelvinizer.elements;

/**
 * 简单函数（谱面格式 v7 的一段）：从 t_i 拍到 t_f 拍，值从 x_i 缓动到 x_f，缓动指数 e。
 *
 * <p>One simple function of chart format v7 (Kelvin 2026-11-09 18:44, item 0): over the beats {@code [ti, tf]} the
 * value goes from {@code xi} to {@code xf} as {@code xi + (xf − xi) · p^e} with {@code p = (t − ti) / (tf − ti)}.
 * {@code e} 1 is linear, above 1 starts slow, below 1 starts fast; an {@code e} that is not above 0 (or NaN) counts
 * as 1. A segment with {@code tf ≤ ti} jumps to {@code xf} at {@code ti}. A 1D function of the chart is a list of
 * these ({@link Fn}).
 */
public final class Seg {
    /** Start and end beat. */
    public float ti, tf;
    /** Value at the start and at the end. */
    public float xi, xf;
    /** Ease exponent. */
    public float e = 1f;

    public Seg(float ti, float tf, float xi, float xf, float e) {
        this.ti = ti;
        this.tf = tf;
        this.xi = xi;
        this.xf = xf;
        this.e = e;
    }

    /** @return the value at beat {@code t}: {@code xi} up to {@code ti}, {@code xf} from {@code tf} on */
    public float at(float t) {
        if (t >= tf && t >= ti) return xf; // also a segment with tf <= ti, from ti on
        if (!(t > ti)) return xi;
        float p = (t - ti) / (tf - ti); // here ti < t < tf
        float k = e > 0f ? e : 1f;
        if (k != 1f) p = (float) Math.pow(p, k);
        return xi + (xf - xi) * p;
    }
}
