package org.kelvinizer.ui;

/**
 * 系统边缘手势的开关：安卓 10 起从屏幕左右边缘划进来是系统的返回，系统一接手就会取消屏幕上所有的手指（断触），所以游玩时把边缘要过来。
 *
 * <p>The platform's switch for the system's edge gestures (Kelvin 2026-11-06: players reported dropped touches). On
 * Android 10+ a swipe in from the left or right edge is the system's Back, and when the system takes a gesture it
 * cancels every touch on the screen, so gameplay asks for the edges while it is shown. The desktop has none.
 */
public interface EdgeGuard {
    /**
     * @param on true while gameplay needs the screen's edges for itself, false to give them back to the system
     *           (called on the render thread)
     */
    void guardEdges(boolean on);
}
