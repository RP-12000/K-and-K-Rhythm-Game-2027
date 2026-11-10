package org.kelvinizer.elements;

/**
 * 谱面格式 v7 的键种类：水滴、Drag、水雷、水流、轨道水雷、浪花、浪花水雷、潮汐、潮汐水雷。
 *
 * <p>The key kinds of chart format v7 (Kelvin 2026-11-09 18:44, items 2–5) and their names in the file.
 */
public enum KeyKind {
    TAP("tap"),
    DRAG("drag"),
    MINE("mine"),
    STREAM("stream"),
    STREAM_MINE("streamMine"),
    WAVE("wave"),
    WAVE_MINE("waveMine"),
    TIDE("tide"),
    TIDE_MINE("tideMine");

    /** The {@code "type"} in the file. */
    public final String json;

    KeyKind(String json) {
        this.json = json;
    }

    /** @return true for the kinds with a height {@code H} (stream, stream mine, tide, tide mine) */
    public boolean hasHeight() {
        return this == STREAM || this == STREAM_MINE || this == TIDE || this == TIDE_MINE;
    }

    /** @return true for the kinds that span a set of lanes (wave, wave mine, tide, tide mine) */
    public boolean spans() {
        return this == WAVE || this == WAVE_MINE || this == TIDE || this == TIDE_MINE;
    }

    /** @return true for the mines */
    public boolean mine() {
        return this == MINE || this == STREAM_MINE || this == WAVE_MINE || this == TIDE_MINE;
    }

    /** @return the kind with this {@code "type"} name, or null */
    public static KeyKind of(String json) {
        for (KeyKind k : values()) {
            if (k.json.equals(json)) return k;
        }
        return null;
    }
}
