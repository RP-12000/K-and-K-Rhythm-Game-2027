package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Pressable;

/**
 * 外观由所有者自己画的可按控件，用于卡片、列表行、自定义小标签和图标热区。
 * 它只提供按下缩放、高亮、悬停、拒绝抖动、波纹和震动这些交互状态，自己什么都不画。
 *
 * <p>A {@link Pressable} whose visuals are drawn by its owner (cards, rows, chips with custom
 * content, glyph hot-spots). It carries the DESIGN §1.5 interaction state — press scale,
 * brightness, hover, "no" shake, ripple, haptic — and the owner reads it through
 * {@link #visualScale()}, {@link #brightness()}, {@link #shakeX()}, {@link #liftY()} and
 * {@link #drawPress(Color, float)} while drawing its own content. {@link #draw(Batch, float)}
 * draws nothing, so a tappable can sit in a {@link WidgetSet} next to real widgets.
 */
public class Tappable extends Pressable {
    /** Optional integer tag (row index, option index) for shared callbacks. */
    public int tag;

    private ScrollArea clip;

    /** Creates a tappable with an empty rect; position it with {@link #setBounds}. */
    public Tappable(Draw draw) {
        super(draw);
    }

    /**
     * Restricts hits to the visible rect of {@code area} (rows inside a scrolling list); null
     * removes the restriction.
     */
    public Tappable setClip(ScrollArea area) {
        clip = area;
        return this;
    }

    @Override
    public boolean hit(float px, float py, float grow) {
        if (clip != null && !clip.contains(px, py)) return false;
        return super.hit(px, py, grow);
    }

    /** Draws the press ripple in {@code color} (call after the owner's background). */
    public void drawPress(Color color, float alpha) {
        if (!visible) return;
        ripple.color(color);
        drawRipple(alpha);
    }

    /** 0..1 press brightness ramp (micro). */
    public float pressAmount() {
        return press;
    }

    /** 0..1 hover ramp (desktop). */
    public float hoverAmount() {
        return hover;
    }

    /** Draws nothing: the owner draws the content (see the class doc). */
    @Override
    public void draw(Batch b, float alpha) {
    }
}
