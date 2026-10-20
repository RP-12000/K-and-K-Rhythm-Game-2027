package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Pressable;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * “所有曲目”层的排序面板：从 SORT 按钮升起，上面选顺序（默认、歌名、定数、最高分、最近游玩），
 * 下面是筛选（NEW / FULL COMBO / RESONATED，一次一个，再点取消）；按曲包分组已经去掉。
 * 点一下马上重排，面板保持打开；点面板外面、再点 SORT 或按返回键关闭。
 *
 * <p>The ALL SONGS sort panel (Kelvin 2026-10-13 21:58: the layout and order options, which were a
 * chip row under the badge, gathered behind one SORT button at the bottom right, between the rise
 * plate and recentre). It rises from the button ({@link #W} wide, {@link MapStyle#FILTER_IN}
 * {@code pow3Out}) with two groups of {@link TextChip}s: ORDER ({@link MapWorld#SORT_NAMES}, three per row) and
 * FILTER (the {@link MapFilter} values, merged in from their own panel by Kelvin 2026-10-16 22:39: one at a
 * time, tapping the lit one clears it). The LAYOUT group (packs / all) went on 2026-10-19 13:14. A tap re-sorts
 * the map at once and keeps the panel open; a touch outside it closes it (the map does not receive that touch).
 *
 * <p>Every metric is the scale-1 value × the panel scale: the HUD scale, shrunk (to
 * {@link #MIN_FIT} at most) to fit between the button and the top cluster. Chip rows are at least
 * {@code P} apart. No allocation per frame.
 */
final class SortPanel {
    /** Panel events (implemented by the screen). */
    interface Listener {
        /** An ORDER chip was tapped: {@code sort} is a {@code MapWorld.SORT_*}. */
        void gridSort(int sort);

        /** A FILTER chip toggled the filter. */
        void filterChanged();
    }

    /** Panel width at scale 1: every order chip label fits on one line. */
    static final float W = 740f;
    /** Scale-1 metrics: padding, header, group label band, chip height, chip gap, order row pitch, group gap. */
    private static final float PAD = 32f, HEAD_H = 88f, LABEL_H = 48f, CHIP_H = 80f, CHIP_GAP = 16f, ROW_PITCH = 104f,
        GROUP_GAP = 24f;
    /** Panel height at scale 1 without the {@code P}-floored order row pitch. */
    private static final float FIXED_H = HEAD_H + LABEL_H + CHIP_H + GROUP_GAP + LABEL_H + CHIP_H + PAD;
    private static final float CLOSE_T = 0.18f;
    /** The panel never shrinks below this scale to fit. */
    private static final float MIN_FIT = 0.8f;
    private static final String TITLE = "排序 SORT", ORDER = "顺序 ORDER", FILTER = "筛选 FILTER";
    /** In English without the Chinese (Kelvin 2026-10-20: 如果语言选择是英语就不要出现中文). */
    private static final String TITLE_EN = "SORT", ORDER_EN = "ORDER", FILTER_EN = "FILTER";
    /** Order chip labels, in {@link MapWorld#SORT_NAMES} order. */
    private static final String[] ORDER_LABELS = {"默认 DEFAULT", "歌名 TITLE", "定数 LEVEL", "最高分 BEST", "最近 RECENT"},
        ORDER_LABELS_EN = {"DEFAULT", "TITLE", "LEVEL", "BEST", "RECENT"};

    private final Draw draw;
    private final MapRenderer r;
    private final Listener listener;
    final TextChip[] sorts = new TextChip[MapWorld.SORT_NAMES.length];
    /** FILTER chips: {@link MapFilter} values 1–3. */
    final TextChip[] filters = new TextChip[MapFilter.LABELS.length - 1];
    private final MapFilter filter;
    private final Pressable[] controls;

    private boolean open;
    private float t, k;
    private float x, top, dy;
    /** Panel size and scale of this frame. */
    private float w = W, h = FIXED_H + ROW_PITCH, ks = 1f;
    private int ptr = -1;
    private Pressable pressed;
    private final Color c1 = new Color();
    /** The language the order chips are labelled in. */
    private boolean chipsZh = true;

    SortPanel(Draw draw, MapRenderer r, MapFilter filter, final Listener listener) {
        this.draw = draw;
        this.r = r;
        this.filter = filter;
        this.listener = listener;
        for (int i = 0; i < sorts.length; i++) {
            final int s = i;
            sorts[i] = new TextChip(draw, r, ORDER_LABELS[i], null);
            sorts[i].setOnTap(new Runnable() {
                @Override
                public void run() {
                    listener.gridSort(s);
                }
            });
        }
        for (int i = 0; i < filters.length; i++) {
            final int f = i + 1;
            filters[i] = new TextChip(draw, r, MapFilter.LABELS[f], null);
            filters[i].setOnTap(new Runnable() {
                @Override
                public void run() {
                    SortPanel.this.filter.toggle(f);
                    listener.filterChanged();
                }
            });
        }
        controls = new Pressable[]{sorts[0], sorts[1], sorts[2], sorts[3], sorts[4], filters[0], filters[1],
            filters[2]};
    }

    // ------------------------------------------------------------------ state
    /** Opens the panel (280 ms rise). */
    void open() {
        if (open) return;
        open = true;
        t = 0f;
    }

    /** Closes the panel (180 ms fade). */
    void close() {
        if (!open) return;
        open = false;
        t = 0f;
        cancel();
    }

    boolean isOpen() {
        return open;
    }

    /** Visible amount 0..1. */
    float amount() {
        return k;
    }

    /** True when (px, py) is inside the panel rect. */
    boolean contains(float px, float py) {
        return k > 0.05f && px >= x && px <= x + w && py >= top - h && py <= top;
    }

    float left() {
        return x;
    }

    float top() {
        return top;
    }

    float width() {
        return w;
    }

    float height() {
        return h;
    }

    // ------------------------------------------------------------------ layout
    /**
     * Anchors the panel above the SORT button: its right edge at {@code right} (kept inside the
     * safe left edge), its bottom at {@code bottomY}, at the HUD scale, shrunk (to {@link #MIN_FIT}
     * at most) so that its top stays under {@code topY}. Rows (scale-1 offsets below the top):
     * header 0–88, ORDER label 88–136, the order chips three per row at a pitch ≥ {@code P}, then
     * after a 24 gap the FILTER label and its three chips.
     */
    void layout(float right, float bottomY, float topY) {
        float P = Ui.touchTarget();
        float k = MapUi.k(), avail = topY - bottomY;
        float fit = avail / (FIXED_H + ROW_PITCH);
        if (ROW_PITCH * fit < P) fit = (avail - P) / FIXED_H;
        ks = Math.max(Math.min(k, fit), Math.min(k, MIN_FIT));
        w = W * ks;
        x = Math.max(Ui.safeLeft() + 24f * ks, right - w);
        float pitch = Math.max(ROW_PITCH * ks, P);
        h = FIXED_H * ks + pitch;
        top = bottomY + h + dy;
        float pad = PAD * ks, gap = CHIP_GAP * ks, chipH = CHIP_H * ks;
        float cx0 = x + pad, cw = w - 2f * pad;
        float thirdW = (cw - 2f * gap) / 3f;
        float rowTop = top - (HEAD_H + LABEL_H) * ks;
        for (int i = 0; i < sorts.length; i++) {
            int row = i / 3, col = i % 3;
            sorts[i].k = ks;
            sorts[i].setBounds(cx0 + col * (thirdW + gap), rowTop - row * pitch - chipH, thirdW, chipH);
            sorts[i].setMinHit(thirdW + gap, pitch);
        }
        float filterY = rowTop - pitch - chipH - (GROUP_GAP + LABEL_H) * ks - chipH;
        for (int i = 0; i < filters.length; i++) {
            filters[i].k = ks;
            filters[i].setBounds(cx0 + i * (thirdW + gap), filterY, thirdW, chipH);
            filters[i].setMinHit(thirdW + gap, Math.max(chipH, P));
        }
    }

    // ------------------------------------------------------------------ input
    /** Touch down: true when the panel claims it (inside). */
    boolean touchDown(float px, float py, int p) {
        if (!open || !contains(px, py)) return false;
        if (ptr >= 0) return true;
        ptr = p;
        pressed = null;
        for (Pressable c : controls) {
            if (c.touchDown(px, py, p)) {
                pressed = c;
                break;
            }
        }
        return true;
    }

    boolean touchDragged(float px, float py, int p) {
        if (p != ptr) return false;
        if (pressed != null) pressed.touchDragged(px, py, p);
        return true;
    }

    boolean touchUp(float px, float py, int p) {
        if (p != ptr) return false;
        ptr = -1;
        Pressable c = pressed;
        pressed = null;
        if (c != null) c.touchUp(px, py, p);
        return true;
    }

    void cancel() {
        for (Pressable c : controls) c.cancel();
        pressed = null;
        ptr = -1;
    }

    void mouseMoved(float px, float py) {
        if (!open) return;
        for (Pressable c : controls) c.mouseMoved(px, py);
    }

    // ------------------------------------------------------------------ frame
    /** Advances the rise animation and the chips; {@code sort} is the level's current order. */
    void update(float dt, int sort) {
        t += dt;
        if (open) {
            float p = Math.min(1f, t / MapStyle.FILTER_IN);
            k = Math.max(k, Anim.POW3_OUT.apply(p));
            dy = -40f * (1f - Anim.POW3_OUT.apply(p));
        } else {
            k = Math.max(0f, k - dt / CLOSE_T);
            dy = -24f * (1f - k);
        }
        if (chipsZh != r.zh) {
            chipsZh = r.zh;
            for (int i = 0; i < sorts.length; i++) sorts[i].setLabel(chipsZh ? ORDER_LABELS[i] : ORDER_LABELS_EN[i]);
        }
        for (int i = 0; i < sorts.length; i++) sorts[i].selected = sort == i;
        for (int i = 0; i < filters.length; i++) filters[i].selected = filter.status() == i + 1;
        for (Pressable c : controls) c.update(dt);
    }

    /** Draws the whole panel (overlay: atlas shapes, then its UI_BOLD text). */
    void draw(Batch b) {
        if (k <= 0.01f) return;
        float a = k;
        float rad = MapStyle.CARD_RADIUS * ks;
        c1.set(Theme.SHADOW);
        draw.sprite(draw.softDot(), x + w * 0.5f, top - h * 0.5f - 16f * ks, w + 140f * ks, h + 140f * ks, c1, 0.5f * a);
        draw.roundRect(x, top - h, w, h, rad, Theme.ABYSS_BLACK, MapStyle.UNDERLAY * a);
        draw.roundRect(x, top - h, w, h, rad, Theme.PANEL, a);
        draw.roundRectStroke(x, top - h, w, h, rad, 2f, Theme.STROKE, a);
        draw.rect(x + rad, top - 2f, w - 2f * rad, 2f, Theme.GROUND_SIG, 0.5f * a);
        for (Pressable c : controls) c.draw(b, a);
        float cx0 = x + PAD * ks;
        float c20 = MapUi.cap(20f, ks), c24 = MapUi.cap(24f, ks);
        r.bold(b, r.zh ? TITLE : TITLE_EN, c24, cx0, top - 48f * ks, Align.left | Fonts.VCENTER, Theme.INK1, a);
        r.bold(b, r.zh ? ORDER : ORDER_EN, c20, cx0, top - (HEAD_H + LABEL_H * 0.5f) * ks, Align.left | Fonts.VCENTER,
            Theme.INK2, a);
        float filterY = filters[0].y() + filters[0].height() + LABEL_H * 0.5f * ks;
        r.bold(b, r.zh ? FILTER : FILTER_EN, c20, cx0, filterY, Align.left | Fonts.VCENTER, Theme.INK2, a);
        for (TextChip c : sorts) c.drawText(b, a);
        for (TextChip c : filters) c.drawText(b, a);
    }
}
