package org.kelvinizer.gameplay;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.fx.HitFx;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

import java.util.List;

/**
 * 教学关卡：在普通游玩之上按拍子显示字幕（英文或中文），并播放各种示范动画。
 * Tutorial gameplay (ARCHITECTURE §10, DESIGN §10.1 step 5 and §10.2): a
 * {@link GameplayScreen} whose result is flagged as the tutorial (nothing recorded but the first-time
 * EXP and {@code tutorialSeen}). Reached through {@code Main.startTutorial()}.
 *
 * <p>On top of the normal run it draws the timed captions of {@link IntroductionGuidelines} (English, or
 * Chinese with Language = 中文; they follow the song beat, so they freeze with a pause) and the demos:
 * the HUD tour (labels beside the four corners, the pause label following Settings → PAUSE INPUT:
 * HOLD / DOUBLE-TAP / TAP HERE TO PAUSE, 按住 / 双击 / 点击这里暂停; the pause control's double-tap hint,
 * only in double-tap mode; a ghost combo),
 * demo hits beside the judgement lines and a demo ECHO with its EARLY / LATE tag, ghost notes falling to
 * the ground line and rising to the sky line, the zone hint pulse and forced stray dots, a demo
 * mine touched by a ghost finger (red LOST, no combo change) and the closed lanes' pulsing outlines in the
 * LANE MINE lesson. The HUD tour labels sit beside the corner elements {@link GameplayHud#element} names
 * (below a top corner, above a bottom one). A SKIP pill (hold 0.6 s) sits centred under the bottom judgement line
 * (a touch there goes to the notes first and to SKIP when it judges nothing): after 10 s on
 * the first run, always on replays.
 */
public class IntroductionScreen extends GameplayScreen {
    /** Hold time of the SKIP pill (s). */
    public static final float SKIP_HOLD_SEC = 0.6f;
    /** Delay before SKIP appears on the first tutorial run (s). */
    public static final float SKIP_DELAY_SEC = 10f;
    private static final float SKIP_W = 264f;
    private static final float SKIP_H = 56f;
    /** Reference y of the SKIP pill's centre: the strip under the bottom judgement line (ref 120; Kelvin 10-12 22:04). */
    private static final float SKIP_CY = 60f;
    private static final String[] SKIP_LABEL = GameplayHud.letters("HOLD TO SKIP");

    /**
     * Distance from a corner element's facing edge to its tour label's centre (the pause label's facing edge is
     * the lower of the icon's and its touch box's, so the label never sits on the box).
     */
    private static final float TOUR_GAP = 36f;
    /** A tour label's centre stays at least this far from a judgement line (clear of its glow) ... */
    private static final float LINE_CLEAR = 48f;
    /** ... else it sits on the far side of the line, this far from it. */
    private static final float LINE_SIDE = 44f;

    /** Seconds between two double-tap hints of the pause control (each hint pulses twice). */
    private static final float PAUSE_DEMO_SEC = 1.6f;
    /** Seconds between two rounds of demo hits beside the judgement lines. */
    private static final float GRADE_DEMO_SEC = 1.5f;
    /** Demo hits sit this far left of each judgement word (rings up to 160 × .45 = 72 stay clear of it). */
    private static final float GRADE_FX_GAP = 96f;
    /** Seconds between two demo ECHO words (EARLY, then LATE, alternating). */
    private static final float EARLY_LATE_SEC = 1.5f;
    /** Timing error of the demo ECHO words: inside the ECHO band, so the default EARLY/LATE setting shows it. */
    private static final float EARLY_LATE_MS = 100f;
    /** Seconds between two ghost touches of the demo mine ... */
    private static final float MINE_TOUCH_SEC = 1.6f;
    /** ... from this beat (the MINES heading is fully visible). */
    private static final float MINE_TOUCH_FROM = IntroductionGuidelines.MINE_DEMO_START + 1f;
    /** Lane width the demo heads and the demo mine are drawn for (the chart's 240-unit lanes). */
    private static final float DEMO_LANE_W = 240f;
    /** The demo mine sits left of the MINES heading, this far from its first letter (EN: centre x ≈ 1520, lane +3). */
    private static final float DEMO_MINE_GAP = 32f;
    /** The two demo heads sit beside the GOLD PEARLS heading, this far from its ends (EN: centres ≈ 598 / 1322). */
    private static final float SYNC_DEMO_GAP = 56f;
    /**
     * Ghost notes of the two-lines demo: a ground DROP falls in lane −4, a sky DROP rises in lane +4 (the outer
     * lanes, clear of the centred body line between them).
     */
    private static final int FALL_LANE = -4;
    private static final int RISE_LANE = 4;
    private static final float FALL_RISE_ALPHA = 0.45f;
    /** Approach distance of the ghost notes (the chart's default approach: 2 beats × 486 units). */
    private static final float FALL_RISE_UNITS = 972f;
    /** Stroke of the closed lane's outline. */
    private static final float HINT_STROKE = 3f;

