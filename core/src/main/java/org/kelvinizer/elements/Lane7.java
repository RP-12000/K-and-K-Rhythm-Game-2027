package org.kelvinizer.elements;

/**
 * 谱面格式 v7 的轨道：只有下落向量朝向 u、原点 v、底面大小 a、天面高度 S、天面延申 s、底面高度 F、底面延申 f 和透明度 o。
 *
 * <p>A lane of chart format v7 (Kelvin 2026-11-09 18:44, item 1), all functions of the beat:
 * <ul>
 *   <li>{@link #u} the fall direction (normalised when used; a zero vector counts as the default straight down);</li>
 *   <li>{@link #v} the origin, in chart axes;</li>
 *   <li>{@link #w}, {@link #h} the base size {@code a}: {@code w} across, {@code h} its length in depth;</li>
 *   <li>{@link #S}, {@link #F} where the sky face and the base (ground face) sit along the lane's up axis
 *       ({@code −u}) from the origin;</li>
 *   <li>{@link #s}, {@link #f} how far the column goes on past the sky face and past the base, fading out;</li>
 *   <li>{@link #o} its opacity (Kelvin 2026-11-09 21:37 「加回透明度」), clamped to 0..1 where used: it multiplies
 *       everything the lane draws and every key on it; 0 draws none of them. Judging does not change.</li>
 * </ul>
 * Nothing else: a lane shows up or goes away by its functions (for example {@code w} from 0, or {@code o}).
 */
public final class Lane7 {
    /** The id keys name it by (unique in the chart). */
    public int id;
    /** Fall direction, default (0, −1, 0). */
    public final Fn3 u = new Fn3(0f, -1f, 0f);
    /** Origin, default (0, 0, 0); the constructor sets x. */
    public final Fn3 v = new Fn3(0f, 0f, 0f);
    /** Base width (default 240) and length in depth (default 240). */
    public final Fn w = new Fn(240f), h = new Fn(240f);
    /** Sky face position (default 480) and how far the column goes on past it (default 820). */
    public final Fn S = new Fn(480f), s = new Fn(820f);
    /** Base position (default −480) and how far the column goes on past it (default 820). */
    public final Fn F = new Fn(-480f), f = new Fn(820f);
    /** Opacity (default 1). */
    public final Fn o = new Fn(1f);

    /** @param x the origin's x (its constant) */
    public Lane7(int id, float x) {
        this.id = id;
        v.x.constant = x;
    }
}
