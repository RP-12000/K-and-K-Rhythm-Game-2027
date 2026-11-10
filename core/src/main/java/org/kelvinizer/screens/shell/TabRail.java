package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Spring;
import org.kelvinizer.ui.Theme;
import space.earlygrey.shapedrawer.ShapeDrawer;

/**
 * 设置的分类导航：宽屏时是左侧 300 宽的竖栏，4:3 时是顶部一排标签。
 * 每项一个图标加名字，选中的底板在各项之间弹性滑动。
 *
 * <p>Settings navigation (DESIGN §9.1): a left rail 300 wide with one item per tab (icon plus
 * UI_BOLD 22), or, on 4:3, a top tab row. The selection plate ({@code panelHi} with a
 * {@code groundSig} edge bar) slides between items on a snappy spring; items are
 * {@link Tappable}s (press scale, ripple, hover) added to the host's widget set, so they fire on
 * release. Icons come from the procedural {@link Icons} set, plus an eye (VISUAL), a
 * line-and-note glyph (GAMEPLAY) and three dots (OTHER) drawn here on the same 64-unit grid.
 */
public final class TabRail {
    /** Icon codes for {@link #TabRail}. */
    public static final int ICON_SPEAKER = 0, ICON_TIMING = 1, ICON_EYE = 2, ICON_LINES = 3, ICON_PERSON = 4,
        ICON_HELP = 5, ICON_MORE = 6;

    /** Selection listener. */
    public interface Listener {
        /** Called after a tap selected a new tab. */
        void selected(int index);
    }

    private final Main game;
    private final Draw draw;
    private final Fonts fonts;
    private final CharSequence[] labels;
    private final int[] icons;
    private final Tappable[] items;
    private final Spring pos = new Spring(Spring.Preset.SNAPPY, 0f);
    private final Color tmp = new Color();
    private int selected;
    private boolean vertical = true;
    private float x, y, itemW, itemH;
    private Listener listener;

