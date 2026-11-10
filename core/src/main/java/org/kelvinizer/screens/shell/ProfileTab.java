package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import org.kelvinizer.Main;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;

/**
 * 玩家档案画面右栏一个标签页（STATS、DST 10、RECENT、DIVERS）的基类：一块带裁剪的滚动内容区，手感与地图相同。
 * 子类在 {@link #refresh} 里缓存各行；每次显示这个标签页时，各行依次滑入。
 *
 * <p>One tab of the Profile screen's right column (DESIGN §8.2): a clipped, scrolling content area
 * with map-style physics ({@link ScrollArea}). Subclasses cache their rows in {@link #refresh}
 * (called when the diver or the store revision changed) and draw them in
 * {@link #drawContent(Batch, float, float)} from the scrolled top edge; rows enter with the list
 * stagger (40 ms) after the tab is shown. Nothing is allocated per frame.
 */
public abstract class ProfileTab {
    /** Service hub. */
    protected final Main game;
    /** Host screen (modals, diver switching). */
    protected final ShellScreen host;
    /** Shared drawing. */
    protected final Draw draw;
    /** Shared fonts. */
    protected final Fonts fonts;
    /** Scroll physics and clip of the content rect. */
    protected final ScrollArea scroll = new ScrollArea();
    /** Visible content rect. */
    protected float x, y, w, h;
    /** Seconds since the tab was shown. */
    protected float shown;
    /** Global time for animated details. */
    protected float time;
    private boolean visible;

    /** Creates the tab. */
    protected ProfileTab(Main game, ShellScreen host) {
        this.game = game;
        this.host = host;
        this.draw = game.draw();
        this.fonts = game.fonts();
    }

    /** Sets the visible content rect. */
    public void layout(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        scroll.setBounds(x, y, w, h);
        onLayout();
    }

    /** Shows or hides the tab (hidden tabs disable their widgets). */
    public void setVisible(boolean on) {
        if (on && !visible) {
            shown = 0f;
            scroll.reset();
        }
        visible = on;
        onVisibility(on);
    }

    /** True while shown. */
    public boolean isVisible() {
        return visible;
    }

    /** The tab's scroll area. */
    public ScrollArea scroll() {
        return scroll;
    }

    /** Advances scroll physics and per-frame widget placement. */
    public void update(float dt) {
        shown += dt;
        time += dt;
        scroll.setContentHeight(contentHeight());
        scroll.update(dt);
        place(scroll.top() + scroll.offset());
    }

    /** Draws the clipped content and the scroll indicator. */
    public void draw(Batch b, float alpha) {
        scroll.beginClip(b, host.screenViewport(), 24f);
        drawContent(b, scroll.top() + scroll.offset(), alpha);
        scroll.endClip(b);
        scroll.drawIndicator(draw, alpha, 20f);
    }

    /** Entrance of row {@code i} (40 ms stagger, 280 ms pow3Out). */
    protected float rowIn(int i) {
        return Anim.ease(Anim.POW3_OUT, shown, Math.min(i, 12) * Anim.STAGGER_LIST, Anim.MEDIUM);
    }

    /**
     * Rebuilds cached rows for diver {@code p} (diver switched, store revision changed);
     * {@code stats} is the live, counting snapshot the tab may keep and read every frame.
     */
    public abstract void refresh(Profile p, DiverStats stats);

    /** Total content height. */
    protected abstract float contentHeight();

    /** Draws the content with its top edge at {@code top} (the batch is drawing, clipped). */
    protected abstract void drawContent(Batch b, float top, float alpha);

    /** Called after {@link #layout}. */
    protected void onLayout() {
    }

    /** Positions widgets for the scrolled top edge (every frame). */
    protected void place(float top) {
    }

    /** Called when shown or hidden. */
    protected void onVisibility(boolean on) {
    }

    /** Touch down outside the list and the widgets (DST 10's chart); true when taken. */
    public boolean touchDown(float px, float py, int pointer) {
        return false;
    }

    /** Drag of a pointer this tab took. */
    public boolean touchDragged(float px, float py, int pointer) {
        return false;
    }

    /** Release of a pointer this tab took. */
    public boolean touchUp(float px, float py, int pointer) {
        return false;
    }

    /** Drops {@code pointer} (−1 = every pointer). */
    public void cancel(int pointer) {
    }

    /** Desktop hover. */
    public void mouseMoved(float px, float py) {
    }

    /** Mouse wheel; true when taken (before the list scrolls). */
    public boolean scrolled(float amountY) {
        return false;
    }
}
