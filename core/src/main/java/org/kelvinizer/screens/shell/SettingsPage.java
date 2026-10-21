package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.viewport.Viewport;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;

/**
 * 设置的一个分页（如 AUDIO、VISUAL）：一列带裁剪、可滚动的设置行，滚动手感与地图相同。
 * 每次打开时各行依次滑入；控件每帧按滚动位置重新摆放，点击区域始终与画面一致。
 * 各行放得下时这一页不会滚动；OTHER 最后的 K &amp; K 那一行占满其他行剩下的高度；
 * 游玩界面视觉参数画面的两列把剩下的高度平分给各行，两列都排到底；AUDIO 也这样平分，每行的内容放在加高后的中间。
 *
 * <p>One Settings tab (DESIGN §9.1): a vertical list of {@link SettingRow}s in a clipped
 * {@link ScrollArea} with map-style scroll physics. Rows enter with a 320 ms {@code pow3Out}
 * slide and a 40 ms stagger each time the page is shown. Row controls are re-placed every frame
 * from the scroll offset, so hit rects always match what is drawn; rows outside the visible rect
 * ignore input. Nothing is allocated per frame.
 *
 * <p>Since Kelvin 2026-10-18 05:58 / 06:03 ("OTHER" and "GAMEPLAY" must not scroll): a row that
 * {@link SettingRow#fills() fills} takes the height the others leave, and the 40-unit bottom pad
 * only counts when the rows alone are taller than the list, so a page whose rows fit never scrolls. A
 * {@link #setSpread spread} page (the GAMEPLAY VISUALS columns, Kelvin 2026-10-18 15:53) shares the spare height
 * among its rows instead, so the list reaches its bottom; a {@link #setSpreadCentred centred} one (AUDIO, Kelvin
 * 2026-10-21) also puts each row's content in the middle of its taller row.
 */
public final class SettingsPage {
    private static final float SLIDE = 56f;
    private static final float BOTTOM_PAD = 40f;

    private final Draw draw;
    private SettingRow[] rows = new SettingRow[16];
    private int count;
    private final ScrollArea scroll = new ScrollArea();
    private float x, y, w, h, k = 1f, controlW = 480f;
    private float shown;
    private boolean spread, centred;

    /** Creates an empty page. */
    public SettingsPage(Draw draw) {
        this.draw = draw;
    }

    /** Adds a row (construction time, or after {@link #clear()}); returns it. */
    public <T extends SettingRow> T add(T row) {
        if (count == rows.length) {
            SettingRow[] grown = new SettingRow[rows.length * 2];
            System.arraycopy(rows, 0, grown, 0, count);
            rows = grown;
        }
        rows[count++] = row;
        return row;
    }

    /** Removes every row (the licence text page swaps its text this way). */
    public void clear() {
        for (int i = 0; i < count; i++) rows[i] = null;
        count = 0;
    }

    /** Shares the height the rows leave among them, so the list reaches its bottom; returns this page. */
    public SettingsPage setSpread(boolean on) {
        spread = on;
        return this;
    }

    /**
     * Like {@link #setSpread}, but half of each row's share goes above its content, so the content sits in the
     * middle of its taller row (AUDIO, Kelvin 2026-10-21: 「把每一列的高度扩大，间隔开一点」); returns this page.
     */
    public SettingsPage setSpreadCentred(boolean on) {
        spread = on;
        centred = on;
        return this;
    }

    /** Height of all rows at uiScale {@code k} (a filling row at its own height). */
    public float rowsHeight(float k) {
        float t = 0f;
        for (int i = 0; i < count; i++) t += rows[i].height(k);
        return t;
    }

    /** Number of rows. */
    public int size() {
        return count;
    }

    /** Row {@code i}. */
    public SettingRow get(int i) {
        return rows[i];
    }

    /** The page's scroll area. */
    public ScrollArea scroll() {
        return scroll;
    }

