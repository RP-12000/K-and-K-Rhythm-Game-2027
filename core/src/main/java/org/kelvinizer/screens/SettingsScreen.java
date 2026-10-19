package org.kelvinizer.screens;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.Params;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.profile.DeviceSettings;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.screens.shell.ActionRow;
import org.kelvinizer.screens.shell.ChipsRow;
import org.kelvinizer.screens.shell.ChoiceRow;
import org.kelvinizer.screens.shell.DiverActionsDialog;
import org.kelvinizer.screens.shell.InfoRow;
import org.kelvinizer.screens.shell.Licenses;
import org.kelvinizer.screens.shell.ResetDialog;
import org.kelvinizer.screens.shell.ScrollArea;
import org.kelvinizer.screens.shell.SettingRow;
import org.kelvinizer.screens.shell.SettingsPage;
import org.kelvinizer.screens.shell.ShellScreen;
import org.kelvinizer.screens.shell.SliderRow;
import org.kelvinizer.screens.shell.TabRail;
import org.kelvinizer.screens.shell.TextRow;
import org.kelvinizer.screens.shell.ToggleRow;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

import java.util.List;

/**
 * 设置画面：AUDIO、VISUAL、GAMEPLAY、OTHER 四页（Kelvin 2026-10-18 00:24 把 TIMING 拆进 AUDIO 和 VISUAL，
 * 去掉 VISUAL OFFSET；02:27 把 ACCOUNT 和 ABOUT 合并成 OTHER「其他」，校准按钮挪到音频延迟下面；
 * 03:25 音频延迟排到 AUDIO 第一项，音符图鉴挪进 OTHER；04:06 预览看得到的设置挪进 VISUAL 里点「调整…」
 * 才打开的「实时预览」页，同时提示到偏差条挪到 GAMEPLAY 最前，去掉实时计数，空点标记改成开关「显示触控」，
 * K &amp; K 那一行放在开源许可证下面；04:34 同时提示改成「关 / 开 / 增强」，语言挪进 OTHER 最上面；
 * 04:48「本机」小标签改叫「公用」；05:14 去掉音符图鉴；05:58 K &amp; K 在左、版本号在右，大小跟着剩下的高度自动调，
 * OTHER 一般不用滚动；06:03 共鸣和回响的早 / 晚提示合成一行，两个可多选的按钮，GAMEPLAY 一般也不用滚动；
 * 14:29 中文名换成 Kelvin 定的（谱面延时、音频预设、视觉、游玩界面视觉参数、背景亮度、多押辅助等），英文按新中文重译，
 * 去掉背景插画开关、打击音效时机、曲目试听和菜单音乐，谱面延时下面两行小字说 EARLY / LATE 多时按哪边；
 * 15:17 音频加「界面音效音量」，游玩界面视觉参数改成像校准那样的独立画面 {@link VisualsScreen}），
 * 宽屏用左侧标签栏，平板比例用顶部标签行。
 * 许可证页不在标签栏上，返回键一层层退回其他。
 * 潜水员设置存进存档，本机设置存进 {@code device.json}；每项改动立即生效，离开时保存。
 * 所有文字跟着 LANGUAGE 切换中英文。
 *
 * <p>Settings (DESIGN §9.1–§9.3).
 *
 * <ul>
 *   <li><b>Shell</b>: backdrop at the diver's depth (shafts × 0.6, {@code Blue.png} at α .15),
 *       CURTAIN in and out, BACK to the screen that opened it. A left rail 300 wide
 *       ({@code AUDIO · VISUAL · GAMEPLAY · OTHER}) on WIDE, a top tab row on TALL
 *       (4:3).</li>
 *   <li><b>Pages</b> (Kelvin 2026-10-18 00:24, 02:27, 03:25, 04:06, 04:34, 05:14, 05:58, 06:03, 14:29 and 15:17;
 *       since 14:29 the Chinese names are hers and the English is translated back from them): AUDIO opens
 *       with TIMING, CHART OFFSET first (CALIBRATE… left of its nudge buttons, two hint lines on
 *       which way to nudge) and then the AUDIO PRESET, followed by SOUND: music, hit sound and UI sound
 *       volume (the last two dimmed while no such sounds ship). VISUAL opens with GAMEPLAY VISUALS, whose
 *       ADJUST… opens {@link VisualsScreen} (a screen of its own like calibration since 15:17, a page of
 *       this list before) with the settings its preview shows; the rest of VISUAL is the scenery, the artwork's
 *       BACKGROUND BRIGHTNESS, UI scale, edge padding and FPS. There is no visual offset any more
 *       (it overlapped the audio offset), and no hit sound mode, song preview, menu music or
 *       background art switch since 14:29 ({@link Settings#sanitize} keeps them on). GAMEPLAY opens
 *       with the in-dive hints (MULTI-PRESS ASSIST, TOO EARLY / TOO LATE HINT with a RESONANCE and
 *       an ECHO chip since 06:03, ms, hit error meter), then the pause input and haptics, and fits
 *       without scrolling. OTHER lists LANGUAGE, SAVE BACKUP (the backup-code EXPORT),
 *       RESET RECORDS, the tutorial and OPEN-SOURCE LICENSES with no section headers, then K &amp; K
 *       at the left and the version at the right in a row that takes the height left (05:58), so
 *       OTHER fits too (both within the limits of DESIGN §24.12; no NOTE GUIDE since 05:14). The
 *       licences page (the game's licence, music &amp; charts, typefaces and software, see
 *       {@link Licenses}) and the licence text page sit off the rail: the rail keeps OTHER lit, the
 *       title names the page and BACK steps back one page at a time.</li>
 *   <li><b>Rows</b> ({@link SettingRow} family, 104 × uiScale tall, at least one touch target):
 *       label, description, a {@code SHARED} / {@code 公用} tag on device-scoped settings (shared by
 *       every diver on the device; {@code DEVICE} / {@code 本机} until 2026-10-18 04:48) and the
 *       control on the right. Each tab enters with a 320 ms {@code pow3Out} slide and a 40 ms row
 *       stagger.</li>
 *   <li><b>Scope</b>: diver settings live in {@code Profile.settings} and are marked with
 *       {@link ProfileStore#markDirty()}; device settings live in {@code device.json}
 *       ({@link ProfileStore#markDeviceDirty()}). Every change applies live
 *       ({@link Main#applySettings()} after discrete changes and on slider release; slider drags
 *       update the cheap live parts directly) and everything is saved on leave
 *       ({@link ProfileStore#save()}, off the render thread).</li>
 *   <li><b>Language</b>: every label is a {@link Lang.Text}, so switching LANGUAGE relabels the
 *       rail, the title and every row at once (Kelvin 2026-10-18 00:24); the legal texts stay in
 *       English.</li>
 * </ul>
 * Nothing is allocated per frame; descriptions that change swap between prebuilt
 * {@link Lang.Text}s.
 */
