package org.kelvinizer.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import org.kelvinizer.transition.TransitionLayer;

/**
 * 把所有视觉服务（绘图、字体、故障字、背景、转场、提示条、头像、背景图缓存和帧率显示）打包，
 * 统一创建、调整大小、恢复和释放。
 * {@code Main} 把这些工作都交给它；每帧最后由它画提示条、帧率和最上层的转场。
 *
 * <p>Bundle of every visual service (Draw, Fonts, GlitchText, Backdrop, TransitionLayer, Toast,
 * Avatar, ArtCache, the FPS readout) with one lifecycle, so {@code Main} can create it in
 * {@code create()} and delegate its {@link KkServices} getters, {@code resize}, {@code resume},
 * {@code render} tail and {@code dispose} to it.
 *
 * <pre>
 *   create():  ui = new UiKit(); ui.resize(w, h)
 *   render():  apply pending switch; super.render(); ui.renderOverlays(delta)
 *   resize():  ui.resize(w, h); screen.resize(w, h)
 *   resume():  ui.resume()
 *   dispose(): ui.dispose()
 * </pre>
 * Must be constructed on the GL thread.
 */
public final class UiKit implements KkServices, Disposable {
    private final Draw draw;
    private final Fonts fonts;
    private final GlitchText glitch;
    private final Backdrop backdrop;
    private final TransitionLayer transitions;
    private final Toast toasts;
    private final Avatar avatars;
    private final ArtCache art = new ArtCache();
    private final OrthographicCamera overlayCamera = new OrthographicCamera();
    private final ExtendViewport overlayViewport = new ExtendViewport(Ui.REF_W, Ui.REF_H, overlayCamera);
    private boolean showFps;

    /** Creates every service (GL thread). */
    public UiKit() {
        draw = new Draw();
        fonts = new Fonts();
        glitch = new GlitchText(fonts, draw);
        backdrop = new Backdrop(draw);
        transitions = new TransitionLayer(draw, fonts, glitch);
        transitions.setBackdrop(backdrop);
        toasts = new Toast(draw, fonts);
        avatars = new Avatar(draw, fonts);
    }

    @Override
    public Fonts fonts() {
        return fonts;
    }

    @Override
    public Draw draw() {
        return draw;
    }

    @Override
    public Backdrop backdrop() {
        return backdrop;
    }

    @Override
    public TransitionLayer transitions() {
        return transitions;
    }

    @Override
    public GlitchText glitch() {
        return glitch;
    }

    @Override
    public Toast toasts() {
        return toasts;
    }

    @Override
    public Avatar avatars() {
        return avatars;
    }

    @Override
    public ArtCache art() {
        return art;
    }

    /** Recomputes {@link Ui} metrics and resizes fonts, overlays and the transition layer. */
    public void resize(int w, int h) {
        Ui.onResize(w, h);
        fonts.onResize();
        transitions.resize(w, h);
        Ui.applyViewport(overlayViewport, w, h);
    }

    /** After a possible GL context loss: resets draw state and re-renders avatars lazily. */
    public void resume() {
        draw.resume();
        avatars.invalidate();
    }

    /** Device setting "Show FPS" (pushed by {@code Main.applySettings}): the readout on every screen. */
    public void setShowFps(boolean show) {
        showFps = show;
    }

    /** True while the FPS readout is on. */
    public boolean showFps() {
        return showFps;
    }

    /**
     * Draws the global overlays after the screen: toasts, the FPS readout (when on), then the
     * transition layer on top. Call at the end of {@code Main.render()}.
     */
    public void renderOverlays(float delta) {
        float dt = MathUtils.clamp(delta, 0f, 1f / 20f);
        toasts.update(dt);
        boolean toast = toasts.isShowing();
        if (toast || showFps) {
            overlayViewport.apply();
            SpriteBatch b = draw.batch();
            b.setProjectionMatrix(overlayCamera.combined);
            b.begin();
            if (toast) toasts.draw(b);
            if (showFps) drawFps(b);
            b.end();
        }
        transitions.render(delta);
    }

    /**
     * Frames per second at the safe bottom-right: {@code 60 FPS}, NUMBER 20 in fixed cells plus a
     * UI_LABEL 16 unit (no allocation).
     */
    private void drawFps(SpriteBatch b) {
        float x = Ui.safeRight() - 8f, y = Ui.safeBottom() + 4f;
        float w = fonts.draw(b, Fonts.Role.UI_LABEL, 16f, "FPS", x, y, Align.right, Theme.INK2, 0.8f);
        fonts.drawDigits(b, Fonts.Role.NUMBER, 20f, Gdx.graphics.getFramesPerSecond(), 1, false, x - w - 10f, y,
            Align.right, Theme.INK1, null, 0.9f);
    }

    @Override
    public void dispose() {
        transitions.dispose();
        avatars.dispose();
        art.dispose();
        backdrop.dispose();
        fonts.dispose();
        draw.dispose();
    }
}
