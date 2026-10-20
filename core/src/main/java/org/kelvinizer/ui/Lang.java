package org.kelvinizer.ui;

/**
 * 界面语言：设置里 LANGUAGE 选「中文」时，除选歌界面（洞穴地图）以外的界面文字都换成中文。
 * {@link #of} 把一对中英文包成跟着语言走的文字，界面只建一次，换语言后下一帧就换字。
 *
 * <p>Interface language (Kelvin 2026-10-18 00:24: with 中文 every screen except the song select
 * shows Chinese). {@code Main.applySettings()} pushes the active diver's {@code Settings.language}
 * through {@link #set(boolean)}. {@link #of(String, String)} wraps a pair in a {@link Text}, a
 * {@link CharSequence} that reads the current language on every call, so rows, buttons, tabs and
 * titles built once relabel themselves without being rebuilt (layouts that measure text re-run
 * anyway, because {@code applySettings} re-lays out every screen). {@link #t(String, String)}
 * picks one of the two strings now, for text drawn every frame or appended to a builder. The map
 * keeps its English but for the rise ring's word and the titles of the screens it opens. Nothing
 * here allocates after construction.
 */
public final class Lang {
    private static boolean zh;

    private Lang() {
    }

    /** Sets the language; true = Chinese. */
    public static void set(boolean chinese) {
        zh = chinese;
    }

    /** True while the interface language is Chinese. */
    public static boolean zh() {
        return zh;
    }

    /** {@code en} or {@code zh}, whichever the current language shows. */
    public static String t(String en, String zh) {
        return Lang.zh ? zh : en;
    }

    /** A label that follows the language: {@code en} in English, {@code zh} in Chinese. */
    public static Text of(String en, String zh) {
        return new Text(en, zh);
    }

    /** {@link #of} for each pair {@code en0, zh0, en1, zh1, …} (choice options, tab names). */
    public static CharSequence[] list(String... pairs) {
        CharSequence[] out = new CharSequence[pairs.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = new Text(pairs[2 * i], pairs[2 * i + 1]);
        return out;
    }

    /**
     * 跟着界面语言变的文字。
     *
     * <p>A pair of strings read as the current language's one. Equality is identity, like a
     * {@link StringBuilder}; compare {@link #toString()} when the text matters.
     */
    public static final class Text implements CharSequence {
        /** English text. */
        public final String en;
        /** Chinese text. */
        public final String zh;

        private Text(String en, String zh) {
            this.en = en;
            this.zh = zh;
        }

        private String now() {
            return Lang.zh ? zh : en;
        }

        @Override
        public int length() {
            return now().length();
        }

        @Override
        public char charAt(int index) {
            return now().charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return now().subSequence(start, end);
        }

        @Override
        public String toString() {
            return now();
        }
    }
}