public class SettingsScreen extends ShellScreen {
    /** Tab indices for {@link #selectTab(int)}. */
    public static final int TAB_AUDIO = 0, TAB_VISUAL = 1, TAB_GAMEPLAY = 2, TAB_OTHER = 3;
    /** Pages off the rail: the open-source licences and one licence text under OTHER. */
    private static final int PAGE_LICENSES = 4, PAGE_TEXT = 5;

    private static final CharSequence[] TAB_NAMES = Lang.list("AUDIO", "音频", "VISUAL", "视觉", "GAMEPLAY", "游玩",
        "OTHER", "其他");
    private static final int[] TAB_ICONS = {TabRail.ICON_SPEAKER, TabRail.ICON_EYE, TabRail.ICON_LINES,
        TabRail.ICON_MORE};
    private static final CharSequence TITLE = Lang.of("SETTINGS", "设置");
    private static final CharSequence TITLE_LICENSES = Lang.of("LICENSES", "开源许可证");
    private static final float RAIL_W = 300f;
    private static final float RAIL_GAP = 48f;
    private static final float LIST_MAX_W = 1240f;
    /** UI scale options (0 = Auto). */
    private static final float[] UI_SCALES = {0f, 0.9f, 1f, 1.15f, 1.3f};
    private static final float RESET_HOLD = 2f;
    /** Version line of the OTHER tab and under the rail. */
    private static final CharSequence VERSION_LINE = Lang.of("VERSION " + Params.VERSION, "版本 " + Params.VERSION);
    /** The VIEW button of every licence row. */
    private static final CharSequence VIEW = Lang.of("VIEW", "查看");

    /**
     * The three description lines of CHART OFFSET: what it does (Kelvin 2026-10-18 19:52), then the two hints on which
     * way to nudge (14:29; the output and calibration state before).
     */
    private static final CharSequence OFFSET_DESC = Lang.of("ADJUST THE SYNCHRONIZATION OF MUSIC", "调整音乐的同步性"),
        OFFSET_EARLY = Lang.of("MOSTLY EARLY → TAP – TO DECREASE", "EARLY 较多 → 点击减少（–）"),
        OFFSET_LATE = Lang.of("MOSTLY LATE → TAP + TO INCREASE", "LATE 较多 → 点击增加（+）");

    // descriptions that depend on other values (refreshDynamic)
    private static final CharSequence HIT_ON = Lang.of("ADJUST THE VOLUME OF NOTE SOUND EFFECTS", "调整音符的打击音效音量"),
        HIT_NONE = Lang.of("ADJUST THE VOLUME OF NOTE SOUND EFFECTS · NONE INSTALLED YET",
            "调整音符的打击音效音量 · 还没有打击音效"),
        UI_NONE = Lang.of("ADJUSTS THE MENU AND BUTTON SOUNDS · NONE INSTALLED YET", "调整菜单和按钮的音效音量 · 还没有界面音效"),
        SHADERS_OFF = Lang.of("SHADERS UNAVAILABLE ON THIS DEVICE", "这台设备不支持着色器"),
        AUTO_LOWERED = Lang.of("LOWERED AUTOMATICALLY FOR SPEED", "为了流畅已自动调低"),
        DETAIL_DESC = Lang.of("LIGHT SHAFTS, CAUSTICS AND MOTES", "光柱、水纹光斑和浮尘");

