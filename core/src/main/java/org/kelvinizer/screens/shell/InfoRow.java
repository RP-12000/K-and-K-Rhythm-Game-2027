package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Panel;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 设置列表里的只读行：分节标题（小号灰字加一条细线），或信息行（标题、可选的第二行和右侧数值）。
 * ABOUT 页用信息行列出版本、致谢和许可证；这种行不接收输入。还有 OTHER 最后的品牌行（{@link #brand}）：左边大字
 * K &amp; K，右边版本号，占满列表剩下的高度，字跟着行高自动缩放，所以其他各行放得下时这一页就不用滚动。
 *
 * <p>A read-only line of the Settings list: either a section header (UI_LABEL 16 {@code ink3} plus
 * a hairline, DESIGN §1.6 style) or an information line with a title (UI_BOLD 22), an optional
 * second line (UI_REGULAR 18 {@code ink2}) and an optional value at the right (UI 20), used by
 * ABOUT for version, credits and licences. Never takes input.
 *
 * <p>{@link #brand} is the row that ends OTHER (Kelvin 2026-10-18 05:58: 「根据屏幕比例自动调整大小吧，K &amp; K放左侧，
 * version放右侧」): the title at the left in the DISPLAY_SOFT logo face of the welcome screen, the value at the
 * right (UI, {@code ink2}), both centred in the row, without the hairline. The row {@link #fills() fills} what the
 * list has left, between {@value #BRAND_MIN_H} and {@value #BRAND_HEIGHT} × uiScale, so OTHER fits wherever its
 * other rows leave the minimum (DESIGN §24.12). The title is cap {@value #BRAND_CAP} × uiScale (the 05:39 size) or
 * half the row's height when that is less, and the value keeps its 05:39 ratio to it ({@value #BRAND_VALUE_CAP} at
 * full size, never under the minimum essential cap). From 05:28 to 05:58 the two were centred one over the other.
 */
public class InfoRow extends SettingRow {
    /** Largest cap of the brand row's title (72 until Kelvin 2026-10-18 05:39: 「还可以再大一点」). */
    static final float BRAND_CAP = 100f;
    /** Cap of the brand row's value beside a title of {@link #BRAND_CAP}. */
    static final float BRAND_VALUE_CAP = 30f;
    /** Tallest brand row (200 until 05:39: 「往下挪一点」); shorter when the list has less left. */
    static final float BRAND_HEIGHT = 300f;
    /** Shortest brand row (one information line). */
    static final float BRAND_MIN_H = 64f;

    private final boolean header;
    private boolean brand;
    private CharSequence value;

    private InfoRow(Main game, CharSequence label, CharSequence desc, boolean header) {
        super(game, label, desc, false);
        this.header = header;
    }

    /** A section header. */
    public static InfoRow header(Main game, CharSequence text) {
        return new InfoRow(game, text, null, true);
    }

    /** An information line: {@code title}, optional {@code second} line and optional right {@code value}. */
    public static InfoRow line(Main game, CharSequence title, CharSequence second, CharSequence value) {
        InfoRow r = new InfoRow(game, title, second, false);
        r.value = value;
        // credits are content, not a hint: ink2 stays legible over the bright surface backdrop
        r.descColor = Theme.INK2;
        return r;
    }

    /** The brand row: {@code title} large at the left, {@code value} at the right, filling the list's spare height. */
    public static InfoRow brand(Main game, CharSequence title, CharSequence value) {
        InfoRow r = new InfoRow(game, title, null, false);
        r.value = value;
        r.brand = true;
        return r;
    }

    @Override
    public float naturalHeight() {
        if (header) return 64f;
        if (brand) return BRAND_HEIGHT;
        return desc != null ? 84f : 64f;
    }

    @Override
    protected float minHeight() {
        return 0f;
    }

    @Override
    public boolean fills() {
        return brand;
    }

    @Override
    public float fillHeight(float spare, float k) {
        return Math.max(BRAND_MIN_H * k, Math.min(BRAND_HEIGHT * k, spare));
    }

    @Override
    public void drawShapes(Batch b, float alpha) {
        if (!header && !brand) Panel.divider(draw, x, x + w, y, 0.5f * alpha);
    }

    @Override
    public void drawLabels(Batch b, float alpha) {
        if (header) {
            Panel.header(b, draw, fonts, label, x, y + 18f * k, x + w, alpha);
            return;
        }
        if (brand) {
            // the 05:39 sizes where the row has room; a shorter row keeps the title at half its height
            float cap = Math.min(BRAND_CAP * k, h * 0.5f), cy = y + h * 0.5f;
            fonts.draw(b, Fonts.Role.DISPLAY_SOFT, cap, Fonts.GlyphSet.LOGO, label, x, cy, Align.left | Fonts.VCENTER,
                Theme.INK1, alpha);
            if (value != null) {
                fonts.drawFit(b, Fonts.Role.UI, Math.max(Ui.minEssentialCap(), cap * BRAND_VALUE_CAP / BRAND_CAP),
                    value, x + w, cy, Align.right | Fonts.VCENTER, w * 0.45f, 0.75f, Theme.INK2, alpha);
            }
            return;
        }
        float valueW = 0f;
        if (value != null) {
            valueW = fonts.drawFit(b, Fonts.Role.UI, 20f * k, value, x + w, y + h * 0.5f, Align.right | Fonts.VCENTER,
                w * 0.45f, 0.75f, Theme.INK2, alpha) + 32f * k;
        }
        float maxW = w - valueW;
        if (desc != null) {
            fonts.drawFit(b, Fonts.Role.UI_BOLD, 22f * k, label, x, y + h * 0.5f + 14f * k, Align.left | Fonts.VCENTER,
                maxW, 0.7f, Theme.INK1, alpha);
            fonts.drawFit(b, Fonts.Role.UI_REGULAR, 18f * k, desc, x, y + h * 0.5f - 18f * k,
                Align.left | Fonts.VCENTER, maxW, 0.75f, descColor, alpha);
        } else {
            fonts.drawFit(b, Fonts.Role.UI_BOLD, 22f * k, label, x, y + h * 0.5f, Align.left | Fonts.VCENTER, maxW,
                0.7f, Theme.INK1, alpha);
        }
    }
}
