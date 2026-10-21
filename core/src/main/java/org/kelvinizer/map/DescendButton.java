package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Pressable;
import org.kelvinizer.ui.Theme;

/**
 * 歌曲卡片上的 DESCEND 按钮：大字标签带轻微的故障闪烁，右边的双箭头不断下沉，外圈光晕随心跳呼吸。
 * 松手开始下潜；AUTO 打开时显示 WATCH，在 LAUNCH POINT 上显示 DIVE IN。没买的单曲变成金色的「按住购买」，按住 0.6 秒买下。
 *
 * <p>The DESCEND button (DESIGN §4.7): a primary button whose label is DISPLAY 40 with an ambient
 * G1 shimmer, with a procedural double chevron sinking to its right (900 ms loop) and an outer
 * glow in the card accent breathing α .20 ↔ .35 on the 2.4 s heartbeat.
 *
 * <p>Press: scale .96, y −4 and a "pressure" gradient rising inside the button over 250 ms.
 * Release fires the Descent; the button flashes and its label bursts G2. With AUTO on it reads
 * {@code WATCH} tinted {@code auto}; on the LAUNCH POINT it reads {@code DIVE IN}. Drawn in two
 * phases (atlas, DISPLAY raster). No allocation per frame.
 *
 * <p>Locked (a locked song opened on ALL SONGS, Kelvin 2026-10-16): a dark plate with a gold
 * padlock and the unlock condition in place of the label (drawn in the card's UI_BOLD phase);
 * a tap only shakes it.
 *
 * <p>For sale (a single not bought yet, Kelvin 2026-10-17): a gold plate {@code HOLD TO BUY 🐚 300};
 * holding it {@link #HOLD_T} fills it from left to right and then buys the song (no dialog). A
 * release before that buys nothing and never dives. Without enough shells the plate is grey,
 * reads {@code NEED 🐚 120 MORE} and a press only shakes it.
 */
final class DescendButton extends Pressable {
    private static final float PRESSURE_T = 0.25f, FLASH_T = 0.3f, CHEVRON_LOOP = 0.9f;
    /** Hold-to-buy length (s). */
    static final float HOLD_T = 0.6f;

    private final MapRenderer r;
    /** Label glitch: ambient G1, G2 on release. */
    final GlitchState label = new GlitchState().ambient(GlitchText.Level.G1);
    private final Color accent = new Color(Theme.GROUND_SIG);
    private final Color c1 = new Color(), c2 = new Color(), c3 = new Color();
    private CharSequence text = "DESCEND";
    private float textW = -1f;
    private boolean auto, locked;
    /** Locked: the condition shown and its alpha (the card cycles them). */
    private CharSequence lockText = "";
    private float lockA = 1f;
    private float pressure, flash, autoK;
    /** For sale: whether the diver can pay, the text before and after the shell icon, the fill. */
    private boolean buy, afford, bought;
    private String buyPre = "", buyPost = "";
    private int buyPrice = -1;
    private long buyShort = -1;
    private boolean buyZh;
    private float hold;
    private Runnable onBuy;
    /** Heartbeat phase 0..1 (set by the card each frame). */
    float beat;
    /** The card scale (DESIGN §0.3), set by the card every frame; bounds are already scaled. */
    float k = 1f;

    DescendButton(Draw draw, MapRenderer r) {
        super(draw);
        this.r = r;
    }

    /** Sets the label ({@code DESCEND}, {@code WATCH} or {@code DIVE IN}). */
    void setText(CharSequence t) {
        if (t != text) {
            text = t;
            textW = -1f;
        }
    }

    /** Sets the (already animated) card accent the glow and pressure use. */
    void setAccent(Color c) {
        accent.set(c);
    }

    /** AUTO on: the button is tinted {@code auto}. */
    void setAuto(boolean on) {
        auto = on;
    }

    /** Locked look with condition {@code text} at alpha {@code a} (false: the normal button). */
    void setLocked(boolean on, CharSequence text, float a) {
        locked = on;
        lockText = text;
        lockA = a;
    }

    boolean isLocked() {
        return locked;
    }

    /**
     * For-sale look: {@code HOLD TO BUY 🐚 price}, or {@code NEED 🐚 n MORE} when {@code shells}
     * is short (false: the normal button). Rebuilds its text only when the numbers change.
     */
    void setBuy(boolean on, int price, long shells, boolean zh) {
        buy = on;
        if (!on) return;
        long lack = Math.max(0L, price - shells);
        afford = lack == 0L;
        if (price == buyPrice && lack == buyShort && zh == buyZh) return;
        buyPrice = price;
        buyShort = lack;
        buyZh = zh;
        if (afford) {
            buyPre = zh ? "按住购买" : "HOLD TO BUY";
            buyPost = Integer.toString(price);
        } else {
            buyPre = zh ? "还差" : "NEED";
            buyPost = zh ? Long.toString(lack) : lack + " MORE";
        }
    }

