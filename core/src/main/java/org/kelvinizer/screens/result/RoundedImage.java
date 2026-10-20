package org.kelvinizer.screens.result;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;

/**
 * 把贴图画成圆角矩形（结算画面的曲绘），不用着色器、裁剪或帧缓冲：中间三块矩形加四个角的扇形三角形。
 * 同样的形状也能画成一层由上往下渐深的纯色（曲绘下部的渐暗）。旋转和缩放来自调用方设置的批处理变换；构造后不再分配内存。
 *
 * <p>Draws a texture into a rectangle with rounded corners, without a shader, scissor or FBO: a
 * centre cross of three quads plus a fan of degenerate quads (triangles) per corner. {@link #shade} fills the same
 * shape with a solid colour whose alpha ramps up towards the bottom (the jacket's dark lower part). Rotation and
 * scale come from the batch transform (the caller sets it), so this is plain geometry in the
 * current space. No allocation after construction.
 */
public final class RoundedImage {
    private static final int SEG = 6;
    private static final float[] COS = new float[SEG + 1];
    private static final float[] SIN = new float[SEG + 1];
    /** Linear bands of the shade ramp. */
    private static final int RAMP_STEPS = 5;

    static {
        for (int i = 0; i <= SEG; i++) {
            float a = MathUtils.HALF_PI * i / SEG;
            COS[i] = MathUtils.cos(a);
            SIN[i] = MathUtils.sin(a);
        }
    }

    private final float[] q = new float[20];
    private Texture tex;
    private float x, y, w, h, u0, v0, u1, v1, col;
    // shade ramp (see shade): colour, alpha, start (world y) and shape
    private boolean ramp;
    private float rampR, rampG, rampB, rampA, rampTop, rampAmount, rampPower;
    private final float[] rampLevel = new float[RAMP_STEPS + 1];

    /**
     * Draws {@code [u, v, u2, v2]} of {@code texture} (v increases downwards, as in libGDX) into
     * {@code (x, y, w, h)} with corner radius {@code r}, tinted with the packed colour.
     */
    public void draw(Batch b, Texture texture, float u, float v, float u2, float v2, float x, float y, float w,
                     float h, float r, float packedColor) {
        if (texture == null || w <= 0f || h <= 0f) return;
        this.batch = b;
        this.tex = texture;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.u0 = u;
        this.v0 = v;
        this.u1 = u2;
        this.v1 = v2;
        this.col = packedColor;
        shape(r);
    }

    /**
     * Fills the rounded rectangle with {@code c}: transparent down to {@code from} (a fraction of the height, from
     * the top), then its alpha rises to {@code amount} at the bottom as {@code t^power} ({@value #RAMP_STEPS} linear
     * bands). Drawn over the image of {@link #draw}, this blends the image towards {@code c}. {@code white} is a
     * solid white region.
     */
    public void shade(Batch b, TextureRegion white, float x, float y, float w, float h, float r, Color c, float alpha,
                      float from, float amount, float power) {
        if (white == null || w <= 0f || h <= 0f || alpha <= 0.002f || amount <= 0f) return;
        this.batch = b;
        this.tex = white.getTexture();
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        u0 = u1 = 0.5f * (white.getU() + white.getU2());
        v0 = v1 = 0.5f * (white.getV() + white.getV2());
        rampR = c.r;
        rampG = c.g;
        rampB = c.b;
        rampA = c.a * alpha;
        rampTop = y + h * (1f - from);
        rampAmount = amount;
        rampPower = power;
        for (int i = 0; i <= RAMP_STEPS; i++) rampLevel[i] = rampTop - (rampTop - y) * i / RAMP_STEPS;
        ramp = true;
        shape(r);
        ramp = false;
    }

    /** The centre cross and the four corner fans in the current space. */
    private void shape(float r) {
        r = Math.max(0f, Math.min(r, Math.min(w, h) * 0.5f));
        if (r < 0.5f) {
            quad(x, y, x + w, y + h);
            return;
        }
        // centre column, then the left and right bands between the corners
        quad(x + r, y, x + w - r, y + h);
        quad(x, y + r, x + r, y + h - r);
        quad(x + w - r, y + r, x + w, y + h - r);
        corner(x + r, y + r, r, -1f, -1f);
        corner(x + w - r, y + r, r, 1f, -1f);
        corner(x + w - r, y + h - r, r, 1f, 1f);
        corner(x + r, y + h - r, r, -1f, 1f);
    }

    private void corner(float cx, float cy, float r, float sx, float sy) {
        // the top corners of a shade sit in its clear part
        if (ramp && rampAlpha(Math.min(cy, cy + sy * r)) <= 0f) return;
        for (int i = 0; i < SEG; i++) {
            float ax = cx + sx * COS[i] * r, ay = cy + sy * SIN[i] * r;
            float bx = cx + sx * COS[i + 1] * r, by = cy + sy * SIN[i + 1] * r;
            vert(0, cx, cy);
            vert(5, ax, ay);
            vert(10, bx, by);
            vert(15, bx, by);
            batchDraw();
        }
    }

    private Batch batch;

    private void quad(float xa, float ya, float xb, float yb) {
        if (xb <= xa || yb <= ya) return;
        if (!ramp) {
            band(xa, ya, xb, yb);
            return;
        }
        // split at the ramp's band levels (descending) so each piece is linear in y
        float lo = ya;
        for (int i = RAMP_STEPS; i >= 0; i--) {
            float l = rampLevel[i];
            if (l <= lo || l >= yb) continue;
            if (rampAlpha(l) > 0f || rampAlpha(lo) > 0f) band(xa, lo, xb, l);
            lo = l;
        }
        if (rampAlpha(lo) > 0f || rampAlpha(yb) > 0f) band(xa, lo, xb, yb);
    }

    private void band(float xa, float ya, float xb, float yb) {
        vert(0, xa, ya);
        vert(5, xa, yb);
        vert(10, xb, yb);
        vert(15, xb, ya);
        batchDraw();
    }

    /** Shade alpha at world y (0 above the ramp's start). */
    private float rampAlpha(float py) {
        if (py >= rampTop) return 0f;
        float t = Math.min(1f, (rampTop - py) / Math.max(1e-3f, rampTop - y));
        return rampA * rampAmount * (float) Math.pow(t, rampPower);
    }

    private void batchDraw() {
        batch.draw(tex, q, 0, 20);
    }

    private void vert(int i, float px, float py) {
        q[i] = px;
        q[i + 1] = py;
        q[i + 2] = ramp ? Color.toFloatBits(rampR, rampG, rampB, rampAlpha(py)) : col;
        q[i + 3] = u0 + (px - x) / w * (u1 - u0);
        q[i + 4] = v1 + (py - y) / h * (v0 - v1);
    }
}
