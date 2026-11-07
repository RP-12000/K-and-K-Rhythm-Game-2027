package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.NoteDistortion;
import org.kelvinizer.elements.NoteGlitch;
import org.kelvinizer.elements.Stroke;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Ui;

/**
 * 自定义线条的画法：表演用的直线或曲线，像悬浮键一样摆在屏幕上，带一圈柔光、圆头；可以画入（笔尖发光）、收回、改透明度，
 * 扭曲时像水一样晃，故障时断成一段段横跳、带红蓝色散。只是画面，从不判定。
 *
 * <p>Draws the custom lines (自定义线条, CHART_FORMAT §13; the approved mock-up v4, Kelvin 2026-11-06 18:44) for
 * {@link NoteRenderer}: those under the notes before the TIDE STREAM bands, those that ask to be in front
 * ({@link Stroke#front}) after the floating notes; in the normal blend, in file order. A line is traced once per chart
 * ({@link Stroke#trace}, points {@link #STEP} reference units apart) and placed like a floating note: x through the
 * lines' camera, y with the field's stretch, its width × {@link FloatSpace#scale}. It shows the part between its
 * taken-back start and its drawn-in end ({@link Stroke#tailAt}, {@link Stroke#headAt}): a soft glow four times its
 * width under it, the line with round ends and, while it is drawn in, a glowing pen tip. Distortion moves its points
 * with the veils' water wobble ({@link VeilRenderer#wobbleX}), strength as a note's. A glitch breaks it into runs of
 * {@link #RUN_MIN}–{@link #RUN_MAX} points that jump sideways as a note's strips do (chance
 * {@code (0.25 + 0.6·intensity)·slice}, up to {@code 4 + 40·intensity·slice} units), a few of them gone, with a red
 * and a blue copy {@code (2 + 10·intensity)·split} away under it, or dims it to 20 % ({@code flicker}); the pattern is
 * a pure function of the segment's step, so it holds still while paused. Never judged. Nothing allocates per frame
 * once a chart's lines are traced.
 */
final class StrokeRenderer {
    /** Reference units between two traced points. */
    static final float STEP = 8f;
    /** A torn line's runs (points), and their share a tear leaves out (of the chance a run jumps). */
    static final int RUN_MIN = 3, RUN_MAX = 14;
    static final float RUN_GAP = 0.15f;
    /** The glow's half width in line widths, and its opacity on the line. */
    static final float GLOW_W = 2f, GLOW_A = 0.16f;
    /** Pieces of a round end. */
    private static final int CAP_PIECES = 6;

    private final Draw draw;
    private final Texture atlas;
    private final float coreU, coreV;
    private final float[] verts = new float[20];
    private final Color tint = new Color();

    /** The chart the lines were traced for; per line its traced points (reference) and the length at each. */
    private Chart seen;
    private float[][] traced = new float[0][];
    private float[][] lengths = new float[0][];

    // the drawn part of the line in hand (world units): points, their normals, and a torn line's runs
    private float[] wx = new float[0], wy = new float[0], nx = new float[0], ny = new float[0];
    private int count;
    private int[] runFrom = new int[0], runTo = new int[0];
    private float[] runDx = new float[0], runDy = new float[0];
    private int runs;
    private int rng;

    StrokeRenderer(Draw draw) {
        this.draw = draw;
        this.atlas = draw.atlasTexture();
        TextureRegion core = new TextureRegion(draw.white(), 1, 1, 2, 2);
        coreU = 0.5f * (core.getU() + core.getU2());
        coreV = 0.5f * (core.getV() + core.getV2());
    }

