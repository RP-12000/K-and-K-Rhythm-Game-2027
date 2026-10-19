package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.elements.Line;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;

/**
 * 判定线附近的短暂反馈：轨道上的判定字、接触环、ECHO 小箭头和水雷冲击环；碰到水雷时在轨道上显示红色的 LOST。
 *
 * <p>Short-lived playfield feedback (DESIGN §5.6 per-lane micro feedback and the at-lane word,
 * §5.10 lane touch feedback, §5.15 mine shock, §5.5 combo-100 ring). The TIDE hit effect is the
 * tide wash of {@link TideWash} (DESIGN §19.2), which replaced the TIDE sweep. A touched mine
 * or LANE MINE contact shows the LOST word in {@link Theme#MINE_LOST} ({@link #mineWord}; owner
 * 2026-10-12 00:42: 「地雷按到的时候用红色判定」).
 *
 * <p>Fixed ring buffers (the oldest entry is replaced), timed in run seconds so everything
 * freezes with the run. {@link #drawAdditive} draws the shape effects in the additive block,
 * {@link #drawText} the at-lane words in the HUD text pass. No allocation.
 */
final class FeedbackLayer {
    private static final int N = 24;
    private static final float CONTACT_SEC = 0.16f;
    private static final float DEMO_DOT_SEC = 0.6f;
    private static final float CHEVRON_SEC = 0.30f;
    private static final float WORD_SEC = 0.30f;
    private static final float SHOCK_SEC = 0.22f;
    private static final float CENTRE_RING_SEC = 0.40f;

    private final Draw draw;
    private final Batch batch;

    private final float[] ringX = new float[N];
    private final float[] ringY = new float[N];
    private final float[] ringT = new float[N];
    private final float[] ringA = new float[N];
    private int ringNext;

    private final float[] brL = new float[N];
    private final float[] brR = new float[N];
    private final float[] brY = new float[N];
    private final float[] brT = new float[N];
    private int brNext;

    private final float[] dotX = new float[N];
    private final float[] dotY = new float[N];
    private final float[] dotT = new float[N];
    private final boolean[] dotDemo = new boolean[N];
    private int dotNext;

    private final float[] chX = new float[N];
    private final float[] chY = new float[N];
    private final float[] chT = new float[N];
    private final float[] chAway = new float[N];
    private final boolean[] chPointUp = new boolean[N];
    private final boolean[] chLate = new boolean[N];
    private int chNext;

    private final float[] wX = new float[N];
    private final float[] wY = new float[N];
    private final float[] wT = new float[N];
    private final boolean[] wTop = new boolean[N];
    private final Judgement[] wJ = new Judgement[N];
    /** True for a mine's red LOST word ({@link #mineWord}). */
    private final boolean[] wMine = new boolean[N];
    /** Measured width of each judgement's at-lane word (0 until first drawn). */
    private final float[] wordW = new float[Judgement.values().length];
    private int wNext;

    private final float[] shX = new float[N];
    private final float[] shY = new float[N];
    private final float[] shT = new float[N];
    private int shNext;


    private float centreX;
    private float centreY;
    private float centreT = -100f;

    /** Creates the layer over the shared atlas. */
    FeedbackLayer(Draw draw) {
        this.draw = draw;
        this.batch = draw.batch();
        clear();
    }

    /** Clears every running effect. */
    void clear() {
        java.util.Arrays.fill(ringT, -100f);
        java.util.Arrays.fill(brT, -100f);
        java.util.Arrays.fill(dotT, -100f);
        java.util.Arrays.fill(chT, -100f);
        java.util.Arrays.fill(wT, -100f);
        java.util.Arrays.fill(shT, -100f);
        centreT = -100f;
    }

    // ------------------------------------------------------------------ triggers

    /** Contact ring at the finger x on the line: r 40 → 90 over 160 ms, white α .30 (.15 empty). */
    void contact(float x, float lineY, boolean judged, float now) {
        int k = ringNext;
        ringNext = (k + 1) % N;
        ringX[k] = x;
        ringY[k] = lineY;
        ringT[k] = now;
        ringA[k] = judged ? 0.30f : 0.15f;
    }

