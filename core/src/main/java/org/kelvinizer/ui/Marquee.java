package org.kelvinizer.ui;

/**
 * 放不下的歌名的滚动方式（选歌卡片超过 30 个字符、结算超过 50 个字符）：先在开头停 1 秒，再以每秒 80 向左匀速滚动，
 * 歌名末尾到达右边时停 2 秒，然后继续滚，末尾滚出去后空 160 接上歌名开头，开头经过左边时不停，一直滚到末尾再停 2 秒，如此循环。
 *
 * <p>The scroll of a title too long for its window: the song card past 30 characters, the result screen past 50
 * (Kelvin 2026-10-19 07:01 「滚动到最后以后停顿2秒，然后空一段距离再接着播放，循环播放」; the other timings are from
 * design round 5). The title rests 1 s at the start, scrolls left at 80 units/s until its end reaches the
 * window's right edge, rests there 2 s, then keeps scrolling: its end leaves, a 160-unit gap follows and the next
 * copy comes in, passing the left edge without a stop, until its end reaches the right edge again. A title that fits
 * but must scroll by its length rests with its start at the left edge instead. Windows fade over up to {@link #FADE}
 * at each edge, less as a copy's start nears the left edge or its end the right one, so the first letter is crisp at
 * rest and the last one while the title rests at its end. Stateless.
 */
public final class Marquee {
    /** Rest at the start and at each end (s), speed (units/s), gap between copies and edge fade (units at scale 1). */
    public static final float HOLD_START = 1f, HOLD_END = 2f, SPEED = 80f, GAP = 160f, FADE = 24f;

    private Marquee() { }

    /**
     * Distance scrolled {@code t} seconds after the title appeared (or the song changed), at scale {@code s}.
     * {@code overflow} is the title's width less the window's (0 or less when it fits) and {@code span} the title's
     * width plus {@link #GAP}·s: draw the title at {@code left − distance % span} and again every span to the right.
     */
    public static float distance(float t, float overflow, float span, float s) {
        float v = SPEED * s;
        float u = t - HOLD_START;
        if (u <= 0f || v <= 0f || span <= 0f) return 0f;
        float over = Math.max(0f, overflow);
        float first = over / v;
        if (u <= first) return u * v;
        u -= first;
        float cycle = HOLD_END + span / v;
        int n = (int) (u / cycle);
        float r = u - n * cycle;
        return over + n * span + Math.max(0f, r - HOLD_END) * v;
    }

    /**
     * Width of the left fade at {@code distance}: the distance a copy's start has gone past the left edge, at most
     * {@link #FADE}·s, closing again as the next copy's start comes up to the edge.
     */
    public static float fadeLeft(float distance, float span, float s) {
        if (span <= 0f) return 0f;
        float m = distance % span;
        return Math.min(FADE * s, Math.min(m, span - m));
    }

    /**
     * Width of the right fade at {@code distance}: how far the nearest copy's end is from the right edge, at most
     * {@link #FADE}·s, so the end is crisp while it rests there ({@code overflow} as for {@link #distance}).
     */
    public static float fadeRight(float distance, float overflow, float span, float s) {
        if (span <= 0f) return 0f;
        float e = distance % span - Math.max(0f, overflow);
        return Math.min(FADE * s, Math.min(Math.abs(e), Math.min(Math.abs(e - span), Math.abs(e + span))));
    }
}
