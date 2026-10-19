package org.kelvinizer.screens.result;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.ui.Draw;

/**
 * 结算画面的装饰粒子：评级印章砸下时的火花、解锁头像外观时的金色光点、深度计和拖动手指处冒出的气泡，以及 R+ 的虹彩微粒。
 * 容量固定、不分配内存，由画面在叠加混合下绘制；与打击特效的 {@code fx.FxPool} 同名但无关。
 *
 * <p>The DIVE LOG's flourish particles (struct of arrays, fixed capacity, no allocation): streak
 * sparks at the stamp impact, unlock dots, gauge bubbles, R+ bio-motes and drag bubbles.
 * Updated with real seconds; drawn by the caller inside an additive blend block.
 */
public final class FxPool {
    /** Particle kinds. */
    public static final int SPARK = 0, DOT = 1, BUBBLE = 2, MOTE = 3;
    /** Capacity. */
    public static final int MAX = 220;

    private final float[] x = new float[MAX], y = new float[MAX], vx = new float[MAX], vy = new float[MAX];
    private final float[] age = new float[MAX], life = new float[MAX], size = new float[MAX];
    private final float[] drag = new float[MAX], ay = new float[MAX], sway = new float[MAX], phase = new float[MAX];
    private final float[] r = new float[MAX], g = new float[MAX], b = new float[MAX], a = new float[MAX];
    private final int[] kind = new int[MAX];
    private int count;
    private float density = 1f;
    private int seed = 7;

    /** Particle density multiplier (0 = off, 0.5 = low, 1 = high). */
    public void setDensity(float k) {
        density = Math.max(0f, Math.min(1f, k));
    }

    /** Scales a nominal particle count by the density. */
    public int scaled(int n) {
        return Math.round(n * density);
    }

    /** Removes every particle. */
    public void clear() {
        count = 0;
    }

    /** Live particles. */
    public int count() {
        return count;
    }

    /** Deterministic pseudo-random 0..1 (the pool's own sequence). */
    public float rnd() {
        seed = seed * 1103515245 + 12345;
        return ((seed >>> 8) & 0xFFFF) / 65535f;
    }

    /** Adds one particle; returns its index or −1 when full. */
    public int add(int k, float px, float py, float pvx, float pvy, float lifeSec, float sz, Color c, float alpha) {
        if (count >= MAX) return -1;
        int i = count++;
        kind[i] = k;
        x[i] = px;
        y[i] = py;
        vx[i] = pvx;
        vy[i] = pvy;
        age[i] = 0f;
        life[i] = Math.max(0.05f, lifeSec);
        size[i] = sz;
        drag[i] = 0f;
        ay[i] = 0f;
        sway[i] = 0f;
        phase[i] = rnd() * MathUtils.PI2;
        r[i] = c.r;
        g[i] = c.g;
        b[i] = c.b;
        a[i] = alpha;
        return i;
    }

    /** Sets drag (1/s), vertical acceleration and horizontal sway amplitude of particle {@code i}. */
    public void motion(int i, float dragPerSec, float accelY, float swayAmp) {
        if (i < 0) return;
        drag[i] = dragPerSec;
        ay[i] = accelY;
        sway[i] = swayAmp;
    }

    /** Emits {@code n} streak sparks from a ring of radius {@code r0} around (cx, cy). */
    public void burstSparks(float cx, float cy, float r0, int n, float speed, Color c) {
        n = scaled(n);
        for (int k = 0; k < n; k++) {
            float ang = MathUtils.PI2 * (k + 0.35f * rnd()) / Math.max(1, n);
            float cs = MathUtils.cos(ang), sn = MathUtils.sin(ang);
            float sp = speed * (0.75f + 0.5f * rnd());
            int i = add(SPARK, cx + cs * r0, cy + sn * r0, cs * sp, sn * sp, 0.45f + 0.25f * rnd(),
                5f + 3f * rnd(), c, 0.95f);
            motion(i, 4.2f, 0f, 0f);
        }
    }

    /** Emits {@code n} glow dots spread along a horizontal segment, rising. */
    public void burstDots(float x0, float x1, float py, int n, Color c) {
        n = scaled(n);
        for (int k = 0; k < n; k++) {
            float px = x0 + (x1 - x0) * rnd();
            float ang = MathUtils.PI * (0.1f + 0.8f * rnd());
            float sp = 160f + 260f * rnd();
            int i = add(DOT, px, py, MathUtils.cos(ang) * sp, MathUtils.sin(ang) * sp, 0.6f + 0.5f * rnd(),
                10f + 12f * rnd(), c, 0.9f);
            motion(i, 2.6f, -380f, 0f);
        }
    }

