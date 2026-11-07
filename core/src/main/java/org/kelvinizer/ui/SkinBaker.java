package org.kelvinizer.ui;

/**
 * 键皮肤烘焙：开局把效果图（2026-11-05 第四步）的水滴、Drag、水雷画成图集小图（发光、渐变、白边、高光都画进去），
 * 游戏里按三段（左头、拉伸的中间、镜像的右头）贴出来；还有小水滴、量子小泡泡和水流尾巴的白边与发光。
 *
 * <p>Note skin baker (Kelvin 2026-11-05 step 4, 「按这页把水滴、水流、Drag、水雷、长水雷画进游戏，大小和现在一样」):
 * paints the approved mock-up's shapes into texel arrays at start-up, as a 2D canvas would (source-over, the
 * {@code shadowBlur} glow as a gaussian blur of the shape under it, linear gradients in sRGB). A stretchable skin is
 * baked for a body {@link #REF_W} wide and cropped to its left cap plus a short uniform column, so a renderer can draw
 * any width as the cap, the column stretched and the cap mirrored ({@link Draw.Skin}). Every size is in reference
 * units at note size 1 with y up and 0 at the leading edge (the edge facing the line), the note's local frame.
 * Runs once in {@link Draw}'s constructor; allocates freely.
 */
final class SkinBaker {
    private SkinBaker() {
    }

    /** Body width every stretchable skin is baked at: wide enough that its far end never reaches the crop. */
    static final float REF_W = 400f;
    /** Texels of the uniform column after a stretchable skin's cap. */
    static final int COLUMN = 4;

    /** A baked skin: straight-alpha RGBA8888 texels, row 0 at the top, and where it sits (units). */
    static final class Baked {
        final int w, h;
        final int[] rgba;
        /** Left and right edge, cap end ({@code xs}), bottom and top, all in units at note size 1. */
        final float x0, x1, xs, y0, y1;
        /** Texels of the cap ({@code x0} to {@code xs}); 0 for a plain sprite. */
        final int capTexels;

        Baked(int w, int h, int[] rgba, float x0, float x1, float xs, float y0, float y1, int capTexels) {
            this.w = w;
            this.h = h;
            this.rgba = rgba;
            this.x0 = x0;
            this.x1 = x1;
            this.xs = xs;
            this.y0 = y0;
            this.y1 = y1;
            this.capTexels = capTexels;
        }
    }

    // ================================================================== canvas

    /** A straight-alpha float canvas over {@code [x0, x0 + w/d] × [y1 - h/d, y1]} (units, y up; row 0 at y1). */
    private static final class Canvas {
        final float x0, y1, d;
        final int w, h;
        final float[] r, g, b, a;

        Canvas(float x0, float x1, float y0, float y1, float d) {
            this.x0 = x0;
            this.y1 = y1;
            this.d = d;
            this.w = Math.round((x1 - x0) * d);
            this.h = Math.round((y1 - y0) * d);
            int n = w * h;
            r = new float[n];
            g = new float[n];
            b = new float[n];
            a = new float[n];
        }

        float x(int i) {
            return x0 + (i + 0.5f) / d;
        }

        float y(int j) {
            return y1 - (j + 0.5f) / d;
        }

        /** Canvas source-over of colour {@code c} (0xRRGGBB) at opacity {@code ca} onto texel k. */
        void over(int k, int c, float ca) {
            if (!(ca > 0f)) return;
            if (ca > 1f) ca = 1f;
            float cr = ((c >>> 16) & 0xFF) / 255f;
            float cg = ((c >>> 8) & 0xFF) / 255f;
            float cb = (c & 0xFF) / 255f;
            overRgb(k, cr, cg, cb, ca);
        }

        void overRgb(int k, float cr, float cg, float cb, float ca) {
            float da = a[k];
            float na = ca + da * (1f - ca);
            if (na <= 0f) return;
            float keep = da * (1f - ca);
            r[k] = (cr * ca + r[k] * keep) / na;
            g[k] = (cg * ca + g[k] * keep) / na;
            b[k] = (cb * ca + b[k] * keep) / na;
            a[k] = na;
        }

