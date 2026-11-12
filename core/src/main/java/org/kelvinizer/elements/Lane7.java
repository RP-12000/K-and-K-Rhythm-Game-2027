package org.kelvinizer.elements;

/**
 * 谱面格式 v7 的轨道：只有下落向量朝向 u、法向量 n、原点 v、底面大小 a、天面高度 S、天面延申 s、底面高度 F、底面延申 f、透明度 o、轨道本身的透明度 l 和水雷 m。
 *
 * <p>A lane of chart format v7 (Kelvin 2026-11-09 18:44, item 1), all functions of the beat:
 * <ul>
 *   <li>{@link #u} the fall direction (normalised when used; a zero vector counts as the default straight down);</li>
 *   <li>{@link #n} the normal its front faces (Kelvin 2026-11-10 17:59 「那就加一个法向量朝向」, 18:00 「和下落向量
 *       一样」; normalised when used): the lane turns about its up axis so its front faces n as near as its fall lets
 *       it. Left at its default, to the player, it is not written and the lane turns the shortest way to its fall
 *       ({@link LaneLayout#turn});</li>
 *   <li>{@link #v} the origin, in chart axes;</li>
 *   <li>{@link #w}, {@link #h} the base size {@code a}: {@code w} across, {@code h} its length in depth;</li>
 *   <li>{@link #S}, {@link #F} where the sky face and the base (ground face) sit along the lane's up axis
 *       ({@code −u}) from the origin;</li>
 *   <li>{@link #s}, {@link #f} how far the column goes on past the sky face and past the base, fading out;</li>
 *   <li>{@link #o} its opacity (Kelvin 2026-11-09 21:37 「加回透明度」), clamped to 0..1 where used: it multiplies
 *       everything the lane draws and every key on it; 0 draws none of them. Judging does not change.</li>
 *   <li>{@link #l} its own opacity (Kelvin 2026-11-11 02:12 「这种浪花水雷理论上也不能出现轨道」, chart version 2.1),
 *       clamped to 0..1 where used: it multiplies only what the lane itself draws (its column, faces, edges, pieces of
 *       the judgement lines and their glows), not its keys, so 0 leaves a lane that only carries keys.</li>
 *   <li>{@link #m} mine (Kelvin 2026-11-10 23:01 「在一定时间内（一维事件）这些轨道会变红，效力跟轨道水雷一样，对天地同时
 *       生效」, 2026-11-11 01:48 「整个红色轨道作为另一种性质」, chart version 2.1): while it is above 0 the lane is a mine
 *       lane, its sky and ground faces judged as LANE MINEs ({@link ChartV7Runtime}), and it is drawn as red as it is,
 *       up to 1. Left at 0 it is not written. Wave and tide mines stay keys of their own.</li>
 * </ul>
 * Nothing else: a lane shows up or goes away by its functions (for example {@code w} from 0, or {@code o}).
 */
public final class Lane7 {
    /** The id keys name it by (unique in the chart). */
    public int id;
    /**
     * The id of the group it is in ({@link Group7}, chart version 2.1), 0 for none: then its functions are in the
     * group's axes, so it moves, turns, sizes and fades with the group.
     */
    public int g;
    /** Fall direction, default (0, −1, 0). */
    public final Fn3 u = new Fn3(0f, -1f, 0f);
    /** Facing, default (0, 0, −1): to the player, not written. */
    public final Fn3 n = new Fn3(0f, 0f, -1f);
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
    /** Own opacity (default 1): the lane's own drawing only, not its keys. */
    public final Fn l = new Fn(1f);
    /** Mine (default 0: none): above 0 a red lane, whose keys can only be RESONANCE ({@link Note#fromLane}). */
    public final Fn m = new Fn(0f);

    /** @param x the origin's x (its constant) */
    public Lane7(int id, float x) {
        this.id = id;
        v.x.constant = x;
    }

    /** @return true when {@link #n} is written (a function of it off its default): the lane faces it */
    public boolean facing() {
        return written(n.x) || written(n.y) || written(n.z);
    }

    /**
     * Its axes at {@code beat} into {@code out}, as the game turns it ({@link LaneLayout#turn}, facing {@link #n} when
     * {@link #facing}): across, up and depth, three floats each.
     *
     * @return true when it is turned (its axes are not the chart's)
     */
    public boolean axes(float beat, float[] out) {
        boolean f = facing();
        return LaneLayout.turn(u.x.at(beat), u.y.at(beat), u.z.at(beat),
            f ? n.x.at(beat) : 0f, f ? n.y.at(beat) : 0f, f ? n.z.at(beat) : 0f, out);
    }

    private static boolean written(Fn f) {
        return !f.isEmpty() || f.constant != f.def;
    }
}