    /**
     * Draws the custom lines under the notes ({@code front} false) or over them (true) at the frame's beat;
     * {@code t} is the water's loop time ({@link VeilRenderer#loopTime}). The batch colour is left as it was.
     */
    void draw(GameEngine e, float t, boolean front) {
        Chart chart = e.chart();
        if (chart == null || chart.strokes().length == 0) return;
        trace(chart);
        Stroke[] all = chart.strokes();
        float beat = e.visualBeat();
        LaneLayout lay = e.layout();
        float sc = FloatSpace.scale(lay);
        float px = Math.max(Ui.worldPerPx(), 1e-3f);
        Batch b = draw.batch();
        float old = b.getPackedColor();
        for (int k = 0; k < all.length; k++) {
            Stroke s = all[k];
            if (s.front() != front) continue;
            float a = s.opacityAt(beat);
            if (a <= NoteRenderer.MIN_ALPHA) continue;
            float[] len = lengths[k];
            float total = len[len.length - 1];
            float s1 = s.headAt(beat) * total, s0 = s.tailAt(beat) * total;
            if (s1 - s0 < 0.5f) continue;
            float bend = s.distortions().isEmpty() ? 0f
                : NoteDistortion.strengthAt(s.distortions(), beat, NoteDistortion.NOTE_BASE);
            part(lay, traced[k], len, s0, s1, bend * sc, t);
            if (count < 2) continue;
            boolean loop = s.closed() && s0 <= 0f && s1 >= total;
            line(b, k, s, a, sc, px, beat, loop, s1 < total);
        }
        b.setPackedColor(old);
    }

    /** @return an upper bound of the quads {@link #draw} submits for the lines that show at the frame's beat */
    int quads(GameEngine e) {
        Chart chart = e.chart();
        if (chart == null || chart.strokes().length == 0) return 0;
        trace(chart);
        Stroke[] all = chart.strokes();
        float beat = e.visualBeat();
        int q = 0;
        for (int k = 0; k < all.length; k++) {
            if (!all[k].showsAt(beat)) continue;
            // per piece: the line (3), its glow (2), a red and a blue copy (3 each); ends, pen tip
            q += 11 * (traced[k].length / 2) + 4 * CAP_PIECES + 8;
        }
        return q;
    }

    /** Traces a new chart's lines once ({@link Stroke#trace}) and sizes the scratch for the longest. */
    private void trace(Chart chart) {
        if (chart == seen) return;
        seen = chart;
        Stroke[] all = chart.strokes();
        traced = new float[all.length][];
        lengths = new float[all.length][];
        int most = 4;
        for (int k = 0; k < all.length; k++) {
            float[] p = all[k].trace(STEP);
            float[] len = new float[p.length / 2];
            for (int i = 1; i < len.length; i++) {
                len[i] = len[i - 1] + (float) Math.hypot(p[2 * i] - p[2 * i - 2], p[2 * i + 1] - p[2 * i - 1]);
            }
            traced[k] = p;
            lengths[k] = len;
            most = Math.max(most, len.length + 2);
        }
        if (wx.length < most) {
            wx = new float[most];
            wy = new float[most];
            nx = new float[most];
            ny = new float[most];
            int r = most / RUN_MIN + 2;
            runFrom = new int[r];
            runTo = new int[r];
            runDx = new float[r];
            runDy = new float[r];
        }
    }

    /**
     * Puts the part of a traced line from length s0 to s1 into {@link #wx}, {@link #wy} in world units, moved by the
     * water wobble × {@code bend} (world units per reference unit times the distortion's strength).
     */
    private void part(LaneLayout lay, float[] pts, float[] len, float s0, float s1, float bend, float t) {
        count = 0;
        int n = len.length;
        int i = 1;
        while (i < n - 1 && len[i] < s0) i++;
        at(lay, pts, len, i, s0, bend, t);
        while (i < n && len[i] < s1) {
            if (len[i] > s0) add(lay, pts[2 * i], pts[2 * i + 1], bend, t);
            i++;
        }
        at(lay, pts, len, Math.min(i, n - 1), s1, bend, t);
    }

    /** Adds the point at length {@code s} on the piece that ends at traced point i. */
    private void at(LaneLayout lay, float[] pts, float[] len, int i, float s, float bend, float t) {
        float l0 = len[i - 1], l1 = len[i];
        float u = l1 > l0 ? MathUtils.clamp((s - l0) / (l1 - l0), 0f, 1f) : 1f;
        add(lay, pts[2 * i - 2] + (pts[2 * i] - pts[2 * i - 2]) * u,
            pts[2 * i - 1] + (pts[2 * i + 1] - pts[2 * i - 1]) * u, bend, t);
    }

