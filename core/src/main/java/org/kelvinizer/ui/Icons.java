package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.FloatArray;
import space.earlygrey.shapedrawer.JoinType;
import space.earlygrey.shapedrawer.ShapeDrawer;

/**
 * 程序绘制的图标（齿轮、暂停、重试、返回、勾、锁等）：用 ShapeDrawer 在 64 单位网格上画圆头线条，可缩放到任意大小。
 * 不用贴图，也不分配内存。
 *
 * <p>Procedural icons (DESIGN §1.6): drawn with the ShapeDrawer on a 64-unit grid with 5-unit
 * strokes and round caps, scaled to any size. No textures, no allocation.
 */
public final class Icons {
    private Icons() { }

    /** Available icons. */
    public enum Icon {
        GEAR, FUNNEL, HELP, CROSSHAIR, CHEVRON_LEFT, CHEVRON_RIGHT, CHEVRON_UP, CHEVRON_DOWN, CHEVRONS_DOWN,
        PAUSE, RETRY, PENCIL, DICE, CLOSE, LIST, SPEAKER, PERSON, TRIANGLE_DOWN, LOCK, CHECK, PLUS, MINUS,
        BACK, SORT
    }

    private static final float STROKE = 5f;
    private static final FloatArray PATH = new FloatArray(64);
    private static final float[] FUNNEL_PTS = {-24f, 22f, 24f, 22f, 6f, -2f, 6f, -20f, -6f, -13f, -6f, -2f};
    private static final float[] CHEV_L_PTS = {7f, 16f, -9f, 0f, 7f, -16f};
    private static final float[] CHEV_R_PTS = {-7f, 16f, 9f, 0f, -7f, -16f};
    private static final float[] CHEV_U_PTS = {-16f, -7f, 0f, 9f, 16f, -7f};
    private static final float[] CHEV_D_PTS = {-16f, 7f, 0f, -9f, 16f, 7f};
    private static final float[] CHEVS_A_PTS = {-15f, 14f, 0f, 1f, 15f, 14f};
    private static final float[] CHEVS_B_PTS = {-15f, -2f, 0f, -15f, 15f, -2f};
    private static final float[] CHECK_PTS = {-18f, 1f, -6f, -12f, 19f, 14f};
    private static final float[] BACK_PTS = {-4f, 17f, -20f, 0f, -4f, -17f};

