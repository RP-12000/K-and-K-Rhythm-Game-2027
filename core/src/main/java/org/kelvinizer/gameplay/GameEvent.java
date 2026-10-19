package org.kelvinizer.gameplay;

import org.kelvinizer.elements.Line;
import org.kelvinizer.play.Judgement;

/**
 * 游戏引擎事件流中的一条：判定、长条开始/结束、水雷接触等，供画面和反馈层读取。
 *
 * <p>One entry of the {@link GameEngine} event stream, for the renderer (judgement word, lane
 * feedback, hit effects, hold ribbons, mine shocks). The consumer owns one instance and fills it
 * with {@link GameEngine#pollEvent(GameEvent)}, so reading events never allocates.
 *
 * <p>Coordinates are world units at the moment of the event: {@link #x} / {@link #y} are where
 * the feedback belongs (the lane centre on the line for lane notes, the touch x on the line for
 * TIDE notes and mines), {@link #touchX} / {@link #touchY} where the finger was (lane centre and
 * line y for keyboard input and autoplay).
 *
 * <p>TIDE STREAMs (ARCHITECTURE §19) use the existing kinds with {@link #FLAG_TIDE_STREAM}: the
 * head is the TIDE group's {@link #HIT} (flag set when a stream of the group started running; the
 * group's drops are scored at once, the streams when they end), its end is a {@link #HOLD_END}
 * with {@link #FLAG_UNIVERSAL} | {@link #FLAG_TIDE_STREAM} ({@link #FLAG_COMPLETED} when
 * completed, LOST when broken), lane −1 and {@link #x} at the window centre. A TIDE STREAM never
 * emits {@link #HOLD_START} (nothing owns it, so nothing re-grabs it). A missed group's
 * {@link #LOST} carries the flag too when it held a stream.
 *
 * <p>Joint judging (ARCHITECTURE §20): a tap or lane key that judges a lane note also judges the
 * TIDE group of that line at exactly the same beat; the lane note's {@link #HIT} /
 * {@link #HOLD_START} comes first, then the group's {@link #HIT} with {@link #FLAG_JOINT}.
 *
 * <p>Mines (owner 2026-10-12): every touched MINE and every LANE MINE contact is one {@link #MINE}
 * event; the screen shows the red LOST word ({@code Theme.MINE_LOST}) and the shock for each.
 */
public final class GameEvent {
    /**
     * A tap, hold-less lane note or a TIDE group was hit (RESONANCE / ECHO); with
     * {@link #FLAG_TIDE_STREAM} the group started at least one TIDE STREAM.
     */
    public static final int HIT = 0;
    /** A note (or a whole TIDE group) passed its window untouched and became LOST. */
    public static final int LOST = 1;
    /** A lane hold head was hit; the hold is now running ({@link #judgement} = head grade). */
    public static final int HOLD_START = 2;
    /**
     * A hold or a TIDE STREAM ({@link #FLAG_TIDE_STREAM}) finished: completed
     * ({@link #FLAG_COMPLETED}, {@link #judgement} = head grade) or broken (released early, left
     * its lane/zone too long, or its line uncovered too long; {@link #judgement} = LOST).
     */
    public static final int HOLD_END = 3;
    /**
     * A mine was touched, or a LANE MINE was contacted ({@link #FLAG_LANE_MINE}; {@link #count} =
     * the contact number, 1 for the first): combo broken, no judgement, no score change. The
     * screen shows the red LOST word and the mine shock.
     */
    public static final int MINE = 4;
    /** A touch or key press that judged nothing ({@link #line} null = outside both zones). */
    public static final int EMPTY = 5;

    /** The judged note was in the neighbouring lane (30 % tolerance). */
    public static final int FLAG_NEIGHBOUR = 1;
    /** The event concerns a TIDE (universal) group. */
    public static final int FLAG_UNIVERSAL = 1 << 1;
    /** HOLD_END only: the hold was completed. */
    public static final int FLAG_COMPLETED = 1 << 2;
    /** Caused by a keyboard key. */
    public static final int FLAG_KEYBOARD = 1 << 3;
    /** Caused by autoplay. */
    public static final int FLAG_AUTO = 1 << 4;
    /** HOLD_START only: the pointer re-grabbed a hold detached by a pause (no new judgement). */
    public static final int FLAG_REGRAB = 1 << 5;
    /**
     * The event concerns a TIDE STREAM (with {@link #FLAG_UNIVERSAL}): HIT = a TIDE group whose
     * judgement started at least one TIDE STREAM; HOLD_END = a TIDE STREAM completed or broke;
     * LOST = a missed group that held one.
     */
    public static final int FLAG_TIDE_STREAM = 1 << 6;
    /** MINE only: a LANE MINE contact (not a plain MINE touch). */
    public static final int FLAG_LANE_MINE = 1 << 7;
    /**
     * HIT of a TIDE group (with {@link #FLAG_UNIVERSAL}) judged together with the lane note just
     * hit by the same tap or key at exactly the same beat (ARCHITECTURE §20 joint judging).
     */
    public static final int FLAG_JOINT = 1 << 8;

    /** Event type ({@link #HIT} … {@link #EMPTY}). */
    public int type;
    /** Grade, or null (MINE, EMPTY). */
    public Judgement judgement;
    /** Signed timing error in ms (judge time − note time; negative = early); 0 when not timed. */
    public float dtMs;
    /** Feedback x (world units). */
    public float x;
    /** Feedback y: the line y (world units); the touch y for a stray EMPTY. */
    public float y;
    /** Touch x (world units). */
    public float touchX;
    /** Touch y (world units). */
    public float touchY;
    /** Lane index in the chart's lane order (the resolved lane for EMPTY), or −1. */
    public int lane;
    /** Line of the note (or of the zone touched for EMPTY), or null. */
    public Line line;
    /** Note index in {@code Chart.notes()} (the first member for a TIDE group), or −1. */
    public int note;
    /**
     * Notes covered by this event: the TIDE group size, else 1 (0 for EMPTY); for a LANE MINE
     * contact the contact number (1, 2, …).
     */
    public int count;
    /** Pointer id, −1 for keyboard, −2 for autoplay or the engine itself. */
    public int pointer;
    /** Judge time of the event in song ms. */
    public double timeMs;
    /** Bit set of {@code FLAG_*}. */
    public int flags;

    /** @return true when {@code flag} is set. */
    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    /** @return a short readable form (debugging only; allocates). */
    @Override
    public String toString() {
        return "GameEvent(" + type + ", " + judgement + ", dt " + dtMs + ", note " + note + ", lane " + lane
            + ", line " + line + ", x " + x + ", y " + y + ", count " + count + ", flags " + flags + ")";
    }
}
