package org.kelvinizer.gameplay;

import org.kelvinizer.gameplay.GuidingText.Anchor;
import org.kelvinizer.gameplay.GuidingText.Style;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.profile.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 教学关卡的全部定时字幕，英文和中文各一套，按语言设置选用；拍点和位置按 Kelvin 手写的谱面（2026-10-17）手工排好。
 * The tutorial's timed captions (DESIGN §10.2, §17.3) for {@code Introduction/Surface.json}: 120 BPM,
 * {@code offsetMs} 27. The chart is hand-charted by the owner (2026-10-17, 102 notes); the old v3 generator
 * no longer owns it or this class. The captions are timed and placed by hand to the chart: a
 * centred caption is gone before notes appear, and a side or half-screen plate sits over lanes that stay free while it
 * shows. After changing the chart or a caption, check that no caption plate covers an unjudged
 * note at 16:9, 20:9 and 4:3, EN and 中文.
 *
 * <p>Verse 1 has no notes: the HUD tour (corner HUD; the pause label follows PAUSE INPUT), the judgements
 * with EARLY / LATE, and the two lines. Then one lesson per mechanic: ground DROPs and TIDEs centred before their
 * notes; sky DROPs, STREAMs, TIDE STREAMs, MINEs and LANE MINEs on plates beside them; and a TIDE held in mid-air as
 * a judgement line (DROPs land on it and jump to the line as they are judged, owner 2026-10-16). The gold sync
 * pearls, the chorus TIDEs with their TIDE + DROP joints and the STREAMs under DROPs play without a caption.
 * Afterimages and moving notes are only mentioned (one side plate in the outro; owner 2026-10-12: basics only). The
 * requiem plays over the held TIDE STREAM pair of the break; story, requiem and dialogue keep the owner's copy.
 *
 * <p>{@code zh} picks the Chinese text of every caption (Language = 中文); positions and beats are the
 * same. Judgement words and the EARLY / LATE tags stay English, as in the HUD. Beat windows used by
 * {@link IntroductionScreen} for its demos are exposed as constants.
 */
public final class IntroductionGuidelines {
    /** HUD tour (beats): the score label arrives on the verse-1 hit; one label per beat after it. */
    public static final float TOUR_START = 32f;
    /** Every tour label fully visible until here, gone a beat later (40.5). */
    public static final float TOUR_END = 39.5f;
    /** The pause label (top right), worded for the PAUSE INPUT mode: DOUBLE-TAP HERE TO PAUSE by default. */
    public static final float PAUSE_START = 33f;
    /** COMBO · CENTRE and the ghost combo. */
    public static final float COMBO_START = 34f;
    /** SONG &amp; DIFFICULTY · BOTTOM LEFT. */
    public static final float CHIP_START = 35f;
    /** TIME · BOTTOM RIGHT. */
    public static final float TIME_START = 36f;
    /** The pause control plays its double-tap hint every 1.6 s from here (never pausing; double-tap mode only) ... */
    public static final float PAUSE_DEMO_START = 34f;
    /** ... until the tour labels start to fade. */
    public static final float PAUSE_DEMO_END = 39.5f;
    /** Judgement lines (RESONANCE, ECHO, LOST, EARLY / LATE one beat apart) with demo hits beside them. */
    public static final float GRADES_START = 40.5f;
    /** End of the judgement lines (gone half a beat later, when the two lines are introduced). */
    public static final float GRADES_END = 52f;
    /** The EARLY / LATE line. */
    public static final float TIMING_START = 43.5f;
    /** The centre word shows ECHO with an EARLY or LATE tag every 1.5 s from here to GRADES_END. */
    public static final float EARLY_LATE_START = 44f;
    /** NOTES FALL TO THE BOTTOM OR RISE TO THE SURFACE. */
    public static final float LINES_START = 52.5f;
    /** Ghost DROP heads fall to the ground line and rise to the sky line (2-beat loop) ... */
    public static final float FALL_RISE_START = 53f;
    /** ... until the line instruction fades. */
    public static final float FALL_RISE_END = 58f;
    /** Zone hint pulse and forced stray dots from HALF THE SCREEN EACH ... */
    public static final float ZONES_START = 58.5f;
    /** ... through the DROP, TIDE and STREAM lessons (the last sky STREAM ends at 123). */
    public static final float ZONES_END = 123f;
    /** Two demo DROP heads beside a GOLD PEARLS heading: none (−1), the sync pearls play without a caption ... */
    public static final float SYNC_DEMO_START = -1f;
    /** ... (−1). */
    public static final float SYNC_DEMO_END = -1f;
    /** The zone hint pulses on for the TIDE STREAM lesson (from its caption) ... */
    public static final float TIDE_ZONE_START = 120f;
    /** ... until the break (the requiem plays over the held TIDE STREAM pair). */
    public static final float TIDE_ZONE_END = 136f;
    /** And again for the chorus TIDEs (152, 156 and the TIDE + DROP joints at 160 and 164) ... */
    public static final float TIDE_ZONE2_START = 150f;
    /** ... until the last of them. */
    public static final float TIDE_ZONE2_END = 164f;
    /** Beat of two stacked ground TIDEs for the stack badge: none (−1), the chart has no stacked TIDEs. */
    public static final float STACK_BEAT = -1f;
    /** The stack badge's window: none (−1) ... */
    public static final float STACK_BADGE_START = -1f;
    /** ... (−1). */
    public static final float STACK_BADGE_END = -1f;
    /** Demo mine beside the MINES heading, touched by a ghost finger every 1.6 s ... */
    public static final float MINE_DEMO_START = 176.5f;
    /** ... until the first real mine appears (183; the heading stays until 189.5). */
    public static final float MINE_DEMO_END = 182.5f;
    /** The closed lanes' outlines pulse from the LANE MINES caption (as the ground lane mine appears) ... */
    public static final float LANE_MINE_HINT_START = 190f;
    /** ... until the sky lane mine's block ends. */
    public static final float LANE_MINE_HINT_END = 200f;
    /** Judgement beat of the first lane mine the hint outlines (4 beats). */
    public static final float LANE_MINE_BEAT = 192f;
    /** Lane id of that lane mine (ground line). */
    public static final int LANE_MINE_LANE = -2;
    /** Judgement beat of the second lane mine the hint outlines (4 beats). */
    public static final float LANE_MINE2_BEAT = 196f;
    /** Lane id of that lane mine (sky line). */
    public static final int LANE_MINE2_LANE = 2;