    private IntroductionGuidelines guidelines = new IntroductionGuidelines(false);
    private final float[] box = new float[4];
    private boolean seenBefore;
    private int skipPointer = -1;
    private float skipHold;
    private float skipShown;
    private float lastGradeDemo = -10f;
    private float lastPauseDemo = -10f;
    private float lastEarlyLate = -10f;
    private boolean lateNext;
    private float lastMineTouch = -10f;
    /** Chart index of the first stacked TIDE at {@link IntroductionGuidelines#STACK_BEAT}, or −1 (none in this chart). */
    private int stackNote = -1;
    /** Chart indices of the two lane mines the hint outlines (LANE_MINE and LANE_MINE2), or −1. */
    private int laneMineNote = -1;
    private int laneMine2Note = -1;
    /** The GOLD PEARLS and MINES headings the demo heads and the demo mine sit beside (null if absent). */
    private GuidingText syncHeading;
    private GuidingText mineHeading;

    /** Creates the screen once (Main caches it). */
    public IntroductionScreen(Main game) {
        super(game);
    }

    @Override
    protected boolean tutorial() {
        return true;
    }

    /**
     * {@inheritDoc} Also picks the caption language, finds the notes the demos follow, and builds every
     * caption page and glyph (and the SKIP / ghost COMBO label page) now, so no caption generates a font
     * mid-song.
     */
    @Override
    public void begin(org.kelvinizer.elements.Chart chart, org.kelvinizer.library.SongMeta song,
                      org.kelvinizer.library.Difficulty diff, boolean autoplay) {
        super.begin(chart, song, diff, autoplay);
        boolean zh = settings != null && settings.zh();
        int pauseMode = settings != null ? settings.pauseMode : Settings.PAUSE_DOUBLE_TAP;
        // the tour's pause label is worded for the player's PAUSE INPUT mode
        if (guidelines.zh() != zh || guidelines.pauseMode() != pauseMode) {
            guidelines = new IntroductionGuidelines(zh, pauseMode);
        }
        stackNote = -1;
        laneMineNote = -1;
        laneMine2Note = -1;
        Note[] all = chart.notes();
        for (int i = 0; i < all.length; i++) {
            Note n = all[i];
            if (stackNote < 0 && n.line() == Line.LOWER && n.type() == NoteType.UNIVERSAL
                && n.judgement() == IntroductionGuidelines.STACK_BEAT) {
                stackNote = i;
            }
            if (n.type() != NoteType.MINE) continue;
            if (laneMineNote < 0 && n.laneId() == IntroductionGuidelines.LANE_MINE_LANE
                && n.judgement() == IntroductionGuidelines.LANE_MINE_BEAT) {
                laneMineNote = i;
            }
            if (laneMine2Note < 0 && n.laneId() == IntroductionGuidelines.LANE_MINE2_LANE
                && n.judgement() == IntroductionGuidelines.LANE_MINE2_BEAT) {
                laneMine2Note = i;
            }
        }
        Fonts fonts = game.fonts();
        List<GuidingText> list = guidelines.getGuidelines();
        syncHeading = null;
        mineHeading = null;
        for (int i = 0; i < list.size(); i++) {
            GuidingText g = list.get(i);
            g.prewarm(fonts);
            if (g.style() != GuidingText.Style.HEADING) continue;
            if (g.startBeat() == IntroductionGuidelines.SYNC_DEMO_START) syncHeading = g;
            if (g.startBeat() == IntroductionGuidelines.MINE_DEMO_START) mineHeading = g;
        }
        fonts.prewarm(Fonts.Role.UI, GuidingText.uiRaster(), Fonts.GlyphSet.FULL);
    }

