package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;

/**
 * 深度配色表：六个真实海洋深度层（浅滩、暮光层、午夜层、黑水层、海沟、未知）的颜色和参数，按深度（米）插值。
 * 每层前 75 % 保持不变，最后 25 %（温跃层）平滑过渡到下一层；查询不分配内存。
 *
 * <p>The depth-layer table of DESIGN §1.2 / §2.3: six real ocean zones whose palette and scalars
 * are interpolated by depth in metres.
 *
 * <p>Inside a band the layer's values hold for the first 75 %; over the last 25 % (the
 * thermocline) every value blends toward the next layer by a smoothstep. Colours blend in
 * linear RGB. All lookups are allocation-free.
 */
public final class DepthPalette {
    private DepthPalette() { }

    /** Number of layers (L1..L6). */
    public static final int LAYERS = 6;
    /** Band top edges in metres; L6 extends to infinity. */
    public static final float[] BAND_TOP = {0f, 200f, 1000f, 4000f, 6000f, 10935f};
    /** Band bottom edges in metres ({@code Float.POSITIVE_INFINITY} for L6). */
    public static final float[] BAND_BOTTOM = {200f, 1000f, 4000f, 6000f, 10935f, Float.POSITIVE_INFINITY};
    /** English layer names. */
    public static final String[] NAMES = {"SHORELINE", "TWILIGHT", "MIDNIGHT", "BLACKWATER", "TRENCH", "BELOW"};

    /** Channel index: gradient top. */
    public static final int CH_TOP = 0;
    /** Channel index: gradient middle. */
    public static final int CH_MID = 1;
    /** Channel index: gradient bottom. */
    public static final int CH_BOTTOM = 2;
    /** Channel index: accent. */
    public static final int CH_ACCENT = 3;
    /** Channel index: second accent. */
    public static final int CH_ACCENT2 = 4;
    /** Channel index: mote. */
    public static final int CH_MOTE = 5;
    /** Channel index: second mote. */
    public static final int CH_MOTE2 = 6;
    /** Channel index: shaft colour. */
    public static final int CH_SHAFT = 7;
    private static final int CHANNELS = 8;

    private static final int[][] HEX = {
        // top,     mid,      bottom,   accent,   accent2,  mote,     mote2,    shaft
        {0x1C5A99, 0x134079, 0x0C2A5A, 0x7FE7FF, 0x7FE7FF, 0xD6FBFF, 0xD6FBFF, 0xD8FBFF},
        {0x143A78, 0x0F275E, 0x0A1A45, 0x6FA8FF, 0x6FA8FF, 0xBCD6FF, 0xBCD6FF, 0x9AD8FF},
        {0x0D1C4C, 0x0A1338, 0x070C27, 0x8F7BFF, 0x8F7BFF, 0x5CF2FF, 0x5CF2FF, 0x9AD8FF},
        {0x0B1032, 0x080B24, 0x050717, 0xC77DFF, 0xC77DFF, 0xFF8AE2, 0xFF8AE2, 0x9AD8FF},
        {0x08091E, 0x050613, 0x03030A, 0xFF5CA8, 0xFF5CA8, 0xFF5CA8, 0xFF5CA8, 0x9AD8FF},
        {0x0C0412, 0x06020A, 0x000000, 0xFF2E88, 0x00E5FF, 0xFF2E88, 0x00E5FF, 0x9AD8FF},
    };
    //                                    L1     L2     L3     L4     L5     L6
    private static final float[] SHAFT = {1.00f, 0.40f, 0.00f, 0.00f, 0.00f, 0.00f};
    private static final float[] CAUSTIC = {1.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f};
    private static final float[] VIGNETTE = {0.30f, 0.38f, 0.48f, 0.56f, 0.64f, 0.70f};
    private static final float[] GRAIN = {0.04f, 0.05f, 0.06f, 0.07f, 0.08f, 0.10f};
    private static final float[] IDLE_GLITCH = {7.0f, 5.5f, 4.0f, 3.0f, 2.2f, 1.6f};
    private static final float[] PARTICULATES = {70f, 80f, 100f, 110f, 70f, 40f};
    private static final float[] BIO_MOTES = {0f, 4f, 12f, 18f, 24f, 16f};
    private static final float[] PIXEL_MIX = {0f, 0f, 0f, 0f, 0f, 1f};

    /** Linear-RGB channel values: [layer][channel][rgb]. */
    private static final float[][][] LIN = new float[LAYERS][CHANNELS][3];
    /** Static sRGB layer accents (never mutate). */
    private static final Color[] ACCENTS = new Color[LAYERS];

