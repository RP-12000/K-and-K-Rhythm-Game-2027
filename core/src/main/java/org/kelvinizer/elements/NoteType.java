package org.kelvinizer.elements;

/**
 * v2 谱面的四种音符类型及其中英文名称；带时长的形式（水流、轨道水雷、潮汐水流）也在这里命名。
 *
 * <p>The four note types of chart format v2 (ARCHITECTURE §13.2).
 *
 * <p>The JSON names ({@link #json}) are fixed and read case-insensitively. The display names
 * live only here; the NOTE GUIDE (音符图鉴) panel ({@code NoteGuide}, off the Settings pages since
 * 2026-10-18 05:14) reads them, so renaming a note type is a single edit.
 */
public enum NoteType {
    /** Single tap in a lane: DROP / 水滴. */
    TAP("tap", "DROP", "水滴", null, null),
    /** Tap-and-hold in a lane, the same finger to the end: STREAM / 水流. */
    HOLD("hold", "STREAM", "水流", "STREAM", "水流"),
    /**
     * Must not be touched; never judged by taps: MINE / 水雷. With a {@code duration} it is a
     * LANE MINE / 轨道水雷 that blocks its whole lane until it ends (ARCHITECTURE §20).
     */
    MINE("mine", "MINE", "水雷", "LANE MINE", "轨道水雷"),
    /**
     * Tap anywhere inside its line's zone, at any x: TIDE / 潮汐. With a {@code duration} it is a
     * TIDE STREAM / 潮汐水流 (any finger on the line until it ends; ARCHITECTURE §19).
     */
    UNIVERSAL("universal", "TIDE", "潮汐", "TIDE STREAM", "潮汐水流");

    private static final NoteType[] VALUES = values();

    /** Name used in chart JSON ({@code "type"}). */
    public final String json;
    /** English display name. */
    public final String display;
    /** Chinese display name. */
    public final String displayZh;
    /**
     * English name of the type's sustained form (a note with a {@code duration}): STREAM for
     * holds, LANE MINE for mines, TIDE STREAM for universal notes; null for taps.
     */
    public final String streamDisplay;
    /** Chinese name of the sustained form (水流, 轨道水雷, 潮汐水流), or null. */
    public final String streamDisplayZh;

    NoteType(String json, String display, String displayZh, String streamDisplay, String streamDisplayZh) {
        this.json = json;
        this.display = display;
        this.displayZh = displayZh;
        this.streamDisplay = streamDisplay;
        this.streamDisplayZh = streamDisplayZh;
    }

    /** @return the type whose JSON name equals {@code name} ignoring case and surrounding blanks, or null. */
    public static NoteType fromJson(String name) {
        if (name == null) return null;
        String s = name.trim();
        for (NoteType t : VALUES) {
            if (t.json.equalsIgnoreCase(s)) return t;
        }
        return null;
    }

    /** @return the type with this ordinal (no allocation, unlike {@code values()}). */
    public static NoteType of(int ordinal) {
        return VALUES[ordinal];
    }

    /** @return number of note types. */
    public static int count() {
        return VALUES.length;
    }

    /**
     * @return true for notes that are judged and counted in {@code totalNotes} (tap, hold,
     *         universal); false for mines
     */
    public boolean judged() {
        return this != MINE;
    }

    /** @return true for notes that belong to a lane (tap, hold, mine); false for universal notes. */
    public boolean inLane() {
        return this != UNIVERSAL;
    }

    /**
     * @return true for types that keep a {@code duration} (hold; mine, whose sustained form is
     *         the LANE MINE; universal, whose sustained form is the TIDE STREAM)
     */
    public boolean sustainable() {
        return streamDisplay != null;
    }

    /** @return the English name of the plain form, or of the sustained form when {@code stream}. */
    public String display(boolean stream) {
        return stream && streamDisplay != null ? streamDisplay : display;
    }

    /** @return the Chinese name of the plain form, or of the sustained form when {@code stream}. */
    public String displayZh(boolean stream) {
        return stream && streamDisplayZh != null ? streamDisplayZh : displayZh;
    }
}
