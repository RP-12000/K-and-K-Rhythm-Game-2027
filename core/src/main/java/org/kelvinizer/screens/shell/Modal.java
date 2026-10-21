package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Matrix4;
import org.kelvinizer.Main;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Panel;
import org.kelvinizer.ui.Spring;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 菜单画面里弹出卡片和对话框的基类（切换潜水员、新建、改名、潜水员操作、导入、头像编辑）：
 * 压暗背景，卡片略带回弹地滑入，退出更快。
 * 打开时独占所有输入；点卡片外面或按 BACK / ESC 会关闭它（可设为不可关闭）。
 *
 * <p>Base of the SHELL sheets and dialogs (switcher, create diver, rename, diver actions, import,
 * avatar editor). A modal dims the screen with the {@code scrim} token, raises a
 * {@code panelHi} card that slides in on the {@code sheet} spring (ω 16, ζ .85: a small
 * overshoot) and leaves faster on a critically damped spring (DESIGN §1.5: exits are faster).
 *
 * <p>While active it takes every touch: its widgets first, then its own content hooks; a tap
 * that starts and ends outside the card dismisses it, as
 * does BACK / ESC. Subclasses place the card and their widgets in {@link #layout(float)} every
 * frame (the slide offset is passed in, so hit rects follow the animation) and draw their content
 * in {@link #drawContent(Batch, float)}. The host {@link ShellScreen} owns one active modal at a
 * time ({@link ShellScreen#openModal(Modal)}). No allocation per frame.
 *
 * <p><b>UI scale.</b> Subclasses lay the card out at 1x; the base class draws the card (not the
 * scrim) scaled by {@code Ui.uiScale()} about {@link #scalePivotX()} / {@link #scalePivotY(float)}
 * (DESIGN §0.3), limited so the card at rest stays inside the safe area (a card that already fills
 * the screen stays at 1x). Text is rasterised for the scale, and touches are mapped back into the
 * card's 1x space before anything sees them, so hit rects, scroll areas and the outside-tap test
 * need no changes.
 */
public abstract class Modal {
    /** The service hub. */
    protected final Main game;
    /** The screen showing this modal. */
    protected final ShellScreen host;
    /** Shared drawing service. */
    protected final Draw draw;
    /** Shared fonts. */
    protected final Fonts fonts;
    /** Widgets of the card (routed and drawn by the base class). */
    protected final WidgetSet widgets = new WidgetSet();
    /** Card rect at rest plus the current slide offset (set by {@link #layout(float)}). */
    protected float x, y, w, h;
    /** Seconds since the modal opened. */
    protected float time;
    /** Extra horizontal offset of the card (e.g. the delete-hold shake). */
    protected float cardShakeX;

    private final Spring anim = new Spring(Spring.Preset.SHEET, 0f);
    private boolean active;
    private int outsidePointer = -1;
    /** Card scale and pivot as last drawn (input is mapped through the same values). */
    private float scale = 1f, pivotX, pivotY;
    private final Matrix4 savedTransform = new Matrix4(), cardTransform = new Matrix4();
    /** Margin kept between the scaled card and the safe edges. */
    private static final float SCALE_MARGIN = 16f;
    /** Opacity of the dark underlay under the card (as the map's panels use). */
    private static final float UNDERLAY = 0.75f;

    /** Creates a closed modal for {@code host}. */
    protected Modal(Main game, ShellScreen host) {
        this.game = game;
        this.host = host;
        this.draw = game.draw();
        this.fonts = game.fonts();
    }

    // ------------------------------------------------------------------ state
    /** Opens the modal (call through {@link ShellScreen#openModal(Modal)}). */
    void open() {
        active = true;
        time = 0f;
        outsidePointer = -1;
        anim.setPreset(Spring.Preset.SHEET).setTarget(1f);
        onOpened();
    }

    /** Closes the modal with the exit animation. */
    public void close() {
        if (!active) return;
        active = false;
        widgets.cancel();
        anim.setParams(22f, 1f).setTarget(0f);
        onClosed();
    }

    /** Closes instantly (screen hidden). */
    void closeNow() {
        if (active) {
            active = false;
            widgets.cancel();
            onClosed();
        }
        anim.snap(0f);
    }

    /** True while open (input goes to it). */
    public boolean isActive() {
        return active;
    }

    /** True while open or still animating out. */
    public boolean isVisible() {
        return active || anim.value() > 0.005f;
    }

    /** Open amount 0..1 (may overshoot slightly while opening). */
    protected float openness() {
        return Math.max(0f, anim.value());
    }

    /** Slide distance in world units at openness 0 (positive = enters from below). */
    protected float slideDistance() {
        return 60f;
    }

    // ------------------------------------------------------------------ frame
    /** Advances the open spring, widgets and the subclass. */
    public void update(float dt) {
        anim.update(dt);
        time += dt;
        widgets.update(dt);
        ScrollArea s = scroll();
        if (s != null) s.update(dt);
        onUpdate(dt);
    }

    /** Draws scrim, card, content and widgets (the batch is drawing). */
    public void draw(Batch b) {
        if (!isVisible()) return;
        float e = openness();
        float a = Math.min(1f, e);
        float dy = -(1f - Math.min(1f, e)) * slideDistance();
        layout(dy);
        fitScale(dy);
        Panel.scrim(draw, Theme.SCRIM, a);
        boolean scaled = scale != 1f;
        if (scaled) {
            savedTransform.set(b.getTransformMatrix());
            cardTransform.set(savedTransform).translate(pivotX, pivotY, 0f).scale(scale, scale, 1f)
                .translate(-pivotX, -pivotY, 0f);
            b.setTransformMatrix(cardTransform);
            fonts.pushPixelScale(Ui.pxPerWorld() * scale);
        }
        float cx = x + cardShakeX;
        Panel.shadow(draw, cx, y, w, h, a);
        // opaque underlay: the panelHi token is 88% opaque, which let the screen's own text (the
        // Welcome title, TAP TO DESCEND) read through between the card's controls
        draw.roundRect(cx, y, w, h, Panel.RADIUS, Theme.ABYSS_BLACK, UNDERLAY * a);
        Panel.cardHi(draw, cx, y, w, h, Panel.RADIUS, a);
        drawContent(b, a);
        widgets.draw(b, a);
        if (scaled) {
            fonts.popPixelScale();
            b.setTransformMatrix(savedTransform);
        }
    }

    /**
     * Picks this frame's card scale: {@code Ui.uiScale()}, but above 1x only as far as the card at
     * rest ({@code y - dy}) still fits inside the safe area around its pivot.
     */
    private void fitScale(float dy) {
        float ry = y - dy;
        pivotX = scalePivotX();
        pivotY = scalePivotY(ry);
        float k = Ui.uiScale();
        if (k > 1f) {
            float lim = k;
            if (x < pivotX) lim = Math.min(lim, (pivotX - Ui.safeLeft() - SCALE_MARGIN) / (pivotX - x));
            if (x + w > pivotX) lim = Math.min(lim, (Ui.safeRight() - SCALE_MARGIN - pivotX) / (x + w - pivotX));
            if (ry < pivotY) lim = Math.min(lim, (pivotY - Ui.safeBottom() - SCALE_MARGIN) / (pivotY - ry));
            if (ry + h > pivotY) lim = Math.min(lim, (Ui.safeTop() - SCALE_MARGIN - pivotY) / (ry + h - pivotY));
            k = Math.max(1f, lim);
        }
        scale = k;
    }

    /** Maps a world x into the card's 1x space (inverse of the draw scale). */
    private float cardX(float px) {
        return pivotX + (px - pivotX) / scale;
    }

    /** Maps a world y into the card's 1x space (inverse of the draw scale). */
    private float cardY(float py) {
        return pivotY + (py - pivotY) / scale;
    }

    // ------------------------------------------------------------------ input (called by the host)
    /** Touch down; always consumed while visible. */
    public boolean touchDown(float px, float py, int ptr) {
        if (!active) return true;
        px = cardX(px);
        py = cardY(py);
        ScrollArea s = scroll();
        if (s != null) s.touchDown(px, py, ptr);
        if (widgets.touchDown(px, py, ptr)) return true;
        if (touchDownContent(px, py, ptr)) return true;
        if (!insideCard(px, py) && outsidePointer < 0) outsidePointer = ptr;
        return true;
    }

    /** Drag. */
    public boolean touchDragged(float px, float py, int ptr) {
        if (!active) return true;
        px = cardX(px);
        py = cardY(py);
        ScrollArea s = scroll();
        if (s != null && s.touchDragged(px, py, ptr)) {
            widgets.cancel();
            cancelContent(ptr);
            return true;
        }
        widgets.touchDragged(px, py, ptr);
        touchDraggedContent(px, py, ptr);
        return true;
    }

    /** Touch up; a tap outside the card dismisses. */
    public boolean touchUp(float px, float py, int ptr) {
        if (!active) return true;
        px = cardX(px);
        py = cardY(py);
        ScrollArea s = scroll();
        if (s != null && s.touchUp(px, py, ptr)) {
            widgets.touchUp(px, py, ptr);
            cancelContent(ptr);
            if (ptr == outsidePointer) outsidePointer = -1;
            return true;
        }
        boolean w = widgets.touchUp(px, py, ptr);
        if (!w) touchUpContent(px, py, ptr);
        if (ptr == outsidePointer) {
            outsidePointer = -1;
            if (!insideCard(px, py)) close();
        }
        return true;
    }

    /** Cancels presses. */
    public void cancel(int ptr) {
        ScrollArea s = scroll();
        if (s != null) s.cancel();
        widgets.cancel();
        cancelContent(ptr);
        if (ptr == outsidePointer) outsidePointer = -1;
    }

    /** Desktop hover. */
    public void mouseMoved(float px, float py) {
        if (active) widgets.mouseMoved(cardX(px), cardY(py));
    }

    /** Mouse wheel; true when consumed. */
    public boolean scrolled(float amountY) {
        ScrollArea s = scroll();
        if (s != null && active) s.scrolled(amountY);
        return true;
    }

    /** BACK / ESC: closes the modal. */
    public void onBack() {
        close();
    }

    /** True when (px, py) is inside the card at rest. */
    protected boolean insideCard(float px, float py) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    // ------------------------------------------------------------------ hooks
    /** The modal's scrolling list, or null (it sees touches first and cancels pressed rows on drag). */
    protected ScrollArea scroll() {
        return null;
    }

    /** X of the point the card scales about (default: the card's centre). */
    protected float scalePivotX() {
        return x + w * 0.5f;
    }

    /** Y of the point the card scales about, given the card's bottom at rest (default: its centre). */
    protected float scalePivotY(float restY) {
        return restY + h * 0.5f;
    }

    /** Centres a {@code cw × ch} card on the screen, shifted by {@code dy}. */
    protected void centerCard(float cw, float ch, float dy) {
        w = Math.min(cw, Ui.safeRight() - Ui.safeLeft() - 32f);
        h = ch;
        x = Ui.CX - w * 0.5f;
        y = Ui.CY - h * 0.5f + dy;
    }

    /**
     * Places the card ({@link #x}, {@link #y}, {@link #w}, {@link #h}) and the widgets for the
     * current safe edges, shifted vertically by {@code dy} (slide animation). Called every frame.
     */
    protected abstract void layout(float dy);

    /** Draws the card content between the card and the widgets; {@code alpha} is the open fade. */
    protected abstract void drawContent(Batch b, float alpha);

    /** Called when opened. */
    protected void onOpened() {
    }

    /** Called when closing starts. */
    protected void onClosed() {
    }

    /** Per-frame hook. */
    protected void onUpdate(float dt) {
    }

    /** Content touch down after widgets; return true when consumed. */
    protected boolean touchDownContent(float px, float py, int ptr) {
        return false;
    }

    /** Content drag. */
    protected void touchDraggedContent(float px, float py, int ptr) {
    }

    /** Content touch up (not owned by a widget). */
    protected void touchUpContent(float px, float py, int ptr) {
    }

    /** Content cancel. */
    protected void cancelContent(int ptr) {
    }

    /** Standard title of a card: UI_BOLD 28 caps at the card's top-left padding. */
    protected void drawTitle(Batch b, CharSequence title, float alpha) {
        fonts.draw(b, Fonts.Role.UI_BOLD, 28f, title, x + cardShakeX + 40f, y + h - 40f,
            com.badlogic.gdx.utils.Align.left | com.badlogic.gdx.utils.Align.top, Theme.INK1, alpha);
    }

    /** Eased entrance of an element that appears {@code delay} seconds after opening (stagger). */
    protected float stagger(float delay) {
        return Anim.ease(Anim.POW3_OUT, time, delay, Anim.MEDIUM);
    }
}
