package org.kelvinizer.gameplay;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import org.kelvinizer.elements.NoteGlitch;
import org.kelvinizer.elements.Veil;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.GlitchText;

import java.nio.IntBuffer;

/**
 * 漩涡和被漩涡判成无效的手指的画法：把已经画好的画面复制下来，用着色器画出折射、同心圆或光网、平常自带的扭曲和 glitch；
 * 判定线、连击数和判定字留在漩涡上面。着色器编译不了时退回简单的圆和轮廓。只是画面，不影响判定。
 *
 * <p>Draws the veils (CHART_FORMAT_v4 §6.10, DESIGN §24.56; Kelvin 2026-10-23 16:09–16:47, distortion 16:57) and the
 * glitch mark of a finger a veil made invalid (17:01). Each veil copies the frame under it
 * ({@code glCopyTexSubImage2D}) and redraws that patch with one shader, after the formulas of the approved mock-up:
 * <ul>
 *   <li>the drawn shape wobbles on its own (two sine layers of 9 and 3.5 reference units, a 6 s loop, times the
 *       veil's distortion strength) while the blocking area stays the exact circle or polygon;</li>
 *   <li>a circle shows concentric rings drifting outward, a polygon a flowing caustic net; both bend what is
 *       under them, and notes keep their own colour under the water;</li>
 *   <li>a glitch segment tears the skin only (rows slide and dim, red and blue part), as notes glitch;</li>
 *   <li>a curtain (水帘, made with {@code curtain} true) only blurs everything drawn under it, judgement lines,
 *       hit effects and HUD included, with a soft edge and thin streaks of water falling through it; it has no
 *       skin, outline or glitch, and never blocks a touch;</li>
 *   <li>on a veil the judgement line strokes stay crisp above the water, and the HUD drawn beneath the notes (combo,
 *       judgement word, timing meter) is drawn again above the most prominent veil through {@link #clipBegin}.</li>
 * </ul>
 * The mark ({@link #drawTouch}) bends and tears the picture under the finger with a ring as wide as a note.
 * Shapes stretch with the field like every other chart event: the centre maps through {@link Field#x} /
 * {@link Field#y} and the outline is authored in the reference frame and multiplied by {@link Field#kx()} and
 * {@link Field#ky()} separately (Kelvin 2026-11-04), the same mapping {@link Veils} judges with. One extra draw
 * call per veil and per mark; no allocation per frame (the capture texture is made again only when the viewport
 * size changes).
 */
public final class VeilRenderer implements Disposable {
    /** Seconds of one loop of the veil's own motion. */
    public static final float LOOP_SEC = 6f;
    /** Angular speed of that loop (rad/s). */
    private static final float OM = (float) (2.0 * Math.PI / LOOP_SEC);
    /** Radius of the glitch mark the mock-up was tuned at (px); its sizes scale with the radius over this. */
    private static final float MARK_TUNED_R = 160f;

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

    private static final String HEAD = "#ifdef GL_ES\n"
        + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
        + "precision highp float;\n"
        + "#else\n"
        + "precision mediump float;\n"
        + "#endif\n"
        + "#define LOWP lowp\n"
        + "#else\n"
        + "#define LOWP\n"
        + "#endif\n"
        + "varying LOWP vec4 v_color;\n"
        + "varying vec2 v_texCoords;\n"
        + "uniform sampler2D u_texture;\n"
        // capture: viewport x, y, 1/width, 1/height; the copied rectangle (window px) samples are clamped to
        + "uniform vec4 u_cap;\n"
        + "uniform vec4 u_rect;\n"
        + "vec3 cap(vec2 w) {\n"
        + "    return texture2D(u_texture, (clamp(w, u_rect.xy, u_rect.zw) - u_cap.xy) * u_cap.zw).rgb;\n"
        + "}\n";

