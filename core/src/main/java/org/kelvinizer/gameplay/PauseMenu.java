package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Pressable;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;
import space.earlygrey.shapedrawer.ShapeDrawer;

/**
 * 暂停菜单（方案 B）：没有面板，场地变暗，三个大按钮横排——左「重开」、中「继续」（最大、青色实心）、右「退出」。
 * 每个按钮图标在上，名字在下：英文模式只写英文名，中文模式只写中文名；四角的 HUD 照常亮着。
 *
 * <p>Pause menu, variant B (Kelvin 2026-10-12 00:58 「然后用方案B」 and 23:35 「暂停菜单东西太多了。
 * 简洁一点」; mocks P2 / P4 / P5): no panel, the field dims behind a {@code scrim @ .70}
 * (DESIGN §5.12) and three big buttons sit in a row at the centre: RETRY 重开 (left), RESUME 继续
 * (centre, largest, filled cyan like a primary button) and QUIT 退出 (right). Each button has an
 * icon above its label (UI_BOLD 26, RESUME 30): the English name in English, the Chinese name in
 * 中文, never both (Kelvin 2026-10-18 17:22 「绿框框起来的语言还是分开吧」; until then the English
 * label had a Chinese sublabel (18) under it).
 * The corner HUD stays lit: the screen draws it after the scrim. Offsets and volumes live in
 * Settings.
 *
 * <p><b>Size.</b> Laid out in world units at the HUD scale {@code k = uiScale} (DESIGN §0.3), shrunk
 * only if the row would leave the safe area; every hit rect is far above the §0 minimum target
 * ({@link Ui#touchTarget()}). The row opens in 200 ms ({@code pow3Out}, fade and scale .96 → 1)
 * and ignores touches for its first 80 ms (the finger that paused). Every button acts on release
 * (the §1.5 press primitives of {@link Pressable}). BACK / Esc resume; the screen handles them.
 *
 * <p><b>Drawing</b> is split so the screen can draw the corner HUD between the scrim and the
 * buttons and record all text in one pass: {@link #drawScrim}, {@link #drawShapes} (atlas), then
 * {@link #drawText} into a {@link DeferredBatch}. The labels share one UI_BOLD raster and the
 * sublabels the CJK page, so the open menu adds two text draw calls. Nothing is allocated per frame.
 */
final class PauseMenu {
    /** RETRY / QUIT button width at HUD scale 1. */
    static final float SIDE_W = 220f;
    /** RETRY / QUIT button height at HUD scale 1. */
    static final float SIDE_H = 220f;
    /** RESUME button width at HUD scale 1. */
    static final float MAIN_W = 300f;
    /** RESUME button height at HUD scale 1. */
    static final float MAIN_H = 258f;
    /** Gap between the buttons at HUD scale 1. */
    static final float GAP = 44f;
    /** Corner radius of the buttons at HUD scale 1. */
    static final float RADIUS = 18f;
    /** Icon height of RETRY / QUIT at HUD scale 1. */
    static final float ICON_SIDE = 48f;
    /** Icon height of RESUME at HUD scale 1. */
    static final float ICON_MAIN = 56f;
    /** Label cap of RETRY / QUIT at HUD scale 1 (DESIGN §1.3: buttons 26). */
    static final float LABEL_SIDE = 26f;
    /** Label cap of RESUME at HUD scale 1 (DESIGN §1.3: large primary 30). */
    static final float LABEL_MAIN = 30f;
    /** Chinese sublabel cap at HUD scale 1 (DESIGN §1.3: subtitles at least 18). */
    static final float SUB_CAP = 18f;
    /** Gap between the icon and the label at HUD scale 1. */
    static final float ICON_GAP = 32f;
    /** Gap between the label and the sublabel at HUD scale 1. */
    static final float SUB_GAP = 14f;
    /**
     * The brief asked for the Chinese sublabel on every button in English (DESIGN §1.3). true: drawn
     * under the English labels; false: never (Kelvin 2026-10-18 17:22: one language at a time). In 中文
     * the Chinese name is the label itself.
     */
    static final boolean SUBLABELS_ALWAYS = false;
    /** Margin kept between the row and the safe edges when the row would not fit at scale k. */
    private static final float FIT_MARGIN = 16f;
    private static final float OPEN_SEC = 0.20f;
    private static final float IGNORE_SEC = 0.08f;

