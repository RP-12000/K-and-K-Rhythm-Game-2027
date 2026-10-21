package org.kelvinizer.elements;

/**
 * 音符所属的判定线：{@link #LOWER} 是地面线（默认 y 120），{@link #UPPER} 是天空线（默认 y 1080，1920 × 1200 的谱面画面）。
 * 地面线的音符向下落到线上，天空线的音符向上升到线上；两条线共用同一组轨道。
 *
 * <p>The judgement line a note belongs to (ARCHITECTURE §13.2). Both lines share one lane set.
 *
 * <ul>
 *   <li>{@link #LOWER}: the ground line (default y 120); its notes travel down onto it.</li>
 *   <li>{@link #UPPER}: the sky line (default y 1080 of the 1920 × 1200 chart frame); its notes travel up onto it.</li>
 * </ul>
 * Arrays indexed by line use {@link #ordinal()}.
 */
public enum Line {
    /** Ground line, JSON {@code "lower"} (the default). */
    LOWER("lower"),
    /** Sky line, JSON {@code "upper"}. */
    UPPER("upper");

    /** Number of judgement lines. */
    public static final int COUNT = 2;
    private static final Line[] VALUES = values();

    /** Name used in chart JSON ({@code "line"}). */
    public final String json;

    Line(String json) {
        this.json = json;
    }

    /** @return the line whose JSON name equals {@code name} ignoring case and surrounding blanks, or null. */
    public static Line fromJson(String name) {
        if (name == null) return null;
        String s = name.trim();
        for (Line l : VALUES) {
            if (l.json.equalsIgnoreCase(s)) return l;
        }
        return null;
    }

    /** @return the line with this ordinal (0 = lower, 1 = upper). */
    public static Line of(int ordinal) {
        return VALUES[ordinal];
    }

    /** @return true for the sky line. */
    public boolean isUpper() {
        return this == UPPER;
    }

    /** @return the other line. */
    public Line other() {
        return this == LOWER ? UPPER : LOWER;
    }
}