    /** The tour's pause label per PAUSE INPUT mode, in {@link Settings#pauseMode} order (hold, double tap, tap). */
    private static final String[] PAUSE_LABEL_EN = {
        "HOLD HERE TO PAUSE", "DOUBLE-TAP HERE TO PAUSE", "TAP HERE TO PAUSE"};
    private static final String[] PAUSE_LABEL_ZH = {"按住这里暂停", "双击这里暂停", "点击这里暂停"};

    private final List<GuidingText> guidelines = new ArrayList<>();
    private final List<GuidingText> view = Collections.unmodifiableList(guidelines);
    private final boolean zh;
    private final int pauseMode;

    /** Builds the English caption list once (double-tap pause). */
    public IntroductionGuidelines() {
        this(false);
    }

    /** Builds the caption list once, in Chinese when {@code zh} (Language = 中文), else in English; double tap. */
    public IntroductionGuidelines(boolean zh) {
        this(zh, Settings.PAUSE_DOUBLE_TAP);
    }

    /**
     * Builds the caption list once, in Chinese when {@code zh}, with the pause label worded for {@code pauseMode}
     * ({@link Settings#PAUSE_HOLD}, {@link Settings#PAUSE_DOUBLE_TAP} or {@link Settings#PAUSE_SINGLE_TAP}; others
     * read as the double tap).
     */
    public IntroductionGuidelines(boolean zh, int pauseMode) {
        this.zh = zh;
        this.pauseMode = pauseMode >= 0 && pauseMode < PAUSE_LABEL_EN.length ? pauseMode : Settings.PAUSE_DOUBLE_TAP;
        // 0-32: title and story (owner copy)
        add("WELCOME TO", "欢迎来到", 0f, 31f, 1f, 1f, 960f, 889f, 40f, Style.HEADING, Anchor.WORLD);
        add("Kelvinizer and Kathlina",
            "Kelvinizer and Kathlina", 0f, 31f, 1f, 1f, 960f, 787f, 72f, Style.STORY, Anchor.WORLD);
        add("Here lies the story of a boy and a girl, trapped in a dark world.",
            "这是一个男孩和一个女孩的故事，他们被困在一个黑暗的世界里。", 8f, 31f, 1f, 1f, 960f, 656f, 32f,
            Style.NARRATIVE, Anchor.WORLD);
        add("The only way out is to descend deeper into the unknown.",
            "唯一的出路，是向未知的更深处下潜。", 16f, 31f, 1f, 1f, 960f, 587f, 32f, Style.NARRATIVE, Anchor.WORLD);
        add("And to uncover their darkest secrets and the tragic truth.",
            "并揭开他们最深的秘密，和那个悲剧的真相。", 24f, 31f, 1f, 1f, 960f, 518f, 32f, Style.NARRATIVE,
            Anchor.WORLD);

        // 32-40.5: HUD tour, each label beside its corner (resolved by IntroductionScreen)
        add("SCORE · TOP LEFT", "分数 · 左上", TOUR_START, TOUR_END, 1f, 1f, 0f, 0f, 26f, Style.LABEL, Anchor.HUD_SCORE);
        add(PAUSE_LABEL_EN[this.pauseMode],
            PAUSE_LABEL_ZH[this.pauseMode], PAUSE_START, TOUR_END, 1f, 1f, 0f, 0f, 26f, Style.LABEL, Anchor.HUD_PAUSE);
        add("COMBO · CENTRE", "连击 · 中央", COMBO_START, TOUR_END, 1f, 1f, 960f, 733f, 26f, Style.LABEL, Anchor.WORLD);
        add("SONG & DIFFICULTY · BOTTOM LEFT",
            "曲目与难度 · 左下", CHIP_START, TOUR_END, 1f, 1f, 0f, 0f, 26f, Style.LABEL, Anchor.HUD_CHIP);
        add("TIME · BOTTOM RIGHT",
            "时间 · 右下", TIME_START, TOUR_END, 1f, 1f, 0f, 0f, 26f, Style.LABEL, Anchor.HUD_TIME);

        // 40.5-52.5: judgements and EARLY / LATE (demo hits beside the words, a demo ECHO with its tag at the centre)
        grade("— RIGHT ON THE BEAT (±66 ms)", "— 共鸣 · 正中节拍（±66 ms）", Judgement.RESONANCE, GRADES_START, 889f);
        grade("— CLOSE (±133 ms)", "— 回响 · 接近（±133 ms）", Judgement.ECHO, GRADES_START + 1f, 822f);
        grade("— MISSED", "— 迷失 · 未击中", Judgement.LOST, GRADES_START + 2f, 756f);
        timing("— TOO SOON / TOO LATE", "— 点早了 / 点晚了", TIMING_START, 689f);

        // 52.5-64: the two lines (ghost notes fall and rise; labels at the lines)
        add("NOTES FALL TO THE BOTTOM OR RISE TO THE SURFACE",
            "音符会落向水底判定线，或升向水面判定线", LINES_START, 58f, 0.5f, 0.5f, 960f, 600f, 30f, Style.BODY,
            Anchor.WORLD);
        add("SURFACE JUDGEMENT", "水面判定线", 53f, 63f, 1f, 1f, 960f, -58f, 26f, Style.LABEL, Anchor.SKY);
        add("BOTTOM JUDGEMENT", "水底判定线", 53f, 63f, 1f, 1f, 960f, 58f, 26f, Style.LABEL, Anchor.GROUND);

        // 58.5-64: each line takes its half of the screen
        add("HALF THE SCREEN EACH",
            "上下各占半个屏幕", ZONES_START, 63.5f, 0.5f, 0.5f, 960f, 711f, 40f, Style.HEADING, Anchor.WORLD);
        add("UPPER HALF: SURFACE JUDGEMENT · LOWER HALF: BOTTOM JUDGEMENT",
            "上半屏是水面判定线，下半屏是水底判定线", 58.5f, 63.5f, 0.5f, 0.5f, 960f, 600f, 30f, Style.BODY,
            Anchor.WORLD);

        // L1 Ground DROPs: caption 64-73.5, notes 76-80 (first visible 74)
        add("GROUND DROPS", "地面水滴", 64f, 73f, 0.5f, 0.5f, 960f, 711f, 40f, Style.HEADING, Anchor.WORLD);
        add("TAP THE LOWER HALF WHEN THEY LAND",
            "音符落到水底判定线时，点下半屏", 64f, 73f, 0.5f, 0.5f, 960f, 600f, 30f, Style.BODY, Anchor.WORLD);

        // L2 Sky DROPs: caption 80.5-88 (side plate over lanes +2..+4), notes 84-88 rise in lanes +1, -1, -4 / -2
        add("SKY DROPS", "天空水滴", 80.5f, 87.5f, 0.5f, 0.5f, -336f, 740f, 40f, Style.HEADING, Anchor.SAFE_RIGHT);
        add("THEY RISE:\nTAP THE UPPER HALF",
            "从下往上升：点上半屏", 80.5f, 87.5f, 0.5f, 0.5f, -336f, 600f, 30f, Style.BODY, Anchor.SAFE_RIGHT);

        // L5 TIDEs: caption 88.5-94, notes 96-104 (first visible 94)
        add("TIDES", "潮汐", 88.5f, 93.5f, 0.5f, 0.5f, 960f, 711f, 40f, Style.HEADING, Anchor.WORLD);
        add("TAP ANYWHERE IN ITS HALF OF THE SCREEN",
            "在它那半屏的任意位置点", 88.5f, 93.5f, 0.5f, 0.5f, 960f, 600f, 30f, Style.BODY, Anchor.WORLD);

        // L3 STREAMs: caption 104.5-110 (left-half plate while the first two fall in lanes +3 / +2; gone before the
        // lane -2 STREAM appears at 110), notes 108-123
        add("STREAMS", "水流", 104.5f, 109.5f, 0.5f, 0.5f, 480f, 740f, 40f, Style.HEADING, Anchor.WORLD);
        add("KEEP THE SAME FINGER DOWN\nUNTIL THE STREAM ENDS",
            "同一根手指按到底，中途不能换手", 104.5f, 109.5f, 0.5f, 0.5f, 480f, 600f, 30f, Style.BODY, Anchor.WORLD);

        // the sync notes (gold pearls) play without a caption: how to hit them is left implied

        // L7 TIDE STREAMs: caption 120-126.5 (right-half plate while the sky STREAMs in lanes -4 / -2 are held), notes
        // 128.25-150
        add("TIDE STREAMS", "潮汐水流", TIDE_ZONE_START, 126f, 0.5f, 0.5f, 1440f, 740f, 40f, Style.HEADING,
            Anchor.WORLD);
        add("KEEP ANY FINGER IN ITS HALF\n— SWAPPING IS FINE",
            "这半屏一直有手指按着就行，可以换手指", 120f, 126f, 0.5f, 0.5f, 1440f, 600f, 30f, Style.BODY,
            Anchor.WORLD);

        // 136-150: requiem over the held TIDE STREAM pair (break; the chorus TIDEs start at 152)
        add("Cherish the ones who accompanied us today\nLet go the ones determined to stay away",
            "珍惜今天陪伴我们的人\n放下那些执意离开的人", 136f, 142.5f, 0.5f, 0.5f, 960f, 622f, 34f, Style.NARRATIVE,
            Anchor.WORLD);
        add("Pray that the requiem puts to rest\nThe most miserable heart\nThe most agonized chest",
            "祈祷这首安魂曲\n抚平最悲惨的心\n安息最痛苦的胸膛", 143f, 149.5f, 0.5f, 0.5f, 960f, 600f, 34f,
            Style.NARRATIVE, Anchor.WORLD);

        // 152-182: chorus TIDEs, TIDE + DROP joints, STREAMs under DROPs: no caption

        // L8 MINEs: caption 176.5-190 (right-edge plate over lanes +3 / +4, free 174-190), mines 185-189 between the
        // DROPs 184-190; the demo mine sits left of the heading, in lane +3, until 183
        add("MINES", "水雷", MINE_DEMO_START, 189.5f, 0.5f, 0.5f, -175f, 822f, 40f, Style.HEADING, Anchor.SAFE_RIGHT);
        add("DON'T TOUCH:\nTHEY BREAK\nYOUR COMBO,\nNOT YOUR SCORE",
            "别碰：\n碰到会断连击，\n但不扣分", 176.5f, 189.5f, 0.5f, 0.5f, -230f, 578f, 30f, Style.BODY,
            Anchor.SAFE_RIGHT);

        // L8b LANE MINEs: caption 190-200 (left-edge plate over lanes -4 / -3), lane mines ground -2 192-196 and sky +2
        // 196-200 with DROPs beside them
        add("LANE MINES", "轨道水雷", LANE_MINE_HINT_START, 199.5f, 0.5f, 0.5f, 228f, 793f, 40f, Style.HEADING,
            Anchor.SAFE_LEFT);
        add("THEY CLOSE\nA WHOLE LANE.\nSTAY OUT\nWHILE IT'S RED",
            "它会封住整条轨道，\n变红的时候别碰", 190f, 199.5f, 0.5f, 0.5f, 228f, 600f, 30f, Style.BODY,
            Anchor.SAFE_LEFT);

        // L12 TIDE line: caption 200.5-214 (left plate over lanes -4..-2, above the sky TIDEs), notes 202-214: ground
        // TIDEs hover 300 above the line and DROPs in lane +1 land on them, then sky TIDEs 300 under the sky line with
        // DROPs in lane -1 (owner 2026-10-16)
        add("A TIDE CAN BE THE LINE",
            "潮汐也能当判定线", 200.5f, 213.5f, 0.5f, 0.5f, 376f, 996f, 40f, Style.HEADING, Anchor.SAFE_LEFT);
        add("TAP AS NOTES LAND ON IT:\nTHEY JUMP TO THE LINE",
            "音符碰到潮汐时就点，\n它会瞬移到判定线上", 200.5f, 213.5f, 0.5f, 0.5f, 376f, 867f, 30f, Style.BODY,
            Anchor.SAFE_LEFT);

        // L10 Other notes, only mentioned: caption 216.5-222 (left plate; lanes -4..-2 are free until the lane -2
        // STREAM appears at 222)
        add("MORE TO DISCOVER", "更多变化", 216.5f, 221.5f, 0.5f, 0.5f, 376f, 767f, 40f, Style.HEADING, Anchor.SAFE_LEFT);
        add("AFTERIMAGES,\nMOVING NOTES —\nYOU'LL MEET THEM IN SONGS",
            "残影、移动的音符……\n以后在曲目里慢慢认识", 216.5f, 221.5f, 0.5f, 0.5f, 376f, 600f, 30f, Style.BODY,
            Anchor.SAFE_LEFT);

        // 224-237: dialogue plates (owner copy): his over lanes -3..-1 (only the held lane -2 STREAM passes under it),
        // hers over lanes +2 / +3 (free 230-238)
        add("\"Is our story over?\nPlease don't leave me, Kathlina\nHow can I make you happy\nand stay with me\nforever......\"",
            "\"我们的故事结束了吗？\n请不要离开我，Kathlina\n我要怎样才能让你快乐\n永远留在我身边……\"", 224f, 235f, 1f,
            1f, 615f, 600f, 30f, Style.QUOTE, Anchor.WORLD);
        add("\"No, not yet.\nWe're searching for a\ndegenerate soul\ndown here.\"",
            "\"不，还没有。\n我们在这深处\n寻找一个\n堕落的灵魂。\"", 230f, 236f, 1f, 1f, 1440f, 600f, 30f,
            Style.QUOTE, Anchor.WORLD);

        // 238-248: dialogue over the closing chords, after the mines (owner copy; the chords pass behind it)
        add("\"YOUR degenerate soul, Kelvinizer\"",
            "\"是你的堕落灵魂，Kelvinizer\"", 238f, 247f, 1f, 1f, 960f, 733f, 64f, Style.STORY, Anchor.WORLD);
        add("\"Because YOU have to save yourself in order to free both of us\"",
            "\"因为你必须先拯救自己，才能让我们两个都得到自由\"", 240.5f, 247f, 1f, 1f, 960f, 618f, 32f,
            Style.NARRATIVE, Anchor.WORLD);

        // 248-251: finale
        add("K  and  K", "K  and  K", 248f, 251f, 0.25f, 1f, 960f, 667f, 140f, Style.FINALE, Anchor.WORLD);
    }

