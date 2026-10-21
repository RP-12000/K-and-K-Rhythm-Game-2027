package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;

/**
 * 提示条队列：屏幕顶部中间弹出的胶囊形提示，滑入后停留约 1.6 秒再淡出，最多排队 4 条。
 * 重复的文字只会重新计时，不会再排一次；在 {@link UiKit#renderOverlays} 的覆盖层里绘制，构造后不分配内存。
 *
 * <p>Toast queue (DESIGN §1.6): a {@code panel} pill with UI 22 text (sizes × {@code Ui.uiScale()}),
 * top-centre with its top edge at {@code safeTop − 140}; 280 ms in ({@code pow3Out}, drops 24 and
 * fades in), holds 1.6 s, 200 ms out ({@code pow2In}). Up to {@value #CAPACITY} messages wait in a FIFO; showing the
 * text that is already on screen restarts its hold instead of queueing a duplicate. A screen whose
 * content sits under the default slot moves it with {@link #setAnchorTop} while it is shown and
 * restores it with {@link #resetAnchor()} when it hides.
 *
 * <p>Text is copied into pooled builders, so callers may pass a reused StringBuilder. Drawn in
 * world units by the overlay pass ({@link UiKit#renderOverlays}); no allocation after
 * construction.
 */
public final class Toast {
    /** Maximum queued messages (including the visible one). */
    public static final int CAPACITY = 4;
    private static final float IN = 0.28f, HOLD = 1.6f, OUT = 0.20f;
    private static final float CAP = 22f, H = 72f, PAD = 36f;

    private final Draw draw;
    private final Fonts fonts;
    private final StringBuilder[] texts = new StringBuilder[CAPACITY];
    private final Color[] accents = new Color[CAPACITY];
    private final boolean[] hasAccent = new boolean[CAPACITY];
    private int head, count;
    private float t;
    /** Top edge of the pill in world units; NaN = the default {@code Ui.safeTop() − 140}. */
    private float anchorTop = Float.NaN;

    /** Creates an empty queue. */
    public Toast(Draw draw, Fonts fonts) {
        this.draw = draw;
        this.fonts = fonts;
        for (int i = 0; i < CAPACITY; i++) {
            texts[i] = new StringBuilder(64);
            accents[i] = new Color(Theme.GROUND_SIG);
        }
    }

    /** Queues a message. */
    public void show(CharSequence text) {
        show(text, null);
    }

    /** Queues a message with a leading accent dot (null = none). */
    public void show(CharSequence text, Color accent) {
        if (text == null || text.length() == 0) return;
        if (count > 0 && equals(texts[head], text)) {
            if (t > IN) t = IN; // restart the hold
            return;
        }
        if (count > 0 && equals(texts[(head + count - 1) % CAPACITY], text)) return;
        if (count == CAPACITY) {
            // drop the oldest waiting message (never the visible one): bubble it to the end
            for (int i = 1; i < count - 1; i++) {
                int a = (head + i) % CAPACITY, b = (head + i + 1) % CAPACITY;
                StringBuilder sb = texts[a];
                texts[a] = texts[b];
                texts[b] = sb;
                Color c = accents[a];
                accents[a] = accents[b];
                accents[b] = c;
                boolean h = hasAccent[a];
                hasAccent[a] = hasAccent[b];
                hasAccent[b] = h;
            }
            count--;
        }
        int slot = (head + count) % CAPACITY;
        texts[slot].setLength(0);
        texts[slot].append(text);
        hasAccent[slot] = accent != null;
        if (accent != null) accents[slot].set(accent);
        if (count == 0) t = 0f;
        count++;
    }

    /** Removes every message immediately. */
    public void clear() {
        count = 0;
        t = 0f;
    }

    /** True while a toast is visible. */
    public boolean isShowing() {
        return count > 0;
    }

    /**
     * Moves the pill's top edge to world y {@code topY} until {@link #resetAnchor()} (e.g. the
     * result screen keeps toasts clear of its score).
     */
    public void setAnchorTop(float topY) {
        anchorTop = topY;
    }

    /** Back to the default anchor, top edge at {@code Ui.safeTop() − 140}. */
    public void resetAnchor() {
        anchorTop = Float.NaN;
    }

    /** Advances the current toast. */
    public void update(float dt) {
        if (count == 0) return;
        t += dt;
        if (t >= IN + HOLD + OUT) {
            head = (head + 1) % CAPACITY;
            count--;
            t = 0f;
        }
    }

    /** Draws the current toast in world units; the batch must be drawing. */
    public void draw(Batch b) {
        if (count == 0) return;
        float a, dy;
        if (t < IN) {
            float e = Anim.POW3_OUT.apply(t / IN);
            a = e;
            dy = 24f * (1f - e);
        } else if (t < IN + HOLD) {
            a = 1f;
            dy = 0f;
        } else {
            float e = Anim.POW2_IN.apply(Math.min(1f, (t - IN - HOLD) / OUT));
            a = 1f - e;
            dy = 12f * e;
        }
        if (a <= 0.003f) return;
        StringBuilder text = texts[head];
        // sizes follow the UI scale (DESIGN §0.3); the anchor stays the pill's top edge
        float k = Ui.uiScale();
        float cap = CAP * k, h = H * k, pad = PAD * k;
        float maxW = Math.min(Ui.worldW() - 160f, 1100f * k);
        float tw = Math.min(fonts.width(Fonts.Role.UI, cap, text), maxW - 2f * pad);
        boolean dot = hasAccent[head];
        float w = tw + 2f * pad + (dot ? 26f * k : 0f);
        float cx = (Ui.left() + Ui.right()) * 0.5f;
        float top = (Float.isNaN(anchorTop) ? Ui.safeTop() - 140f : anchorTop) + dy;
        float x = cx - w * 0.5f, y = top - h;
        draw.glowDot(cx, y + h * 0.5f - 8f, w + 120f, Theme.SHADOW, 0.5f * a);
        draw.roundRect(x, y, w, h, h * 0.5f, Theme.PANEL_HI, a);
        draw.roundRectStroke(x, y, w, h, h * 0.5f, 1f, Theme.STROKE, a);
        float tx = cx;
        if (dot) {
            float dx = x + pad + 6f * k;
            draw.glowDot(dx, y + h * 0.5f, 34f * k, accents[head], 0.5f * a);
            draw.disc(dx, y + h * 0.5f, 6f * k, accents[head], a);
            tx = dx + 20f * k + tw * 0.5f;
        }
        fonts.drawFit(b, Fonts.Role.UI, cap, text, tx, y + h * 0.5f, Fonts.VCENTER, tw + 1f, 0.8f, Theme.INK1, a);
    }

    private static boolean equals(StringBuilder a, CharSequence b) {
        int n = a.length();
        if (n != b.length()) return false;
        for (int i = 0; i < n; i++) if (a.charAt(i) != b.charAt(i)) return false;
        return true;
    }
}