    /** The veil: mode 0 draws it over the copy, 1 / 2 pass sprites only outside / inside it (the HUD clip). */
    private static final String VEIL_FRAGMENT = HEAD
        + "uniform vec4 u_win;\n"   // window px of world (0, 0); world units per px x, y
        + "uniform vec4 u_xf;\n"    // centre (world), 1 / scale, scale
        + "uniform vec4 u_an;\n"    // world units per reference unit: x, y (the field's stretch)
        + "uniform vec4 u_rot;\n"   // cos, sin of the turn; px per world unit x, y
        + "uniform vec4 u_shape;\n" // 0 circle / 1 polygon, radius, points, opacity
        + "uniform vec4 u_pts[9];\n"
        + "uniform vec4 u_anim;\n"  // t (s), distortion, mode, glitch seed
        + "uniform vec4 u_gl;\n"    // glitch: slide chance, reach, colour split, dim chance
        + "uniform vec4 u_keep;\n"  // judgement line strokes: lower y, half, upper y, half (world)
        + "uniform vec4 u_keepX;\n" // their window left, right (world)
        + "uniform vec4 u_blur;\n"  // curtain: blur (reference units)
        + "const float OM = 1.0471976;\n"
        + "const vec3 FILL = vec3(0.0549, 0.1725, 0.5490);\n"
        + "const vec3 LINE = vec3(0.3843, 0.6667, 0.9569);\n"
        + "const vec3 EDGE = vec3(0.5020, 0.7608, 0.9804);\n"
        + "vec2 toLocal(vec2 world) {\n"
        // turn the offset back (rigid), then undo the field's stretch: the outline is stretched, then turned
        + "    vec2 d = world - u_xf.xy;\n"
        + "    vec2 r = vec2(u_rot.x * d.x + u_rot.y * d.y, u_rot.x * d.y - u_rot.y * d.x);\n"
        + "    return r / u_an.xy;\n"
        + "}\n"
        + "vec2 wobble(vec2 p, float t) {\n"
        + "    return vec2(9.0 * sin(p.y / 47.0 + 1.7 + OM * t) + 3.5 * sin((p.x + p.y) / 29.0 + 0.4 - 2.0 * OM * t),\n"
        + "        9.0 * sin(p.x / 53.0 + 0.6 - OM * t) + 3.5 * sin((p.x - p.y) / 31.0 + 2.1 + 2.0 * OM * t));\n"
        + "}\n"
        + "void edge(vec2 p, vec2 a, vec2 b, inout float d, inout float s) {\n"
        + "    vec2 e = b - a;\n"
        + "    vec2 w = p - a;\n"
        + "    float h = clamp(dot(w, e) / max(dot(e, e), 1e-6), 0.0, 1.0);\n"
        + "    d = min(d, length(w - e * h));\n"
        + "    if ((a.y > p.y) != (b.y > p.y) && p.x < e.x * (p.y - a.y) / e.y + a.x) s = -s;\n"
        + "}\n"
        // signed distance to the outline in reference units, positive inside (even-odd)
        + "float shapeSdf(vec2 p) {\n"
        + "    if (u_shape.x < 0.5) return u_shape.y - length(p);\n"
        + "    float d = 1e9;\n"
        + "    float s = -1.0;\n"
        + "    int n = int(u_shape.z + 0.5);\n"
        + "    for (int k = 0; k < 8; k++) {\n"
        + "        if (2 * k >= n) break;\n"
        + "        edge(p, u_pts[k].xy, u_pts[k].zw, d, s);\n"
        + "        if (2 * k + 1 >= n) break;\n"
        + "        edge(p, u_pts[k].zw, u_pts[k + 1].xy, d, s);\n"
        + "    }\n"
        + "    return s * d;\n"
        + "}\n"
        + "float caustic(vec2 q, float t) {\n"
        + "    vec2 w = q + vec2(9.0 * sin(q.y / 37.0 + 1.3 + OM * t) + 5.0 * sin((q.x + q.y) / 23.0 - 2.0 * OM * t),\n"
        + "        9.0 * sin(q.x / 41.0 + 0.4 - OM * t) + 5.0 * sin((q.x - q.y) / 29.0 + 2.0 * OM * t));\n"
        + "    vec2 g = floor(w / 72.0);\n"
        + "    float f1 = 1e9;\n"
        + "    float f2 = 1e9;\n"
        + "    for (int i = -1; i <= 1; i++) {\n"
        + "        for (int j = -1; j <= 1; j++) {\n"
        + "            vec2 c = g + vec2(float(i), float(j));\n"
        + "            vec2 h = fract(sin(vec2(dot(c, vec2(127.1, 311.7)), dot(c, vec2(269.5, 183.3))))\n"
        + "                * 43758.5453);\n"
        + "            float d = length(w - (c + 0.15 + 0.7 * h) * 72.0);\n"
        + "            if (d < f1) { f2 = f1; f1 = d; } else if (d < f2) { f2 = d; }\n"
        + "        }\n"
        + "    }\n"
        + "    float e = (f2 - f1) / 3.2;\n"
        + "    float v = 0.55 + 0.45 * (0.5 + 0.5 * sin(q.x / 90.0 + 2.0 * sin(q.y / 130.0) + OM * t));\n"
        + "    return clamp(exp(-e * e) * v, 0.0, 1.0);\n"
        + "}\n"
        + "vec2 causticWarp(vec2 q, float t) {\n"
        + "    return 6.5 * vec2(sin(q.y / 21.0 + 1.7 * sin(q.x / 47.0) + 2.0 * OM * t)\n"
        + "        + 0.5 * sin((q.x + q.y) / 17.0 - 3.0 * OM * t),\n"
        + "        sin(q.x / 27.0 + 1.3 * sin(q.y / 39.0) - 2.0 * OM * t)\n"
        + "        + 0.5 * sin((q.x - q.y) / 19.0 + 3.0 * OM * t));\n"
        + "}\n"
        // the veil at a world point: signed distance, pattern and where the water bends the view to (window px)
        + "float field(vec2 world, float t, out float pat, out vec2 off) {\n"
        + "    float ds = u_anim.y;\n"
        + "    vec2 p = toLocal(world);\n"
        + "    p += ds * wobble(p, t);\n"
        + "    float sdf = shapeSdf(p);\n"
        + "    vec2 o;\n"
        + "    if (u_shape.x < 0.5) {\n"
        + "        float r = length(p) + 1e-3;\n"
        + "        float x = r - 15.0 - 15.0 * t;\n"
        + "        float f = clamp((r - 8.0) / 18.0, 0.0, 1.0) * clamp((u_shape.y - r - 5.0) / 20.0, 0.0, 1.0);\n"
        + "        float c = cos(3.14159265 * x / 30.0);\n"
        + "        pat = pow(c * c, 10.0) * f * 0.532;\n"
        + "        o = 6.5 * sin(6.2831853 * x / 30.0) * f * p / r;\n"
        + "    } else {\n"
        + "        vec2 q = p + 100.0;\n"
        + "        pat = caustic(q, t) * 0.46;\n"
        + "        o = causticWarp(q, t);\n"
        + "    }\n"
        + "    o *= ds;\n"
        // next to a judgement line the view bends less (|o| stays under 14 ds), never as far as the line: no ghost
        // of it in the water
        + "    if (world.x >= u_keepX.x && world.x <= u_keepX.y) {\n"
        + "        float d = min(abs(world.y - u_keep.x) - u_keep.y, abs(world.y - u_keep.z) - u_keep.w);\n"
        + "        o *= clamp((d - 1.0) / (1.0 + 14.0 * ds * u_xf.w), 0.0, 1.0);\n"
        + "    }\n"
        + "    off = vec2(u_rot.x * o.x - u_rot.y * o.y, u_rot.y * o.x + u_rot.x * o.y) * u_xf.w * u_rot.zw;\n"
        + "    return sdf;\n"
        + "}\n"
        // the water skin as one premultiplied layer: fill, pattern, inner glow, crisp outline
        + "vec4 layers(float sdf, float pat, float cov, vec3 under, float pxs) {\n"
        + "    float nm = clamp((dot(under, vec3(85.0)) - 62.0) / 55.0, 0.0, 1.0);\n"
        + "    vec3 c = vec3(0.0);\n"
        + "    float al = 0.0;\n"
        + "    float a = 0.55 * cov * (1.0 - 0.8 * nm);\n"
        + "    c = c * (1.0 - a) + FILL * a; al = al * (1.0 - a) + a;\n"
        + "    a = pat * cov * (1.0 - 0.5 * nm);\n"
        + "    c = c * (1.0 - a) + LINE * a; al = al * (1.0 - a) + a;\n"
        + "    a = 0.16 * exp(-max(sdf, 0.0) / 22.0) * cov;\n"
        + "    c = c * (1.0 - a) + EDGE * a; al = al * (1.0 - a) + a;\n"
        + "    a = clamp(1.6 - abs(sdf * pxs - 1.0), 0.0, 1.0) * 0.8 * cov;\n"
        + "    c = c * (1.0 - a) + EDGE * a; al = al * (1.0 - a) + a;\n"
        + "    return vec4(c, al);\n"
        + "}\n"
        + "vec4 skinAt(vec2 frag, vec2 world, float t, float pxs) {\n"
        + "    float pat;\n"
        + "    vec2 off;\n"
        + "    float sdf = field(world, t, pat, off);\n"
        + "    float cov = clamp(sdf * pxs + 0.5, 0.0, 1.0) * u_shape.w;\n"
        + "    return layers(sdf, pat, cov, cap(frag + off), pxs);\n"
        + "}\n"
        + "float rnd(float row, float k) {\n"
        + "    return fract(sin(row * 12.9898 + u_anim.w * 78.233 + k * 37.719) * 43758.5453);\n"
        + "}\n"
        + "float hash(vec2 q) {\n"
        + "    return fract(sin(dot(q, vec2(12.9898, 78.233))) * 43758.5453);\n"
        + "}\n"
        // the curtain (mode 3): what is under it blurred, soft edge, thin streaks of water falling through it;
        // the pattern falls 160 units a second and repeats every 240, so the 6 s loop joins up
        + "vec4 curtain(vec2 frag, vec2 world, float t) {\n"
        + "    float pxs = u_xf.w * sqrt(abs(u_rot.z * u_rot.w));\n"
        + "    vec2 p = toLocal(world);\n"
        + "    p += u_anim.y * wobble(p, t);\n"
        + "    float cov = smoothstep(-6.0, 22.0, shapeSdf(p)) * u_shape.w;\n"
        + "    if (cov <= 0.0) return vec4(0.0);\n"
        + "    vec2 q = vec2(p.x, p.y + 160.0 * t);\n"
        + "    float col = floor(q.x / 9.0);\n"
        + "    float h1 = hash(vec2(col, 1.7));\n"
        + "    float h2 = hash(vec2(col, 5.3));\n"
        + "    vec2 c = frag + vec2(2.5 * sin(q.y / 23.0 + col * 1.7) * pxs, 0.0);\n"
        + "    float r = u_blur.x * pxs;\n"
        + "    float spin = 6.2831853 * hash(floor(frag));\n"
        + "    vec3 acc = cap(c);\n"
        + "    for (int i = 1; i <= 24; i++) {\n"
        + "        float fi = float(i);\n"
        + "        float a = fi * 2.3999632 + spin;\n"
        + "        acc += cap(c + vec2(cos(a), sin(a)) * r * sqrt(fi / 24.0));\n"
        + "    }\n"
        + "    vec3 b = mix(acc / 25.0, vec3(0.10, 0.30, 0.42), 0.14) * 1.05;\n"
        + "    float y = fract((q.y + h2 * 240.0) / 240.0);\n"
        + "    float line = smoothstep(0.12 + 0.25 * h1, 0.0, abs(fract(q.x / 9.0) - 0.5));\n"
        + "    float s = line * smoothstep(0.0, 0.12, y) * smoothstep(0.95, 0.4, y) * step(0.5, h1);\n"
        + "    b = mix(b, vec3(0.86, 0.95, 1.0), 0.22 * s);\n"
        + "    return vec4(b, cov);\n"
        + "}\n"
        + "void main() {\n"
        + "    vec2 frag = gl_FragCoord.xy;\n"
        + "    vec2 world = (frag - u_win.xy) * u_win.zw;\n"
        + "    float t = u_anim.x;\n"
        + "    if (u_anim.z > 2.5) {\n"
        + "        vec4 w = curtain(frag, world, t);\n"
        + "        if (w.a <= 0.0) discard;\n"
        + "        gl_FragColor = vec4(mix(cap(frag), w.rgb, w.a), 1.0);\n"
        + "        return;\n"
        + "    }\n"
        + "    if (u_anim.z > 0.5) {\n"
        + "        vec2 p = toLocal(world);\n"
        + "        p += u_anim.y * wobble(p, t);\n"
        + "        if ((shapeSdf(p) >= 0.0) != (u_anim.z > 1.5)) discard;\n"
        + "        gl_FragColor = v_color * texture2D(u_texture, v_texCoords);\n"
        + "        return;\n"
        + "    }\n"
        + "    if (world.x >= u_keepX.x && world.x <= u_keepX.y\n"
        + "        && (abs(world.y - u_keep.x) <= u_keep.y || abs(world.y - u_keep.z) <= u_keep.w)) discard;\n"
        + "    float pxs = u_xf.w * sqrt(abs(u_rot.z * u_rot.w));\n"
        + "    float pat;\n"
        + "    vec2 off;\n"
        + "    float sdf = field(world, t, pat, off);\n"
        + "    float cov = clamp(sdf * pxs + 0.5, 0.0, 1.0) * u_shape.w;\n"
        + "    vec3 under = cap(frag + off);\n"
        + "    vec3 reg = mix(cap(frag), under, cov);\n"
        + "    if (u_gl.x + u_gl.z + u_gl.w <= 0.0) {\n"
        + "        vec4 s = layers(sdf, pat, cov, under, pxs);\n"
        + "        if (cov <= 0.0 && s.a < 0.002) discard;\n"
        + "        gl_FragColor = vec4(reg * (1.0 - s.a) + s.rgb, 1.0);\n"
        + "        return;\n"
        + "    }\n"
        // glitch: rows of the skin slide sideways and dim, red and blue part; what is under stays steady
        + "    float ry = (world.y - u_xf.y) * u_xf.z;\n"
        + "    float blk = floor(ry / 32.0);\n"
        + "    float m = 1.0 + floor(rnd(blk, 0.0) * 4.0);\n"
        + "    float row = blk * 4.0 + floor(fract(ry / 32.0) * m);\n"
        + "    float sh = 0.0;\n"
        + "    if (rnd(row, 1.0) < u_gl.x) sh = (rnd(row, 2.0) < 0.5 ? -1.0 : 1.0) * (3.0 + rnd(row, 3.0) * u_gl.y);\n"
        + "    float dim = rnd(row, 4.0) < u_gl.w ? 0.2 : 1.0;\n"
        + "    float sw = sh * u_xf.w;\n"
        + "    float sp = u_gl.z * u_xf.w;\n"
        + "    vec4 sg = skinAt(frag - vec2(sw * u_rot.z, 0.0), world - vec2(sw, 0.0), t, pxs) * dim;\n"
        + "    vec4 sr = sg;\n"
        + "    vec4 sb = sg;\n"
        + "    if (sp > 0.0) {\n"
        + "        sr = skinAt(frag - vec2((sw + sp) * u_rot.z, 0.0), world - vec2(sw + sp, 0.0), t, pxs) * dim;\n"
        + "        sb = skinAt(frag - vec2((sw - sp) * u_rot.z, 0.0), world - vec2(sw - sp, 0.0), t, pxs) * dim;\n"
        + "    }\n"
        + "    if (cov <= 0.0 && max(sg.a, max(sr.a, sb.a)) < 0.002) discard;\n"
        + "    gl_FragColor = vec4(reg.r * (1.0 - sr.a) + sr.r, reg.g * (1.0 - sg.a) + sg.g,\n"
        + "        reg.b * (1.0 - sb.a) + sb.b, 1.0);\n"
        + "}\n";