    private static final int ICON_RETRY = 0;
    private static final int ICON_PLAY = 1;
    private static final int ICON_EXIT = 2;

    private final Draw draw;
    private final Fonts fonts;
    private final BigButton resume;
    private final BigButton retry;
    private final BigButton quit;

    private boolean open;
    private boolean zh;
    private float openT;
    /** Row scale without the open animation (the text rasters are generated for it). */
    private float rowK = 1f;

    /** Creates the menu once; the runnables are the RESUME / RETRY / QUIT actions. */
    PauseMenu(Main game, Runnable onResume, Runnable onRetry, Runnable onQuit) {
        this.draw = game.draw();
        this.fonts = game.fonts();
        resume = new BigButton(true, ICON_PLAY, "RESUME", "继续");
        retry = new BigButton(false, ICON_RETRY, "RETRY", "重开");
        quit = new BigButton(false, ICON_EXIT, "QUIT", "退出");
        resume.setOnTap(onResume);
        retry.setOnTap(onRetry);
        quit.setOnTap(onQuit);
        layout();
    }

    /** True while the menu is open. */
    boolean isOpen() {
        return open;
    }

    /**
     * Opens the menu.
     *
     * @param zhOn Language = 中文 (the Chinese names become the labels)
     */
    void open(boolean zhOn) {
        open = true;
        zh = zhOn;
        openT = 0f;
        cancel();
        layout();
    }

    /** Closes the menu (no animation; the countdown takes over). */
    void close() {
        open = false;
        cancel();
    }

    /** Cancels every press in progress. */
    void cancel() {
        resume.cancel();
        retry.cancel();
        quit.cancel();
    }

    /** Generates the label raster and the sublabel glyphs now (load time), so opening never hitches. */
    void prewarm() {
        float k = fitScale();
        fonts.prewarm(Fonts.Role.UI_BOLD, LABEL_MAIN * k, Fonts.GlyphSet.FULL);
        fonts.width(Fonts.Role.UI, SUB_CAP * k, "重开继续退出");
        fonts.width(Fonts.Role.UI_BOLD, LABEL_MAIN * k, "重开继续退出"); // the labels in 中文
    }

    // ------------------------------------------------------------------ layout

    /** Row scale: the HUD scale, reduced only when the row would leave the safe area. */
    private static float fitScale() {
        float rowW = 2f * SIDE_W + MAIN_W + 2f * GAP;
        float halfW = Math.min(Ui.safeRight() - Ui.CX, Ui.CX - Ui.safeLeft()) - FIT_MARGIN;
        float halfH = Math.min(Ui.safeTop() - Ui.CY, Ui.CY - Ui.safeBottom()) - FIT_MARGIN;
        float lim = Math.min(halfW / (rowW * 0.5f), halfH / (MAIN_H * 0.5f));
        return Math.min(MathUtils.clamp(Ui.uiScale(), 0.9f, 1.3f), lim);
    }

    private void layout() {
        rowK = fitScale();
        float s = 0.96f + 0.04f * alpha();
        float k = rowK * s;
        float mw = MAIN_W * k;
        float sw = SIDE_W * k;
        float off = (mw + sw) * 0.5f + GAP * k;
        resume.place(Ui.CX, Ui.CY, mw, MAIN_H * k, k);
        retry.place(Ui.CX - off, Ui.CY, sw, SIDE_H * k, k);
        quit.place(Ui.CX + off, Ui.CY, sw, SIDE_H * k, k);
    }

    // ------------------------------------------------------------------ update / draw

    /** Advances the open animation and the buttons. */
    void update(float dt) {
        if (!open) return;
        openT += dt;
        layout();
        resume.update(dt);
        retry.update(dt);
        quit.update(dt);
    }

    /** Open progress 0..1 ({@code pow3Out} over 200 ms): the alpha of everything. */
    private float alpha() {
        return Anim.POW3_OUT.apply(MathUtils.clamp(openT / OPEN_SEC, 0f, 1f));
    }

    /** The scrim over the whole visible world (atlas; drawn before the corner HUD). */
    void drawScrim(Batch b) {
        if (!open) return;
        draw.rect(Ui.left(), Ui.bottom(), Ui.worldW(), Ui.worldH(), Theme.SCRIM_PAUSE, alpha());
    }