    /** Adds reference point (x, y) in world units, unless it is where the last one is. */
    private void add(LaneLayout lay, float x, float y, float bend, float t) {
        float px = lay.x(x), py = Field.y(y);
        if (bend != 0f) {
            px += bend * VeilRenderer.wobbleX(x, y, t);
            py += bend * VeilRenderer.wobbleY(x, y, t);
        }
        if (count > 0 && Math.abs(px - wx[count - 1]) < 0.01f && Math.abs(py - wy[count - 1]) < 0.01f) return;
        wx[count] = px;
        wy[count] = py;
        count++;
    }

    /**
     * Draws line k's part in hand: its glitch (copies, runs, flicker), glow, line and round ends ({@code loop}: closed
     * and whole, no ends), and the pen tip while it is drawn in ({@code pen}).
     */
    private void line(Batch b, int k, Stroke s, float a, float sc, float px, float beat, boolean loop, boolean pen) {
        int c = s.color();
        float r = (c >>> 24) / 255f, g = (c >>> 16 & 0xFF) / 255f, bl = (c >>> 8 & 0xFF) / 255f;
        a *= (c & 0xFF) / 255f;
        float w = s.width() * sc;
        normals(loop);
        // the glitch of this step, as a note's (GlitchCapture)
        boolean torn = false;
        float shift = 0f, splitA = 0f;
        NoteGlitch gl = s.glitches().isEmpty() ? null : NoteGlitch.activeAt(s.glitches(), beat);
        float gi = gl == null ? 0f : gl.intensityAt(beat);
        if (gi > 0.001f) {
            boolean reduce = GlitchText.reduceFlashing();
            rng = GlitchCapture.seed(k, gl.step(beat, reduce ? 0.5f : 1f), Float.floatToIntBits(gl.from()) ^ 0x5EED);
            if (rng == 0) rng = 0x6D2B79F5;
            float flicker = reduce ? 0f : gl.flicker();
            if (flicker > 0f && next() < GlitchCapture.FLICKER_CHANCE * gi * flicker) {
                a *= GlitchCapture.FLICKER_DIM;
            } else if (gl.split() > 0f) {
                shift = (2f + 10f * gi) * gl.split() * sc;
                splitA = Math.min(1f, (0.35f + 0.45f * gi) * Math.min(1f, gl.split()));
            }
            if (gl.slice() > 0f) {
                torn = true;
                tear(Math.min(1f, (0.25f + 0.6f * gi) * gl.slice()), 1f + 40f * gi * gl.slice(), sc);
            }
        }
        if (!torn) {
            runs = 1;
            runFrom[0] = 0;
            runTo[0] = count - 1;
            runDx[0] = 0f;
            runDy[0] = 0f;
        }
        if (shift > 0f) { // the colour split under it, a little apart up and down too
            for (int q = 0; q < runs; q++) {
                strip(b, runFrom[q], runTo[q], runDx[q] - shift, runDy[q] + 0.4f * shift, w, GlitchCapture.RED_R,
                    GlitchCapture.RED_G, GlitchCapture.RED_B, splitA * a, px);
                strip(b, runFrom[q], runTo[q], runDx[q] + shift, runDy[q] - 0.4f * shift, w, GlitchCapture.BLUE_R,
                    GlitchCapture.BLUE_G, GlitchCapture.BLUE_B, splitA * a, px);
            }
        }
        for (int q = 0; q < runs; q++) glow(b, runFrom[q], runTo[q], runDx[q], runDy[q], w, r, g, bl, GLOW_A * a);
        for (int q = 0; q < runs; q++) strip(b, runFrom[q], runTo[q], runDx[q], runDy[q], w, r, g, bl, a, px);
        if (!loop) {
            float h = 0.5f * Math.max(w, px);
            float solid = Color.toFloatBits(r, g, bl, a * Math.min(1f, w / px));
            if (runFrom[0] == 0 && runTo[0] > 0) cap(b, 0, 1, runDx[0], runDy[0], h, solid);
            int z = runs - 1;
            if (runTo[z] == count - 1 && runFrom[z] < runTo[z]) {
                cap(b, count - 1, count - 2, runDx[z], runDy[z], h, solid);
            }
        }
        if (pen) { // the pen tip while the line is drawn in
            int z = runs - 1;
            float x = wx[count - 1] + runDx[z], y = wy[count - 1] + runDy[z];
            tint.set(r, g, bl, 1f);
            draw.disc(x, y, 3.2f * w, tint, 0.28f * a);
            draw.disc(x, y, 1.2f * w, Color.WHITE, Math.min(1f, a + 0.3f));
        }
    }