    /** The glitch mark of a veiled finger (veil_mock5 glitch_mark): lens, swirl, torn rows and blocks, split. */
    private static final String TOUCH_FRAGMENT = HEAD
        + "uniform vec4 u_pt;\n" // finger (window px), radius (px), radius / 160
        + "uniform vec4 u_tl;\n" // t (s), life, pattern seed, row drop chance
        + "const vec3 WHITE = vec3(0.7843, 0.9098, 1.0);\n"
        + "const vec3 TINT = vec3(0.4314, 0.7059, 1.0);\n"
        + "float rnd(float a, float k) {\n"
        + "    return fract(sin(a * 12.9898 + u_tl.z * 78.233 + k * 37.719) * 43758.5453);\n"
        + "}\n"
        + "float region(vec2 l) {\n"
        + "    return pow(clamp(1.15 - (length(l) + 1e-3) / (u_pt.z * 1.12), 0.0, 1.0), 0.7);\n"
        + "}\n"
        // the picture under the mark: a rippling lens and a swirl, strongest in the middle
        + "vec3 lens(vec2 l) {\n"
        + "    float k = u_pt.w;\n"
        + "    float t = u_tl.x;\n"
        + "    float rho = length(l) + 1e-3;\n"
        + "    float q = rho / (u_pt.z * 1.08);\n"
        + "    float inside = clamp(1.0 - q * q, 0.0, 1.0);\n"
        + "    float d = (12.0 * sin(rho / (9.0 * k) - 14.0 * t) + 6.0 * sin(rho / (4.3 * k) + 9.0 * t))\n"
        + "        * inside * k;\n"
        + "    float sw = 0.35 * inside * sin(3.0 * atan(l.y, l.x) + 6.0 * t);\n"
        + "    float c = cos(sw);\n"
        + "    float s = sin(sw);\n"
        + "    return cap(u_pt.xy + vec2(l.x * c - l.y * s, l.x * s + l.y * c) + d * l / rho);\n"
        + "}\n"
        // the mark itself on wobbling coordinates: faint fill, inner ring, ring, centre dot (premultiplied)
        + "vec4 ring(vec2 l) {\n"
        + "    float r = u_pt.z;\n"
        + "    float k = u_pt.w;\n"
        + "    float t = u_tl.x;\n"
        + "    vec2 u = l + k * vec2(13.0 * sin(l.y / (21.0 * k) + 11.0 * t)\n"
        + "        + 4.0 * sin((l.x + l.y) / (15.0 * k) - 17.0 * t),\n"
        + "        13.0 * sin(l.x / (25.0 * k) - 9.0 * t) + 4.0 * sin((l.x - l.y) / (16.0 * k) + 13.0 * t));\n"
        + "    float q = length(u);\n"
        + "    vec3 c = vec3(0.0);\n"
        + "    float al = 0.0;\n"
        + "    float a = clamp(r - q + 0.5, 0.0, 1.0) * 0.16;\n"
        + "    c = c * (1.0 - a) + TINT * a; al = al * (1.0 - a) + a;\n"
        + "    a = clamp(1.0 - abs(q - r * 0.62) / max(2.2 * k, 1.3), 0.0, 1.0) * 0.45;\n"
        + "    c = c * (1.0 - a) + WHITE * a; al = al * (1.0 - a) + a;\n"
        + "    a = clamp(1.0 - abs(q - r) / max(6.5 * k, 2.6), 0.0, 1.0) * 0.95;\n"
        + "    c = c * (1.0 - a) + WHITE * a; al = al * (1.0 - a) + a;\n"
        + "    a = clamp(20.0 * k - q + 0.5, 0.0, 1.0) * 0.9;\n"
        + "    c = c * (1.0 - a) + WHITE * a; al = al * (1.0 - a) + a;\n"
        + "    return vec4(c, al);\n"
        + "}\n"
        // one colour channel's view at l: displaced blocks, then the torn row, then picture and mark
        + "vec3 torn(vec2 l, float shift, float drop) {\n"
        + "    float k = u_pt.w;\n"
        + "    float P = u_pt.z + 90.0 * k;\n"
        + "    float nb = 3.0 + floor(rnd(1.0, 50.0) * 4.0);\n"
        + "    vec2 b = l;\n"
        + "    for (int j = 0; j < 6; j++) {\n"
        + "        float fj = float(j);\n"
        + "        if (fj >= nb) break;\n"
        + "        float bw = max(6.0, (24.0 + 86.0 * rnd(fj, 51.0)) * k);\n"
        + "        float bh = max(3.0, (6.0 + 20.0 * rnd(fj, 52.0)) * k);\n"
        + "        float by = -P + rnd(fj, 53.0) * max(1.0, 2.0 * P - bh);\n"
        + "        float bx = -P + rnd(fj, 54.0) * max(1.0, 2.0 * P - bw);\n"
        + "        float sx = clamp(bx + (rnd(fj, 55.0) * 80.0 - 40.0) * k, -P, P - bw);\n"
        + "        if (l.x >= bx && l.x < bx + bw && l.y >= by && l.y < by + bh) b.x = l.x + sx - bx;\n"
        + "    }\n"
        + "    b.x -= shift;\n"
        + "    vec3 u = mix(cap(u_pt.xy + b), lens(b), region(b));\n"
        + "    vec4 s = ring(b) * drop;\n"
        + "    return u * (1.0 - s.a) + s.rgb;\n"
        + "}\n"
        + "void main() {\n"
        + "    vec2 frag = gl_FragCoord.xy;\n"
        + "    vec2 l = frag - u_pt.xy;\n"
        + "    float m = clamp(region(l) * u_tl.y * 1.6, 0.0, 1.0);\n"
        + "    if (m <= 0.002) discard;\n"
        + "    float k = u_pt.w;\n"
        + "    float rh = 27.0 * k;\n"
        + "    float blk = floor(l.y / rh);\n"
        + "    float n = 1.0 + floor(rnd(blk, 60.0) * 4.0);\n"
        + "    float row = blk * 4.0 + floor(fract(l.y / rh) * n);\n"
        + "    float shift = 0.0;\n"
        + "    if (rnd(row, 61.0) < 0.5) shift = (rnd(row, 62.0) < 0.5 ? -1.0 : 1.0)\n"
        + "        * max(2.0, (8.0 + 56.0 * rnd(row, 63.0)) * k);\n"
        + "    float drop = rnd(row, 64.0) < u_tl.w ? 0.05 : 1.0;\n"
        + "    float split = max(3.0, (8.0 + 7.0 * rnd(2.0, 65.0)) * k);\n"
        + "    vec3 o = vec3(torn(l - vec2(split, 0.0), shift, drop).r, torn(l, shift, drop).g,\n"
        + "        torn(l + vec2(split, 0.0), shift, drop).b);\n"
        + "    gl_FragColor = vec4(mix(cap(frag), o, m), 1.0);\n"
        + "}\n";

