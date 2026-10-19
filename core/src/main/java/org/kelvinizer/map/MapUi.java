package org.kelvinizer.map;

import org.kelvinizer.ui.Ui;

/**
 * 地图画面的界面缩放：HUD、歌曲卡片、筛选面板、列表和长按浮窗的尺寸都乘以 {@link #k()}，地图世界本身不缩放。
 * 字号经 {@link #cap(float, float)} 换算，不会小于当前界面缩放下的最小字号。
 *
 * <p>The HUD / menu scale of the map screen (DESIGN §0.3, §1.1).
 *
 * <p>Every HUD, card and overlay dimension in DESIGN §3.9–§4 is given at uiScale 1. The map's
 * HUD, the song card, the filter panel, the list and the peek tooltip multiply those by
 * {@link #k()} about each element's anchor (an edge or a corner); the map world, its nodes and
 * its labels are never scaled. Text caps go through {@link #cap(float, float)}, which also keeps
 * every text at or above its §1.1 minimum for the current {@code uiScale}, even where a container
 * had to shrink its own scale to fit (the song card on short screens).
 *
 * <p>Stateless; every method is allocation-free.
 */
final class MapUi {
    private MapUi() { }

    /** The HUD scale: {@link Ui#uiScale()} (Settings 0.9–1.3, or Auto 1.15 on phones). */
    static float k() {
        return Ui.uiScale();
    }

    /**
     * The cap to draw a text designed at {@code cap} (uiScale 1) with, in a container scaled by
     * {@code k}: {@code cap · k}, clamped to the §1.1 minimum of its class: essential text
     * (designed at ≥ 20) to {@link Ui#minEssentialCap()}, secondary text (18) to
     * {@code 18 · uiScale}, decorative micro text (16) to {@code 16 · uiScale}.
     */
    static float cap(float cap, float k) {
        float c = cap * k;
        float u = Ui.uiScale();
        float min;
        if (cap >= 20f) min = Ui.minEssentialCap();
        else if (cap >= 18f) min = 18f * u;
        else if (cap >= 16f) min = 16f * u;
        else return c;
        return Math.max(c, min);
    }

    /** {@link #cap(float, float)} at the HUD scale {@link #k()}. */
    static float cap(float cap) {
        return cap(cap, k());
    }

    /** Minimum hit size {@code P} (DESIGN §1.1), never below a visual of {@code visual}. */
    static float hit(float visual) {
        return Math.max(Ui.touchTarget(), visual);
    }
}