    /**
     * Breaks the part in hand into runs of {@link #RUN_MIN}–{@link #RUN_MAX} points; each jumps sideways with
     * {@code chance}, {@code 3 + next·reach} units (and a third of that up or down), and a few of them are left out.
     */
    private void tear(float chance, float reach, float sc) {
        runs = 0;
        int i = 0;
        while (i < count - 1 && runs < runFrom.length) {
            int j = Math.min(count - 1, i + RUN_MIN + (int) (next() * (RUN_MAX - RUN_MIN + 1)));
            float dx = 0f, dy = 0f;
            if (next() < chance) {
                float d = (3f + next() * reach) * sc;
                dx = next() < 0.5f ? -d : d;
                dy = (next() - 0.5f) * 0.6f * d;
            }
            boolean gone = next() < RUN_GAP * chance;
            if (!gone) {
                runFrom[runs] = i;
                runTo[runs] = j;
                runDx[runs] = dx;
                runDy[runs] = dy;
                runs++;
            }
            i = j;
        }
        if (runs == 0) { // all gone: keep the first point alone, nothing is drawn
            runFrom[0] = 0;
            runTo[0] = 0;
            runDx[0] = 0f;
            runDy[0] = 0f;
            runs = 1;
        }
    }

    /** Unit normals of the points in hand, joined (and lengthened at a bend, at most 2.5×) between two pieces. */
    private void normals(boolean loop) {
        int last = count - 1;
        for (int i = 0; i <= last; i++) {
            int p = i > 0 ? i - 1 : loop ? last - 1 : -1;
            int q = i < last ? i + 1 : loop ? 1 : -1;
            float ax = 0f, ay = 0f, bx = 0f, by = 0f;
            if (p >= 0) {
                float dx = wx[i] - wx[p], dy = wy[i] - wy[p];
                float l = (float) Math.sqrt(dx * dx + dy * dy);
                if (l > 1e-4f) {
                    ax = -dy / l;
                    ay = dx / l;
                }
            }
            if (q >= 0) {
                float dx = wx[q] - wx[i], dy = wy[q] - wy[i];
                float l = (float) Math.sqrt(dx * dx + dy * dy);
                if (l > 1e-4f) {
                    bx = -dy / l;
                    by = dx / l;
                }
            }
            float mx = ax + bx, my = ay + by;
            float ml = (float) Math.sqrt(mx * mx + my * my);
            if (ml < 1e-4f) { // a turn back on itself, or a lone point
                mx = bx != 0f || by != 0f ? bx : ax;
                my = bx != 0f || by != 0f ? by : ay;
                ml = 1f;
            }
            mx /= ml;
            my /= ml;
            float dot = q >= 0 ? mx * bx + my * by : mx * ax + my * ay;
            float k = 1f / Math.max(0.4f, dot);
            nx[i] = mx * k;
            ny[i] = my * k;
        }
    }

    /** The line from point i0 to i1 moved by (dx, dy): {@code w} wide in colour (r, g, b) at a, its edges feathered. */
    private void strip(Batch b, int i0, int i1, float dx, float dy, float w, float r, float g, float bl, float a,
                       float px) {
        if (i1 <= i0 || a <= 0f) return;
        float h = 0.5f * Math.max(w, px), f = 0.5f * px;
        float in = Math.max(0f, h - f), out = h + f;
        float solid = Color.toFloatBits(r, g, bl, Math.min(1f, a * Math.min(1f, w / px)));
        float clear = Color.toFloatBits(r, g, bl, 0f);
        for (int i = i0; i < i1; i++) {
            int j = i + 1;
            float x0 = wx[i] + dx, y0 = wy[i] + dy, x1 = wx[j] + dx, y1 = wy[j] + dy;
            float n0x = nx[i], n0y = ny[i], n1x = nx[j], n1y = ny[j];
            quad(b, x0 - n0x * in, y0 - n0y * in, solid, x1 - n1x * in, y1 - n1y * in, solid,
                x1 + n1x * in, y1 + n1y * in, solid, x0 + n0x * in, y0 + n0y * in, solid);
            quad(b, x0 + n0x * in, y0 + n0y * in, solid, x1 + n1x * in, y1 + n1y * in, solid,
                x1 + n1x * out, y1 + n1y * out, clear, x0 + n0x * out, y0 + n0y * out, clear);
            quad(b, x0 - n0x * out, y0 - n0y * out, clear, x1 - n1x * out, y1 - n1y * out, clear,
                x1 - n1x * in, y1 - n1y * in, solid, x0 - n0x * in, y0 - n0y * in, solid);
        }
    }

