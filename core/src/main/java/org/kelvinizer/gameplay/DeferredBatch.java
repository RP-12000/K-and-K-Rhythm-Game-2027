package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Affine2;
import com.badlogic.gdx.math.Matrix4;

import java.util.Arrays;

/**
 * 先记录、再按贴图分组重放的 {@link Batch}：游戏画面里分散绘制的文字最后按字体页合并，每页只用一次绘制调用。
 * 只调整不同贴图之间的先后，同一贴图内的顺序不变；稳定运行时不分配内存。
 *
 * <p>A recording {@link Batch} that keeps the quads drawn through it and replays them into the real
 * batch grouped by texture (first-seen order), so text drawn in many small calls costs one draw
 * call per font page instead of one per texture switch (DESIGN §14 draw-call budget).
 *
 * <p>Use: {@link #start(Batch)} while the real batch is drawing, pass this object as the
 * {@code Batch} to text code ({@code Fonts}, {@code GlitchText}, widget labels), then
 * {@link #replay()}. Shapes that the caller draws through {@code Draw} (which owns the real batch)
 * go straight to the real batch, so a pass of "widget = plate + label" ends up as all plates
 * first, then all labels: draw order is only reordered between different textures, never within
 * one, which is exact for text over its own plates.
 *
 * <p>Recorded: {@code draw(Texture, float[], int, int)} (BitmapFontCache),
 * {@code draw(Texture, x, y, w, h, u, v, u2, v2)} (fixed-cell digits, glitch slices) and the
 * unrotated {@code TextureRegion} / {@code Texture} variants. Rotated or flipped variants are
 * forwarded to the real batch at once with the recorder's colour. State setters (blend, shader,
 * matrices) are forwarded immediately. The buffers are allocated with the recorder; a page with
 * more than 256 quads grows its buffer once and keeps it, so steady-state use allocates nothing.
 * Not thread-safe; one instance per pass owner.
 */
final class DeferredBatch implements Batch {
    private static final int MAX_GROUPS = 12;
    /** Room for 256 quads per texture before a (one-time) growth. */
    private static final int INITIAL_FLOATS = 20 * 256;

    private Batch target;
    private final Texture[] textures = new Texture[MAX_GROUPS];
    private final float[][] vertices = new float[MAX_GROUPS][];
    private final int[] lengths = new int[MAX_GROUPS];
    private int groups;
    /** Group of the last {@link #slot} call. */
    private int current;
    private final Color color = new Color(1f, 1f, 1f, 1f);
    private float packed = Color.WHITE_FLOAT_BITS;

    /**
     * Allocates every group buffer up front (about 240 KB), so the first frame that shows a new
     * font page never allocates mid-song.
     */
    DeferredBatch() {
        for (int g = 0; g < MAX_GROUPS; g++) vertices[g] = new float[INITIAL_FLOATS];
    }

    /** Starts recording for {@code real} (which must be drawing); the colour starts as the real batch's. */
    void start(Batch real) {
        target = real;
        groups = 0;
        color.set(real.getColor());
        packed = real.getPackedColor();
    }

    /** Draws everything recorded since {@link #start} into the real batch, one call per texture, and stops. */
    void replay() {
        if (target == null) return;
        for (int g = 0; g < groups; g++) {
            if (lengths[g] > 0) target.draw(textures[g], vertices[g], 0, lengths[g]);
            textures[g] = null;
            lengths[g] = 0;
        }
        groups = 0;
        target = null;
    }

    // ------------------------------------------------------------------ recording

    private float[] slot(Texture t, int floats) {
        int g = -1;
        for (int i = 0; i < groups; i++) {
            if (textures[i] == t) {
                g = i;
                break;
            }
        }
        if (g < 0) {
            if (groups == MAX_GROUPS) return null;
            g = groups++;
            textures[g] = t;
            lengths[g] = 0;
        }
        float[] v = vertices[g];
        int need = lengths[g] + floats;
        if (need > v.length) {
            v = Arrays.copyOf(v, Math.max(need, v.length * 2));
            vertices[g] = v;
        }
        current = g;
        return v;
    }

    private void quad(Texture t, float x, float y, float w, float h, float u, float v, float u2, float v2) {
        float[] buf = slot(t, 20);
        if (buf == null) {
            float old = target.getPackedColor();
            target.setPackedColor(packed);
            target.draw(t, x, y, w, h, u, v, u2, v2);
            target.setPackedColor(old);
            return;
        }
        int i = lengths[current];
        float x2 = x + w;
        float y2 = y + h;
        float c = packed;
        buf[i] = x;
        buf[i + 1] = y;
        buf[i + 2] = c;
        buf[i + 3] = u;
        buf[i + 4] = v;
        buf[i + 5] = x;
        buf[i + 6] = y2;
        buf[i + 7] = c;
        buf[i + 8] = u;
        buf[i + 9] = v2;
        buf[i + 10] = x2;
        buf[i + 11] = y2;
        buf[i + 12] = c;
        buf[i + 13] = u2;
        buf[i + 14] = v2;
        buf[i + 15] = x2;
        buf[i + 16] = y;
        buf[i + 17] = c;
        buf[i + 18] = u2;
        buf[i + 19] = v;
        lengths[current] = i + 20;
    }

    @Override
    public void draw(Texture texture, float[] spriteVertices, int offset, int count) {
        if (count <= 0) return;
        float[] buf = slot(texture, count);
        if (buf == null) {
            target.draw(texture, spriteVertices, offset, count);
            return;
        }
        System.arraycopy(spriteVertices, offset, buf, lengths[current], count);
        lengths[current] += count;
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, float u, float v, float u2,
                     float v2) {
        quad(texture, x, y, width, height, u, v, u2, v2);
    }

