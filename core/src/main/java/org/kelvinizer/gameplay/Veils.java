package org.kelvinizer.gameplay;

import org.kelvinizer.elements.Veil;

/**
 * 漩涡在屏幕上的位置：形状先按场地拉伸（横向乘 kx、纵向乘 ky），再整体绕中心刚性旋转，所以不会斜切变形；判定和画面共用这一套换算。
 *
 * <p>Where a {@link Veil} is on screen (ARCHITECTURE §22): its outline is authored in the reference frame, stretched
 * with the field ({@code x · kx}, {@code y · ky}, like every other chart event — Kelvin 2026-11-04) and then turned
 * about the centre as a rigid body, so a rotated veil is never sheared, while a full-screen curtain still covers the
 * screen on any aspect. The engine's hit test ({@link #contains}) and the renderer share this mapping, so what blocks
 * is exactly the outline drawn without the veil's wobble. Static, no allocation.
 */
public final class Veils {
    private Veils() { }

    /**
     * @return true when world point (x, y) lies inside veil v at {@code beat}: v blocks then ({@link Veil#blocksAt})
     *         and the point is within its exact outline
     */
    public static boolean contains(Veil v, float x, float y, float beat) {
        if (!v.blocksAt(beat)) return false;
        // its size factors and card flip block as drawn (Kelvin 2026-11-07 22:54); its depth is drawing only
        float fx = v.shapeX(beat);
        float fy = v.shapeY(beat);
        if (!(fx > 1e-3f) || !(fy > 1e-3f)) return false;
        float dx = x - Field.x(v.xAt(beat));
        float dy = y - Field.y(v.yAt(beat));
        double a = Math.toRadians(v.rotationAt(beat));
        float c = (float) Math.cos(a), sn = (float) Math.sin(a);
        // turn the offset back (rigid), then undo the field's stretch: the outline is stretched, then turned
        float lx = (dx * c + dy * sn) / (Field.kx() * fx);
        float ly = (dy * c - dx * sn) / (Field.ky() * fy);
        float e = v.extentAt(beat);
        if (lx * lx + ly * ly > e * e * 1.0001f + 1e-3f) return false;
        return v.containsAt(lx, ly, beat);
    }

    /** @return true when world point (x, y) lies inside any of {@code veils} at {@code beat}. */
    public static boolean containsAny(Veil[] veils, float x, float y, float beat) {
        for (int i = 0; i < veils.length; i++) {
            if (contains(veils[i], x, y, beat)) return true;
        }
        return false;
    }
}
