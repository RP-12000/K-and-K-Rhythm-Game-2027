package org.kelvinizer.profile;

import org.kelvinizer.library.Difficulty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一位本地潜水员的存档（{@code profiles/<id>.json}）：名字、头像、贝壳、深度、买过的单曲、成绩记录、个人设置和地图位置等。
 * 只经由 {@link ProfileStore} 修改，或改完字段后调用 {@link ProfileStore#markDirty()}。
 *
 * <p>One local diver profile, stored as {@code profiles/<id>.json} (ARCHITECTURE §7, DESIGN §8.1
 * and Appendix A.11). Plain public fields, bound by {@link PlainJson}; derived values are methods,
 * so they are never serialised.
 *
 * <p>Mutate only through {@link ProfileStore} (records, shells, purchases) or set fields and call
 * {@link ProfileStore#markDirty()} (settings, map camera, flags).
 */
public class Profile {
    /**
     * Current schema version of profile files: 2 since the shells and depth of 2026-10-17, 3 since
     * that day's depth formula of 14:10, 4 since its correction at 15:22. Older files are migrated
     * once on load ({@link #migrateToSchema2()}, then {@link #migrateToSchema4()}).
     */
    public static final int SCHEMA_VERSION = 4;
    /**
     * Current score scale (ARCHITECTURE §18): 2 = the 100,000,000 scale of scoring v2. A file
     * without the field (or with 1) holds 10,000,000-scale scores and is migrated once on load.
     */
    public static final int SCORE_SCALE = 2;
    /** Entries kept in {@link #depthHistory}. */
    public static final int DEPTH_HISTORY_MAX = 30;
    /** Entries kept in {@link #recentPlays}. */
    public static final int RECENT_PLAYS_MAX = 20;
    /** {@link #flags} prefix of a story read on the map ({@code story:<story id>}). */
    public static final String FLAG_STORY = "story:";
    /**
     * {@link #flags} prefix of a revealed secret song or pack ({@code reveal:<song id>}, {@code reveal:pack:<title>}).
     */
    public static final String FLAG_REVEAL = "reveal:";

    /** Schema version of this file. */
    public int schemaVersion = SCHEMA_VERSION;
    /**
     * Score scale of {@link #records} and {@link #recentPlays}: absent / 0 / 1 = the old
     * 10,000,000 scale, {@link #SCORE_SCALE} = 100,000,000. New profiles start at
     * {@link #SCORE_SCALE}; see {@link #migrateScoreScale()}.
     */
    public int scoreScale;
    /** Unique id (file name). */
    public String id;
    /** Display name, 1–16 code points. */
    public String name;
    /** Creation time (ms since epoch). */
    public long createdAt;
    /** Time of the last recorded play (ms since epoch, 0 = never). */
    public long lastPlayedAt;
    /** Avatar parameters (seed, shape, pattern, palette, glyph, frame). */
    public AvatarSpec avatar = new AvatarSpec();
    /** Shells (贝壳) to spend on singles; earned per play like the former EXP. */
    public long shells;
    /**
     * Schema 1 only: the EXP of files from before 2026-10-17, moved 1:1 into {@link #shells} by
     * {@link #migrateToSchema2()} and always 0 afterwards.
     */
    public long exp;
    /** Cached player depth in metres, unrounded (recomputed on load and after each play). */
    public float depth;
    /** Song ids bought with shells (and, from the migration, every song played before); RESET RECORDS empties it. */
    public List<String> ownedSongs = new ArrayList<>();
    /** Recorded plays (excludes autoplay and the tutorial). */
    public int playCount;
    /** RESONANCE + ECHO hits over all recorded plays. */
    public long totalNotesHit;
    /**
     * The tutorial counts as seen: the map labels it REPLAY and its SKIP shows at once. Settings →
     * RESET RECORDS clears it; the first-time shells are tracked by {@link #tutorialExpGranted}.
     */
    public boolean tutorialSeen;
    /**
     * The tutorial's first-completion shells were granted (once per diver until RESET RECORDS; named for the
     * former EXP).
     */
    public boolean tutorialExpGranted;
    /** Calibration was completed at least once. */
    public boolean calibrated;
    /** Song id last selected on the map (may be null). */
    public String lastSongId;
    /** Difficulty name last selected on the map (may be null). */
    public String lastDifficulty;
    /** Cave map level the saved camera belongs to (empty: the hub). */
    public String mapLevel = "";
    /** True once the map camera below has been saved. */
    public boolean mapCamSaved;
    /** Saved map camera x. */
    public float mapCamX;
    /** Saved map camera y. */
    public float mapCamY;
    /** Saved map zoom. */
    public float mapZoom = 1f;
    /** MAIN in chapter mode (every chapter entrance on one layer) instead of story mode (Kelvin 2026-10-16). */
    public boolean mainChapterMode;
    /** Local day (yyyymmdd) of the last completed play, 0 = never (daily shell bonus). */
    public int lastPlayDay;
    /** Diver-scoped settings. */
    public Settings settings = new Settings();
    /** Records: song id → difficulty name ({@link Difficulty#name()}) → record. */
    public Map<String, Map<String, ChartRecord>> records = new LinkedHashMap<>();
    /** Player depth after each recorded play, oldest first, at most {@link #DEPTH_HISTORY_MAX}. */
    public List<DepthPoint> depthHistory = new ArrayList<>();
    /** Recent recorded plays, newest first, at most {@link #RECENT_PLAYS_MAX}. */
    public List<RecentPlay> recentPlays = new ArrayList<>();
    /** Aggregate statistics. */
    public Stats stats = new Stats();
    /**
     * Chapter ids whose first-visit reveal has played. Kept in the save schema (DESIGN Appendix A)
     * for the [P1] first-visit sonar sweep (DESIGN §3.11); nothing reads or writes it yet.
     */
    public List<String> seenChapters = new ArrayList<>();
    /** One-shot UI flags (coach marks shown, reminders shown, …). */
    public List<String> flags = new ArrayList<>();

    /** A point of the depth history. */
    public static class DepthPoint {
        /** Time (ms since epoch). */
        public long t;
        /** Player depth in metres after that play. */
        public float m;

        /** Creates an empty point (JSON binding). */
        public DepthPoint() {
        }

        /** Creates a point. */
        public DepthPoint(long t, float m) {
            this.t = t;
            this.m = m;
        }
    }

    /** One entry of the play log. */
    public static class RecentPlay {
        /** Song id. */
        public String songId;
        /** Difficulty name. */
        public String difficulty;
        /** Score (100,000,000 scale once {@link Profile#scoreScale} is current). */
        public int score;
        /** Grade string. */
        public String grade;
        /** Accuracy 0..1. */
        public float accuracy;
        /** FULL COMBO. */
        public boolean fullCombo;
        /** ALL RESONANCE. */
        public boolean allResonance;
        /** Time (ms since epoch). */
        public long t;

        /** Creates an empty entry (JSON binding). */
        public RecentPlay() {
        }
    }

    /** Aggregate statistics. */
    public static class Stats {
        /** Total time spent in recorded plays (ms). */
        public long totalPlayMs;
        /** Recorded plays that were FULL COMBO. */
        public int fullCombos;
        /** Recorded plays that were ALL RESONANCE. */
        public int allResonance;

        /** Creates zero stats. */
        public Stats() {
        }
    }

    /** Creates an empty profile (JSON binding); use {@link ProfileStore#create(String)} instead. */
    public Profile() {
    }

    /** @return the avatar seed. */
    public int avatarSeed() {
        return avatar != null ? avatar.seed : 0;
    }

    /** @return the cached player depth in whole metres, as displayed. */
    public int depthMeters() {
        return Depth.meters(depth);
    }

    /** @return true when the song was bought (or played before shells existed). */
    public boolean owns(String songId) {
        return songId != null && ownedSongs != null && ownedSongs.contains(songId);
    }

    /** @return the record for a chart, or null (read-only use; never creates). */
    public ChartRecord record(String songId, Difficulty d) {
        if (songId == null || d == null || records == null) return null;
        Map<String, ChartRecord> bySong = records.get(songId);
        return bySong == null ? null : bySong.get(d.name());
    }

    /**
     * @return the record for a chart, creating it when missing
     * @throws IllegalArgumentException when {@code songId} or {@code d} is null (a null map key
     *                                  would make every later save of the profile fail)
     */
    ChartRecord recordOrCreate(String songId, Difficulty d) {
        if (songId == null || d == null) throw new IllegalArgumentException("chart record needs a song id and difficulty");
        if (records == null) records = new LinkedHashMap<>();
        Map<String, ChartRecord> bySong = records.get(songId);
        if (bySong == null) {
            bySong = new LinkedHashMap<>();
            records.put(songId, bySong);
        }
        ChartRecord r = bySong.get(d.name());
        if (r == null) {
            r = new ChartRecord();
            bySong.put(d.name(), r);
        }
        return r;
    }

    /** @return number of charts with at least one recorded play. */
    public int playedChartCount() {
        int n = 0;
        for (Map<String, ChartRecord> bySong : records.values()) {
            for (ChartRecord r : bySong.values()) if (r.plays > 0) n++;
        }
        return n;
    }

    /** @return number of charts with a FULL COMBO. */
    public int fullComboChartCount() {
        int n = 0;
        for (Map<String, ChartRecord> bySong : records.values()) {
            for (ChartRecord r : bySong.values()) if (r.fullCombo) n++;
        }
        return n;
    }

    /** @return number of charts with ALL RESONANCE. */
    public int allResonanceChartCount() {
        int n = 0;
        for (Map<String, ChartRecord> bySong : records.values()) {
            for (ChartRecord r : bySong.values()) if (r.allResonance) n++;
        }
        return n;
    }

    /** @return true when the one-shot UI flag is set. */
    public boolean hasFlag(String flag) {
        return flags.contains(flag);
    }

    /**
     * Sets a one-shot UI flag.
     *
     * @return true when it was not set before (call {@link ProfileStore#markDirty()})
     */
    public boolean setFlag(String flag) {
        if (flag == null || flags.contains(flag)) return false;
        flags.add(flag);
        return true;
    }

    /**
     * Converts the scores of an old-scale profile ({@link #scoreScale} &lt; {@link #SCORE_SCALE})
     * to the 100,000,000 scale once (ARCHITECTURE §18): every record's best and recent scores
     * × 10 (capped at 100,000,000; a PURE run lands on exactly 100,000,000) with the best grade
     * recomputed, the play log's scores × 10 with their grades recomputed; then
     * {@code scoreScale = SCORE_SCALE}. Idempotent: a profile already on the new scale is left
     * alone. The depths are recomputed by {@code ProfileStore} after loading.
     *
     * @return true when the profile changed (save it)
     */
    public boolean migrateScoreScale() {
        if (scoreScale >= SCORE_SCALE) return false;
        if (records != null) {
            for (Map<String, ChartRecord> bySong : records.values()) {
                if (bySong == null) continue;
                for (ChartRecord r : bySong.values()) if (r != null) r.migrateFromOldScale();
            }
        }
        if (recentPlays != null) {
            for (RecentPlay rp : recentPlays) {
                if (rp == null) continue;
                boolean pure = ChartRecord.wasPureOnOldScale(rp.grade, rp.allResonance, rp.score);
                rp.score = ChartRecord.toNewScale(rp.score, pure);
                if (rp.grade != null) rp.grade = Grades.gradeOf(rp.score);
            }
        }
        scoreScale = SCORE_SCALE;
        return true;
    }

    /**
     * Moves a schema 1 profile to the shells and depth of 2026-10-17, once: its EXP becomes shells
     * 1:1, every song with a recorded play counts as owned (a single played before needs no
     * buying), and the stored grades are recomputed from the scores with the fifteen grades of
     * {@link Grades} (a former PURE becomes R; R+ needs a new run). The old rating and rating
     * history are dropped by the reader (unknown keys); the depths are recomputed by
     * {@code ProfileStore}. Equipped avatar options are kept. Call after
     * {@link #migrateScoreScale()}.
     *
     * @return true when the profile changed (save it)
     */
    public boolean migrateToSchema2() {
        if (schemaVersion >= 2) return false;
        if (exp > 0) shells += exp;
        exp = 0;
        if (ownedSongs == null) ownedSongs = new ArrayList<>();
        if (records != null) {
            for (Map.Entry<String, Map<String, ChartRecord>> e : records.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                for (ChartRecord r : e.getValue().values()) {
                    if (r == null) continue;
                    if (r.plays > 0 && !ownedSongs.contains(e.getKey())) ownedSongs.add(e.getKey());
                    if (r.bestGrade != null || r.plays > 0) r.bestGrade = Grades.gradeOf(r.bestScore);
                }
            }
        }
        if (recentPlays != null) {
            for (RecentPlay rp : recentPlays) {
                if (rp != null && rp.grade != null) rp.grade = Grades.gradeOf(rp.score);
            }
        }
        schemaVersion = 2;
        return true;
    }

    /**
     * Moves a schema 2 or 3 profile to the depth formula of 2026-10-17 (14:10, corrected at 15:22:
     * a score below 96,000,000 lowers the constant), once: the depth history holds depths of an
     * older formula, so it starts over. The chart depths and the player depth are recomputed by
     * {@code ProfileStore}.
     *
     * @return true when the profile changed (save it)
     */
    public boolean migrateToSchema4() {
        if (schemaVersion >= 4) return false;
        if (depthHistory != null) depthHistory.clear();
        schemaVersion = 4;
        return true;
    }

    /** Repairs nulls and ranges after loading untrusted JSON (runs the schema migrations first). */
    void sanitize() {
        migrateToSchema2();
        migrateToSchema4();
        if (avatar == null) avatar = new AvatarSpec();
        avatar.sanitize();
        if (settings == null) settings = new Settings();
        settings.sanitize();
        if (records == null) records = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, ChartRecord>> e : new ArrayList<>(records.entrySet())) {
            Map<String, ChartRecord> bySong = e.getValue();
            if (e.getKey() == null || bySong == null) {
                records.remove(e.getKey());
                continue;
            }
            for (Map.Entry<String, ChartRecord> r : new ArrayList<>(bySong.entrySet())) {
                if (r.getValue() == null || Difficulty.parse(r.getKey()) == null) {
                    bySong.remove(r.getKey());
                } else {
                    r.getValue().sanitize();
                }
            }
        }
        if (depthHistory == null) depthHistory = new ArrayList<>();
        while (depthHistory.size() > DEPTH_HISTORY_MAX) depthHistory.remove(0);
        if (recentPlays == null) recentPlays = new ArrayList<>();
        while (recentPlays.size() > RECENT_PLAYS_MAX) recentPlays.remove(recentPlays.size() - 1);
        if (stats == null) stats = new Stats();
        if (seenChapters == null) seenChapters = new ArrayList<>();
        if (flags == null) flags = new ArrayList<>();
        if (ownedSongs == null) ownedSongs = new ArrayList<>();
        ownedSongs.removeAll(Collections.singleton((String) null));
        if (shells < 0) shells = 0;
        exp = 0;
        if (!(depth >= 0f) || Float.isInfinite(depth)) depth = 0f;
        if (tutorialSeen) tutorialExpGranted = true; // files from before the field existed
        if (mapZoom <= 0f || Float.isNaN(mapZoom)) mapZoom = 1f;
        schemaVersion = SCHEMA_VERSION;
    }
}
