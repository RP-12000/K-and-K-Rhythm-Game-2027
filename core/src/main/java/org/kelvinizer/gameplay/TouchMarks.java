package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Theme;

import java.util.Arrays;

/**
 * 「显示触控」：游戏中每个按着的手指下面画一个白色的圈，中间一个小点，跟着手指移动；松开后圈放大一点并在 0.25 秒内淡出。
 * 在漩涡里按下的手指不管有没有打开「显示触控」都画 glitch 触点（直径和一个键一样宽），这根手指只画它。
 *
 * <p>SHOW TOUCHES (Kelvin 2026-10-18 19:52: 「show touches还不起作用，触控点要明显一点」): a white ring of radius
 * {@link #R} with a faint fill and a centre dot under every pointer that is down, following it; on release the ring
 * widens by a third and fades over {@link #FADE_SEC}. The screen draws the marks over the notes and hit effects
 * (normal blend) and under the corner HUD. Until then the switch only flashed a faint dot where a touch fell outside
 * both judgement zones, which the zones of ±0.4 × the screen height (2026-10-12; ±160 in 7.184–7.188) left almost
 * nowhere. A finger starts its mark when it goes down ({@link #down}, so a tap that lifts within the same frame still
 * shows); where it is and when it lifts come from the engine's pointer table once a frame, on screen time, so a finger
 * lifted while paused still fades. No allocation.
 *
 * <p>A finger that went down inside a veil (ARCHITECTURE §22, judging nothing until it lifts) gets the veil's glitch
 * mark instead, whether SHOW TOUCHES is on or not (Kelvin 2026-10-23 16:47, 16:55, 17:01): as wide as a note, drawn
 * by {@link VeilRenderer#drawTouch}, coming up over {@link #APPEAR_SEC}, following the finger also after it slides out
 * of the veil, and widening by a third and fading like a ring when it lifts.
 */
final class TouchMarks {
    /** Ring radius and stroke (world units), and the fade after release (s). */
    private static final float R = 64f, RING_W = 5f, DOT_R = 8f, FADE_SEC = 0.25f;
    /** Seconds a glitch mark takes to come up. */
    private static final float APPEAR_SEC = 0.08f;

    private final Draw draw;
    private final float[] x = new float[GameEngine.MAX_POINTERS];
    private final float[] y = new float[GameEngine.MAX_POINTERS];
    /** Screen seconds when each pointer went down and lifted (−100 = long ago). */
    private final float[] downAt = new float[GameEngine.MAX_POINTERS];
    private final float[] upAt = new float[GameEngine.MAX_POINTERS];
    private final boolean[] down = new boolean[GameEngine.MAX_POINTERS];
    /** The pointer went down inside a veil (kept after the lift for its fade). */
    private final boolean[] veiled = new boolean[GameEngine.MAX_POINTERS];

    /** Creates the marks over the shared atlas. */
    TouchMarks(Draw draw) {
        this.draw = draw;
        clear();
    }

    /** Forgets every pointer (a new run). */
    void clear() {
        Arrays.fill(down, false);
        Arrays.fill(veiled, false);
        Arrays.fill(downAt, -100f);
        Arrays.fill(upAt, -100f);
    }

    /**
     * A finger went down at (x, y) (after the engine saw it); {@code veiled}: inside a veil. {@code now} is screen
     * seconds.
     */
    void down(int p, float x, float y, boolean veiled, float now) {
        if (p < 0 || p >= GameEngine.MAX_POINTERS) return;
        down[p] = true;
        downAt[p] = now;
        this.veiled[p] = veiled;
        this.x[p] = x;
        this.y[p] = y;
    }

    /** Follows the engine's pointers once a frame; {@code now} is screen seconds. */
    void update(GameEngine e, float now) {
        for (int p = 0; p < GameEngine.MAX_POINTERS; p++) {
            if (e.pointerDown(p)) {
                if (!down[p]) downAt[p] = now;
                down[p] = true;
                veiled[p] = e.pointerVeiled(p);
                x[p] = e.pointerX(p);
                y[p] = e.pointerY(p);
            } else if (down[p]) {
                down[p] = false;
                upAt[p] = now;
            }
        }
    }

    /**
     * Draws the glitch mark of every veiled finger and, when {@code rings} (SHOW TOUCHES), a ring under every other
     * finger that is down, with the fading marks of the ones just lifted (normal blend).
     *
     * @param glitchR radius of a glitch mark: half a note's width (world units)
     */
    void draw(SpriteBatch b, VeilRenderer veils, float now, boolean rings, float glitchR) {
        for (int p = 0; p < GameEngine.MAX_POINTERS; p++) {
            float r = 1f;
            float a = 1f;
            if (!down[p]) {
                float t = now - upAt[p];
                if (t < 0f || t >= FADE_SEC) continue;
                float f = t / FADE_SEC;
                r = 1f + Anim.POW2_OUT.apply(f) / 3f;
                a = 1f - f;
            }
            if (veiled[p]) {
                float in = Math.max(0f, Math.min(1f, (now - downAt[p]) / APPEAR_SEC));
                veils.drawTouch(b, x[p], y[p], glitchR * r, a * in, now, p);
            } else if (rings) {
                mark(x[p], y[p], R * r, a);
            }
        }
    }

    private void mark(float cx, float cy, float r, float a) {
        draw.disc(cx, cy, r, Theme.INK1, 0.14f * a);
        draw.circle(cx, cy, r, RING_W, Theme.INK1, 0.85f * a);
        draw.disc(cx, cy, DOT_R, Theme.INK1, 0.9f * a);
    }
}