    /** Contact brackets: two 2×24 ticks at the resolved lane's edges, {@code ink1 @ .8}, 160 ms. */
    void brackets(float left, float right, float lineY, float now) {
        int k = brNext;
        brNext = (k + 1) % N;
        brL[k] = left;
        brR[k] = right;
        brY[k] = lineY;
        brT[k] = now;
    }

    /** Stray touch dot: 24 px, {@code ink1 @ .15}, 160 ms. */
    void stray(float x, float y, float now) {
        strayDot(x, y, now, false);
    }

    /** Tutorial demo of a stray touch (DESIGN §10.2): the same dot, brighter and longer, with a ring. */
    void strayDemo(float x, float y, float now) {
        strayDot(x, y, now, true);
    }

    private void strayDot(float x, float y, float now, boolean demo) {
        int k = dotNext;
        dotNext = (k + 1) % N;
        dotX[k] = x;
        dotY[k] = y;
        dotT[k] = now;
        dotDemo[k] = demo;
    }

    /**
     * ECHO chevron just outside the zone edge (line ± 120): EARLY points toward where the note
     * came from (ground ∧, sky ∨), LATE the other way; rises 12 units away from the line over
     * 300 ms and fades.
     */
    void chevron(float x, Line line, float lineY, boolean late, float now) {
        int k = chNext;
        chNext = (k + 1) % N;
        boolean sky = line == Line.UPPER;
        chX[k] = x;
        chY[k] = sky ? lineY - 120f : lineY + 120f;
        chAway[k] = sky ? -1f : 1f;
        chPointUp[k] = sky == late; // ground: EARLY ∧ (up), LATE ∨; sky: EARLY ∨, LATE ∧
        chLate[k] = late;
        chT[k] = now;
    }

    /** At-lane judgement word (setting "At lane"): 24 outside the zone edge, cap 22, 300 ms. */
    void laneWord(Judgement j, float x, Line line, float lineY, float now) {
        wMine[addWord(j, x, line, lineY, now)] = false;
    }

    /**
     * At-lane word of a touched MINE or a LANE MINE contact: the LOST word at the mine x, drawn
     * in {@link Theme#MINE_LOST} (redder than a missed note's LOST).
     */
    void mineWord(float x, Line line, float lineY, float now) {
        wMine[addWord(Judgement.LOST, x, line, lineY, now)] = true;
    }

    private int addWord(Judgement j, float x, Line line, float lineY, float now) {
        boolean sky = line == Line.UPPER;
        // a newer word in the same lane replaces the one still fading there
        for (int i = 0; i < N; i++) {
            if (wJ[i] != null && wTop[i] == sky && Math.abs(wX[i] - x) < 1f) wJ[i] = null;
        }
        int k = wNext;
        wNext = (k + 1) % N;
        wX[k] = x;
        wY[k] = sky ? lineY - FieldRenderer.zone() - 24f : lineY + FieldRenderer.zone() + 24f;
        wTop[k] = sky;
        wJ[k] = j;
        wT[k] = now;
        return k;
    }

    /**
     * True while an at-lane word overlaps the rect (x0..x1, y0..y1): the HUD fades the element
     * under it like the zone-overlap guard (DESIGN §5.1), since the words sit on the HUD rows.
     */
    boolean wordOverlaps(float x0, float x1, float y0, float y1, float now) {
        for (int k = 0; k < N; k++) {
            Judgement j = wJ[k];
            if (j == null) continue;
            float t = now - wT[k];
            if (t < 0f || t >= WORD_SEC) continue;
            float hw = (wordW[j.ordinal()] > 0f ? wordW[j.ordinal()] : 200f) * 0.5f + 8f;
            float wy0 = wTop[k] ? wY[k] - 22f : wY[k];
            float wy1 = wy0 + 22f;
            if (wX[k] + hw > x0 && wX[k] - hw < x1 && wy1 > y0 && wy0 < y1) return true;
        }
        return false;
    }

    /** Mine shock ring at the touch x on the line: r 30 → 150 over 220 ms, α .7 → 0. */
    void mineShock(float x, float lineY, float now) {
        int k = shNext;
        shNext = (k + 1) % N;
        shX[k] = x;
        shY[k] = lineY;
        shT[k] = now;
    }

    /** Combo-100 ring: {@code ring_thin} r 0 → 360 over 400 ms ({@code pow3Out}), α .25. */
    void centreRing(float x, float y, float now) {
        centreX = x;
        centreY = y;
        centreT = now;
    }

