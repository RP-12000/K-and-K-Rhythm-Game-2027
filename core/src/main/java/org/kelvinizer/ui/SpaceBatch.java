package org.kelvinizer.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Affine2;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;

/**
 * 3D 画面的合批：转过的轨道、键各有各的镜头矩阵，普通的 SpriteBatch 每换一次矩阵就要画一次（My Soul Deep 一帧五千多次）。
 * 在 {@link #beginSpace()} 和 {@link #endSpace()} 之间，每个顶点在 CPU 上按当时的矩阵算到裁剪空间，换矩阵不用画，
 * 同一张贴图、同一种混合的一整批一次画完；GPU 拿到的位置和原来自己算的一样（只差浮点舍入），画面不变。
 *
 * <p>The shared batch (Kelvin 2026-11-10 01:19 「我的手机根本带不动这么大的3D建模」, 01:34 「想尽一切办法让弱一点的手机/
 * 平板也能正常游玩」). Outside space mode it is a plain {@link SpriteBatch}. Between {@link #beginSpace()} and
 * {@link #endSpace()} (the 3D field's blocks) every quad goes into a mesh of its own whose positions are clip-space
 * (x, y, z, w): each vertex is multiplied on the CPU by the projection × transform in force when it is drawn, exactly
 * the product the shader would have formed, and the shader then runs with an identity {@code u_projTrans}. So
 * {@link #setProjectionMatrix} and {@link #setTransformMatrix} no longer flush there; a flush happens only on a
 * texture, blend or shader change or a full buffer, as before. Every shader used in space mode must compute
 * {@code gl_Position = u_projTrans * a_position} and nothing else from the position (SpriteBatch's default and
 * {@code FadeShader} do); the GPU still clips and interpolates perspective-correctly, as the w is the same.
 */
public final class SpaceBatch extends SpriteBatch {
    /** Floats per space vertex: x, y, z, w, packed colour, u, v. */
    private static final int VERTEX = 7;
    private static final Matrix4 IDENTITY = new Matrix4();

    private final Mesh mesh;
    private final float[] verts;
    private final float[] quad = new float[20];
    private final Matrix4 combined = new Matrix4();
    private int idx;
    private Texture texture;
    private boolean space;
    private boolean dirty = true;

    /** @param size quads per flush, as {@link SpriteBatch#SpriteBatch(int)} (at most 8191) */
    public SpaceBatch(int size) {
        super(size);
        Mesh.VertexDataType type = Gdx.gl30 != null ? Mesh.VertexDataType.VertexBufferObjectWithVAO
            : Mesh.VertexDataType.VertexBufferObject;
        mesh = new Mesh(type, false, size * 4, size * 6,
            new VertexAttribute(Usage.Position, 4, ShaderProgram.POSITION_ATTRIBUTE),
            new VertexAttribute(Usage.ColorPacked, 4, ShaderProgram.COLOR_ATTRIBUTE),
            new VertexAttribute(Usage.TextureCoordinates, 2, ShaderProgram.TEXCOORD_ATTRIBUTE + "0"));
        verts = new float[size * 4 * VERTEX];
        short[] indices = new short[size * 6];
        for (int i = 0, j = 0; i < indices.length; i += 6, j += 4) {
            indices[i] = (short) j;
            indices[i + 1] = (short) (j + 1);
            indices[i + 2] = (short) (j + 2);
            indices[i + 3] = (short) (j + 2);
            indices[i + 4] = (short) (j + 3);
            indices[i + 5] = (short) j;
        }
        mesh.setIndices(indices);
    }

    /** From now until {@link #endSpace()} matrix changes cost no draw call (the batch must be drawing). */
    public void beginSpace() {
        if (space || !isDrawing()) return;
        super.flush();
        space = true;
        dirty = true;
    }

    /** Draws what space mode holds and goes back to a plain SpriteBatch with the matrices last set. */
    public void endSpace() {
        if (!space) return;
        flushSpace();
        space = false;
        getShader().bind();
        setupMatrices(); // the shader's u_projTrans back to projection × transform
    }