    private final TabRail rail;
    private final SettingsPage[] pages = new SettingsPage[TAB_NAMES.length + 2];

    private SliderRow hitSoundRow, offsetRow, edgeRow;
    private ChoiceRow detailRow;
    private ActionRow backupRow;
    private Button exportButton;
    /** RESET RECORDS' confirmation (made on the first hold). */
    private ResetDialog resetDialog;

    private int tab = TAB_AUDIO;
    private boolean tall;
    private float k = 1f;
    private float contentX, contentTop, contentW;

    /** Creates the screen once (Main caches it). */
    public SettingsScreen(final Main game) {
        super(game, TITLE, "Blue.png", 0.15f, 0.6f, false);
        rail = new TabRail(game, widgets, TAB_NAMES, TAB_ICONS);
        rail.setListener(new TabRail.Listener() {
            @Override
            public void selected(int index) {
                showTab(index, true);
            }
        });
        for (int i = 0; i < pages.length; i++) pages[i] = new SettingsPage(game.draw());
        buildAudio(pages[TAB_AUDIO]);
        buildVisual(pages[TAB_VISUAL]);
        buildGameplay(pages[TAB_GAMEPLAY]);
        buildOther(pages[TAB_OTHER]);
        buildLicenses(pages[PAGE_LICENSES]);
    }

    // ------------------------------------------------------------------ public API
    /** Selects the tab shown on the next show (e.g. {@link #TAB_AUDIO} from a calibration hint). */
    public void selectTab(int index) {
        tab = Math.max(0, Math.min(TAB_OTHER, index));
        rail.select(tab, false);
    }