    /**
     * Sets the visible list rect and the uiScale {@code k}. The control column is 480 × k wide,
     * narrowed down to 52 % of the list on tight layouts (never below 360).
     */
    public void layout(float x, float y, float w, float h, float k) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.k = k;
        controlW = Math.max(360f, Math.min(480f * k, w * 0.52f));
        scroll.setBounds(x, y, w, h);
    }

    /** Restarts the entrance stagger and scrolls to the top. */
    public void show() {
        shown = 0f;
        scroll.reset();
        for (int i = 0; i < count; i++) rows[i].sync();
    }

    /** Re-reads every row's value. */
    public void syncAll() {
        for (int i = 0; i < count; i++) rows[i].sync();
    }

    private float rowIn(int i) {
        return Anim.ease(Anim.POW3_OUT, shown, Math.min(i, 10) * Anim.STAGGER_LIST, 0.32f);
    }

    /** Advances scroll physics, places every row and updates its control. */
    public void update(float dt) {
        shown += dt;
        // a filling row (the K & K row ending OTHER) takes what the other rows leave of the list
        float total = 0f, fill = 0f;
        for (int i = 0; i < count; i++) {
            if (!rows[i].fills()) total += rows[i].height(k);
        }
        for (int i = 0; i < count; i++) {
            if (rows[i].fills()) fill = rows[i].fillHeight(scroll.height() - total - BOTTOM_PAD, k);
        }
        total += fill;
        float extra = spread && count > 0 && total < scroll.height() ? (scroll.height() - total) / count : 0f;
        total += extra * count;
        // the bottom pad is for lists that scroll: rows that fit never scroll for its sake (Kelvin 2026-10-18 06:03)
        scroll.setContentHeight(total > scroll.height() ? total + BOTTOM_PAD
            : Math.min(total + BOTTOM_PAD, scroll.height()));
        scroll.update(dt);
        float top = scroll.top() + scroll.offset();
        for (int i = 0; i < count; i++) {
            SettingRow r = rows[i];
            float rh = (r.fills() ? fill : r.height(k)) + extra;
            float slide = SLIDE * (1f - rowIn(i));
            boolean visible = scroll.isVisible(top - rh, top);
            // a centred spread starts the row's rect half its share lower (its hairline stays at the bottom)
            r.place(x + slide, top - rh, w, centred ? rh - extra * 0.5f : rh, controlW, k, visible);
            r.update(dt);
            top -= rh;
        }
    }

    /** Draws the visible rows clipped to the list rect, then the scroll indicator. */
    public void draw(Batch b, Viewport viewport, float alpha) {
        scroll.beginClip(b, viewport, 28f);
        // two passes (shapes, then text): see SettingRow
        for (int i = 0; i < count; i++) {
            SettingRow r = rows[i];
            float e = rowIn(i) * alpha;
            if (r.shownInList && e > 0.002f) r.drawShapes(b, e);
        }
        for (int i = 0; i < count; i++) {
            SettingRow r = rows[i];
            float e = rowIn(i) * alpha;
            if (r.shownInList && e > 0.002f) r.drawLabels(b, e);
        }
        scroll.endClip(b);
        scroll.drawIndicator(draw, alpha, 14f);
    }

    // ------------------------------------------------------------------ input
    /** Touch down: rows inside the visible rect; a grabbed slider stops the list from scrolling. */
    public boolean touchDown(float px, float py, int pointer) {
        if (!scroll.contains(px, py)) return false;
        for (int i = 0; i < count; i++) {
            if (rows[i].touchDown(px, py, pointer)) {
                if (rows[i].isGrabbing()) scroll.cancel();
                return true;
            }
        }
        return false;
    }

    /** Drag. */
    public boolean touchDragged(float px, float py, int pointer) {
        boolean any = false;
        for (int i = 0; i < count; i++) {
            any |= rows[i].touchDragged(px, py, pointer);
            if (rows[i].isGrabbing()) scroll.cancel();
        }
        return any;
    }

    /** Touch up. */
    public boolean touchUp(float px, float py, int pointer) {
        boolean any = false;
        for (int i = 0; i < count; i++) any |= rows[i].touchUp(px, py, pointer);
        return any;
    }

    /** Cancels every press. */
    public void cancel() {
        for (int i = 0; i < count; i++) rows[i].cancel();
    }

    /** Desktop hover. */
    public void mouseMoved(float px, float py) {
        for (int i = 0; i < count; i++) rows[i].mouseMoved(px, py);
    }

    /** Mouse wheel. */
    public boolean scrolled(float amountY) {
        return scroll.scrolled(amountY);
    }
}
