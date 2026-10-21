package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Params;
import org.kelvinizer.play.ScoreKeeper;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.SonarField;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 盖在 HUD 之上的几个时刻：开场的 3-2-1 倒计时（每个数字 1 秒，之后 0.3 秒什么都不显示，然后歌曲时钟才开始走）、
 * 暂停后恢复时的 3-2-1 倒计时，以及歌曲结束时的横幅。横幅为 ALL RESONANCE / FULL COMBO / CLEAR（自动游玩显示 AUTOPLAY COMPLETE），前两种还放出全屏声呐波和一圈光点。
 * 横幅等音乐和谱面都结束 1 秒后才出现，大字、带故障效果，停 2 秒后进结算（2026-10-17）。
 *
 * <p>HUD-over run moments (DESIGN §5.11 start sequence, §5.12 resume countdown, §5.13 end banner):
 * the start countdown {@code 3 / 2 / 1}, one second each from run time 0 (the DIVE open), keyed
 * to the pre-roll's own clock while the song clock waits (NUMBER 132, scale 1.2 → 1 over the
 * first third of its second, G2 on appear, fading over 11/12 of it, a {@code ring_thin}
 * contracting from r 600 over the whole second), then {@link #BUFFER_SEC} of nothing before the
 * song clock starts (Kelvin 2026-10-12 16:06); the same visuals at 0.6 s steps for a resume; and
 * the end banner (Kelvin 2026-10-17: not right after the last note but {@code Params.END_BANNER_DELAY_MS} after the
 * run's end, the later of the last note end and the music end; {@code ALL RESONANCE} / {@code FULL COMBO} /
 * {@code CLEAR} / {@code AUTOPLAY COMPLETE}, RUBIK {@value #BANNER_CAP} with a scale punch, a G3 tear in, G1
 * shimmer and short G2 bursts while it stays, G2 out; the result follows when it has gone). FULL COMBO and ALL
 * RESONANCE also fire the full-screen ping and a bio burst ({@link #drawBurst}). The intro card
 * is gone (Kelvin 16:06: nothing but the countdown); the song and difficulty stay in the corner HUD.
 */
final class RunOverlay {
    /** Length of each start countdown step (s, DESIGN §5.11). */
    static final float COUNT_SEC = 1f;
    /** Length of each resume countdown step (s, DESIGN §5.12). */
    static final float RESUME_STEP_SEC = 0.6f;
    /** Nothing but the stage between the start countdown's {@code 1} and the song clock's start (s). */
    static final float BUFFER_SEC = 0.3f;
    /** The whole pre-roll: 3 × {@link #COUNT_SEC} + {@link #BUFFER_SEC} (s of wall time before the song clock runs). */
    static final float PREROLL_SEC = 3f * COUNT_SEC + BUFFER_SEC;
    /** The end banner's wait after the run's end (ms, Kelvin 2026-10-17: 1 s after the music / chart end). */
    private static final float BANNER_DELAY_MS = Params.END_BANNER_DELAY_MS;
    private static final float BANNER_SEC = Params.END_BANNER_MS / 1000f;
    /** End banner cap (Kelvin 2026-10-17: bigger than the old 56); narrowed to fit the world width. */
    static final float BANNER_CAP = 104f;
    /** The banner's entry punch: scale 1.25 → 1 over this many seconds. */
    private static final float BANNER_PUNCH_SEC = 0.35f;
    /** Mean gap between the banner's short G2 bursts while it stays (s). */
    private static final float BANNER_IDLE_SEC = 0.6f;
    /** Bio-burst motes (DESIGN §5.13). */
    private static final int BURST_N = 36;
    private static final float BURST_SEC = 1.4f;

    private final Fonts fonts;
    private final Draw draw;
    private final GlitchText glitch;
    private final ScaledGlitch digits;
    private final GlitchState bannerState;
    private final StringBuilder num = new StringBuilder(2);

    private boolean autoplay;
    private boolean bannerEnabled = true;

    private float bannerShownAt = -1f;
    private boolean bannerOutFired;
    private String bannerText = "";
    private int bannerKind;
    /** Scale that fits the banner text into the world width (1 when it fits at {@link #BANNER_CAP}). */
    private float bannerFit = 1f;

    /** Creates the overlay over the shared services. */
    RunOverlay(Fonts fonts, Draw draw, GlitchText glitch) {
        this.fonts = fonts;
        this.draw = draw;
        this.glitch = glitch;
        this.digits = new ScaledGlitch(fonts, draw);
        bannerState = glitch.obtainState();
    }

    /** Resets for a new run (load time). */
    void begin(boolean autoplay, boolean banner) {
        this.autoplay = autoplay;
        this.bannerEnabled = banner;
        bannerShownAt = -1f;
        bannerOutFired = false;
        bannerState.reset();
        prewarm();
    }

    /**
     * Generates the countdown (with its G2 face swap) and banner font pages at load time, so none
     * is built during the start sequence or at the end of the song.
     */
    private void prewarm() {
        Fonts.GlyphSet full = Fonts.GlyphSet.FULL;
        Fonts.GlyphSet dig = Fonts.GlyphSet.DIGITS;
        fonts.prewarm(Fonts.Role.NUMBER, 132f, dig);
        fonts.prewarm(Fonts.Role.RUBIK, BANNER_CAP, Fonts.GlyphSet.WORDS);
    }

    // ------------------------------------------------------------------ countdowns

    /**
     * Start countdown keyed to the pre-roll (DESIGN §5.11): {@code 3 / 2 / 1} for one second each
     * from its start, so the {@code 1} and its ring are gone at 3.0 s; nothing is drawn in the
     * buffer after it.
     *
     * @param prerollSec seconds into the pre-roll (negative or ≥ {@link #PREROLL_SEC}: none)
     * @param now        run seconds (glitch ticks)
     */
    void drawStartCountdown(Batch b, float prerollSec, float now) {
        if (prerollSec < 0f || prerollSec >= 3f * COUNT_SEC) return;
        int k = Math.min(2, (int) (prerollSec / COUNT_SEC));
        drawCount(b, 3 - k, prerollSec - k * COUNT_SEC, now, COUNT_SEC);
    }

    /**
     * Resume countdown (3 × 0.6 s) with the start visuals.
     *
     * @param remaining seconds left (3 × 0.6 … 0)
     */
    void drawResumeCountdown(Batch b, float remaining, float now) {
        if (remaining < 0f) return;
        float step = RESUME_STEP_SEC;
        int n = Math.max(1, Math.min(3, (int) Math.ceil(remaining / step)));
        float el = n * step - remaining;
        drawCount(b, n, el, now, step);
    }

    /**
     * One digit, {@code el} seconds into its step of {@code step} seconds. The scale-in, fade and
     * ring follow the 0.6 s resume timeline stretched to the step ({@code k}); the G2 burst keeps
     * its 0.25 s.
     */
    private void drawCount(Batch b, int n, float el, float now, float step) {
        float k = el * (RESUME_STEP_SEC / step);
        float scale = 1.2f - 0.2f * Anim.POW3_OUT.apply(Math.min(1f, k / 0.20f));
        float a = 1f - Math.min(1f, k / 0.55f);
        int mode = el < 0.25f ? ScaledGlitch.G2 : ScaledGlitch.NONE;
        num.setLength(0);
        num.append((char) ('0' + n));
        // Ring and digit order saves a flush (DESIGN §14): while the G2 burst draws its atlas tear
        // bars after the glyphs, the ring joins them after the digit; otherwise it goes first. The
        // ring is still wide (r > 400) during the burst, so the order never shows.
        float rp = Math.min(1f, k / 0.6f);
        float r = 600f * (1f - Anim.POW2_IN.apply(rp));
        boolean tears = mode != ScaledGlitch.NONE && a > 0.003f;
        if (!tears && r > 4f) draw.ringSprite(960f, 540f, r, Theme.INK1, 0.35f * (1f - rp));
        if (a > 0.003f) {
            digits.draw(b, Fonts.Role.NUMBER, 132f, num, 960f, 540f - 66f, scale, Theme.INK1, a, mode, el / 0.25f, el,
                n * 7, now);
        }
        if (tears && r > 4f) draw.ringSprite(960f, 540f, r, Theme.INK1, 0.35f * (1f - rp));
    }

    // ------------------------------------------------------------------ end banner

    /**
     * End banner {@link #BANNER_DELAY_MS} after the run's end, held {@link #BANNER_SEC} s. Fires the full-screen
     * ping for FULL COMBO / ALL RESONANCE through {@code sonar} when it appears.
     *
     * @param songMs judge time (song ms)
     * @param endMs  the run's end: the later of the last note end and the music end ({@code GameEngine.endMs})
     */
    void drawEndBanner(Batch b, double songMs, double endMs, ScoreKeeper s, float now, SonarField sonar) {
        if (!bannerEnabled) return;
        double el = songMs - (endMs + BANNER_DELAY_MS);
        if (el < 0.0) return;
        float t = (float) (el / 1000.0);
        if (t > BANNER_SEC) return;
        if (bannerShownAt < 0f) {
            bannerShownAt = now;
            if (autoplay) {
                bannerKind = 3;
                bannerText = "AUTOPLAY COMPLETE";
            } else if (s.isAllResonance()) {
                bannerKind = 2;
                bannerText = "ALL RESONANCE";
            } else if (s.isFullCombo()) {
                bannerKind = 1;
                bannerText = "FULL COMBO";
            } else {
                bannerKind = 0;
                bannerText = "CLEAR";
            }
            float w = fonts.width(Fonts.Role.RUBIK, BANNER_CAP, Fonts.GlyphSet.WORDS, bannerText);
            float room = 0.86f * Ui.worldW();
            bannerFit = w > room ? room / w : 1f;
            // Kelvin 2026-10-17 「带上点glitch特效」: a screen tear on entry, then shimmer and short bursts
            bannerState.ambient(GlitchText.Level.G1).idleBursts(BANNER_IDLE_SEC);
            bannerState.trigger(GlitchText.Level.G3, now, 0.40f);
            if ((bannerKind == 1 || bannerKind == 2) && sonar != null) {
                sonar.ping(960f, 540f, 1400f, 1.4f, bannerKind == 2 ? Theme.ALL_RESONANCE : Theme.FULL_COMBO,
                    GlitchText.flashAlpha(0.15f), 2);
            }
        }
        if (t > BANNER_SEC - 0.3f && !bannerOutFired) {
            bannerOutFired = true;
            bannerState.idleBursts(0f);
            bannerState.trigger(GlitchText.Level.G2, now, 0.30f);
        }
        float a = bannerAlpha(t);
        Color c;
        switch (bannerKind) {
            case 2:
                c = Theme.ALL_RESONANCE;
                break;
            case 1:
                c = Theme.FULL_COMBO;
                break;
            case 3:
                c = Theme.AUTO;
                break;
            default:
                c = Theme.INK1;
                break;
        }
        glitch.drawScreenTears(b, bannerState, now, Ui.left(), Ui.bottom(), Ui.worldW(), Ui.worldH());
        float punch = 1.25f - 0.25f * Anim.POW3_OUT.apply(Math.min(1f, t / BANNER_PUNCH_SEC));
        glitch.draw(b, Fonts.Role.RUBIK, BANNER_CAP, Fonts.GlyphSet.WORDS, bannerText, 960f, 540f,
            Align.center | Fonts.VCENTER, c, a, bannerState, now, punch * bannerFit, 0f);
    }

    private static float bannerAlpha(float t) {
        float a = t < 0.15f ? t / 0.15f : t > BANNER_SEC - 0.3f ? (BANNER_SEC - t) / 0.3f : 1f;
        return MathUtils.clamp(a, 0f, 1f);
    }

    /**
     * How much the end banner owns the centre, 0..1: it rises with the banner's fade-in and stays at
     * 1 afterwards, so the HUD combo (drawn on the same spot) gives way instead of showing through.
     */
    float bannerCover(double songMs, double endMs) {
        if (!bannerEnabled) return 0f;
        double el = songMs - (endMs + BANNER_DELAY_MS);
        if (el <= 0.0) return 0f;
        return Math.min(1f, (float) (el / 1000.0) / 0.15f);
    }

    /**
     * The banner's scrim strip (scrim α .55 × the banner fade, 220 tall across the world). Drawn at
     * the end of the normal note block rather than with the banner text, so the HUD-over pass of a
     * banner frame needs no extra atlas flush (DESIGN §14); nothing but fading hit effects is on
     * the field by then.
     */
    void drawBannerScrim(double songMs, double endMs) {
        if (!bannerEnabled) return;
        double el = songMs - (endMs + BANNER_DELAY_MS);
        if (el < 0.0) return;
        float t = (float) (el / 1000.0);
        if (t > BANNER_SEC) return;
        draw.rect(Ui.left(), 540f - 110f, Ui.worldW(), 220f, Theme.SCRIM, 0.55f * bannerAlpha(t));
    }

    /**
     * The bio burst of a FULL COMBO / ALL RESONANCE end (DESIGN §5.13): {@value #BURST_N} soft motes
     * thrown out from the banner (wider than tall, like the banner), drifting up and fading over
     * {@value #BURST_SEC} s, in {@code #7FE7FF} / {@code groundSig} or the iridescent trio. Call in
     * the additive block: it uses the shared atlas's {@code soft_dot}, so it adds no draw call. No-op
     * before the banner has appeared, for CLEAR and AUTOPLAY, and once it has faded. Allocation-free.
     *
     * @param songMs judge time (song ms); the burst is timed like the banner, so it freezes with a pause
     */
    void drawBurst(Batch b, double songMs, double endMs) {
        if (bannerShownAt < 0f || (bannerKind != 1 && bannerKind != 2)) return;
        float t = (float) ((songMs - (endMs + BANNER_DELAY_MS)) / 1000.0);
        if (t < 0f || t > BURST_SEC) return;
        float k = t / BURST_SEC;
        float out = 1f - (1f - k) * (1f - k) * (1f - k);
        float fade = Math.min(1f, t / 0.08f) * (1f - k) * (1f - k);
        TextureRegion dot = draw.softDot();
        for (int i = 0; i < BURST_N; i++) {
            float h1 = Anim.hash01(i, 1);
            float h2 = Anim.hash01(i, 2);
            float h3 = Anim.hash01(i, 3);
            float h4 = Anim.hash01(i, 4);
            float ang = MathUtils.PI2 * (i + 0.7f * h1) / BURST_N;
            float d = 40f + (200f + 420f * h2) * out;
            float x = 960f + MathUtils.cos(ang) * d * 1.6f;
            float y = 540f + MathUtils.sin(ang) * d + 140f * k * (0.4f + h3);
            Color c;
            if (bannerKind == 2) {
                c = (i & 1) == 0 ? Theme.ALL_RESONANCE : Theme.SKY_SIG;
            } else {
                c = (i & 1) == 0 ? Theme.FULL_COMBO : Theme.GROUND_SIG;
            }
            float a = fade * (0.45f + 0.45f * h4);
            float s = 22f + 34f * h3;
            b.setColor(c.r, c.g, c.b, a * 0.5f);
            b.draw(dot, x - s * 0.5f, y - s * 0.5f, s, s);
            float core = s * 0.3f;
            b.setColor(c.r, c.g, c.b, a);
            b.draw(dot, x - core * 0.5f, y - core * 0.5f, core, core);
        }
        b.setColor(Color.WHITE);
    }
}
