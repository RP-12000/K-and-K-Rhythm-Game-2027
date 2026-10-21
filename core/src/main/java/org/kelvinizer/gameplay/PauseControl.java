package org.kelvinizer.gameplay;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.Params;
import org.kelvinizer.elements.JudgementLine;
import org.kelvinizer.elements.Line;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.ui.Ui;

/**
 * 右上角暂停键的位置与输入规则：靠右，上下居中在屏幕顶边和天线之间；300 ms 内在触摸区双击即暂停，判定了音符的触摸既不计数也不取消。
 * 纯 Java，不依赖 GL，测试可以直接用真实引擎和事件时间戳驱动它。
 *
 * <p>The top-right pause control (Kelvin's decisions 2026-10-12, DESIGN §21.5): where it sits and how
 * a touch operates it. Pure Java (no GL), so tests drive it with a real {@link GameEngine} and event
 * timestamps. {@link GameplayScreen} hands it every touch after the engine has seen that touch, and
 * {@link GameplayHud} draws the icon from its geometry.
 *
 * <p><b>Geometry</b> (world units × the HUD scale {@code k = uiScale}): a ring of radius
 * {@link #RING_R} with a {@link #RING_STROKE} stroke and two {@link #BAR_W} × {@link #BAR_H} bars
 * whose centres sit {@link #BAR_DX} either side of the ring centre. The centre is
 * {@link #EDGE_RIGHT}·k + r left of the right edge and, since Kelvin 2026-10-20 05:25, halfway
 * between the top edge and the default sky line ({@link JudgementLine#DEFAULT_UPPER} of the
 * 1920×1200 frame, a tenth of the world height under the visible top), where an edge is the visible
 * screen edge less the cutout inset and the Settings edge padding ({@code Ui.safeRight() +
 * Ui.MIN_MARGIN}, {@code Ui.safeTop() + Ui.MIN_MARGIN}); the ring never comes closer than
 * {@link #TOP_CLEAR} to the top edge, which only a large edge padding reaches. At 1920×1080 and k 1
 * the centre is (W − 58, 1026) and the ring's right edge is W − 26 (mock-up P14, 04:33).
 * The hit box is anchored to that top-right corner and reaches the screen edges; it is
 * {@code max(P, P·k)} on each side ({@code P = Ui.touchTarget()}, the §0 minimum), never smaller
 * than the minimum target and always around the whole ring.
 *
 * <p><b>Input</b> ({@link Settings#pauseMode}):
 * <ul>
 *   <li>{@link Settings#PAUSE_DOUBLE_TAP} (default): two counted touch-downs in the box at most
 *       {@link #DOUBLE_TAP_NS} apart (event timestamps) pause; the second down triggers. A first
 *       counted down returns {@link #HINT} and the HUD plays the first-tap hint.</li>
 *   <li>{@link Settings#PAUSE_HOLD}: a counted down arms the control; staying in the box for
 *       {@link #HOLD_SEC} pauses. Leaving the box, lifting early, grabbing a hold or covering a
 *       running TIDE STREAM disarms it.</li>
 *   <li>{@link Settings#PAUSE_SINGLE_TAP}: a counted down arms it; lifting inside the box pauses.</li>
 * </ul>
 * A down counts only when it judges nothing: it did not judge a note or TIDE
 * ({@link GameEngine#INPUT_JUDGED}), did not re-grab a hold ({@link GameEngine#INPUT_REGRAB}), did
 * not touch a MINE or a LANE MINE ({@link GameEngine#lastInputMine()}) and does not cover a running
 * TIDE STREAM (it is in the zone of a line whose TIDE STREAM runs). Such a
 * down neither counts nor cancels a pending first tap, and neither does a down outside the box.
 * Drags and fingers resting in the box never count: only downs do. Every touch reaches gameplay
 * first, so notes under the corner stay playable. BACK, Esc and the keyboard's Pause key pause at
 * once ({@link #pausesAtOnce}); P stays the upper row's lane key (ARCHITECTURE §13.2).
 * Nothing is allocated after construction.
 */