    @Override
    protected void onShow() {
        super.onShow();
        Profile p = game.profiles() != null ? game.profiles().active() : null;
        seenBefore = p != null && p.tutorialSeen;
        skipPointer = -1;
        skipHold = 0f;
        skipShown = 0f;
        lastGradeDemo = -10f;
        lastPauseDemo = -10f;
        lastEarlyLate = -10f;
        lateNext = false;
        lastMineTouch = -10f;
    }

    // ------------------------------------------------------------------ hooks

    /**
     * Zone hint: through the zone instructions and the DROP, TIDE, STREAM and TIDE STREAM lessons until the
     * break, and again for the chorus TIDEs (with the TIDE + DROP joints).
     */
    @Override
    protected float zoneHintPulse() {
        float beat = engine.visualBeat();
        float edge = Math.max(
            window(beat, IntroductionGuidelines.ZONES_START, IntroductionGuidelines.ZONES_END, 1f),
            Math.max(window(beat, IntroductionGuidelines.TIDE_ZONE_START, IntroductionGuidelines.TIDE_ZONE_END, 1f),
                window(beat, IntroductionGuidelines.TIDE_ZONE2_START, IntroductionGuidelines.TIDE_ZONE2_END, 1f)));
        return edge * (0.5f - 0.5f * MathUtils.cos(runTime * MathUtils.PI2));
    }

    /** 0 outside [in, out + ramp], 1 from in + ramp to out, linear edges of {@code ramp} beats. */
    private static float window(float beat, float in, float out, float ramp) {
        if (beat < in || beat > out + ramp) return 0f;
        return Math.min(MathUtils.clamp((beat - in) / ramp, 0f, 1f),
            MathUtils.clamp((out + ramp - beat) / ramp, 0f, 1f));
    }

    @Override
    protected boolean forceStrayDots() {
        float beat = engine.visualBeat();
        return beat >= IntroductionGuidelines.ZONES_START && beat <= IntroductionGuidelines.ZONES_END;
    }

    private boolean skipVisible() {
        return !resultShown() && (seenBefore || time >= SKIP_DELAY_SEC);
    }

    private boolean resultShown() {
        return engine.isFinished();
    }

    /** Centred under the bottom judgement line, inside its zone, so a touch still plays notes first. */
    private float skipX() {
        return Ui.CX - SKIP_W * 0.5f;
    }

    private float skipY() {
        return Math.max(Field.y(SKIP_CY), Ui.safeBottom() + SKIP_H * 0.5f) - SKIP_H * 0.5f;
    }

    private boolean inSkip(float x, float y) {
        float hw = Math.max(SKIP_W, Ui.touchTarget()) * 0.5f;
        float hh = Math.max(SKIP_H, Ui.touchTarget()) * 0.5f;
        return Math.abs(x - (skipX() + SKIP_W * 0.5f)) <= hw && Math.abs(y - (skipY() + SKIP_H * 0.5f)) <= hh;
    }

    @Override
    protected boolean controlAt(float x, float y) {
        return super.controlAt(x, y) || skipVisible() && inSkip(x, y);
    }

    /** Takes a touch that judged nothing (GameplayScreen routes STRAY, EMPTY and IGNORED touches here). */
    @Override
    protected boolean extraTouchDown(float x, float y, int pointer) {
        if (skipPointer < 0 && skipVisible() && inSkip(x, y)) {
            skipPointer = pointer;
            skipHold = 0f;
            return true;
        }
        return false;
    }