    // the veil uniforms, in this order
    private static final String[] VEIL_UNIFORMS = {"u_cap", "u_rect", "u_win", "u_xf", "u_rot", "u_shape", "u_pts",
        "u_anim", "u_gl", "u_keep", "u_keepX", "u_blur", "u_an"};
    private static final int CAP = 0, RECT = 1, WIN = 2, XF = 3, ROT = 4, SHAPE = 5, PTS = 6, ANIM = 7, GL = 8,
        KEEP = 9, KEEP_X = 10, BLUR = 11, AN = 12;
    private static final String[] TOUCH_UNIFORMS = {"u_cap", "u_rect", "u_pt", "u_tl"};
    private static final int T_PT = 2, T_TL = 3;
    private static final Color FILL = new Color(14 / 255f, 44 / 255f, 140 / 255f, 1f);
    private static final Color EDGE = new Color(128 / 255f, 194 / 255f, 250 / 255f, 1f);

    private final Draw draw;
    /** True for the curtains (水帘): blur only, no skin, no glitch. */
    private final boolean curtain;
    private ShaderProgram veilShader;
    private ShaderProgram touchShader;
    private boolean veilFailed;
    private boolean touchFailed;
    private final int[] veilLoc = new int[VEIL_UNIFORMS.length];
    private final int[] touchLoc = new int[TOUCH_UNIFORMS.length];
    private Texture capture;
    private int capW;
    private int capH;
    private final IntBuffer viewport = BufferUtils.newIntBuffer(16);
    private final Matrix4 combined = new Matrix4();
    private int vpX;
    private int vpY;
    private int vpW;
    private int vpH;
    /** Pixels per world unit, and the window pixel of world (0, 0). */
    private float pxX;
    private float pxY;
    private float winX;
    private float winY;
    /** The copied rectangle (window pixel centres) the shader clamps its samples to. */
    private float rectX0;
    private float rectY0;
    private float rectX1;
    private float rectY1;
    private final float[] pts = new float[36];

