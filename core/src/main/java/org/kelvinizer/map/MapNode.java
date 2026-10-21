package org.kelvinizer.map;

import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.profile.ChartRecord;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.Spring;

/**
 * 洞穴地图上的一个点：歌曲洞、剧情入口、章节竖井、航线洞口、上浮洞或未知信号。
 * 记着固定的世界坐标、当前潜水员各难度成绩的缓存、上锁和读过的状态，以及按下、选中弹跳、到达时亮起等动画。
 *
 * <p>One spot on the cave map (DESIGN §16): a song hole, story entrance, chapter shaft, hub route
 * hole, rise hole or uncharted contact. It keeps its fixed world position, the cached record state
 * of the active diver (song holes), its lock / read state, and its animation state (press, select
 * pop, ping answer, idle bob, jelly lag, arrival wake light, lock shake).
 *
 * <p>All state is plain fields updated in place; nothing allocates after construction.
 */
final class MapNode {
    /** Spot kinds. */
    static final int SONG = 0, STORY = 1, SHAFT = 2, ROUTE = 3, RISE = 4, CONTACT = 5, TUTORIAL = 6, SLOT = 7;

    /** The song (song holes and the tutorial; null otherwise or when it is missing). */
    final SongMeta song;
    /** The level data of this spot (null for generated grid slots). */
    final CaveData.Spot spot;
    /** One of the kinds above. */
    final int kind;
    /** True for the tutorial hole. */
    final boolean launch;
    /** Kept for the card and filter: 0 (one band per level). */
    final int band = 0;
    /** Index among the level's song holes (−1 for other spots). */
    final int order;
    /** Index in {@code MapWorld.all}. */
    int idx;
    /** Base world position (before bob and lag) and hole radius (ALL SONGS sets both every frame). */
    float x, y, r;
    /** ALL SONGS: no idle bob (the grid's rows stay straight under their names). */
    boolean still;

    // ------------------------------------------------------------------ record cache
    /** Per difficulty: chart present. */
    final boolean[] has = new boolean[4];
    /** Per difficulty: at least one recorded play. */
    final boolean[] played = new boolean[4];
    /** Per difficulty: a full combo at least once (the grade is drawn purple). */
    final boolean[] fullCombo = new boolean[4];
    /** Per difficulty: best grade index (0 = R+ … 14 = F), −1 when unplayed. */
    final int[] grade = new int[4];
    /** Per difficulty: best score. */
    final int[] best = new int[4];
    /** True when any difficulty was played. */
    boolean anyPlayed;

    // ------------------------------------------------------------------ cave state
    /** Locked: a required song is not cleared yet (gold padlock, no card). */
    boolean locked;
    /** Story read by the active diver (drawn dimmer). */
    boolean read;
    /** Secret song (special unlock) shown garbled: noise disc, garbled label, no card. */
    boolean glitch;
    /** ALL SONGS: the unmet unlock conditions of a locked song (names), shown in turn on the card. */
    String[] lockConds = new String[0];
    /** Required song nodes on this level (gold dotted lines to them). */
    MapNode[] needs = new MapNode[0];
    /** Display names of the required songs ({@code Song 3 · Song 4}). */
    String needsText = "";
    /** Arrival wake light 0..1 (X1 / X3: the light reaches this spot) and its delay (s). */
    float wake = 1f, wakeDelay;
    /** Lock shake time left (s) after a tap on a locked spot. */
    float shake;
    /** X5: still drawn locked until the gold flow arrives. */
    boolean unlocking;
    /** Unlock flash 0..1 (X5: the lock shatters). */
    float unlockFx;
    /** A single not bought yet (placed in no story chapter, not owned): HOLD TO BUY for {@link #price} shells. */
    boolean forSale;
    /** Price in shells when {@link #forSale}. */
    int price;

    // ------------------------------------------------------------------ animation
    /** Press scale (snappy) and select pop (bouncy). */
    final Spring press = new Spring(Spring.Preset.SNAPPY, 1f);
    final Spring pop = new Spring(Spring.Preset.BOUNCY, 1f);
    /** Jelly lag offset springs (P1); also carry a grid re-layout. */
    final Spring lagX = new Spring(Spring.Preset.JELLY, 0f), lagY = new Spring(Spring.Preset.JELLY, 0f);
    /** Ping answer flash 0..1 (decays over 300 ms). */
    float flash;
    /** Idle bob phase and period (P1). */
    final float bobPhase, bobPeriod;
    /** Rotation of the sonar dashes (degrees). */
    float dashAngle;
    /** Label glitch state (G2 on select). */
    final GlitchState labelGlitch = new GlitchState();
    /** Selection amount 0..1 (eases in/out). */
    float selected;
    /** True while a pointer presses this node. */
    boolean pressed;
    /** Per difficulty: arc sweep-fill 0..1 (the return animation; 1 = drawn full). */
    final float[] sweep = {1f, 1f, 1f, 1f};
    /** Delay before the arc sweep starts (s). */
    float sweepDelay;