    /** True while the button sells the song (a release then never dives). */
    boolean isBuying() {
        return buy || bought;
    }

    /** Runs when a hold-to-buy completes. */
    void setOnBuy(Runnable r) {
        onBuy = r;
    }

    @Override
    protected boolean firesOnRelease() {
        // holding buys; the release after a purchase must not dive either
        return !buy && !bought;
    }

    @Override
    protected void onPress(float px, float py) {
        if (buy && !afford) {
            shake();
            return;
        }
        if (!locked) scale.setTarget(0.96f);
    }

    @Override
    protected void onFire() {
        if (locked) {
            shake();
            return;
        }
        flash = 1f;
        label.trigger(GlitchText.Level.G2, r.time);
    }

    /** Width of the padlock and the condition (cap 24, at most the button minus its padding). */
    private float lockRowW(float s) {
        return Math.min(44f * k * s + r.boldWidth(lockText, lockCap(s)), (w - 40f * k) * s);
    }

    private float lockCap(float s) {
        return MapUi.cap(24f, k) * s;
    }

    @Override
    protected void onUpdate(float dt) {
        if (buy && afford && isPressed() && !bought) {
            hold = Math.min(1f, hold + dt / HOLD_T);
            if (hold >= 1f) {
                bought = true;
                flash = 1f;
                if (onBuy != null) onBuy.run();
            }
        } else if (!isPressed()) {
            hold = approach(hold, 0f, dt / Anim.MICRO);
            bought = false;
        }
        pressure = approach(pressure, isPressed() && !buy ? 1f : 0f, dt / (isPressed() ? PRESSURE_T : Anim.MICRO));
        flash = Math.max(0f, flash - dt / FLASH_T);
        autoK = approach(autoK, auto ? 1f : 0f, dt / Anim.SHORT);
    }

    /** Label width at the scaled cap ({@code DISPLAY 40}). */
    private float labelWidth() {
        if (textW < 0f) {
            textW = r.fonts().width(Fonts.Role.DISPLAY, MapRenderer.R_DISPLAY, text) / MapRenderer.R_DISPLAY;
        }
        return textW * labelCap();
    }

    private float labelCap() {
        return MapUi.cap(40f, k);
    }

    private float labelX(float cx, float s) {
        return cx - 30f * k * s;
    }