    // this frame's showing veils
    private int count;
    private int clip = -1;
    private float time;
    private Veil[] ref = new Veil[0];
    private float[] cx = new float[0];
    private float[] cy = new float[0];
    private float[] cos = new float[0];
    private float[] sin = new float[0];
    private float[] alpha = new float[0];
    private float[] dist = new float[0];
    private float[] reach = new float[0];
    private float[] glSlide = new float[0];
    private float[] glReach = new float[0];
    private float[] glSplit = new float[0];
    private float[] glDim = new float[0];
    private float[] glSeed = new float[0];
    private float[] blur = new float[0];
    private float scale = 1f;

    /**
     * @return x of the water wobble at local point (lx, ly) (reference units) at second {@code t} of the loop: two
     *         sine layers of 9 and 3.5 units, the shader's {@code wobble} (notes bend with it, CHART_FORMAT_v4 §6.11)
     */
    static float wobbleX(float lx, float ly, float t) {
        return 9f * MathUtils.sin(ly / 47f + 1.7f + OM * t)
            + 3.5f * MathUtils.sin((lx + ly) / 29f + 0.4f - 2f * OM * t);
    }

    /** @return y of the water wobble at local point (lx, ly) at second {@code t} ({@link #wobbleX}). */
    static float wobbleY(float lx, float ly, float t) {
        return 9f * MathUtils.sin(lx / 53f + 0.6f - OM * t)
            + 3.5f * MathUtils.sin((lx - ly) / 31f + 2.1f + 2f * OM * t);
    }

