package org.kelvinizer.profile;

import org.kelvinizer.play.Judgement;

/**
 * 跟随潜水员、存在各自存档里的设置：打击音、EARLY / LATE、视觉效果、音符大小、暂停方式、判定字位置、语言等。
 * 音量、延迟、画质、界面缩放这类本机设置在 {@link DeviceSettings}。
 *
 * <p>Diver-scoped settings stored inside each {@link Profile} (DESIGN §9.2, Appendix A.13).
 * Device-scoped settings (volumes, offsets, quality, UI scale…) live in
 * {@link DeviceSettings}.
 *
 * <p>Plain public fields (bound by {@link PlainJson}) for cheap reads from render code; integer
 * options use the constants below. Changes must be followed by {@code ProfileStore.markDirty()}.
 */
public class Settings {
    /** {@link #hitSoundMode}: play the hit sound on judgement (default). */
    public static final int HIT_ON_JUDGEMENT = 0;
    /** {@link #hitSoundMode}: play the hit sound on any tap inside a judgement zone. */
    public static final int HIT_ON_ANY_ZONE_TAP = 1;

    /** {@link #earlyLate}: both switches off, never show EARLY/LATE. */
    public static final int EARLY_LATE_OFF = 0;
    /** {@link #earlyLate}: ECHO switch only, tag every ECHO (default). */
    public static final int EARLY_LATE_ECHO = 1;
    /** {@link #earlyLate}: both switches on, tag ECHO and RESONANCE outside ±33 ms. */
    public static final int EARLY_LATE_ALL = 2;
    /** {@link #earlyLate}: RESONANCE switch only, tag RESONANCE outside ±33 ms (Kelvin 10-12 22:04). */
    public static final int EARLY_LATE_RESONANCE = 3;

    /** {@link #strayTouches}: SHOW TOUCHES on (a ring under every finger in a game since 2026-10-18 19:52). */
    public static final int STRAY_ON = 1;
    /** {@link #strayTouches}: SHOW TOUCHES off. */
    public static final int STRAY_OFF = 2;

    /** {@link #pauseMode}: hold the pause control for 0.35 s. */
    public static final int PAUSE_HOLD = 0;
    /** {@link #pauseMode}: double-tap the pause control within 300 ms (default, Kelvin 2026-10-12). */
    public static final int PAUSE_DOUBLE_TAP = 1;
    /** {@link #pauseMode}: single tap. */
    public static final int PAUSE_SINGLE_TAP = 2;

    /** {@link #judgementDisplay}: centre (default). */
    public static final int JUDGEMENT_CENTRE = 0;
    /** {@link #judgementDisplay}: at the lane. */
    public static final int JUDGEMENT_AT_LANE = 1;
    /** {@link #judgementDisplay}: hidden. */
    public static final int JUDGEMENT_OFF = 2;

    /** {@link #haptics}: off. */
    public static final int HAPTICS_OFF = 0;
    /** {@link #haptics}: menus only (default). */
    public static final int HAPTICS_MENUS = 1;
    /** {@link #haptics}: menus and judgements. */
    public static final int HAPTICS_ALL = 2;

    /** English UI. */
    /** {@link #syncHint}: a halo round each note that shares its beat (default, Kelvin 2026-10-13). */
    public static final int SYNC_HINT_HIGHLIGHT = 0;
    /** {@link #syncHint}: the halo and the gold (red on mines) sync pearls. */
    public static final int SYNC_HINT_PEARLS = 1;
    /** {@link #syncHint}: no sync hint. */
    public static final int SYNC_HINT_OFF = 2;
    public static final String LANG_EN = "en";
    /** Chinese subtitles. */
    public static final String LANG_ZH = "zh";

    // AUDIO
    /** Hit sound mode: {@link #HIT_ON_JUDGEMENT} or {@link #HIT_ON_ANY_ZONE_TAP}; always the first since 2026-10-18. */
    public int hitSoundMode = HIT_ON_JUDGEMENT;
    /** Song preview on the map (always on since 2026-10-18 14:29, see {@link #sanitize}). */
    public boolean previewOn = true;
    /** Menu background music (always on since 2026-10-18 14:29). */
    public boolean menuMusicOn = true;

