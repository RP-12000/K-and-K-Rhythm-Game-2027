package org.kelvinizer.elements;

/**
 * 谱面的音符类型及其中英文名称；带时长的形式（水流、轨道水雷、潮汐、潮汐水雷）也在这里命名。
 *
 * <p>The note types of chart format v2 (ARCHITECTURE §13.2), plus the TIDE MINE (2026-10-31) and the DRAG
 * (2026-11-05, kept in the 8-lane game of 7.190).
 *
 * <p>The JSON names ({@link #json}) are fixed and read case-insensitively. The display names
 * live only here (the Charter shows them as well), so renaming a note type is a single edit.
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
     * Tap anywhere inside its line's zone, at any x: TIDE / 浪花. With a {@code duration} it is a
     * TIDE STREAM / 潮汐 (a finger of its own on the line until it ends; ARCHITECTURE §19).
     */
    UNIVERSAL("universal", "TIDE", "浪花", "TIDE STREAM", "潮汐"),
    /**
     * The mine form of a TIDE (Kelvin 2026-10-31): TIDE MINE / 浪花水雷, touched by a press anywhere in its line's
     * zone. With a {@code duration} it is a TIDE STREAM MINE / 潮汐水雷 that blocks that whole zone until it
     * ends, as a LANE MINE blocks its lane.
     */
    TIDE_MINE("tideMine", "TIDE MINE", "浪花水雷", "TIDE STREAM MINE", "潮汐水雷"),
    /**
     * DRAG (Kelvin 2026-11-05: 「加 Drag，手指在对应判定线就判」): never tapped; a finger at it on its line when it
     * arrives (resting there early, or sliding in) is a RESONANCE, none is LOST; there is no ECHO. It sits in a lane
     * like a tap (Kelvin 2026-11-08 02:51 「也上轨道」) or, on a sea, at its own x ({@link Note#laneless()}); otherwise
     * moved like a tap. Last in the enum, so the older types keep their ordinals.
     */
    DRAG("drag", "DRAG", "Drag", null, null);

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
    /** Chinese name of the sustained form (水流, 轨道水雷, 潮汐), or null. */
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
     *         universal, drag); false for mines (MINE, TIDE MINE)
     */
    public boolean judged() {
        return this != MINE && this != TIDE_MINE;
    }

    /**
     * @return true for keys, drawn as keys and not as TIDEs (tap, hold, mine, drag): each belongs to a lane unless it
     *         has its own x ({@link Note#laneless()}); false for universals and TIDE MINEs
     */
    public boolean inLane() {
        return this != UNIVERSAL && this != TIDE_MINE;
    }

    /**
     * @return true for types that keep a {@code duration} (hold; mine, whose sustained form is
     *         the LANE MINE; universal, whose sustained form is the TIDE STREAM)
     */
    public boolean sustainable() {
        return streamDisplay != null;
    }
}