    MapNode(CaveData.Spot spot, int kind, SongMeta song, int order, float x, float y, float r) {
        this.spot = spot;
        this.kind = kind;
        this.song = song;
        this.launch = kind == TUTORIAL;
        this.order = order;
        this.x = x;
        this.y = y;
        this.r = r;
        String key = song != null ? song.id() : spot != null ? spot.id : "slot" + x + "," + y;
        bobPhase = MapStyle.hash01(key + "bob") * MathUtils.PI2;
        bobPeriod = 3.2f + 1.2f * MapStyle.hash01(key + "period");
        dashAngle = 360f * MapStyle.hash01(key + "dash");
        for (int i = 0; i < 4; i++) grade[i] = -1;
    }

    /** True for a hole that leads somewhere when tapped (not a contact or slot). */
    boolean tappable() {
        return kind != SLOT;
    }

    /** True for a song hole. */
    boolean isSong() {
        return kind == SONG && song != null;
    }

    /** Sealed shaft / route (grate, red light). */
    boolean sealed() {
        return spot != null && spot.sealed;
    }

    /** Counts as cleared for path styling: played song, read story, open shaft or rise. */
    boolean cleared() {
        switch (kind) {
            case SONG:
                return anyPlayed;
            case STORY:
                return read;
            case SHAFT:
            case ROUTE:
                return !sealed();
            default:
                return true;
        }
    }

    /** Refreshes the record cache from {@code p} (null = no diver). Called on profile changes only. */
    void refresh(Profile p) {
        anyPlayed = false;
        for (int i = 0; i < 4; i++) {
            Difficulty d = Difficulty.byOrdinal(i);
            has[i] = song != null && song.has(d);
            ChartRecord r = (p != null && song != null && !launch) ? p.record(song.id(), d) : null;
            boolean pl = r != null && r.plays > 0;
            played[i] = pl;
            fullCombo[i] = pl && r.fullCombo;
            best[i] = pl ? r.bestScore : 0;
            grade[i] = pl ? gradeIndex(r.bestGrade) : -1;
            anyPlayed |= pl;
        }
        if (launch) anyPlayed = p != null && p.tutorialSeen;
    }

    private static int gradeIndex(String g) {
        return org.kelvinizer.profile.Grades.gradeIndex(g);
    }

    /** True when the song has no chart for the lens difficulty (still tappable). */
    boolean missing(Difficulty lens) {
        return kind == SONG && !has[lens.ordinal()];
    }

    /** True for a song never played on any difficulty (NEW dot, dashed rim). */
    boolean isNew() {
        return kind == SONG && !anyPlayed;
    }

    /**
     * Advances the animation.
     *
     * @param camVx camera velocity (world units / s) driving the jelly lag
     * @param wave  sonar wave strength at the node (ping answer when &gt; .5)
     * @param sel   true when this node is the selected one
     * @param reduceMotion disables bob and lag
     */
    void update(float dt, float camVx, float camVy, float wave, boolean sel, boolean reduceMotion) {
        press.setTarget(pressed ? 0.92f : 1f);
        press.update(dt);
        pop.update(dt);
        selected = Math.min(1f, Math.max(0f, selected + (sel ? dt / 0.2f : -dt / 0.2f)));
        if (sweepDelay > 0f) {
            sweepDelay -= dt;
        } else {
            for (int i = 0; i < 4; i++) if (sweep[i] < 1f) sweep[i] = Math.min(1f, sweep[i] + dt / MapStyle.ARC_SWEEP);
        }
        if (wakeDelay > 0f) wakeDelay -= dt;
        else if (wake < 1f) wake = Math.min(1f, wake + dt / 0.45f);
        if (shake > 0f) shake = Math.max(0f, shake - dt);
        if (unlockFx > 0f) unlockFx = Math.max(0f, unlockFx - dt / 1.2f);
        if (wave > 0.5f) flash = 1f;
        else flash = Math.max(0f, flash - dt / 0.3f);
        float speed = (sel ? 24f : 6f) * (pressed ? 4f : 1f);
        dashAngle = (dashAngle - speed * dt) % 360f;
        if (reduceMotion) {
            lagX.snap(0f);
            lagY.snap(0f);
        } else {
            lagX.setTarget(MathUtils.clamp(camVx * 0.012f, -24f, 24f));
            lagY.setTarget(MathUtils.clamp(camVy * 0.012f, -24f, 24f));
            lagX.update(dt);
            lagY.update(dt);
        }
    }

    /** Starts the select pop (1.0 → 1.18 → 1.06 on the bouncy spring). */
    void popSelect() {
        pop.snap(1f).setTarget(1.06f).impulse(5.5f);
    }

    /** Current world x including the lag and the lock shake. */
    float drawX() {
        float s = shake > 0f ? 10f * MathUtils.sin(shake * 60f) * Math.min(1f, shake / 0.15f) : 0f;
        return x + lagX.value() + s;
    }

    /** Current world y including bob and lag. */
    float drawY(float time, boolean reduceMotion) {
        float bob = reduceMotion || still || kind == SLOT ? 0f
            : 4f * MathUtils.sin(time * MathUtils.PI2 / bobPeriod + bobPhase);
        return y + bob + lagY.value();
    }

    /** Combined scale of press and pop. */
    float scale() {
        return press.value() * pop.value();
    }

    /** Alpha factor of the arrival wake. */
    float vis() {
        return 0.35f + 0.65f * wake;
    }
}