    @Override
    public void draw(Batch b, float alpha) {
        if (!visible || alpha <= 0.002f) return;
        float a = alpha * alphaMul();
        float s = visualScale();
        float cx = centerX() + shakeX(), cy = centerY() + liftY() - 4f * k * press;
        float bw = w * s, bh = h * s, bx = cx - bw * 0.5f, by = cy - bh * 0.5f;
        float rad = Button.RADIUS * k * s;
        if (buy) {
            if (afford) {
                Theme.mix(Theme.GOLD, Theme.ABYSS_BLACK, 0.25f, c1);
                draw.roundRectGradient(bx, by, bw, bh, rad, c1, Theme.GOLD, a);
                if (hold > 0f) draw.roundRect(bx, by, Math.max(2f * rad, bw * hold), bh, rad, Theme.WHITE, 0.35f * a);
            } else {
                draw.roundRect(bx, by, bw, bh, rad, Theme.PANEL_HI, a);
                draw.roundRectStroke(bx, by, bw, bh, rad, 2f, Theme.STROKE, a);
            }
            if (flash > 0f) draw.roundRect(bx, by, bw, bh, rad, Theme.WHITE, 0.5f * flash * a);
            float cap = lockCap(s), gap = 10f * k * s, icon = 30f * k * s;
            float w1 = r.boldWidth(buyPre, cap), w2 = r.boldWidth(buyPost, cap);
            float ix = cx - (w1 + w2 + icon + 2f * gap) * 0.5f + w1 + gap + icon * 0.5f;
            draw.shell(ix, cy, icon, afford ? Theme.ABYSS_BLACK : Theme.INK3, afford ? Theme.GOLD : Theme.PANEL_HI, a);
            return;
        }
        if (locked) {
            draw.roundRect(bx, by, bw, bh, rad, Theme.PANEL_HI, a);
            draw.roundRectStroke(bx, by, bw, bh, rad, 2f, Theme.STROKE, a);
            float lx = cx - lockRowW(s) * 0.5f + 14f * k * s;
            Icons.draw(draw.shapes(), Icons.Icon.LOCK, lx, cy, 30f * k * s, Theme.GOLD, lockA * a);
            return;
        }
        // breathing outer glow on the heartbeat (strongest on the beat)
        float pulse = (1f - beat) * (1f - beat);
        Theme.mix(accent, Theme.AUTO, autoK, c3);
        draw.sprite(draw.softDot(), cx, cy - 6f * k, bw + 150f * k, bh + 130f * k, c3, (0.20f + 0.15f * pulse) * a);
        float k = brightness();
        Theme.brighten(Theme.BTN_TOP, k, c1);
        Theme.brighten(Theme.BTN_BOT, k, c2);
        if (autoK > 0f) {
            Theme.mix(c1, Theme.AUTO, 0.55f * autoK, c1);
            Theme.mix(c2, Theme.AUTO, 0.75f * autoK, c2);
        }
        draw.roundRectGradient(bx, by, bw, bh, rad, c2, c1, a);
        if (pressure > 0f) {
            c2.set(c3);
            c1.set(c3);
            c1.a = 0f;
            float ph = Math.max(2f * rad, bh * Anim.POW2_OUT.apply(pressure));
            draw.roundRectGradient(bx, by, bw, ph, rad, c2, c1, 0.55f * a * Math.min(1f, pressure * 3f));
        }
        draw.rect(bx + rad, by + bh - 3f * k * s, bw - 2f * rad, 1.5f * k * s, Theme.WHITE, 0.35f * a);
        ripple.color(Theme.WHITE);
        drawRipple(a);
        if (flash > 0f) draw.roundRect(bx, by, bw, bh, rad, Theme.WHITE, 0.5f * flash * a);
        // double chevron sinking in a 900 ms loop
        float p = r.reduceMotion ? 0.35f : (r.time % CHEVRON_LOOP) / CHEVRON_LOOP;
        float fade = MathUtils.clamp(Math.min(p / 0.25f, (1f - p) / 0.25f), 0f, 1f);
        float lw = Math.min(labelWidth(), (w - 150f * k)) * s;
        float chx = labelX(cx, s) + lw * 0.5f + 46f * k * s;
        Icons.draw(draw.shapes(), Icons.Icon.CHEVRONS_DOWN, chx, cy + (8f - 16f * p) * k * s, 44f * k * s, labelColor(),
            (0.35f + 0.65f * fade) * a);
    }

    private Color labelColor() {
        return autoK > 0.5f ? Theme.INK1 : Theme.BTN_LABEL;
    }

    /** UI_BOLD phase (locked or for sale): the unlock condition right of the padlock, or the price row. */
    void drawLock(Batch b, float alpha) {
        if (!visible || alpha <= 0.002f) return;
        if (buy) {
            float s = visualScale();
            float cx = centerX() + shakeX(), cy = centerY() + liftY() - 4f * k * press;
            float cap = lockCap(s), gap = 10f * k * s, icon = 30f * k * s;
            float w1 = r.boldWidth(buyPre, cap), w2 = r.boldWidth(buyPost, cap);
            float x0 = cx - (w1 + w2 + icon + 2f * gap) * 0.5f;
            Color tc = afford ? Theme.ABYSS_BLACK : Theme.INK2;
            float a = alpha * alphaMul();
            r.boldFit(b, buyPre, cap, x0, cy, Align.left | Fonts.VCENTER, w1 + 1f, 0.7f, tc, a);
            r.boldFit(b, buyPost, cap, x0 + w1 + 2f * gap + icon, cy, Align.left | Fonts.VCENTER, w2 + 1f, 0.7f, tc, a);
            return;
        }
        if (!locked) return;
        float s = visualScale();
        float cx = centerX() + shakeX(), cy = centerY() + liftY();
        float rw = lockRowW(s), x0 = cx - rw * 0.5f + 44f * k * s;
        r.boldFit(b, lockText, lockCap(s), x0, cy, Align.left | Fonts.VCENTER, rw - 44f * k * s, 0.7f, Theme.INK1,
            lockA * alpha * alphaMul());
    }

    /** DISPLAY phase: the label with its G1 shimmer (G2 after release). */
    void drawLabel(Batch b, float alpha) {
        if (!visible || locked || buy || alpha <= 0.002f) return;
        float a = alpha * alphaMul();
        float s = visualScale();
        float cx = centerX() + shakeX(), cy = centerY() + liftY() - 4f * k * press;
        float cap = labelCap() * s;
        float maxW = (w - 150f * k) * s;
        float lw = labelWidth() * s;
        if (lw > maxW) cap *= maxW / lw;
        r.glitch(b, Fonts.Role.DISPLAY, MapRenderer.R_DISPLAY, text, cap, labelX(cx, s), cy,
            Align.center | Fonts.VCENTER, labelColor(), a, label);
    }
}
