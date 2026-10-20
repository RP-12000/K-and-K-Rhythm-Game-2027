package org.kelvinizer.screens.result;

import org.kelvinizer.library.ChapterMeta;
import org.kelvinizer.library.ConstantLabel;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.play.PlayResult;
import org.kelvinizer.play.RecordOutcome;
import org.kelvinizer.play.ShellBreakdown;
import org.kelvinizer.profile.AvatarSpec;
import org.kelvinizer.profile.DeviceSettings;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.Depth;
import org.kelvinizer.profile.Grades;
import org.kelvinizer.screens.shell.ShellFormat;
import org.kelvinizer.ui.Avatar;
import org.kelvinizer.ui.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * 结算画面要显示的全部数据：由一局的 {@link PlayResult} 和记录成绩后的变化 {@link RecordOutcome}，
 * 一次算出分数、评级、判定、贝壳和深度等。
 * 所有字符串都在上升转场遮住画面时建好，画面只负责动画，渲染时不再分配内存。
 *
 * <p>Everything the DIVE LOG shows, derived once from a {@link PlayResult} and its
 * {@link RecordOutcome} (DESIGN §6.1, §6.6, §11, §12). The screen only animates these values;
 * every string is built here (in {@code present}, while the ASCENT covers the screen) so the
 * render loop never allocates.
 */
public final class ResultModel {
    /** Best-line variants (DESIGN §6.2 "Best line", §6.6). */
    public enum BestKind { NEW_BEST, BEST, FIRST_DIVE, AUTO, NONE }

    /** Minimum non-LOST hits before the offset hint is offered (§6.5). */
    public static final int HINT_MIN_HITS = 50;
    /** Mean offset (ms) above which the offset hint is offered (§6.5). */
    public static final float HINT_MIN_OFFSET = 25f;
    /** Score digits (DESIGN §18.2: 9, so {@code 100,010,812} fits when the bonuses pass 100 M). */
    public static final int SCORE_DIGITS = 9;
    /** Score cells: 9 digits + 2 group commas. */
    public static final int SCORE_CELLS = SCORE_DIGITS + (SCORE_DIGITS - 1) / 3;

    // ------------------------------------------------------------------ flags
    /** Autoplay run (nothing recorded). */
    public boolean autoplay;
    /** Tutorial run. */
    public boolean tutorial;
    /** True when the profile changed (false for autoplay). */
    public boolean recorded;
    /** First play of this chart + difficulty (FIRST DIVE). */
    public boolean firstPlay;
    /** New best score. */
    public boolean newBest;
    /** Recorded repeat play that did not beat the best (stamp uses 1 ring, ink3 best line). */
    public boolean noImprovement;
    /** No LOST: a full combo (the stamp turns purple; no badge since round 8). */
    public boolean fullCombo;
    /** Every note RESONANCE (ALL RESONANCE, shown by the R / R+ grade since round 8). */
    public boolean allResonance;
    /** Grade R+, the theoretical maximum (iridescent stamp, motes, constant shimmer). */
    public boolean rPlus;
    /** Depth phase (D) runs. */
    public boolean hasDepth;
    /** Shells phase (E) runs. */
    public boolean hasShells;
    /** The new depth unlocked avatar options ({@link #unlocks} is not empty): the UNLOCKED alert plays. */
    public boolean unlockUp;
    /**
     * Language = 中文: the shell and depth breakdown rows, the offset sheet's question, the offset hint and the note
     * shown in its place are Chinese (the hint and note since Kelvin 2026-10-18 21:37).
     */
    public boolean zh;
    /** The offset hint link is offered (P1, §6.5). */
    public boolean hasHint;

    // ------------------------------------------------------------------ song
    /** Song (may be null when the library no longer has it). */
    public SongMeta song;
    /** Difficulty played. */
    public Difficulty difficulty = Difficulty.SURFACE;
    /** Title and artist as shown. */
    public String title = "", artist = "";
    /** Difficulty chip text, e.g. {@code DEEP  15} for 15.9 (the constant's whole number; {@link #chipMark} follows). */
    public String chipText = "";
    /** Superscript after {@link #chipText}: the chart constant's mark ({@code ConstantLabel}). */
    public String chipMark = "";
    /** Layer numeral of the song's chapter ("02"), or empty. */
    public String layerNumeral = "";