    /** @return {@code now} folded into one loop of the water's motion (0..{@link #LOOP_SEC}). */
    static float loopTime(float now) {
        float t = now % LOOP_SEC;
        return t < 0f ? t + LOOP_SEC : t;
    }

    /** Creates the renderer; {@code draw} draws the plain stand-in when a shader does not compile. */
    public VeilRenderer(Draw draw) {
        this(draw, false);
    }

    /** @param curtain true to draw curtains (水帘, CHART_FORMAT §6.1): they blur what is under them */
    public VeilRenderer(Draw draw, boolean curtain) {
        this.draw = draw;
        this.curtain = curtain;
    }

    /**
     * Compiles the shaders and makes the capture texture now, so the first veil of a run costs no stall: compiling
     * the fragment shader in the first drawn frame let the song clock (nanoTime-anchored) jump past the first note's
     * fall — seen only on the first run of a session and only in a chart with a curtain (Kelvin 2026-11-04). Call
     * while the screen is covered; cheap after the first call.
     */
    public void prewarm() {
        veilProgram();
        touchProgram();
        if (Gdx.gl == null) return;
        viewport.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
        vpW = viewport.get(2);
        vpH = viewport.get(3);
        if (vpW > 0 && vpH > 0) captureReady();
    }

    /**
     * Works out which veils show at {@code beat} and how; call once a frame before {@link #clipBegin} and
     * {@link #draw}. Allocates only when a chart with more veils than before comes.
     *
     * @param now  seconds of the run's own clock (the veils' motion stops while paused)
     * @param fade the chart's opacity (the resume countdown fades it in)
     * @return true when at least one veil shows
     */
    public boolean prepare(Veil[] veils, float beat, float now, float fade) {
        count = 0;
        clip = -1;
        time = loopTime(now);
        if (veils == null || veils.length == 0 || !(fade > 0f)) return false;
        room(veils.length);
        scale = Field.shapeScale();
        boolean reduce = GlitchText.reduceFlashing();
        float best = 0f;
        for (int i = 0; i < veils.length; i++) {
            Veil v = veils[i];
            float a = v.opacityAt(beat) * fade;
            if (!(a > 0.003f)) continue;
            int k = count++;
            ref[k] = v;
            cx[k] = Field.x(v.xAt(beat));
            cy[k] = Field.y(v.yAt(beat));
            double th = Math.toRadians(v.rotationAt(beat));
            cos[k] = (float) Math.cos(th);
            sin[k] = (float) Math.sin(th);
            alpha[k] = Math.min(1f, a);
            dist[k] = v.distortionAt(beat);
            glSlide[k] = 0f;
            glReach[k] = 0f;
            glSplit[k] = 0f;
            glDim[k] = 0f;
            glSeed[k] = 0f;
            float margin = 4f + 12.5f * dist[k];
            blur[k] = curtain ? v.blurAt(beat) : 0f;
            NoteGlitch g = v.glitches().isEmpty() ? null : NoteGlitch.activeAt(v.glitches(), beat);
            float in = g == null ? 0f : g.intensityAt(beat);
            if (in > 0.001f && !curtain) {
                glSlide[k] = Math.min(1f, (0.25f + 0.6f * in) * g.slice());
                glReach[k] = 1f + 40f * in * g.slice();
                glSplit[k] = (2f + 10f * in) * g.split();
                glDim[k] = reduce ? 0f : GlitchCapture.FLICKER_CHANCE * in * g.flicker();
                int seed = GlitchCapture.seed(i, g.step(beat, reduce ? 0.5f : 1f), Float.floatToIntBits(g.from()));
                glSeed[k] = ((seed >>> 1) % 9973) * 0.1f;
                margin += 3f + glReach[k] + glSplit[k];
            }
            // the quad's reach in reference units: draw() multiplies it by the field's x and y stretch separately
            reach[k] = v.extent() + margin;
            float weight = v.extent() * v.extent() * a;
            if (weight > best) {
                best = weight;
                clip = k;
            }
        }
        return count > 0;
    }

    private void room(int n) {
        if (ref.length >= n) return;
        ref = new Veil[n];
        cx = new float[n];
        cy = new float[n];
        cos = new float[n];
        sin = new float[n];
        alpha = new float[n];
        dist = new float[n];
        reach = new float[n];
        glSlide = new float[n];
        glReach = new float[n];
        glSplit = new float[n];
        glDim = new float[n];
        glSeed = new float[n];
        blur = new float[n];
    }

    /**
     * Sends what {@code b} draws next only outside ({@code inside} false) or only inside the most prominent showing
     * veil (largest by size and opacity), as drawn this frame: the HUD beneath the notes is drawn outside it before
     * the notes and inside it again after {@link #draw}, so it stays readable above the water. Ends with
     * {@link #clipEnd}.
     *
     * @return false when there is nothing to clip (then draw normally and do not call {@link #clipEnd})
     */
    public boolean clipBegin(SpriteBatch b, boolean inside) {
        if (clip < 0 || !veilProgram()) return false;
        frame(b);
        b.setShader(veilShader);
        uniforms(clip);
        veilShader.setUniformf(veilLoc[ANIM], time, dist[clip], inside ? 2f : 1f, 0f);
        return true;
    }

