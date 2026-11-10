package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.List;

/**
 * 谱面格式 v7 的水幕（漩涡，挡住判定）和水帘（模糊）：只有长方体、椭球体两种，有体积大小 V、原点位置 p 和 z 轴朝向 o；扭曲、故障和 v5 一样。
 *
 * <p>A veil (漩涡: a touch whose ray passes through it is not judged) or a curtain (水帘: blurs what is behind it) of
 * chart format v7 (Kelvin 2026-11-09 18:44, item 6): a box or an ellipsoid with size {@link #V} ({@code w}, {@code h},
 * {@code d} along its own x, y and z axes) centred at {@link #p}, its own z axis along {@link #o} (Kelvin 2026-11-09
 * 21:21 「加一个z轴朝向吧，也是三维变量」), in chart axes. A curtain blurs by {@link #b}. Its distortions and a veil's
 * glitches are as a v5 veil's ({@link #distortions}).
 */
public final class Volume7 {
    /** True for a curtain (水帘), false for a veil (水幕 / 漩涡). */
    public boolean curtain;
    /** True for an ellipsoid, false for a box. */
    public boolean ellipsoid;
    /** Size (w, h, d), default (480, 480, 480). */
    public final Fn3 V = new Fn3(480f, 480f, 480f);
    /** Centre, default (0, 0, 0). */
    public final Fn3 p = new Fn3(0f, 0f, 0f);
    /** Where its own z axis points (any length), default (0, 0, 1): unturned, {@code d} in depth. */
    public final Fn3 o = new Fn3(0f, 0f, 1f);
    /**
     * A curtain's blur (reference units), default {@link Veil#DEFAULT_BLUR}; clamped to 0..{@link Veil#MAX_BLUR}
     * where used. A veil never blurs.
     */
    public final Fn b = new Fn(Veil.DEFAULT_BLUR);
    /**
     * Distortion segments, as a v5 veil's {@code distortions} (its own wobble's strength, 1 where none covers the beat;
     * Kelvin 2026-11-10 02:17 「还有那个distortion，这些还是保留」); none by default.
     */
    public List<NoteDistortion> distortions = new ArrayList<>();
    /** Glitch segments, as a v5 veil's {@code glitches} (a curtain does not glitch); none by default. */
    public List<NoteGlitch> glitches = new ArrayList<>();

    public Volume7() {
    }

    private Volume7(Volume7 v) {
        curtain = v.curtain;
        ellipsoid = v.ellipsoid;
        copyInto(v.V, V);
        copyInto(v.p, p);
        copyInto(v.o, o);
        copyInto(v.b, b);
        if (v.distortions != null) distortions.addAll(v.distortions);
        if (v.glitches != null) glitches.addAll(v.glitches);
    }

    /** @return an independent copy */
    public Volume7 copy() {
        return new Volume7(this);
    }

    private static void copyInto(Fn3 from, Fn3 to) {
        copyInto(from.x, to.x);
        copyInto(from.y, to.y);
        copyInto(from.z, to.z);
    }

    private static void copyInto(Fn from, Fn to) {
        to.constant = from.constant;
        to.segs.clear();
        for (Seg g : from.segs) to.segs.add(new Seg(g.ti, g.tf, g.xi, g.xf, g.e));
    }

    /**
     * The turn of a volume whose z axis points along (ox, oy, oz): the shortest turn that takes +z to it (straight
     * back, half a turn about y; zero, NaN or infinite: none). Into {@code out}: its own x, y and z axes in chart
     * axes, three floats each.
     *
     * @return true when it is turned
     */
    public static boolean turn(float ox, float oy, float oz, float[] out) {
        double len = Math.sqrt((double) ox * ox + (double) oy * oy + (double) oz * oz);
        double bx = 0, by = 0, bz = 1;
        if (len > 1e-9 && len < Double.POSITIVE_INFINITY) {
            bx = ox / len;
            by = oy / len;
            bz = oz / len;
        }
        double s = bx * bx + by * by;
        if (s < 1e-12) { // along +z: as it is; along -z: half a turn about y
            float k = bz < 0 ? -1f : 1f;
            out[0] = k; out[1] = 0f; out[2] = 0f;
            out[3] = 0f; out[4] = 1f; out[5] = 0f;
            out[6] = 0f; out[7] = 0f; out[8] = k;
            return k < 0f;
        }
        // R = I + [k]x + [k]x^2 / (1 + bz), k = z x b; 1 / (1 + bz) without cancellation
        double q = bz < 0 ? (1 - bz) / s : 1 / (1 + bz);
        out[0] = (float) (1 - q * bx * bx); // x: R e_x
        out[1] = (float) (-q * bx * by);
        out[2] = (float) -bx;
        out[3] = (float) (-q * bx * by);    // y: R e_y
        out[4] = (float) (1 - q * by * by);
        out[5] = (float) -by;
        out[6] = (float) bx;                // z: b
        out[7] = (float) by;
        out[8] = (float) bz;
        return true;
    }
}
