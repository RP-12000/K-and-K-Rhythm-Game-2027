package org.kelvinizer.screens.shell;

import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.Viewport;
import org.kelvinizer.Main;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.transition.TransitionLayer;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Backdrop;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Haptics;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.KkScreen;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.TextField;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 欢迎、首次设置、玩家档案、设置和校准这些菜单画面的基类，
 * 负责按潜水员深度显示的海水背景、标题栏和返回键，以及一次只开一个的弹出卡片。
 * BACK / ESC 先关闭弹出卡片，否则经 CURTAIN 转场回到打开它的画面（没有就回地图）。
 *
 * <p>Base of the SHELL screens (Welcome, Profile, FirstRun, Settings, Calibration).
 *
 * <ul>
 *   <li><b>Backdrop</b> (DESIGN §2.6 / §2.8): menu mode at the diver's depth, an optional image
 *       hook ({@code welcome.png}, {@code Blue.png}) at its DESIGN alpha × the diver's
 *       <i>Art opacity</i> (off with <i>Background art</i> off), a shaft multiplier and the
 *       caustics flag. The image is acquired from the kit's {@link org.kelvinizer.ui.ArtCache} (one
 *       texture per file for all screens) on the first show and released in {@link #dispose()};
 *       it is removed from the shared backdrop on hide.</li>
 *   <li><b>Header</b> (optional): a BACK icon button at the safe top-left and the screen title
 *       (DISPLAY_SOFT 44) that decodes in on show and bursts with a G2 on {@link #glitchTitle()}.</li>
 *   <li><b>Navigation</b>: BACK / ESC / the icon go to the screen that opened this one
 *       ({@link #openScreen(ShellScreen, CharSequence)} records it), else to the map, through a
 *       CURTAIN carrying the destination's name.</li>
 *   <li><b>Modals</b>: one {@link Modal} at a time ({@link #openModal}); while it is visible it
 *       takes all input and BACK.</li>
 *   <li><b>Input</b> order: modal, then the screen {@link #widgets}, then the content hooks.
 *       Menu music plays on show.</li>
 * </ul>
 * Layout runs in {@link #layout()} whenever the {@link Ui} generation changed (resize, settings)
 * or {@link #invalidateLayout()} was called. Nothing is allocated per frame.
 */
public abstract class ShellScreen extends KkScreen<Main> {
    /** Header height below the safe top. */
    public static final float HEADER_H = 120f;

    /** Seconds since the last show. */
    protected float time;
    /** Monotonic clock (glitch schedules stay valid across shows). */
    protected float clock;
    /** Screen widgets (routed after the modal). */
    protected final WidgetSet widgets = new WidgetSet();
    /** BACK icon button (null when the screen has no header). */
    protected final Button backButton;

    private CharSequence title;
    private float titleTime;
    private final StringBuilder titleText = new StringBuilder(32);
    private final GlitchState titleGlitch;
    private final String artFile;
    private final float artAlpha, shaftScale;
    private final boolean caustics;
    private Texture art;
    private boolean artTried;
    private Modal modal;
    private Screen returnTo;
    private CharSequence returnLabel;
    private int layoutGen = -1;
    private boolean titleBurst;
    private boolean openingChild;

    /**
     * @param title      screen title (null = no header and no BACK icon); a {@code Lang.Text} follows
     *                   the interface language
     * @param artFile    internal image hook file (null = none)
     * @param artAlpha   image alpha at Art opacity 100 % (DESIGN §2.8)
     * @param shaftScale light-shaft multiplier (Profile / Settings 0.6)
     * @param caustics   whether the CAUSTICS shader variant is allowed (Welcome only)
     */
    protected ShellScreen(Main game, CharSequence title, String artFile, float artAlpha, float shaftScale,
                          boolean caustics) {
        super(game);
        this.title = title;
        this.artFile = artFile;
        this.artAlpha = artAlpha;
        this.shaftScale = shaftScale;
        this.caustics = caustics;
        titleGlitch = game.glitch().obtainState();
        if (title != null) {
            backButton = widgets.add(new Button(game.draw(), game.fonts(), Icons.Icon.BACK));
            backButton.setOnTap(new Runnable() {
                @Override
                public void run() {
                    onBack();
                }
            });
        } else {
            backButton = null;
        }
    }

    // ------------------------------------------------------------------ navigation
    /**
     * Sets the screen BACK returns to (null = the map) and the CURTAIN label shown on the way.
     * Cleared when this screen hides, except when it hides to open a child screen through
     * {@link #openScreen} (the child returns here, and BACK still leads to the same place).
     */
    public void setReturnTarget(Screen target, CharSequence label) {
        returnTo = target;
        returnLabel = label;
    }

    /** CURTAIN label naming this screen when another screen returns to it. */
    protected CharSequence screenLabel() {
        return title != null ? title : "K & K";
    }

    /**
     * Opens {@code target} with a CURTAIN labelled {@code label}, recording this screen as its
     * BACK destination. Returns false (nothing recorded) when the transition could not start.
     */
    public boolean openScreen(ShellScreen target, CharSequence label) {
        target.setReturnTarget(this, screenLabel());
        if (game.transitionTo(target, TransitionLayer.Style.CURTAIN, Ui.CX, Ui.CY, label)) {
            openingChild = true;
            return true;
        }
        target.setReturnTarget(null, null);
        return false;
    }

    /** Leaves to the recorded return target (else the map) through a CURTAIN. */
    protected void goBack() {
        if (returnTo != null) {
            game.transitionTo(returnTo, TransitionLayer.Style.CURTAIN, Ui.CX, Ui.CY, returnLabel);
        } else {
            game.transitionTo(game.map(), TransitionLayer.Style.CURTAIN, Ui.CX, Ui.CY, Lang.t("DIVE CHART", "航线"));
        }
    }

    /** BACK / ESC: closes the modal, else {@link #back()}. */
    @Override
    protected final void onBack() {
        if (modal != null && modal.isVisible()) {
            if (modal.isActive()) modal.onBack();
            return;
        }
        back();
    }

    /** The screen's BACK action (default {@link #goBack()}). */
    protected void back() {
        goBack();
    }

    // ------------------------------------------------------------------ modals
    /** Shows {@code m}, closing any other modal first. */
    public void openModal(Modal m) {
        if (modal != null && modal != m && modal.isActive()) modal.close();
        widgets.cancel();
        modal = m;
        m.open();
    }

    /** The current (possibly closing) modal, or null. */
    protected Modal modal() {
        return modal;
    }

    /** True while a modal is open (not counting its exit animation). */
    public boolean isModalOpen() {
        return modal != null && modal.isActive();
    }

    /** Registers a text field of this screen or of one of its modals for keyboard routing. */
    public void registerField(TextField field) {
        registerTextField(field);
    }

    // ------------------------------------------------------------------ helpers
    /** Re-runs {@link #layout()} before the next frame. */
    protected void invalidateLayout() {
        layoutGen = -1;
    }

    /** Fires a G2 burst on the title (e.g. after a diver switch). */
    public void glitchTitle() {
        titleGlitch.trigger(GlitchText.Level.G2, clock);
    }

    /** Replaces the header title (it decodes in again); ignored on a screen without a header. */
    protected void setTitle(CharSequence text) {
        if (title == null || text == null || text == title) return;
        title = text;
        titleTime = 0f;
        titleBurst = false;
    }

    /** The world viewport of this screen (for scissor clipping in modals and lists). */
    public Viewport screenViewport() {
        return viewport;
    }

    /**
     * The scroll area under the pointer logic (null = none): it sees every touch down first,
     * and once it starts dragging the pressed widgets are cancelled.
     */
    protected ScrollArea activeScroll() {
        return null;
    }

    /** Eased (pow3Out, 320 ms) entrance progress of an element appearing {@code delay} s after show. */
    protected float enter(float delay) {
        return Anim.ease(Anim.POW3_OUT, time, delay, 0.32f);
    }

    /** The active diver (never null after load). */
    protected Profile diver() {
        return game.profiles().active();
    }

    /** Backdrop depth of this screen (DESIGN §2.6: the diver's depth; FirstRun 0 m). */
    protected float screenDepth() {
        return game.playerDepth();
    }

    // ------------------------------------------------------------------ divers
    /**
     * Makes diver {@code id} active (DESIGN §8.3: the store saves first; the backdrop moves to the
     * new depth, the art hook is re-applied and the header bursts with a G2), then calls
     * {@link #onActiveDiverChanged()}. Returns false when the diver is unknown or already active.
     */
    public boolean switchDiver(String id) {
        ProfileStore store = game.profiles();
        if (id == null || id.equals(store.activeId())) return false;
        if (!store.switchTo(id)) return false;
        afterActiveChange();
        Haptics.light();
        return true;
    }

    /**
     * Deletes diver {@code id} (never the last one). Deleting the active diver switches to the most
     * recently played remaining one, exactly like {@link #switchDiver}. Returns false when refused.
     */
    public boolean deleteDiver(String id) {
        ProfileStore store = game.profiles();
        boolean wasActive = id != null && id.equals(store.activeId());
        if (!store.delete(id)) return false;
        if (wasActive) afterActiveChange();
        else onDiversEdited();
        return true;
    }

    private void afterActiveChange() {
        // the new diver's language first, so the tabs refresh in it (Main applies the rest next frame)
        Profile p = diver();
        Lang.set(p != null && p.settings.zh());
        game.backdrop().setDepth(screenDepth());
        applyArt();
        glitchTitle();
        onActiveDiverChanged();
    }

    /** Called after the active diver changed (switch, delete of the active diver). */
    public void onActiveDiverChanged() {
    }

    /** Called after diver data changed without a switch (create, rename, avatar, import, delete). */
    public void onDiversEdited() {
    }

    /** Re-applies the backdrop image hook (after the art settings or the diver changed). */
    protected void applyArt() {
        Backdrop b = game.backdrop();
        Profile p = diver();
        boolean on = art != null && (p == null || p.settings.backgroundArt);
        float k = p != null ? p.settings.artOpacity : 1f;
        if (on) {
            b.setImage(art, artAlpha * k);
            b.setImageParallax(0f);
        } else {
            b.setImage(null);
        }
    }

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected final void onShow() {
        time = 0f;
        titleTime = 0f;
        Backdrop b = game.backdrop();
        b.setMode(Backdrop.Mode.MENU);
        b.setCaustics(caustics);
        b.setShaftScale(shaftScale);
        b.setDepth(screenDepth());
        b.setVirtualVelocity(0f, 0f);
        if (!artTried && artFile != null) {
            artTried = true;
            art = game.art().acquire(artFile);
        }
        applyArt();
        if (playsMenuMusic()) game.audio().playMenu();
        titleBurst = false;
        layoutGen = -1;
        enterScreen();
    }

    @Override
    protected final void onHide() {
        game.toasts().resetAnchor();
        widgets.cancel();
        if (modal != null) modal.closeNow();
        Backdrop b = game.backdrop();
        b.setImage(null);
        b.setShaftScale(1f);
        b.setShaftTilt(0f);
        b.setCaustics(false);
        b.clearFocus();
        b.setVirtualVelocity(0f, 0f);
        if (!openingChild) {
            returnTo = null;
            returnLabel = null;
        }
        openingChild = false;
        leaveScreen();
    }

    /** Frees the title glitch state and releases the image hook, then {@link #disposeScreen()}. */
    @Override
    public void dispose() {
        game.glitch().freeState(titleGlitch);
        if (art != null) game.art().release(artFile);
        art = null;
        disposeScreen();
    }

    // ------------------------------------------------------------------ frame
    @Override
    protected final void draw(float delta) {
        time += delta;
        titleTime += delta;
        clock += delta;
        if (layoutGen != Ui.generation()) {
            layoutGen = Ui.generation();
            if (backButton != null) backButton.setCenter(Ui.safeLeft() + 44f, Ui.safeTop() - 44f);
            // titled screens show toasts in the header band, centred on the title line: the default
            // slot would cover their tab row or first content row
            if (title != null) game.toasts().setAnchorTop(Ui.safeTop() - 8f);
            layout();
        }
        widgets.update(delta);
        if (modal != null) {
            modal.update(delta);
            if (!modal.isVisible()) modal = null;
        }
        update(delta);
        batch.begin();
        game.backdrop().render(batch, viewport, delta);
        drawScreen(delta);
        drawHeader();
        widgets.draw(batch, widgetAlpha());
        drawOverlay(delta);
        if (modal != null) modal.draw(batch);
        batch.end();
    }

    private void drawHeader() {
        if (title == null) return;
        float e = Anim.ease(Anim.POW3_OUT, titleTime, 0.05f, 0.32f);
        float decodeT = titleTime - 0.08f;
        if (decodeT < 0f) return;
        GlitchText.decodeInto(titleText, title, decodeT, 0.03f, 41);
        if (!titleBurst && decodeT > title.length() * 0.03f + 0.05f) {
            titleBurst = true;
            titleGlitch.trigger(GlitchText.Level.G2, clock);
        }
        float x = Ui.safeLeft() + 112f - 24f * (1f - e);
        game.glitch().draw(batch, Fonts.Role.DISPLAY_SOFT, 44f, titleText, x, Ui.safeTop() - 44f,
            Align.left | Fonts.VCENTER, Theme.INK1, e, titleGlitch, clock);
    }

    /** Alpha applied to the screen widgets (entrance fades). */
    protected float widgetAlpha() {
        return 1f;
    }

    // ------------------------------------------------------------------ input
    @Override
    protected final boolean onTouchDown(float x, float y, int pointer, int button) {
        if (modal != null && modal.isVisible()) return modal.touchDown(x, y, pointer);
        ScrollArea s = activeScroll();
        if (s != null) s.touchDown(x, y, pointer);
        if (widgets.touchDown(x, y, pointer)) return true;
        return contentTouchDown(x, y, pointer);
    }

    @Override
    protected final boolean onTouchDragged(float x, float y, int pointer) {
        if (modal != null && modal.isVisible()) return modal.touchDragged(x, y, pointer);
        ScrollArea s = activeScroll();
        if (s != null && s.touchDragged(x, y, pointer)) {
            widgets.cancel();
            contentCancel(pointer);
            return true;
        }
        boolean w = widgets.touchDragged(x, y, pointer);
        return contentTouchDragged(x, y, pointer) || w;
    }

    @Override
    protected final boolean onTouchUp(float x, float y, int pointer, int button) {
        if (modal != null && modal.isVisible()) return modal.touchUp(x, y, pointer);
        ScrollArea s = activeScroll();
        if (s != null && s.touchUp(x, y, pointer)) {
            widgets.touchUp(x, y, pointer);
            contentCancel(pointer);
            return true;
        }
        if (widgets.touchUp(x, y, pointer)) {
            contentCancel(pointer);
            return true;
        }
        return contentTouchUp(x, y, pointer);
    }

    @Override
    protected final void onTouchCancel(int pointer) {
        if (modal != null) modal.cancel(pointer);
        ScrollArea s = activeScroll();
        if (s != null) s.cancel();
        widgets.cancel();
        contentCancel(pointer);
    }

    @Override
    protected final boolean onMouseMoved(float x, float y) {
        if (modal != null && modal.isVisible()) {
            modal.mouseMoved(x, y);
            return true;
        }
        widgets.mouseMoved(x, y);
        contentMouseMoved(x, y);
        return false;
    }

    @Override
    protected final boolean onScrolled(float amountX, float amountY) {
        if (modal != null && modal.isVisible()) return modal.scrolled(amountY);
        if (contentScrolled(amountY)) return true;
        ScrollArea s = activeScroll();
        return s != null && s.scrolled(amountY);
    }

    @Override
    protected final boolean onKeyDown(int keycode) {
        if (modal != null && modal.isVisible()) return true;
        return contentKeyDown(keycode);
    }

    // ------------------------------------------------------------------ hooks
    /** Whether showing this screen starts the MENU music slot (Calibration does not). */
    protected boolean playsMenuMusic() {
        return true;
    }

    /** Called at the end of show (after the backdrop and music were set up). */
    protected void enterScreen() {
    }

    /** Called at the end of hide. */
    protected void leaveScreen() {
    }

    /** Called from {@link #dispose()}. */
    protected void disposeScreen() {
    }

    /** Positions the widgets for the current safe edges. */
    protected abstract void layout();

    /** Per-frame update before drawing (widgets and modal are already updated). */
    protected void update(float delta) {
    }

    /** Draws the screen content over the backdrop, under the header and widgets (batch drawing). */
    protected abstract void drawScreen(float delta);

    /** Draws on top of the widgets, under the modal (batch drawing). */
    protected void drawOverlay(float delta) {
    }

    /** Content touch down after the widgets; return true when consumed. */
    protected boolean contentTouchDown(float x, float y, int pointer) {
        return false;
    }

    /** Content drag. */
    protected boolean contentTouchDragged(float x, float y, int pointer) {
        return false;
    }

    /** Content touch up (not taken by a widget). */
    protected boolean contentTouchUp(float x, float y, int pointer) {
        return false;
    }

    /** A pointer that must not act any more (input locked, widget took the release). */
    protected void contentCancel(int pointer) {
    }

    /** Desktop hover over the content. */
    protected void contentMouseMoved(float x, float y) {
    }

    /** Mouse wheel over the content; true when consumed. */
    protected boolean contentScrolled(float amountY) {
        return false;
    }

    /** Key other than BACK / ESC while no modal is open. */
    protected boolean contentKeyDown(int keycode) {
        return false;
    }
}
