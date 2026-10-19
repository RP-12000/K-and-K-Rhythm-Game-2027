package org.kelvinizer.map;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Pressable;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 剧情卡：点剧情入口后，墨从洞口向右晕开、青色光丝散出（X6），卡片滑出，正文逐字写出；可以翻页，看完或点外面关闭。
 * 文本来自 maps/stories.json，现在是占位文字（标题待定）。
 *
 * <p>The story card (preview v17 X6 ink bloom): ink spreads right from the story hole with cyan
 * light threads, the card slides out and the page text writes in. Pages turn with ‹ / ›; DONE,
 * × or a tap outside closes it. Texts come from {@code maps/stories.json} (placeholders for now).
 * Lines are wrapped once when a story opens; drawing allocates nothing.
 */
final class StoryCard {
    private static final float INK = 0.45f, SLIDE = 0.25f, CPS = 70f;

    private final Draw draw;
    private final MapRenderer r;
    private final Runnable onClose;
    private final Button prev, next, close;
    private final Pressable[] controls;
    private final StringBuilder part = new StringBuilder(128);

    private boolean open;
    private float t, hx, hy, x, y, w, h;
    private String head = "", title = "";
    private final List<List<String>> pages = new ArrayList<>();
    private int page;
    private float pageT;

    StoryCard(Draw draw, Fonts fonts, MapRenderer r, Runnable onClose) {
        this.draw = draw;
        this.r = r;
        this.onClose = onClose;
        prev = new Button(draw, fonts, Button.Variant.SECONDARY, "‹");
        next = new Button(draw, fonts, Button.Variant.PRIMARY, "›");
        close = new Button(draw, fonts, Icons.Icon.CLOSE);
        prev.setOnTap(new Runnable() {
            @Override
            public void run() {
                turn(-1);
            }
        });
        next.setOnTap(new Runnable() {
            @Override
            public void run() {
                if (page >= pages.size() - 1) close();
                else turn(1);
            }
        });
        close.setOnTap(new Runnable() {
            @Override
            public void run() {
                close();
            }
        });
        controls = new Pressable[]{prev, next, close};
    }

    boolean isOpen() {
        return open;
    }

    /** Opens {@code st} from the hole at HUD (holeX, holeY); {@code zh} picks the Chinese pages when there are some. */
    void open(CaveData.Story st, float holeX, float holeY, boolean zh) {
        open = true;
        t = 0f;
        hx = holeX;
        hy = holeY;
        w = Math.min(820f, Ui.worldW() - 160f);
        h = Math.min(560f, Ui.worldH() - 320f);
        x = hx < Ui.CX ? hx + 140f : hx - 140f - w;
        x = MathUtils.clamp(x, Ui.safeLeft() + 24f, Ui.safeRight() - w - 24f);
        y = MathUtils.clamp(hy - h * 0.5f, Ui.safeBottom() + 140f, Ui.safeTop() - h - 160f); // clear of the HUD rows
        head = "STORY " + st.number + " · " + st.minutes + " MIN · 剧情";
        title = st.titleZh.isEmpty() || st.titleZh.equals(st.title) ? st.title : st.titleZh + "  " + st.title;
        String[] src = zh && st.pagesZh.length > 0 ? st.pagesZh : st.pages.length > 0 ? st.pages : st.pagesZh;
        pages.clear();
        for (String p : src) pages.add(wrap(p, 24f, w - 96f));
        if (pages.isEmpty()) pages.add(wrap("……", 24f, w - 96f));
        page = 0;
        pageT = -INK - SLIDE;
        layout();
    }

    void close() {
        if (!open) return;
        open = false;
        for (Pressable p : controls) p.cancel();
        if (onClose != null) onClose.run();
    }

    private void turn(int dir) {
        page = MathUtils.clamp(page + dir, 0, pages.size() - 1);
        pageT = 0f;
        layout();
    }

    private void layout() {
        float bs = 88f;
        close.setBounds(x + w - 24f - 64f, y + h - 24f - 64f, 64f, 64f);
        prev.setBounds(x + 32f, y + 28f, bs, 72f);
        next.setBounds(x + w - 32f - bs * 1.6f, y + 28f, bs * 1.6f, 72f);
        prev.setVisible(page > 0);
        next.setLabel(page >= pages.size() - 1 ? "DONE · 完成" : "NEXT ›");
    }

