package org.kelvinizer.library;

/**
 * 定数的显示规则：只显示整数，小数 .0–.3 在右上角加小号“-”，.4–.6 不加，.7–.9 加“+”
 * （16.2 → 16⁻，16.4 → 16，16.8 → 16⁺）。排序、筛选、评分和经验仍用精确的定数。
 *
 * <p>How a chart constant is shown to players (Kelvin 2026-10-13 23:11, 23:12): the whole number
 * with a superscript mark from the tenths digit: {@link #MINUS} for .0–.3, none for .4–.6 and
 * {@link #PLUS} for .7–.9. The value is rounded to one decimal first (16.96 reads 17⁻). Text that
 * is not a number is shown as authored, with no mark. The superscript's size, lift and gap are
 * {@code Theme.CONST_MARK_*}. Sorting, filters, rating and EXP keep the exact value.
 */
public final class ConstantLabel {
    /** The marks: the lower, middle and upper part of a whole number. */
    public static final String MINUS = "-", NONE = "", PLUS = "+";
    private static final int NAN = Integer.MIN_VALUE;

    private ConstantLabel() {
    }

    /**
     * The whole number shown for {@code text} ("16" for "16.2"); {@code text} itself when it is not a number, null for
     * null.
     */
    public static String base(String text) {
        int t = tenths(text);
        return t == NAN ? text : Integer.toString(Math.floorDiv(t, 10));
    }

    /**
     * The superscript mark of {@code text}: {@link #MINUS}, {@link #NONE} or {@link #PLUS} ({@link #NONE} when it is
     * not a number).
     */
    public static String mark(String text) {
        int t = tenths(text);
        if (t == NAN) return NONE;
        int d = Math.floorMod(t, 10);
        return d <= 3 ? MINUS : d >= 7 ? PLUS : NONE;
    }

    /** {@code text} in tenths (rounded), or {@link #NAN} when it is not a finite number. */
    private static int tenths(String text) {
        if (text == null) return NAN;
        try {
            double v = Double.parseDouble(text.trim());
            return Double.isNaN(v) || Math.abs(v) > 1e6 ? NAN : (int) Math.round(v * 10.0);
        } catch (NumberFormatException e) {
            return NAN;
        }
    }
}
