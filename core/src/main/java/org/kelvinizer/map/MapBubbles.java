package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.ui.Draw;

/**
 * 地图上的触水气泡：固定大小的气泡池，点一下冒出 8 个，拖动留下一串尾迹，长按持续冒泡，K&amp;K 光点游动时也留下气泡。
 *
 * <p>Water-touch bubbles (DESIGN §3.5, P1) in map world units: a fixed pool of rising, wobbling
 * rings. Taps emit 8, drags leave a wake (1 per 40 units, at most 30 alive from the wake), a
 * long press streams 12 per second, and the K&amp;K swim leaves a trail. No allocation after
 * construction.
 */
final class MapBubbles {
    private static final int MAX = 96;
    private static final int WAKE_CAP = 30;

    private final float[] x = new float[MAX], y = new float[MAX], vx = new float[MAX], vy = new float[MAX];
    private final float[] age = new float[MAX], life = new float[MAX], size = new float[MAX], seed = new float[MAX];
    private final boolean[] live = new boolean[MAX], wake = new boolean[MAX];
    private int next;
    private int wakeAlive;
    private float wakeAcc;
    private boolean streaming;
    private float streamX, streamY, streamAcc;
    private int counter;

    /** Emits {@code n} bubbles around (wx, wy) within {@code spread}. */
    void burst(float wx, float wy, int n, float spread) {
        for (int i = 0; i < n; i++) spawn(wx, wy, spread, false);
    }

    /** Accumulates a drag of {@code dist} world units at (wx, wy) into the wake. */
    void wake(float wx, float wy, float dist) {
        wakeAcc += dist;
        while (wakeAcc >= 40f) {
            wakeAcc -= 40f;
            if (wakeAlive < WAKE_CAP) spawn(wx, wy, 30f, true);
        }
    }

    /** Starts or moves the long-press stream. */
    void stream(float wx, float wy) {
        streaming = true;
        streamX = wx;
        streamY = wy;
    }

    /** Stops the stream. */
    void stopStream() {
        streaming = false;
    }

    /** Removes every bubble. */
    void clear() {
        for (int i = 0; i < MAX; i++) live[i] = false;
        wakeAlive = 0;
        streaming = false;
    }

    private void spawn(float wx, float wy, float spread, boolean fromWake) {
        int i = next;
        next = (next + 1) % MAX;
        if (live[i] && wake[i]) wakeAlive--;
        counter++;
        float h1 = hash(counter * 3 + 1), h2 = hash(counter * 3 + 2), h3 = hash(counter * 3 + 3);
        x[i] = wx + (h1 * 2f - 1f) * spread;
        y[i] = wy + (h2 * 2f - 1f) * spread * 0.5f;
        vx[i] = (h3 - 0.5f) * 30f;
        vy[i] = 70f + 90f * h2;
        age[i] = 0f;
        life[i] = 1.2f + 0.9f * h1;
        size[i] = 8f + 12f * h3;
        seed[i] = h1 * MathUtils.PI2;
        live[i] = true;
        wake[i] = fromWake;
        if (fromWake) wakeAlive++;
    }

    private static float hash(int v) {
        int h = v * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return (h >>> 8) / 16777216f;
    }

    /** Advances bubbles and the stream. */
    void update(float dt) {
        if (streaming) {
            streamAcc += dt * 12f;
            while (streamAcc >= 1f) {
                streamAcc -= 1f;
                spawn(streamX, streamY, 12f, false);
            }
        }
        for (int i = 0; i < MAX; i++) {
            if (!live[i]) continue;
            age[i] += dt;
            if (age[i] >= life[i]) {
                live[i] = false;
                if (wake[i]) wakeAlive--;
                continue;
            }
            x[i] += (vx[i] + 18f * MathUtils.sin(age[i] * 5f + seed[i])) * dt;
            y[i] += vy[i] * dt;
            vx[i] *= (float) Math.exp(-1.5f * dt);
        }
    }

    /** Draws the bubbles (map projection; batch drawing). */
    void draw(Draw d, Color tint, float viewL, float viewR, float viewB, float viewT) {
        for (int i = 0; i < MAX; i++) {
            if (!live[i]) continue;
            if (x[i] < viewL - 40f || x[i] > viewR + 40f || y[i] < viewB - 40f || y[i] > viewT + 40f) continue;
            float t = age[i] / life[i];
            float a = Math.min(1f, t * 8f) * (1f - t) * 0.6f;
            float s = size[i] * (0.7f + 0.5f * t);
            d.ringSprite(x[i], y[i], s * 0.5f, tint, a);
            d.disc(x[i] - s * 0.15f, y[i] + s * 0.15f, s * 0.08f, tint, a);
        }
    }
}