public final class PauseControl {
    /** Ring radius at HUD scale 1 (64 across). */
    public static final float RING_R = 32f;
    /** Ring stroke at HUD scale 1. */
    public static final float RING_STROKE = 3f;
    /** Width of each pause bar at HUD scale 1. */
    public static final float BAR_W = 6f;
    /** Height of each pause bar at HUD scale 1. */
    public static final float BAR_H = 22f;
    /** Distance from the ring centre to each bar's centre at HUD scale 1. */
    public static final float BAR_DX = 7f;
    /** Gap between the ring's right edge and the right edge at HUD scale 1. */
    public static final float EDGE_RIGHT = 26f;
    /** Least room between the ring's outer edge and the top edge, clear of the progress hairline. */
    public static final float TOP_CLEAR = 10f;
    /** Room kept between the ring and the hit box's inner edges at HUD scale 1. */
    public static final float BOX_SLACK = 8f;
    /** Longest interval between the two downs of a double tap (DESIGN §1.1: 300 ms), in ns. */
    public static final long DOUBLE_TAP_NS = 300_000_000L;
    /** Hold time of {@link Settings#PAUSE_HOLD} (s). */
    public static final float HOLD_SEC = 0.35f;
    /** Time an early-released hold ring takes to drain (s). */
    public static final float HOLD_DRAIN_SEC = 0.15f;
    /** Length of the first-tap hint (s): the double-tap window. */
    public static final float HINT_SEC = 0.30f;

    /** {@link #touchDown} result: the touch does not operate the control. */
    public static final int NONE = 0;
    /** {@link #touchDown} result: first counted tap of a double tap; show the hint. */
    public static final int HINT = 1;
    /** {@link #touchDown} result: the hold or single-tap mode armed the control. */
    public static final int ARMED = 2;
    /** Result: pause now. */
    public static final int PAUSE = 3;

    private float k = 1f;
    private float cx;
    private float cy;
    private float r;
    private float sky;
    private float boxX;
    private float boxY;
    private float boxW;
    private float boxH;
    private float edgeRight;
    private float edgeTop;

    private boolean pending;
    private long firstNs;
    private int armPointer = -1;
    private float held;

    /** Creates the control and lays it out for the current viewport. */
    public PauseControl() {
        layout();
    }

    /** Recomputes the geometry from {@link Ui} (cheap; call once per frame and after a resize). */
    public void layout() {
        k = MathUtils.clamp(Ui.uiScale(), 0.9f, 1.3f);
        edgeRight = Ui.safeRight() + Ui.MIN_MARGIN;
        edgeTop = Ui.safeTop() + Ui.MIN_MARGIN;
        r = RING_R * k;
        cx = edgeRight - EDGE_RIGHT * k - r;
        // the default sky line, from the world height (as Field maps it) so a reset Field cannot move it
        sky = Ui.top() - Ui.worldH() * (Params.REF_H - JudgementLine.DEFAULT_UPPER) / Params.REF_H;
        cy = Math.min((edgeTop + sky) * 0.5f, edgeTop - TOP_CLEAR - r - RING_STROKE * k * 0.5f);
        float p = Ui.touchTarget() * Math.max(1f, k);
        float w = Math.max(p, (EDGE_RIGHT + 2f * RING_R + BOX_SLACK) * k);
        float h = Math.max(p, edgeTop - cy + (RING_R + BOX_SLACK) * k);
        boxX = edgeRight - w;
        boxY = edgeTop - h;
        // the box reaches the screen edges (a cutout or edge padding only moves the icon)
        boxW = Math.max(edgeRight, Ui.right()) - boxX;
        boxH = Math.max(edgeTop, Ui.top()) - boxY;
    }

    // ------------------------------------------------------------------ geometry

    /** HUD scale the control was laid out with. */
    public float scale() {
        return k;
    }

    /** Ring centre x. */
    public float cx() {
        return cx;
    }

    /** Ring centre y. */
    public float cy() {
        return cy;
    }

    /** The default sky line's y, the foot of the top band the ring and the HUD score are laid out in. */
    public float sky() {
        return sky;
    }

    /** Ring radius (centre of the stroke). */
    public float radius() {
        return r;
    }

    /** Ring stroke width. */
    public float stroke() {
        return RING_STROKE * k;
    }

    /** Left edge of the hit box. */
    public float boxX() {
        return boxX;
    }

    /** Bottom edge of the hit box. */
    public float boxY() {
        return boxY;
    }

    /** Width of the hit box (to the screen's right edge). */
    public float boxW() {
        return boxW;
    }

    /** Height of the hit box (to the screen's top edge). */
    public float boxH() {
        return boxH;
    }

    /** Right edge the ring is placed from (visible edge less cutout inset and edge padding). */
    public float edgeRight() {
        return edgeRight;
    }

    /** Top edge the ring is placed from (visible edge less cutout inset and edge padding). */
    public float edgeTop() {
        return edgeTop;
    }

    /** True when (x, y) is inside the hit box. */
    public boolean contains(float x, float y) {
        return x >= boxX && x <= boxX + boxW && y >= boxY && y <= boxY + boxH;
    }

    // ------------------------------------------------------------------ input