    @Override
    protected void extraTouchDragged(float x, float y, int pointer) {
        if (pointer == skipPointer && !inSkip(x, y)) skipPointer = -1;
    }

    @Override
    protected void extraTouchUp(float x, float y, int pointer) {
        if (pointer == skipPointer) skipPointer = -1;
    }

    @Override
    protected void extraTouchCancel(int pointer) {
        if (pointer == skipPointer) skipPointer = -1;
    }

    @Override
    protected void updateExtras(float delta) {
        if (skipVisible()) skipShown = Math.min(1f, skipShown + delta / 0.3f);
        if (skipPointer >= 0 && !isMenuOpen()) {
            skipHold += delta;
            if (skipHold >= SKIP_HOLD_SEC) {
                skipPointer = -1;
                finishNow();
            }
        } else {
            skipHold = Math.max(0f, skipHold - delta * 2f);
        }
        if (!engine.isRunning()) return;
        float beat = engine.visualBeat();
        // pause control: the double-tap hint (it never pauses), only when PAUSE INPUT is the double tap
        if (guidelines.pauseMode() == Settings.PAUSE_DOUBLE_TAP
            && beat >= IntroductionGuidelines.PAUSE_DEMO_START && beat <= IntroductionGuidelines.PAUSE_DEMO_END
            && runTime - lastPauseDemo >= PAUSE_DEMO_SEC) {
            lastPauseDemo = runTime;
            hud().demoPauseDoubleTap(runTime);
        }
        // grade demos: a hit effect beside each visible judgement word (not beside the EARLY / LATE line)
        if (beat >= IntroductionGuidelines.GRADES_START && beat <= IntroductionGuidelines.GRADES_END
            && runTime - lastGradeDemo >= GRADE_DEMO_SEC) {
            lastGradeDemo = runTime;
            HitFx fx = game.hitFx();
            List<GuidingText> list = guidelines.getGuidelines();
            for (int i = 0; i < list.size(); i++) {
                GuidingText g = list.get(i);
                if (g.grade() == null || g.alphaAt(beat) < 0.9f) continue;
                float x = g.gradeLeft(game.fonts(), anchorX(g)) - GRADE_FX_GAP;
                float y = anchorY(g);
                if (g.grade() == Judgement.LOST) fx.lost(x, y, 0.8f);
                else fx.tap(x, y, g.grade(), 0.45f, false); // beside a caption, not a line: ground form
            }
        }
        // EARLY / LATE: the centre word shows ECHO with its tag, early and late in turn
        if (beat >= IntroductionGuidelines.EARLY_LATE_START && beat <= IntroductionGuidelines.GRADES_END
            && runTime - lastEarlyLate >= EARLY_LATE_SEC) {
            lastEarlyLate = runTime;
            hud().demoJudgement(Judgement.ECHO, lateNext ? EARLY_LATE_MS : -EARLY_LATE_MS, runTime);
            lateNext = !lateNext;
        }
        // MINES: a ghost finger touches the demo mine (red LOST and shock; combo and score untouched)
        if (beat >= MINE_TOUCH_FROM && beat <= IntroductionGuidelines.MINE_DEMO_END
            && runTime - lastMineTouch >= MINE_TOUCH_SEC && mineHeading != null) {
            lastMineTouch = runTime;
            demoMineTouch(demoMineX(), anchorY(mineHeading));
        }
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Demos, captions and the SKIP pill. Shapes and plates go straight to the batch; every text is
     * recorded and replayed afterwards, one draw call per font page (DESIGN §14).
     */
    @Override
    protected void drawExtras(float delta) {
        float beat = engine.visualBeat();
        Fonts fonts = game.fonts();
        Draw draw = game.draw();
        DeferredBatch text = textBatch();
        text.start(batch);
        drawGhostCombo(text, beat);
        drawFallRise(beat);
        drawSyncHeads(beat);
        drawDemoMine(beat);
        drawLaneMineHint(draw, beat);
        List<GuidingText> list = guidelines.getGuidelines();
        for (int i = 0; i < list.size(); i++) {
            GuidingText g = list.get(i);
            if (g.alphaAt(beat) <= 0f) continue;
            g.draw(text, fonts, draw, beat, anchorX(g), anchorY(g));
        }
        drawSkip(text);
        text.replay();
    }

    /**
     * Resolves the caption's x for its anchor: corner labels align with the edge of what they name (left
     * corners with its left edge, right corners with its right edge), always inside the safe area. A world
     * or line-anchored x is a reference x stretched with the field ({@link Field#x}), a TIDE_STACK offset from
     * the window edge is stretched by kx; safe-edge offsets are not (the corners are not stretched).
     */
    private float anchorX(GuidingText g) {
        switch (g.anchor()) {
            case SAFE_LEFT:
                return Ui.safeLeft() + g.x();
            case SAFE_RIGHT:
                return Ui.safeRight() + g.x();
            case HUD_SCORE:
            case HUD_CHIP: {
                if (!tourBox(g.anchor(), box)) return g.x();
                float half = g.width(game.fonts()) * 0.5f;
                return inSafe(box[0] + half, half);
            }
            case HUD_PAUSE:
            case HUD_TIME: {
                if (!tourBox(g.anchor(), box)) return g.x();
                float half = g.width(game.fonts()) * 0.5f;
                return inSafe(box[2] - half, half);
            }
            case TIDE_STACK:
                return engine.layout().windowRight() + g.x() * Field.kx();
            default:
                return Field.x(g.x());
        }
    }

    /**
     * Resolves the caption's y for its anchor. A top-corner label sits {@link #TOUR_GAP} below its
     * element, a bottom-corner label {@link #TOUR_GAP} above it; when that would put it on or past a
     * judgement line (the corners are inside the zones at 16:9) it moves to the line's far side instead. A world y
     * is stretched with the field ({@link Field#y}), an offset from a line or the TIDE bar by ky ({@link Field#dist}).
     */
    private float anchorY(GuidingText g) {
        float sky = engine.lineY(Line.UPPER);
        float ground = engine.lineY(Line.LOWER);
        switch (g.anchor()) {
            case GROUND:
                return ground + Field.dist(g.y());
            case SKY:
                return sky + Field.dist(g.y());
            case HUD_SCORE:
            case HUD_PAUSE: {
                if (!tourBox(g.anchor(), box)) return g.y();
                float y = box[1] - TOUR_GAP;
                return y >= sky + LINE_CLEAR ? y : sky - LINE_SIDE;
            }
            case HUD_CHIP:
            case HUD_TIME: {
                if (!tourBox(g.anchor(), box)) return g.y();
                float y = box[3] + TOUR_GAP;
                return y <= ground - LINE_CLEAR ? y : ground + LINE_SIDE;
            }
            case TIDE_STACK: {
                float pos = stackNote >= 0 ? Math.max(0f, NoteRenderer.position(chart.note(stackNote), beatNow())) : 0f;
                // the design's offset clears the T4 wave crest (NoteShapes.T4_REACH) at note size 1; larger notes
                // raise the crest, and the badge rises with it
                float u = noteDemoHeight() / NoteRenderer.NOTE_H;
                return ground + Field.dist(pos + g.y()) + Math.max(0f, u - 1f) * NoteShapes.T4_REACH;
            }
            default:
                return Field.y(g.y());
        }
    }

    /**
     * Writes the bounds {x0, y0, x1, y1} a tour label is placed from into {@code out}: the corner element
     * {@link GameplayHud#element} names, as laid out this frame (HUD_SCORE score + accuracy, HUD_PAUSE the icon,
     * HUD_CHIP chip + title, HUD_TIME the time). The pause icon's bottom drops to its touch box's bottom, so the label
     * stays off the box.
     *
     * @return false for any other anchor
     */
    private boolean tourBox(GuidingText.Anchor a, float[] out) {
        int el = GameplayHud.element(a);
        if (el < 0) return false;
        GameplayHud h = hud();
        out[0] = h.left(el);
        out[1] = h.bottom(el);
        out[2] = h.right(el);
        out[3] = h.top(el);
        if (el == GameplayHud.EL_PAUSE) out[1] = Math.min(out[1], h.bottom(GameplayHud.EL_PAUSE_BOX));
        return true;
    }

    /** @return centre x clamped so a label {@code 2 × half} wide stays inside the safe area. */
    private static float inSafe(float x, float half) {
        return Math.max(Ui.safeLeft() + half, Math.min(Ui.safeRight() - half, x));
    }

    private float beatNow() {
        return engine.visualBeat();
    }

    /** Ghost combo "25 COMBO" while COMBO · CENTRE shows and the real combo is under 3. */
    private void drawGhostCombo(com.badlogic.gdx.graphics.g2d.Batch text, float beat) {
        if (beat < IntroductionGuidelines.COMBO_START || beat > IntroductionGuidelines.TOUR_END + 1f) return;
        float fadeOut = MathUtils.clamp(IntroductionGuidelines.TOUR_END + 1f - beat, 0f, 1f);
        float ca = MathUtils.clamp(beat - IntroductionGuidelines.COMBO_START, 0f, 1f) * fadeOut;
        if (ca > 0f && engine.scores().combo() < 3) {
            Fonts f = game.fonts();
            f.drawDigits(text, Fonts.Role.NUMBER, 96f, 25, 1, true, Ui.CX, 540f, Align.center, Theme.INK1, null,
                0.30f * ca);
            hud().label(text, GameplayHud.COMBO, 18f, GuidingText.uiRaster(), Ui.CX, 504f, Align.center, Theme.INK1,
                0.40f * ca);
        }
    }

    /**
     * Two-lines demo: a ghost ground DROP falls to the ground line in lane −4 and a ghost sky DROP rises
     * to the sky line in lane +4, at the chart's approach speed, every 2 beats.
     */
    private void drawFallRise(float beat) {
        float a = window(beat, IntroductionGuidelines.FALL_RISE_START, IntroductionGuidelines.FALL_RISE_END, 0.5f);
        if (a <= 0f) return;
        LaneLayout lay = engine.layout();
        int gi = lay.indexOf(FALL_LANE);
        int si = lay.indexOf(RISE_LANE);
        if (gi < 0 || si < 0) return;
        float p = ((beat - IntroductionGuidelines.FALL_RISE_START) % 2f) / 2f; // 0..1 over one approach
        float alpha = FALL_RISE_ALPHA * a * Math.min(1f, p / 0.1f);
        float dist = Field.dist((1f - p) * FALL_RISE_UNITS);
        drawNoteDemo(lay.centre(gi), engine.lineY(Line.LOWER) + dist, lay.width(gi), false, false, alpha);
        drawNoteDemo(lay.centre(si), engine.lineY(Line.UPPER) - dist, lay.width(si), true, false, alpha);
    }

    /**
     * Sync lesson: two ground DROP heads with the gold sync pearls, one each side of the GOLD PEARLS heading
     * (the caption keeps its words until the tutorial text is revised; {@link #SYNC_DEMO_GAP} from its ends,
     * centred on its line), with the heading's fades.
     */
    private void drawSyncHeads(float beat) {
        float a = window(beat, IntroductionGuidelines.SYNC_DEMO_START, IntroductionGuidelines.SYNC_DEMO_END, 0.5f);
        if (a <= 0f || syncHeading == null) return;
        float off = syncHeading.width(game.fonts()) * 0.5f + SYNC_DEMO_GAP + noteDemoWidth(DEMO_LANE_W) * 0.5f;
        float y = anchorY(syncHeading) - noteDemoHeight() * 0.5f; // leading edge, so the head is centred on the heading
        float hx = anchorX(syncHeading);
        drawNoteDemo(hx - off, y, DEMO_LANE_W, false, true, a);
        drawNoteDemo(hx + off, y, DEMO_LANE_W, false, true, a);
    }

    /** Centre x of the demo mine: {@link #DEMO_MINE_GAP} left of the MINES heading. */
    private float demoMineX() {
        return anchorX(mineHeading) - mineHeading.width(game.fonts()) * 0.5f - DEMO_MINE_GAP
            - DEMO_LANE_W * GameEngine.MINE_BOX * 0.5f;
    }

    /** MINE lesson: the demo mine beside the MINES heading (the ghost touches come from updateExtras). */
    private void drawDemoMine(float beat) {
        float a = window(beat, IntroductionGuidelines.MINE_DEMO_START, IntroductionGuidelines.MINE_DEMO_END, 0.5f);
        if (a <= 0f || mineHeading == null) return;
        drawMineDemo(demoMineX(), anchorY(mineHeading), DEMO_LANE_W, a);
    }

    /**
     * LANE MINE lesson: a {@link Theme#MINE} outline pulsing at 2 Hz (α .25–.55) around each closed lane over
     * its line's zone (where a touch meets the lane mine; the other line's zone stays open), from when the lane
     * mine appears until its block ends: the ground one, then the sky one.
     */
    private void drawLaneMineHint(Draw d, float beat) {
        if (window(beat, IntroductionGuidelines.LANE_MINE_HINT_START, IntroductionGuidelines.LANE_MINE_HINT_END,
            0.5f) <= 0f) return;
        float pulse = 0.40f + 0.15f * MathUtils.sin(runTime * MathUtils.PI2 * 2f);
        laneMineOutline(d, beat, laneMineNote, pulse);
        laneMineOutline(d, beat, laneMine2Note, pulse);
    }

    /** The pulsing outline of {@link #drawLaneMineHint} for the lane mine at chart index {@code i} (none for −1). */
    private void laneMineOutline(Draw d, float beat, int i, float pulse) {
        if (i < 0) return;
        Note n = chart.note(i);
        float a = window(beat, Math.max(IntroductionGuidelines.LANE_MINE_HINT_START, n.visibleFrom()),
            Math.min(IntroductionGuidelines.LANE_MINE_HINT_END, n.endBeat()), 0.5f);
        if (a <= 0f) return;
        LaneLayout lay = engine.layout();
        int li = n.laneIndex();
        if (li < 0 || li >= lay.count() || !lay.active(li)) return;
        float lineY = engine.lineY(n.line());
        float zone = Field.zoneHalf();
        float y0 = lineY - zone;
        float y1 = lineY + zone;
        float alpha = a * pulse;
        float x0 = lay.left(li);
        float w = lay.width(li);
        float s = HINT_STROKE;
        d.rect(x0, y0, s, y1 - y0, Theme.MINE, alpha);
        d.rect(x0 + w - s, y0, s, y1 - y0, Theme.MINE, alpha);
        d.rect(x0 + s, y0, w - 2f * s, s, Theme.MINE, alpha);
        d.rect(x0 + s, y1 - s, w - 2f * s, s, Theme.MINE, alpha);
    }

    /** SKIP pill under the bottom judgement line (hold 0.6 s; the fill shows the hold). */
    private void drawSkip(com.badlogic.gdx.graphics.g2d.Batch text) {
        if (skipShown <= 0f || isMenuOpen()) return;
        Draw d = game.draw();
        float a = skipShown;
        float x = skipX();
        float y = skipY();
        d.pill(x, y, SKIP_W, SKIP_H, Theme.PANEL, 0.9f * a);
        float fill = skipHold / SKIP_HOLD_SEC;
        if (fill > 0f) d.pill(x, y, Math.max(SKIP_H, SKIP_W * Math.min(1f, fill)), SKIP_H, Theme.INK1, 0.18f * a);
        d.roundRectStroke(x, y, SKIP_W, SKIP_H, SKIP_H * 0.5f, 2f, Theme.STROKE, a);
        // UI_LABEL look from the captions' UI page (no page of its own)
        hud().label(text, SKIP_LABEL, 20f, GuidingText.uiRaster(), x + SKIP_W * 0.5f, y + SKIP_H * 0.5f,
            Align.center | Fonts.VCENTER, Theme.INK1, 0.85f * a);
    }
}
