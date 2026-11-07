package org.kelvinizer.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import org.kelvinizer.transition.TransitionLayer;

/**
 * 所有画面的基类：1920 × 1080 的扩展世界视口和共用的 SpriteBatch，
 * 把触摸换算成世界坐标，返回键调用 {@link #onBack()}。
 * 转场进行时锁定输入，按下的控件会被取消而不会触发。
 *
 * <p>Base class of every new screen (ARCHITECTURE §2 "KkScreen").
 *
 * <p>Generic over the service hub so it compiles before {@code Main} is rewritten: screens write
 * {@code class MapScreen extends KkScreen<Main>} and get a typed {@link #game}; {@code Main}
 * only has to implement {@link KkServices}.
 *
 * <ul>
 *   <li>World = {@code ExtendViewport(1920, 1080)} with the camera centred at (960, 540); the
 *       batch is the shared one from {@code game.draw()} (never dispose it).</li>
 *   <li>{@link #render(float)} clamps delta to ≤ 1/20 s, applies the viewport, sets the batch
 *       projection and calls {@link #draw(float)} (the batch is not begun).</li>
 *   <li>BACK calls {@link #onBack()} unless input is locked; other keys are ignored.</li>
 *   <li>Touch events are unprojected to world units and forwarded to {@link #onTouchDown} /
 *       {@link #onTouchDragged} / {@link #onTouchUp}. While {@link #inputLocked()} (a transition
 *       runs) downs and drags are swallowed and ups arrive as {@link #onTouchCancel(int)} so
 *       presses never fire. A gesture the platform cancels arrives as {@link #onGestureCancel(int)}.</li>
 *   <li>{@link #show()} is idempotent (sets the input processor, then {@link #onShow()});
 *       {@link #hide()} clears the processor if it is still ours, then {@link #onHide()}.</li>
 * </ul>
 *
 * @param <G> the service hub type (Main)
 */
public abstract class KkScreen<G extends KkServices> extends InputAdapter implements Screen {
    /** Maximum frame delta passed to {@link #draw(float)}. */
    public static final float MAX_DELTA = 1f / 20f;
    /** Pointer ids a touch can have (libGDX's Android backend tracks 20 fingers). */
    public static final int MAX_POINTERS = 20;

    /** The service hub. */
    protected final G game;
    /** World viewport (1920×1080 extended). */
    protected final ExtendViewport viewport;
    /** World camera, centred at (960, 540) after every resize. */
    protected final OrthographicCamera camera;
    /** The shared batch (owned by Draw; never dispose). */
    protected final SpriteBatch batch;

    private final Vector3 tmp = new Vector3();
    private boolean shown;

    /** Creates the viewport and camera; no other allocation should happen in show(). */
    protected KkScreen(G game) {
        this.game = game;
        camera = new OrthographicCamera();
        viewport = new ExtendViewport(Ui.REF_W, Ui.REF_H, camera);
        camera.position.set(Ui.CX, Ui.CY, 0f);
        camera.update();
        batch = game.draw().batch();
    }

    // ------------------------------------------------------------------ Screen
    /**
     * The processor installed on show: this screen by default. Screens that need more (e.g. a
     * GestureDetector) return a multiplexer created once in their constructor: the same
     * instance on every call, so {@link #hide()} can recognise it.
     */
    protected InputProcessor inputProcessor() {
        return this;
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(inputProcessor());
        shown = true;
        onShow();
    }

    @Override
    public void hide() {
        if (Gdx.input.getInputProcessor() == inputProcessor()) Gdx.input.setInputProcessor(null);
        shown = false;
        onHide();
    }

    /** True between show and hide. */
    public boolean isShown() {
        return shown;
    }

    /**
     * Updates the viewport (camera centred at (960, 540)) and then calls {@link #onResize}.
     * Main must call {@code UiKit.resize(w, h)} first; if it did not, the {@link Ui} metrics are
     * refreshed here when the size changed (fonts are re-bucketed only by UiKit).
     */
    @Override
    public void resize(int width, int height) {
        Ui.onResizeIfChanged(width, height);
        Ui.applyViewport(viewport, width, height);
        onResize(width, height);
    }

    @Override
    public final void render(float delta) {
        float dt = MathUtils.clamp(delta, 0f, MAX_DELTA);
        viewport.apply();
        batch.setProjectionMatrix(camera.combined);
        draw(dt);
    }

    @Override
    public void pause() {
    }

    @Override
    public void resume() {
    }

    @Override
    public void dispose() {
    }

    // ------------------------------------------------------------------ hooks
    /** Draws and updates the screen; {@code delta} is clamped. The batch is not begun. */
    protected abstract void draw(float delta);

    /** Called at the end of {@link #show()}. */
    protected void onShow() {
    }

    /** Called at the end of {@link #hide()}. */
    protected void onHide() {
    }

    /** Called after the viewport was updated for a resize. */
    protected void onResize(int width, int height) {
    }

    /** BACK (not called while input is locked). */
    protected void onBack() {
    }

    /** True while the global transition runs; touch input is suppressed. */
    protected boolean inputLocked() {
        TransitionLayer t = game.transitions();
        return t != null && t.isBusy();
    }

    /** Touch down in world units; return true when consumed. */
    protected boolean onTouchDown(float x, float y, int pointer, int button) {
        return false;
    }

    /** Drag in world units. */
    protected boolean onTouchDragged(float x, float y, int pointer) {
        return false;
    }

    /** Touch up in world units (acts on release). */
    protected boolean onTouchUp(float x, float y, int pointer, int button) {
        return false;
    }

    /** A press that must not fire (input locked mid-press): cancel pressed widgets. */
    protected void onTouchCancel(int pointer) {
    }

    /**
     * The platform cancelled the touch gesture ({@code touchCancelled}): on Android the system took it (an edge swipe,
     * a phone maker's gesture) and every finger on the screen is cancelled, although libGDX reports only
     * {@code pointer} and forgets the others. By default every press is called off ({@link #onTouchCancel} for each
     * pointer), so no screen waits for a finger that will never lift.
     */
    protected void onGestureCancel(int pointer) {
        for (int p = 0; p < MAX_POINTERS; p++) onTouchCancel(p);
    }

    // ------------------------------------------------------------------ helpers
    /** Unprojects screen coordinates into world units (the returned vector is reused). */
    protected Vector3 toWorld(int screenX, int screenY) {
        tmp.set(screenX, screenY, 0f);
        viewport.unproject(tmp);
        return tmp;
    }

    // ------------------------------------------------------------------ InputProcessor
    @Override
    public boolean keyDown(int keycode) {
        if (keycode != Input.Keys.BACK) return false;
        if (!inputLocked()) onBack();
        return true;
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (inputLocked()) return true;
        Vector3 v = toWorld(screenX, screenY);
        return onTouchDown(v.x, v.y, pointer, button);
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (inputLocked()) return true;
        Vector3 v = toWorld(screenX, screenY);
        return onTouchDragged(v.x, v.y, pointer);
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (inputLocked()) {
            onTouchCancel(pointer);
            return true;
        }
        Vector3 v = toWorld(screenX, screenY);
        return onTouchUp(v.x, v.y, pointer, button);
    }

    @Override
    public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
        onGestureCancel(pointer);
        return true;
    }
}