    // TIMING
    /**
     * EARLY/LATE switches packed in one int so old saves keep their meaning: {@link #EARLY_LATE_OFF},
     * {@link #EARLY_LATE_ECHO}, {@link #EARLY_LATE_ALL}, {@link #EARLY_LATE_RESONANCE}. Read it through
     * {@link #showsEarlyLate}.
     */
    public int earlyLate = EARLY_LATE_ECHO;
    /** Show the timing error in ms. */
    public boolean showTimingMs = false;
    /** Show the hit error meter. */
    public boolean showTimingMeter = false;

    // VISUAL
    /** Global glitch multiplier 0..1 (detents 0 / .35 / .6 / 1). */
    public float glitchIntensity = 0.6f;
    /** Cap flashes at α .10. */
    public boolean reduceFlashing = false;
    /** Reduce motion. */
    public boolean reduceMotion = false;
    /** Show chart background art in gameplay (always on since 2026-10-18 14:29). */
    public boolean backgroundArt = true;
    /** Opacity of the background art 0..1 (BACKGROUND BRIGHTNESS on VISUAL since 2026-10-18 14:29). */
    public float artOpacity = 1.0f;
    /** Gameplay background dim 0..0.9 (shown as BACKGROUND BRIGHTNESS = 1 − dim since 2026-10-18 14:29). */
    public float backgroundDim = 0.35f;
    /** Note size scale 0.8..1.3. */
    public float noteSizeScale = 1.0f;
    /** {@link #hitPointPx}: range and default (the line through the key's middle at the judgement time). */
    public static final float HIT_POINT_MIN = -100f, HIT_POINT_MAX = 100f, HIT_POINT_DEFAULT = 0f;
    /**
     * VISUAL HIT POINT 目押校准 (Kelvin 2026-10-20 03:20), {@link #HIT_POINT_MIN}..{@link #HIT_POINT_MAX} px: how far a
     * TAP or HOLD key's midline is from its judgement line at the judgement time, measured at note size 1.0 (a larger
     * key scales it). {@link #HIT_POINT_DEFAULT} 0 puts the line through the key's middle (Kelvin 2026-10-20 22:37:
     * 「默认值为0而不是20」); 20, the default before, is half the key: its edge on the line. Every note is drawn that
     * much nearer to (or further past) its line.
     */
    public float hitPointPx = HIT_POINT_DEFAULT;
    /** Show the ±108 judgement zone hint. */
    public boolean zoneHint = true;
    /** Approach glow on notes (always on since 2026-10-18 16:19: no row, its effect was too faint to choose). */
    public boolean approachGlow = true;
    /**
     * FC / AR INDICATOR (Kelvin 2026-10-18 16:17): the dive's score is light blue while every judgement is RESONANCE,
     * purple while the combo holds through ECHOs, white once it broke.
     */
    public boolean fcArIndicator = true;
    /**
     * SHOW TOUCHES: {@link #STRAY_ON} or {@link #STRAY_OFF} (default); AUTO reads as off. The name is from when it
     * marked only touches that hit no zone (stray-touch dots, until 2026-10-18 19:52).
     */
    public int strayTouches = STRAY_OFF;
    /**
     * How notes that share their beat are marked: {@link #SYNC_HINT_HIGHLIGHT} (default), {@link #SYNC_HINT_PEARLS},
     * {@link #SYNC_HINT_OFF}.
     */
    public int syncHint = SYNC_HINT_HIGHLIGHT;

    // GAMEPLAY
    /** Pause input: {@link #PAUSE_DOUBLE_TAP} (default), {@link #PAUSE_HOLD}, {@link #PAUSE_SINGLE_TAP}. */
    public int pauseMode = PAUSE_DOUBLE_TAP;
    /** Judgement word: {@link #JUDGEMENT_CENTRE}, {@link #JUDGEMENT_AT_LANE}, {@link #JUDGEMENT_OFF}. */
    public int judgementDisplay = JUDGEMENT_CENTRE;
    /** Live counters in the band layout (no settings row since 2026-10-18: no landscape screen uses that layout). */
    public boolean liveCounters = true;
    /** Haptics: {@link #HAPTICS_OFF}, {@link #HAPTICS_MENUS}, {@link #HAPTICS_ALL}. */
    public int haptics = HAPTICS_MENUS;
    /** UI language: {@link #LANG_EN} (default) or {@link #LANG_ZH} (adds zh subtitles). */
    public String language = LANG_EN;

