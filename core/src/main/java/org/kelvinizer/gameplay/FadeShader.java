package org.kelvinizer.gameplay;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;

/**
 * 整体淡化：在 SpriteBatch 的默认着色器上再乘一个透明度，把一段画面（暂停后继续时倒数里的谱面）整体画淡，不用改各个绘制器。
 *
 * <p>SpriteBatch's default shader with every fragment's alpha multiplied by {@code u_alpha}: whatever is drawn between
 * {@link #begin} and {@link #end} fades as a whole, as if its own opacity were lower (the chart fading back in through
 * the resume countdown, Kelvin 2026-10-21 15:40). The gameplay blends are all {@code SRC_ALPHA} ones (normal and
 * additive), so a lower alpha dims colours and glows alike. Compiled at the first use; when it does not compile,
 * {@link #begin} changes nothing.
 */
final class FadeShader implements Disposable {
    private static final String VERTEX = "attribute vec4 a_position;\n"
        + "attribute vec4 a_color;\n"
        + "attribute vec2 a_texCoord0;\n"
        + "uniform mat4 u_projTrans;\n"
        + "varying vec4 v_color;\n"
        + "varying vec2 v_texCoords;\n"
        + "void main() {\n"
        + "    v_color = a_color;\n"
        + "    v_color.a = v_color.a * (255.0 / 254.0);\n"
        + "    v_texCoords = a_texCoord0;\n"
        + "    gl_Position = u_projTrans * a_position;\n"
        + "}\n";
    private static final String FRAGMENT = "#ifdef GL_ES\n"
        + "#define LOWP lowp\n"
        + "precision mediump float;\n"
        + "#else\n"
        + "#define LOWP\n"
        + "#endif\n"
        + "varying LOWP vec4 v_color;\n"
        + "varying vec2 v_texCoords;\n"
        + "uniform sampler2D u_texture;\n"
        + "uniform float u_alpha;\n"
        + "void main() {\n"
        + "    gl_FragColor = v_color * texture2D(u_texture, v_texCoords);\n"
        + "    gl_FragColor.a *= u_alpha;\n"
        + "}\n";

    private ShaderProgram program;
    private boolean failed;

    /**
     * Draws what follows on the begun {@code batch} with its alpha times {@code alpha} (flushes what was drawn before).
     *
     * @return false when the shader does not compile (nothing changed, nothing to {@link #end})
     */
    boolean begin(SpriteBatch batch, float alpha) {
        if (program == null && !failed) {
            program = new ShaderProgram(VERTEX, FRAGMENT);
            if (!program.isCompiled()) {
                if (Gdx.app != null) Gdx.app.error("FadeShader", program.getLog());
                program.dispose();
                program = null;
                failed = true;
            }
        }
        if (program == null) return false;
        batch.setShader(program);
        program.setUniformf("u_alpha", alpha);
        return true;
    }

    /** Back to the batch's own shader (flushes the faded draws). */
    void end(SpriteBatch batch) {
        batch.setShader(null);
    }

    @Override
    public void dispose() {
        if (program != null) program.dispose();
        program = null;
    }
}
