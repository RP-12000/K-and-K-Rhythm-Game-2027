package org.kelvinizer.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import org.kelvinizer.library.SongLibrary;

/**
 * 深度徽章：头像坐在深度六边形上，六边形按深度分 16 档。六边形、翅膀、尖刺和头像上的珍珠是预先画好的图片
 * （tools/depth-tiers/make_tiers.py），数字由游戏自己写。地图左上角和结算页左上角共用。
 *
 * <p>The depth crest (Kelvin 2026-10-30 16:37–20:58): the avatar sits on a hexagon whose look follows the player's
 * depth tier ({@link org.kelvinizer.profile.Depth#crestTier}). Each tier is one PNG made by
 * {@code tools/depth-tiers/make_tiers.py}, its colours premultiplied by alpha. The rows under its split are the back
 * layer (the hexagon with its wings, gems, pips and the spikes beside the avatar), drawn before the avatar's disc; the
 * rows above it are the top layer (the pearls, or tier 6's diamond, on the avatar ring), drawn after the avatar.
 * Measures are world units at scale 1; callers pass the hexagon's bottom left corner and a scale. A tier's texture
 * loads the first time it is drawn and stays until {@link #dispose()}.
 */
public final class DepthCrest implements Disposable {
    /** Number of tiers. */
    public static final int TIERS = 16;
    /** Hexagon width and height, the avatar's radius and how far the avatar overlaps the hexagon's top (× scale). */
    public static final float HEX_W = 220f, HEX_H = 44f, AV_R = 54f, LIFT = 8f;
    /** The depth number's cap height and its shadow's offset down and right (× scale). */
    public static final float NUM_CAP = 24f, SHADOW = 1.6f;

    private static final String DIR = "ui/depth/";
    /** Per tier: how far the solid art reaches past the hexagon's left and right tips (world units at scale 1). */
    private static float[] reachL, reachR;
    /** Texels per world unit, canvas size, hexagon top left and the split row (texels, rows counted from the top). */
    private static float texels;
    private static int canvasW, canvasH, hexLeft, hexTop, split;

    private final Texture[] tex = new Texture[TIERS];
    private final TextureRegion region = new TextureRegion();

    private static void meta() {
        if (reachL != null) return;
        JsonValue m = new JsonReader().parse(Gdx.files.internal(DIR + "tiers.json"));
        texels = m.getFloat("scale");
        canvasW = m.get("canvas").getInt(0);
        canvasH = m.get("canvas").getInt(1);
        hexLeft = m.get("hexagon").getInt(0);
        hexTop = m.get("hexagon").getInt(1);
        split = m.getInt("split");
        float[] l = new float[TIERS], r = new float[TIERS];
        JsonValue t = m.get("tiers");
        for (int i = 0; i < TIERS; i++) {
            l[i] = t.get(i).get("reach").getFloat(0);
            r[i] = t.get(i).get("reach").getFloat(1);
        }
        reachR = r;
        reachL = l;
    }

    /** @return how far tier {@code tier} (1–16)'s fins or wings reach left of the hexagon (world units at scale 1). */
    public static float reachLeft(int tier) {
        meta();
        return reachL[clamp(tier) - 1];
    }

    /** @return how far tier {@code tier} (1–16)'s fins or wings reach right of the hexagon (world units at scale 1). */
    public static float reachRight(int tier) {
        meta();
        return reachR[clamp(tier) - 1];
    }

    private static int clamp(int tier) {
        return Math.max(1, Math.min(TIERS, tier));
    }

    /** The back layer: the hexagon of {@code tier}, its bottom left at ({@code x}, {@code y}), at scale {@code s}. */
    public void drawBack(Batch b, int tier, float x, float y, float s, float alpha) {
        draw(b, tier, x, y, s, alpha, false);
    }

    /** The top layer: the pearls or the diamond on the avatar ring, placed as {@link #drawBack}. */
    public void drawTop(Batch b, int tier, float x, float y, float s, float alpha) {
        if (clamp(tier) >= 6) draw(b, tier, x, y, s, alpha, true);
    }

    private void draw(Batch b, int tier, float x, float y, float s, float alpha, boolean front) {
        if (alpha <= 0.004f) return;
        Texture t = texture(clamp(tier));
        float u = s / texels;
        float left = x - hexLeft * u, bottom = y - (canvasH - hexTop - HEX_H * texels) * u;
        region.setTexture(t);
        if (front) {
            region.setRegion(0, 0, canvasW, split);
            bottom += (canvasH - split) * u;
        } else {
            region.setRegion(0, split, canvasW, canvasH - split);
        }
        int srcC = b.getBlendSrcFunc(), dstC = b.getBlendDstFunc();
        int srcA = b.getBlendSrcFuncAlpha(), dstA = b.getBlendDstFuncAlpha();
        float old = b.getPackedColor();
        b.setBlendFunctionSeparate(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        b.setColor(alpha, alpha, alpha, alpha); // premultiplied: fade every channel
        b.draw(region, left, bottom, region.getRegionWidth() * u, region.getRegionHeight() * u);
        b.setBlendFunctionSeparate(srcC, dstC, srcA, dstA);
        b.setPackedColor(old);
    }

    /** @return the texture of tier {@code tier} (1–16), loaded on first use (a text queue groups draws by it). */
    public Texture page(int tier) {
        return texture(clamp(tier));
    }

    private Texture texture(int tier) {
        meta();
        Texture t = tex[tier - 1];
        if (t == null) {
            boolean mip = SongLibrary.canMipmap(canvasW, canvasH);
            t = new Texture(Gdx.files.internal(DIR + String.format("tier%02d.png", tier)), mip);
            t.setFilter(mip ? Texture.TextureFilter.MipMapLinearLinear : Texture.TextureFilter.Linear,
                Texture.TextureFilter.Linear);
            tex[tier - 1] = t;
        }
        return t;
    }

    @Override
    public void dispose() {
        for (int i = 0; i < TIERS; i++) {
            if (tex[i] != null) tex[i].dispose();
            tex[i] = null;
        }
    }
}