    /**
     * True when this touch-down did something in gameplay: it judged a note or TIDE, re-grabbed a
     * hold, touched a MINE or a LANE MINE, or covers a running TIDE STREAM (its y is in the zone of a
     * line whose TIDE STREAM runs). Such a touch never operates the pause control.
     *
     * @param result the engine's {@code INPUT_*} result for this down
     */
    public static boolean judges(GameEngine e, int result, float y) {
        if (result == GameEngine.INPUT_JUDGED || result == GameEngine.INPUT_REGRAB) return true;
        if (result != GameEngine.INPUT_EMPTY || e.autoplay()) return false;
        if (e.lastInputMine()) return true;
        return covers(e, Line.LOWER, y) || covers(e, Line.UPPER, y);
    }

    private static boolean covers(GameEngine e, Line line, float y) {
        if (!e.tideRunning(line)) return false;
        return e.lastInputLine() == line || Math.abs(y - e.lineY(line)) <= Field.zoneHalf();
    }

    /**
     * A pointer went down at (x, y), after the engine has seen it.
     *
     * @param result  the engine's {@code INPUT_*} result for this down
     * @param nanos   the event timestamp (the same one the engine judged with)
     * @param mode    {@link Settings#pauseMode}
     * @param enabled false while the control is off (paused, countdown, finished run)
     * @return {@link #NONE}, {@link #HINT}, {@link #ARMED} or {@link #PAUSE}
     */
    public int touchDown(GameEngine e, int result, int pointer, float x, float y, long nanos, int mode,
                         boolean enabled) {
        if (!enabled || !contains(x, y) || judges(e, result, y)) return NONE;
        if (mode == Settings.PAUSE_DOUBLE_TAP) {
            long dt = nanos - firstNs;
            if (pending && dt >= 0L && dt <= DOUBLE_TAP_NS) {
                pending = false;
                return PAUSE;
            }
            pending = true;
            firstNs = nanos;
            return HINT;
        }
        armPointer = pointer;
        held = 0f;
        return ARMED;
    }

    /** A pointer moved: an armed pointer that leaves the box disarms the control. */
    public void touchDragged(int pointer, float x, float y) {
        if (pointer == armPointer && !contains(x, y)) armPointer = -1;
    }

    /**
     * A pointer went up.
     *
     * @return {@link #PAUSE} when the single-tap mode's armed pointer lifts inside the box, else
     *         {@link #NONE}
     */
    public int touchUp(int pointer, float x, float y, int mode) {
        if (pointer != armPointer) return NONE;
        armPointer = -1;
        return mode == Settings.PAUSE_SINGLE_TAP && contains(x, y) ? PAUSE : NONE;
    }

    /** A pointer was cancelled. */
    public void touchCancelled(int pointer) {
        if (pointer == armPointer) armPointer = -1;
    }

    /**
     * Per-frame step of the hold mode: fills the hold, disarms a pointer that grabbed a hold or
     * covers a running TIDE STREAM, drains an early release.
     *
     * @param dt screen delta (s)
     * @return {@link #PAUSE} when the hold completed, else {@link #NONE}
     */
    public int update(float dt, GameEngine e, int mode) {
        if (armPointer >= 0 && mode == Settings.PAUSE_HOLD) {
            Line l = e.pointerLine(armPointer);
            if (!e.pointerDown(armPointer) || e.pointerHold(armPointer) >= 0
                || l != null && !e.autoplay() && e.tideRunning(l)) {
                armPointer = -1;
                return NONE;
            }
            held += dt;
            if (held >= HOLD_SEC) {
                armPointer = -1;
                held = 0f;
                return PAUSE;
            }
        } else if (armPointer < 0 && held > 0f) {
            held = Math.max(0f, held - dt * HOLD_SEC / HOLD_DRAIN_SEC);
        }
        return NONE;
    }

    /** Hold-mode ring progress 0..1. */
    public float holdProgress() {
        return Math.min(1f, held / HOLD_SEC);
    }

    /** True while a first tap waits for its second at event time {@code nanos}. */
    public boolean pending(long nanos) {
        long dt = nanos - firstNs;
        return pending && dt >= 0L && dt <= DOUBLE_TAP_NS;
    }

    /** Pointer arming the hold / single-tap mode, or −1. */
    public int armedPointer() {
        return armPointer;
    }

    /** Forgets a pending first tap and any armed pointer (on pause, resume and a new run). */
    public void reset() {
        pending = false;
        armPointer = -1;
        held = 0f;
    }

    /**
     * True for keys that pause a running song at once: BACK, Esc and the keyboard's Pause key. P is
     * not one of them: it is the upper row's lane key (ARCHITECTURE §13.2).
     */
    public static boolean pausesAtOnce(int keycode) {
        return keycode == Input.Keys.BACK || keycode == Input.Keys.ESCAPE || keycode == Input.Keys.PAUSE;
    }
}