    // ------------------------------------------------------------------ score
    /** Final score. */
    public int score;
    /** Score characters left to right ({@code 098,123,456}). */
    public final char[] scoreChars = new char[SCORE_CELLS];
    /** True for leading-zero cells (and the commas between them). */
    public final boolean[] scoreLead = new boolean[SCORE_CELLS];
    /** Lock order of each cell (digit index 0..8; commas lock with the digit before them). */
    public final int[] scoreLockIndex = new int[SCORE_CELLS];
    /** Score without the PERFECT bonus (0..100,010,000). */
    public int baseScore;
    /** PERFECT count (RESONANCE within ±33 ms), i.e. the bonus points. */
    public int perfect;
    /** Longest resonance chain ({@code m_r}). */
    public int maxChain;
    /** Note count at the end of the chain line ({@code 1,693}) and the PERFECT label. */
    public String notesText = "", perfectLabel = "PERFECT";
    /**
     * Score breakdown (DESIGN §18.2 [P1], shown when the score is tapped): the accuracy part
     * {@code 100,000,000 · (r + 0.55e)/t}, the chain part {@code 10,000 · m_r/t} (the two add up
     * to the base exactly) and the PERFECT bonus.
     */
    public String breakdownChain = "", breakdownAccuracy = "", breakdownPerfect = "";
    /** Grade string ("R+".."F") or null (tutorial). */
    public String grade;
    /** Grade letter without "+" ("SS" for "SS+", "R" for R+). */
    public String gradeLetter = "";
    /** True when the grade carries the superscript "+". */
    public boolean gradePlus;
    /** Rings of the stamp impact (§12; 1 when there was no improvement; 0 for autoplay). */
    public int stampRings;
    /** Best line variant. */
    public BestKind bestKind = BestKind.NONE;
    /** Previous best score. */
    public int previousBest;
    /** Score minus previous best (signed). */
    public int bestDiff;
    /** Previous best, grouped ({@code 9,900,000}). */
    public String bestText = "";
    /** Signed grouped difference with an ASCII sign for the DIGITS set ({@code +123,456} / {@code -87,655}). */
    public String bestDiffText = "";

    // ------------------------------------------------------------------ judgements
    /** Counts per judgement (RESONANCE, ECHO, LOST). */
    public final int[] counts = new int[3];
    /** Notes in the chart (mines excluded). */
    public int totalNotes;
    /** Mines in the chart and touched. */
    public int minesTotal, minesTouched;
    /** Best combo. */
    public int maxCombo;
    /** Accuracy 0..1. */
    public float accuracy;
    /** Early / late counts. */
    public int early, late;
    /** Mean offset of non-LOST hits (ms, + = late). */
    public float meanOffset;
    /** Mean offset text, e.g. {@code +9 ms}. */
    public String meanText = "";
    /** Count texts for the early / late line. */
    public String earlyText = "", lateText = "";
    /** RESONANCE hits that are not PERFECT, split early / late (shown when PERFECT is tapped). */
    public int earlyResonance, lateResonance;
    /** Grouped count texts of {@link #earlyResonance} / {@link #lateResonance}. */
    public String earlyResonanceText = "", lateResonanceText = "";
    /** Accuracy in hundredths of a percent, truncated (9,912 = 99.12 %). */
    public int accuracyHundredths;
    /** Final accuracy text, truncated to 2 decimals ({@code 99.12%}). */
    public String accuracyText = "";
    /** Mines text ({@code 2 / 14}). */
    public String minesText = "";