    /**
     * Draws {@code icon} centred at (cx, cy), {@code size} world units across (the 64 grid is
     * scaled to it), in {@code color × alpha}. The batch must be drawing.
     */
    public static void draw(ShapeDrawer sd, Icon icon, float cx, float cy, float size, Color color, float alpha) {
        float k = size / 64f;
        float old = sd.getPackedColor();
        sd.setColor(color.r, color.g, color.b, color.a * alpha);
        float w = STROKE * k;
        switch (icon) {
            case GEAR: {
                ringStroke(sd, cx, cy, 15f * k, 8f * k);
                for (int i = 0; i < 8; i++) {
                    float a = i * MathUtils.PI2 / 8f;
                    float c = MathUtils.cos(a), s = MathUtils.sin(a);
                    sd.line(cx + c * 18f * k, cy + s * 18f * k, cx + c * 26f * k, cy + s * 26f * k, 9f * k);
                }
                break;
            }
            case FUNNEL:
                path(sd, cx, cy, k, w, false, FUNNEL_PTS);
                break;
            case HELP: {
                sd.arc(cx, cy + 9f * k, 12f * k, -1.0f, 3.6f, w, false, 20);
                float ex = cx + MathUtils.cos(-1.0f) * 12f * k, ey = cy + 9f * k + MathUtils.sin(-1.0f) * 12f * k;
                sd.line(ex, ey, cx, cy - 6f * k, w);
                cap(sd, cx, cy - 6f * k, w);
                cap(sd, cx + MathUtils.cos(2.6f) * 12f * k, cy + 9f * k + MathUtils.sin(2.6f) * 12f * k, w);
                sd.filledCircle(cx, cy - 18f * k, 3.6f * k);
                break;
            }
            case CROSSHAIR:
                ringStroke(sd, cx, cy, 16f * k, w);
                line(sd, cx, cy + 21f * k, cx, cy + 29f * k, w);
                line(sd, cx, cy - 21f * k, cx, cy - 29f * k, w);
                line(sd, cx + 21f * k, cy, cx + 29f * k, cy, w);
                line(sd, cx - 21f * k, cy, cx - 29f * k, cy, w);
                sd.filledCircle(cx, cy, 4f * k);
                break;
            case CHEVRON_LEFT:
                path(sd, cx, cy, k, w, true, CHEV_L_PTS);
                break;
            case CHEVRON_RIGHT:
                path(sd, cx, cy, k, w, true, CHEV_R_PTS);
                break;
            case CHEVRON_UP:
                path(sd, cx, cy, k, w, true, CHEV_U_PTS);
                break;
            case CHEVRON_DOWN:
                path(sd, cx, cy, k, w, true, CHEV_D_PTS);
                break;
            case CHEVRONS_DOWN:
                path(sd, cx, cy, k, w, true, CHEVS_A_PTS);
                path(sd, cx, cy, k, w, true, CHEVS_B_PTS);
                break;
            case PAUSE:
                sd.filledRectangle(cx - 13f * k, cy - 14f * k, 8f * k, 28f * k);
                sd.filledRectangle(cx + 5f * k, cy - 14f * k, 8f * k, 28f * k);
                break;
            case RETRY: {
                float r = 17f * k;
                float start = 0.9f, sweep = 4.9f;
                sd.arc(cx, cy, r, start, sweep, w, false, 28);
                cap(sd, cx + MathUtils.cos(start) * r, cy + MathUtils.sin(start) * r, w);
                float ea = start + sweep;
                float ex = cx + MathUtils.cos(ea) * r, ey = cy + MathUtils.sin(ea) * r;
                // arrow head pointing along the tangent (counter-clockwise)
                float tx = -MathUtils.sin(ea), ty = MathUtils.cos(ea);
                float nx = MathUtils.cos(ea), ny = MathUtils.sin(ea);
                float hs = 10f * k;
                sd.filledTriangle(ex + tx * hs, ey + ty * hs, ex + nx * hs * 0.9f, ey + ny * hs * 0.9f,
                    ex - nx * hs * 0.9f, ey - ny * hs * 0.9f);
                break;
            }
            case PENCIL: {
                float c = MathUtils.cosDeg(45f), s = MathUtils.sinDeg(45f);
                // body from (-14,-14) to (14,14) along the diagonal, width 10
                float bx0 = cx - 10f * k, by0 = cy - 10f * k, bx1 = cx + 17f * k, by1 = cy + 17f * k;
                sd.line(bx0, by0, bx1, by1, 11f * k);
                float tipX = cx - 22f * k, tipY = cy - 22f * k;
                float px = -s * 5.5f * k, py = c * 5.5f * k;
                sd.filledTriangle(bx0 + px, by0 + py, bx0 - px, by0 - py, tipX, tipY);
                break;
            }
            case DICE: {
                float h = 21f * k;
                rectStroke(sd, cx - h, cy - h, 2f * h, 2f * h, w);
                float p = 3.8f * k;
                sd.filledCircle(cx - 9f * k, cy + 9f * k, p);
                sd.filledCircle(cx + 9f * k, cy - 9f * k, p);
                sd.filledCircle(cx, cy, p);
                sd.filledCircle(cx + 9f * k, cy + 9f * k, p);
                sd.filledCircle(cx - 9f * k, cy - 9f * k, p);
                break;
            }
            case CLOSE:
                line(sd, cx - 16f * k, cy - 16f * k, cx + 16f * k, cy + 16f * k, w);
                line(sd, cx - 16f * k, cy + 16f * k, cx + 16f * k, cy - 16f * k, w);
                break;
            case LIST:
                for (int i = -1; i <= 1; i++) {
                    float y = cy + i * 13f * k;
                    sd.filledCircle(cx - 20f * k, y, 3.5f * k);
                    line(sd, cx - 10f * k, y, cx + 22f * k, y, w);
                }
                break;
            case SPEAKER: {
                sd.filledRectangle(cx - 22f * k, cy - 7f * k, 10f * k, 14f * k);
                sd.filledTriangle(cx - 14f * k, cy - 7f * k, cx - 14f * k, cy + 7f * k, cx - 2f * k, cy + 17f * k);
                sd.filledTriangle(cx - 14f * k, cy - 7f * k, cx - 2f * k, cy + 17f * k, cx - 2f * k, cy - 17f * k);
                sd.arc(cx - 2f * k, cy, 11f * k, -0.75f, 1.5f, w * 0.9f, false, 10);
                sd.arc(cx - 2f * k, cy, 21f * k, -0.8f, 1.6f, w * 0.9f, false, 14);
                break;
            }
            case PERSON:
                ringStroke(sd, cx, cy + 11f * k, 9f * k, w);
                sd.arc(cx, cy - 22f * k, 19f * k, 0.15f, MathUtils.PI - 0.3f, w, false, 18);
                break;
            case TRIANGLE_DOWN:
                sd.filledTriangle(cx - 16f * k, cy + 10f * k, cx + 16f * k, cy + 10f * k, cx, cy - 14f * k);
                break;
            case LOCK:
                sd.filledRectangle(cx - 16f * k, cy - 20f * k, 32f * k, 24f * k);
                sd.arc(cx, cy + 4f * k, 11f * k, 0f, MathUtils.PI, w, false, 14);
                line(sd, cx - 11f * k, cy + 4f * k, cx - 11f * k, cy + 2f * k, w);
                line(sd, cx + 11f * k, cy + 4f * k, cx + 11f * k, cy + 2f * k, w);
                break;
            case CHECK:
                path(sd, cx, cy, k, w, true, CHECK_PTS);
                break;
            case PLUS:
                line(sd, cx - 17f * k, cy, cx + 17f * k, cy, w);
                line(sd, cx, cy - 17f * k, cx, cy + 17f * k, w);
                break;
            case MINUS:
                line(sd, cx - 17f * k, cy, cx + 17f * k, cy, w);
                break;
            case BACK:
                path(sd, cx, cy, k, w, true, BACK_PTS);
                line(sd, cx - 20f * k, cy, cx + 20f * k, cy, w);
                break;
            case SORT:
                // three left-aligned bars, shorter going down (a sorted list)
                line(sd, cx - 22f * k, cy + 14f * k, cx + 22f * k, cy + 14f * k, w);
                line(sd, cx - 22f * k, cy, cx + 8f * k, cy, w);
                line(sd, cx - 22f * k, cy - 14f * k, cx - 6f * k, cy - 14f * k, w);
                break;
            default:
                break;
        }
        sd.setColor(old);
    }

