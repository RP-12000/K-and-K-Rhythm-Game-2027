package org.kelvinizer.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;

/**
 * 触感反馈（振动）：在设置里打开后才生效，分轻、中、重三档（按下、印章冲击、升级等）。
 * 设备不支持时不振动；平台出错（例如缺少振动权限）时本次运行内自动关闭，不会崩溃。
 *
 * <p>Haptic feedback (DESIGN §1.5, P2). Off until Settings enables it ({@link #setEnabled}).
 * Every call is guarded by the haptic peripheral check, and a platform failure (for example a
 * missing {@code VIBRATE} permission) disables haptics for the rest of the session instead of
 * crashing. No GL state; safe to keep static.
 */
public final class Haptics {
    private static boolean enabled;
    private static boolean broken;

    private Haptics() { }

    /** Enables or disables haptics (Settings "haptics"). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** True when haptics are enabled and have not failed. */
    public static boolean isEnabled() {
        return enabled && !broken;
    }

    /** Light tick: presses, chips, slider detents. */
    public static void light() {
        fire(Input.VibrationType.LIGHT);
    }

    /** Medium thump: DESCEND, stamp impact. */
    public static void medium() {
        fire(Input.VibrationType.MEDIUM);
    }

    /** Heavy: level-up. */
    public static void heavy() {
        fire(Input.VibrationType.HEAVY);
    }

    private static void fire(Input.VibrationType type) {
        if (!enabled || broken || Gdx.input == null) return;
        try {
            if (!Gdx.input.isPeripheralAvailable(Input.Peripheral.HapticFeedback)) return;
            Gdx.input.vibrate(type);
        } catch (RuntimeException e) {
            broken = true;
        }
    }
}