    // ------------------------------------------------------------------ drawing

    /** Draws rings, brackets, dots, chevrons and shocks (additive block). */
    void drawAdditive(float now) {
        for (int k = 0; k < N; k++) {
            float t = now - ringT[k];
            if (t >= 0f && t < CONTACT_SEC) {
                float p = t / CONTACT_SEC;
                float r = 40f + 50f * Anim.POW2_OUT.apply(p);
                draw.sprite(draw.ringSoft(), ringX[k], ringY[k], r * 4.76f, r * 4.76f, Theme.WHITE, ringA[k] * (1f - p));
            }
            t = now - brT[k];
            if (t >= 0f && t < CONTACT_SEC) {
                float a = 0.8f * (1f - t / CONTACT_SEC);
                draw.rect(brL[k] - 1f, brY[k] - 12f, 2f, 24f, Theme.INK1, a);
                draw.rect(brR[k] - 1f, brY[k] - 12f, 2f, 24f, Theme.INK1, a);
            }
            t = now - dotT[k];
            if (dotDemo[k]) {
                if (t >= 0f && t < DEMO_DOT_SEC) {
                    float p = t / DEMO_DOT_SEC;
                    draw.disc(dotX[k], dotY[k], 14f, Theme.INK1, 0.45f * (1f - p));
                    draw.ringSprite(dotX[k], dotY[k], 14f + 30f * Anim.POW2_OUT.apply(p), Theme.INK1, 0.30f * (1f - p));
                }
            } else if (t >= 0f && t < CONTACT_SEC) {
                draw.disc(dotX[k], dotY[k], 12f, Theme.INK1, 0.15f * (1f - t / CONTACT_SEC));
            }
            t = now - chT[k];
            if (t >= 0f && t < CHEVRON_SEC) {
                float p = t / CHEVRON_SEC;
                float y = chY[k] + chAway[k] * 12f * Anim.POW2_OUT.apply(p);
                float a = p < 0.5f ? 1f : 1f - (p - 0.5f) / 0.5f;
                Color c = chLate[k] ? Theme.LATE : Theme.EARLY;
                float tip = chPointUp[k] ? 5f : -5f;
                draw.line(chX[k] - 9f, y - tip, chX[k], y + tip, 3f, c, a);
                draw.line(chX[k], y + tip, chX[k] + 9f, y - tip, 3f, c, a);
            }
            t = now - shT[k];
            if (t >= 0f && t < SHOCK_SEC) {
                float p = t / SHOCK_SEC;
                float r = 30f + 120f * Anim.POW2_OUT.apply(p);
                draw.sprite(draw.ringSoft(), shX[k], shY[k], r * 4.76f, r * 4.76f, Theme.MINE, 0.7f * (1f - p));
            }
        }
        float t = now - centreT;
        if (t >= 0f && t < CENTRE_RING_SEC) {
            float p = t / CENTRE_RING_SEC;
            float r = 360f * Anim.POW3_OUT.apply(p);
            if (r > 2f) draw.ringSprite(centreX, centreY, r, Theme.INK1, 0.25f * (1f - p));
        }
        batch.setColor(Color.WHITE);
    }

    /** Draws the at-lane judgement words (HUD text pass). */
    void drawText(Batch b, Fonts fonts, float now) {
        for (int k = 0; k < N; k++) {
            float t = now - wT[k];
            if (t < 0f || t >= WORD_SEC || wJ[k] == null) continue;
            float a = t < 0.2f ? 1f : 1f - (t - 0.2f) / 0.1f;
            Judgement j = wJ[k];
            Fonts.Role role = GameplayHud.wordRole(j);
            if (wordW[j.ordinal()] == 0f) wordW[j.ordinal()] = fonts.width(role, 22f, Fonts.GlyphSet.WORDS, j.display);
            float alpha = a * (j == Judgement.LOST ? 0.85f : 1f);
            int align = com.badlogic.gdx.utils.Align.center | (wTop[k] ? com.badlogic.gdx.utils.Align.top : 0);
            fonts.draw(b, role, 22f, Fonts.GlyphSet.WORDS, j.display, wX[k], wY[k], align,
                wMine[k] ? Theme.MINE_LOST : j.color(), MathUtils.clamp(alpha, 0f, 1f));
        }
    }
}
