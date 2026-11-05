package org.kelvinizer.profile;

/**
 * 玩家名字的宽度：中日韩等全角字符算 1.5，其他算 1，最多 15（15 个英文或 10 个中文）。超长的旧名字显示时末尾加 “…”。
 *
 * <p>Player name width (Kelvin 2026-10-30 20:20 「最多只能用15个英文字符或者10个中文字符的宽度」): a wide character (CJK,
 * kana, hangul, full-width forms) counts 1.5, any other 1, and a name may use {@link #MAX_UNITS}. New names and renames
 * are cut to it; an older, longer name is kept and shown clipped with {@code …} ({@link #clip}). Pure Java.
 */
public final class NameWidth {
    /** Most units a name may use: 15 Latin or 10 CJK characters. */
    public static final float MAX_UNITS = 15f;

    private NameWidth() {
    }

    /** @return the width of code point {@code cp}: 1.5 for a wide character, else 1. */
    public static float of(int cp) {
        return wide(cp) ? 1.5f : 1f;
    }

    /** @return the width of {@code s} in units. */
    public static float units(CharSequence s) {
        float u = 0f;
        for (int i = 0; i < s.length(); ) {
            int cp = Character.codePointAt(s, i);
            i += Character.charCount(cp);
            u += of(cp);
        }
        return u;
    }

    /** @return the longest start of {@code s} no wider than {@code max} units. */
    public static String cut(String s, float max) {
        float u = 0f;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            u += of(cp);
            if (u > max) return s.substring(0, i);
            i += Character.charCount(cp);
        }
        return s;
    }

    /** @return {@code s} when it fits {@link #MAX_UNITS}, else its start and {@code …} within the limit. */
    public static String clip(String s) {
        if (units(s) <= MAX_UNITS) return s;
        return cut(s, MAX_UNITS - 1f).trim() + "…";
    }

    private static boolean wide(int cp) {
        Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
        return sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA
            || sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HANGUL
            || cp >= 0x3000 && cp <= 0x303F || cp >= 0xFF01 && cp <= 0xFF60 || cp >= 0xFFE0 && cp <= 0xFFE6;
    }
}