    // ------------------------------------------------------------------ player frame and shell capsule
    /** Diver name. */
    public String name = "";
    /** Avatar of the diver. */
    public final Avatar.Spec avatar = new Avatar.Spec();
    /** Shells before and after the run. */
    public long shellsBefore, shellsAfter;
    /** Shells gained. */
    public int shellsGained;
    /** {@code +93} (the chip draws the shell icon after it). */
    public String shellsText = "";
    /** Itemised shell rows (label, value) for the popover; null entries are skipped. */
    public final List<String> shellRows = new ArrayList<>();
    /** Values of {@link #shellRows}. */
    public final List<String> shellRowValues = new ArrayList<>();
    /** Depth before and after (whole metres). */
    public int depthBefore, depthAfter;
    /** Metres deeper ({@code depthAfter - depthBefore}; never negative in practice). */
    public int depthGain;
    /** {@link #depthGain} after the ▼ in the player frame, e.g. {@code 212}. */
    public String depthGainText = "";
    /**
     * Depth breakdown rows (tap the player frame, round 7): DEPTH {@code -2,486 → -2,698 m}, CHART DEPTH, DST 10 and
     * LOCAL (the meta line at the top right until 2026-10-19), labels in the language like the shell breakdown.
     */
    public final List<String> depthRows = new ArrayList<>();
    /** Values of {@link #depthRows}. */
    public final List<String> depthRowValues = new ArrayList<>();
    /** Avatar options unlocked by the new depth. */
    public final List<String> unlocks = new ArrayList<>();
    /** Note in the offset hint's place (autoplay / tutorial; the profile strip's until 2026-10-19). */
    public String stripNote = "";

    // ------------------------------------------------------------------ offset hint
    /** Hint text, e.g. {@code YOU'RE HITTING LATE (+38 ms) · ADJUST} (中文 {@code 你打得偏晚（+38 ms） · 调整}). */
    public String hintText = "";
    /** Sheet question, e.g. {@code SHIFT CHART OFFSET FOR SPEAKER FROM +12 TO +50 ms?}. */
    public String hintQuestion = "";
    /** Offset before and suggested (ms). */
    public int offsetOld, offsetNew;

