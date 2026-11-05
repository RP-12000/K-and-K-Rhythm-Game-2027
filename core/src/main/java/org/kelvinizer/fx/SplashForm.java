package org.kelvinizer.fx;

import com.badlogic.gdx.graphics.Color;
import org.kelvinizer.ui.Theme;

/**
 * 水花的两种样子：共鸣是水滴打击（单点另有三个淡蓝圈），回响是波纹（单点三个紫圈，长条不溅水滴）；颜色、亮度、大小、水珠和按住时的波纹
 * 都只写在这里，以后调两者的差别只改这个文件。
 *
 * <p>The two forms of the DROP / STREAM splash ({@link Splash}): every number that differs between a RESONANCE and
 * an ECHO head, index {@link #RESONANCE} or {@link #ECHO} in each table, so a later change of the gap touches this
 * file only. Kelvin 2026-10-26 13:36 「区别太小了，共鸣按住的过程中的圈还可以再多一点」, approved 13:41 (design thread
 * v84) with the splash drops at 0.85× speed (13:42 「水滴结开始和结束的时候可以飞的稍微近一点」), then 14:12
 * 「drop的resonance就是一个水滴打击，echo就是三个紫色圆圈，象征echo。对于长条，resonance就是波纹+水滴打击，echo就只是波纹，
 * 不要水滴打击。长条的波纹设计不变」, and 15:39 「resonance的打击也加上3个圆环，和echo一样，但是淡蓝色」.
 *
 * <p>Lengths and speeds are world units, the approved mock-up's px: at 1920×1080 the world is 1920×1080 (the
 * playfield's 1200-high frame is stretched onto it, the effects are not).
 */
final class SplashForm {
    /** Form indices: the head (or DROP) was judged RESONANCE or ECHO. */
    static final int RESONANCE = 0, ECHO = 1;
    /** Colour of the dots, drops and rings (the same on both lines). */
    static final Color[] COLOUR = {Theme.RESONANCE, Theme.ECHO};
    /** Brightness: every α of the form is multiplied by it. */
    static final float[] KA = {1f, 0.6f};
    /** Size of the flash, the splash rings and the held light. */
    static final float[] SZ = {1f, 0.7f};
    /** Share of the white parts: the flash's white dot and the held light's white dot. */
    static final float[] WHITE = {1f, 0.5f};
    /** White mixed into a drop's core colour. */
    static final float[] MIX = {0.55f, 0.3f};
    /** True when this form throws drops at all (Kelvin 2026-10-26 14:12: an ECHO is ripples only). */
    static final boolean[] DROPLETS = {true, false};

    /**
     * The ripples of a DROP hit: three circles for both forms, purple for an ECHO (Kelvin 2026-10-26 14:12
     * 「echo就是三个紫色圆圈，象征echo」) and light blue round a RESONANCE's water splash (15:39 「resonance的打击也加上3个
     * 圆环，和echo一样，但是淡蓝色」). The RESONANCE radii are the ECHO's times its {@link #SZ}, so both draw the same
     * circles. Delays and final radii pair up.
     */
    static final float[][] DROP_RING_DELAY = {{0f, 0.09f, 0.18f}, {0f, 0.09f, 0.18f}};
    static final float[][] DROP_RING_RADIUS = {{147f, 105f, 73.5f}, {210f, 150f, 105f}};

    /** The ripples of a STREAM head or release (the approved design, 13:41: RESONANCE two, ECHO one). */
    static final float[][] HIT_RING_DELAY = {{0f, 0.09f}, {0f}};
    static final float[][] HIT_RING_RADIUS = {{210f, 150f}, {210f}};

    /** Drops thrown at a DROP hit or a STREAM head. */
    static final int[] HIT_DROPS = {16, 6};
    /** Drops thrown at a completed release. */
    static final int[] RELEASE_DROPS = {20, 8};
    /** Launch speed range of a hit's drops (world / s). */
    static final float[][] HIT_SPEED = {{552.5f, 1147.5f}, {408f, 765f}};
    /** Launch speed range of a release's drops (world / s). */
    static final float[][] RELEASE_SPEED = {{595f, 1232.5f}, {442f, 833f}};
    /** Radius range of a splash drop. */
    static final float[][] DROP_RADIUS = {{8f, 13.5f}, {5.5f, 9.5f}};

    /** Drops per second of the fountain while a STREAM is held. */
    static final float[] FOUNTAIN_RATE = {46f, 16f};
    /** Launch speed range of a fountain drop (world / s). */
    static final float[][] FOUNTAIN_SPEED = {{480f, 960f}, {320f, 620f}};
    /** Radius range of a fountain drop. */
    static final float[][] FOUNTAIN_RADIUS = {{6.5f, 10.5f}, {4.5f, 7.5f}};

    /**
     * Beats between the rings of a held STREAM at the song's current tempo, the first one this long after the head
     * (Kelvin 2026-10-31 15:03: one every 1/4 beat; ECHO keeps a third as many, as in the approved 10-26 look).
     */
    static final float[] HELD_RING_BEATS = {0.25f, 0.75f};
    /** Seconds between the rings of a held STREAM when no tempo is set ({@link HitFx#setBeatLength}). */
    static final float[] HELD_RING_PERIOD = {0.11f, 0.33f};
    /** Shortest time between two rings of one held STREAM (s), whatever the tempo. */
    static final float HELD_RING_MIN_PERIOD = 0.05f;
    /** Final radius of a held STREAM's ring (not scaled by {@link #SZ}). */
    static final float[] HELD_RING_RADIUS = {150f, 100f};
    /** Life of a held STREAM's ring (s). */
    static final float[] HELD_RING_LIFE = {0.5f, 0.45f};
    /** Stroke of a held STREAM's ring. */
    static final float[] HELD_RING_STROKE = {4.5f, 3.5f};

    private SplashForm() {
    }
}