        /**
         * @return texels {@code [i0, i1)} of every row as RGBA8888; a fully transparent texel takes {@code clear}'s
         *         colour, so filtering never pulls a dark or foreign hue into the soft edges
         */
        int[] rgba(int i0, int i1, int clear) {
            int cw = i1 - i0;
            int[] out = new int[cw * h];
            for (int j = 0; j < h; j++) {
                for (int i = i0; i < i1; i++) {
                    int k = j * w + i;
                    float al = a[k];
                    int px;
                    if (al < 0.5f / 255f) {
                        px = clear << 8;
                    } else {
                        px = (byte8(r[k]) << 24) | (byte8(g[k]) << 16) | (byte8(b[k]) << 8) | byte8(al);
                    }
                    out[j * cw + (i - i0)] = px;
                }
            }
            return out;
        }
    }

    private static int byte8(float v) {
        return (int) (Math.max(0f, Math.min(1f, v)) * 255f + 0.5f);
    }

    /** Coverage of a shape whose signed distance (units, negative inside) is {@code sd}, one texel of edge. */
    private static float fill(float sd, float d) {
        return clamp01(0.5f - sd * d);
    }

    /** Coverage of a stroke {@code lw} wide centred on a path at signed distance {@code sd}. */
    private static float stroke(float sd, float lw, float d) {
        return clamp01(0.5f + (0.5f * lw - Math.abs(sd)) * d);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    /** Signed distance to the rounded rect {@code [x0, x1] × [y0, y1]} with corner radius r (negative inside). */
    static float sdRRect(float px, float py, float x0, float y0, float x1, float y1, float r) {
        float hw = 0.5f * (x1 - x0), hh = 0.5f * (y1 - y0);
        r = Math.min(r, Math.min(hw, hh));
        float qx = Math.abs(px - 0.5f * (x0 + x1)) - (hw - r);
        float qy = Math.abs(py - 0.5f * (y0 + y1)) - (hh - r);
        float ox = Math.max(qx, 0f), oy = Math.max(qy, 0f);
        return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0f) - r;
    }

