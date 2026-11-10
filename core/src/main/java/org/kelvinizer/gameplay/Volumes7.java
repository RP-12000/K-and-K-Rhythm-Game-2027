package org.kelvinizer.gameplay;

import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Sea;
import org.kelvinizer.elements.Veil;
import org.kelvinizer.elements.Volume7;

import java.util.List;

/**
 * 谱面格式 v7 的水幕和水帘这一帧在屏幕上的样子：椭球投下来是精确的椭圆，长方体是 8 个角的凸包；穿过镜头平面的部分切掉；
 * 镜头在里面时整个画面都算。手指按在水幕的外形里（视线穿过水幕）就不判定。
 *
 * <p>The veils and curtains of a chart format v7 chart ({@link Volume7}, Kelvin 2026-11-09 18:44 item 6, 21:21
 * 「椭球和长方体可以。加一个z轴朝向吧」) as they show this frame. Each volume is its size {@code V} along its own axes,
 * turned so its z axis points along {@code o} ({@link Volume7#turn}), centred at {@code p}: in the view's world (the
 * chart origin halfway into the columns' depth, {@link View3D#worldZ}) a centre and three half axes. Its outline on
 * the screen (world units of the flat camera, where presses land) is:
 * <ul>
 *   <li>an ellipsoid: the exact ellipse it projects to (its dual quadric through the camera);</li>
 *   <li>a box: the convex hull of its eight corners;</li>
 *   <li>one reaching behind the camera's near plane: the hull of what is in front of it (an ellipsoid sampled);</li>
 *   <li>one the eye is inside: the whole screen ({@link #EVERYWHERE}).</li>
 * </ul>
 * A press inside a veil's outline (its ray passes through the veil) judges nothing until it lifts
 * ({@link #veiled}, {@link GameEngine#touchDown}); curtains never block. While the view is flat the outline is the
 * volume seen straight along z. No allocation per frame once the most volumes a chart shows at once were seen.
 */
public final class Volumes7 {
    /** Outline kinds: none (hidden, behind the eye or edge-on), an ellipse, a convex polygon, the whole screen. */
    public static final int NONE = 0, ELLIPSE = 1, POLYGON = 2, EVERYWHERE = 3;
    /** The most points of a polygon outline (what the veil shader takes). */
    public static final int MAX_POINTS = 16;
    /** Meridians and parallels an ellipsoid cut by the near plane is sampled with. */
    private static final int MERIDIANS = 16, PARALLELS = 9;

    private int count;
    /** The beat of the last {@link #update}. */
    private float beat;
    /** Each showing volume's own, and its index in the chart's list. */
    private Volume7[] source = new Volume7[0];
    private int[] index = new int[0];
    private boolean[] curtain = new boolean[0];
    private boolean[] ellipsoid = new boolean[0];
    /** A curtain's blur this frame (reference units, 0..{@link Veil#MAX_BLUR}); 0 for a veil. */
    private float[] blur = new float[0];
    private int[] kind = new int[0];
    /** World centre and half axes (x, y, z each; nine per volume: its own x, y and z axes). */
    private float[] centre = new float[0];
    private float[] axes = new float[0];
    /** Ellipse: centre, semi-axes (major first) and the major axis's cos and sin (screen world units). */
    private float[] ellipse = new float[0];
    /** Polygon: up to {@link #MAX_POINTS} points (x, y), counter-clockwise. */
    private float[][] poly = new float[0][];
    private int[] points = new int[0];
    /** Bounds on the screen: left, bottom, right, top. */
    private float[] bounds = new float[0];

    private final float[] rows = new float[12];
    private final float[] turn = new float[9];
    private final float[] half = new float[3];
    private final float[] box4 = new float[4];
    private final float[] scratch = new float[2 * (MERIDIANS * PARALLELS + 2 * MERIDIANS * (PARALLELS + 1) + 32)];
    private final float[] hullTmp = new float[scratch.length];
    private final float[] p3 = new float[3 * (MERIDIANS * PARALLELS + 2)];
    private float near;
    private boolean depthShown;

