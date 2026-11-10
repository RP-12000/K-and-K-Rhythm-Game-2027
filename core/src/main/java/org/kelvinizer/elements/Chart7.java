package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 谱面格式 v7（简单函数）：轨道、键、水幕水帘、镜头位置和视角；其他（判定线、海面、全屏判定……）都没有了。
 *
 * <p>A chart of format v7 (Kelvin 2026-11-09 18:44–18:45): lanes ({@link Lane7}), keys ({@link Key7}), veils and
 * curtains ({@link Volume7}), the camera position {@link #camera} (P) and its field of view {@link #fov}. The camera
 * always looks at the chart origin (0, 0, 0) with +y up. The song values (BPM and its changes, offset) stay in
 * {@code song.json}; {@link #editor} is the chart editor's own block, kept as read.
 */
public final class Chart7 {
    /**
     * The default camera distance from the origin: the 1200-tall frame fills the 11° view at the columns' front
     * (600 / tan 5.5°), which lies half a column's depth (120) before the origin.
     */
    public static final float CAMERA_DIST = 6351.24f;

    /** The charter's name and the chart constant (as written in the file), or null. */
    public String charter, constant;
    public final List<Lane7> lanes = new ArrayList<>();
    public final List<Key7> keys = new ArrayList<>();
    public final List<Volume7> volumes = new ArrayList<>();
    /** The camera position P, default straight on at {@link #CAMERA_DIST}. */
    public final Fn3 camera = new Fn3(0f, 0f, -CAMERA_DIST);
    /** The default field of view, degrees. */
    public static final float FOV = 11f;
    /**
     * The camera's vertical field of view in degrees (Kelvin 2026-11-09 22:28 「加视角」), default {@link #FOV};
     * clamped to 1..150 where used. Drawing and the touch rays use it, the HUD does not.
     */
    public final Fn fov = new Fn(FOV);
    /** The chart editor's block: maps, lists, strings, numbers, booleans and nulls. */
    public Map<String, Object> editor = new LinkedHashMap<>();

    /** @return a new chart with the six default lanes (ids −3..−1, 1..3 left to right at x −700 … 700) */
    public static Chart7 empty() {
        Chart7 c = new Chart7();
        int[] ids = {-3, -2, -1, 1, 2, 3};
        for (int i = 0; i < ids.length; i++) c.lanes.add(new Lane7(ids[i], -700f + 280f * i));
        return c;
    }
}
