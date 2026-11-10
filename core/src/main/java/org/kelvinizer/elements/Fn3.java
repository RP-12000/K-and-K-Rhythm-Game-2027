package org.kelvinizer.elements;

/**
 * 谱面格式 v7 的三维函数：x、y、z 各是一个一维函数（{@link Fn}）。
 *
 * <p>A vector function of chart format v7: one {@link Fn} per component, in chart axes (x right of the centre line,
 * y up from the frame's centre, z inward).
 */
public final class Fn3 {
    public final Fn x, y, z;

    /** @param dx the x default, {@code dy} the y default, {@code dz} the z default */
    public Fn3(float dx, float dy, float dz) {
        this(new Fn(dx), new Fn(dy), new Fn(dz));
    }

    private Fn3(Fn x, Fn y, Fn z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** @return an independent copy */
    public Fn3 copy() {
        return new Fn3(x.copy(), y.copy(), z.copy());
    }
}