    /** Signed distance to a closed polygon (x, y pairs; negative inside, even-odd). */
    static float sdPolygon(float px, float py, float[] p, int n) {
        float dx0 = px - p[0], dy0 = py - p[1];
        float best = dx0 * dx0 + dy0 * dy0;
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i, i++) {
            float ax = p[2 * j], ay = p[2 * j + 1], bx = p[2 * i], by = p[2 * i + 1];
            float ex = bx - ax, ey = by - ay;
            float wx = px - ax, wy = py - ay;
            float len2 = ex * ex + ey * ey;
            float t = len2 > 0f ? Math.max(0f, Math.min(1f, (wx * ex + wy * ey) / len2)) : 0f;
            float qx = wx - ex * t, qy = wy - ey * t;
            best = Math.min(best, qx * qx + qy * qy);
            if ((ay > py) != (by > py) && px < ax + (py - ay) * ex / ey) inside = !inside;
        }
        float dist = (float) Math.sqrt(best);
        return inside ? -dist : dist;
    }

    /** Gaussian blur of a coverage mask (w × h texels), σ in texels; outside the mask counts as empty. */
    private static float[] blur(float[] m, int w, int h, float sigma) {
        int rad = (int) Math.ceil(3f * sigma);
        float[] k = new float[2 * rad + 1];
        float sum = 0f;
        for (int i = -rad; i <= rad; i++) {
            k[i + rad] = (float) Math.exp(-0.5 * i * i / (sigma * sigma));
            sum += k[i + rad];
        }
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        float[] t = new float[w * h];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                float s = 0f;
                for (int o = -rad; o <= rad; o++) {
                    int x = i + o;
                    if (x >= 0 && x < w) s += m[j * w + x] * k[o + rad];
                }
                t[j * w + i] = s;
            }
        }
        float[] out = new float[w * h];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                float s = 0f;
                for (int o = -rad; o <= rad; o++) {
                    int y = j + o;
                    if (y >= 0 && y < h) s += t[y * w + i] * k[o + rad];
                }
                out[j * w + i] = s;
            }
        }
        return out;
    }

    /** A 2D canvas {@code shadowBlur} of {@code blur} reference pixels is a gaussian of σ = blur × 0.6 / 2 units. */
    private static float shadowSigma(float blur) {
        return blur * 0.6f * 0.5f;
    }

    /** @return colour of a three-stop gradient: {@code far} at t 0, {@code mid} at {@code m}, {@code near} at 1. */
    private static int grad3(float t, int far, int mid, int near, float m) {
        t = clamp01(t);
        return t < m ? mix(far, mid, t / m) : mix(mid, near, (t - m) / (1f - m));
    }

    static int mix(int c0, int c1, float t) {
        int r = Math.round(((c0 >>> 16) & 0xFF) + (((c1 >>> 16) & 0xFF) - ((c0 >>> 16) & 0xFF)) * t);
        int g = Math.round(((c0 >>> 8) & 0xFF) + (((c1 >>> 8) & 0xFF) - ((c0 >>> 8) & 0xFF)) * t);
        int b = Math.round((c0 & 0xFF) + ((c1 & 0xFF) - (c0 & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }

    /** Crops a stretchable skin: its cap {@code [x0, xs]} and {@link #COLUMN} texels after it. */
    private static Baked crop(Canvas c, float xs, float y0, int clear) {
        int cap = Math.round((xs - c.x0) * c.d);
        int[] px = c.rgba(0, cap + COLUMN, clear);
        return new Baked(cap + COLUMN, c.h, px, c.x0, xs + COLUMN / c.d, xs, y0, c.y1, cap);
    }

    // ================================================================== DROP and STREAM head

    /** Texels per unit of the heads (DROP, DRAG, MINE). */
    static final float HEAD_D = 2f;
    /** DROP head: glow margin, cap end and the far edge (a 40-unit-tall body). */
    static final float DROP_MARGIN = 12f, DROP_CAP = 56f, DROP_H = 40f;

    /** Colours of one DROP head look: gradient far → near, glow, the ticks near the ends. */
    static final class DropLook {
        final int far, mid, near, glow, tick;
        final float glowA, tickA;

        DropLook(int far, int mid, int near, int glow, float glowA, int tick, float tickA) {
            this.far = far;
            this.mid = mid;
            this.near = near;
            this.glow = glow;
            this.glowA = glowA;
            this.tick = tick;
            this.tickA = tickA;
        }
    }

    /** Ground DROP (the mock-up's cyan glass). */
    static final DropLook GROUND = new DropLook(0xA8EEFF, 0x4FD8FF, 0x1C9AD4, 0x4FD8FF, 0.8f, 0xFFFFFF, 0.42f);
    /** Sky DROP (pearl white; its ticks a faint blue-grey). */
    static final DropLook SKY = new DropLook(0xFFFFFF, 0xE3ECF7, 0xA9BDD6, 0xD6E8FF, 0.75f, 0x46648C, 0.32f);
    /** A grey ramp of the same shape, tinted by the renderer (a broken STREAM's head turning to the missed grey). */
    static final DropLook NEUTRAL = new DropLook(0xFFFFFF, 0xD6DCE8, 0x9AA6BC, 0xFFFFFF, 0.5f, 0xFFFFFF, 0.35f);

    /**
     * The mock-up's {@code noteBox} + ticks (not the tear, a sprite of its own): rounded glass r 12 with a
     * {@code shadowBlur 16} glow, the far → near gradient (stop .55), a 2.5 white rim at α .88, a 4-unit white
     * leading edge 2.5 in (α .95), a gloss stripe 18 in from each end over 56–78 % of the height, its white rising
     * to α .62 over the first 44 units of the body (the mock-up's 22 % of a 200-wide key), and 2-unit ticks 28 from
     * each end between 9 and 33. One skin per look, in order; the looks share one shape, so its outline and glow are
     * worked out once.
     */
    static Baked[] drops(DropLook... looks) {
        float d = HEAD_D, m = DROP_MARGIN, h = DROP_H, sigma = shadowSigma(16f);
        Canvas shape = new Canvas(-m, DROP_CAP + 32f, -m, h + m, d);
        int w = shape.w, n = w * shape.h;
        float[] sd = new float[n], mask = new float[n];
        for (int j = 0; j < shape.h; j++) {
            for (int i = 0; i < w; i++) {
                int q = j * w + i;
                sd[q] = sdRRect(shape.x(i), shape.y(j), 0f, 0f, REF_W, h, 12f);
                mask[q] = fill(sd[q], d);
            }
        }
        float[] glow = blur(mask, w, shape.h, sigma * d);
        Baked[] out = new Baked[looks.length];
        for (int l = 0; l < looks.length; l++) {
            DropLook k = looks[l];
            Canvas c = l == 0 ? shape : new Canvas(-m, DROP_CAP + 32f, -m, h + m, d);
            for (int j = 0; j < c.h; j++) {
                float y = c.y(j);
                int body = grad3((h - y) / h, k.far, k.mid, k.near, 0.55f);
                for (int i = 0; i < w; i++) {
                    float x = c.x(i);
                    int q = j * w + i;
                    c.over(q, k.glow, k.glowA * glow[q]);
                    c.over(q, body, mask[q]);
                    c.over(q, 0xFFFFFF, 0.88f * stroke(sd[q], 2.5f, d));
                    c.over(q, 0xFFFFFF, 0.95f * fill(sdRRect(x, y, 5f, 2.5f, REF_W - 5f, 6.5f, 2f), d));
                    float gloss = 0.62f * clamp01(x / 44f);
                    c.over(q, 0xFFFFFF, gloss * fill(sdRRect(x, y, 18f, 0.56f * h, REF_W - 18f, 0.78f * h, 0.11f * h), d));
                    float tick = clamp01(0.5f + (1f - Math.abs(x - 28f)) * d)
                        * clamp01(0.5f + Math.min(y - 9f, 33f - y) * d);
                    c.over(q, k.tick, k.tickA * tick);
                }
            }
            out[l] = crop(c, DROP_CAP, -m, k.glow);
        }
        return out;
    }

    // ================================================================== the tear on a DROP

    /** Tear sprite box (units, local frame: x across, y from the leading edge). */
    static final float TEAR_X0 = -8f, TEAR_X1 = 8f, TEAR_Y0 = 7.8f, TEAR_Y1 = 27.8f;

    /**
     * The mock-up's {@code tear(0, -1, 6.5)} on a DROP: a drop whose tip points at the line, white (tinted by the
     * renderer: blue on a sky head), baked at 4 texels a unit.
     */
    static Baked tear() {
        float d = 4f, cx = 0f, cy = 0.5f * DROP_H - 1f, rr = 6.5f;
        float[] p = tearPath(cx, cy, rr, 24);
        int n = p.length / 2;
        Canvas c = new Canvas(TEAR_X0, TEAR_X1, TEAR_Y0, TEAR_Y1, d);
        for (int j = 0; j < c.h; j++) {
            for (int i = 0; i < c.w; i++) {
                c.over(j * c.w + i, 0xFFFFFF, fill(sdPolygon(c.x(i), c.y(j), p, n), d));
            }
        }
        return new Baked(c.w, c.h, c.rgba(0, c.w, 0xFFFFFF), TEAR_X0, TEAR_X1, TEAR_X1, TEAR_Y0, TEAR_Y1, 0);
    }

    /**
     * The tear path in the local frame (y up): tip at {@code cy - 1.6 rr} (toward the line), two quadratic sides to a
     * round end of radius {@code 0.95 rr} about {@code cy + 0.25 rr}, as the mock-up's canvas path.
     */
    private static float[] tearPath(float cx, float cy, float rr, int seg) {
        float[] p = new float[2 * (3 * seg)];
        int k = 0;
        float tx = cx, ty = cy - 1.6f * rr;
        float ex = cx + 0.95f * rr, ey = cy + 0.25f * rr;
        // right side: quadratic tip → (ex, ey), control (cx + 1.05 rr, cy - 0.35 rr)
        for (int s = 0; s < seg; s++) {
            float t = s / (float) seg, u = 1f - t;
            p[k++] = u * u * tx + 2f * u * t * (cx + 1.05f * rr) + t * t * ex;
            p[k++] = u * u * ty + 2f * u * t * (cy - 0.35f * rr) + t * t * ey;
        }
        // round end: angle 0 → π through the far side
        for (int s = 0; s < seg; s++) {
            double an = Math.PI * s / seg;
            p[k++] = cx + 0.95f * rr * (float) Math.cos(an);
            p[k++] = ey + 0.95f * rr * (float) Math.sin(an);
        }
        // left side: (−ex, ey) → tip
        for (int s = 0; s < seg; s++) {
            float t = s / (float) seg, u = 1f - t;
            p[k++] = u * u * (2f * cx - ex) + 2f * u * t * (cx - 1.05f * rr) + t * t * tx;
            p[k++] = u * u * ey + 2f * u * t * (cy - 0.35f * rr) + t * t * ty;
        }
        return p;
    }

    // ================================================================== DRAG (shape B)

    /** DRAG bar: glow margin, cap end; the bar lies over 12–28 of the 40-unit box. */
    static final float DRAG_MARGIN = 11f, DRAG_CAP = 14f, DRAG_BAR_Y0 = 12f, DRAG_BAR_Y1 = 28f;
    private static final int DRAG_FAR = 0xF4EBFF, DRAG_MID = 0xBF95FF, DRAG_NEAR = 0x8552E8, DRAG_GLOW = 0xC496FF;

    /**
     * The DRAG's thin bar (the mock-up's shape B): 16 tall, r 8, light purple far → deep near (stop .55) with a
     * {@code shadowBlur 14} glow at α .85, a 2.5 white rim at α .88 and a 3-unit highlight 2 in from its edge facing
     * the line (α .9).
     */
    static Baked dragBar() {
        float d = HEAD_D, m = DRAG_MARGIN, y0 = DRAG_BAR_Y0, y1 = DRAG_BAR_Y1, sigma = shadowSigma(14f);
        Canvas c = new Canvas(-m, DRAG_CAP + 32f, y0 - m, y1 + m, d);
        float[] mask = new float[c.w * c.h];
        for (int j = 0; j < c.h; j++) {
            for (int i = 0; i < c.w; i++) mask[j * c.w + i] = fill(sdRRect(c.x(i), c.y(j), 0f, y0, REF_W, y1, 8f), d);
        }
        float[] glow = blur(mask, c.w, c.h, sigma * d);
        for (int j = 0; j < c.h; j++) {
            float y = c.y(j);
            int body = grad3((y1 - y) / (y1 - y0), DRAG_FAR, DRAG_MID, DRAG_NEAR, 0.55f);
            for (int i = 0; i < c.w; i++) {
                float x = c.x(i);
                int q = j * c.w + i;
                c.over(q, DRAG_GLOW, 0.85f * glow[q]);
                float sd = sdRRect(x, y, 0f, y0, REF_W, y1, 8f);
                c.over(q, body, fill(sd, d));
                c.over(q, 0xFFFFFF, 0.88f * stroke(sd, 2.5f, d));
                c.over(q, 0xFFFFFF, 0.9f * fill(sdRRect(x, y, 10f, y0 + 2f, REF_W - 10f, y0 + 5f, 1.5f), d));
            }
        }
        return crop(c, DRAG_CAP, y0 - m, DRAG_GLOW);
    }

    /** DRAG diamond: half width, half height (the 40-unit box), glow margin. */
    static final float DIAMOND_HW = 36f, DIAMOND_HH = 20f, DIAMOND_MARGIN = 12f;

    /**
     * The DRAG's diamond (shape B): 72 × 40 about the box centre, light purple at the far tip → deep at the tip facing
     * the line (stop .55), a {@code shadowBlur 16} glow at α .85, a 3-unit white rim and a 26 × 18 white diamond inside
     * (α .95). Baked as its left half and a short column past the centre; drawn mirrored.
     */
    static Baked dragDiamond() {
        float d = HEAD_D, m = DIAMOND_MARGIN, cy = DIAMOND_HH, sigma = shadowSigma(16f);
        float[] outer = {0f, cy - DIAMOND_HH, DIAMOND_HW, cy, 0f, cy + DIAMOND_HH, -DIAMOND_HW, cy};
        float[] inner = {0f, cy - 9f, 13f, cy, 0f, cy + 9f, -13f, cy};
        Canvas c = new Canvas(-DIAMOND_HW - m, DIAMOND_HW + m, -m, 2f * DIAMOND_HH + m, d);
        float[] mask = new float[c.w * c.h];
        for (int j = 0; j < c.h; j++) {
            for (int i = 0; i < c.w; i++) mask[j * c.w + i] = fill(sdPolygon(c.x(i), c.y(j), outer, 4), d);
        }
        float[] glow = blur(mask, c.w, c.h, sigma * d);
        float h = 2f * DIAMOND_HH;
        for (int j = 0; j < c.h; j++) {
            float y = c.y(j);
            int body = grad3((h - y) / h, DRAG_FAR, DRAG_MID, DRAG_NEAR, 0.55f);
            for (int i = 0; i < c.w; i++) {
                float x = c.x(i);
                int q = j * c.w + i;
                c.over(q, DRAG_GLOW, 0.85f * glow[q]);
                float sd = sdPolygon(x, y, outer, 4);
                c.over(q, body, fill(sd, d));
                c.over(q, 0xFFFFFF, stroke(sd, 3f, d));
                c.over(q, 0xFFFFFF, 0.95f * fill(sdPolygon(x, y, inner, 4), d));
            }
        }
        return crop(c, 0f, -m, DRAG_GLOW);
    }

    // ================================================================== MINE

    /** MINE body: end margin (the spikes reach 14 past the body), cap end, glow margin, body height. */
    static final float MINE_END = 16f, MINE_CAP = 14f, MINE_MARGIN = 12f, MINE_H = 32f;

    /**
     * The mock-up's MINE: red spikes 14 past both ends (no glow), a rounded r 10 body dark red far → darker near with
     * a {@code shadowBlur 16} red glow at α .8, a 3.5 #FF3344 rim and a faint 1.5 inner rim 7 / 6 in (r 5, α .45). The
     * three rivets are drawn by the renderer.
     */
    static Baked mine() {
        float d = HEAD_D, h = MINE_H, sigma = shadowSigma(16f);
        float[] spike = {4f, 0.5f * h + 0.3f * h, -14f, 0.5f * h, 4f, 0.5f * h - 0.3f * h};
        Canvas c = new Canvas(-MINE_END, MINE_CAP + 32f, -MINE_MARGIN, h + MINE_MARGIN, d);
        float[] mask = new float[c.w * c.h];
        for (int j = 0; j < c.h; j++) {
            for (int i = 0; i < c.w; i++) mask[j * c.w + i] = fill(sdRRect(c.x(i), c.y(j), 0f, 0f, REF_W, h, 10f), d);
        }
        float[] glow = blur(mask, c.w, c.h, sigma * d);
        for (int j = 0; j < c.h; j++) {
            float y = c.y(j);
            int body = mix(0x4A0C18, 0x1A0308, clamp01((h - y) / h));
            for (int i = 0; i < c.w; i++) {
                float x = c.x(i);
                int q = j * c.w + i;
                c.over(q, 0xFF3344, fill(sdPolygon(x, y, spike, 3), d));
                c.over(q, 0xFF3344, 0.8f * glow[q]);
                float sd = sdRRect(x, y, 0f, 0f, REF_W, h, 10f);
                c.over(q, body, fill(sd, d));
                c.over(q, 0xFF3344, stroke(sd, 3.5f, d));
                c.over(q, 0xFF5A6E, 0.45f * stroke(sdRRect(x, y, 7f, 6f, REF_W - 7f, h - 6f, 5f), 1.5f, d));
            }
        }
        return crop(c, MINE_CAP, -MINE_MARGIN, 0xFF3344);
    }

    // ================================================================== STREAM body pieces

    /** The far end of a STREAM body is baked for a body this wide and this long (stretched to the real one). */
    static final float HOLD_CAP_W = 162f, HOLD_CAP_LEN = 46f;
    /** Margin of the far-end sprites (the glow) and how far below the far end's start they reach (a seamless join). */
    static final float HOLD_CAP_MARGIN = 10f, HOLD_CAP_BELOW = 2f;
    /** Texels per unit of the far-end sprites. */
    static final float HOLD_CAP_D = 1f;

    /**
     * @return half width of the mock-up's STREAM far end at height {@code y} above the start of its round end
     *         ({@code holdPath}: quadratic sides from straight to the tip), for a body {@code hw} half wide whose
     *         round end is {@code cap} long
     */
    static float capHalfWidth(float y, float hw, float cap) {
        if (y <= 0f) return hw;
        if (y >= cap) return 0f;
        float u = (cap - y) / cap;
        float s = (float) Math.sqrt(u);
        return hw * (2f * s - u);
    }

    /** The outline of the round far end and 200 units of straight body below it (y from −200), as a polygon. */
    private static float[] holdEndPath(float hw, float cap, int seg) {
        float[] p = new float[2 * (2 * seg + 3)];
        int k = 0;
        p[k++] = -hw;
        p[k++] = -200f;
        for (int s = 0; s <= seg; s++) { // left side, quadratic (−hw, 0) → (0, cap), control (−hw, cap)
            float t = s / (float) seg;
            p[k++] = -hw * (1f - t * t);
            p[k++] = cap * (1f - (1f - t) * (1f - t));
        }
        for (int s = seg - 1; s >= 0; s--) {
            float t = s / (float) seg;
            p[k++] = hw * (1f - t * t);
            p[k++] = cap * (1f - (1f - t) * (1f - t));
        }
        p[k++] = hw;
        p[k++] = -200f;
        return p;
    }

    /** The outline of the round far end ({@link #holdEndPath}) of a body {@link #HOLD_CAP_W} wide, built once. */
    private static final float[] HOLD_END_PATH = holdEndPath(0.5f * HOLD_CAP_W, HOLD_CAP_LEN, 48);

    /** Parts of the far end of a STREAM body ({@link #holdEnds}). */
    static final int END_FILL = 0, END_LINE = 1, END_GLOW = 2;

    /**
     * The far end of a STREAM body (white masks, tinted by the renderer), indexed by part: its fill
     * ({@link #END_FILL}), its 2.5 rim ({@link #END_LINE}) and the {@code shadowBlur 12} glow outside it
     * ({@link #END_GLOW}); each over the round end and from {@link #HOLD_CAP_BELOW} below its start, so filtering at
     * the join samples the body, not the padding. The three parts share one outline pass.
     */
    static Baked[] holdEnds() {
        float d = HOLD_CAP_D, hw = 0.5f * HOLD_CAP_W, m = HOLD_CAP_MARGIN, sigma = shadowSigma(12f);
        float y0 = -HOLD_CAP_BELOW, y1 = HOLD_CAP_LEN + m;
        // the mask runs well below the crop, so the blur sees the straight body continue
        Canvas c = new Canvas(-hw - m, hw + m, y0 - 3f * sigma - 4f, y1, d);
        int w = c.w, n = w * c.h, corners = HOLD_END_PATH.length / 2;
        float[] sd = new float[n], mask = new float[n];
        for (int j = 0; j < c.h; j++) {
            for (int i = 0; i < w; i++) {
                int q = j * w + i;
                sd[q] = sdPolygon(c.x(i), c.y(j), HOLD_END_PATH, corners);
                mask[q] = fill(sd[q], d);
            }
        }
        float[] glow = blur(mask, w, c.h, sigma * d);
        int rows = Math.round((y1 - y0) * d); // row 0 is the top (y1)
        Baked[] out = new Baked[3];
        for (int part = END_FILL; part <= END_GLOW; part++) {
            int[] px = new int[w * rows];
            for (int q = 0; q < px.length; q++) {
                float a = part == END_LINE ? stroke(sd[q], 2.5f, d)
                    : part == END_GLOW ? glow[q] * (1f - mask[q]) // outside only: the fill covers the inside
                    : mask[q];
                px[q] = 0xFFFFFF00 | byte8(a); // white, as a canvas paints it (a clear texel keeps the white)
            }
            out[part] = new Baked(w, rows, px, -hw - m, hw + m, hw + m, y0, y1, 0);
        }
        return out;
    }

    /**
     * The glow beside a STREAM body's straight sides: one row of the {@code shadowBlur 12} profile outside an edge,
     * {@code 0.5 erfc(d / σ√2)} from the edge (texel 0) outward over 4 σ; tinted and stretched along the body.
     */
    static Baked sideGlow() {
        float sigma = shadowSigma(12f), d = 4f;
        int w = Math.round(4f * sigma * d), h = 4;
        int[] px = new int[w * h];
        for (int i = 0; i < w; i++) {
            float x = (i + 0.5f) / d;
            float g = (float) (0.5 * erfc(x / (sigma * Math.sqrt(2.0))));
            g *= clamp01((4f * sigma - x) / sigma); // to 0 at the far end
            for (int j = 0; j < h; j++) px[j * w + i] = 0xFFFFFF00 | byte8(g);
        }
        return new Baked(w, h, px, 0f, 4f * sigma, 4f * sigma, 0f, 1f, 0);
    }

    /**
     * The 2.5-unit white rim along a STREAM body's straight sides: one row of the stroke's coverage across
     * {@code [-2, 2]} (the rim centred on 0), 4 texels a unit; stretched along the body.
     */
    static Baked rim() {
        float d = 4f;
        int w = 16, h = 4;
        int[] px = new int[w * h];
        for (int i = 0; i < w; i++) {
            float x = -2f + (i + 0.5f) / d;
            int a = byte8(stroke(x, 2.5f, d));
            for (int j = 0; j < h; j++) px[j * w + i] = 0xFFFFFF00 | a;
        }
        return new Baked(w, h, px, -2f, 2f, 2f, 0f, 1f, 0);
    }

    /**
     * The light bars flowing inside a STREAM body: a rounded white bar 100 × 7 (r 3.5) at {@code [0, 100] × [0, 7]},
     * baked at 1.28 × 1.29 texels a unit with a texel of margin all round.
     */
    static Baked holdBar() {
        int w = 130, h = 11;
        float sx = 128f / 100f, sy = 9f / 7f; // texels per unit across and along
        int[] px = new int[w * h];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                float x = (i + 0.5f - 1f) / sx, y = (j + 0.5f - 1f) / sy; // units, 0..100 × 0..7
                float sd = sdRRect(x, y, 0f, 0f, 100f, 7f, 3.5f);
                px[j * w + i] = 0xFFFFFF00 | byte8(clamp01(0.5f - sd * Math.min(sx, sy)));
            }
        }
        return new Baked(w, h, px, -1f / sx, (w - 1) / sx, (w - 1) / sx, -1f / sy, (h - 1) / sy, 0);
    }

    // ================================================================== the lines' quantum bubbles

    /** Size of the bubble sprite in texels; the bubble's radius is {@link #BUBBLE_R} of its half size. */
    static final int BUBBLE_TEX = 64;
    static final float BUBBLE_R = 0.9f;

    /**
     * A small air bubble for the judgement lines' quantum fluctuation (Kelvin 2026-11-05 23:11, 「好像一直有这种小泡泡
     * 浮现…逼真一点」): a clear body that brightens toward a thin rim (the Fresnel look of a bubble in water), a soft
     * highlight and a sharp glint toward the upper left, a faint reflected arc at the lower right. White; tinted by the
     * line colour.
     */
    static Baked bubble() {
        int n = BUBBLE_TEX;
        float c0 = 0.5f * n, R = BUBBLE_R * c0;
        int[] px = new int[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float dx = i + 0.5f - c0, dy = c0 - (j + 0.5f); // y up
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float rho = dist / R;
                float edge = clamp01(0.5f + (R - dist));
                float body = 0.07f + 0.5f * (float) Math.pow(Math.min(rho, 1f), 6.0);
                float rim = (float) Math.exp(-sq((dist - (R - 1.6f)) / 1.5f));
                float a = edge * Math.min(1f, body + 0.75f * rim);
                // highlight toward the upper left: a soft spot and a sharp glint in it
                float hx = dx / R + 0.38f, hy = dy / R - 0.40f;
                float hd = (float) Math.sqrt(hx * hx + hy * hy);
                float soft = 0.55f * clamp01(1f - hd / 0.30f);
                float glint = clamp01(0.5f + (0.12f - hd) * R);
                // a faint reflected arc at the lower right, just inside the rim
                double an = Math.atan2(dy, dx);
                float arc = (float) (Math.max(0.0, Math.cos(an + Math.PI / 4.0))
                    * Math.exp(-sq((rho - 0.78f) / 0.07f)));
                a = 1f - (1f - a) * (1f - edge * soft) * (1f - glint) * (1f - 0.35f * edge * arc);
                px[j * n + i] = 0xFFFFFF00 | byte8(a);
            }
        }
        return new Baked(n, n, px, -1f, 1f, 1f, -1f, 1f, 0);
    }

    private static float sq(float v) {
        return v * v;
    }

    /** Complementary error function (Abramowitz–Stegun 7.1.26, |error| < 1.5e-7). */
    static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * z);
        double y = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
            * Math.exp(-z * z);
        return x >= 0 ? y : 2.0 - y;
    }
}