    /** Creates default settings. */
    public Settings() {
    }

    /** @return true when zh subtitles should be drawn. */
    public boolean zh() {
        return LANG_ZH.equals(language);
    }

    /** @return whether the RESONANCE switch is on (tags RESONANCE hits outside ±33 ms). */
    public boolean earlyLateResonance() {
        return earlyLate == EARLY_LATE_ALL || earlyLate == EARLY_LATE_RESONANCE;
    }

    /** @return whether the ECHO switch is on (tags every ECHO hit). */
    public boolean earlyLateEcho() {
        return earlyLate == EARLY_LATE_ALL || earlyLate == EARLY_LATE_ECHO;
    }

    /** Stores the two EARLY/LATE switches. */
    public void setEarlyLate(boolean resonance, boolean echo) {
        earlyLate = resonance ? (echo ? EARLY_LATE_ALL : EARLY_LATE_RESONANCE)
            : (echo ? EARLY_LATE_ECHO : EARLY_LATE_OFF);
    }

    /** @return whether a hit of grade {@code j}, {@code dtMs} off centre, gets an EARLY/LATE tag. */
    public boolean showsEarlyLate(Judgement j, float dtMs) {
        if (j == Judgement.ECHO) return earlyLateEcho() && dtMs != 0f;
        return j == Judgement.RESONANCE && earlyLateResonance() && !Judgement.isPerfect(dtMs);
    }

    /** Clamps every value into its valid range (after loading untrusted JSON). */
    public void sanitize() {
        // no rows since Kelvin 2026-10-18 14:29: hit sounds on judgement only, song previews, menu music and the
        // background artwork always on (its brightness is the BACKGROUND BRIGHTNESS slider); kept for old saves
        hitSoundMode = HIT_ON_JUDGEMENT;
        previewOn = true;
        menuMusicOn = true;
        backgroundArt = true;
        // no APPROACH GLOW row since Kelvin 2026-10-18 16:19 (「感觉效果不明显」): the glow stays on
        approachGlow = true;
        earlyLate = clamp(earlyLate, 0, 3);
        glitchIntensity = clamp(glitchIntensity, 0f, 1f);
        artOpacity = clamp(artOpacity, 0f, 1f);
        backgroundDim = clamp(backgroundDim, 0f, 0.9f);
        noteSizeScale = clamp(noteSizeScale, 0.8f, 1.3f);
        hitPointPx = Float.isNaN(hitPointPx) ? HIT_POINT_DEFAULT : Math.round(clamp(hitPointPx, HIT_POINT_MIN,
            HIT_POINT_MAX));
        strayTouches = clamp(strayTouches, 0, 2);
        syncHint = clamp(syncHint, 0, 2);
        pauseMode = clamp(pauseMode, 0, 2);
        judgementDisplay = clamp(judgementDisplay, 0, 2);
        haptics = clamp(haptics, 0, 2);
        if (!LANG_ZH.equals(language)) language = LANG_EN;
    }

    /** @return a deep copy. */
    public Settings copy() {
        Settings s = new Settings();
        s.hitSoundMode = hitSoundMode;
        s.previewOn = previewOn;
        s.menuMusicOn = menuMusicOn;
        s.earlyLate = earlyLate;
        s.showTimingMs = showTimingMs;
        s.showTimingMeter = showTimingMeter;
        s.glitchIntensity = glitchIntensity;
        s.reduceFlashing = reduceFlashing;
        s.reduceMotion = reduceMotion;
        s.backgroundArt = backgroundArt;
        s.artOpacity = artOpacity;
        s.backgroundDim = backgroundDim;
        s.noteSizeScale = noteSizeScale;
        s.hitPointPx = hitPointPx;
        s.zoneHint = zoneHint;
        s.approachGlow = approachGlow;
        s.fcArIndicator = fcArIndicator;
        s.strayTouches = strayTouches;
        s.syncHint = syncHint;
        s.pauseMode = pauseMode;
        s.judgementDisplay = judgementDisplay;
        s.liveCounters = liveCounters;
        s.haptics = haptics;
        s.language = language;
        return s;
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
