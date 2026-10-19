package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;

/**
 * 长按节点时的小浮窗：节点周围的圆环填满后，在节点上方浮出曲名和各难度的最高分。
 * 镜头不动，松手就消失。
 *
 * <p>The long-press peek tooltip (DESIGN §3.8 step 4, P1): after the ring filled around a held node,
 * a floating panel above it shows the title and the best score per difficulty. No camera move;
 * the release dismisses it. Drawn last in the frame; metrics × {@link MapUi#k()}, caps through
 * {@link MapUi#cap(float, float)}. No allocation per frame (the score texts are built once per show).
 */
final class PeekTip {
    private static final float W = 460f, ROW = 40f, IN_T = 0.16f, OUT_T = 0.12f;

    private final Draw draw;
    private final MapRenderer r;
    private MapNode node;
    private boolean shown;
    private float k, t;
    private final StringBuilder[] score = new StringBuilder[4];
    private final int[] lead = new int[4];
    private final Color c1 = new Color();
    private float x, y, w = W, h;

    PeekTip(Draw draw, MapRenderer r) {
        this.draw = draw;
        this.r = r;
        for (int i = 0; i < 4; i++) score[i] = new StringBuilder(14);
    }

    /** Shows the tooltip for {@code n} (texts built here). */
    void show(MapNode n) {
        node = n;
        shown = true;
        t = 0f;
        for (int i = 0; i < 4; i++) {
            StringBuilder sb = score[i];
            sb.setLength(0);
            lead[i] = 0;
            if (n.launch || !n.has[i]) continue;
            if (!n.played[i]) continue;
            CardRecord.appendScore(sb, n.best[i]);
            for (int c = 0; c < sb.length(); c++) {
                char ch = sb.charAt(c);
                if (ch != '0' && ch != ',') break;
                lead[i] = c + 1;
            }
        }
    }

    /** Starts the fade out. */
    void hide() {
        shown = false;
    }

    boolean isShown() {
        return shown;
    }

    void update(float dt) {
        t += dt;
        if (shown) k = Math.min(1f, k + dt / IN_T);
        else {
            k = Math.max(0f, k - dt / OUT_T);
            if (k <= 0f) node = null;
        }
    }

    /** Draws the tooltip above the node, clamped inside [left, right] × [bottom, top] (HUD units). */
    void draw(Batch b, MapCamera cam, float left, float right, float bottom, float top) {
        MapNode n = node;
        if (n == null || k <= 0.01f) return;
        float ks = MapUi.k();
        float e = Anim.POW3_OUT.apply(k);
        float a = k;
        float c18 = MapUi.cap(18f, ks), c20 = MapUi.cap(20f, ks);
        float row = Math.max(ROW * ks, c20 * 1.7f), head = 96f * ks, pad = 28f * ks;
        w = W * ks;
        h = n.launch ? head : head + 4f * row + 16f * ks;
        float hx = cam.hudX(n.drawX()), hy = cam.hudY(n.drawY(r.time, r.reduceMotion));
        float above = (MapStyle.ARC_R1 + 40f) / cam.zoom;
        x = MathUtils.clamp(hx - w * 0.5f, left + 16f, right - 16f - w);
        y = hy + above;
        if (y + h > top - 16f) y = hy - above - h;
        y = MathUtils.clamp(y, bottom + 16f, top - 16f - h) - 12f * ks * (1f - e);
        float rad = 16f * ks;
        c1.set(Theme.SHADOW);
        draw.sprite(draw.softDot(), x + w * 0.5f, y + h * 0.5f - 10f * ks, w + 120f * ks, h + 120f * ks, c1, 0.45f * a);
        draw.roundRect(x, y, w, h, rad, Theme.ABYSS_BLACK, MapStyle.UNDERLAY * a);
        draw.roundRect(x, y, w, h, rad, Theme.PANEL, a);
        draw.roundRectStroke(x, y, w, h, rad, 2f, Theme.STROKE, a);
        Color acc = n.launch ? Theme.GOLD : Theme.difficultyAccent(r.lens.ordinal());
        draw.rect(x + rad, y + h - 2f, w - 2f * rad, 2f, acc, 0.6f * a);
        float rowTop = y + h - head;
        float dotX = x + 34f * ks;
        if (!n.launch) {
            for (int i = 0; i < 4; i++) {
                float cy = rowTop - i * row - row * 0.5f;
                if (n.has[i]) draw.disc(dotX, cy, 6f * ks, Theme.difficultyFill(i), a);
                else draw.circle(dotX, cy, 5f * ks, 2f, Theme.INK3, a);
            }
        }
        // text: one UI_BOLD phase (title, names, notes), then the fixed NUMBER cells (an overlay
        // drawn last in the frame, so every raster it touches is one more flush)
        float rx = x + w - pad;
        CharSequence title = n.launch ? "LAUNCH POINT" : n.song != null ? n.song.title() : "";
        r.boldFit(b, title, MapUi.cap(26f, ks), x + pad, y + h - 34f * ks, Align.left | Fonts.VCENTER, w - 2f * pad, 0.7f,
            n.launch ? Theme.GOLD : Theme.INK1, a);
        if (n.launch) {
            r.bold(b, "THE TWO SIGNAL LINES · THE BASICS", c18, x + pad, y + 30f * ks, Align.left | Fonts.VCENTER,
                Theme.INK2, a);
            return;
        }
        r.bold(b, n.isNew() ? "NEW SIGNAL" : "BEST PER DIFFICULTY", c18, x + pad, y + h - 70f * ks,
            Align.left | Fonts.VCENTER, Theme.INK2, a);
        for (int i = 0; i < 4; i++) {
            float cy = rowTop - i * row - row * 0.5f;
            Color c = !n.has[i] ? Theme.INK3 : i == 3 ? Theme.difficultyAccent(i) : Theme.difficultyFill(i);
            r.bold(b, Theme.DIFFICULTY_NAMES[i], c18, x + 52f * ks, cy, Align.left | Fonts.VCENTER, c, a);
            if (!n.has[i]) r.bold(b, "—", c20, rx, cy, Align.right | Fonts.VCENTER, Theme.INK3, a);
            else if (!n.played[i]) r.bold(b, "NOT YET DIVED", c18, rx, cy, Align.right | Fonts.VCENTER, Theme.INK3, a);
        }
        for (int i = 0; i < 4; i++) {
            if (score[i].length() == 0) continue;
            float cy = rowTop - i * row - row * 0.5f;
            r.fixed(b, score[i], MapUi.cap(22f, ks), rx, cy, Align.right | Fonts.VCENTER, Theme.INK1, Theme.LEADING_ZERO,
                lead[i], a, 1f);
        }
    }

    /** True while visible at all. */
    boolean visible() {
        return node != null && k > 0.01f;
    }

    /** Current rect (HUD units) for the occluders. */
    float x() {
        return x;
    }

    float y() {
        return y;
    }

    float width() {
        return w;
    }

    float height() {
        return h;
    }
}
