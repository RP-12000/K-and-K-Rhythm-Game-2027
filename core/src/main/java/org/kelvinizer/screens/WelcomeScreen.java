package org.kelvinizer.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.Params;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.screens.shell.CreateDiverSheet;
import org.kelvinizer.screens.shell.ShellFormat;
import org.kelvinizer.screens.shell.ShellScreen;
import org.kelvinizer.screens.shell.SwitcherSheet;
import org.kelvinizer.screens.shell.Tappable;
import org.kelvinizer.transition.TransitionLayer;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Backdrop;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Haptics;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.SonarField;
import org.kelvinizer.ui.Spring;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 欢迎画面：开场时两颗光点螺旋下沉、相撞，浮现 {@code K & K} 标志，
 * 然后提示 TAP TO DESCEND；左下角切换潜水员，右下角进设置。
 * 点击后，首次启动进入首次设置，否则下潜进入地图。
 *
 * <p>Title screen (DESIGN §7).
 *
 * <p><b>Intro</b> (t = seconds since show): fade from {@code #02040F} (400 ms); 0.10–0.70 two
 * motes ({@code groundSig}, {@code skySig}) spiral down a helix from y 1,160 into (960, 640),
 * radius 220 → 0 ({@code exp5Out}); 0.70 they collide: a sonar ping and the logo appears with a
 * G2; 0.75–1.25 {@code K & K} decodes (60 ms stagger, DISPLAY_SOFT 140) and rests at G1 with an
 * idle G2 every 1.8–3.5 s; 1.10 the tagline; 1.20 the breathing {@code TAP TO DESCEND} synced to
 * the logo's 2.4 s heartbeat. Idle: two ring emitters at (760, 640) and (1160, 640) alternate
 * every 1.2 s ({@code groundSig} / {@code skySig} interference). Returning to Welcome under a
 * transition skips the intro.
 *
 * <p><b>Input</b> from 0.3 s: a tap (or any key) before 1.2 s fast-forwards to the final state in
 * 300 ms; afterwards it descends. Play (P1): dragging leaves sonar ripples and bubbles, the two K
 * glyphs lean toward the finger (±16 and ±4° on a jelly spring) while the backdrop shafts tilt
 * ±4° with the drag, tapping a K pings it with a jiggle and a
 * G2, and hovering a K (desktop) brightens it.
 *
 * <p><b>Corners</b>: bottom-left the profile chip {@code DIVING AS NAME · LV 12 · 2,560 m ▾}
 * opening the {@link SwitcherSheet}; bottom-right the Settings gear; the version at the bottom
 * centre. The corners, the tagline and the prompt are sized × {@code Ui.uiScale()} about their
 * anchors (DESIGN §0.3); the logo is display art and keeps its size.
 * <b>Exit</b>: first run → IRIS to FirstRun; otherwise "the descent" (§3.12): the logo
 * flies up 200 and fades, the motes dive out of the bottom, snow streaks upward, the shared
 * backdrop starts ramping toward the depth the map camera arrives at
 * ({@link org.kelvinizer.map.MapScreen#arrivalDepth()}), and
 * after 450 ms the map is switched in. Showing Welcome also creates the map early so its
 * thumbnails start building (DESIGN §3.3). BACK exits the app.
 */
public class WelcomeScreen extends ShellScreen {
    /** Version line at the bottom centre. */
    private static final CharSequence VERSION_LINE = Lang.of("VERSION " + Params.VERSION, "版本 " + Params.VERSION);
    private static final CharSequence TAGLINE = Lang.of("TWO SIGNALS · ONE DESCENT", "两道信号 · 一同下潜");
    private static final CharSequence PROMPT = Lang.of("TAP TO DESCEND", "点击下潜");
    /** Logo centre y, tagline baseline, prompt baseline (DESIGN §7). */
    public static final float LOGO_Y = 640f, TAGLINE_Y = 520f, PROMPT_Y = 220f;
    private static final float LOGO_CAP = 140f, LOGO_GAP = 44f;
    /** Profile chip height and text cap at uiScale 1. */
    private static final float CHIP_H = 64f, CHIP_CAP = 20f;
    private static final float INPUT_FROM = 0.30f, FAST_FORWARD = 0.30f, EXIT_TIME = 0.45f;
    private static final float COLLIDE = 0.70f, DECODE_START = 0.75f, DECODE_STAGGER = 0.06f, DECODE_DUR = 0.38f;
    private static final float TAGLINE_AT = 1.10f, PROMPT_AT = 1.20f, INTRO_END = 1.60f;
    private static final float EMITTER_PERIOD = 1.2f, LEAN = 16f, LEAN_TILT = 4f, SHAFT_TILT = 4f;
    private static final String[] GLYPH = {"K", "&", "K"};
    private static final String POOL = "K&AND";
    private static final int TRAIL = 7, BUBBLES = 24;

    private final GlitchState[] glyphGlitch = new GlitchState[3];
    private final StringBuilder[] glyphText = new StringBuilder[3];
    private final Spring[] leanX = new Spring[3], leanY = new Spring[3];
    private final float[] glyphX = new float[3], glyphW = new float[3];
    private final float[] glyphFlash = new float[3];
    private final float[] mote = new float[3];
    private final Tappable chip;
    private final Button gear;
    private final SwitcherSheet switcher;
    private final CreateDiverSheet createSheet;
    private final StringBuilder chipText = new StringBuilder(64);
    private final StringBuilder chipName = new StringBuilder(32);
    private final StringBuilder chipPost = new StringBuilder(40);
    private float chipPreW, chipNameW, chipPostW;
    private final Color tmp = new Color();
    // bubbles (P1 play)
    private final float[] bx = new float[BUBBLES], by = new float[BUBBLES], br = new float[BUBBLES];
    private final float[] bvy = new float[BUBBLES], bage = new float[BUBBLES], blife = new float[BUBBLES];
    private int bubbleNext;

    private float introT, ffFrom = -1f, ffT;
    private boolean collided, settled;
    private float nextEmitter, nextBurst;
    private boolean emitLeft;
    private float exitT = -1f;
    private boolean routed;
    private int chipRevision = Integer.MIN_VALUE;
    private String chipProfileId;
    private boolean chipZh;
    private float chipW;
    // touch tracking
    private int downPointer = -1, pressedGlyph = -1, hoverGlyph = -1;
    private float downX, downY, lastRippleX, lastRippleY, lastBubbleX, lastBubbleY;
    private boolean dragging;

    /** Creates the screen once (Main caches it). */
    public WelcomeScreen(final Main game) {
        // No welcome art for now, only the water and its caustic ripples (Kelvin 10-12 22:04: 「欢迎界面背景图先不要了，就只保留水纹那种纹理即可」).
        super(game, null, null, 0.35f, 1f, true);
        for (int i = 0; i < 3; i++) {
            glyphGlitch[i] = game.glitch().obtainState();
            glyphText[i] = new StringBuilder(2).append(GLYPH[i]);
            leanX[i] = new Spring(Spring.Preset.JELLY, 0f);
            leanY[i] = new Spring(Spring.Preset.JELLY, 0f);
        }
        chip = widgets.add(new Tappable(game.draw()));
        chip.setOnTap(new Runnable() {
            @Override
            public void run() {
                openSwitcher();
            }
        });
        gear = widgets.add(new Button(game.draw(), game.fonts(), Icons.Icon.GEAR));
        gear.setOnTap(new Runnable() {
            @Override
            public void run() {
                openScreen(game.settings(), Lang.t("SETTINGS", "设置"));
            }
        });
        createSheet = new CreateDiverSheet(game, this);
        switcher = new SwitcherSheet(game, this, new Runnable() {
            @Override
            public void run() {
                openModal(createSheet);
            }
        }, new Runnable() {
            @Override
            public void run() {
                game.profile().selectTab(ProfileScreen.TAB_DIVERS);
                openScreen(game.profile(), Lang.t("PROFILE", "账号与统计"));
            }
        });
    }

    @Override
    protected CharSequence screenLabel() {
        return "K & K";
    }

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void enterScreen() {
        exitT = -1f;
        routed = false;
        ffFrom = -1f;
        downPointer = -1;
        pressedGlyph = -1;
        hoverGlyph = -1;
        dragging = false;
        for (int i = 0; i < BUBBLES; i++) bage[i] = blife[i] = 0f;
        for (int i = 0; i < 3; i++) {
            leanX[i].snap(0f);
            leanY[i].snap(0f);
            glyphFlash[i] = 0f;
            glyphGlitch[i].ambient(GlitchText.Level.G0);
        }
        chipRevision = Integer.MIN_VALUE;
        game.map(); // prewarm: builds the map world and starts its thumbnails (DESIGN §3.3)
        // Shown under a transition (back from Settings / Profile / FirstRun): no intro.
        if (game.transitions().isBusy()) {
            skipIntro();
        } else {
            introT = 0f;
            collided = false;
            settled = false;
        }
        nextEmitter = Math.max(introT, DECODE_START + 0.5f);
        emitLeft = true;
        nextBurst = clock + 1.8f + MathUtils.random(1.7f);
    }

    private void skipIntro() {
        introT = INTRO_END;
        collided = true;
        settle();
        game.backdrop().setFocus(Ui.CX, LOGO_Y);
    }

    private void settle() {
        if (settled) return;
        settled = true;
        for (int i = 0; i < 3; i++) glyphGlitch[i].ambient(GlitchText.Level.G1);
    }

    @Override
    protected void back() {
        Gdx.app.exit();
    }

    @Override
    public void onActiveDiverChanged() {
        for (int i = 0; i < 3; i++) glyphGlitch[i].trigger(GlitchText.Level.G2, clock, 0.25f);
        chipRevision = Integer.MIN_VALUE;
    }

    @Override
    public void onDiversEdited() {
        chipRevision = Integer.MIN_VALUE;
    }

    @Override
    protected void disposeScreen() {
        for (int i = 0; i < 3; i++) game.glitch().freeState(glyphGlitch[i]);
    }

    // ------------------------------------------------------------------ layout
    @Override
    protected void layout() {
        Fonts f = game.fonts();
        float wk = f.width(Fonts.Role.DISPLAY_SOFT, LOGO_CAP, Fonts.GlyphSet.LOGO, "K");
        float wa = f.width(Fonts.Role.DISPLAY_SOFT, LOGO_CAP, Fonts.GlyphSet.LOGO, "&");
        float total = 2f * wk + wa + 2f * LOGO_GAP;
        float x0 = Ui.CX - total * 0.5f;
        glyphW[0] = wk;
        glyphW[1] = wa;
        glyphW[2] = wk;
        glyphX[0] = x0 + wk * 0.5f;
        glyphX[1] = x0 + wk + LOGO_GAP + wa * 0.5f;
        glyphX[2] = x0 + wk + wa + 2f * LOGO_GAP + wk * 0.5f;
        float cy = cornerY();
        float gd = Button.ICON_DIAMETER * Ui.uiScale();
        gear.setBounds(Ui.safeRight() - 24f - gd, cy - gd * 0.5f, gd, gd);
        chipRevision = Integer.MIN_VALUE;
    }

    /** Centre y of the bottom corners (chip, version, gear). */
    private float cornerY() {
        return Ui.safeBottom() + 24f + Button.ICON_DIAMETER * Ui.uiScale() * 0.5f;
    }

    private void refreshChip() {
        Profile p = diver();
        int rev = game.profiles().revision();
        String id = p != null ? p.id : null;
        if (rev == chipRevision && chipZh == Lang.zh() && (id == null ? chipProfileId == null
            : id.equals(chipProfileId))) return;
        chipRevision = rev;
        chipProfileId = id;
        chipZh = Lang.zh();
        // three runs so a CJK name does not pull the Latin text into the CJK font
        chipText.setLength(0);
        chipText.append(Lang.t("DIVING AS", "玩家"));
        chipName.setLength(0);
        chipPost.setLength(0);
        if (p != null) {
            chipName.append(p.name != null ? p.name : "");
            chipPost.append(" · ");
            ShellFormat.appendDepth(chipPost, p.depthMeters());
        }
        Fonts f = game.fonts();
        float k = Ui.uiScale(), cap = CHIP_CAP * k;
        // GlyphLayout drops a trailing space's advance, so the gap is measured separately
        chipPreW = f.width(Fonts.Role.UI, cap, chipText) + f.width(Fonts.Role.UI, cap, "A A")
            - f.width(Fonts.Role.UI, cap, "AA");
        chipNameW = f.width(Fonts.Role.UI, cap, chipName);
        chipPostW = f.width(Fonts.Role.UI, cap, chipPost);
        float maxW = Ui.CX - 140f * k - (Ui.safeLeft() + 24f);
        float textW = chipPreW + chipNameW + chipPostW;
        chipW = Math.min(maxW, textW + (28f + 12f + 24f + 24f) * k);
        chip.setBounds(Ui.safeLeft() + 24f, cornerY() - CHIP_H * 0.5f * k, chipW, CHIP_H * k);
    }

    // ------------------------------------------------------------------ update
    @Override
    protected void update(float delta) {
        refreshChip();
        boolean corners = cornerAlpha() > 0.3f && exitT < 0f;
        chip.setVisible(corners && !game.profiles().isFirstRun());
        gear.setVisible(corners);
        if (ffFrom >= 0f) {
            ffT += delta;
            float p = Anim.POW2_OUT.apply(Anim.clamp01(ffT / FAST_FORWARD));
            introT = Anim.lerp(ffFrom, INTRO_END, p);
            if (ffT >= FAST_FORWARD) ffFrom = -1f;
        } else if (introT < INTRO_END) {
            introT = Math.min(INTRO_END, introT + delta);
        } else {
            introT += delta;
        }
        Backdrop b = game.backdrop();
        if (!collided && introT >= COLLIDE) {
            collided = true;
            b.sonar().ping(Ui.CX, LOGO_Y, 900f, 0.7f, b.accent(), 0.4f, 2);
            for (int i = 0; i < 3; i++) glyphGlitch[i].trigger(GlitchText.Level.G2, clock, 0.3f);
            b.setFocus(Ui.CX, LOGO_Y);
            Haptics.light();
        }
        if (!settled && introT >= DECODE_START + 2f * DECODE_STAGGER + DECODE_DUR) settle();
        if (settled && exitT < 0f) {
            if (introT >= nextEmitter) {
                nextEmitter = introT + EMITTER_PERIOD;
                SonarField s = b.sonar();
                if (emitLeft) s.ping(760f, LOGO_Y, 1100f, 3.2f, Theme.GROUND_SIG, 0.18f, 1);
                else s.ping(1160f, LOGO_Y, 1100f, 3.2f, Theme.SKY_SIG, 0.18f, 1);
                emitLeft = !emitLeft;
            }
            if (clock >= nextBurst) {
                nextBurst = clock + 1.8f + MathUtils.random(1.7f);
                for (int i = 0; i < 3; i++) glyphGlitch[i].trigger(GlitchText.Level.G2, clock);
            }
        }
        for (int i = 0; i < 3; i++) {
            leanX[i].update(delta);
            leanY[i].update(delta);
            glyphFlash[i] = Math.max(0f, glyphFlash[i] - delta / 0.4f);
        }
        for (int i = 0; i < BUBBLES; i++) {
            if (bage[i] >= blife[i]) continue;
            bage[i] += delta;
            by[i] += bvy[i] * delta;
        }
        if (exitT >= 0f) {
            exitT += delta;
            float p = Anim.clamp01(exitT / EXIT_TIME);
            b.setVirtualVelocity(0f, -2400f * Anim.POW2_IN.apply(p));
            if (exitT >= EXIT_TIME && !routed) {
                routed = true;
                game.map().arriveFromWelcome();
                game.switchNow(game.map());
            }
        }
    }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void drawScreen(float delta) {
        float out = exitT >= 0f ? Anim.POW2_IN.apply(Anim.clamp01(exitT / EXIT_TIME)) : 0f;
        drawBubbles();
        if (introT >= 0.1f && introT < COLLIDE) drawIntroMotes();
        if (exitT >= 0f) drawExitMotes(out);
        drawCollision();
        drawLogo(out);
        drawTexts(out);
        drawCorners(1f - out);
    }

    private void drawIntroMotes() {
        Draw d = game.draw();
        for (int m = 0; m < 2; m++) {
            Color c = m == 0 ? Theme.GROUND_SIG : Theme.SKY_SIG;
            for (int k = TRAIL - 1; k >= 0; k--) {
                float t = introT - k * 0.018f;
                if (t < 0.1f) continue;
                helix(t, m, mote);
                float fade = 1f - k / (float) TRAIL;
                float size = (26f + 10f * mote[2]) * (0.45f + 0.55f * fade);
                float a = (0.75f + 0.25f * mote[2]) * fade * fade;
                if (k == 0) d.glowDot(mote[0], mote[1], size * 4f, c, 0.35f * a);
                d.glowDot(mote[0], mote[1], size, c, a);
            }
            helix(introT, m, mote);
            d.disc(mote[0], mote[1], 5f + 2f * mote[2], Theme.WHITE, 0.9f);
        }
    }

    /** Position of mote {@code m} on the helix at intro time {@code t}: x, y, depth z (−1..1). */
    private void helix(float t, int m, float[] out) {
        float p = Anim.clamp01((t - 0.1f) / (COLLIDE - 0.1f));
        float e = Anim.EXP5_OUT.apply(p);
        float r = 220f * (1f - e);
        float y = 1160f + (LOGO_Y - 1160f) * Anim.POW3_OUT.apply(p);
        float theta = MathUtils.PI2 * (2.2f * e + 1.6f * p) + (m == 0 ? 0f : MathUtils.PI);
        out[0] = Ui.CX + r * MathUtils.cos(theta);
        out[1] = y + r * 0.18f * MathUtils.sin(theta);
        out[2] = MathUtils.sin(theta);
    }

    private void drawExitMotes(float out) {
        Draw d = game.draw();
        float bottom = Ui.bottom() - 80f;
        for (int m = 0; m < 2; m++) {
            Color c = m == 0 ? Theme.GROUND_SIG : Theme.SKY_SIG;
            float side = m == 0 ? -1f : 1f;
            for (int k = TRAIL - 1; k >= 0; k--) {
                float p = Anim.clamp01((exitT - k * 0.02f) / EXIT_TIME);
                float e = Anim.POW2_IN.apply(p);
                float x = Ui.CX + side * 70f * MathUtils.sin(p * MathUtils.PI * 1.5f);
                float y = LOGO_Y + (bottom - LOGO_Y) * e;
                float fade = 1f - k / (float) TRAIL;
                d.glowDot(x, y, 30f * (0.5f + 0.5f * fade), c, fade * fade);
            }
        }
    }

    private void drawCollision() {
        if (!collided || introT > COLLIDE + 0.6f) return;
        float p = Anim.clamp01((introT - COLLIDE) / 0.6f);
        float a = (1f - p) * (1f - p);
        Draw d = game.draw();
        d.glowDot(Ui.CX, LOGO_Y, 260f + 900f * Anim.POW3_OUT.apply(p), Theme.INK1, 0.30f * a);
        d.glowDot(Ui.CX, LOGO_Y, 120f + 200f * p, Theme.WHITE, 0.6f * a);
    }

    private void drawLogo(float out) {
        if (introT < COLLIDE) return;
        GlitchText g = game.glitch();
        Draw d = game.draw();
        float alphaIn = Anim.clamp01((introT - COLLIDE) / 0.12f);
        float a = alphaIn * (1f - out);
        if (a <= 0.002f) return;
        float ly = LOGO_Y + 200f * out;
        for (int i = 0; i < 3; i++) {
            float start = DECODE_START + i * DECODE_STAGGER;
            float reveal = start + DECODE_DUR;
            float ga = a;
            StringBuilder sb = glyphText[i];
            if (introT < reveal) {
                int tick = (int) (introT * 30f);
                int idx = (int) (Anim.hash01(tick * 7 + i * 131) * POOL.length()) % POOL.length();
                sb.setCharAt(0, POOL.charAt(idx));
                float flicker = 0.55f + 0.45f * Anim.hash01(tick, i + 17);
                ga *= introT < start ? 0.45f * flicker : 0.8f * flicker;
            } else {
                sb.setCharAt(0, GLYPH[i].charAt(0));
            }
            Color base = i == 0 ? Theme.GROUND_SIG : (i == 1 ? Theme.INK2 : Theme.SKY_SIG);
            if (i == 1) ga *= 0.7f;
            float bright = 1f + (i == hoverGlyph ? 0.25f : 0f) + 0.6f * glyphFlash[i];
            Theme.brighten(base, bright, tmp);
            float cx = glyphX[i] + leanX[i].value();
            float cy = ly + leanY[i].value();
            if (i != 1 && (i == hoverGlyph || glyphFlash[i] > 0f)) {
                d.glowDot(cx, cy, 300f, base, (0.10f + 0.25f * glyphFlash[i]) * a);
            }
            // the K's lean also tilts them up to ±LEAN_TILT° toward the finger (top leads)
            float tilt = -LEAN_TILT * leanX[i].value() / LEAN;
            g.draw(batch, Fonts.Role.DISPLAY_SOFT, LOGO_CAP, Fonts.GlyphSet.LOGO, sb, cx, cy,
                Align.center | Fonts.VCENTER, tmp, ga, glyphGlitch[i], clock, 1f, tilt);
        }
    }

    private void drawTexts(float out) {
        Fonts f = game.fonts();
        float k = Ui.uiScale();
        float ta = Anim.ease(Anim.POW3_OUT, introT, TAGLINE_AT, 0.32f) * (1f - out);
        if (ta > 0.002f) {
            f.draw(batch, Fonts.Role.UI_LABEL, 20f * k, TAGLINE, Ui.CX,
                TAGLINE_Y - 12f * (1f - ta) + 200f * out, Align.center, Theme.INK2, ta);
        }
        float pa = Anim.ease(Anim.POW3_OUT, introT, PROMPT_AT, 0.32f) * (1f - out);
        if (pa > 0.002f) {
            float phase = game.backdrop().sonar().heartbeatPhase();
            float breathe = 0.45f + 0.55f * (0.5f + 0.5f * MathUtils.cos(phase * MathUtils.PI2));
            f.draw(batch, Fonts.Role.UI_BOLD, 26f * k, PROMPT, Ui.CX, PROMPT_Y, Align.center, Theme.INK1,
                breathe * pa);
        }
    }

    private float cornerAlpha() {
        return Anim.ease(Anim.POW2_OUT, introT, 1.25f, 0.4f);
    }

    @Override
    protected float widgetAlpha() {
        float out = exitT >= 0f ? Anim.clamp01(exitT / 0.2f) : 0f;
        return cornerAlpha() * (1f - out);
    }

    private void drawCorners(float k) {
        float a = cornerAlpha() * k;
        if (a <= 0.002f) return;
        Draw d = game.draw();
        Fonts f = game.fonts();
        float u = Ui.uiScale(), cap = CHIP_CAP * u;
        f.draw(batch, Fonts.Role.UI, 16f * u, VERSION_LINE, Ui.CX, cornerY(), Align.center | Fonts.VCENTER, Theme.INK3, a);
        if (game.profiles().isFirstRun()) return;
        float s = chip.visualScale();
        float cx = chip.centerX() + chip.shakeX(), cy = chip.centerY();
        float w = chipW * s, h = CHIP_H * u * s;
        float x = cx - w * 0.5f;
        d.pill(x, cy - h * 0.5f, w, h, Theme.PANEL, a);
        d.roundRectStroke(x, cy - h * 0.5f, w, h, h * 0.5f, 1.5f, Theme.STROKE, a);
        if (chip.pressAmount() > 0f || chip.hoverAmount() > 0f) {
            d.pill(x, cy - h * 0.5f, w, h, Theme.INK1, (0.08f * chip.pressAmount() + 0.05f * chip.hoverAmount()) * a);
        }
        chip.drawPress(Theme.INK1, a);
        float avail = w - (28f + 60f) * u;
        float total = chipPreW + chipNameW + chipPostW;
        float fit = total > avail && total > 0f ? Math.max(0.7f, avail / total) : 1f;
        float tx = x + 28f * u;
        f.drawScaled(batch, Fonts.Role.UI, cap, fit, chipText, tx, cy, Align.left | Fonts.VCENTER, -1f, 1f, Theme.INK1,
            a);
        tx += chipPreW * fit;
        float nameMax = Math.max(40f * u, avail - (chipPreW + chipPostW) * fit);
        tx += f.drawScaled(batch, Fonts.Role.UI, cap, fit, chipName, tx, cy, Align.left | Fonts.VCENTER, nameMax, 0.8f,
            Theme.INK1, a);
        f.drawScaled(batch, Fonts.Role.UI, cap, fit, chipPost, tx, cy, Align.left | Fonts.VCENTER, -1f, 1f, Theme.INK1,
            a);
        Icons.draw(d.shapes(), Icons.Icon.TRIANGLE_DOWN, x + w - 36f * u, cy, 22f * u, Theme.INK2, a);
    }

    private void drawBubbles() {
        Draw d = game.draw();
        for (int i = 0; i < BUBBLES; i++) {
            if (bage[i] >= blife[i]) continue;
            float p = bage[i] / blife[i];
            float a = (p < 0.15f ? p / 0.15f : 1f - (p - 0.15f) / 0.85f) * 0.55f;
            float x = bx[i] + 10f * MathUtils.sin(bage[i] * 3.1f + i);
            d.ringSprite(x, by[i], br[i], Theme.INK1, a);
            d.disc(x - br[i] * 0.35f, by[i] + br[i] * 0.35f, Math.max(1.5f, br[i] * 0.18f), Theme.WHITE, a);
        }
    }

    @Override
    protected void drawOverlay(float delta) {
        float a = 1f - Anim.clamp01(time / 0.4f);
        if (a > 0.002f && introT < INTRO_END) {
            game.draw().rect(Ui.left(), Ui.bottom(), Ui.worldW(), Ui.worldH(), Theme.ABYSS_BLACK, a);
        }
    }

    // ------------------------------------------------------------------ input
    private boolean inputReady() {
        return time >= INPUT_FROM && exitT < 0f;
    }

    private int glyphAt(float x, float y) {
        if (introT < COLLIDE + 0.1f) return -1;
        float hh = Math.max(LOGO_CAP, Ui.touchTarget()) * 0.5f;
        for (int i = 0; i < 3; i += 2) {
            float hw = Math.max(glyphW[i], Ui.touchTarget()) * 0.5f;
            if (Math.abs(x - glyphX[i]) <= hw && Math.abs(y - LOGO_Y) <= hh) return i;
        }
        return -1;
    }

    @Override
    protected boolean contentTouchDown(float x, float y, int pointer) {
        if (!inputReady() || downPointer >= 0) return true;
        downPointer = pointer;
        downX = lastRippleX = lastBubbleX = x;
        downY = lastRippleY = lastBubbleY = y;
        dragging = false;
        pressedGlyph = glyphAt(x, y);
        return true;
    }

    @Override
    protected boolean contentTouchDragged(float x, float y, int pointer) {
        if (pointer != downPointer) return false;
        if (!dragging && Math.abs(x - downX) + Math.abs(y - downY) > Ui.slop()) {
            dragging = true;
            pressedGlyph = -1;
        }
        if (dragging) {
            lean(x, y);
            // the shafts tilt with the drag, tops following the finger (DESIGN §7 Play)
            game.backdrop().setShaftTilt(-SHAFT_TILT * MathUtils.clamp((x - downX) / 480f, -1f, 1f));
            float drx = x - lastRippleX, dry = y - lastRippleY;
            if (drx * drx + dry * dry > 110f * 110f) {
                game.backdrop().touch(x, y);
                lastRippleX = x;
                lastRippleY = y;
            }
            float dbx = x - lastBubbleX, dby = y - lastBubbleY;
            if (dbx * dbx + dby * dby > 48f * 48f) {
                spawnBubble(x, y);
                lastBubbleX = x;
                lastBubbleY = y;
            }
        }
        return true;
    }

    @Override
    protected boolean contentTouchUp(float x, float y, int pointer) {
        if (pointer != downPointer) return false;
        downPointer = -1;
        releaseLean();
        if (dragging) {
            dragging = false;
            return true;
        }
        if (!inputReady()) return true;
        if (pressedGlyph >= 0 && glyphAt(x, y) == pressedGlyph) {
            pingGlyph(pressedGlyph);
            pressedGlyph = -1;
            return true;
        }
        pressedGlyph = -1;
        advance(x, y);
        return true;
    }

    @Override
    protected void contentCancel(int pointer) {
        if (pointer == downPointer) {
            downPointer = -1;
            pressedGlyph = -1;
            dragging = false;
            releaseLean();
        }
    }

    @Override
    protected void contentMouseMoved(float x, float y) {
        int g = glyphAt(x, y);
        if (g >= 0) {
            float hw = glyphW[g] * 0.5f;
            if (Math.abs(x - glyphX[g]) > hw || Math.abs(y - LOGO_Y) > LOGO_CAP * 0.5f) g = -1;
        }
        hoverGlyph = g;
    }

    @Override
    protected boolean contentKeyDown(int keycode) {
        if (inputReady()) advance(Ui.CX, LOGO_Y);
        return true;
    }

    private void lean(float fx, float fy) {
        for (int i = 0; i < 3; i += 2) {
            float dx = fx - glyphX[i], dy = fy - LOGO_Y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < 1f) continue;
            float k = LEAN * Math.min(1f, dist / 420f) / dist;
            leanX[i].setTarget(dx * k);
            leanY[i].setTarget(dy * k);
        }
    }

    private void releaseLean() {
        for (int i = 0; i < 3; i++) {
            leanX[i].setTarget(0f);
            leanY[i].setTarget(0f);
        }
        game.backdrop().setShaftTilt(0f);
    }

    private void spawnBubble(float x, float y) {
        int i = bubbleNext;
        bubbleNext = (bubbleNext + 1) % BUBBLES;
        bx[i] = x + MathUtils.random(-14f, 14f);
        by[i] = y + MathUtils.random(-8f, 8f);
        br[i] = MathUtils.random(6f, 15f);
        bvy[i] = MathUtils.random(80f, 170f);
        bage[i] = 0f;
        blife[i] = MathUtils.random(1.1f, 1.9f);
    }

    private void pingGlyph(int i) {
        Color c = i == 0 ? Theme.GROUND_SIG : Theme.SKY_SIG;
        game.backdrop().sonar().ping(glyphX[i], LOGO_Y, 560f, 0.9f, c, 0.4f, 2);
        glyphGlitch[i].trigger(GlitchText.Level.G2, clock);
        leanX[i].impulse(i == 0 ? -340f : 340f);
        leanY[i].impulse(220f);
        glyphFlash[i] = 1f;
        Haptics.light();
    }

    /** Fast-forwards the intro, or descends once it is (nearly) complete. */
    private void advance(float x, float y) {
        if (introT < PROMPT_AT) {
            if (ffFrom < 0f) {
                ffFrom = introT;
                ffT = 0f;
            }
            return;
        }
        descend(x, y);
    }

    /** Routing rule (ARCHITECTURE §2): first run → FirstRun (IRIS), else the descent to the map. */
    private void descend(float x, float y) {
        if (exitT >= 0f || isModalOpen()) return;
        game.backdrop().touch(x, y);
        for (int i = 0; i < 3; i++) glyphGlitch[i].trigger(GlitchText.Level.G2, clock, 0.25f);
        Haptics.light();
        if (game.profiles().isFirstRun()) {
            game.transitionTo(game.firstRun(), TransitionLayer.Style.IRIS, x, y);
        } else {
            exitT = 0f;
            widgets.cancel();
            // §3.12: the water starts moving toward the depth the map camera arrives at; the
            // backdrop eases (τ 0.6 s) and the map keeps feeding it, so the descent is continuous
            game.backdrop().setDepth(game.map().arrivalDepth());
        }
    }

    private void openSwitcher() {
        if (exitT >= 0f) return;
        switcher.setAnchor(chip.x(), chip.y() + chip.height() + 16f);
        openModal(switcher);
    }
}