    private static void line(ShapeDrawer sd, float x1, float y1, float x2, float y2, float w) {
        sd.line(x1, y1, x2, y2, w);
        cap(sd, x1, y1, w);
        cap(sd, x2, y2, w);
    }

    private static void cap(ShapeDrawer sd, float x, float y, float w) {
        sd.filledCircle(x, y, w * 0.5f);
    }

    private static void ringStroke(ShapeDrawer sd, float cx, float cy, float r, float w) {
        sd.arc(cx, cy, r, 0f, MathUtils.PI2, w, false, Math.max(16, (int) (r * 1.2f)));
    }

    private static void rectStroke(ShapeDrawer sd, float x, float y, float w, float h, float lw) {
        line(sd, x, y, x + w, y, lw);
        line(sd, x + w, y, x + w, y + h, lw);
        line(sd, x + w, y + h, x, y + h, lw);
        line(sd, x, y + h, x, y, lw);
    }

    /** Polyline in grid units (pairs), scaled by k about (cx, cy), with round caps when open. */
    private static void path(ShapeDrawer sd, float cx, float cy, float k, float w, boolean open, float[] pts) {
        FloatArray p = PATH;
        p.clear();
        for (int i = 0; i + 1 < pts.length; i += 2) {
            p.add(cx + pts[i] * k);
            p.add(cy + pts[i + 1] * k);
        }
        int n = p.size;
        sd.path(p, w, JoinType.SMOOTH, open);
        if (open) {
            cap(sd, p.get(0), p.get(1), w);
            cap(sd, p.get(n - 2), p.get(n - 1), w);
        }
    }
}