    /** Emits {@code n} rising bubbles around (cx, cy). */
    public void bubbles(float cx, float cy, float spread, int n, Color c) {
        n = scaled(n);
        for (int k = 0; k < n; k++) {
            int i = add(BUBBLE, cx + (rnd() * 2f - 1f) * spread, cy + (rnd() * 2f - 1f) * spread * 0.5f,
                0f, 40f + 70f * rnd(), 0.9f + 0.6f * rnd(), 6f + 8f * rnd(), c, 0.55f);
            motion(i, 0.4f, 30f, 10f + 8f * rnd());
        }
    }

    /** Emits {@code n} slow bio-motes rising over a box (R+). */
    public void motes(float x0, float y0, float w, float h, int n, Color c1, Color c2, Color c3) {
        n = scaled(n);
        for (int k = 0; k < n; k++) {
            float pick = rnd();
            Color c = pick < 0.34f ? c1 : (pick < 0.67f ? c2 : c3);
            int i = add(MOTE, x0 + w * rnd(), y0 + h * rnd(), 0f, 50f + 70f * rnd(), 1.4f + 0.6f * rnd(),
                8f + 10f * rnd(), c, 0.85f);
            motion(i, 0.2f, 12f, 14f + 10f * rnd());
        }
    }

    /** Advances every particle by {@code dt} real seconds. */
    public void update(float dt) {
        if (dt <= 0f) return;
        for (int i = 0; i < count; ) {
            age[i] += dt;
            if (age[i] >= life[i]) {
                remove(i);
                continue;
            }
            float k = 1f - Math.min(0.9f, drag[i] * dt);
            vx[i] *= k;
            vy[i] = (vy[i] + ay[i] * dt) * k;
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            i++;
        }
    }

    private void remove(int i) {
        int last = --count;
        if (i == last) return;
        kind[i] = kind[last];
        x[i] = x[last];
        y[i] = y[last];
        vx[i] = vx[last];
        vy[i] = vy[last];
        age[i] = age[last];
        life[i] = life[last];
        size[i] = size[last];
        drag[i] = drag[last];
        ay[i] = ay[last];
        sway[i] = sway[last];
        phase[i] = phase[last];
        r[i] = r[last];
        g[i] = g[last];
        b[i] = b[last];
        a[i] = a[last];
    }

    /** Draws every particle (the caller sets additive blending). */
    public void draw(Batch batch, Draw d, float alphaMul) {
        if (count == 0 || alphaMul <= 0f) return;
        float old = batch.getPackedColor();
        TextureRegion streak = d.streak(), dot = d.softDot(), ring = d.ringThin();
        for (int i = 0; i < count; i++) {
            float u = age[i] / life[i];
            float px = x[i] + (sway[i] != 0f ? MathUtils.sin(phase[i] + age[i] * 2.4f) * sway[i] : 0f);
            float py = y[i];
            float s = size[i];
            switch (kind[i]) {
                case SPARK: {
                    float sp = (float) Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i]);
                    float len = Math.max(s * 2f, Math.min(90f, sp * 0.09f));
                    float al = a[i] * (1f - u) * (1f - u) * alphaMul;
                    batch.setColor(r[i], g[i], b[i], al);
                    float deg = MathUtils.atan2(vy[i], vx[i]) * MathUtils.radiansToDegrees;
                    batch.draw(streak, px - len * 0.5f, py - s * 0.5f, len * 0.5f, s * 0.5f, len, s, 1f, 1f, deg);
                    break;
                }
                case BUBBLE: {
                    float al = a[i] * Math.min(1f, u * 6f) * (1f - u) * alphaMul;
                    float sz = s * (0.8f + 0.4f * u);
                    batch.setColor(r[i], g[i], b[i], al);
                    batch.draw(ring, px - sz, py - sz, sz * 2f, sz * 2f);
                    break;
                }
                case MOTE: {
                    float tw = 0.75f + 0.25f * MathUtils.sin(phase[i] * 3f + age[i] * 7f);
                    float al = a[i] * Math.min(1f, u * 5f) * (1f - u) * tw * alphaMul;
                    batch.setColor(r[i], g[i], b[i], al);
                    batch.draw(dot, px - s, py - s, s * 2f, s * 2f);
                    break;
                }
                case DOT:
                default: {
                    float al = a[i] * (1f - u) * alphaMul;
                    float sz = s * (1f - 0.5f * u);
                    batch.setColor(r[i], g[i], b[i], al);
                    batch.draw(dot, px - sz, py - sz, sz * 2f, sz * 2f);
                    break;
                }
            }
        }
        batch.setPackedColor(old);
    }
}