    @Override
    public void begin() {
        space = false;
        super.begin();
    }

    @Override
    public void end() {
        endSpace();
        super.end();
    }

    @Override
    public void flush() {
        if (space) flushSpace();
        else super.flush();
    }

    @Override
    public void setProjectionMatrix(Matrix4 projection) {
        if (!space) {
            super.setProjectionMatrix(projection);
            return;
        }
        getProjectionMatrix().set(projection);
        dirty = true;
    }

    @Override
    public void setTransformMatrix(Matrix4 transform) {
        if (!space) {
            super.setTransformMatrix(transform);
            return;
        }
        getTransformMatrix().set(transform);
        dirty = true;
    }

    @Override
    public void dispose() {
        mesh.dispose();
        super.dispose();
    }

    private void flushSpace() {
        if (idx == 0) return;
        renderCalls++;
        totalRenderCalls++;
        int quads = idx / (4 * VERTEX);
        if (quads > maxSpritesInBatch) maxSpritesInBatch = quads;
        texture.bind();
        mesh.setVertices(verts, 0, idx);
        if (!isBlendingEnabled()) {
            Gdx.gl.glDisable(GL20.GL_BLEND);
        } else {
            Gdx.gl.glEnable(GL20.GL_BLEND);
            if (getBlendSrcFunc() != -1) {
                Gdx.gl.glBlendFuncSeparate(getBlendSrcFunc(), getBlendDstFunc(), getBlendSrcFuncAlpha(),
                    getBlendDstFuncAlpha());
            }
        }
        ShaderProgram shader = getShader();
        shader.bind();
        shader.setUniformMatrix("u_projTrans", IDENTITY);
        mesh.render(shader, GL20.GL_TRIANGLES, 0, quads * 6);
        idx = 0;
    }

    /** Adds {@code count} floats of SpriteBatch quads (x, y, colour, u, v per vertex) through the current matrices. */
    private void put(Texture t, float[] src, int offset, int count) {
        if (!isDrawing()) throw new IllegalStateException("SpriteBatch.begin must be called before draw.");
        if (t != texture) {
            flushSpace();
            texture = t;
        }
        if (dirty) {
            combined.set(getProjectionMatrix()).mul(getTransformMatrix()); // as SpriteBatch's setupMatrices
            dirty = false;
        }
        float[] m = combined.val;
        float m00 = m[Matrix4.M00], m01 = m[Matrix4.M01], m03 = m[Matrix4.M03];
        float m10 = m[Matrix4.M10], m11 = m[Matrix4.M11], m13 = m[Matrix4.M13];
        float m20 = m[Matrix4.M20], m21 = m[Matrix4.M21], m23 = m[Matrix4.M23];
        float m30 = m[Matrix4.M30], m31 = m[Matrix4.M31], m33 = m[Matrix4.M33];
        float[] v = verts;
        int k = idx;
        for (int i = offset, end = offset + count; i < end; i += 5) {
            if (k == v.length) { // only ever between quads: the buffer holds whole quads
                idx = k;
                flushSpace();
                k = 0;
            }
            float x = src[i], y = src[i + 1];
            v[k] = m00 * x + m01 * y + m03;
            v[k + 1] = m10 * x + m11 * y + m13;
            v[k + 2] = m20 * x + m21 * y + m23;
            v[k + 3] = m30 * x + m31 * y + m33;
            v[k + 4] = src[i + 2];
            v[k + 5] = src[i + 3];
            v[k + 6] = src[i + 4];
            k += VERTEX;
        }
        idx = k;
    }

