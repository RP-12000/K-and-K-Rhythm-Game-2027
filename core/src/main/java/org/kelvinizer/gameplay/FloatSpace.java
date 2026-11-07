package org.kelvinizer.gameplay;

import org.kelvinizer.elements.Floating;
import org.kelvinizer.elements.LaneLayout;

/**
 * 悬浮键在屏幕上的位置和大小：把谱面里的坐标换成世界坐标，画悬浮键和判定它们都用它；不碰 GL。
 *
 * <p>Where a floating note is in the world (CHART_FORMAT §11), shared by the notes' renderer ({@link FloatNotes}) and
 * the engine that judges them ({@link GameEngine}): its centre's x goes through the lines' camera like a key's x, its y
 * gets the field's stretch like a line's y, and its diameter scales with the lines' scale × {@code ky}. Each at the
 * layout's own beat ({@link LaneLayout#beat}): the frame's when drawn, the input event's when judged. Pure Java with no
 * GL, so the engine stays headless.
 */
final class FloatSpace {
    private FloatSpace() {
    }

    /** @return world units per reference unit for a floating note's size: the lines' scale × {@code ky} */
    static float scale(LaneLayout lay) {
        float s = lay.frameScale() / Field.kx() * Field.ky();
        return s > 0f && s < Float.POSITIVE_INFINITY ? s : Field.ky();
    }

    /** @return the world x of the note's centre at the layout's beat */
    static float worldX(LaneLayout lay, Floating f) {
        return lay.x(f.xAt(lay.beat()));
    }

    /** @return the world y of the note's centre at the layout's beat */
    static float worldY(LaneLayout lay, Floating f) {
        return Field.y(f.yAt(lay.beat()));
    }

    /** @return the note's radius in world units at the layout's beat */
    static float radius(LaneLayout lay, Floating f) {
        return 0.5f * f.sizeAt(lay.beat()) * scale(lay);
    }

    /**
     * @return how far (x, y) is from the note's centre at the layout's beat, in radii (1: on its edge); +∞ when the
     *         note has no place (a value that is not a number)
     */
    static float reach(LaneLayout lay, Floating f, float x, float y) {
        float r = radius(lay, f);
        float dx = x - worldX(lay, f);
        float dy = y - worldY(lay, f);
        float d = (float) Math.sqrt(dx * dx + dy * dy) / r;
        return d >= 0f ? d : Float.POSITIVE_INFINITY; // NaN fails the test
    }
}
