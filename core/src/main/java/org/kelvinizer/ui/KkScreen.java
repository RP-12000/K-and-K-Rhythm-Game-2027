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
 * 把触摸和鼠标换算成世界坐标，返回键调用 {@link #onBack()}。
 * 转场进行时锁定输入，按下的控件会被取消而不会触发；正在编辑的文字输入框优先接收键盘。
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
 *   <li>BACK (Android) and ESCAPE (desktop) call {@link #onBack()} unless input is locked; other
 *       keys go to {@link #onKeyDown} (not while locked) and {@link #onKeyUp} (always).</li>
 *   <li>Touch and mouse events are unprojected to world units and forwarded to
 *       {@link #onTouchDown} / {@link #onTouchDragged} / {@link #onTouchUp} /
 *       {@link #onMouseMoved}. While {@link #inputLocked()} (a transition runs) downs and drags
 *       are swallowed and ups arrive as {@link #onTouchCancel(int)} so presses never fire.</li>
 *   <li>{@link #show()} is idempotent (sets the input processor, then {@link #onShow()});
 *       {@link #hide()} clears the processor if it is still ours, then {@link #onHide()}.</li>
 *   <li>Text fields passed to {@link #registerTextField(TextField)} get the keyboard while they
 *       edit inline: keys go to the field first (ESC cancels the edit instead of calling
 *       {@link #onBack()}), a touch outside commits or reverts it, and {@link #hide()} reverts
 *       it. Touches inside still go through {@link #onTouchDown} so the screen forwards them to
 *       the field like any other widget.</li>
 * </ul>
 *
 * @param <G> the service hub type (Main)
 */
public abstract class KkScreen<G extends KkServices> extends InputAdapter implements Screen {
    /** Maximum frame delta passed to {@link #draw(float)}. */
    public static final float MAX_DELTA = 1f / 20f;

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
    private TextField[] textFields = new TextField[2];
    private int textFieldCount;

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
        for (int i = 0; i < textFieldCount; i++) textFields[i].cancelEdit();
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

    /** BACK / ESC (not called while input is locked). */
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

    /** Mouse move in world units (desktop hover). */
    protected boolean onMouseMoved(float x, float y) {
        return false;
    }

    /** Scroll wheel (desktop); amountY > 0 scrolls down. */
    protected boolean onScrolled(float amountX, float amountY) {
        return false;
    }

    /** Key down other than BACK / ESC. */
    protected boolean onKeyDown(int keycode) {
        return false;
    }

    /**
     * Key up (not routed while a registered text field edits). Unlike the other hooks it also
     * arrives while input is locked, so a key held across a transition is never left pressed.
     */
    protected boolean onKeyUp(int keycode) {
        return false;
    }

    /** Character typed (not routed to an editing text field). */
    protected boolean onKeyTyped(char character) {
        return false;
    }

    // ------------------------------------------------------------------ helpers
    /**
     * Registers a text field so keyboard input reaches it while it edits inline (call once,
     * e.g. in the constructor; registering the same field twice is a no-op).
     */
    protected void registerTextField(TextField field) {
        for (int i = 0; i < textFieldCount; i++) if (textFields[i] == field) return;
        if (textFieldCount == textFields.length) {
            TextField[] grown = new TextField[textFields.length * 2];
            System.arraycopy(textFields, 0, grown, 0, textFieldCount);
            textFields = grown;
        }
        textFields[textFieldCount++] = field;
    }

    /** The registered text field currently editing inline, or null. */
    protected TextField editingTextField() {
        for (int i = 0; i < textFieldCount; i++) if (textFields[i].isEditing()) return textFields[i];
        return null;
    }

    /** Unprojects screen coordinates into world units (the returned vector is reused). */
    protected Vector3 toWorld(int screenX, int screenY) {
        tmp.set(screenX, screenY, 0f);
        viewport.unproject(tmp);
        return tmp;
    }

    // ------------------------------------------------------------------ InputProcessor
    @Override
    public boolean keyDown(int keycode) {
        TextField editing = editingTextField();
        if (editing != null) return editing.keyDown(keycode);
        if (keycode == Input.Keys.BACK || keycode == Input.Keys.ESCAPE) {
            if (!inputLocked()) onBack();
            return true;
        }
        if (inputLocked()) return true;
        return onKeyDown(keycode);
    }

    @Override
    public boolean keyUp(int keycode) {
        if (editingTextField() != null) return false;
        return onKeyUp(keycode);
    }

    @Override
    public boolean keyTyped(char character) {
        TextField editing = editingTextField();
        if (editing != null) return editing.keyTyped(character);
        if (inputLocked()) return true;
        return onKeyTyped(character);
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (inputLocked()) return true;
        Vector3 v = toWorld(screenX, screenY);
        TextField editing = editingTextField();
        if (editing != null && !editing.hit(v.x, v.y)) editing.commitOrRevert();
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
        onTouchCancel(pointer);
        return true;
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) {
        if (inputLocked()) return false;
        Vector3 v = toWorld(screenX, screenY);
        return onMouseMoved(v.x, v.y);
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        if (inputLocked()) return true;
        return onScrolled(amountX, amountY);
    }
}
