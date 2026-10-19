package org.kelvinizer.screens.result;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Matrix4;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchText;
import space.earlygrey.shapedrawer.ShapeDrawer;

/**
 * 结算画面各组件共用的绘制环境：共享的绘图、字体和故障字服务，
 * 临时颜色和字符串缓冲，文字队列，以及缩放、旋转动画用的批处理变换。
 * 变换中的文字立即绘制，其余文字延后按字体页分组绘制；只在 GL 线程使用。
 *
 * <p>Shared drawing context of the DIVE LOG components: the services, scratch colours and builders,
 * the {@link TextQueue}, and the batch transform helpers used for scale / rotation pops (so
 * animating sizes never generates new font buckets). Text drawn inside a pushed transform is
 * drawn at once (the transform belongs to it); everywhere else it is deferred into page groups.
 * Not thread-safe; GL thread only.
 */
public final class ResultKit {
    /** The shared batch. */
    public final Batch batch;
    /** Drawing helpers. */
    public final Draw draw;
    /** Shape drawer over the batch. */
    public final ShapeDrawer shapes;
    /** Font service. */
    public final Fonts fonts;
    /** Glitch text renderer. */
    public final GlitchText glitch;
    /** Glyph-level helpers. */
    public final GlyphQuads quads = new GlyphQuads();
    /** Page-grouped text (shared rasters, deferred until the frame's shapes are down). */
    public final TextQueue text;
    /** Rounded texture quads. */
    public final RoundedImage image = new RoundedImage();
    /** Scratch colours (each component documents which it uses within one call). */
    public final Color c1 = new Color(), c2 = new Color(), c3 = new Color(), c4 = new Color();
    /** Scratch text builders. */
    public final StringBuilder sb = new StringBuilder(64), sb2 = new StringBuilder(64);

    private final Matrix4 saved = new Matrix4();
    private final Matrix4 xf = new Matrix4();
    private int pushed;
    private boolean additive;

    /** Creates the kit over the shared services. */
    public ResultKit(Draw draw, Fonts fonts, GlitchText glitch) {
        this.draw = draw;
        this.batch = draw.batch();
        this.shapes = draw.shapes();
        this.fonts = fonts;
        this.glitch = glitch;
        this.text = new TextQueue(fonts, batch);
    }

    /**
     * Sets the batch transform to translate(cx, cy) · rotate(deg) · scale(sx, sy) (local drawing
     * then happens around the origin). Must be balanced by {@link #pop()}; not nestable.
     */
    public void push(float cx, float cy, float deg, float sx, float sy) {
        if (pushed == 0) {
            saved.set(batch.getTransformMatrix());
            text.direct(true);
        }
        pushed = 1;
        xf.set(saved).translate(cx, cy, 0f);
        if (deg != 0f) xf.rotate(0f, 0f, 1f, deg);
        if (sx != 1f || sy != 1f) xf.scale(sx, sy, 1f);
        batch.setTransformMatrix(xf);
    }

    /** Origin of the current local frame in world units: (0, 0) while pushed, else the centre. */
    public float ox, oy;

    /**
     * Like {@link #push} but skips the transform (and the batch flush it costs) at rest, when
     * there is no rotation and no scale: local drawing then adds {@link #ox} / {@link #oy}.
     *
     * @return whether a transform was pushed; pass it to {@link #popIf(boolean)}
     */
    public boolean pushIf(float cx, float cy, float deg, float sx, float sy) {
        if (deg == 0f && Math.abs(sx - 1f) < 1e-3f && Math.abs(sy - 1f) < 1e-3f) {
            ox = cx;
            oy = cy;
            return false;
        }
        ox = 0f;
        oy = 0f;
        push(cx, cy, deg, sx, sy);
        return true;
    }

    /** Ends a {@link #pushIf} frame. */
    public void popIf(boolean pushedTransform) {
        if (pushedTransform) pop();
        ox = 0f;
        oy = 0f;
    }

    /** Restores the transform saved by {@link #push}. */
    public void pop() {
        if (pushed == 0) return;
        pushed = 0;
        batch.setTransformMatrix(saved);
        text.direct(false);
    }

    /** Switches to additive blending (no-op when already additive). */
    public void additive() {
        if (additive) return;
        additive = true;
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
    }

    /** Back to normal alpha blending (no-op when already normal). */
    public void normal() {
        if (!additive) return;
        additive = false;
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Sets the batch colour to {@code c × alpha}. */
    public void color(Color c, float alpha) {
        batch.setColor(c.r, c.g, c.b, c.a * alpha);
    }

    /** Filled triangle via the shape drawer. */
    public void triangle(float x1, float y1, float x2, float y2, float x3, float y3, Color c, float alpha) {
        float old = shapes.getPackedColor();
        shapes.setColor(c.r, c.g, c.b, c.a * alpha);
        shapes.filledTriangle(x1, y1, x2, y2, x3, y3);
        shapes.setColor(old);
    }

    /** Filled polygon (regular, {@code sides}) via the shape drawer. */
    public void polygon(float cx, float cy, int sides, float radius, float rotRad, Color c, float alpha) {
        float old = shapes.getPackedColor();
        shapes.setColor(c.r, c.g, c.b, c.a * alpha);
        shapes.filledPolygon(cx, cy, sides, radius, rotRad);
        shapes.setColor(old);
    }

    /** Stroked regular polygon via the shape drawer. */
    public void polygonStroke(float cx, float cy, int sides, float radius, float rotRad, float width, Color c,
                              float alpha) {
        float old = shapes.getPackedColor();
        shapes.setColor(c.r, c.g, c.b, c.a * alpha);
        shapes.polygon(cx, cy, sides, radius, rotRad, width);
        shapes.setColor(old);
    }

    /** Clamps to 0..1. */
    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