    /**
     * Works out each volume of {@code chart} at {@code beat} through {@code view} (its last {@link View3D#update}).
     * A volume with {@code w} or {@code h} not above 0 is hidden.
     */
    public void update(Chart chart, float beat, View3D view) {
        count = 0;
        this.beat = beat;
        List<Volume7> list = chart != null ? chart.volumes7() : null;
        if (list == null || list.isEmpty()) return;
        room(list.size());
        depthShown = view != null && view.active();
        if (depthShown) {
            view.screenRows(rows);
            near = view.nearPlane();
        } else { // flat: straight along z
            java.util.Arrays.fill(rows, 0f);
            rows[0] = 1f;
            rows[5] = 1f;
            rows[11] = 1f;
            near = 0f;
        }
        float kx = Field.kx(), ky = Field.ky();
        float zk = depthShown ? view.worldZ(1f) : 0f;
        float z0 = depthShown ? view.worldZ(Sea.MID_Z) : 0f;
        for (int j = 0; j < list.size(); j++) {
            Volume7 v = list.get(j);
            float w = v.V.x.at(beat), h = v.V.y.at(beat), d = v.V.z.at(beat);
            if (!(w > 0f) || !(h > 0f) || !(w < Float.POSITIVE_INFINITY) || !(h < Float.POSITIVE_INFINITY)) continue;
            if (!(d > 0f) || !(d < Float.POSITIVE_INFINITY)) d = 0f;
            int k = count;
            source[k] = v;
            index[k] = j;
            curtain[k] = v.curtain;
            ellipsoid[k] = v.ellipsoid;
            float bl = v.curtain ? v.b.at(beat) : 0f;
            blur[k] = bl > 0f ? Math.min(Veil.MAX_BLUR, bl) : 0f; // NaN: none
            int c = 3 * k;
            centre[c] = Field.x(LaneLayout.TILE_CENTRE + finite(v.p.x.at(beat)));
            centre[c + 1] = Field.y(Sea.MID_Y + finite(v.p.y.at(beat)));
            centre[c + 2] = z0 + zk * finite(v.p.z.at(beat));
            Volume7.turn(v.o.x.at(beat), v.o.y.at(beat), v.o.z.at(beat), turn);
            half[0] = 0.5f * w;
            half[1] = 0.5f * h;
            half[2] = 0.5f * d;
            int a = 9 * k;
            for (int i = 0; i < 3; i++) {
                axes[a + 3 * i] = kx * turn[3 * i] * half[i];
                axes[a + 3 * i + 1] = ky * turn[3 * i + 1] * half[i];
                axes[a + 3 * i + 2] = zk * turn[3 * i + 2] * half[i];
            }
            kind[k] = depthShown && inside(k, view.eyeX(), view.eyeY(), view.eyeZ()) ? everywhere(k, view)
                : outline(k);
            if (kind[k] != NONE) count++;
        }
    }

    /** Volume k round the eye: the whole screen and a tenth past each edge, as a polygon; @return its kind */
    private int everywhere(int k, View3D view) {
        view.screenBox(box4);
        float mx = 0.1f * (box4[2] - box4[0]), my = 0.1f * (box4[3] - box4[1]);
        float l = box4[0] - mx, b = box4[1] - my, r = box4[2] + mx, t = box4[3] + my;
        float[] p = poly[k];
        p[0] = l;
        p[1] = b;
        p[2] = r;
        p[3] = b;
        p[4] = r;
        p[5] = t;
        p[6] = l;
        p[7] = t;
        points[k] = 4;
        int o = 4 * k;
        bounds[o] = l;
        bounds[o + 1] = b;
        bounds[o + 2] = r;
        bounds[o + 3] = t;
        return EVERYWHERE;
    }

