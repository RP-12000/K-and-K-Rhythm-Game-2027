package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import org.kelvinizer.ui.Pressable;

/**
 * 一组可按控件，统一分发输入、更新和绘制：按下先交给最后加入（画在最上层）的控件，拖动、抬起和取消发给每一个控件。
 * 控件在构造时一次加好，之后不再分配内存。
 *
 * <p>An ordered set of {@link Pressable} widgets with shared input routing, update and draw.
 * Touch downs go to the most recently added widget first (it is drawn on top); drags, ups and
 * cancels reach every widget, which ignore pointers they do not own. No allocation after the
 * widgets are added (add them once, at construction).
 */
public final class WidgetSet {
    private Pressable[] items = new Pressable[8];
    private int count;

    /** Adds a widget (call once per widget, e.g. in a constructor); returns it. */
    public <T extends Pressable> T add(T widget) {
        if (count == items.length) {
            Pressable[] grown = new Pressable[items.length * 2];
            System.arraycopy(items, 0, grown, 0, count);
            items = grown;
        }
        items[count++] = widget;
        return widget;
    }

    /** Number of widgets. */
    public int size() {
        return count;
    }

    /** Widget {@code i} in insertion order. */
    public Pressable get(int i) {
        return items[i];
    }

    /** Routes a touch down (top-most first); true when a widget took it. */
    public boolean touchDown(float x, float y, int pointer) {
        for (int i = count - 1; i >= 0; i--) {
            if (items[i].touchDown(x, y, pointer)) return true;
        }
        return false;
    }

    /** Routes a drag; true when a widget owns the pointer. */
    public boolean touchDragged(float x, float y, int pointer) {
        boolean any = false;
        for (int i = 0; i < count; i++) any |= items[i].touchDragged(x, y, pointer);
        return any;
    }

    /** Routes a touch up; true when a widget owned the pointer (it fires on release inside). */
    public boolean touchUp(float x, float y, int pointer) {
        boolean any = false;
        for (int i = 0; i < count; i++) any |= items[i].touchUp(x, y, pointer);
        return any;
    }

    /** True while any widget is held by a pointer. */
    public boolean isPressed() {
        for (int i = 0; i < count; i++) if (items[i].isPressed()) return true;
        return false;
    }

    /** Cancels every press without firing. */
    public void cancel() {
        for (int i = 0; i < count; i++) items[i].cancel();
    }

    /** Desktop hover. */
    public void mouseMoved(float x, float y) {
        for (int i = 0; i < count; i++) items[i].mouseMoved(x, y);
    }

    /** Advances every widget. */
    public void update(float dt) {
        for (int i = 0; i < count; i++) items[i].update(dt);
    }

    /** Draws every visible widget in insertion order with {@code alpha}. */
    public void draw(Batch b, float alpha) {
        for (int i = 0; i < count; i++) items[i].draw(b, alpha);
    }
}