    /** Button plates, ripples and icons (atlas). */
    void drawShapes(Batch b) {
        if (!open) return;
        float a = alpha();
        retry.drawBody(a);
        resume.drawBody(a);
        quit.drawBody(a);
    }

    /** Labels and sublabels into the screen's recording batch (the caller replays it). */
    void drawText(Batch text) {
        if (!open) return;
        float a = alpha();
        retry.drawText(text, a);
        resume.drawText(text, a);
        quit.drawText(text, a);
    }

    private boolean sublabels() {
        return SUBLABELS_ALWAYS && !zh;
    }

    // ------------------------------------------------------------------ input

    /** Touch down while open (the buttons act on release). */
    boolean touchDown(float x, float y, int pointer) {
        if (!open) return false;
        if (openT < IGNORE_SEC) return true; // the finger that opened the menu
        if (resume.touchDown(x, y, pointer) || retry.touchDown(x, y, pointer)) return true;
        quit.touchDown(x, y, pointer);
        return true;
    }

    /** Drag while open. */
    void touchDragged(float x, float y, int pointer) {
        if (!open) return;
        resume.touchDragged(x, y, pointer);
        retry.touchDragged(x, y, pointer);
        quit.touchDragged(x, y, pointer);
    }

    /** Touch up while open (fires the button released over). */
    void touchUp(float x, float y, int pointer) {
        if (!open) return;
        // each action may close the menu or switch screens: stop at the first one that took the pointer
        if (!resume.touchUp(x, y, pointer) && !retry.touchUp(x, y, pointer)) quit.touchUp(x, y, pointer);
    }

    /** RESUME (the tests tap it; BACK while paused is handled by the screen). */
    Pressable resumeButton() {
        return resume;
    }

    // ------------------------------------------------------------------ button

    /**
     * One big button: plate (primary: cyan gradient with a soft glow; secondary: panel fill with a
     * 2-unit {@code ink1 @ .6} outline), icon, label and (English only) Chinese sublabel, centred as one
     * stack. Uses the {@link Pressable} press scale, brightness and ripple.
     */
    private final class BigButton extends Pressable {
        private final boolean primary;
        private final int icon;
        private final String label;
        private final String sub;
        private final Color c1 = new Color();
        private final Color c2 = new Color();
        /** Layout scale of this frame (row scale × open animation). */
        private float k = 1f;

        BigButton(boolean primary, int icon, String label, String sub) {
            super(PauseMenu.this.draw);
            this.primary = primary;
            this.icon = icon;
            this.label = label;
            this.sub = sub;
        }

        void place(float cx, float cy, float bw, float bh, float scale) {
            setBounds(cx - bw * 0.5f, cy - bh * 0.5f, bw, bh);
            k = scale;
        }

        private float iconH() {
            return (primary ? ICON_MAIN : ICON_SIDE) * k;
        }

        private float labelCap() {
            return (primary ? LABEL_MAIN : LABEL_SIDE) * k;
        }

        /** Top of the icon / label / sublabel stack centred on the button, at press scale {@code s}. */
        private float stackTop(float s) {
            float total = iconH() + ICON_GAP * k + labelCap() + (sublabels() ? (SUB_GAP + SUB_CAP) * k : 0f);
            return centerY() + liftY() + total * s * 0.5f;
        }

        void drawBody(float alpha) {
            if (!visible) return;
            float a = alpha * alphaMul();
            if (a <= 0.002f) return;
            float s = visualScale();
            float cx = centerX() + shakeX();
            float cy = centerY() + liftY();
            float bw = w * s;
            float bh = h * s;
            float bx = cx - bw * 0.5f;
            float by = cy - bh * 0.5f;
            float rad = RADIUS * k * s;
            float br = brightness();
            Color ink;
            if (primary) {
                draw.sprite(draw.softDot(), cx, cy - 6f * k, bw + 120f * k, bh + 110f * k, Theme.BTN_TOP,
                    0.25f * a * (0.9f + 0.1f * br));
                Theme.brighten(Theme.BTN_TOP, br, c1);
                Theme.brighten(Theme.BTN_BOT, br, c2);
                draw.roundRectGradient(bx, by, bw, bh, rad, c2, c1, a);
                draw.rect(bx + rad, by + bh - 3f * s, bw - 2f * rad, 1.5f * s, Theme.WHITE, 0.35f * a);
                ripple.color(Theme.WHITE);
                ink = Theme.BTN_LABEL;
            } else {
                draw.roundRect(bx, by, bw, bh, rad, Theme.PANEL, 0.92f * a);
                if (press > 0f || hover > 0f) {
                    draw.roundRect(bx, by, bw, bh, rad, Theme.INK1, (0.08f * press + 0.05f * hover) * a);
                }
                bright(Theme.INK1, c1);
                draw.roundRectStroke(bx, by, bw, bh, rad, 2f, c1, Math.min(1f, 0.6f * br) * a);
                ripple.color(Theme.INK1);
                ink = Theme.INK1;
            }
            drawRipple(a);
            float ih = iconH() * s;
            drawIcon(icon, cx, stackTop(s) - ih * 0.5f, ih, ink, a);
        }