    /** Back to the batch's own shader after {@link #clipBegin} (flushes the clipped draws). */
    public void clipEnd(SpriteBatch b) {
        b.setShader(null);
    }

    /** Draws the prepared shapes keeping nothing crisp under them: the curtains blur the judgement lines too. */
    public void draw(SpriteBatch b) {
        draw(b, 0f, -1f, 0f, -1f, 0f, 0f);
    }

    /**
     * Draws every showing veil over what {@code b} has drawn so far (flushes first). The judgement line strokes
     * (centre y and half thickness, world units, across {@code wl..wr}) stay as they are.
     */
    public void draw(SpriteBatch b, float lowerY, float lowerHalf, float upperY, float upperHalf, float wl, float wr) {
        if (count == 0) return;
        if (!veilProgram()) {
            if (!curtain) plain();
            return;
        }
        b.flush();
        frame(b);
        if (!captureReady()) return;
        b.setShader(veilShader);
        b.disableBlending();
        veilShader.setUniformf(veilLoc[KEEP], lowerY, lowerHalf, upperY, upperHalf);
        veilShader.setUniformf(veilLoc[KEEP_X], wl, wr, 0f, 0f);
        for (int k = 0; k < count; k++) {
            float rx = reach[k] * Field.kx();
            float ry = reach[k] * Field.ky();
            // the copy also covers where the water bends the view from
            float bend = 14f * dist[k] + 2f + (curtain ? blur[k] + 4f : 0f);
            float bx = bend * Field.kx();
            float by = bend * Field.ky();
            if (!copy(b, cx[k] - rx - bx, cy[k] - ry - by, cx[k] + rx + bx, cy[k] + ry + by, veilLoc)) continue;
            uniforms(k);
            veilShader.setUniformf(veilLoc[ANIM], time, dist[k], curtain ? 3f : 0f, glSeed[k]);
            veilShader.setUniformf(veilLoc[GL], glSlide[k], glReach[k], glSplit[k], glDim[k]);
            veilShader.setUniformf(veilLoc[BLUR], blur[k], 0f, 0f, 0f);
            b.draw(capture, cx[k] - rx, cy[k] - ry, 2f * rx, 2f * ry);
            b.flush();
        }
        b.enableBlending();
        b.setShader(null);
    }

    /**
     * Draws the glitch mark of a finger a veil made invalid (Kelvin 17:01): a ring of {@code radius} world units
     * (half a note's width) with a faint fill and a centre dot, torn by heavy distortion, while the picture under
     * it bends and glitches too; the pattern changes 16 times a second (8 with "reduce flashing", which also keeps
     * rows from dropping out). Flushes first.
     *
     * @param life    1 while the finger is down, fading to 0 after it lifts
     * @param now     screen seconds (the mark moves while paused too)
     * @param pointer the finger, for its own pattern
     */
    public void drawTouch(SpriteBatch b, float x, float y, float radius, float life, float now, int pointer) {
        if (!(life > 0.003f) || !(radius > 0f)) return;
        if (!touchProgram()) {
            plainTouch(x, y, radius, life, now);
            return;
        }
        b.flush();
        frame(b);
        if (!captureReady()) return;
        float ux = Math.abs(pxX);
        float uy = Math.abs(pxY);
        if (!(ux > 0f) || !(uy > 0f)) return;
        float rpx = radius * ux;
        float k = rpx / MARK_TUNED_R;
        float half = rpx + 90f * k;
        float pad = half + 140f * k;
        if (!copy(b, x - pad / ux, y - pad / uy, x + pad / ux, y + pad / uy, touchLoc)) return;
        b.setShader(touchShader);
        b.disableBlending();
        setCapture(touchShader, touchLoc);
        boolean reduce = GlitchText.reduceFlashing();
        int step = (int) Math.floor(now * (reduce ? 8f : 16f));
        int seed = GlitchCapture.seed(pointer, step, 0x5EED);
        touchShader.setUniformf(touchLoc[T_PT], winX + x * pxX, winY + y * pxY, rpx, k);
        touchShader.setUniformf(touchLoc[T_TL], now % 600f, Math.min(1f, life), ((seed >>> 1) % 9973) * 0.1f,
            reduce ? 0f : 0.12f);
        b.draw(capture, x - half / ux, y - half / uy, 2f * half / ux, 2f * half / uy);
        b.flush();
        b.enableBlending();
        b.setShader(null);
    }

    // ------------------------------------------------------------------ shared

    /** Reads the viewport and the batch's matrices: where world points land in window pixels. */
    private void frame(SpriteBatch b) {
        viewport.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
        vpX = viewport.get(0);
        vpY = viewport.get(1);
        vpW = viewport.get(2);
        vpH = viewport.get(3);
        combined.set(b.getProjectionMatrix()).mul(b.getTransformMatrix());
        float[] m = combined.val;
        pxX = m[Matrix4.M00] * 0.5f * vpW;
        pxY = m[Matrix4.M11] * 0.5f * vpH;
        winX = vpX + (m[Matrix4.M03] + 1f) * 0.5f * vpW;
        winY = vpY + (m[Matrix4.M13] + 1f) * 0.5f * vpH;
    }

    /** Makes the capture texture the size of the viewport (again only when that size changes). */
    private boolean captureReady() {
        if (vpW <= 0 || vpH <= 0) return false;
        if (capture != null && capW == vpW && capH == vpH) return true;
        if (capture != null) capture.dispose();
        capW = vpW;
        capH = vpH;
        // managed, so a lost GL context gets it back (its pixels are copied again at every use anyway)
        capture = new Texture(new GLOnlyTextureData(capW, capH, 0, GL20.GL_RGB, GL20.GL_RGB, GL20.GL_UNSIGNED_BYTE) {
            @Override
            public boolean isManaged() {
                return true;
            }
        });
        capture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        capture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return true;
    }

