package org.kelvinizer.elements;

import java.util.List;

/**
 * 键的公转中心：参考画面（1920 × 1200，y 向上）里的一个绝对点。键按自己的角度绕它转，几个键共用一个中心就一起整体转；
 * 中心可以用事件移动，键跟着它平移，事件结束后中心停在事件的终点。只改绘制，判定、打击效果和声音仍在轨道上。
 *
 * <p>A lane note's orbit centre (Kelvin 2026-10-29 19:04: 「按键的旋转中心……定义为屏幕绝对坐标上的一点（公转）……按键平移也
 * 可以通过这个来实现了」). The note turns by its own angle ({@link Note#angleAt}) about the point ({@link #x()},
 * {@link #y()}) of the reference frame instead of about its pivot, so notes that share a centre turn as one shape. The
 * centre moves by {@link #xEvents()} and {@link #yEvents()} (absolute reference coordinates) and carries the note
 * with it; after an event it stays where the event left it, before the first event it is at (x, y). At the angle 0
 * with the centre at (x, y) the note is where it is without an orbit.
 *
 * <p>With a radius or an angle (Kelvin 2026-10-29 20:55: 「公转半径……和移动参数有一样小参数」; 21:13: a key that stays
 * parallel to its line can still orbit, {@link #polar()}) the centre of the note's head is placed at the radius from
 * the centre in the direction of the orbit's own angle (0° right, counter-clockwise; a negative radius reaches the
 * other way) while the orbit is in effect ({@link Note#inOrbitSpan}); the note's rotation only turns its body about
 * the head's centre. Radius and angle change by {@link #radiusEvents()} and {@link #angleEvents()} and stay where an
 * event leaves them, like the centre. {@link #conflict()} says which wins where the note's movement puts its head at
 * another height. With {@link #rigid()} (Kelvin 2026-11-02: 「大雪花在手机上略有错位」) the radius is scaled the same way
 * across and up on any screen, so keys that orbit one centre keep their shape. Immutable.
 */
public final class NoteOrbit {
    private final float x;
    private final float y;
    private final List<Events> xEvents;
    private final List<Events> yEvents;
    private final float radius;
    private final List<Events> radiusEvents;
    private final float angle;
    private final List<Events> angleEvents;
    private final int conflict;
    private final boolean rigid;

    /** {@link #conflict()}: not chosen yet (the chart editor warns; the game lets the orbit win). */
    public static final int CONFLICT_UNSET = 0;
    /** {@link #conflict()}: the orbit places the note's head. */
    public static final int CONFLICT_ORBIT = 1;
    /** {@link #conflict()}: the movement gives the head's height, the orbit its x. */
    public static final int CONFLICT_MOVE = 2;

    /**
     * @param x       centre x in reference units (NaN or infinite → 960, the middle)
     * @param y       centre y in reference units, up from the bottom (NaN or infinite → 600, the middle)
     * @param xEvents moves of the centre's x by beat (null for none)
     * @param yEvents moves of the centre's y by beat (null for none)
     */
    public NoteOrbit(float x, float y, List<Events> xEvents, List<Events> yEvents) {
        this(x, y, xEvents, yEvents, Float.NaN, null, Float.NaN, null, CONFLICT_UNSET);
    }

    /**
     * @param radius       the head's distance from the centre in reference units (NaN for none; infinite → none)
     * @param radiusEvents changes of the radius by beat (null for none)
     * @param angle        direction of the radius in degrees, 0 right, counter-clockwise (NaN or infinite for none)
     * @param angleEvents  changes of the angle by beat (null for none)
     * @param conflict     {@link #CONFLICT_UNSET}, {@link #CONFLICT_ORBIT} or {@link #CONFLICT_MOVE} (else unset)
     */
    public NoteOrbit(float x, float y, List<Events> xEvents, List<Events> yEvents, float radius,
                     List<Events> radiusEvents, float angle, List<Events> angleEvents, int conflict) {
        this(x, y, xEvents, yEvents, radius, radiusEvents, angle, angleEvents, conflict, false);
    }