    /** @return true when the eye at (ex, ey, ez) (world) is inside volume k (its own axes, Cramer's rule) */
    boolean inside(int k, float ex, float ey, float ez) {
        int a = 9 * k, c = 3 * k;
        double rx = ex - centre[c], ry = ey - centre[c + 1], rz = ez - centre[c + 2];
        double det = triple(axes[a], axes[a + 1], axes[a + 2], axes[a + 3], axes[a + 4], axes[a + 5], axes[a + 6],
            axes[a + 7], axes[a + 8]);
        if (!(Math.abs(det) > 1e-9)) return false; // flat: nothing to be inside
        double u = triple(rx, ry, rz, axes[a + 3], axes[a + 4], axes[a + 5], axes[a + 6], axes[a + 7],
            axes[a + 8]) / det;
        double v = triple(axes[a], axes[a + 1], axes[a + 2], rx, ry, rz, axes[a + 6], axes[a + 7], axes[a + 8]) / det;
        double w = triple(axes[a], axes[a + 1], axes[a + 2], axes[a + 3], axes[a + 4], axes[a + 5], rx, ry, rz) / det;
        if (ellipsoid[k]) return u * u + v * v + w * w <= 1.0;
        return Math.abs(u) <= 1.0 && Math.abs(v) <= 1.0 && Math.abs(w) <= 1.0;
    }