    /** The tab currently shown. */
    public int selectedTab() {
        return tab;
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

    /** A device-scoped setting changed: mark device.json dirty and apply. */
    private void deviceChanged() {
        game.profiles().markDeviceDirty();
        game.applySettings();
    }

    private SettingsPage page() {
        return pages[tab];
    }

    // ------------------------------------------------------------------ AUDIO
    private void buildAudio(SettingsPage p) {
        // the audio half of the old TIMING tab (Kelvin 2026-10-18 00:24), first on the page with the offset
        // above the output since 03:25
        p.add(InfoRow.header(game, Lang.of("TIMING", "延迟")));
        // 音频延迟 → 谱面延时 and 音频输出 → 音频预设, the English retranslated from them (Kelvin 2026-10-18 14:29)
        offsetRow = p.add(new SliderRow(game, Lang.of("CHART OFFSET", "谱面延时"), OFFSET_DESC, true,
            DeviceSettings.AUDIO_OFFSET_MIN, DeviceSettings.AUDIO_OFFSET_MAX, 1f, 1f, false, true, "ms",
            new SliderRow.Value() {
                @Override
                public float get() {
                    return d().audioOffsetMs();
                }

                @Override
                public void set(float v, boolean finished) {
                    d().setAudioOffsetMs(Math.round(v));
                    if (finished) deviceChanged();
                }
            }));
        offsetRow.setNudges(new float[]{-10f, -1f, 1f, 10f}, new CharSequence[]{"–10", "–1", "+1", "+10"});
        offsetRow.setDescription2(OFFSET_EARLY);
        offsetRow.setDescription3(OFFSET_LATE);
        // CALIBRATE… fills the space left of the nudges instead of a row of its own (Kelvin 2026-10-18 02:27)
        Button calibrate = offsetRow.setLead(new Button(game.draw(), game.fonts(), Button.Variant.PRIMARY,
            Lang.of("CALIBRATE…", "校准…")));
        calibrate.setOnTap(new Runnable() {
            @Override
            public void run() {
                game.calibration().setQuickMode(false);
                openScreen(game.calibration(), CalibrationScreen.TITLE);
            }
        });
        p.add(new ChoiceRow(game, Lang.of("AUDIO PRESET", "音频预设"),
            Lang.of("TAP TO SWITCH THE PRESET", "点击切换预设项目"), true,
            Lang.list("SPEAKER", "扬声器", "WIRED", "有线耳机", "BT", "蓝牙"), new ChoiceRow.Value() {
                @Override
                public int get() {
                    return d().activeOutput;
                }

                @Override
                public void set(int index) {
                    d().activeOutput = index;
                    deviceChanged();
                    offsetRow.sync();
                }
            }));

        p.add(InfoRow.header(game, Lang.of("SOUND", "声音")));
        p.add(new SliderRow(game, Lang.of("MUSIC VOLUME", "音乐音量"), Lang.of("MENUS, PREVIEWS AND SONGS", "菜单、试听和曲目"),
            true, 0f, 1f, 0.01f, 100f, true, false, null, new SliderRow.Value() {
                @Override
                public float get() {
                    return d().musicVolume;
                }

                @Override
                public void set(float v, boolean finished) {
                    d().musicVolume = v;
                    if (finished) deviceChanged();
                }
            }));
        // HIT SOUND VOLUME → NOTE VOLUME in English (Kelvin 2026-10-18 19:52)
        hitSoundRow = p.add(new SliderRow(game, Lang.of("NOTE VOLUME", "打击音效音量"), null, true, 0f, 1f, 0.01f,
            100f, true, false, null, new SliderRow.Value() {
                @Override
                public float get() {
                    return d().sfxVolume;
                }

                @Override
                public void set(float v, boolean finished) {
                    d().sfxVolume = v;
                    if (!finished) return;
                    deviceChanged();
                    if (game.audio().hasHitSound()) game.audio().playHit(Judgement.RESONANCE);
                }
            }));
        // UI SOUND VOLUME (Kelvin 2026-10-18 15:17): no UI sounds ship yet, so the row waits dimmed like NOTE VOLUME
        // without hit sounds; the value is kept for when they arrive
        SliderRow uiSoundRow = p.add(new SliderRow(game, Lang.of("UI SOUND VOLUME", "界面音效音量"), UI_NONE, true, 0f, 1f,
            0.01f, 100f, true, false, null, new SliderRow.Value() {
                @Override
                public float get() {
                    return d().uiVolume;
                }

                @Override
                public void set(float v, boolean finished) {
                    d().uiVolume = v;
                    if (finished) deviceChanged();
                }
            }));
        uiSoundRow.setEnabled(false);
        uiSoundRow.setDescription(UI_NONE, Theme.INK2);
        // HIT SOUND MODE (on judgement only), SONG PREVIEW and MENU MUSIC have no rows since Kelvin 2026-10-18 14:29;
        // Settings.sanitize keeps them at their defaults
    }

    // ------------------------------------------------------------------ VISUAL
    private void buildVisual(SettingsPage p) {
        // the settings the preview shows sit behind this row (Kelvin 2026-10-18 04:06), on a screen of their own like
        // calibration since 15:17
        ActionRow live = p.add(new ActionRow(game, VisualsScreen.TITLE,
            Lang.of("GLITCH, PARTICLES, NOTE SCALE, JUDGEMENTS, MULTI-PRESS", "故障、粒子、按键缩放、判定和多押，边调边看"), false));
        live.add(new Button(game.draw(), game.fonts(), Button.Variant.PRIMARY, Lang.of("ADJUST…", "调整…")))
            .setOnTap(new Runnable() {
                @Override
                public void run() {
                    openScreen(game.visuals(), VisualsScreen.TITLE);
                }
            });
        p.add(new ToggleRow(game, Lang.of("REDUCE MOTION", "减少动态"),
            Lang.of("CALMER SCENERY AND TRANSITIONS", "场景和转场更平静"), false, new ToggleRow.Value() {
                @Override
                public boolean get() {
                    return s().reduceMotion;
                }

                @Override
                public void set(boolean on) {
                    s().reduceMotion = on;
                    diverChanged();
                }
            }));
        detailRow = p.add(new ChoiceRow(game, Lang.of("BACKGROUND DETAIL", "背景细节"), null, true,
            Lang.list("LOW", "低", "HIGH", "高"), new ChoiceRow.Value() {
                @Override
                public int get() {
                    return d().backgroundDetail;
                }

                @Override
                public void set(int index) {
                    d().backgroundDetail = index;
                    d().backdropAutoDropped = false;
                    deviceChanged();
                    refreshDynamic();
                }
            }));
        // the BACKGROUND ART switch is gone and ART OPACITY reads BACKGROUND BRIGHTNESS (Kelvin 2026-10-18 14:29): the
        // artwork always shows, as bright as this slider says
        p.add(new SliderRow(game, Lang.of("BACKGROUND BRIGHTNESS", "背景亮度"),
            Lang.of("BRIGHTNESS OF THE MENU AND SONG ARTWORK", "菜单和曲目背景插画的亮度"), false, 0f, 1f, 0.01f,
            100f, true, false, null, new SliderRow.Value() {
                @Override
                public float get() {
                    return s().artOpacity;
                }

                @Override
                public void set(float v, boolean finished) {
                    s().artOpacity = v;
                    applyArt();
                    if (finished) diverChanged();
                }
            }));
        p.add(new ChoiceRow(game, Lang.of("UI SCALE", "界面缩放"),
            Lang.of("PERCENT · AUTO = 115 ON PHONES", "百分比 · 自动在手机上是 115"), true,
            Lang.list("AUTO", "自动", "90", "90", "100", "100", "115", "115", "130", "130"), new ChoiceRow.Value() {
                @Override
                public int get() {
                    return uiScaleIndex(d().uiScale);
                }

                @Override
                public void set(int index) {
                    d().uiScale = UI_SCALES[index];
                    deviceChanged();
                }
            }));
        edgeRow = p.add(new SliderRow(game, Lang.of("EXTRA EDGE PADDING", "额外边距"),
            Lang.of("CLEARANCE FROM CURVED EDGES", "离弧形屏幕边缘远一点"), true, 0f, 48f, 4f, 1f, false, false, "px",
            new SliderRow.Value() {
                @Override
                public float get() {
                    return d().edgePadding;
                }

                @Override
                public void set(float v, boolean finished) {
                    if (!finished) return;
                    d().edgePadding = Math.round(v);
                    deviceChanged();
                }
            }));
        p.add(new ToggleRow(game, Lang.of("SHOW FPS", "显示帧率"), Lang.of("FRAME RATE IN THE CORNER", "在角落显示帧率"), true,
            new ToggleRow.Value() {
                @Override
                public boolean get() {
                    return d().showFps;
                }

                @Override
                public void set(boolean on) {
                    d().showFps = on;
                    deviceChanged();
                }
            }));
    }

    /** {@link Settings#pauseMode} of each "PAUSE INPUT" option, in display order. */
    private static final int[] PAUSE_ORDER = {
        Settings.PAUSE_DOUBLE_TAP, Settings.PAUSE_HOLD, Settings.PAUSE_SINGLE_TAP};

    private static int uiScaleIndex(float v) {
        if (v <= 0f) return 0;
        int best = 1;
        for (int i = 2; i < UI_SCALES.length; i++) {
            if (Math.abs(UI_SCALES[i] - v) < Math.abs(UI_SCALES[best] - v)) best = i;
        }
        return best;
    }

    // ------------------------------------------------------------------ GAMEPLAY
    private void buildGameplay(SettingsPage p) {
        // the in-game hints from VISUAL come first (Kelvin 2026-10-18 04:06). LIVE COUNTERS went away then: they
        // only showed in the band layout, which no landscape screen has used since the field stretches.
        // MULTI-PRESS ASSIST went to GAMEPLAY VISUALS and SHOW TOUCHES came here on 19:52.
        // 早 / 晚提示 → 过早 / 过晚提示 (Kelvin 2026-10-18 14:29); EARLY / LATE HINT in English since 19:52.
        // One row, a chip per grade, any or none lit (06:03; two switches since 10-12 22:04)
        p.add(new ChipsRow(game, Lang.of("EARLY / LATE HINT", "过早 / 过晚提示"),
            Lang.of("RESONANCE ONLY OUTSIDE ±33 MS", "共鸣只在 ±33 ms 以外提示"), false,
            Lang.list("RESONANCE", "共鸣", "ECHO", "回响"), new Color[] {Theme.RESONANCE, Theme.ECHO},
            new ChipsRow.Value() {
                @Override
                public boolean get(int i) {
                    return i == 0 ? s().earlyLateResonance() : s().earlyLateEcho();
                }

                @Override
                public void set(int i, boolean on) {
                    s().setEarlyLate(i == 0 ? on : s().earlyLateResonance(), i == 1 ? on : s().earlyLateEcho());
                    diverChanged();
                }
            }));
        p.add(new ToggleRow(game, Lang.of("SHOW MS", "显示毫秒"), Lang.of("TIMING ERROR IN MILLISECONDS", "用毫秒显示偏差"),
            false, new ToggleRow.Value() {
                @Override
                public boolean get() {
                    return s().showTimingMs;
                }

                @Override
                public void set(boolean on) {
                    s().showTimingMs = on;
                    diverChanged();
                }
            }));
        p.add(new ToggleRow(game, Lang.of("HIT ERROR METER", "偏差条"),
            Lang.of("A STRIP OF RECENT TIMING TICKS", "一条显示最近几次偏差的刻度"), false, new ToggleRow.Value() {
                @Override
                public boolean get() {
                    return s().showTimingMeter;
                }

                @Override
                public void set(boolean on) {
                    s().showTimingMeter = on;
                    diverChanged();
                }
            }));
        // DOUBLE-TAP (the default) is listed first; the row maps its order onto Settings.pauseMode
        p.add(new ChoiceRow(game, Lang.of("PAUSE INPUT", "暂停方式"), Lang.of("CHOOSE HOW TO PAUSE", "下潜时暂停键怎样触发"),
            false,
            Lang.list("DOUBLE-TAP", "双击", "HOLD 0.35 S", "按住 0.35 秒", "SINGLE TAP", "单击"), new ChoiceRow.Value() {
                @Override
                public int get() {
                    for (int i = 0; i < PAUSE_ORDER.length; i++) {
                        if (PAUSE_ORDER[i] == s().pauseMode) return i;
                    }
                    return 0;
                }

                @Override
                public void set(int index) {
                    s().pauseMode = PAUSE_ORDER[index];
                    diverChanged();
                }
            }));
        // from GAMEPLAY VISUALS between PAUSE INPUT and HAPTICS (Kelvin 2026-10-18 19:52), now a ring under every finger
        // in a game; a plain switch since 04:06 (the old AUTO reads as off)
        p.add(new ToggleRow(game, Lang.of("SHOW TOUCHES", "显示触控"),
            Lang.of("A RING UNDER EACH FINGER DURING A GAME", "游戏中在每个手指下面显示一个圈"), false, new ToggleRow.Value() {
                @Override
                public boolean get() {
                    return s().strayTouches == Settings.STRAY_ON;
                }

                @Override
                public void set(boolean on) {
                    s().strayTouches = on ? Settings.STRAY_ON : Settings.STRAY_OFF;
                    diverChanged();
                }
            }));
        p.add(new ChoiceRow(game, Lang.of("HAPTICS", "震动"), Lang.of("VIBRATION ON SUPPORTED DEVICES", "支持的设备上会震动"),
            false, Lang.list("OFF", "关", "MENUS", "菜单", "MENUS + HITS", "菜单 + 打击"), new ChoiceRow.Value() {
                @Override
                public int get() {
                    return s().haptics;
                }

                @Override
                public void set(int index) {
                    s().haptics = index;
                    diverChanged();
                }
            }));
    }

    // ------------------------------------------------------------------ OTHER
    /**
     * ACCOUNT and ABOUT on one page (Kelvin 2026-10-18 02:27), as one list with no section headers
     * since 03:25: LANGUAGE (from GAMEPLAY on 04:34), the backup-code export, RESET RECORDS, the
     * tutorial, the licences and K &amp; K with the version (under the licences since 04:06; at the left and
     * right of a row that takes the height left since 05:58). The NOTE GUIDE was the last row until 05:14.
     * Divers are switched on PROFILE.
     */
    private void buildOther(SettingsPage p) {
        // the whole interface but the song select follows it (Kelvin 2026-10-18 00:24); first on OTHER since 04:34
        // LANGUAGE, TUTORIAL and OPEN-SOURCE LICENSES have no description since Kelvin 2026-10-18 14:29
        p.add(new ChoiceRow(game, Lang.of("LANGUAGE", "语言"), null, false,
            new CharSequence[]{"EN", "中文"}, new ChoiceRow.Value() {
                @Override
                public int get() {
                    return s().zh() ? 1 : 0;
                }

                @Override
                public void set(int index) {
                    s().language = index == 1 ? Settings.LANG_ZH : Settings.LANG_EN;
                    diverChanged();
                    refreshDynamic();
                }
            }));
        backupRow = p.add(new ActionRow(game, Lang.of("SAVE BACKUP", "备份存档"),
            Lang.of("COPY THIS DIVER AS A KK1: CODE", "把当前玩家复制成一串 KK1: 备份码"), false));
        exportButton = backupRow.add(new Button(game.draw(), game.fonts(), Button.Variant.SECONDARY,
            Lang.of("EXPORT", "导出")));
        exportButton.setOnTap(new Runnable() {
            @Override
            public void run() {
                if (!DiverActionsDialog.exportCode(game, game.profiles().activeId())) exportButton.shake();
            }
        });
        // everything goes since Kelvin 2026-10-18 20:16, after the hold, CONFIRM and the name typed in full
        ActionRow reset = p.add(new ActionRow(game, Lang.of("RESET RECORDS", "重置记录"),
            Lang.of("ERASES ALL OF THIS DIVER'S PROGRESS", "清空当前玩家的全部进度"), false));
        reset.setDescription2(Lang.of("SCORES, DEPTH, GAME LOG, SHELLS AND BOUGHT SONGS",
            "成绩、深度、下潜记录、贝壳和买下的曲目"));
        Button hold = reset.add(new Button(game.draw(), game.fonts(), Button.Variant.DESTRUCTIVE,
            Lang.of("HOLD TO RESET", "按住重置"))).setHoldTime(RESET_HOLD);
        hold.setOnTap(new Runnable() {
            @Override
            public void run() {
                if (resetDialog == null) {
                    resetDialog = new ResetDialog(game, SettingsScreen.this, new Runnable() {
                        @Override
                        public void run() {
                            resetRecords();
                        }
                    });
                }
                openModal(resetDialog);
            }
        });
        // the tutorial lives here since the cave map (Kelvin 2026-10-12 22:04); first launch still plays it
        ActionRow tutorial = p.add(new ActionRow(game, Lang.of("TUTORIAL", "新手教程"), null, false));
        tutorial.add(new Button(game.draw(), game.fonts(), Button.Variant.SECONDARY, Lang.of("PLAY", "开始")))
            .setOnTap(new Runnable() {
                @Override
                public void run() {
                    game.profiles().save();
                    game.startTutorial();
                }
            });
        // credits and licences moved to their own page (Kelvin 2026-10-18 00:24)
        ActionRow licenses = p.add(new ActionRow(game, Lang.of("OPEN-SOURCE LICENSES", "开源许可证"), null, false));
        licenses.add(new Button(game.draw(), game.fonts(), Button.Variant.SECONDARY, VIEW))
            .setOnTap(new Runnable() {
                @Override
                public void run() {
                    showTab(PAGE_LICENSES, true);
                }
            });
        // under the licences (Kelvin 2026-10-18 04:06) and the last row since the NOTE GUIDE left (05:14), without
        // TWO SIGNALS · ONE DESCENT since 05:28; since 05:58 K & K left and the version right, sized by the height
        // the other rows leave, so OTHER fits wherever they leave the row's minimum (DESIGN §24.12)
        p.add(InfoRow.brand(game, "K & K", VERSION_LINE));
    }

    /**
     * RESET RECORDS (DESIGN §9.2: hold 2 s, then {@link ResetDialog}'s CONFIRM and the name typed in full): takes
     * the active diver back to a new player's progress, shells and bought songs included
     * ({@link ProfileStore#resetRecords}, whose revision bump makes Profile and Map rebuild).
     */
    private void resetRecords() {
        ProfileStore store = game.profiles();
        Profile p = store.active();
        if (p == null || !store.resetRecords(p.id)) return;
        game.backdrop().setDepth(screenDepth());
        game.toasts().show(Lang.t("RECORDS RESET", "记录已重置"), Theme.LOST);
        glitchTitle();
    }

    // ------------------------------------------------------------------ LICENSES
    /** The open-source licences page: the game's licence, music &amp; charts, typefaces and software. */
    private void buildLicenses(SettingsPage p) {
        p.add(InfoRow.header(game, "K & K"));
        licenseRow(p, Licenses.GAME, Lang.of("CODE AND DOCUMENTATION", "代码和文档"));
        p.add(InfoRow.line(game, Lang.of("MUSIC, SOUNDS, ARTWORK AND CHARTS", "音乐、音效、插画和谱面"),
            Lang.of("NOT COVERED BY THE MIT LICENSE · OWNED BY THEIR CREATORS", "不适用 MIT 许可证 · 版权归各自的作者"),
            Lang.of("ALL RIGHTS RESERVED", "保留所有权利")));
        List<SongMeta> songs = game.library().songs();
        if (!songs.isEmpty()) {
            p.add(InfoRow.header(game, Lang.of("MUSIC & CHARTS", "音乐与谱面")));
            for (int i = 0; i < songs.size(); i++) {
                SongMeta m = songs.get(i);
                p.add(InfoRow.line(game, nz(m.title()), Lang.of(credits(m, " · CHARTS ", " · ILLUSTRATION "),
                    credits(m, " · 谱面 ", " · 曲绘 ")), null));
            }
        }
        p.add(InfoRow.header(game, Lang.of("TYPEFACES", "字体")));
        for (Licenses.Entry e : Licenses.TYPEFACES) licenseRow(p, e, null);
        p.add(InfoRow.header(game, Lang.of("SOFTWARE", "软件")));
        for (Licenses.Entry e : Licenses.SOFTWARE) licenseRow(p, e, null);
    }

    /** "artist · CHARTS x · ILLUSTRATION y" with the two given separators. */
    private static String credits(SongMeta m, String charts, String art) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(nz(m.artist()));
        String charters = m.chartersText();
        if (!charters.isEmpty()) sb.append(charts).append(charters);
        if (!nz(m.illustrator()).isEmpty()) sb.append(art).append(m.illustrator());
        return sb.toString();
    }