    static {
        for (int l = 0; l < LAYERS; l++) {
            for (int c = 0; c < CHANNELS; c++) {
                int h = HEX[l][c];
                LIN[l][c][0] = toLinear(((h >> 16) & 0xFF) / 255f);
                LIN[l][c][1] = toLinear(((h >> 8) & 0xFF) / 255f);
                LIN[l][c][2] = toLinear((h & 0xFF) / 255f);
            }
            ACCENTS[l] = Theme.rgb(HEX[l][CH_ACCENT], 1f);
        }
    }

    /** Layer index 0..5 that contains {@code meters} (negative depths map to L1). */
    public static int layerIndex(float meters) {
        for (int i = LAYERS - 1; i > 0; i--) {
            if (meters >= BAND_TOP[i]) return i;
        }
        return 0;
    }

    /** Fraction 0..1 of {@code meters} inside its band (0 for L6). */
    public static float bandFraction(float meters) {
        int i = layerIndex(meters);
        if (i >= LAYERS - 1) return 0f;
        return MathUtils.clamp((meters - BAND_TOP[i]) / (BAND_BOTTOM[i] - BAND_TOP[i]), 0f, 1f);
    }

    /** Thermocline blend toward the next layer (0 for the first 75 % of a band). */
    public static float thermocline(float meters) {
        int i = layerIndex(meters);
        if (i >= LAYERS - 1) return 0f;
        float f = bandFraction(meters);
        if (f < 0.75f) return 0f;
        float t = (f - 0.75f) / 0.25f;
        return t * t * (3f - 2f * t);
    }

    /** Writes one palette channel ({@code CH_*}) interpolated at {@code meters} into {@code out}. */
    public static Color colorAt(float meters, int channel, Color out) {
        int i = layerIndex(meters);
        float b = thermocline(meters);
        int j = Math.min(i + 1, LAYERS - 1);
        return lerpLinear(LIN[i][channel], LIN[j][channel], b, out);
    }

    /**
     * Fills {@code out} with every colour and scalar of the depth palette at {@code meters}
     * (DESIGN §2.3). Allocation-free.
     */
    public static void params(float meters, DepthParams out) {
        int i = layerIndex(meters);
        int j = Math.min(i + 1, LAYERS - 1);
        float b = thermocline(meters);
        float f = bandFraction(meters);
        out.meters = meters;
        out.layer = i;
        out.blend = b;
        lerpLinear(LIN[i][CH_TOP], LIN[j][CH_TOP], b, out.top);
        lerpLinear(LIN[i][CH_MID], LIN[j][CH_MID], b, out.mid);
        lerpLinear(LIN[i][CH_BOTTOM], LIN[j][CH_BOTTOM], b, out.bottom);
        lerpLinear(LIN[i][CH_ACCENT], LIN[j][CH_ACCENT], b, out.accent);
        lerpLinear(LIN[i][CH_ACCENT2], LIN[j][CH_ACCENT2], b, out.accent2);
        lerpLinear(LIN[i][CH_MOTE], LIN[j][CH_MOTE], b, out.mote);
        lerpLinear(LIN[i][CH_MOTE2], LIN[j][CH_MOTE2], b, out.mote2);
        lerpLinear(LIN[i][CH_SHAFT], LIN[j][CH_SHAFT], b, out.shaft);
        out.shaftStrength = lerp(SHAFT, i, j, b);
        out.caustic = lerp(CAUSTIC, i, j, b);
        out.vignette = lerp(VIGNETTE, i, j, b);
        out.grain = lerp(GRAIN, i, j, b);
        out.idleGlitchSec = lerp(IDLE_GLITCH, i, j, b);
        out.particulates = lerp(PARTICULATES, i, j, b);
        out.bioMotes = lerp(BIO_MOTES, i, j, b);
        out.pixelMix = lerp(PIXEL_MIX, i, j, b);
        // L1 = bubbles, L2 turns bubbles into snow across its band, L3+ = snow.
        float snowI = i == 0 ? 0f : (i == 1 ? f : 1f);
        float snowJ = j == 0 ? 0f : 1f;
        out.snowMix = snowI + (snowJ - snowI) * b;
    }

    private static float lerp(float[] table, int i, int j, float b) {
        return table[i] + (table[j] - table[i]) * b;
    }

    private static Color lerpLinear(float[] a, float[] c, float t, Color out) {
        out.r = toSrgb(a[0] + (c[0] - a[0]) * t);
        out.g = toSrgb(a[1] + (c[1] - a[1]) * t);
        out.b = toSrgb(a[2] + (c[2] - a[2]) * t);
        out.a = 1f;
        return out;
    }

    private static float toLinear(float c) {
        return (float) Math.pow(c, 2.2);
    }

    private static float toSrgb(float c) {
        return c <= 0f ? 0f : (float) Math.pow(c, 1.0 / 2.2);
    }
}
