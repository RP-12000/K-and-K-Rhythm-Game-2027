package org.kelvinizer.gameplay;

import com.badlogic.gdx.Input.Keys;
import org.kelvinizer.elements.Line;

/**
 * 桌面键位表：两排各 10 个键分别对应地面线和天空线的 −5..−1、1..5 号轨道，中间的 G/H、T/Y 是插入的 0 号轨，
 * 空格只判定地面线的潮汐。每排的任意键也能判定该线的潮汐，±5 以外的轨道没有默认按键；引擎按这张表把按键换成线和轨道。
 *
 * <p>Desktop key map (ARCHITECTURE §13.2), shared by the engine, the field's held-lane feedback
 * and GameplayScreen's key handling.
 *
 * <ul>
 *   <li>Lower line, lanes −5..−1 = CapsLock, A, S, D, F and 1..5 = J, K, L, ;, '</li>
 *   <li>Upper line, lanes −5..−1 = Tab, Q, W, E, R and 1..5 = U, I, O, P, [</li>
 *   <li>Lane 0 (only exists when the chart inserts it, CHART_FORMAT §3.6): G or H on the lower
 *       line, T or Y on the upper line. Without a lane 0 these keys take TIDE notes only.</li>
 *   <li>Space: lower-line TIDE notes only. Every key of a row also takes that line's TIDE notes.</li>
 * </ul>
 * Lanes beyond ±5 have no default key. Each key is a "slot" 0..{@link #SLOTS}−1; every slot below
 * {@link #SPACE} is a lane key.
 */
public final class GameKeys {
    /** Number of slots: 10 lower-row keys, 10 upper-row keys, 4 lane-0 keys, Space. */
    public static final int SLOTS = 25;
    /** Slot of the Space key (the last slot). */
    public static final int SPACE = 24;
    /** First lane-0 slot: G, H (lower), T, Y (upper). */
    private static final int ZERO = 20;

    private static final int[] LOWER = {Keys.CAPS_LOCK, Keys.A, Keys.S, Keys.D, Keys.F,
        Keys.J, Keys.K, Keys.L, Keys.SEMICOLON, Keys.APOSTROPHE};
    private static final int[] UPPER = {Keys.TAB, Keys.Q, Keys.W, Keys.E, Keys.R,
        Keys.U, Keys.I, Keys.O, Keys.P, Keys.LEFT_BRACKET};
    private static final int[] ZERO_KEYS = {Keys.G, Keys.H, Keys.T, Keys.Y};
    private static final int[] IDS = {-5, -4, -3, -2, -1, 1, 2, 3, 4, 5};
    private static final int[] SLOT_OF = new int[Keys.MAX_KEYCODE + 1];

    static {
        java.util.Arrays.fill(SLOT_OF, -1);
        for (int i = 0; i < 10; i++) {
            SLOT_OF[LOWER[i]] = i;
            SLOT_OF[UPPER[i]] = 10 + i;
        }
        for (int i = 0; i < ZERO_KEYS.length; i++) SLOT_OF[ZERO_KEYS[i]] = ZERO + i;
        SLOT_OF[Keys.SPACE] = SPACE;
    }

    private GameKeys() {
    }

    /** @return the slot of {@code keycode}, or −1 when it is not a gameplay key. */
    public static int slotOf(int keycode) {
        return keycode >= 0 && keycode < SLOT_OF.length ? SLOT_OF[keycode] : -1;
    }

    /** @return the libGDX key code of {@code slot}. */
    public static int keycode(int slot) {
        if (slot == SPACE) return Keys.SPACE;
        if (slot >= ZERO) return ZERO_KEYS[slot - ZERO];
        return slot < 10 ? LOWER[slot] : UPPER[slot - 10];
    }

    /** @return the line a slot plays on (Space = lower). */
    public static Line line(int slot) {
        return slot >= 10 && slot < ZERO || slot == ZERO + 2 || slot == ZERO + 3 ? Line.UPPER : Line.LOWER;
    }

    /** @return the lane id of a slot: 0 for the lane-0 keys, and also 0 for Space (the engine gives Space no lane). */
    public static int laneId(int slot) {
        return slot >= ZERO ? 0 : IDS[slot % 10];
    }

    /** @return the slot for (line, lane id) (G / T for lane 0), or −1 when that lane has no key. */
    public static int slotFor(Line line, int laneId) {
        if (laneId == 0) return line == Line.UPPER ? ZERO + 2 : ZERO;
        for (int i = 0; i < 10; i++) {
            if (IDS[i] == laneId) return line == Line.UPPER ? 10 + i : i;
        }
        return -1;
    }
}