    /**
     * Fills the model.
     *
     * @param r       the run
     * @param o       its record outcome (never null from Main)
     * @param song    the song (null allowed)
     * @param chapter the song's chapter (null allowed)
     * @param p       the active diver (null allowed)
     * @param device  device settings (audio offset for the hint; null allowed)
     */
    public void set(PlayResult r, RecordOutcome o, SongMeta song, ChapterMeta chapter, Profile p,
                    DeviceSettings device) {
        this.song = song;
        tutorial = r.tutorial() || o.tutorial();
        autoplay = r.autoplay() || (!o.recorded() && !tutorial);
        recorded = o.recorded();
        zh = p != null && p.settings != null && p.settings.zh();
        difficulty = r.difficulty() != null ? r.difficulty() : Difficulty.SURFACE;

        title = r.title() != null && !r.title().isEmpty() ? r.title() : (song != null ? song.title() : "");
        artist = r.artist() != null ? r.artist() : (song != null ? song.artist() : "");
        chipText = Theme.difficultyName(difficulty) + "  " + ConstantLabel.base(r.constantText());
        chipMark = ConstantLabel.mark(r.constantText());
        layerNumeral = chapter != null ? chapter.numeral() : "";
        // the result screen is English in both languages (Kelvin 2026-10-18 16:45 「结算界面的英文也全部保留」),
        // except the shell breakdown and the offset sheet below (17:22); no DIVE LOG header line since 17:22

        // score -----------------------------------------------------------------------------
        score = r.score();
        baseScore = r.baseScore();
        perfect = r.perfect();
        maxChain = r.maxChain();
        fillScoreCells(score);

        grade = tutorial ? null : (o.grade() != null ? o.grade() : Grades.gradeOf(score, r.totalNotes()));
        rPlus = "R+".equals(grade);
        if (grade != null) {
            gradePlus = grade.endsWith("+");
            gradeLetter = gradePlus ? grade.substring(0, grade.length() - 1) : grade;
        } else {
            gradePlus = false;
            gradeLetter = "";
        }
        previousBest = o.previousBest();
        bestDiff = score - previousBest;
        bestText = group(previousBest);
        bestDiffText = (bestDiff >= 0 ? "+" : "-") + group(Math.abs(bestDiff));
        firstPlay = recorded && !tutorial && o.isFirstPlay();
        newBest = recorded && !tutorial && o.isNewBest();
        noImprovement = recorded && !tutorial && !firstPlay && !newBest;
        if (tutorial) bestKind = BestKind.NONE;
        else if (autoplay) bestKind = BestKind.AUTO;
        else if (firstPlay) bestKind = BestKind.FIRST_DIVE;
        else if (newBest) bestKind = BestKind.NEW_BEST;
        else bestKind = BestKind.BEST;
        if (autoplay || grade == null) stampRings = 0;
        else if (noImprovement) stampRings = 1;
        else stampRings = Theme.gradeRings(grade);

        // judgements ------------------------------------------------------------------------
        counts[0] = r.count(Judgement.RESONANCE);
        counts[1] = r.count(Judgement.ECHO);
        counts[2] = r.count(Judgement.LOST);
        totalNotes = Math.max(r.totalNotes(), counts[0] + counts[1] + counts[2]);
        minesTotal = r.minesTotal();
        minesTouched = r.minesTouched();
        maxCombo = r.maxCombo();
        accuracy = r.accuracy();
        early = r.early();
        late = r.late();
        meanOffset = r.meanOffsetMs();
        earlyText = Integer.toString(early);
        lateText = Integer.toString(late);
        earlyResonance = r.earlyResonance();
        lateResonance = r.lateResonance();
        earlyResonanceText = group(earlyResonance);
        lateResonanceText = group(lateResonance);
        accuracyHundredths = (int) Math.floor(accuracy * 10000.0 + 0.01);
        StringBuilder acc = new StringBuilder(12);
        appendPercent(acc, accuracyHundredths);
        accuracyText = acc.toString();
        notesText = group(totalNotes);
        long chainPart = r.totalNotes() > 0 ? Judgement.chainScore(maxChain, totalNotes) : 0L;
        // the accuracy part is the rest of the base, so the two parts add up to it exactly
        // (floor(chain) + floor(accuracy) may be one short of floor(chain + accuracy))
        long accPart = Math.max(0L, baseScore - chainPart);
        breakdownChain = groupLong(chainPart);
        breakdownAccuracy = groupLong(accPart);
        breakdownPerfect = "+" + group(perfect);
        minesText = minesTouched + " / " + minesTotal;
        int mean = Math.round(meanOffset);
        meanText = (mean > 0 ? "+" : (mean < 0 ? "–" : "±")) + Math.abs(mean) + " ms";
        fullCombo = r.fullCombo() || (totalNotes > 0 && maxCombo >= totalNotes);
        allResonance = r.allResonance();

        // player frame and shell capsule ----------------------------------------------------
        name = p != null && p.name != null ? p.name : "";
        if (p != null && p.avatar != null) {
            AvatarSpec a = p.avatar;
            avatar.set(a.seed, a.shape, a.pattern, a.palette, a.glyph, a.frame,
                name.isEmpty() ? 'K' : name.charAt(0));
        } else {
            avatar.set(1L, 0, 0, 0, 1, 0, 'K');
        }
        shellsBefore = o.shellsBefore();
        shellsAfter = o.shellsAfter();
        shellsGained = o.shellsGained();
        shellsText = "+" + group(shellsGained);
        hasShells = !autoplay && shellsGained > 0;
        shellRows.clear();
        shellRowValues.clear();
        ShellBreakdown e = o.shellBreakdown();
        addShellRow(zh ? "基础" : "BASE", e.base);
        addShellRow(zh ? "首次下潜" : "FIRST DIVE", e.firstPlay);
        addShellRow(zh ? "新纪录" : "NEW BEST", e.newBest);
        addShellRow(zh ? "全连" : "FULL COMBO", e.fullCombo);
        addShellRow(zh ? "全共鸣" : "ALL RESONANCE", e.allResonance);
        addShellRow(zh ? "今日首次下潜" : "FIRST DIVE TODAY", e.daily);
        addShellRow(zh ? "新手教程" : "TUTORIAL", e.tutorial);
        unlocks.clear();
        unlocks.addAll(o.unlocks());

        hasDepth = recorded && !tutorial && !autoplay;
        depthBefore = o.depthBefore();
        depthAfter = o.depthAfter();
        depthGain = depthAfter - depthBefore;
        depthGainText = group(Math.abs(depthGain));
        // depths read negative (below the surface); DST 10 is the Profile tab's name in both languages
        depthRows.clear();
        depthRowValues.clear();
        if (hasDepth) {
            String after = group(-depthAfter) + " m";
            addDepthRow(zh ? "深度" : "DEPTH", depthGain != 0 ? group(-depthBefore) + " → " + after : after);
            addDepthRow(zh ? "谱面深度" : "CHART DEPTH", group(-Depth.meters(o.playDepth())) + " m");
            if (o.topTenIndex() >= 0) addDepthRow("DST 10", "#" + (o.topTenIndex() + 1));
            if (o.localCount() >= 2 && o.localRank() > 0) {
                addDepthRow(zh ? "本机排名" : "LOCAL", "#" + o.localRank() + " / " + o.localCount());
            }
        }
        unlockUp = hasDepth && !unlocks.isEmpty();
        if (autoplay) stripNote = zh ? "AUTOPLAY  ·  不记录成绩" : "AUTOPLAY  ·  NOT RECORDED";
        else if (tutorial) stripNote = zh ? "下一步  ·  在地图上选一首歌" : "NEXT  ·  PICK A SONG ON THE MAP";
        else stripNote = "";

        // offset hint (P1) ------------------------------------------------------------------
        int hits = counts[0] + counts[1];
        hasHint = !autoplay && device != null && hits >= HINT_MIN_HITS && Math.abs(meanOffset) > HINT_MIN_OFFSET;
        if (hasHint) {
            offsetOld = device.audioOffsetMs();
            offsetNew = Math.max(DeviceSettings.AUDIO_OFFSET_MIN,
                Math.min(DeviceSettings.AUDIO_OFFSET_MAX, offsetOld + mean));
            if (zh) {
                hintText = (mean > 0 ? "你打得偏晚（" : "你打得偏早（") + signed(mean) + " ms）  ·  调整";
                hintQuestion = "把" + ShellFormat.outputName(device.activeOutput) + "的谱面延时从 " + signed(offsetOld)
                    + " 改成 " + signed(offsetNew) + " ms？";
            } else {
                hintText = (mean > 0 ? "YOU'RE HITTING LATE (" : "YOU'RE HITTING EARLY (") + signed(mean)
                    + " ms)  ·  ADJUST";
                hintQuestion = "SHIFT CHART OFFSET FOR " + device.activeOutputName() + " FROM " + signed(offsetOld)
                    + " TO " + signed(offsetNew) + " ms?";
            }
            if (offsetNew == offsetOld) hasHint = false;
        }
    }

