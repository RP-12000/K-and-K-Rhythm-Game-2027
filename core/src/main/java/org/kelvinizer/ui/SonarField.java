package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;

/**
 * 共用的声呐波纹：从某点扩散并淡出的圆环（最多同时 8 个），以及每隔一段时间发出一圈的可选「心跳」源。
 * 由 {@link Backdrop} 持有；其他元素可以用 {@link #waveAt(float, float)} 查询经过的波纹来做呼应。
 *
 * <p>The shared sonar motif (DESIGN §2.5): pooled expanding rings plus an optional heartbeat
 * source. Owned by {@link Backdrop} ({@code backdrop.sonar()}); never allocates after
 * construction.
 *
 * <p>Ring radius follows {@code pow3Out}, alpha {@code (1 − t)²}. Rings with r ≤ 160 are drawn
 * with the {@code ring_thin} sprite, larger ones as a 2-unit ShapeDrawer circle. Other
 * elements "answer" a passing ring through {@link #waveAt(float, float)}.
 */
public final class SonarField {
    /** Maximum simultaneous rings (a multi-ring ping uses one slot per ring). */
    public static final int MAX = 8;
    private static final float SPRITE_MAX_R = 160f;

    private final Draw draw;
    private final float[] x = new float[MAX], y = new float[MAX], maxR = new float[MAX];
    private final float[] dur = new float[MAX], age = new float[MAX], alpha = new float[MAX];
    private final int[] order = new int[MAX];
    private final boolean[] live = new boolean[MAX];
    private final Color[] color = new Color[MAX];
    private int counter;

    private boolean hbOn;
    private float hbX, hbY, hbPeriod = Anim.HEARTBEAT, hbMaxR = 900f, hbAlpha = 0.18f, hbTimer;
    private Color hbColor;

    /** Creates an empty field drawing with the shared atlas. */
    public SonarField(Draw draw) {
        this.draw = draw;
        for (int i = 0; i < MAX; i++) color[i] = new Color(1f, 1f, 1f, 1f);
    }

    /**
     * Emits a ping of {@code rings} (1..3) concentric rings: ring k starts k × 0.07 s later and
     * reaches 0.85^k of {@code maxR}. The colour is copied.
     */
    public void ping(float px, float py, float maxRadius, float durSec, Color c, float a, int rings) {
        int n = MathUtils.clamp(rings, 1, 3);
        float scale = 1f;
        for (int k = 0; k < n; k++) {
            int s = slot();
            x[s] = px;
            y[s] = py;
            maxR[s] = maxRadius * scale;
            dur[s] = Math.max(0.05f, durSec);
            age[s] = -0.07f * k;
            alpha[s] = a;
            color[s].set(c);
            live[s] = true;
            order[s] = ++counter;
            scale *= 0.85f;
        }
    }

    /**
     * Starts (or moves) the heartbeat: a single ring every {@code periodSec} from (hx, hy).
     * The colour is referenced, not copied, so a live accent colour may be passed.
     */
    public void setHeartbeat(float hx, float hy, float periodSec, float maxRadius, float a, Color c) {
        if (!hbOn) hbTimer = periodSec * 0.5f;
        hbOn = true;
        hbX = hx;
        hbY = hy;
        hbPeriod = Math.max(0.3f, periodSec);
        hbMaxR = maxRadius;
        hbAlpha = a;
        hbColor = c;
    }

    /** Moves the heartbeat source without resetting its timer. */
    public void moveHeartbeat(float hx, float hy) {
        hbX = hx;
        hbY = hy;
    }

    /** Stops the heartbeat (rings already emitted finish). */
    public void clearHeartbeat() {
        hbOn = false;
    }

    /** True when a heartbeat is configured. */
    public boolean hasHeartbeat() {
        return hbOn;
    }

    /**
     * Heartbeat phase 0..1 (0 right at an emission) — breathing buttons, stamp rings and
     * prompts sync to it. Runs on the 2.4 s period even without a heartbeat source.
     */
    public float heartbeatPhase() {
        return MathUtils.clamp(hbTimer / hbPeriod, 0f, 1f);
    }

    /** Removes every ring. */
    public void clear() {
        for (int i = 0; i < MAX; i++) live[i] = false;
    }

    /**
     * Wave strength 0..1 at a point: max over rings of {@code clamp(1 − |dist − r| / 40, 0, 1)}
     * times the ring's fade {@code (1 − t)²}.
     */
    public float waveAt(float px, float py) {
        float best = 0f;
        for (int i = 0; i < MAX; i++) {
            if (!live[i] || age[i] < 0f) continue;
            float t = age[i] / dur[i];
            float r = maxR[i] * Anim.POW3_OUT.apply(t);
            float dx = px - x[i], dy = py - y[i];
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float w = MathUtils.clamp(1f - Math.abs(d - r) / 40f, 0f, 1f) * Anim.pingAlpha(t);
            if (w > best) best = w;
        }
        return best;
    }

    /** Advances rings and the heartbeat. */
    public void update(float dt) {
        for (int i = 0; i < MAX; i++) {
            if (!live[i]) continue;
            age[i] += dt;
            if (age[i] >= dur[i]) live[i] = false;
        }
        hbTimer += dt;
        if (hbTimer >= hbPeriod) {
            hbTimer -= hbPeriod;
            if (hbTimer > hbPeriod) hbTimer = 0f;
            if (hbOn && hbColor != null) {
                ping(hbX, hbY, hbMaxR, Math.min(hbPeriod * 0.9f, 1.8f), hbColor, hbAlpha, 1);
            }
        }
    }

    /** Draws every live ring with the current batch blend mode. */
    public void draw(Batch b) {
        for (int i = 0; i < MAX; i++) {
            if (!live[i] || age[i] < 0f) continue;
            float t = age[i] / dur[i];
            float r = maxR[i] * Anim.POW3_OUT.apply(t);
            float a = alpha[i] * Anim.pingAlpha(t);
            if (a <= 0.003f || r < 1f) continue;
            if (r <= SPRITE_MAX_R) {
                draw.ringSprite(x[i], y[i], r, color[i], a);
            } else {
                float old = draw.shapes().getPackedColor();
                Color c = color[i];
                draw.shapes().setColor(c.r, c.g, c.b, c.a * a);
                int sides = MathUtils.clamp((int) (r / 10f), 32, 96);
                draw.shapes().arc(x[i], y[i], r, 0f, MathUtils.PI2, 2f, false, sides);
                draw.shapes().setColor(old);
            }
        }
    }

    private int slot() {
        int oldest = 0, oldestOrder = Integer.MAX_VALUE;
        for (int i = 0; i < MAX; i++) {
            if (!live[i]) return i;
            if (order[i] < oldestOrder) {
                oldestOrder = order[i];
                oldest = i;
            }
        }
        return oldest;
    }
}
