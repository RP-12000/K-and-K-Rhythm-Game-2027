package org.kelvinizer.library;

import java.util.Collection;

/**
 * 四个谱面难度 SURFACE / SHALLOW / DEEP / ABYSS，显示为 Shallow / Medium / Deep / Abyss。名字也是存档的键，不能改。
 * 歌曲文件夹里的谱面文件从 2026-11-10 起和显示名一致：{@code Shallow.json}、{@code Medium.json}、{@code Deep.json}、
 * {@code Abyss.json}；旧命名 {@code Surface.json}、{@code Shallow.json}、{@code Deep.json}、{@code Abyss.json} 照样认，
 * 文件夹里有 {@code Surface.json} 就是旧命名（Kelvin 2026-11-10 05:50「把谱面文件的名字也都统一改了，改成
 * shallow/medium/deep」）。
 *
 * <p>Chart difficulties. Names are stable: they are the save keys ({@link #name()}). Each maps to a chart file
 * of a song folder: {@link #fileName()} in the naming that matches the displayed names, {@link #oldFileName()}
 * in the old one, which is still read; a folder holding {@code Surface.json} uses the old naming
 * ({@link #oldNames}).
 */
public enum Difficulty {
    SURFACE("Surface", "Shallow"),
    SHALLOW("Shallow", "Medium"),
    DEEP("Deep", "Deep"),
    ABYSS("Abyss", "Abyss");

    private static final Difficulty[] VALUES = values();

    private final String label;
    private final String fileName;
    private final String oldFileName;

    Difficulty(String label, String fileBase) {
        this.label = label;
        this.fileName = fileBase + ".json";
        this.oldFileName = label + ".json";
    }

    /** @return chart file name inside a song folder, e.g. {@code "Medium.json"} for SHALLOW. */
    public String fileName() {
        return fileName;
    }

    /** @return chart file name in the old naming, e.g. {@code "Shallow.json"} for SHALLOW. */
    public String oldFileName() {
        return oldFileName;
    }

    /** @return {@link #oldFileName()} when {@code oldNames}, else {@link #fileName()}. */
    public String fileName(boolean oldNames) {
        return oldNames ? oldFileName : fileName;
    }

    /**
     * @param fileNames the file names in one song folder
     * @return true when the folder uses the old naming: it holds {@code Surface.json} (any case)
     */
    public static boolean oldNames(Collection<String> fileNames) {
        for (String n : fileNames) if (SURFACE.oldFileName.equalsIgnoreCase(n)) return true;
        return false;
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
