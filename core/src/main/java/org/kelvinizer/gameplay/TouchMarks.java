package org.kelvinizer.gameplay;

import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Theme;

import java.util.Arrays;

/**
 * 「显示触控」：游戏中每个按着的手指下面画一个白色的圈，中间一个小点，跟着手指移动；松开后圈放大一点并在 0.25 秒内淡出。
 *
 * <p>SHOW TOUCHES (Kelvin 2026-10-18 19:52: 「show touches还不起作用，触控点要明显一点」): a white ring of radius
 * {@link #R} with a faint fill and a centre dot under every pointer that is down, following it; on release the ring
 * widens by a third and fades over {@link #FADE_SEC}. The screen draws the marks over the notes and hit effects
 * (normal blend) and under the corner HUD. Until then the switch only flashed a faint dot where a touch fell outside
 * both judgement zones, which the zones of ±0.4 × the screen height (2026-10-12) leave almost nowhere. Positions
 * come from the engine's pointer table once a frame, on screen time, so a finger lifted while paused still fades.
 * No allocation.
 */
final class TouchMarks {
    /** Ring radius and stroke (world units), and the fade after release (s). */
    private static final float R = 64f, RING_W = 5f, DOT_R = 8f, FADE_SEC = 0.25f;

    private final Draw draw;
    private final float[] x = new float[GameEngine.MAX_POINTERS];
    private final float[] y = new float[GameEngine.MAX_POINTERS];
    /** Screen seconds when each pointer lifted (−100 = long ago). */
    private final float[] upAt = new float[GameEngine.MAX_POINTERS];
    private final boolean[] down = new boolean[GameEngine.MAX_POINTERS];

    /** Creates the marks over the shared atlas. */
    TouchMarks(Draw draw) {
        this.draw = draw;
        clear();
    }

    /** Forgets every pointer (a new run). */
    void clear() {
        Arrays.fill(down, false);
        Arrays.fill(upAt, -100f);
    }

    /** Follows the engine's pointers; {@code now} is screen seconds. */
    void update(GameEngine e, float now) {
        for (int p = 0; p < GameEngine.MAX_POINTERS; p++) {
            if (e.pointerDown(p)) {
                down[p] = true;
                x[p] = e.pointerX(p);
                y[p] = e.pointerY(p);
            } else if (down[p]) {
                down[p] = false;
                upAt[p] = now;
            }
        }
    }

    /** Draws a mark under every finger that is down and the fading rings of the ones just lifted (normal blend). */
    void draw(float now) {
        for (int p = 0; p < GameEngine.MAX_POINTERS; p++) {
            if (down[p]) {
                mark(x[p], y[p], R, 1f);
                continue;
            }
            float t = now - upAt[p];
            if (t < 0f || t >= FADE_SEC) continue;
            float f = t / FADE_SEC;
            mark(x[p], y[p], R * (1f + Anim.POW2_OUT.apply(f) / 3f), 1f - f);
        }
    }

    private void mark(float cx, float cy, float r, float a) {
        draw.disc(cx, cy, r, Theme.INK1, 0.14f * a);
        draw.circle(cx, cy, r, RING_W, Theme.INK1, 0.85f * a);
        draw.disc(cx, cy, DOT_R, Theme.INK1, 0.9f * a);
    }
}
