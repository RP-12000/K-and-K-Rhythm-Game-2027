package org.kelvinizer.library;

/**
 * 四个谱面难度 SURFACE / SHALLOW / DEEP / ABYSS，对应歌曲文件夹里的 {@code Surface.json} … {@code Abyss.json}。
 * 名字也是存档的键，不能改。
 *
 * <p>Chart difficulties. Names are stable: they are the save keys ({@link #name()}) and map to
 * the chart file of each song folder ({@link #fileName()}).
 */
public enum Difficulty {
    SURFACE("Surface"),
    SHALLOW("Shallow"),
    DEEP("Deep"),
    ABYSS("Abyss");

    private static final Difficulty[] VALUES = values();

    private final String label;
    private final String fileName;

    Difficulty(String label) {
        this.label = label;
        this.fileName = label + ".json";
    }

    /** @return chart file name inside a song folder, e.g. {@code "Surface.json"}. */
    public String fileName() {
        return fileName;
    }

    /** @return title-case label as used by the chart files' {@code level} field, e.g. "Deep". */
    public String label() {
        return label;
    }

    /** @return the difficulty with the given ordinal (no allocation). */
    public static Difficulty byOrdinal(int ordinal) {
        return VALUES[ordinal];
    }

    /** @return number of difficulties. */
    public static int count() {
        return VALUES.length;
    }

    /**
     * Parses a difficulty name case-insensitively ("DEEP", "Deep", "deep.json").
     *
     * @return the difficulty, or {@code null} when the text matches none
     */
    public static Difficulty parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.regionMatches(true, Math.max(0, t.length() - 5), ".json", 0, 5)) {
            t = t.substring(0, t.length() - 5);
        }
        for (Difficulty d : VALUES) {
            if (d.name().equalsIgnoreCase(t)) return d;
        }
        return null;
    }
}