    /**
     * Copies the window pixels under the world box (x0, y0)–(x1, y1), clipped to the viewport, into the capture
     * texture at the same place, and tells the shader with locations {@code loc} where it is.
     *
     * @return false when the box is off screen
     */
    private boolean copy(SpriteBatch b, float x0, float y0, float x1, float y1, int[] loc) {
        float ax = winX + x0 * pxX;
        float bx = winX + x1 * pxX;
        float ay = winY + y0 * pxY;
        float by = winY + y1 * pxY;
        int rx0 = Math.max(vpX, (int) Math.floor(Math.min(ax, bx)));
        int rx1 = Math.min(vpX + vpW, (int) Math.ceil(Math.max(ax, bx)));
        int ry0 = Math.max(vpY, (int) Math.floor(Math.min(ay, by)));
        int ry1 = Math.min(vpY + vpH, (int) Math.ceil(Math.max(ay, by)));
        if (rx1 <= rx0 || ry1 <= ry0) return false;
        capture.bind();
        Gdx.gl.glCopyTexSubImage2D(GL20.GL_TEXTURE_2D, 0, rx0 - vpX, ry0 - vpY, rx0, ry0, rx1 - rx0, ry1 - ry0);
        ShaderProgram s = loc == veilLoc ? veilShader : touchShader;
        rectX0 = rx0 + 0.5f;
        rectY0 = ry0 + 0.5f;
        rectX1 = rx1 - 0.5f;
        rectY1 = ry1 - 0.5f;
        if (b.getShader() == s) setCapture(s, loc);
        return true;
    }

    private void setCapture(ShaderProgram s, int[] loc) {
        s.setUniformf(loc[CAP], vpX, vpY, 1f / capW, 1f / capH);
        s.setUniformf(loc[RECT], rectX0, rectY0, rectX1, rectY1);
    }

    /** The uniforms of showing veil k (its shape and where it is). */
    private void uniforms(int k) {
        Veil v = ref[k];
        ShaderProgram s = veilShader;
        if (capture != null) setCapture(s, veilLoc);
        s.setUniformf(veilLoc[WIN], winX, winY, 1f / pxX, 1f / pxY);
        s.setUniformf(veilLoc[XF], cx[k], cy[k], 1f / scale, scale);
        s.setUniformf(veilLoc[AN], Field.kx(), Field.ky(), 0f, 0f);
        s.setUniformf(veilLoc[ROT], cos[k], sin[k], pxX, pxY);
        int n = v.shape() == Veil.Shape.CIRCLE ? 0 : v.pointCount();
        s.setUniformf(veilLoc[SHAPE], n == 0 ? 0f : 1f, v.radius(), n, alpha[k]);
        if (n > 0) {
            for (int i = 0; i < n; i++) {
                pts[2 * i] = v.pointX(i);
                pts[2 * i + 1] = v.pointY(i);
            }
            pts[2 * n] = pts[0];
            pts[2 * n + 1] = pts[1];
            s.setUniform4fv(veilLoc[PTS], pts, 0, pts.length);
        }
        s.setUniformf(veilLoc[GL], 0f, 0f, 0f, 0f);
    }

    private boolean veilProgram() {
        if (veilShader == null && !veilFailed) {
            veilShader = compile(VEIL_FRAGMENT, VEIL_UNIFORMS, veilLoc);
            veilFailed = veilShader == null;
        }
        return veilShader != null;
    }

    private boolean touchProgram() {
        if (touchShader == null && !touchFailed) {
            touchShader = compile(TOUCH_FRAGMENT, TOUCH_UNIFORMS, touchLoc);
            touchFailed = touchShader == null;
        }
        return touchShader != null;
    }

    private static ShaderProgram compile(String fragment, String[] names, int[] loc) {
        ShaderProgram p = new ShaderProgram(VERTEX, fragment);
        if (!p.isCompiled()) {
            if (Gdx.app != null) Gdx.app.error("VeilRenderer", p.getLog());
            p.dispose();
            return null;
        }
        for (int i = 0; i < names.length; i++) loc[i] = p.fetchUniformLocation(names[i], false);
        return p;
    }

    /** Stand-in when the veil shader does not compile: a tinted disc or the outline, no refraction. */
    private void plain() {
        for (int k = 0; k < count; k++) {
            Veil v = ref[k];
            float a = alpha[k];
            if (v.shape() == Veil.Shape.CIRCLE) {
                float r = v.radius() * scale;
                draw.disc(cx[k], cy[k], r, FILL, 0.55f * a);
                draw.circle(cx[k], cy[k], r, 2.5f, EDGE, 0.8f * a);
                continue;
            }
            int n = v.pointCount();
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                draw.line(px(k, v.pointX(i), v.pointY(i)), py(k, v.pointX(i), v.pointY(i)),
                    px(k, v.pointX(j), v.pointY(j)), py(k, v.pointX(j), v.pointY(j)), 2.5f, EDGE, 0.8f * a);
            }
        }
    }

    private float px(int k, float lx, float ly) {
        return cx[k] + (cos[k] * lx - sin[k] * ly) * Field.kx();
    }

    private float py(int k, float lx, float ly) {
        return cy[k] + (sin[k] * lx + cos[k] * ly) * Field.ky();
    }

    /** Stand-in mark when its shader does not compile: a light-blue ring jittering 16 times a second. */
    private void plainTouch(float x, float y, float radius, float life, float now) {
        int step = (int) Math.floor(now * 16f);
        float jx = ((step * 37) % 7 - 3) * 2f;
        float jy = ((step * 53) % 7 - 3) * 2f;
        draw.disc(x, y, radius, EDGE, 0.16f * life);
        draw.circle(x + jx, y + jy, radius, 5f, EDGE, 0.85f * life);
        draw.disc(x, y, radius * 0.12f, EDGE, 0.9f * life);
    }

    @Override
    public void dispose() {
        if (capture != null) capture.dispose();
        capture = null;
        if (veilShader != null) veilShader.dispose();
        if (touchShader != null) touchShader.dispose();
        veilShader = null;
        touchShader = null;
    }
}
