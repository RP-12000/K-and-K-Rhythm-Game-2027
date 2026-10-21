package org.kelvinizer.screens.result;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 结算画面的评级印章：评级字母旋转着砸下，
 * 冲击时按评级荡开光环、印章一列闪白、RGB 分离后回弹，R、SS+ 和 R+ 还有扫过的光泽。
 * 自动播放时是灰色的墨印，新手教程则显示打勾的 COMPLETE 印章；冲击时的火花、震屏和振动由画面触发。
 *
 * <p>The grade stamp (DESIGN §6.2, §6.3, §12): slam in 2.00–2.22 (scale 2.6 → 1, rotation −12° →
 * −6°, {@code pow4In}), the 2.22 impact (rings per grade, column flash, RGB split decaying over
 * 250 ms, bouncy settle), the R / SS+ / R+ shimmer, the autoplay ink stamp and the tutorial check
 * seal. Sparks, kicks, haptics and the heartbeat are fired by the screen on the impact event.
 *
 * <p>Sizes animate by scaling geometry, never by regenerating font buckets. Outside glitch
 * bursts the rotated letter is drawn as glyph quads placed by hand ({@link GlyphQuads#setAffine})
 * and joins the stamp page's group in the {@link TextQueue}, replicating GlitchText's G1 resting
 * shimmer; only a G2 burst (impact, idle, re-stamp) uses a batch transform and GlitchText.
 */
public final class GradeStamp implements TextQueue.Deferred {
    /** Restamp (tap) pop length. */
    public static final float RESTAMP_D = 0.14f;
    private static final float SLOPE = 0.45f;

    private final ResultKit k;
    private final Color col = new Color();
    private float letterCap, plusCap, letterW, plusW, gap;
    // per-frame state of the deferred letter
    private ResultModel dm;
    private float dX, dY, dRot, dScale, dAlpha, dT, dU;
    private GlitchState dState;

    /** Creates the stamp renderer. */
    public GradeStamp(ResultKit kit) {
        this.k = kit;
    }

    /** Measures the letter for the current layout (call after a layout change or a new result). */
    public void measure(ResultModel m, ResultLayout l) {
        if (m.grade == null) return;
        Fonts f = k.fonts;
        letterCap = l.stampCap;
        plusCap = letterCap * 0.55f;
        letterW = f.width(Fonts.Role.DISPLAY, letterCap, Fonts.GlyphSet.GRADE, m.gradeLetter);
        plusW = m.gradePlus ? f.width(Fonts.Role.DISPLAY, plusCap, Fonts.GlyphSet.GRADE, "+") : 0f;
        gap = m.gradePlus ? letterCap * 0.04f : 0f;
        // round 7's fit (2 × 84 − 44 across its r 84 ring) scaled with the ring, as the round-8 mock-ups scale it
        float maxW = (2f - 44f / 84f) * l.stampR;
        float total = letterW + gap + plusW;
        if (total > maxW && total > 0f) {
            float s = maxW / total;
            letterCap *= s;
            plusCap *= s;
            letterW *= s;
            plusW *= s;
            gap *= s;
        }
    }

    /** Cap of the letter as drawn (after fitting); the screen prewarms its GRADE buckets. */
    public float letterCap() {
        return letterCap;
    }

    /** Stamp colour (steady; the gloss of R and R+ is the shimmer band; purple when this play was a full combo). */
    public Color color(ResultModel m, float t, Color out) {
        if (m.autoplay || m.grade == null) return out.set(Theme.INK3);
        return Theme.gradeColor(Theme.gradeIndex(m.grade), m.fullCombo, out);
    }

    /** Settle multiplier {@code u} seconds after an impact: 1 → .96 → 1 with a small overshoot. */
    public static float settle(float u) {
        if (u <= 0f || u > 0.8f) return 1f;
        return 1f - 0.082f * (float) Math.exp(-9f * u) * (float) Math.sin(Math.PI * 2f * 2.8f * u);
    }

    /**
     * Draws the stamp at timeline time {@code t}.
     *
     * @param state    glitch state of the letter (G0 until the impact, then G1 at rest)
     * @param restampT timeline time of the last tap re-stamp, or a negative value
     */
    public void draw(ResultModel m, ResultLayout l, float t, GlitchState state, float restampT, float alpha) {
        if (m.tutorial) {
            drawSeal(l, t, alpha);
            return;
        }
        if (t < Choreo.STAMP_T || m.grade == null) return;
        float p = Anim.progress(t, Choreo.STAMP_T, Choreo.IMPACT_T - Choreo.STAMP_T);
        float e = Anim.POW4_IN.apply(p);
        float s = 2.6f - 1.6f * e;
        float rot = -12f + 6f * e;
        float a = alpha * ResultKit.clamp01((t - Choreo.STAMP_T) / 0.08f);
        float u = t - Choreo.IMPACT_T;
        if (u >= 0f) s *= settle(u);
        if (restampT >= 0f && t >= restampT) {
            float r = t - restampT;
            if (r < RESTAMP_D) s *= 1.18f - 0.18f * Anim.POW4_IN.apply(r / RESTAMP_D);
            else s *= settle(r - RESTAMP_D) * 0.5f + 0.5f;
        }
        color(m, t, col);
        boolean d = "F".equals(m.grade);
        float R = l.stampR;

        // backing, rings and ticks in world space (scaled and rotated by hand), so they batch
        // with the shapes before them; only the letter needs the transform
        float sx0 = l.stampX, sy0 = l.stampY;
        k.draw.disc(sx0, sy0, (R - 2f) * s, Theme.PANEL, 0.55f * a);
        k.draw.disc(sx0, sy0, (R - 2f) * s, col, 0.06f * a);
        if (d || m.autoplay) {
            k.draw.circle(sx0, sy0, R * s, (d ? 2f : 3f) * s, col, 0.9f * a);
        } else {
            k.draw.circle(sx0, sy0, R * s, 4f * s, col, a);
            k.draw.circle(sx0, sy0, (R - 12f) * s, 2f * s, col, 0.7f * a);
        }
        for (int i = 0; i < 4; i++) {
            double ang = Math.PI * 0.5 * i + Math.PI * 0.25 + Math.toRadians(rot);
            float cs = (float) Math.cos(ang) * s, sn = (float) Math.sin(ang) * s;
            k.draw.line(sx0 + cs * (R + 8f), sy0 + sn * (R + 8f), sx0 + cs * (R + 20f), sy0 + sn * (R + 20f), 2f * s,
                col, 0.6f * a);
        }
        float sh = shimmerPhase(m, t);
        boolean bursting = state.isBursting(t) && GlitchText.intensity() > 0.001f;
        if (!bursting) {
            // rest, slam and settle: hand-placed rotated quads, grouped with the stamp page
            dm = m;
            dX = l.stampX;
            dY = l.stampY;
            dRot = rot;
            dScale = s;
            dAlpha = a;
            dT = t;
            dU = u;
            dState = state;
            BitmapFont font = k.fonts.get(Fonts.Role.DISPLAY, letterCap, Fonts.GlyphSet.GRADE);
            k.text.deferred(font.getRegion().getTexture(), this, 0);
            return;
        }
        k.push(l.stampX, l.stampY, rot, s, s);
        // letter (+ superscript)
        float total = letterW + gap + plusW;
        float lx = -total * 0.5f;
        String text = m.gradeLetter;
        if (u >= 0f && u < 0.25f && !m.autoplay) {
            float sp = 6f * (1f - u / 0.25f);
            float ca = 0.45f * a;
            k.fonts.draw(k.batch, Fonts.Role.DISPLAY, letterCap, Fonts.GlyphSet.GRADE, text, lx - sp, 0f,
                Align.left | Fonts.VCENTER, Theme.GLITCH_C, ca);
            k.fonts.draw(k.batch, Fonts.Role.DISPLAY, letterCap, Fonts.GlyphSet.GRADE, text, lx + sp, 0f,
                Align.left | Fonts.VCENTER, Theme.GLITCH_M, ca);
        }
        k.glitch.draw(k.batch, Fonts.Role.DISPLAY, letterCap, Fonts.GlyphSet.GRADE, text, lx, 0f,
            Align.left | Fonts.VCENTER, col, a, state, t);
        if (m.gradePlus) drawPlus(k.fonts.get(Fonts.Role.DISPLAY, letterCap, Fonts.GlyphSet.GRADE), lx,
            Color.toFloatBits(col.r, col.g, col.b, ResultKit.clamp01(col.a * a)));
        if (sh >= 0f && !m.autoplay) drawShimmerBand(m, text, lx, sh, a);
        k.pop();
    }

    /**
     * The letter at rest (deferred into the stamp page): impact RGB split, GlitchText's G1
     * resting shimmer (two offset ghost copies with a periodic 1-unit jitter), the letter, the
     * superscript and the R / SS+ / R+ shimmer band, all as quads rotated and scaled by hand.
     */
    @Override
    public void drawDeferred(int arg) {
        ResultModel m = dm;
        float a = dAlpha, t = dT, u = dU;
        BitmapFont font = k.fonts.get(Fonts.Role.DISPLAY, letterCap, Fonts.GlyphSet.GRADE);
        String text = m.gradeLetter;
        float total = letterW + gap + plusW;
        float lx = -total * 0.5f;
        int align = Align.left | Fonts.VCENTER;
        k.quads.setAffine(dX, dY, dRot, dScale);
        if (u >= 0f && u < 0.25f && !m.autoplay) {
            float sp = 6f * (1f - u / 0.25f);
            float ca = 0.45f * a;
            k.quads.text(k.batch, font, text, lx - sp, 0f, align, letterCap, packed(Theme.GLITCH_C, ca));
            k.quads.text(k.batch, font, text, lx + sp, 0f, align, letterCap, packed(Theme.GLITCH_M, ca));
        }
        float off = GlitchText.restingOffset(dState, letterCap, t);
        if (off > 0f) {
            float jx = GlitchText.restingJitterX(dState, t), jy = GlitchText.restingJitterY(dState, t);
            float ca = 0.35f * a * col.a;
            k.quads.text(k.batch, font, text, lx - off + jx, jy, align, letterCap, packed(Theme.GLITCH_C, ca));
            k.quads.text(k.batch, font, text, lx + off - jx, -jy, align, letterCap, packed(Theme.GLITCH_M, ca));
        }
        float main = Color.toFloatBits(col.r, col.g, col.b, ResultKit.clamp01(col.a * a));
        k.quads.text(k.batch, font, text, lx, 0f, align, letterCap, main);
        if (m.gradePlus) drawPlus(font, lx, main);
        float sh = shimmerPhase(m, t);
        if (sh >= 0f && !m.autoplay) drawShimmerBand(m, text, lx, sh, a);
        k.quads.clearAffine();
    }

    /** The superscript "+" from the letter's raster, scaled to its own cap. */
    private void drawPlus(BitmapFont font, float lx, float packedColor) {
        TextQueue.scaleTo(font, letterCap, plusCap);
        k.quads.text(k.batch, font, "+", lx + letterW + gap, letterCap * 0.5f, Align.left | Align.top, plusCap,
            packedColor);
        TextQueue.scaleTo(font, plusCap, letterCap);
    }

    /**
     * Diagonal shimmer band through the letter and its superscript (additive; SS+ once at 2.40,
     * R and R+ sweeping on from then, {@link Theme#sweep}).
     */
    private void drawShimmerBand(ResultModel m, String text, float lx, float sh, float a) {
        BitmapFont font = k.fonts.get(Fonts.Role.DISPLAY, letterCap, Fonts.GlyphSet.GRADE);
        float half = letterCap * 0.32f;
        float from = -SLOPE * letterCap - half, to = letterW + gap + plusW + half;
        float pos = from + (to - from) * sh;
        k.additive();
        k.quads.shimmer(k.batch, font, text, lx, 0f, Align.left | Fonts.VCENTER, letterCap, pos, half, SLOPE,
            Theme.WHITE, 0.85f * a);
        if (m.gradePlus) {
            // the same band, moved into the superscript's own left edge and baseline
            float pp = pos - letterW - gap + SLOPE * (letterCap - plusCap);
            TextQueue.scaleTo(font, letterCap, plusCap);
            k.quads.shimmer(k.batch, font, "+", lx + letterW + gap, letterCap * 0.5f, Align.left | Align.top, plusCap,
                pp, half, SLOPE, Theme.WHITE, 0.85f * a);
            TextQueue.scaleTo(font, plusCap, letterCap);
        }
        k.normal();
    }

    private static float packed(Color c, float alpha) {
        return Color.toFloatBits(c.r, c.g, c.b, ResultKit.clamp01(c.a * alpha));
    }

    /** Shimmer progress 0..1, or −1 when no band is crossing. */
    public static float shimmerPhase(ResultModel m, float t) {
        if (m.grade == null) return -1f;
        float rel = t - Choreo.SHIMMER_T;
        if (rel < 0f) return -1f;
        // R and R+ keep sweeping (Kelvin 2026-10-17 16:02); SS+ shines once
        if (m.rPlus || "R".equals(m.grade)) return Theme.sweep(Theme.gradeIndex(m.grade), rel);
        if (!"SS+".equals(m.grade)) return -1f;
        return rel < Choreo.SHIMMER_D ? rel / Choreo.SHIMMER_D : -1f;
    }

    /** Impact rings (deterministic from t) and the re-stamp ring; draw additive, world space. */
    public void drawRings(ResultModel m, ResultLayout l, float t, float restampT, float alpha) {
        if (m.tutorial || m.autoplay || m.grade == null) return;
        color(m, t, col);
        boolean d = "F".equals(m.grade);
        for (int i = 0; i < m.stampRings; i++) {
            float u = (t - Choreo.IMPACT_T - i * 0.08f) / 0.7f;
            if (u < 0f || u >= 1f) continue;
            float r = 480f * Anim.POW3_OUT.apply(u);
            float w = (d ? 2f : 3.5f) - (d ? 1f : 2f) * u;
            k.draw.circle(l.stampX, l.stampY, Math.max(1f, r), w, col, 0.6f * (1f - u) * (1f - u) * alpha);
        }
        if (restampT >= 0f) {
            float u = (t - restampT - RESTAMP_D) / 0.6f;
            if (u >= 0f && u < 1f) {
                float r = l.stampR + 160f * Anim.POW3_OUT.apply(u);
                k.draw.circle(l.stampX, l.stampY, r, 3f - 2f * u, col, 0.5f * (1f - u) * (1f - u) * alpha);
            }
        }
    }

    /** The 90 ms flash over the stamp column at the impact (α .12, capped by reduce flashing). */
    public void drawFlash(ResultModel m, ResultLayout l, float t, float alpha) {
        if (m.tutorial || m.autoplay || m.grade == null) return;
        float u = t - Choreo.IMPACT_T;
        if (u < 0f || u >= 0.09f) return;
        float a = GlitchText.flashAlpha(0.12f) * (1f - u / 0.09f) * alpha;
        k.draw.rect(l.stampColX0, Ui.bottom(), l.stampColX1 - l.stampColX0, Ui.worldH(), Theme.WHITE, a);
    }

    private void drawSeal(ResultLayout l, float t, float alpha) {
        if (t < Choreo.STAMP_T) return;
        float p = Anim.progress(t, Choreo.STAMP_T, Anim.POP);
        float s = Math.max(0.001f, Anim.POP_EASE.apply(p));
        float a = alpha * Math.min(1f, p * 3f);
        float R = l.stampR;
        Color c = Theme.RESONANCE;
        // upright check seal: at rest no transform (it batches with the other shapes)
        boolean px = k.pushIf(l.stampX, l.stampY, 0f, s, s);
        float x = k.ox, y = k.oy;
        k.draw.disc(x, y, R - 2f, Theme.PANEL, 0.55f * a);
        k.draw.disc(x, y, R - 2f, c, 0.06f * a);
        k.draw.circle(x, y, R, 4f, c, a);
        k.draw.circle(x, y, R - 12f, 2f, c, 0.7f * a);
        Icons.draw(k.shapes, Icons.Icon.CHECK, x, y + R * 0.14f, R * 1.05f, c, a);
        // 18 at round 7's r 84; scaled with the smaller round-8 rings so the word stays inside the seal
        k.text.text(Fonts.Role.UI_LABEL, 18f * Math.min(1f, R / 84f), "COMPLETE", x, y - R * 0.42f,
            Align.center | Fonts.VCENTER, c, a);
        k.popIf(px);
    }
}