    /**
     * Same as the constructor with a radius and angle, plus rigid placement.
     *
     * @param rigid the radius scales the same across and up on any screen ({@link #rigid()})
     */
    public NoteOrbit(float x, float y, List<Events> xEvents, List<Events> yEvents, float radius,
                     List<Events> radiusEvents, float angle, List<Events> angleEvents, int conflict, boolean rigid) {
        this.rigid = rigid;
        this.x = Float.isNaN(x) || Float.isInfinite(x) ? 960f : x;
        this.y = Float.isNaN(y) || Float.isInfinite(y) ? 600f : y;
        this.xEvents = Events.sortedCopy(xEvents);
        this.yEvents = Events.sortedCopy(yEvents);
        this.radius = Float.isInfinite(radius) ? Float.NaN : radius == 0f ? 0f : radius; // folds −0 into +0
        this.radiusEvents = Events.sortedCopy(radiusEvents);
        this.angle = Float.isInfinite(angle) ? Float.NaN : angle == 0f ? 0f : angle;
        this.angleEvents = Events.sortedCopy(angleEvents);
        this.conflict = conflict == CONFLICT_ORBIT || conflict == CONFLICT_MOVE ? conflict : CONFLICT_UNSET;
    }

    /** @return the centre's x before its first move, in reference units. */
    public float x() { return x; }

    /** @return the centre's y before its first move, in reference units (up from the bottom). */
    public float y() { return y; }

    /** @return the moves of the centre's x, sorted (empty for none). */
    public List<Events> xEvents() { return xEvents; }

    /** @return the moves of the centre's y, sorted (empty for none). */
    public List<Events> yEvents() { return yEvents; }

    /** @return the centre's x at {@code beat}: where the last move started by then has taken it, else {@link #x()}. */
    public float xAt(float beat) { return Events.heldValue(xEvents, beat, x); }

    /** @return the centre's y at {@code beat}, as {@link #xAt}. */
    public float yAt(float beat) { return Events.heldValue(yEvents, beat, y); }

    /**
     * @return true when the orbit places the note by its radius and angle (either given, with or without events);
     *         false for an orbit that turns the note from where it is placed (the 2026-10-29 19:04 orbit)
     */
    public boolean polar() {
        return !Float.isNaN(radius) || !radiusEvents.isEmpty() || !Float.isNaN(angle) || !angleEvents.isEmpty();
    }

    /** @return the radius before its first change in reference units, or NaN when the chart gives none. */
    public float radius() { return radius; }

    /** @return the changes of the radius, sorted (empty for none). */
    public List<Events> radiusEvents() { return radiusEvents; }

    /**
     * @return the radius at {@code beat}: where the last change started by then has taken it, else {@link #radius()}
     *         (without one, the first change's starting value; 0 without either)
     */
    public float radiusAt(float beat) {
        float base = !Float.isNaN(radius) ? radius : radiusEvents.isEmpty() ? 0f : radiusEvents.get(0).initialValue();
        return Events.heldValue(radiusEvents, beat, base);
    }

    /** @return the angle before its first change in degrees, or NaN when the chart gives none. */
    public float angle() { return angle; }

    /** @return the changes of the angle, sorted (empty for none). */
    public List<Events> angleEvents() { return angleEvents; }

    /**
     * @return the direction of the radius at {@code beat} in degrees (0 right, counter-clockwise), held like
     *         {@link #radiusAt}; 0 without an angle or changes
     */
    public float angleAt(float beat) {
        float base = !Float.isNaN(angle) ? angle : angleEvents.isEmpty() ? 0f : angleEvents.get(0).initialValue();
        return Events.heldValue(angleEvents, beat, base);
    }

    /** @return which wins where the movement disagrees: {@link #CONFLICT_UNSET}, {@link #CONFLICT_ORBIT}, ... */
    public int conflict() { return conflict; }

    /**
     * @return true when the radius scales by the smaller of the field's stretches both across and up (Kelvin
     *         2026-11-02, {@code orbitRigid}), so a shape of keys round one centre keeps its proportions on any screen;
     *         false: across by the width's stretch and up by the height's, like any position (the centre always maps
     *         that way)
     */
    public boolean rigid() { return rigid; }

    @Override
    public String toString() {
        return "orbit (" + x + ", " + y + ")" + (xEvents.isEmpty() && yEvents.isEmpty() ? ""
            : " " + (xEvents.size() + yEvents.size()) + " move(s)")
            + (polar() ? " radius " + (Float.isNaN(radius) ? "-" : radius)
            + " angle " + (Float.isNaN(angle) ? "-" : angle)
            + (radiusEvents.isEmpty() && angleEvents.isEmpty() ? ""
            : " " + (radiusEvents.size() + angleEvents.size()) + " change(s)") : "")
            + (conflict == CONFLICT_UNSET ? ""
            : conflict == CONFLICT_ORBIT ? " orbit wins" : " move wins") + (rigid ? " rigid" : "");
    }
}