    /** One quad from its four corners (SpriteBatch's order: bottom left, top left, top right, bottom right). */
    private void quad(Texture t, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4,
                      float u1, float v1, float u2, float v2, float u3, float v3, float u4, float v4) {
        float c = getPackedColor();
        float[] q = quad;
        q[0] = x1;
        q[1] = y1;
        q[2] = c;
        q[3] = u1;
        q[4] = v1;
        q[5] = x2;
        q[6] = y2;
        q[7] = c;
        q[8] = u2;
        q[9] = v2;
        q[10] = x3;
        q[11] = y3;
        q[12] = c;
        q[13] = u3;
        q[14] = v3;
        q[15] = x4;
        q[16] = y4;
        q[17] = c;
        q[18] = u4;
        q[19] = v4;
        put(t, q, 0, 20);
    }

    /** An axis-aligned quad from (x, y) to (x2, y2) with SpriteBatch's corner texture coordinates. */
    private void rect(Texture t, float x, float y, float x2, float y2, float u, float v, float u2, float v2) {
        quad(t, x, y, x, y2, x2, y2, x2, y, u, v, u, v2, u2, v2, u2, v);
    }

    /**
     * A scaled, rotated quad about its origin, its corners worked out exactly as SpriteBatch does; texture coordinates
     * per corner as SpriteBatch's matching overload passes them.
     */
    private void turned(Texture t, float x, float y, float originX, float originY, float width, float height,
                        float scaleX, float scaleY, float rotation,
                        float u1, float v1, float u2, float v2, float u3, float v3, float u4, float v4) {
        final float worldOriginX = x + originX;
        final float worldOriginY = y + originY;
        float fx = -originX;
        float fy = -originY;
        float fx2 = width - originX;
        float fy2 = height - originY;
        if (scaleX != 1 || scaleY != 1) {
            fx *= scaleX;
            fy *= scaleY;
            fx2 *= scaleX;
            fy2 *= scaleY;
        }
        float x1, y1, x2, y2, x3, y3, x4, y4;
        if (rotation != 0) {
            final float cos = MathUtils.cosDeg(rotation);
            final float sin = MathUtils.sinDeg(rotation);
            x1 = cos * fx - sin * fy;
            y1 = sin * fx + cos * fy;
            x2 = cos * fx - sin * fy2;
            y2 = sin * fx + cos * fy2;
            x3 = cos * fx2 - sin * fy2;
            y3 = sin * fx2 + cos * fy2;
            x4 = x1 + (x3 - x2);
            y4 = y3 - (y2 - y1);
        } else {
            x1 = fx;
            y1 = fy;
            x2 = fx;
            y2 = fy2;
            x3 = fx2;
            y3 = fy2;
            x4 = fx2;
            y4 = fy;
        }
        quad(t, x1 + worldOriginX, y1 + worldOriginY, x2 + worldOriginX, y2 + worldOriginY,
            x3 + worldOriginX, y3 + worldOriginY, x4 + worldOriginX, y4 + worldOriginY,
            u1, v1, u2, v2, u3, v3, u4, v4);
    }

    // ------------------------------------------------------------------ SpriteBatch's draws, routed in space mode

    @Override
    public void draw(Texture t, float x, float y, float originX, float originY, float width, float height,
                     float scaleX, float scaleY, float rotation, int srcX, int srcY, int srcWidth, int srcHeight,
                     boolean flipX, boolean flipY) {
        if (!space) {
            super.draw(t, x, y, originX, originY, width, height, scaleX, scaleY, rotation, srcX, srcY, srcWidth,
                srcHeight, flipX, flipY);
            return;
        }
        float iw = 1.0f / t.getWidth(), ih = 1.0f / t.getHeight();
        float u = srcX * iw, v = (srcY + srcHeight) * ih, u2 = (srcX + srcWidth) * iw, v2 = srcY * ih;
        if (flipX) {
            float tmp = u;
            u = u2;
            u2 = tmp;
        }
        if (flipY) {
            float tmp = v;
            v = v2;
            v2 = tmp;
        }
        turned(t, x, y, originX, originY, width, height, scaleX, scaleY, rotation, u, v, u, v2, u2, v2, u2, v);
    }

