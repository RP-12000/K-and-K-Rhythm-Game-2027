package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;

/**
 * TOP 10 和 LOG 列表行共用的绘制部件：歌曲缩略图（没有曲绘时画难度色方块加曲名首字母）、难度标签和评级胶囊。
 *
 * <p>Shared pieces of the TOP 10 and LOG rows (DESIGN §8.2): the song thumbnail (jacket from
 * {@link ThumbCache}, else a procedural tile in the difficulty colour with the title's initial),
 * the difficulty chip and the grade pill. Static and allocation-free.
 */
public final class ChartRows {
    private static final Color TMP = new Color();
    private static final StringBuilder INITIAL = new StringBuilder(2);
    private static final StringBuilder LETTERS = new StringBuilder(3);
    /** Superscript "+" size and gap as fractions of the letter cap (as the result stamp). */
    private static final float PLUS_SCALE = 0.55f, PLUS_GAP = 0.04f;

    private ChartRows() {
    }

    /** Draws a {@code size}² thumbnail with its bottom-left corner at (x, y). */
    public static void thumb(Batch b, Draw d, Fonts f, ThumbCache thumbs, SongMeta song, String title, int diff,
                             float x, float y, float size, float alpha) {
        TextureRegion r = thumbs.get(song);
        if (r != null) {
            float old = b.getPackedColor();
            b.setColor(1f, 1f, 1f, alpha);
            b.draw(r, x, y, size, size);
            b.setPackedColor(old);
        } else {
            Color fill = Theme.difficultyFill(diff);
            d.roundRect(x, y, size, size, 10f, fill, 0.45f * alpha);
            d.roundRectGradient(x, y, size, size, 10f, Theme.SEAM, Theme.difficultyAccent(diff), 0.35f * alpha);
            INITIAL.setLength(0);
            INITIAL.append(ShellFormat.initialOf(title));
            f.draw(b, Fonts.Role.UI_BOLD, size * 0.38f, INITIAL, x + size * 0.5f, y + size * 0.5f,
                Align.center | Fonts.VCENTER, Theme.INK1, 0.85f * alpha);
        }
        d.roundRectStroke(x, y, size, size, 10f, 1.5f, Theme.STROKE, alpha);
    }

    /** Draws the difficulty chip with its left edge at {@code x}, centred on {@code cy}; returns its width. */
    public static float diffChip(Batch b, Draw d, Fonts f, int diff, float x, float cy, float alpha) {
        String name = Theme.DIFFICULTY_NAMES[Math.max(0, Math.min(3, diff))];
        float tw = f.width(Fonts.Role.UI_LABEL, 14f, name);
        float w = tw + 24f, h = 28f;
        d.roundRect(x, cy - h * 0.5f, w, h, 8f, Theme.difficultyFill(diff), 0.9f * alpha);
        float bw = Theme.difficultyBorderWidth(diff);
        Color border = Theme.difficultyBorder(diff);
        if (bw > 0f && border != null) d.roundRectStroke(x, cy - h * 0.5f, w, h, 8f, bw, border, alpha);
        f.draw(b, Fonts.Role.UI_LABEL, 14f, name, x + w * 0.5f, cy, Align.center | Fonts.VCENTER,
            Theme.difficultyText(diff), alpha);
        return w;
    }

    /**
     * Draws a grade pill centred at (cx, cy) in the grade colour (R and R+ light blue); a trailing
     * "+" is a superscript like the result stamp, with no sweep (Kelvin 2026-10-17 16:40).
     */
    public static void gradePill(Batch b, Draw d, Fonts f, String grade, float cx, float cy, float time, float alpha) {
        if (grade == null) return;
        Theme.gradeColor(grade, time, TMP);
        int n = grade.length();
        boolean plus = n > 1 && grade.charAt(n - 1) == '+';
        LETTERS.setLength(0);
        LETTERS.append(grade, 0, plus ? n - 1 : n);
        float cap = 20f, pc = cap * PLUS_SCALE, gap = plus ? cap * PLUS_GAP : 0f;
        float lw = f.width(Fonts.Role.NUMBER, cap, Fonts.GlyphSet.GRADE, LETTERS);
        float tw = lw + gap + (plus ? f.width(Fonts.Role.NUMBER, pc, Fonts.GlyphSet.GRADE, "+") : 0f);
        float w = Math.max(72f, tw + 28f), h = 40f;
        d.pill(cx - w * 0.5f, cy - h * 0.5f, w, h, TMP, 0.18f * alpha);
        d.roundRectStroke(cx - w * 0.5f, cy - h * 0.5f, w, h, h * 0.5f, 2f, TMP, alpha);
        float x0 = cx - tw * 0.5f;
        f.draw(b, Fonts.Role.NUMBER, cap, Fonts.GlyphSet.GRADE, LETTERS, x0, cy, Align.left | Fonts.VCENTER, TMP,
            alpha);
        if (plus) f.draw(b, Fonts.Role.NUMBER, pc, Fonts.GlyphSet.GRADE, "+", x0 + lw + gap, cy + cap * 0.5f,
            Align.left | Align.top, TMP, alpha);
    }
}