        void drawText(Batch t, float alpha) {
            if (!visible) return;
            float a = alpha * alphaMul();
            if (a <= 0.002f) return;
            float s = visualScale();
            float cx = centerX() + shakeX();
            float top = stackTop(s) - (iconH() + ICON_GAP * k) * s;
            float cap = labelCap() * s;
            Color ink = primary ? Theme.BTN_LABEL : Theme.INK1;
            // every label from the RESUME label's raster (one UI_BOLD page for the three)
            fonts.pushPixelScale(Ui.pxPerWorld() * LABEL_MAIN * rowK / cap);
            fonts.draw(t, Fonts.Role.UI_BOLD, cap, zh ? sub : label, cx, top, Align.center | Align.top, ink, a);
            fonts.popPixelScale();
            if (!sublabels()) return;
            float sc = SUB_CAP * k * s;
            fonts.pushPixelScale(Ui.pxPerWorld() * SUB_CAP * rowK / sc);
            fonts.draw(t, Fonts.Role.UI, sc, sub, cx, top - cap - SUB_GAP * k * s, Align.center | Align.top,
                primary ? Theme.BTN_LABEL : Theme.INK2, (primary ? 0.8f : 1f) * a);
            fonts.popPixelScale();
        }

        @Override
        public void draw(Batch b, float alpha) {
            drawBody(alpha);
            drawText(b, alpha);
        }
    }

    /**
     * Draws a menu icon {@code h} tall centred at (cx, cy) with 5-unit strokes on the 64-unit icon
     * grid (DESIGN §1.6): the retry arc, a play triangle, or an exit mark (open bracket and arrow).
     */
    private void drawIcon(int kind, float cx, float cy, float h, Color c, float a) {
        ShapeDrawer sd = draw.shapes();
        if (kind == ICON_RETRY) {
            // the retry glyph is about 44 units tall on its 64 grid
            Icons.draw(sd, Icons.Icon.RETRY, cx, cy, h * 64f / 44f, c, a);
            return;
        }
        float old = sd.getPackedColor();
        sd.setColor(c.r, c.g, c.b, c.a * a);
        if (kind == ICON_PLAY) {
            float w = h * 0.84f;
            float x0 = cx - w * 0.42f; // optical centre: nearer the flat side
            sd.filledTriangle(x0, cy - h * 0.5f, x0, cy + h * 0.5f, x0 + w, cy);
        } else {
            float g = h / 44f; // the mark is 44 units tall on the 64 grid
            float sw = 5f * g;
            float rc = sw * 0.5f;
            float l = cx - 18f * g;
            float bx = cx - 4f * g;
            float t = cy + 19.5f * g;
            float bt = cy - 19.5f * g;
            sd.line(l, t, bx, t, sw);
            sd.line(l, bt, bx, bt, sw);
            sd.line(l, t, l, bt, sw);
            sd.filledCircle(l, t, rc);
            sd.filledCircle(l, bt, rc);
            sd.filledCircle(bx, t, rc);
            sd.filledCircle(bx, bt, rc);
            float ax0 = cx - 6f * g;
            float ax1 = cx + 20f * g;
            float hx = ax1 - 10f * g;
            sd.line(ax0, cy, ax1, cy, sw);
            sd.line(hx, cy + 10f * g, ax1, cy, sw);
            sd.line(hx, cy - 10f * g, ax1, cy, sw);
            sd.filledCircle(ax0, cy, rc);
            sd.filledCircle(ax1, cy, rc);
            sd.filledCircle(hx, cy + 10f * g, rc);
            sd.filledCircle(hx, cy - 10f * g, rc);
        }
        sd.setColor(old);
    }
}