    @Override
    public void draw(Texture t, float x, float y, float width, float height, int srcX, int srcY, int srcWidth,
                     int srcHeight, boolean flipX, boolean flipY) {
        if (!space) {
            super.draw(t, x, y, width, height, srcX, srcY, srcWidth, srcHeight, flipX, flipY);
            return;
        }
        float iw = 1.0f / t.getWidth(), ih = 1.0f / t.getHeight();
        float u = srcX * iw, v = (srcY + srcHeight) * ih, u2 = (srcX + srcWidth) * iw, v2 = srcY * ih;
        if (flipX) {
            float tmp = u;
            u = u2;
            u2 = tmp;
        }
        if (flipY) {
            float tmp = v;
            v = v2;
            v2 = tmp;
        }
        rect(t, x, y, x + width, y + height, u, v, u2, v2);
    }

    @Override
    public void draw(Texture t, float x, float y, int srcX, int srcY, int srcWidth, int srcHeight) {
        if (!space) {
            super.draw(t, x, y, srcX, srcY, srcWidth, srcHeight);
            return;
        }
        float iw = 1.0f / t.getWidth(), ih = 1.0f / t.getHeight();
        rect(t, x, y, x + srcWidth, y + srcHeight, srcX * iw, (srcY + srcHeight) * ih, (srcX + srcWidth) * iw,
            srcY * ih);
    }

    @Override
    public void draw(Texture t, float x, float y, float width, float height, float u, float v, float u2, float v2) {
        if (!space) super.draw(t, x, y, width, height, u, v, u2, v2);
        else rect(t, x, y, x + width, y + height, u, v, u2, v2);
    }

    @Override
    public void draw(Texture t, float x, float y, float width, float height) {
        if (!space) super.draw(t, x, y, width, height);
        else rect(t, x, y, x + width, y + height, 0f, 1f, 1f, 0f);
    }

    @Override
    public void draw(Texture t, float[] spriteVertices, int offset, int count) {
        if (!space) super.draw(t, spriteVertices, offset, count);
        else put(t, spriteVertices, offset, count);
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float width, float height) {
        if (!space) super.draw(region, x, y, width, height);
        else rect(region.getTexture(), x, y, x + width, y + height, region.getU(), region.getV2(), region.getU2(),
            region.getV());
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height,
                     float scaleX, float scaleY, float rotation) {
        if (!space) {
            super.draw(region, x, y, originX, originY, width, height, scaleX, scaleY, rotation);
            return;
        }
        float u = region.getU(), v = region.getV2(), u2 = region.getU2(), v2 = region.getV();
        turned(region.getTexture(), x, y, originX, originY, width, height, scaleX, scaleY, rotation,
            u, v, u, v2, u2, v2, u2, v);
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height,
                     float scaleX, float scaleY, float rotation, boolean clockwise) {
        if (!space) {
            super.draw(region, x, y, originX, originY, width, height, scaleX, scaleY, rotation, clockwise);
            return;
        }
        float ru = region.getU(), rv = region.getV(), ru2 = region.getU2(), rv2 = region.getV2();
        if (clockwise) {
            turned(region.getTexture(), x, y, originX, originY, width, height, scaleX, scaleY, rotation,
                ru2, rv2, ru, rv2, ru, rv, ru2, rv);
        } else {
            turned(region.getTexture(), x, y, originX, originY, width, height, scaleX, scaleY, rotation,
                ru, rv, ru2, rv, ru2, rv2, ru, rv2);
        }
    }

    @Override
    public void draw(TextureRegion region, float width, float height, Affine2 transform) {
        if (!space) {
            super.draw(region, width, height, transform);
            return;
        }
        float u = region.getU(), v = region.getV2(), u2 = region.getU2(), v2 = region.getV();
        quad(region.getTexture(), transform.m02, transform.m12,
            transform.m01 * height + transform.m02, transform.m11 * height + transform.m12,
            transform.m00 * width + transform.m01 * height + transform.m02,
            transform.m10 * width + transform.m11 * height + transform.m12,
            transform.m00 * width + transform.m02, transform.m10 * width + transform.m12,
            u, v, u, v2, u2, v2, u2, v);
    }
}
