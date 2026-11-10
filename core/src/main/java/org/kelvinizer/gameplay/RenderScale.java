package org.kelvinizer.gameplay;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import org.kelvinizer.ui.Ui;

import java.nio.IntBuffer;

/**
 * 弱一点的手机上自动把游戏画面画小再放大：游玩时帧率持续低于 45，就把谱面（背景、场地、键、打击特效）先画到小一点的画面上，
 * 再拉伸铺满屏幕，少填很多像素；帧率回来了再一步步升回去；画小了也没变快（卡在 CPU 上）就升回来、这一局不再降。
 * 顶层的 HUD、暂停菜单照旧按屏幕原样画。
 *
 * <p>Dynamic resolution for the gameplay picture (Kelvin 2026-11-10 01:34 「想尽一切办法让弱一点的手机/平板也能正常游玩，
 * 可以去搜一下其他的3D动作游戏等是怎么处理这些地图渲染之类的」: the way 3D games keep a weak GPU at speed, Android's
 * own game-mode downscaling included). While the song plays, the frame rate is averaged over {@link #WINDOW_SEC}; under
 * {@link #LOW_FPS} the picture steps down ({@link #STEPS}): {@link #begin} draws it into a smaller frame buffer and
 * {@link #end} stretches that over the screen, before the touch marks and the HUD over the notes. A step down that
 * does not lift the rate by {@link #HELPS} (the phone waits on its CPU, not on filling pixels) is undone and the
 * picture stays at that size for the rest of the run; at or over {@link #HIGH_FPS} for {@link #UP_WINDOWS} windows it
 * steps back up, and a step up that brings the rate under {@link #LOW_FPS} again is undone and kept. The veils read
 * the bound viewport ({@code VeilRenderer}), so they work in the small picture as on the screen. The system property
 * {@code kk.renderScale} fixes the scale (tests: {@code 1} keeps every frame as before).
 */
final class RenderScale implements Disposable {
    /** The picture's sizes, a fraction of the screen's, from full size down. */
    static final float[] STEPS = {1f, 0.8f, 0.65f, 0.5f};
    /** Frame rate under which the picture steps down. */
    static final float LOW_FPS = 45f;
    /** Frame rate at or over which, for {@link #UP_WINDOWS} windows, the picture steps back up. */
    static final float HIGH_FPS = 57f;
    /** How much faster a step down must make the frames to be kept. */
    static final float HELPS = 1.08f;
    /** Seconds of play averaged per check. */
    static final float WINDOW_SEC = 1.5f;
    /** Windows in a row at or over {@link #HIGH_FPS} before a step up. */
    static final int UP_WINDOWS = 4;
    /** Seconds of play left out after the start, a pause or a step (hitches). */
    static final float SETTLE_SEC = 1f;

    /** The scale fixed by {@code kk.renderScale} (0: automatic). */
    private static final float FIXED = fixed();

    private int step;
    /** True once a step was undone: the size stays for the rest of the run. */
    private boolean settled;
    /** +1 after a step down, −1 after a step up, until the next window says whether it is kept. */
    private int trying;
    private float before;
    private float settle = SETTLE_SEC;
    private float time;
    private int frames;
    private int fast;
    private FrameBuffer buffer;
    private final IntBuffer view = BufferUtils.newIntBuffer(16);
    private int vx, vy, vw, vh;

    private static float fixed() {
        try {
            String s = System.getProperty("kk.renderScale");
            return s == null ? 0f : Math.max(0.25f, Math.min(1f, Float.parseFloat(s)));
        } catch (RuntimeException e) {
            return 0f;
        }
    }

    /** @return the picture's size this frame, a fraction of the screen's */
    float scale() {
        return FIXED > 0f ? FIXED : STEPS[step];
    }

    /**
     * Counts a frame: {@code playing} while the song runs (a pause starts the settling again). Uses the real frame
     * time ({@code Gdx.graphics.getDeltaTime()}), not the clamped one the screen gets.
     */
    void update(boolean playing) {
        if (FIXED > 0f || settled && trying == 0) return;
        if (!playing) {
            settle = SETTLE_SEC;
            time = 0f;
            frames = 0;
            return;
        }
        float dt = Gdx.graphics.getDeltaTime();
        if (!(dt > 0f)) return;
        if (settle > 0f) {
            settle -= dt;
            return;
        }
        time += dt;
        frames++;
        if (time < WINDOW_SEC) return;
        float fps = frames / time;
        time = 0f;
        frames = 0;
        if (trying > 0 && fps < before * HELPS || trying < 0 && fps < LOW_FPS) { // that step did not pay: undo it
            step -= trying;
            trying = 0;
            settled = true;
            settle = SETTLE_SEC;
            return;
        }
        trying = 0;
        if (settled) return;
        if (fps < LOW_FPS && step < STEPS.length - 1) {
            move(1, fps);
        } else if (fps >= HIGH_FPS && step > 0) {
            if (++fast >= UP_WINDOWS) move(-1, fps);
        } else {
            fast = 0;
        }
    }

    private void move(int d, float fps) {
        step += d;
        trying = d;
        before = fps;
        fast = 0;
        settle = SETTLE_SEC * 0.5f;
    }

    /**
     * Starts the picture in the smaller buffer when the scale is under 1 (call before the batch begins).
     *
     * @return false at full size: nothing changed
     */
    boolean begin() {
        float s = scale();
        if (s >= 1f) return false;
        view.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, view);
        vx = view.get(0);
        vy = view.get(1);
        vw = view.get(2);
        vh = view.get(3);
        int w = Math.max(1, Math.round(vw * s));
        int h = Math.max(1, Math.round(vh * s));
        if (buffer == null || buffer.getWidth() != w || buffer.getHeight() != h) {
            if (buffer != null) buffer.dispose();
            buffer = new FrameBuffer(Pixmap.Format.RGBA8888, w, h, false);
            buffer.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        }
        buffer.bind();
        Gdx.gl.glViewport(0, 0, w, h);
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        return true;
    }

    /**
     * Ends {@link #begin} (the batch not drawing, its projection the screen camera's) and stretches the small picture
     * over the whole world view, opaque.
     */
    void end(SpriteBatch batch) {
        FrameBuffer.unbind();
        Gdx.gl.glViewport(vx, vy, vw, vh);
        float packed = batch.getPackedColor();
        batch.begin();
        batch.disableBlending();
        batch.setPackedColor(Color.WHITE_FLOAT_BITS);
        batch.draw(buffer.getColorBufferTexture(), Ui.left(), Ui.bottom(), Ui.worldW(), Ui.worldH(), 0f, 0f, 1f, 1f);
        batch.enableBlending();
        batch.end();
        batch.setPackedColor(packed);
    }

    @Override
    public void dispose() {
        if (buffer != null) buffer.dispose();
        buffer = null;
    }
}
