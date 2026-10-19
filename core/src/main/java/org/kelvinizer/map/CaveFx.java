package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 洞穴地图换层的转场：X1 漩涡（航线洞口）、X3 深坠（章节竖井，中间闪出层名）、X4 气泡上浮（上浮洞、捏合、上浮板）。
 * 前半段在旧的一层上播放，盖住时调用换层回调，后半段在新的一层上播放（泡沫环、落地冲击波和泥沙、水花环）。
 *
 * <p>Level transitions of the cave map (preview v17 X1, X3, X4), drawn in screen space over the
 * map: the out half plays on the old level, then the swap callback builds the new one, then the
 * in half plays on it from the arrival point ({@link #arrive}). X1 whirlpool: water swirls and
 * bubbles spiral into the hole, a foam ring opens on the new level. X3 deep fall: the swirl, the
 * water darkens, bubbles rise, the layer name flashes, then a landing shockwave and silt. X4
 * bubble rise: a bubble column and god rays carry the view up, a splash ring opens at the shaft
 * mouth. Particles are hashed per index (no allocation per frame).
 */
final class CaveFx {
    static final int NONE = 0, WHIRL = 1, FALL = 2, RISE = 3;
    private static final int OUT = 0, HOLD = 1, IN = 2;
    private static final int BUBBLES = 40;

    private final Draw draw;
    private final MapRenderer r;
    private int kind = NONE, phase;
    private float t, fx, fy, ax = Ui.CX, ay = Ui.CY;
    private String title = "", sub = "";
    private Runnable swap;
    private final Color c1 = new Color();

    CaveFx(Draw draw, MapRenderer r) {
        this.draw = draw;
        this.r = r;
    }

    /** True while a transition runs (input is locked). */
    boolean busy() {
        return kind != NONE;
    }

    /** True while the out half runs (the camera may dive into the hole). */
    boolean leaving() {
        return kind != NONE && phase == OUT;
    }

    /** Out-half duration of {@code k} (s). */
    static float outDuration(int k) {
        return k == FALL ? 0.75f : k == RISE ? 0.6f : 0.55f;
    }

    private float hold() {
        return kind == FALL ? 0.75f : 0f;
    }

    private float in() {
        return kind == FALL ? 0.8f : kind == RISE ? 0.6f : 0.65f;
    }

    /**
     * Starts transition {@code k} from HUD point (hx, hy); {@code onSwap} builds the new level at
     * the cover and must call {@link #arrive}. {@code name} / {@code line} are the X3 layer flash.
     */
    void start(int k, float hx, float hy, String name, String line, Runnable onSwap) {
        kind = k;
        phase = OUT;
        t = 0f;
        fx = hx;
        fy = hy;
        ax = Ui.CX;
        ay = Ui.CY;
        title = name != null ? name : "";
        sub = line != null ? line : "";
        swap = onSwap;
    }

    /** The arrival point of the in half (HUD units). */
    void arrive(float hx, float hy) {
        ax = hx;
        ay = hy;
    }

    /** Cover amount 0..1 the screen uses to fade its own sounds or HUD. */
    float cover() {
        if (kind == NONE) return 0f;
        if (phase == OUT) return Anim.clamp01(t / outDuration(kind));
        if (phase == HOLD) return 1f;
        return 1f - Anim.clamp01(t / in());
    }

    void update(float dt) {
        if (kind == NONE) return;
        t += dt;
        if (phase == OUT && t >= outDuration(kind)) {
            phase = hold() > 0f ? HOLD : IN;
            t = 0f;
            Runnable s = swap;
            swap = null;
            if (s != null) s.run();
        } else if (phase == HOLD && t >= hold()) {
            phase = IN;
            t = 0f;
        } else if (phase == IN && t >= in()) {
            kind = NONE;
        }
        r.beaconBoost = kind == FALL && phase == OUT ? 1f + 5f * Anim.clamp01(t / outDuration(FALL)) : 1f;
    }

    /** Screen atlas and text (screen projection, batch drawing). */
    void draw(Batch b) {
        if (kind == NONE) return;
        float l = Ui.left(), w = Ui.worldW(), bo = Ui.bottom(), h = Ui.worldH();
        float k = cover();
        if (kind == WHIRL || kind == FALL) {
            float dark = kind == FALL ? 0.94f * k : 0.55f * k;
            draw.rect(l, bo, w, h, Theme.ABYSS_BLACK, dark);
            if (phase == OUT) swirl(fx, fy, Anim.clamp01(t / outDuration(kind)));
            if (kind == FALL && phase != IN) risingBubbles(phase == HOLD ? 1f : k, phase == HOLD ? 1f + t : t);
            if (kind == FALL && phase == HOLD) flash(b, Anim.clamp01(t / hold()));
            if (phase == IN) {
                float p = Anim.clamp01(t / in());
                if (kind == WHIRL) foam(ax, ay, p);
                else landing(ax, ay, p);
            }
        } else {
            float lift = phase == OUT ? Anim.clamp01(t / outDuration(RISE)) : 1f - Anim.clamp01(t / in());
            draw.rect(l, bo, w, h, Theme.SURFACE_TOP, 0.45f * lift);
            for (int i = 0; i < 5; i++) {
                float x = l + w * (0.12f + 0.19f * i + 0.04f * MathUtils.sin(i * 2.1f + t));
                draw.sprite(draw.glowV(), x, bo + h * 0.7f, 140f, h * 1.2f, 8f - 4f * i, Theme.SKY_SIG, 0.18f * lift);
            }
            column(phase == OUT ? Anim.clamp01(t / outDuration(RISE)) : 1f, phase == OUT ? t : 0.6f + t, lift);
            if (phase == IN) splash(ax, ay, Anim.clamp01(t / in()));
        }
    }

    /** X1 / X3: three spiral arms and bubbles winding into the hole. */
    private void swirl(float cx, float cy, float p) {
        float a = Math.min(1f, p * 2.5f) * (1f - 0.3f * p);
        for (int arm = 0; arm < 3; arm++) {
            for (int j = 0; j < 14; j++) {
                float d = 520f * (1f - j / 14f) * (1f - 0.6f * p);
                float ang = arm * MathUtils.PI2 / 3f + j * 0.42f + p * 9f;
                draw.disc(cx + d * MathUtils.cos(ang), cy + d * MathUtils.sin(ang), 5f + 0.4f * (14 - j), Theme.GROUND_SIG,
                    0.35f * a * (1f - j / 16f));
            }
        }
        for (int i = 0; i < BUBBLES; i++) {
            float h1 = Anim.hash01(i, 3), h2 = Anim.hash01(i, 5);
            float q = (p * (1.2f + h2) + h1) % 1f;
            float d = 700f * (1f - q) + 10f;
            float ang = h1 * MathUtils.PI2 + q * 6f;
            draw.circle(cx + d * MathUtils.cos(ang), cy + d * MathUtils.sin(ang), 4f + 8f * h2 * (1f - q), 2f, Theme.SKY_SIG,
                0.7f * a * q);
        }
        draw.glowDot(cx, cy, 300f + 400f * p, Theme.ABYSS_BLACK, 0.7f * p);
    }

    /** X3: bubbles rise past the camera as it falls. */
    private void risingBubbles(float a, float time) {
        float l = Ui.left(), w = Ui.worldW(), bo = Ui.bottom(), h = Ui.worldH();
        for (int i = 0; i < BUBBLES; i++) {
            float h1 = Anim.hash01(i, 7), h2 = Anim.hash01(i, 9);
            float y = bo + ((h2 + time * (0.8f + h1)) % 1f) * (h + 80f) - 40f;
            float x = l + w * h1 + 12f * MathUtils.sin(time * 4f + i);
            draw.circle(x, y, 3f + 7f * h2, 2f, Theme.SKY_SIG, 0.55f * a);
        }
    }

    /** X3: the layer name flashes in the dark. */
    private void flash(Batch b, float p) {
        float a = Math.min(1f, p * 4f) * (1f - Anim.clamp01((p - 0.75f) / 0.25f));
        r.bold(b, title, 64f, Ui.CX, Ui.CY + 30f, Align.center | Fonts.VCENTER, Theme.INK1, a);
        r.ui(b, sub, 26f, Ui.CX, Ui.CY - 40f, Align.center | Fonts.VCENTER, Theme.GROUND_SIG, a);
    }

    /** X1 in: a foam ring opens at the arrival point. */
    private void foam(float cx, float cy, float p) {
        float e = Anim.POW3_OUT.apply(p);
        float a = 1f - p;
        draw.circle(cx, cy, 60f + 520f * e, 10f * a + 2f, Theme.SKY_SIG, 0.8f * a);
        draw.circle(cx, cy, 40f + 360f * e, 4f, Theme.GROUND_SIG, 0.6f * a);
        for (int i = 0; i < 24; i++) {
            float ang = i * MathUtils.PI2 / 24f + Anim.hash01(i, 11);
            float d = 60f + 520f * e + 20f * Anim.hash01(i, 13);
            draw.disc(cx + d * MathUtils.cos(ang), cy + d * MathUtils.sin(ang), 5f * a + 1f, Theme.SKY_SIG, 0.7f * a);
        }
    }

    /** X3 in: landing shockwave and silt spreading along the floor. */
    private void landing(float cx, float cy, float p) {
        float e = Anim.POW3_OUT.apply(p);
        float a = 1f - p;
        draw.circle(cx, cy, 80f + 900f * e, 14f * a + 2f, Theme.GROUND_SIG, 0.75f * a);
        c1.set(0.62f, 0.55f, 0.42f, 1f);
        for (int i = 0; i < BUBBLES; i++) {
            float h1 = Anim.hash01(i, 15), h2 = Anim.hash01(i, 17);
            float dir = h1 < 0.5f ? -1f : 1f;
            float x = cx + dir * (40f + 700f * e * (0.4f + h2));
            float y = cy - 40f + 120f * h2 * e - 60f * e * e;
            draw.disc(x, y, 3f + 6f * h2, c1, 0.5f * a);
        }
    }

    /** X4: a column of bubbles carries the view up. */
    private void column(float p, float time, float a) {
        float bo = Ui.bottom(), h = Ui.worldH();
        for (int i = 0; i < BUBBLES; i++) {
            float h1 = Anim.hash01(i, 19), h2 = Anim.hash01(i, 21);
            float y = bo + ((h2 + time * (1.2f + h1)) % 1f) * (h + 100f) - 50f;
            float x = Ui.CX + (h1 - 0.5f) * 520f * (0.4f + p) + 18f * MathUtils.sin(time * 5f + i);
            draw.circle(x, y, 4f + 10f * h2, 2.5f, Theme.SKY_SIG, 0.7f * a);
        }
    }

    /** X4 in: a splash ring at the shaft mouth the view rose out of. */
    private void splash(float cx, float cy, float p) {
        float e = Anim.POW3_OUT.apply(p);
        float a = 1f - p;
        draw.circle(cx, cy, 50f + 420f * e, 8f * a + 2f, Theme.SURFACE_TOP, 0.85f * a);
        for (int i = 0; i < 16; i++) {
            float ang = i * MathUtils.PI2 / 16f;
            float d = 60f + 300f * e;
            draw.disc(cx + d * MathUtils.cos(ang), cy + d * MathUtils.sin(ang) + 80f * e * (1f - e), 6f * a + 1f, Theme.SKY_SIG,
                0.8f * a);
        }
    }
}