    /** The soft glow along points i0..i1 moved by (dx, dy): a on the line, nothing {@link #GLOW_W} widths away. */
    private void glow(Batch b, int i0, int i1, float dx, float dy, float w, float r, float g, float bl, float a) {
        if (i1 <= i0 || a <= 0f) return;
        float hw = GLOW_W * w;
        float solid = Color.toFloatBits(r, g, bl, Math.min(1f, a)), clear = Color.toFloatBits(r, g, bl, 0f);
        for (int i = i0; i < i1; i++) {
            int j = i + 1;
            float x0 = wx[i] + dx, y0 = wy[i] + dy, x1 = wx[j] + dx, y1 = wy[j] + dy;
            quad(b, x0, y0, solid, x1, y1, solid, x1 + nx[j] * hw, y1 + ny[j] * hw, clear,
                x0 + nx[i] * hw, y0 + ny[i] * hw, clear);
            quad(b, x0 - nx[i] * hw, y0 - ny[i] * hw, clear, x1 - nx[j] * hw, y1 - ny[j] * hw, clear, x1, y1, solid,
                x0, y0, solid);
        }
    }

    /** A round end of radius h at point i, away from point j, moved by (dx, dy). */
    private void cap(Batch b, int i, int j, float dx, float dy, float h, float c) {
        if (j < 0 || j >= count) return;
        float tx = wx[i] - wx[j], ty = wy[i] - wy[j];
        float l = (float) Math.sqrt(tx * tx + ty * ty);
        if (l < 1e-4f) return;
        tx /= l;
        ty /= l;
        float x = wx[i] + dx, y = wy[i] + dy;
        // from one side (the normal) round the outside to the other
        float ax = -ty, ay = tx;
        for (int p = 0; p < CAP_PIECES; p++) {
            float u0 = MathUtils.PI * p / CAP_PIECES, u1 = MathUtils.PI * (p + 1) / CAP_PIECES;
            float c0 = MathUtils.cos(u0), s0 = MathUtils.sin(u0), c1 = MathUtils.cos(u1), s1 = MathUtils.sin(u1);
            quad(b, x, y, c, x + h * (ax * c0 + tx * s0), y + h * (ay * c0 + ty * s0), c,
                x + h * (ax * c1 + tx * s1), y + h * (ay * c1 + ty * s1), c, x, y, c);
        }
    }

    private void quad(Batch b, float x0, float y0, float c0, float x1, float y1, float c1, float x2, float y2, float c2,
                      float x3, float y3, float c3) {
        float[] q = verts;
        q[0] = x0;
        q[1] = y0;
        q[2] = c0;
        q[3] = coreU;
        q[4] = coreV;
        q[5] = x1;
        q[6] = y1;
        q[7] = c1;
        q[8] = coreU;
        q[9] = coreV;
        q[10] = x2;
        q[11] = y2;
        q[12] = c2;
        q[13] = coreU;
        q[14] = coreV;
        q[15] = x3;
        q[16] = y3;
        q[17] = c3;
        q[18] = coreU;
        q[19] = coreV;
        b.draw(atlas, q, 0, 20);
    }

    /** @return the next pseudo-random 0..1 of this step's pattern (xorshift, as a note's glitch) */
    private float next() {
        int x = rng;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        rng = x;
        return (x >>> 8) * (1f / (1 << 24));
    }
}