    @Override
    public void draw(Texture texture, float x, float y) {
        quad(texture, x, y, texture.getWidth(), texture.getHeight(), 0f, 1f, 1f, 0f);
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height) {
        quad(texture, x, y, width, height, 0f, 1f, 1f, 0f);
    }

    @Override
    public void draw(TextureRegion region, float x, float y) {
        quad(region.getTexture(), x, y, region.getRegionWidth(), region.getRegionHeight(), region.getU(),
            region.getV2(), region.getU2(), region.getV());
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float width, float height) {
        quad(region.getTexture(), x, y, width, height, region.getU(), region.getV2(), region.getU2(), region.getV());
    }

    // ------------------------------------------------------------------ forwarded (immediate) draws

    private float swapIn() {
        float old = target.getPackedColor();
        target.setPackedColor(packed);
        return old;
    }

    @Override
    public void draw(Texture texture, float x, float y, float originX, float originY, float width, float height,
                     float scaleX, float scaleY, float rotation, int srcX, int srcY, int srcWidth, int srcHeight,
                     boolean flipX, boolean flipY) {
        float old = swapIn();
        target.draw(texture, x, y, originX, originY, width, height, scaleX, scaleY, rotation, srcX, srcY, srcWidth,
            srcHeight, flipX, flipY);
        target.setPackedColor(old);
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, int srcX, int srcY, int srcWidth,
                     int srcHeight, boolean flipX, boolean flipY) {
        float old = swapIn();
        target.draw(texture, x, y, width, height, srcX, srcY, srcWidth, srcHeight, flipX, flipY);
        target.setPackedColor(old);
    }

    @Override
    public void draw(Texture texture, float x, float y, int srcX, int srcY, int srcWidth, int srcHeight) {
        float old = swapIn();
        target.draw(texture, x, y, srcX, srcY, srcWidth, srcHeight);
        target.setPackedColor(old);
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height,
                     float scaleX, float scaleY, float rotation) {
        float old = swapIn();
        target.draw(region, x, y, originX, originY, width, height, scaleX, scaleY, rotation);
        target.setPackedColor(old);
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height,
                     float scaleX, float scaleY, float rotation, boolean clockwise) {
        float old = swapIn();
        target.draw(region, x, y, originX, originY, width, height, scaleX, scaleY, rotation, clockwise);
        target.setPackedColor(old);
    }

    @Override
    public void draw(TextureRegion region, float width, float height, Affine2 transform) {
        float old = swapIn();
        target.draw(region, width, height, transform);
        target.setPackedColor(old);
    }

    // ------------------------------------------------------------------ colour

    @Override
    public void setColor(Color tint) {
        color.set(tint);
        packed = color.toFloatBits();
    }

    @Override
    public void setColor(float r, float g, float b, float a) {
        color.set(r, g, b, a);
        packed = color.toFloatBits();
    }

    @Override
    public Color getColor() {
        return color;
    }

    @Override
    public void setPackedColor(float packedColor) {
        packed = packedColor;
        Color.abgr8888ToColor(color, packedColor);
    }

    @Override
    public float getPackedColor() {
        return packed;
    }

    // ------------------------------------------------------------------ state (forwarded)

    /** No-op: use {@link #start(Batch)}. */
    @Override
    public void begin() {
    }

    /** No-op: use {@link #replay()}. */
    @Override
    public void end() {
    }

    /** No-op: recorded quads are only drawn by {@link #replay()}. */
    @Override
    public void flush() {
    }

    @Override
    public void disableBlending() {
        target.disableBlending();
    }

    @Override
    public void enableBlending() {
        target.enableBlending();
    }

    @Override
    public void setBlendFunction(int srcFunc, int dstFunc) {
        target.setBlendFunction(srcFunc, dstFunc);
    }

    @Override
    public void setBlendFunctionSeparate(int srcFuncColor, int dstFuncColor, int srcFuncAlpha, int dstFuncAlpha) {
        target.setBlendFunctionSeparate(srcFuncColor, dstFuncColor, srcFuncAlpha, dstFuncAlpha);
    }

    @Override
    public int getBlendSrcFunc() {
        return target.getBlendSrcFunc();
    }

    @Override
    public int getBlendDstFunc() {
        return target.getBlendDstFunc();
    }

    @Override
    public int getBlendSrcFuncAlpha() {
        return target.getBlendSrcFuncAlpha();
    }

    @Override
    public int getBlendDstFuncAlpha() {
        return target.getBlendDstFuncAlpha();
    }

    @Override
    public Matrix4 getProjectionMatrix() {
        return target.getProjectionMatrix();
    }

    @Override
    public Matrix4 getTransformMatrix() {
        return target.getTransformMatrix();
    }

    @Override
    public void setProjectionMatrix(Matrix4 projection) {
        target.setProjectionMatrix(projection);
    }

    @Override
    public void setTransformMatrix(Matrix4 transform) {
        target.setTransformMatrix(transform);
    }

    @Override
    public void setShader(ShaderProgram shader) {
        target.setShader(shader);
    }

    @Override
    public ShaderProgram getShader() {
        return target.getShader();
    }

    @Override
    public boolean isBlendingEnabled() {
        return target.isBlendingEnabled();
    }

    @Override
    public boolean isDrawing() {
        return target != null && target.isDrawing();
    }

    /** Nothing to free (the buffers are plain arrays). */
    @Override
    public void dispose() {
    }
}