    /** @return the determinant of the columns (ax, ay, az), (bx, by, bz), (cx, cy, cz) */
    private static double triple(double ax, double ay, double az, double bx, double by, double bz, double cx,
                                 double cy, double cz) {
        return ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx);
    }

    /** Sets volume k's outline on the screen; @return its kind */
    private int outline(int k) {
        int a = 9 * k, c = 3 * k;
        // depth (the third row) of the centre and of each half axis
        float wc = rows[8] * centre[c] + rows[9] * centre[c + 1] + rows[10] * centre[c + 2] + rows[11];
        float reach = 0f;
        for (int i = 0; i < 3; i++) {
            float wi = rows[8] * axes[a + 3 * i] + rows[9] * axes[a + 3 * i + 1] + rows[10] * axes[a + 3 * i + 2];
            reach += ellipsoid[k] ? wi * wi : Math.abs(wi);
        }
        if (ellipsoid[k]) reach = (float) Math.sqrt(reach);
        float limit = Math.max(near, 1e-3f);
        if (wc + reach <= limit) return NONE; // all of it behind the near plane
        if (wc - reach > limit) return ellipsoid[k] ? ellipse(k) : box(k, limit);
        return ellipsoid[k] ? sampled(k, limit) : box(k, limit);
    }

    /** The exact ellipse of ellipsoid k, all in front of the eye: C* = Σ g_i g_iᵀ − g_c g_cᵀ. */
    private int ellipse(int k) {
        int a = 9 * k, c = 3 * k;
        double s00 = 0, s01 = 0, s02 = 0, s11 = 0, s12 = 0, s22 = 0;
        for (int i = 0; i <= 3; i++) {
            double x, y, z, one;
            if (i < 3) {
                x = axes[a + 3 * i];
                y = axes[a + 3 * i + 1];
                z = axes[a + 3 * i + 2];
                one = 0;
            } else {
                x = centre[c];
                y = centre[c + 1];
                z = centre[c + 2];
                one = 1;
            }
            double g0 = rows[0] * x + rows[1] * y + rows[2] * z + rows[3] * one;
            double g1 = rows[4] * x + rows[5] * y + rows[6] * z + rows[7] * one;
            double g2 = rows[8] * x + rows[9] * y + rows[10] * z + rows[11] * one;
            double sg = i < 3 ? 1 : -1;
            s00 += sg * g0 * g0;
            s01 += sg * g0 * g1;
            s02 += sg * g0 * g2;
            s11 += sg * g1 * g1;
            s12 += sg * g1 * g2;
            s22 += sg * g2 * g2;
        }
        if (!(s22 < 0)) return NONE;
        double mx = s02 / s22, my = s12 / s22;
        double p = mx * mx - s00 / s22, q = mx * my - s01 / s22, r = my * my - s11 / s22;
        double mean = 0.5 * (p + r), dev = Math.sqrt(0.25 * (p - r) * (p - r) + q * q);
        double l1 = mean + dev, l2 = mean - dev;
        if (!(l2 > 0.25)) return NONE; // edge-on: thinner than half a unit
        double th = 0.5 * Math.atan2(2 * q, p - r);
        int e = 6 * k;
        ellipse[e] = (float) mx;
        ellipse[e + 1] = (float) my;
        ellipse[e + 2] = (float) Math.sqrt(l1);
        ellipse[e + 3] = (float) Math.sqrt(l2);
        ellipse[e + 4] = (float) Math.cos(th);
        ellipse[e + 5] = (float) Math.sin(th);
        // its bounds: the half widths of a turned ellipse
        double ca = ellipse[e + 4], sa = ellipse[e + 5];
        double hx = Math.sqrt(l1 * ca * ca + l2 * sa * sa), hy = Math.sqrt(l1 * sa * sa + l2 * ca * ca);
        int b = 4 * k;
        bounds[b] = (float) (mx - hx);
        bounds[b + 1] = (float) (my - hy);
        bounds[b + 2] = (float) (mx + hx);
        bounds[b + 3] = (float) (my + hy);
        return ELLIPSE;
    }

    /** Box k: the hull of its corners in front of the near plane and of where its edges cross that plane. */
    private int box(int k, float limit) {
        int a = 9 * k, c = 3 * k;
        int n = 0;
        for (int i = 0; i < 8; i++) {
            float sx = (i & 1) == 0 ? -1f : 1f, sy = (i & 2) == 0 ? -1f : 1f, sz = (i & 4) == 0 ? -1f : 1f;
            for (int j = 0; j < 3; j++) {
                p3[3 * i + j] = centre[c + j] + sx * axes[a + j] + sy * axes[a + 3 + j] + sz * axes[a + 6 + j];
            }
        }
        for (int i = 0; i < 8; i++) {
            if (depth(p3, i) > limit) n = project(p3, i, n);
            for (int bit = 1; bit < 8; bit <<= 1) { // each edge once: from the corner without the bit
                if ((i & bit) != 0) continue;
                n = cross(p3, i, i | bit, limit, n);
            }
        }
        return hull(k, n);
    }

    /** Ellipsoid k cut by the near plane: the hull of its sampled points in front and where its meridians cross. */
    private int sampled(int k, float limit) {
        int a = 9 * k, c = 3 * k;
        int n = 0;
        for (int m = 0; m < MERIDIANS; m++) {
            double phi = 2 * Math.PI * m / MERIDIANS;
            float cp = (float) Math.cos(phi), sp = (float) Math.sin(phi);
            int prev = -1;
            for (int q = 0; q <= PARALLELS + 1; q++) {
                double th = Math.PI * q / (PARALLELS + 1) - 0.5 * Math.PI;
                float ct = (float) Math.cos(th), st = (float) Math.sin(th);
                int slot = q % 2; // two points along the meridian at a time
                for (int j = 0; j < 3; j++) {
                    p3[3 * slot + j] = centre[c + j] + ct * (cp * axes[a + j] + sp * axes[a + 3 + j])
                        + st * axes[a + 6 + j];
                }
                if (depth(p3, slot) > limit && n < scratch.length / 2 - 4) n = project(p3, slot, n);
                if (prev >= 0 && n < scratch.length / 2 - 4) n = cross(p3, prev, slot, limit, n);
                prev = slot;
            }
        }
        return hull(k, n);
    }

    private float depth(float[] p, int i) {
        return rows[8] * p[3 * i] + rows[9] * p[3 * i + 1] + rows[10] * p[3 * i + 2] + rows[11];
    }

    /** Projects point i of {@code p} into {@link #scratch} at n; @return n + 1 */
    private int project(float[] p, int i, int n) {
        float x = p[3 * i], y = p[3 * i + 1], z = p[3 * i + 2];
        float w = rows[8] * x + rows[9] * y + rows[10] * z + rows[11];
        scratch[2 * n] = (rows[0] * x + rows[1] * y + rows[2] * z + rows[3]) / w;
        scratch[2 * n + 1] = (rows[4] * x + rows[5] * y + rows[6] * z + rows[7]) / w;
        return n + 1;
    }

    /** Where the segment from point i to point j crosses the near plane, if it does, projected; @return the count */
    private int cross(float[] p, int i, int j, float limit, int n) {
        float di = depth(p, i) - limit, dj = depth(p, j) - limit;
        if ((di > 0f) == (dj > 0f) || di == dj) return n;
        float t = di / (di - dj);
        float x = p[3 * i] + t * (p[3 * j] - p[3 * i]);
        float y = p[3 * i + 1] + t * (p[3 * j + 1] - p[3 * i + 1]);
        float z = p[3 * i + 2] + t * (p[3 * j + 2] - p[3 * i + 2]);
        float w = limit;
        scratch[2 * n] = (rows[0] * x + rows[1] * y + rows[2] * z + rows[3]) / w;
        scratch[2 * n + 1] = (rows[4] * x + rows[5] * y + rows[6] * z + rows[7]) / w;
        return n + 1;
    }

    /**
     * Volume k's outline: the convex hull of the n points in {@link #scratch} (monotone chain), counter-clockwise, at
     * most {@link #MAX_POINTS} of them (every other one dropped while more).
     */
    private int hull(int k, int n) {
        if (n < 3) return NONE;
        // sort the points by x, then y (insertion sort: a few dozen at most)
        for (int i = 1; i < n; i++) {
            float x = scratch[2 * i], y = scratch[2 * i + 1];
            int j = i - 1;
            while (j >= 0 && (scratch[2 * j] > x || (scratch[2 * j] == x && scratch[2 * j + 1] > y))) {
                scratch[2 * j + 2] = scratch[2 * j];
                scratch[2 * j + 3] = scratch[2 * j + 1];
                j--;
            }
            scratch[2 * j + 2] = x;
            scratch[2 * j + 3] = y;
        }
        int h = 0;
        for (int pass = 0; pass < 2; pass++) {
            int start = h;
            for (int s = 0; s < n; s++) {
                int i = pass == 0 ? s : n - 1 - s;
                float x = scratch[2 * i], y = scratch[2 * i + 1];
                while (h - start >= 2 && cross2(hullTmp, h - 2, h - 1, x, y) <= 0f) h--;
                hullTmp[2 * h] = x;
                hullTmp[2 * h + 1] = y;
                h++;
            }
            h--; // the last point starts the other chain
        }
        if (h < 3) return NONE;
        int keep = h;
        int step = 1;
        while (keep > MAX_POINTS) {
            step *= 2;
            keep = (h + step - 1) / step;
        }
        float[] out = poly[k];
        float l = Float.POSITIVE_INFINITY, b = Float.POSITIVE_INFINITY;
        float r = Float.NEGATIVE_INFINITY, t = Float.NEGATIVE_INFINITY;
        int m = 0;
        for (int i = 0; i < h; i += step) {
            out[2 * m] = hullTmp[2 * i];
            out[2 * m + 1] = hullTmp[2 * i + 1];
            l = Math.min(l, out[2 * m]);
            r = Math.max(r, out[2 * m]);
            b = Math.min(b, out[2 * m + 1]);
            t = Math.max(t, out[2 * m + 1]);
            m++;
        }
        if (m < 3 || !(r - l > 0.5f) || !(t - b > 0.5f)) return NONE;
        points[k] = m;
        int o = 4 * k;
        bounds[o] = l;
        bounds[o + 1] = b;
        bounds[o + 2] = r;
        bounds[o + 3] = t;
        return POLYGON;
    }

    private static float cross2(float[] p, int i, int j, float x, float y) {
        return (p[2 * j] - p[2 * i]) * (y - p[2 * i + 1]) - (p[2 * j + 1] - p[2 * i + 1]) * (x - p[2 * i]);
    }

    /** @return true when screen point (x, y) (world units of the flat camera) is inside a veil (not a curtain) */
    public boolean veiled(float x, float y) {
        for (int k = 0; k < count; k++) if (!curtain[k] && contains(k, x, y)) return true;
        return false;
    }

    /** @return true when screen point (x, y) is inside volume k's outline */
    public boolean contains(int k, float x, float y) {
        switch (kind[k]) {
            case EVERYWHERE:
                return true;
            case ELLIPSE: {
                int e = 6 * k;
                float dx = x - ellipse[e], dy = y - ellipse[e + 1];
                float u = (dx * ellipse[e + 4] + dy * ellipse[e + 5]) / ellipse[e + 2];
                float v = (dy * ellipse[e + 4] - dx * ellipse[e + 5]) / ellipse[e + 3];
                return u * u + v * v <= 1f;
            }
            case POLYGON: {
                float[] p = poly[k];
                int n = points[k];
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    if ((p[2 * j] - p[2 * i]) * (y - p[2 * i + 1]) - (p[2 * j + 1] - p[2 * i + 1]) * (x - p[2 * i])
                        < 0f) {
                        return false;
                    }
                }
                return true;
            }
            default:
                return false;
        }
    }

    /** @return the volumes showing this frame */
    public int count() {
        return count;
    }

    /** @return the beat the volumes were worked out at */
    public float beat() {
        return beat;
    }

    /** @return showing volume k as charted (for its distortions and glitches) */
    public Volume7 source(int k) {
        return source[k];
    }

    /** @return showing volume k's index in the chart's list */
    public int index(int k) {
        return index[k];
    }

    /** @return true when volume k is a curtain (水帘) */
    public boolean curtain(int k) {
        return curtain[k];
    }

    /** @return volume k's blur this frame (reference units, 0..{@link Veil#MAX_BLUR}; 0 for a veil) */
    public float blur(int k) {
        return blur[k];
    }

    /** @return true when volume k is an ellipsoid */
    public boolean ellipsoid(int k) {
        return ellipsoid[k];
    }

    /**
     * @return volume k's outline kind: {@link #ELLIPSE}, {@link #POLYGON}, or {@link #EVERYWHERE} (its polygon then
     *         the screen and past it)
     */
    public int kind(int k) {
        return kind[k];
    }

    /** @return true when the volumes were worked out through the 3D view (their depth shows) */
    public boolean depthShown() {
        return depthShown;
    }

    /** @return component j (0 x, 1 y, 2 z) of volume k's world centre */
    public float centre(int k, int j) {
        return centre[3 * k + j];
    }

    /** @return component j of volume k's own axis i (0 x, 1 y, 2 z) as a world half axis */
    public float axis(int k, int i, int j) {
        return axes[9 * k + 3 * i + j];
    }

    /** @return value j of volume k's ellipse: centre x, y, semi-axes major, minor, the major axis's cos, sin */
    public float ellipse(int k, int j) {
        return ellipse[6 * k + j];
    }

    /** @return the points of volume k's polygon (x, y pairs; {@link #points} of them; do not modify) */
    public float[] polygon(int k) {
        return poly[k];
    }

    /** @return how many points volume k's polygon has */
    public int points(int k) {
        return points[k];
    }

    /** @return bound j of volume k's outline on the screen: left, bottom, right, top */
    public float bound(int k, int j) {
        return bounds[4 * k + j];
    }

    private void room(int n) {
        if (curtain.length >= n) return;
        source = new Volume7[n];
        index = new int[n];
        curtain = new boolean[n];
        ellipsoid = new boolean[n];
        blur = new float[n];
        kind = new int[n];
        centre = new float[3 * n];
        axes = new float[9 * n];
        ellipse = new float[6 * n];
        poly = new float[n][2 * MAX_POINTS];
        points = new int[n];
        bounds = new float[4 * n];
    }

    private static float finite(float v) {
        return v > Float.NEGATIVE_INFINITY && v < Float.POSITIVE_INFINITY ? v : 0f;
    }
}