    /**
     * Fills {@link #scoreChars}, {@link #scoreLead} and {@link #scoreLockIndex} for a score drawn
     * as {@link #SCORE_DIGITS} grouped digits ({@code 098,123,456}, {@code 100,000,812}).
     */
    void fillScoreCells(int value) {
        int v = Math.max(0, value);
        int sig = 1;
        for (int t = v; t >= 10; t /= 10) sig++;
        int cell = 0;
        for (int j = 0; j < SCORE_DIGITS; j++) {
            boolean lead = j < SCORE_DIGITS - sig;
            if (j > 0 && (SCORE_DIGITS - j) % 3 == 0) {
                // group comma: dim with the leading digit left of it (as Fonts.drawDigits); locks with it
                scoreChars[cell] = ',';
                scoreLead[cell] = j - 1 < SCORE_DIGITS - sig;
                scoreLockIndex[cell] = j - 1;
                cell++;
            }
            int pow = 1;
            for (int k = 0; k < SCORE_DIGITS - 1 - j; k++) pow *= 10;
            scoreChars[cell] = (char) ('0' + (v / pow) % 10);
            scoreLead[cell] = lead;
            scoreLockIndex[cell] = j;
            cell++;
        }
    }

    /** Appends {@code hundredths} as a percentage with two decimals ({@code 99.12%}); no allocation. */
    public static StringBuilder appendPercent(StringBuilder sb, int hundredths) {
        int v = Math.max(0, hundredths);
        org.kelvinizer.ui.Fonts.appendInt(sb, v / 100);
        int f = v % 100;
        sb.append('.').append((char) ('0' + f / 10)).append((char) ('0' + f % 10)).append('%');
        return sb;
    }

    private void addDepthRow(String label, String value) {
        depthRows.add(label);
        depthRowValues.add(value);
    }

    private void addShellRow(String label, int value) {
        if (value <= 0) return;
        shellRows.add(label);
        shellRowValues.add("+" + value);
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : (v < 0 ? "–" + (-v) : "0");
    }

    private static String group(int v) {
        StringBuilder sb = new StringBuilder(12);
        org.kelvinizer.ui.Fonts.appendGrouped(sb, v);
        return sb.toString();
    }

    private static String groupLong(long v) {
        StringBuilder sb = new StringBuilder(14);
        org.kelvinizer.ui.Fonts.appendGrouped(sb, v);
        return sb.toString();
    }
}