    private void add(String en, String zhText, float start, float end, float fadeIn, float fadeOut, float x,
                     float y, float cap, Style style, Anchor anchor) {
        guidelines.add(new GuidingText(zh ? zhText : en, start, end, fadeIn, fadeOut, x, y, cap, style, anchor,
            null));
    }

    /** A judgement line: the word in its HUD face (English in both languages), then the description. */
    private void grade(String en, String zhText, Judgement j, float start, float y) {
        guidelines.add(new GuidingText(zh ? zhText : en, start, GRADES_END, 1f, 0.5f, 960f, y, 28f, Style.GRADE,
            Anchor.WORLD, j));
    }

    /** The EARLY / LATE line: both tags in their colours (English in both languages), then the description. */
    private void timing(String en, String zhText, float start, float y) {
        guidelines.add(new GuidingText(zh ? zhText : en, start, GRADES_END, 1f, 0.5f, 960f, y, 28f, Style.TIMING,
            Anchor.WORLD, null));
    }

    /** @return true when the captions are the Chinese set. */
    public boolean zh() {
        return zh;
    }

    /** @return the PAUSE INPUT mode the pause label is worded for. */
    public int pauseMode() {
        return pauseMode;
    }

    /** The captions in start order (read only). */
    public List<GuidingText> getGuidelines() {
        return view;
    }
}