    /** Creates the rail; its items are added to {@code widgets}. */
    public TabRail(Main game, WidgetSet widgets, CharSequence[] labels, int[] icons) {
        this.game = game;
        this.draw = game.draw();
        this.fonts = game.fonts();
        this.labels = labels;
        this.icons = icons;
        items = new Tappable[labels.length];
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            items[i] = widgets.add(new Tappable(draw));
            items[i].setOnTap(new Runnable() {
                @Override
                public void run() {
                    pick(index);
                }
            });
        }
    }

    /** Sets the listener. */
    public TabRail setListener(Listener l) {
        listener = l;
        return this;
    }

    /** Selects {@code index} without a callback; {@code animate = false} snaps the plate. */
    public void select(int index, boolean animate) {
        selected = MathUtils.clamp(index, 0, items.length - 1);
        if (animate) pos.setTarget(selected);
        else pos.snap(selected);
    }

    /** Selected index. */
    public int selected() {
        return selected;
    }

    private void pick(int index) {
        if (index == selected) return;
        select(index, true);
        if (listener != null) listener.selected(index);
    }

    /** Lays the items out top-down from {@code topY} at {@code x}, each {@code w × itemH}. */
    public void layoutVertical(float x, float topY, float w, float itemH) {
        vertical = true;
        this.x = x;
        this.y = topY;
        this.itemW = w;
        this.itemH = itemH;
        for (int i = 0; i < items.length; i++) items[i].setBounds(x, topY - (i + 1) * itemH, w, itemH);
    }

    /** Lays the items out left to right as a tab row of total width {@code w}, height {@code h}. */
    public void layoutHorizontal(float x, float y, float w, float h) {
        vertical = false;
        this.x = x;
        this.y = y;
        this.itemW = w / items.length;
        this.itemH = h;
        for (int i = 0; i < items.length; i++) items[i].setBounds(x + i * itemW, y, itemW, h);
    }

    /** Bottom edge of the rail (vertical) or of the tab row. */
    public float bottom() {
        return vertical ? y - items.length * itemH : y;
    }

    /** Advances the plate spring. */
    public void update(float dt) {
        pos.update(dt);
    }

    /** Draws plate, items and labels with {@code alpha}. */
    public void draw(Batch b, float alpha) {
        float p = pos.value();
        if (vertical) {
            float py = y - (p + 1f) * itemH;
            draw.roundRect(x, py + 6f, itemW, itemH - 12f, 16f, Theme.PANEL_HI, alpha);
            draw.rect(x, py + 22f, 4f, itemH - 44f, Theme.GROUND_SIG, alpha);
        } else {
            float px = x + p * itemW;
            draw.roundRect(px + 6f, y, itemW - 12f, itemH, 16f, Theme.PANEL_HI, alpha);
            draw.rect(px + 28f, y + 4f, itemW - 56f, 4f, Theme.GROUND_SIG, alpha);
        }
        for (int i = 0; i < items.length; i++) drawItem(b, i, alpha);
    }

    private void drawItem(Batch b, int i, float alpha) {
        Tappable t = items[i];
        float near = MathUtils.clamp(1f - Math.abs(pos.value() - i), 0f, 1f);
        Theme.mix(Theme.INK2, Theme.INK1, near, tmp);
        Theme.brighten(tmp, t.brightness(), tmp);
        float s = t.visualScale();
        float lift = t.liftY();
        if (t.pressAmount() > 0f || t.hoverAmount() > 0f) {
            float a = (0.06f * t.pressAmount() + 0.04f * t.hoverAmount()) * alpha;
            if (vertical) draw.roundRect(t.x(), t.y() + 6f, t.width(), t.height() - 12f, 16f, Theme.INK1, a);
            else draw.roundRect(t.x() + 6f, t.y(), t.width() - 12f, t.height(), 16f, Theme.INK1, a);
        }
        t.drawPress(Theme.INK1, alpha * 0.7f);
        float icon = 34f * s;
        if (vertical) {
            float cy = t.centerY() + lift;
            float ix = t.x() + 44f;
            drawIcon(icons[i], ix, cy, icon, near > 0.5f ? Theme.GROUND_SIG : tmp, alpha);
            fonts.drawScaled(b, Fonts.Role.UI_BOLD, 22f, s, labels[i], ix + 36f, cy, Align.left | Fonts.VCENTER,
                t.width() - 96f, 0.75f, tmp, alpha);
        } else {
            float cy = t.centerY() + lift;
            float lw = fonts.width(Fonts.Role.UI_BOLD, 22f, labels[i]) * s;
            float total = icon + 14f + Math.min(lw, t.width() - icon - 40f);
            float ix = t.centerX() - total * 0.5f + icon * 0.5f;
            drawIcon(icons[i], ix, cy, icon, near > 0.5f ? Theme.GROUND_SIG : tmp, alpha);
            fonts.drawScaled(b, Fonts.Role.UI_BOLD, 22f, s, labels[i], ix + icon * 0.5f + 14f, cy,
                Align.left | Fonts.VCENTER, t.width() - icon - 40f, 0.7f, tmp, alpha);
        }
    }

    /** Draws icon {@code code} centred at (cx, cy), {@code size} across. */
    public void drawIcon(int code, float cx, float cy, float size, Color c, float alpha) {
        ShapeDrawer sd = draw.shapes();
        switch (code) {
            case ICON_SPEAKER: Icons.draw(sd, Icons.Icon.SPEAKER, cx, cy, size, c, alpha); break;
            case ICON_TIMING: Icons.draw(sd, Icons.Icon.CROSSHAIR, cx, cy, size, c, alpha); break;
            case ICON_PERSON: Icons.draw(sd, Icons.Icon.PERSON, cx, cy, size, c, alpha); break;
            case ICON_HELP: Icons.draw(sd, Icons.Icon.HELP, cx, cy, size, c, alpha); break;
            case ICON_EYE: drawEye(sd, cx, cy, size, c, alpha); break;
            case ICON_MORE: drawDots(sd, cx, cy, size, c, alpha); break;
            case ICON_LINES:
            default: drawLines(cx, cy, size, c, alpha); break;
        }
    }

    private void drawEye(ShapeDrawer sd, float cx, float cy, float size, Color c, float alpha) {
        float k = size / 64f;
        float old = sd.getPackedColor();
        sd.setColor(c.r, c.g, c.b, c.a * alpha);
        float r = 30f * k, off = 16f * k, sw = 5f * k;
        float a0 = (float) Math.asin(off / r);
        sd.arc(cx, cy - off, r, a0, MathUtils.PI - 2f * a0, sw, false, 18);
        sd.arc(cx, cy + off, r, MathUtils.PI + a0, MathUtils.PI - 2f * a0, sw, false, 18);
        sd.filledCircle(cx, cy, 8f * k);
        sd.setColor(old);
    }

    private void drawDots(ShapeDrawer sd, float cx, float cy, float size, Color c, float alpha) {
        float k = size / 64f;
        float old = sd.getPackedColor();
        sd.setColor(c.r, c.g, c.b, c.a * alpha);
        for (int i = -1; i <= 1; i++) sd.filledCircle(cx + i * 20f * k, cy, 6f * k);
        sd.setColor(old);
    }

    private void drawLines(float cx, float cy, float size, Color c, float alpha) {
        float k = size / 64f;
        draw.rect(cx - 26f * k, cy - 20f * k, 52f * k, 5f * k, c, alpha);
        draw.rect(cx - 26f * k, cy + 15f * k, 52f * k, 5f * k, c, 0.6f * alpha);
        draw.roundRect(cx - 12f * k, cy - 8f * k, 24f * k, 10f * k, 3f * k, c, alpha);
    }
}