    /** One credit: name (or {@code label}), notice · licence (· where it ships), and VIEW for the text. */
    private void licenseRow(SettingsPage p, final Licenses.Entry e, CharSequence label) {
        String first = e.notice.split("\n")[0];
        boolean desk = e.platform == Licenses.DESKTOP, droid = e.platform == Licenses.ANDROID;
        String where = desk ? " · DESKTOP" : droid ? " · ANDROID" : "";
        String whereZh = desk ? " · 电脑版" : droid ? " · 安卓版" : "";
        ActionRow row = p.add(new ActionRow(game, label != null ? label : e.name,
            Lang.of(e.licence + where + " · " + first, e.licence + whereZh + " · " + first), false));
        row.add(new Button(game.draw(), game.fonts(), Button.Variant.SECONDARY, VIEW)).setOnTap(new Runnable() {
            @Override
            public void run() {
                showLicense(e);
            }
        });
    }

    /** Opens the text page on {@code e}'s notice and licence text (read now, replaced on the next VIEW). */
    private void showLicense(Licenses.Entry e) {
        SettingsPage p = pages[PAGE_TEXT];
        p.clear();
        p.add(InfoRow.header(game, e.name + " · " + e.licence));
        p.add(new TextRow(game, p.scroll(), Licenses.lines(e)));
        showTab(PAGE_TEXT, true);
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    // ------------------------------------------------------------------ dynamic labels
    /** Re-derives labels, descriptions and enabled states that depend on other values. */
    private void refreshDynamic() {
        DeviceSettings d = d();
        boolean hit = game.audio().hasHitSound();
        hitSoundRow.setEnabled(hit);
        hitSoundRow.setDescription(hit ? HIT_ON : HIT_NONE, hit ? null : Theme.INK2);
        boolean shader = game.backdrop().isShaderAvailable();
        detailRow.setEnabled(shader);
        if (!shader) detailRow.setDescription(SHADERS_OFF, Theme.INK2);
        else if (d.backdropAutoDropped) detailRow.setDescription(AUTO_LOWERED, Theme.GOLD);
        else detailRow.setDescription(DETAIL_DESC, null);
    }

    // ------------------------------------------------------------------ tabs
    /** Shows tab or page {@code index}; a page off the rail keeps its tab lit and titles the screen. */
    private void showTab(int index, boolean animate) {
        page().cancel();
        tab = Math.max(0, Math.min(pages.length - 1, index));
        rail.select(railTab(tab), animate);
        setTitle(tab > TAB_OTHER ? TITLE_LICENSES : TITLE);
        page().show();
    }

    /** The rail tab a page belongs to: the licence pages to OTHER. */
    private static int railTab(int index) {
        return Math.min(index, TAB_OTHER);
    }

    /** BACK steps out of a licence text to the list, out of the list to OTHER, else leaves. */
    @Override
    protected void back() {
        if (tab == PAGE_TEXT) showTab(PAGE_LICENSES, true);
        else if (tab > TAB_OTHER) showTab(railTab(tab), true);
        else goBack();
    }

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void enterScreen() {
        refreshDynamic();
        for (SettingsPage p : pages) p.syncAll();
        showTab(railTab(tab), false);
    }

    /**
     * DESIGN §9.1 "saved on leave": cancels open drags (a cancelled slider commits its value),
     * applies, then marks the diver, the device file and the index dirty and saves at once, so
     * nothing changed here can be lost even if a change path forgot its own mark.
     */
    @Override
    protected void leaveScreen() {
        for (SettingsPage p : pages) p.cancel();
        game.applySettings();
        game.profiles().markDirty();
        game.profiles().save();
    }

    @Override
    public void onActiveDiverChanged() {
        refreshDynamic();
        for (SettingsPage p : pages) p.syncAll();
    }

    @Override
    public void onDiversEdited() {
        refreshDynamic();
    }

    // ------------------------------------------------------------------ layout
    @Override
    protected void layout() {
        k = Ui.uiScale();
        tall = Ui.isTablet();
        float sl = Ui.safeLeft(), sr = Ui.safeRight(), sb = Ui.safeBottom();
        float top = Ui.safeTop() - HEADER_H;
        if (!tall) {
            rail.layoutVertical(sl, top - 8f, RAIL_W, Math.max(Ui.touchTarget(), 96f));
            contentX = sl + RAIL_W + RAIL_GAP;
        } else {
            float th = Math.max(Ui.touchTarget(), 88f);
            rail.layoutHorizontal(sl, top - th, sr - sl, th);
            contentX = sl;
            top -= th + 24f;
        }
        contentTop = top;
        contentW = sr - contentX;
        float listW = tall ? contentW : Math.min(contentW, LIST_MAX_W * k);
        for (SettingsPage p : pages) p.layout(contentX, sb, listW, contentTop - sb, k);
    }

    // ------------------------------------------------------------------ frame
    @Override
    protected void update(float delta) {
        rail.update(delta);
        page().update(delta);
    }

    @Override
    protected void drawScreen(float delta) {
        float e = enter(0.05f);
        rail.draw(batch, e);
        if (!tall) {
            float vy = sbVersionY();
            if (vy > 0f) {
                game.fonts().draw(batch, Fonts.Role.UI, 16f, VERSION_LINE, Ui.safeLeft() + 20f, vy,
                    Align.left | Fonts.VCENTER, Theme.INK3, e);
            }
        }
        page().draw(batch, viewport, 1f);
    }

    /** Y of the version line under the rail on WIDE, or 0 when it does not fit. */
    private float sbVersionY() {
        float y = Ui.safeBottom() + 20f;
        return rail.bottom() - y > 40f ? y : 0f;
    }

    @Override
    protected void drawOverlay(float delta) {
        if (edgeRow == null || !edgeRow.isGrabbing() || tab != TAB_VISUAL) return;
        float dpad = Math.round(edgeRow.sliderValue()) - d().edgePadding;
        float x0 = Ui.safeLeft() + dpad, y0 = Ui.safeBottom() + dpad;
        float x1 = Ui.safeRight() - dpad, y1 = Ui.safeTop() - dpad;
        game.draw().roundRectStroke(x0, y0, x1 - x0, y1 - y0, 20f, 3f, Theme.GROUND_SIG, 0.85f);
        game.fonts().draw(batch, Fonts.Role.UI_LABEL, 16f, Lang.t("SAFE AREA", "安全区域"), x1 - 20f, y0 + 18f,
            Align.right, Theme.GROUND_SIG, 0.9f);
    }

    // ------------------------------------------------------------------ input
    @Override
    protected ScrollArea activeScroll() {
        return page().scroll();
    }

    @Override
    protected boolean contentTouchDown(float x, float y, int pointer) {
        return page().touchDown(x, y, pointer);
    }

    @Override
    protected boolean contentTouchDragged(float x, float y, int pointer) {
        return page().touchDragged(x, y, pointer);
    }

    @Override
    protected boolean contentTouchUp(float x, float y, int pointer) {
        return page().touchUp(x, y, pointer);
    }

    @Override
    protected void contentCancel(int pointer) {
        page().cancel();
    }

    @Override
    protected void contentMouseMoved(float x, float y) {
        page().mouseMoved(x, y);
    }

    @Override
    protected boolean contentKeyDown(int keycode) {
        if (tab > TAB_OTHER) return false; // the pages under OTHER only go BACK
        int prev = tall ? Input.Keys.LEFT : Input.Keys.UP;
        int next = tall ? Input.Keys.RIGHT : Input.Keys.DOWN;
        if (keycode == Input.Keys.PAGE_UP || keycode == prev) {
            if (tab > 0) showTab(tab - 1, true);
            return true;
        }
        if (keycode == Input.Keys.PAGE_DOWN || keycode == next) {
            if (tab < TAB_OTHER) showTab(tab + 1, true);
            return true;
        }
        return false;
    }
}