    /** Greedy wrap: CJK breaks anywhere, Latin at spaces. */
    private List<String> wrap(String text, float cap, float maxW) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            int i = 0;
            while (i < para.length()) {
                int j = i + 1;
                if (para.charAt(i) < 0x2E80 && para.charAt(i) != ' ') {
                    while (j < para.length() && para.charAt(j) != ' ' && para.charAt(j) < 0x2E80) j++;
                }
                String tok = para.substring(i, j);
                if (line.length() > 0 && r.uiWidth(line + tok, cap) > maxW) {
                    out.add(line.toString().trim());
                    line.setLength(0);
                    if (tok.equals(" ")) {
                        i = j;
                        continue;
                    }
                }
                line.append(tok);
                i = j;
            }
            out.add(line.toString().trim());
        }
        return out;
    }

    void update(float dt) {
        if (!open) return;
        t += dt;
        pageT += dt;
        for (Pressable p : controls) p.update(dt);
    }

    boolean touchDown(float px, float py, int ptr) {
        if (!open) return false;
        if (t < INK) return true;
        for (Pressable p : controls) if (p.touchDown(px, py, ptr)) return true;
        if (px < x || px > x + w || py < y || py > y + h) close();
        return true;
    }

    boolean touchDragged(float px, float py, int ptr) {
        boolean own = false;
        for (Pressable p : controls) own |= p.touchDragged(px, py, ptr);
        return own || open;
    }

    boolean touchUp(float px, float py, int ptr) {
        boolean own = false;
        for (Pressable p : controls) own |= p.touchUp(px, py, ptr);
        return own || open;
    }

    void cancel() {
        for (Pressable p : controls) p.cancel();
    }

    /** Ink bloom, card, then text (screen projection; the batch is drawing). */
    void draw(Batch b) {
        if (!open) return;
        float ink = Anim.clamp01(t / INK);
        float e = Anim.POW3_OUT.apply(ink);
        // ink spreads from the hole toward the card, cyan threads reach out
        float tx = x + w * 0.5f, ty = y + h * 0.5f;
        for (int i = 0; i < 9; i++) {
            float k = i / 8f;
            float px = hx + (tx - hx) * k * e, py = hy + (ty - hy) * k * e;
            float rr = (60f + 260f * k) * e * (0.8f + 0.4f * Anim.hash01(i, 3));
            draw.disc(px, py, rr, Theme.ABYSS_BLACK, 0.55f);
        }
        for (int i = 0; i < 7; i++) {
            float ang = MathUtils.atan2(ty - hy, tx - hx) + (Anim.hash01(i, 5) - 0.5f) * 1.6f;
            float len = (200f + 420f * Anim.hash01(i, 7)) * e;
            float ex = hx + len * MathUtils.cos(ang), ey = hy + len * MathUtils.sin(ang);
            draw.line(hx, hy, ex, ey, 2f, Theme.GROUND_SIG, 0.6f * (1f - 0.5f * ink));
            draw.glowDot(ex, ey, 40f, Theme.GROUND_SIG, 0.6f);
        }
        float s = Anim.clamp01((t - INK) / SLIDE);
        if (s <= 0f) return;
        float a = Anim.POW2_OUT.apply(s);
        float cx = x - 40f * (1f - a);
        draw.panel(cx, y, w, h, 22f, 0.97f * a);
        draw.rect(cx + 28f, y + h - 120f, 4f, 72f, Theme.GROUND_SIG, a);
        close.draw(b, a);
        prev.draw(b, a);
        next.draw(b, a);
        r.ui(b, head, 18f, cx + 48f, y + h - 48f, Align.left | Align.top, Theme.GROUND_SIG, a);
        r.boldFit(b, title, 32f, cx + 48f, y + h - 80f, Align.left | Align.top, w - 160f, 0.7f, Theme.INK1, a);
        // page text writes in
        List<String> lines = pages.get(page);
        int budget = pageT <= 0f ? 0 : (int) (pageT * CPS);
        float ly = y + h - 150f;
        for (int i = 0; i < lines.size() && ly > y + 120f; i++) {
            String ln = lines.get(i);
            int n = Math.min(ln.length(), budget);
            budget -= n;
            if (n <= 0) break;
            part.setLength(0);
            part.append(ln, 0, n);
            r.ui(b, part, 24f, cx + 48f, ly, Align.left | Align.top, Theme.INK1, a);
            ly -= 40f;
        }
        part.setLength(0);
        part.append(page + 1).append(" / ").append(pages.size());
        r.ui(b, part, 18f, cx + w * 0.5f, y + 64f, Align.center | Fonts.VCENTER, Theme.INK2, a);
    }
}
