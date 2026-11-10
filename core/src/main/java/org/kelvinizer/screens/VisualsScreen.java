package org.kelvinizer.screens;

import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.profile.DeviceSettings;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.screens.shell.ChoiceRow;
import org.kelvinizer.screens.shell.LivePreview;
import org.kelvinizer.screens.shell.SettingsPage;
import org.kelvinizer.screens.shell.ShellScreen;
import org.kelvinizer.screens.shell.SliderRow;
import org.kelvinizer.screens.shell.ToggleRow;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Panel;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 「游戏界面」画面（19:52 前叫「游玩界面视觉参数」）：像谱面延时校准一样的独立画面，从设置 → 视觉里点「调整…」打开，
 * 返回键回到设置。中间是大的实时预览（和这块屏幕一样的比例，用真正的游戏画面自动演示，不能点），下面一条「判定字样」
 * 按当前的故障强度示范回响、共鸣、迷失三个字；左列「画面」放背景亮度、故障效果强度、减少闪烁和粒子质量，
 * 右列「音符与判定」放按键缩放、多押辅助、判定位置、FC/AR 指示和判定区提示，两列都排到底，最下面一行小字说改动马上生效。
 * 每项改动立即生效，离开时保存。
 *
 * <p>GAMEPLAY VISUALS (Kelvin 2026-10-18 15:17: 「做成类似谱面延时校准那样的独立界面」; 15:53 option 1 with the lower
 * half filled; 16:17 notes on it: ECHO and RESONANCE swapped, the strip's caption centred, no TAP TO TEST in the
 * preview, a line under GLITCH INTENSITY and a new FC / AR INDICATOR; 16:19 that switch on the right and no APPROACH
 * GLOW; 19:52 「游戏界面」 in Chinese, MULTI-PRESS ASSIST in from GAMEPLAY, SHOW TOUCHES out to it, and a preview that
 * only plays). Until then these settings were a page of the Settings list with a small preview beside it.
 *
 * <ul>
 *   <li><b>Centre</b>: the {@link LivePreview} (the screen's shape, at most the column; Kelvin 2026-10-18 19:52) above
 *       the JUDGEMENT WORDS strip (caption centred), where ECHO, RESONANCE and LOST (their faces and colours,
 *       RESONANCE in the middle) burst every 1.5 s in turn at the glitch intensity being set. A line under
 *       everything says changes apply at once; the preview takes no taps since 19:52.</li>
 *   <li><b>Columns</b>: two spread {@link SettingsPage}s of {@link org.kelvinizer.screens.shell.SettingRow#setStacked
 *       stacked} rows. SCREEN: BACKGROUND BRIGHTNESS (the game's, shown as {@code 1 − backgroundDim}), GLITCH
 *       INTENSITY (detents 0 / 35 / 60 / 100 %), REDUCE FLASHING and the device's PARTICLE QUALITY. NOTES &amp;
 *       JUDGEMENTS: NOTE SCALE, MULTI-PRESS ASSIST, JUDGEMENT POSITION, FC / AR INDICATOR (the game's score colour)
 *       and ZONE HINT (a 0–100 % slider since 2026-10-22, 50 % the old switch's ON); APPROACH GLOW has no row since
 *       16:19 and stays on. Each column is as wide
 *       as leaves the preview the screen's shape, within 380–500 × UI scale (460 at least in English, whose labels
 *       are longer); when the rows do not fit the height (large UI scale on a phone) they are laid out at a smaller
 *       scale instead of scrolling.</li>
 *   <li><b>Saving</b>: as in Settings, every change applies at once ({@link Main#applySettings()}) and leaving marks
 *       the diver and the device dirty and saves.</li>
 * </ul>
 * Nothing is allocated per frame (the preview's run writes a new chart each round).
 */
public class VisualsScreen extends ShellScreen {
    /** Title, also the CURTAIN label of Settings' ADJUST…. */
    public static final CharSequence TITLE = Lang.of("GAMEPLAY VISUALS", "游戏界面");

    private static final CharSequence HEAD_SCREEN = Lang.of("SCREEN", "画面"),
        HEAD_NOTES = Lang.of("NOTES & JUDGEMENTS", "音符与判定"),
        WORDS = Lang.of("JUDGEMENT WORDS", "判定字样"),
        FC_AR = Lang.of("FC / AR INDICATOR", "FC/AR指示"),
        FOOTNOTE = Lang.of("CHANGES APPLY AT ONCE", "改动马上生效"),
        AUTO_LOWERED = Lang.of("LOWERED AUTOMATICALLY FOR SPEED", "为了流畅已自动调低"),
        PARTICLE_DESC = Lang.of("HIT EFFECTS DURING A GAME", "下潜时的打击特效");
    /**
     * {@link Settings#syncHint} of each MULTI-PRESS ASSIST option in display order: OFF, ON, ENHANCED (Kelvin 04:34).
     */
    private static final int[] SYNC_ORDER = {
        Settings.SYNC_HINT_OFF, Settings.SYNC_HINT_HIGHLIGHT, Settings.SYNC_HINT_PEARLS};
    /** The strip's words, RESONANCE in the middle (Kelvin 2026-10-18 16:17). */
    private static final Judgement[] SAMPLES = {Judgement.ECHO, Judgement.RESONANCE, Judgement.LOST};
    /** Seconds between two bursts of one sample word. */
    private static final float PERIOD = 1.5f;
    /** Layout at uiScale 1: gap under the header, column header band, footnote band, column gap, strip. */
    private static final float TOP_GAP = 24f, HEAD_H = 44f, FOOT_H = 64f, GAP = 40f, STRIP_H = 170f, STRIP_GAP = 24f;
    /** Column width at uiScale 1: at least 380 (460 for the longer English labels), at most 500. */
    private static final float COL_MIN_ZH = 380f, COL_MIN_EN = 460f, COL_MAX = 500f, PREVIEW_MIN = 320f;
    /** Least space between the sample words and at the strip's ends. */
    private static final float WORD_GAP = 72f;
    /** Smallest share of the UI scale the columns shrink to before they give up fitting. */
    private static final float FIT_MIN = 0.7f;

    private final LivePreview preview;
    private final SettingsPage left, right;
    private final SettingsPage[] cols;
    private final ChoiceRow particleRow;
    private final GlitchState[] states = new GlitchState[SAMPLES.length];
    private final float[] nextBurst = new float[SAMPLES.length];
    private final float[] wordX = new float[SAMPLES.length];

    private float k = 1f, colW, colTop, leftX, rightX, centreX, centreW, contentBottom, footY;
    private float stripY, wordCap;

    /** Creates the screen once (Main caches it). */
    public VisualsScreen(final Main game) {
        super(game, TITLE, "Blue.png", 0.15f, 0.6f, false);
        preview = new LivePreview(game);
        for (int i = 0; i < SAMPLES.length; i++) {
            states[i] = game.glitch().obtainState();
            if (SAMPLES[i] == Judgement.RESONANCE) states[i].ambient(GlitchText.Level.G1);
        }
        left = new SettingsPage(game.draw()).setSpread(true);
        right = new SettingsPage(game.draw()).setSpread(true);
        cols = new SettingsPage[] {left, right};
        particleRow = buildScreen(left);
        buildNotes(right);
        for (SettingsPage p : cols) {
            for (int i = 0; i < p.size(); i++) p.get(i).setStacked(true);
        }
    }

    // ------------------------------------------------------------------ helpers
    private Settings s() {
        Profile p = game.profiles().active();
        return p != null ? p.settings : FALLBACK;
    }

    /** Detached defaults used only if no diver is loaded (never saved). */
    private static final Settings FALLBACK = new Settings();

    private DeviceSettings d() {
        return game.profiles().device();
    }

    /** A diver-scoped setting changed: mark the active diver dirty and apply. */
    private void diverChanged() {
        game.profiles().markDirty();
        game.applySettings();
    }

    // ------------------------------------------------------------------ rows
    /** The SCREEN column; returns the PARTICLE QUALITY row (its description follows the auto-drop). */
    private ChoiceRow buildScreen(SettingsPage p) {
        // BACKGROUND DIM shows as its opposite, BACKGROUND BRIGHTNESS (Kelvin 2026-10-18 14:29): 100 % is no dimming
        // and 10 % the old 90 % dim; Settings.backgroundDim keeps the stored value
        p.add(new SliderRow(game, Lang.of("BACKGROUND BRIGHTNESS", "背景亮度"),
            Lang.of("THE BACKGROUND DURING A GAME", "下潜时背景的亮度"), false, 0.1f, 1f, 0.01f, 100f, true,
            false, null, new SliderRow.Value() {
                @Override
                public float get() {
                    return 1f - s().backgroundDim;
                }

                @Override
                public void set(float v, boolean finished) {
                    s().backgroundDim = Math.max(0f, Math.min(0.9f, 1f - v));
                    if (finished) diverChanged();
                }
            }));
        // the line under it since Kelvin 2026-10-18 16:17
        p.add(new SliderRow(game, Lang.of("GLITCH INTENSITY", "故障效果强度"),
            Lang.of("GLITCH ON JUDGEMENT WORDS AND TITLES", "判定字和标题的故障特效"), false, 0f, 1f, 0.01f, 100f, true,
            false, null, new SliderRow.Value() {
                @Override
                public float get() {
                    return s().glitchIntensity;
                }

                @Override
                public void set(float v, boolean finished) {
                    s().glitchIntensity = v;
                    GlitchText.setIntensity(v);
                    if (finished) diverChanged();
                }
            }).setDetents(0f, 0.35f, 0.6f, 1f));
        p.add(new ToggleRow(game, Lang.of("REDUCE FLASHING", "减少闪烁"),
            Lang.of("NO WHITE FLASHES · SOFTER GLITCH", "没有白闪 · 故障效果更柔和"), false, new ToggleRow.Value() {
                @Override
                public boolean get() {
                    return s().reduceFlashing;
                }

                @Override
                public void set(boolean on) {
                    s().reduceFlashing = on;
                    diverChanged();
                }
            }));
        return p.add(new ChoiceRow(game, Lang.of("PARTICLE QUALITY", "粒子质量"), PARTICLE_DESC, true,
            Lang.list("OFF", "关", "LOW", "低", "HIGH", "高"), new ChoiceRow.Value() {
                @Override
                public int get() {
                    return d().particleQuality;
                }

                @Override
                public void set(int index) {
                    d().particleQuality = index;
                    d().particlesAutoDropped = false;
                    game.profiles().markDeviceDirty();
                    game.applySettings();
                    refreshDynamic();
                }
            }));
    }

    /** The NOTES & JUDGEMENTS column. */
    private void buildNotes(SettingsPage p) {
        p.add(new SliderRow(game, Lang.of("NOTE SCALE", "按键缩放"), Lang.of("NOTE SIZE DURING A GAME", "下潜时音符的大小"),
            false, 0.8f, 1.3f, 0.05f, 100f, true, false, null, new SliderRow.Value() {
                @Override
                public float get() {
                    return s().noteSizeScale;
                }

                @Override
                public void set(float v, boolean finished) {
                    s().noteSizeScale = v;
                    if (finished) diverChanged();
                }
            }));
        // from GAMEPLAY (Kelvin 2026-10-18 19:52), so the preview's doubles show it; OFF / ON (the glow) / ENHANCED
        // (glow and pearls) since 04:34, mapped onto Settings.syncHint so saved values keep their meaning
        p.add(new ChoiceRow(game, Lang.of("MULTI-PRESS ASSIST", "多押辅助"), Lang.of("NOTES THAT SHARE A BEAT", "同一拍上的音符"),
            false, Lang.list("OFF", "关", "ON", "开", "ENHANCED", "增强"), new ChoiceRow.Value() {
                @Override
                public int get() {
                    for (int i = 0; i < SYNC_ORDER.length; i++) {
                        if (SYNC_ORDER[i] == s().syncHint) return i;
                    }
                    return 1;
                }

                @Override
                public void set(int index) {
                    s().syncHint = SYNC_ORDER[index];
                    diverChanged();
                }
            }));
        p.add(new ChoiceRow(game, Lang.of("JUDGEMENT POSITION", "判定位置"),
            Lang.of("WHERE TO DISPLAY THE JUDGEMENT WORD", "共鸣 / 回响 / 迷失显示在哪里"), false,
            Lang.list("CENTRE", "中央", "AT LANE", "轨道上", "OFF", "关"), new ChoiceRow.Value() {
                @Override
                public int get() {
                    return s().judgementDisplay;
                }

                @Override
                public void set(int index) {
                    s().judgementDisplay = index;
                    diverChanged();
                }
            }));
        // FC / AR INDICATOR (Kelvin 2026-10-18 16:17) where APPROACH GLOW was (16:19: 「把接近光晕去掉，感觉效果不明显」)
        p.add(new ToggleRow(game, FC_AR, Lang.of("SCORE IS BLUE ON AR, PURPLE ON FC", "分数变色：AR 淡蓝 · FC 紫 · 否则白色"),
            false, new ToggleRow.Value() {
                @Override
                public boolean get() {
                    return s().fcArIndicator;
                }

                @Override
                public void set(boolean on) {
                    s().fcArIndicator = on;
                    diverChanged();
                }
            }));
        // a slider instead of the switch (Kelvin 2026-10-22 17:17: 「拖到0就是不显示。现在的开状态默认是50%」)
        p.add(new SliderRow(game, Lang.of("ZONE HINT", "判定区提示"),
            Lang.of("TINT ON EACH TOUCH ZONE · 0 % HIDES IT", "给每个触控区着色 · 拖到 0 不显示"), false, 0f, 1f, 0.01f,
            100f, true, false, null, new SliderRow.Value() {
                @Override
                public float get() {
                    return s().zoneHintOpacity;
                }

                @Override
                public void set(float v, boolean finished) {
                    s().zoneHintOpacity = v;
                    if (finished) diverChanged();
                }
            }).setDetents(0f, Settings.ZONE_HINT_DEFAULT, 1f));
        // SHOW TOUCHES moved to GAMEPLAY (Kelvin 2026-10-18 19:52): the preview plays itself, so it has nothing to show
    }

    /** The PARTICLE QUALITY description: gold while HitFx's auto-drop has lowered it. */
    private void refreshDynamic() {
        if (d().particlesAutoDropped) particleRow.setDescription(AUTO_LOWERED, Theme.GOLD);
        else particleRow.setDescription(PARTICLE_DESC, null);
    }

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void enterScreen() {
        refreshDynamic();
        for (SettingsPage p : cols) p.show();
        preview.reset();
        for (int i = 0; i < SAMPLES.length; i++) nextBurst[i] = clock + 0.4f + i * PERIOD / SAMPLES.length;
    }

    /** As Settings: cancels open drags (a cancelled slider commits its value), applies and saves at once. */
    @Override
    protected void leaveScreen() {
        for (SettingsPage p : cols) p.cancel();
        game.hitFx().clear();
        game.applySettings();
        game.profiles().markDirty();
        game.profiles().save();
    }

    @Override
    protected void disposeScreen() {
        for (GlitchState g : states) game.glitch().freeState(g);
    }

    @Override
    public void onActiveDiverChanged() {
        refreshDynamic();
        for (SettingsPage p : cols) p.syncAll();
        preview.reset();
    }

    // ------------------------------------------------------------------ layout
    @Override
    protected void layout() {
        k = Ui.uiScale();
        float sl = Ui.safeLeft(), sr = Ui.safeRight(), sb = Ui.safeBottom();
        colTop = Ui.safeTop() - HEADER_H - TOP_GAP * k;
        contentBottom = sb + FOOT_H * k;
        footY = sb + FOOT_H * 0.4f * k;
        float gap = GAP * k, strip = STRIP_H * k;
        // columns about a quarter of the width each: as wide as leaves the preview the screen's shape, within limits
        float availW = sr - sl;
        float previewH = colTop - contentBottom - strip - STRIP_GAP * k;
        float ideal = (availW - 2f * gap - previewH * Ui.worldW() / Ui.worldH()) * 0.5f;
        colW = Math.max((Lang.zh() ? COL_MIN_ZH : COL_MIN_EN) * k, Math.min(COL_MAX * k, ideal));
        colW = Math.max(200f, Math.min(colW, (availW - 2f * gap - PREVIEW_MIN * k) * 0.5f));
        leftX = sl;
        rightX = sr - colW;
        centreX = sl + colW + gap;
        centreW = rightX - gap - centreX;
        // the rows never scroll: when they need more than the height (large UI scale on a phone) the columns shrink
        float rowsTop = colTop - HEAD_H * k;
        float avail = rowsTop - contentBottom;
        float kc = k;
        while (kc > FIT_MIN * k && Math.max(left.rowsHeight(kc), right.rowsHeight(kc)) > avail) kc -= 0.02f * k;
        left.layout(leftX, contentBottom, colW, avail, kc);
        right.layout(rightX, contentBottom, colW, avail, kc);
        stripY = contentBottom;
        preview.setBounds(centreX, stripY + strip + STRIP_GAP * k, centreW, Math.max(80f, previewH));
        // the sample words at cap 46 (smaller when they do not fit), with equal space between and around them
        float cap = 46f * k, total = 0f;
        for (int i = 0; i < SAMPLES.length; i++) {
            wordX[i] = game.fonts().width(Fonts.Role.DISPLAY_SOFT, cap, SAMPLES[i].display);
            total += wordX[i];
        }
        float fit = (centreW - (SAMPLES.length + 1) * WORD_GAP * k) / Math.max(1f, total);
        wordCap = Math.min(cap * Math.min(1f, fit), strip * 0.32f);
        float sc = wordCap / cap;
        float space = (centreW - total * sc) / (SAMPLES.length + 1);
        float x = centreX + space;
        for (int i = 0; i < SAMPLES.length; i++) {
            float ww = wordX[i] * sc;
            wordX[i] = x + ww * 0.5f;
            x += ww + space;
        }
    }

    // ------------------------------------------------------------------ frame
    @Override
    protected void update(float delta) {
        for (SettingsPage p : cols) p.update(delta);
        preview.update(delta);
        for (int i = 0; i < SAMPLES.length; i++) {
            if (clock < nextBurst[i]) continue;
            states[i].trigger(SAMPLES[i] == Judgement.LOST ? GlitchText.Level.G2_RED : GlitchText.Level.G2, clock);
            nextBurst[i] = clock + PERIOD;
        }
    }

    @Override
    protected void drawScreen(float delta) {
        float e = enter(0.05f);
        drawHead(HEAD_SCREEN, leftX, e);
        drawHead(HEAD_NOTES, rightX, e);
        for (SettingsPage p : cols) p.draw(batch, viewport, 1f);
        float pe = enter(0.12f);
        preview.draw(batch, viewport, pe);
        drawStrip(pe);
        game.fonts().drawFit(batch, Fonts.Role.UI_REGULAR, 18f * k, FOOTNOTE, centreX + centreW * 0.5f, footY,
            Align.center | Fonts.VCENTER, Ui.safeRight() - Ui.safeLeft(), 0.8f, Theme.INK3, enter(0.2f));
    }

    /** A column's header: the name and a hairline to the column's right edge. */
    private void drawHead(CharSequence text, float x, float alpha) {
        float cy = colTop - 14f * k;
        float tw = game.fonts().drawFit(batch, Fonts.Role.UI_LABEL, 18f * k, text, x, cy, Align.left | Fonts.VCENTER,
            colW - 40f * k, 0.8f, Theme.INK2, alpha);
        Panel.divider(game.draw(), x + tw + 16f * k, x + colW, cy, 0.8f * alpha);
    }

    /** The JUDGEMENT WORDS strip under the preview. */
    private void drawStrip(float alpha) {
        if (alpha <= 0.002f) return;
        float h = STRIP_H * k;
        game.draw().roundRect(centreX, stripY, centreW, h, 6f, Theme.ABYSS_BLACK, 0.35f * alpha);
        game.draw().roundRectStroke(centreX, stripY, centreW, h, 6f, 1.5f, Theme.STROKE, alpha);
        game.fonts().draw(batch, Fonts.Role.UI_LABEL, 18f * k, WORDS, centreX + centreW * 0.5f, stripY + h - 18f,
            Align.center | Align.top, Theme.INK2, 0.9f * alpha);
        GlitchText g = game.glitch();
        float cy = stripY + h * 0.42f;
        for (int i = 0; i < SAMPLES.length; i++) {
            Judgement j = SAMPLES[i];
            g.draw(batch, Fonts.Role.DISPLAY_SOFT, wordCap, j.display, wordX[i], cy, Align.center | Fonts.VCENTER,
                Theme.judgement(j), (j == Judgement.LOST ? 0.85f : 1f) * alpha, states[i], clock);
        }
    }

    // ------------------------------------------------------------------ input
    /** The preview only plays (Kelvin 2026-10-18 19:52: 「live preview就不要支持点击了，只看就行了」). */
    @Override
    protected boolean contentTouchDown(float x, float y, int pointer) {
        return left.touchDown(x, y, pointer) || right.touchDown(x, y, pointer);
    }

    @Override
    protected boolean contentTouchDragged(float x, float y, int pointer) {
        return left.touchDragged(x, y, pointer) | right.touchDragged(x, y, pointer);
    }

    @Override
    protected boolean contentTouchUp(float x, float y, int pointer) {
        return left.touchUp(x, y, pointer) | right.touchUp(x, y, pointer);
    }

    @Override
    protected void contentCancel(int pointer) {
        for (SettingsPage p : cols) p.cancel();
    }

    @Override
    protected void contentMouseMoved(float x, float y) {
        for (SettingsPage p : cols) p.mouseMoved(x, y);
    }
}
