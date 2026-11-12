package org.kelvinizer.elements;

/**
 * 谱面版本 2.1 的组：几条轨道（和别的组）收在一起，有名字、所在的组 p、中心位置 P、旋转 R（度）、缩放 K 和透明度 o，都是拍的函数。
 *
 * <p>A group of chart version 2.1 (Kelvin 2026-11-11 16:39 「轨道一多怎么办？…有点像CAD？」, 17:32 「可以」): lanes
 * ({@link Lane7#g}) and other groups ({@link #p}) gathered under a {@link #name} and moved, turned, sized and faded as
 * one piece, all functions of the beat:
 * <ul>
 *   <li>{@link #P} where its centre is, in the axes of the group it is in (the chart's at the top);</li>
 *   <li>{@link #R} its turn about its centre, degrees about x, y and z, turned about z first, then x, then y (as Unity
 *       does): +x takes up to inward (its top tips away from the player), +y takes right to the player and +z takes
 *       right to up (anticlockwise as the player sees it);</li>
 *   <li>{@link #K} its size about its centre (below 0, NaN or infinite: 0);</li>
 *   <li>{@link #o} its opacity, times its lanes' and their keys' (clamped to 0..1 where used).</li>
 * </ul>
 * What is in a group is written in the group's axes: a lane's origin v from the group's centre, its fall u and its
 * normal n turning with it; the lane's sizes (a, S, s, F, f) and how far its keys fall are sized with it. A point q of
 * a group is {@code P + K·Ry·Rx·Rz·q} in the axes of the group it is in ({@link Pose}). Judging follows the drawing.
 */
public final class Group7 {
    /** The id lanes ({@link Lane7#g}) and groups ({@link #p}) name it by: unique in the chart, not 0. */
    public int id;
    /** Its name (the editor shows it), or null. */
    public String name;
    /** The id of the group it is in, 0 for none. */
    public int p;
    /** Centre, default (0, 0, 0). */
    public final Fn3 P = new Fn3(0f, 0f, 0f);
    /** Turn about x, y and z, degrees, default (0, 0, 0). */
    public final Fn3 R = new Fn3(0f, 0f, 0f);
    /** Size, default 1. */
    public final Fn K = new Fn(1f);
    /** Opacity, default 1. */
    public final Fn o = new Fn(1f);

    public Group7(int id) {
        this.id = id;
    }

    /** @return an independent copy */
    public Group7 copy() {
        Group7 c = new Group7(id);
        c.name = name;
        c.p = p;
        copyInto(P.x, c.P.x);
        copyInto(P.y, c.P.y);
        copyInto(P.z, c.P.z);
        copyInto(R.x, c.R.x);
        copyInto(R.y, c.R.y);
        copyInto(R.z, c.R.z);
        copyInto(K, c.K);
        copyInto(o, c.o);
        return c;
    }

    private static void copyInto(Fn from, Fn to) {
        to.constant = from.constant;
        to.segs.clear();
        for (Seg s : from.segs) to.segs.add(new Seg(s.ti, s.tf, s.xi, s.xf, s.e));
    }

    /**
     * 一个组在某一拍的变换（连同它所在的组）：组里的点 q 在谱面坐标里是 t + k·m·q。
     *
     * <p>A group's transform at a beat with the groups it is in ({@link #at}): a point q of the group is
     * {@code t + k·m·q} in chart axes. Kept for the last beat asked.
     */
    public static final class Pose {
        private final Group7 g;
        private final Pose parent;
        /** Its turn with its parents', row by row (orthonormal). */
        public final float[] m = {1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
        /** Where its centre is, chart axes. */
        public final float[] t = new float[3];
        /** Its size with its parents' (0 or more). */
        public float k = 1f;
        /** True when {@link #m} turns: some group of the chain has a turn at the beat. */
        public boolean turned;
        private float beat = Float.NaN;
        /** Its own turn, Ry·Rx·Rz ({@link #at}). */
        private final float[] r = new float[9];

        /** @param parent the pose of the group {@code g} is in, or null */
        public Pose(Group7 g, Pose parent) {
            this.g = g;
            this.parent = parent;
        }

        /** @return the group */
        public Group7 group() {
            return g;
        }

        /** @return this, its transform at {@code beat} */
        public Pose at(float beat) {
            if (beat == this.beat) return this;
            float rx = finite(g.R.x.at(beat)), ry = finite(g.R.y.at(beat)), rz = finite(g.R.z.at(beat));
            float px = finite(g.P.x.at(beat)), py = finite(g.P.y.at(beat)), pz = finite(g.P.z.at(beat));
            float kk = g.K.at(beat);
            kk = kk > 0f && kk < Float.POSITIVE_INFINITY ? kk : 0f;
            boolean own = rx != 0f || ry != 0f || rz != 0f;
            if (own) {
                double ax = Math.toRadians(rx), ay = Math.toRadians(ry), az = Math.toRadians(rz);
                double cx = Math.cos(ax), sx = Math.sin(ax), cy = Math.cos(ay), sy = Math.sin(ay);
                double cz = Math.cos(az), sz = Math.sin(az);
                r[0] = (float) (cy * cz + sy * sx * sz);
                r[1] = (float) (sy * sx * cz - cy * sz);
                r[2] = (float) (sy * cx);
                r[3] = (float) (cx * sz);
                r[4] = (float) (cx * cz);
                r[5] = (float) -sx;
                r[6] = (float) (cy * sx * sz - sy * cz);
                r[7] = (float) (sy * sz + cy * sx * cz);
                r[8] = (float) (cy * cx);
            } else {
                unturned(r);
            }
            if (parent == null) {
                System.arraycopy(r, 0, m, 0, 9);
                t[0] = px;
                t[1] = py;
                t[2] = pz;
                k = kk;
                turned = own;
            } else {
                Pose q = parent.at(beat);
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        m[3 * i + j] = q.m[3 * i] * r[j] + q.m[3 * i + 1] * r[3 + j] + q.m[3 * i + 2] * r[6 + j];
                    }
                    t[i] = q.t[i] + q.k * (q.m[3 * i] * px + q.m[3 * i + 1] * py + q.m[3 * i + 2] * pz);
                }
                k = q.k * kk;
                turned = own || q.turned;
                if (!turned) unturned(m); // exactly
            }
            this.beat = beat;
            return this;
        }

        /** @return its opacity with its parents' at {@code beat}, 0..1 */
        public float opacityAt(float beat) {
            float a = Lane.clamp01(g.o.at(beat));
            return parent == null || !(a > 0f) ? a : a * parent.opacityAt(beat);
        }

        /** Puts point (x, y, z) of the group, at the beat last asked ({@link #at}), into {@code out} in chart axes. */
        public void point(float x, float y, float z, float[] out) {
            for (int i = 0; i < 3; i++) out[i] = t[i] + k * (m[3 * i] * x + m[3 * i + 1] * y + m[3 * i + 2] * z);
        }

        private static void unturned(float[] a) {
            a[1] = a[2] = a[3] = a[5] = a[6] = a[7] = 0f;
            a[0] = a[4] = a[8] = 1f;
        }

        private static float finite(float v) {
            return v > -Float.MAX_VALUE && v < Float.MAX_VALUE ? v : 0f;
        }
    }
}
