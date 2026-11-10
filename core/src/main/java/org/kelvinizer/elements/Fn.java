package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.List;

/**
 * 谱面格式 v7 的一维函数：由若干简单函数（{@link Seg}）组成；两段之间保持上一段的终值，没有段时是一个常数。
 *
 * <p>A 1D function {@code R → R} of chart format v7 (Kelvin 2026-11-09 18:44, item 0): a list of simple functions
 * ({@link Seg}), kept sorted by {@code ti}. With no segments the value is {@link #constant} (at first the
 * function's default, {@link #def}). Otherwise the segment that started last at {@code t} decides: inside it its
 * curve, after its end its {@code xf} (so the value holds between segments, and where two overlap the later-starting
 * one wins from its {@code ti}); before the first segment the value is that segment's {@code xi}.
 */
public final class Fn {
    /** The default value: a function at it with no segments is left out of the file. */
    public final float def;
    /** The value when there are no segments. */
    public float constant;
    /** The segments, sorted by {@code ti} ({@link #sort()} after changing them). */
    public final List<Seg> segs = new ArrayList<>();

    public Fn(float def) {
        this.def = def;
        this.constant = def;
    }

    /** @return the value at beat {@code t} */
    public float at(float t) {
        int n = segs.size();
        if (n == 0) return constant;
        // the last segment with ti <= t (binary search, segs sorted by ti; equal ti: the later one)
        int lo = 0, hi = n - 1, k = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (segs.get(mid).ti <= t) {
                k = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return k < 0 ? segs.get(0).xi : segs.get(k).at(t);
    }

    /** Sorts the segments by {@code ti}, keeping the order of segments that start together. */
    public void sort() {
        segs.sort((a, b) -> Float.compare(a.ti, b.ti));
    }

    /** @return true when there are no segments (the value is {@link #constant}) */
    public boolean isEmpty() {
        return segs.isEmpty();
    }

    /** @return the first segment's start beat, or NaN with no segments */
    public float firstTi() {
        return segs.isEmpty() ? Float.NaN : segs.get(0).ti;
    }

    /** @return the beat the last segment ends (from there on the value holds), or NaN with no segments */
    public float lastTf() {
        if (segs.isEmpty()) return Float.NaN;
        Seg s = segs.get(segs.size() - 1);
        return Math.max(s.ti, s.tf);
    }

    /** @return an independent copy (same default, constant and segments) */
    public Fn copy() {
        Fn c = new Fn(def);
        c.constant = constant;
        for (Seg s : segs) c.segs.add(new Seg(s.ti, s.tf, s.xi, s.xf, s.e));
        return c;
    }
}
