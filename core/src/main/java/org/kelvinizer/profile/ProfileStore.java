package org.kelvinizer.profile;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Base64Coder;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongLibrary;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.play.ShellBreakdown;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.play.PlayResult;
import org.kelvinizer.play.RecordOutcome;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 本地多潜水员存档：建档、切换、改名、删除、导入导出，以及记录一局成绩（{@link #recordPlay}）。
 * 保存是原子的（先写 {@code .tmp} 再改名），有备份和损坏恢复；写盘在后台线程进行，画面不必等待磁盘。
 *
 * <p>Local multi-profile storage (ARCHITECTURE §7, DESIGN §8).
 *
 * <p>Layout under the root (Android {@code Gdx.files.local("profiles/")}, desktop
 * {@code Gdx.files.external("KandKGame/profiles/")}):
 * <pre>
 *   index.json   {schemaVersion, activeProfileId, firstRunDone, profiles:[{id,name,createdAt}]}
 *   &lt;id&gt;.json    one {@link Profile} per diver (max {@value #MAX_PROFILES})
 *   device.json  {@link DeviceSettings}
 *   &lt;id&gt;.json.scale1.bak  the profile as it was before its one-time score-scale migration
 *                         (ARCHITECTURE §18; written once, never rotated)
 * </pre>
 * Writes are atomic: the new bytes go to {@code <file>.tmp} (flushed and synced), the previous
 * file is copied to {@code <file>.bak}, then the tmp file is <em>renamed</em> over the file
 * ({@link java.io.File#renameTo}, atomic on Android and POSIX; on Windows, where a rename onto an
 * existing file fails, the old file is deleted first). The live file is therefore never
 * rewritten in place, so an interrupted save leaves either the old or the new version.
 *
 * <p>Recovery on load: a {@code .tmp} that parses is the newest complete save (it only exists
 * when a save was interrupted after the data was fully written) and wins; an unparsable
 * {@code .tmp} is a half-written scratch file and is deleted. Otherwise the file is read, and
 * when it fails to parse it is renamed {@code <file>.corrupt-<timestamp>} (never silently
 * overwritten) and the {@code .bak} is used instead.
 *
 * <p>Saves are debounced: {@link #markDirty()} only flags; {@link #update(float)} saves after a
 * quiet period (not while {@linkplain #setAutosaveSuspended suspended}, i.e. during a song), and
 * {@link #save()} (result, profile edits, settings close) saves at once.
 *
 * <p><b>Threads.</b> Use the store from the render thread only. A save serialises every dirty
 * file to bytes on the calling thread (a consistent snapshot), then one daemon writer thread
 * performs the {@code .tmp} / fsync / {@code .bak} / rename sequence above, in order, so the
 * frame never waits on the disk. A write that fails flags its file dirty again (picked up by the
 * next {@link #update}). {@link #saveNow()} is the synchronous variant for {@code pause()} /
 * {@code dispose()} (Android may kill the process right after): it saves and waits until every
 * queued write is on disk. {@link #dispose()} flushes and stops the writer.
 */
public final class ProfileStore {
    /** Maximum number of local profiles. */
    public static final int MAX_PROFILES = 8;
    /** Maximum name length in code points. */
    public static final int MAX_NAME_CODEPOINTS = 16;
    /** Prefix of profile export codes. */
    public static final String EXPORT_PREFIX = "KK1:";
    /** Seconds without further changes before {@link #update(float)} saves. */
    public static final float AUTOSAVE_QUIET_SECONDS = 3f;
    /** Seconds after the first unsaved change at which {@link #update(float)} saves anyway. */
    public static final float AUTOSAVE_MAX_SECONDS = 15f;

    private static final String TAG = "ProfileStore";
    private static final String INDEX = "index.json";
    private static final String DEVICE = "device.json";
    private static final int MAX_IMPORT_BYTES = 4 * 1024 * 1024;
    /**
     * Suffix of the one-time copy of an old-scale profile taken before its score migration
     * (ARCHITECTURE §18): {@code <id>.json.scale1.bak}. Unlike {@code <id>.json.bak} it is never
     * rotated by later saves (and it matches none of the {@code .json} / {@code .json.tmp} /
     * {@code .json.bak} names {@link #rebuildIndexFromFiles()} reads).
     */
    static final String SCALE1_BACKUP_SUFFIX = ".json.scale1.bak";
    /**
     * Suffix of the one-time copy of a schema 1 profile (EXP, rating, the old grades) taken before
     * its move to shells and depth ({@link Profile#migrateToSchema2()}); never rotated.
     */
    static final String SCHEMA1_BACKUP_SUFFIX = ".json.schema1.bak";

    private final FileHandle root;
    private final Map<String, Profile> profiles = new LinkedHashMap<>();
    private final List<Profile> profileView = new ArrayList<>();
    private final List<Profile> profileViewReadOnly = Collections.unmodifiableList(profileView);
    private final Set<String> dirtyProfiles = new HashSet<>();
    private final Random random = new Random();
    private Index index = new Index();
    private DeviceSettings device = new DeviceSettings();
    private SongLibrary library;
    private boolean indexDirty;
    private boolean deviceDirty;
    private float quietTimer;
    private float dirtyTimer;
    private boolean anyDirty;
    private int legacyImported = -1;
    private boolean loaded;
    private int revision;
    private boolean autosaveSuspended;
    /** True once {@link #dispose()} stopped the writer (later saves write inline). */
    private boolean disposedWriter;
    /** Ordered file writer (created on the first save, stopped by {@link #dispose()}). */
    private ExecutorService writer;
    /** Last queued write; waiting on it waits for every earlier one (single thread, FIFO). */
    private Future<?> lastWrite;
    /** Files whose background write failed (names as in {@link #markFileDirty}), drained on the render thread. */
    private final ConcurrentLinkedQueue<String> failedWrites = new ConcurrentLinkedQueue<>();

    /** index.json model. */
    public static class Index {
        /** Schema version. */
        public int schemaVersion = 1;
        /** Active profile id. */
        public String activeProfileId;
        /** True once the first-run flow finished. */
        public boolean firstRunDone;
        /** Profile list in display order. */
        public List<Entry> profiles = new ArrayList<>();

        /** One profile entry. */
        public static class Entry {
            /** Profile id. */
            public String id;
            /** Name (mirror of the profile's, for listing without loading). */
            public String name;
            /** Creation time. */
            public long createdAt;

            /** Creates an empty entry (JSON binding). */
            public Entry() {
            }
        }

        /** Creates an empty index (JSON binding). */
        public Index() {
        }
    }

    /** One row of a player's deepest charts (the TOP 10 tab). */
    public static final class TopEntry {
        /** Song id. */
        public final String songId;
        /** Difficulty. */
        public final Difficulty difficulty;
        /** Depth of the chart's best in metres. */
        public final float depth;
        /** Best score. */
        public final int score;
        /** Best grade. */
        public final String grade;

        TopEntry(String songId, Difficulty difficulty, float depth, int score, String grade) {
            this.songId = songId;
            this.difficulty = difficulty;
            this.depth = depth;
            this.score = score;
            this.grade = grade;
        }
    }

    /**
     * Order of the TOP 10 list: depth descending, then score descending, then song id and
     * difficulty (so equal entries have a stable, deterministic order).
     */
    private static final Comparator<TopEntry> TOP_ORDER = new Comparator<TopEntry>() {
        @Override
        public int compare(TopEntry a, TopEntry b) {
            return compareTop(a.depth, a.score, a.songId, a.difficulty, b.depth, b.score, b.songId, b.difficulty);
        }
    };

    /**
     * A profile's position among local profiles on one chart. A plain mutable holder so a
     * screen can keep one instance and refill it with
     * {@link #localRank(Profile, String, Difficulty, LocalRank)} without allocating.
     */
    public static final class LocalRank {
        /** 1-based rank of the asked profile, 0 when it has no record. */
        public int rank;
        /** Number of local profiles with a record on the chart. */
        public int count;

        /** Creates an empty holder (rank 0 of 0). */
        public LocalRank() {
        }

        LocalRank(int rank, int count) {
            set(rank, count);
        }

        LocalRank set(int rank, int count) {
            this.rank = rank;
            this.count = count;
            return this;
        }
    }

    /** Why {@link #importCode(String)} failed. */
    public static final class ImportException extends Exception {
        /** Failure reasons. */
        public enum Reason { INVALID_CODE, LIMIT_REACHED }

        /** The reason. */
        public final Reason reason;

        ImportException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }
    }

    /** Creates a store at the platform default root (see class doc). */
    public ProfileStore() {
        this(defaultRoot());
    }

    /** Creates a store at a custom root directory (tests). */
    public ProfileStore(FileHandle root) {
        this.root = root;
    }

    /** @return the platform default storage root. */
    public static FileHandle defaultRoot() {
        if (Gdx.app != null && Gdx.app.getType() == Application.ApplicationType.Android) {
            return Gdx.files.local("profiles/");
        }
        return Gdx.files.external("KandKGame/profiles/");
    }

    /** @return the storage root. */
    public FileHandle root() {
        return root;
    }

    // ------------------------------------------------------------------ loading

    /** Loads without a song library (no legacy import, stored depths kept). */
    public void load() {
        load(null);
    }

    /**
     * Loads device settings, the index and every profile, recovering from corrupt files.
     * On a fresh install a default diver is created and an old {@code user.json} is imported
     * into it (needs {@code library} for title → id mapping). With a library, chart depths are
     * recomputed from the current constants.
     */
    public void load(SongLibrary library) {
        this.library = library;
        profiles.clear();
        dirtyProfiles.clear();
        indexDirty = false;
        deviceDirty = false;
        try {
            root.mkdirs();
        } catch (RuntimeException e) {
            log("Cannot create " + root.path() + ": " + e.getMessage());
        }

        DeviceSettings d = readWithRecovery(root.child(DEVICE), DeviceSettings.class);
        if (d == null) {
            d = new DeviceSettings();
            deviceDirty = true;
        }
        d.sanitize();
        device = d;

        Index idx = readWithRecovery(root.child(INDEX), Index.class);
        boolean fresh = false;
        if (idx == null) {
            idx = rebuildIndexFromFiles();
            fresh = idx.profiles.isEmpty();
            indexDirty = true;
        }
        if (idx.profiles == null) idx.profiles = new ArrayList<>();
        index = idx;

        for (Index.Entry e : new ArrayList<>(index.profiles)) {
            if (e == null || e.id == null || profiles.containsKey(e.id) || profiles.size() >= MAX_PROFILES) {
                index.profiles.remove(e);
                indexDirty = true;
                continue;
            }
            Profile p = readWithRecovery(profileFile(e.id), Profile.class);
            if (p == null) {
                log("Profile " + e.id + " unreadable; recreating it empty");
                p = newProfile(e.name != null ? e.name : defaultName(), null);
                p.id = e.id;
                p.createdAt = e.createdAt != 0 ? e.createdAt : p.createdAt;
                dirtyProfiles.add(p.id);
            }
            p.id = e.id;
            // ARCHITECTURE §18: old 10,000,000-scale scores × 10, once. The old file is kept as
            // <id>.json.scale1.bak first: the rotating .bak would lose it on the next save.
            if (p.scoreScale < Profile.SCORE_SCALE) backupBeforeScaleMigration(e.id, p, null);
            if (p.migrateScoreScale()) {
                log("Profile " + e.id + ": scores converted to the 100,000,000 scale");
                dirtyProfiles.add(p.id);
            }
            if (p.schemaVersion < 2) {
                // 2026-10-17: EXP → shells, rating → depth, fifteen grades (Profile.migrateToSchema2)
                backupBeforeSchemaMigration(e.id);
                log("Profile " + e.id + ": moved to shells and depth");
            }
            if (p.schemaVersion < Profile.SCHEMA_VERSION) {
                // 14:10 / 15:22: depth without the score factor, depth history restarted (Profile.migrateToSchema4)
                log("Profile " + e.id + ": depth formula of 2026-10-17 15:22");
                dirtyProfiles.add(p.id);
            }
            p.sanitize();
            if (p.name == null || sanitizeName(p.name) == null) p.name = e.name != null ? e.name : defaultName();
            e.name = p.name;
            e.createdAt = p.createdAt;
            profiles.put(p.id, p);
        }

        if (profiles.isEmpty()) {
            Profile p = newProfile(defaultName(), AvatarSpec.fromSeed(random.nextInt()));
            addProfile(p);
            index.activeProfileId = p.id;
            if (fresh) index.firstRunDone = false;
            dirtyProfiles.add(p.id);
            indexDirty = true;
            importLegacyInto(p);
        }
        if (index.activeProfileId == null || !profiles.containsKey(index.activeProfileId)) {
            index.activeProfileId = profiles.keySet().iterator().next();
            indexDirty = true;
        }
        for (Profile p : profiles.values()) refreshDepths(p);
        rebuildView();
        loaded = true;
        anyDirty = indexDirty || deviceDirty || !dirtyProfiles.isEmpty();
        if (anyDirty) saveNow();
    }

    private Index rebuildIndexFromFiles() {
        Index idx = new Index();
        FileHandle[] files;
        try {
            files = root.list();
        } catch (RuntimeException e) {
            files = new FileHandle[0];
        }
        // Profile ids from <id>.json and from saves that only survive as <id>.json.tmp / .bak.
        Set<String> ids = new TreeSet<>();
        for (FileHandle f : files) {
            String n = f.name();
            int cut = n.endsWith(".json") ? n.length() - 5
                : n.endsWith(".json.tmp") || n.endsWith(".json.bak") ? n.length() - 9 : -1;
            if (cut <= 0) continue;
            String id = n.substring(0, cut);
            if ((id + ".json").equals(INDEX) || (id + ".json").equals(DEVICE)) continue;
            ids.add(id);
        }
        for (String id : ids) {
            Profile p = readWithRecovery(profileFile(id), Profile.class);
            if (p == null) continue;
            Index.Entry e = new Index.Entry();
            e.id = id;
            e.name = p.name;
            e.createdAt = p.createdAt;
            idx.profiles.add(e);
        }
        if (!idx.profiles.isEmpty()) {
            idx.firstRunDone = true;
            log("index.json missing; rebuilt from " + idx.profiles.size() + " profile file(s)");
        }
        return idx;
    }

    private void importLegacyInto(Profile p) {
        if (library == null || !LegacyUserDataImporter.available()) return;
        FileHandle f = LegacyUserDataImporter.legacyFile();
        int n = LegacyUserDataImporter.importInto(p, library, f);
        if (n >= 0) {
            legacyImported = n;
            LegacyUserDataImporter.markImported(f);
            log("Imported " + n + " legacy record(s) into " + p.name);
        }
    }

    /** @return records imported from an old {@code user.json} at load, or −1 when none was imported. */
    public int legacyImportedCount() {
        return legacyImported;
    }

    /**
     * @return true when an old {@code user.json} exists and was not imported (a successful import
     *         renames it; one that failed to parse at load stays)
     */
    public boolean hasLegacyData() {
        return LegacyUserDataImporter.available();
    }

    private void refreshDepths(Profile p) {
        if (library != null) {
            for (Map.Entry<String, Map<String, ChartRecord>> e : p.records.entrySet()) {
                SongMeta song = library.song(e.getKey());
                if (song == null) continue;
                for (Map.Entry<String, ChartRecord> r : e.getValue().entrySet()) {
                    Difficulty d = Difficulty.parse(r.getKey());
                    if (d == null || !song.has(d)) continue;
                    float m = Depth.chartDepth(song.constant(d), r.getValue().bestScore);
                    if (Math.abs(m - r.getValue().bestDepth) > 1e-3f) {
                        r.getValue().bestDepth = m;
                        dirtyProfiles.add(p.id);
                    }
                }
            }
        }
        float before = p.depth;
        p.depth = computeDepth(p);
        if (Math.abs(before - p.depth) > 1e-3f) dirtyProfiles.add(p.id);
    }

    /** Keeps the schema 1 file as {@code <id>.json.schema1.bak} once (best effort). */
    private void backupBeforeSchemaMigration(String id) {
        FileHandle src = profileFile(id);
        FileHandle dst = root.child(id + SCHEMA1_BACKUP_SUFFIX);
        if (!isFile(src) || isFile(dst)) return;
        try {
            writeSynced(dst, src.readBytes());
        } catch (IOException | RuntimeException e) {
            log("Profile " + id + ": schema 1 backup failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ queries

    /** @return true once {@link #load} has run. */
    public boolean isLoaded() {
        return loaded;
    }

    /** @return the active profile (never null after {@link #load}). */
    public Profile active() {
        return profiles.get(index.activeProfileId);
    }

    /** @return the active profile id. */
    public String activeId() {
        return index.activeProfileId;
    }

    /** @return every profile in display order (unmodifiable live view; the same instance on every call). */
    public List<Profile> list() {
        return profileViewReadOnly;
    }

    /**
     * Change counter for screens that cache derived data ({@link #topEntries}, {@link #list}
     * rows, local ranks): it increases whenever a profile is created, renamed, deleted,
     * imported or switched, a run is recorded, or the store is loaded. Compare it once per
     * frame and recompute only when it changed.
     *
     * @return the current revision
     */
    public int revision() {
        return revision;
    }

    /** @return the number of profiles. */
    public int count() {
        return profiles.size();
    }

    /** @return the profile with this id, or null. */
    public Profile profile(String id) {
        return profiles.get(id);
    }

    /** @return true when another profile can be created or imported. */
    public boolean canCreate() {
        return profiles.size() < MAX_PROFILES;
    }

    /** @return device-scoped settings (never null). */
    public DeviceSettings device() {
        return device;
    }

    /** @return true until {@link #completeFirstRun()} (fresh install). */
    public boolean isFirstRun() {
        return !index.firstRunDone;
    }

    /** Marks the first-run flow as finished and saves. */
    public void completeFirstRun() {
        if (index.firstRunDone) return;
        index.firstRunDone = true;
        indexDirty = true;
        anyDirty = true;
        save();
    }

    /** @return the active profile's record for a chart, or null (read-only, never creates). */
    public ChartRecord record(String songId, Difficulty d) {
        Profile p = active();
        return p == null ? null : p.record(songId, d);
    }

    /** @return the active profile's player depth in metres (unrounded). */
    public float depth() {
        Profile p = active();
        return p == null ? 0f : p.depth;
    }

    /** @return the active profile's shells. */
    public long shells() {
        Profile p = active();
        return p == null ? 0 : p.shells;
    }

    /** @return true when the active profile owns {@code songId} (see {@link Profile#owns}). */
    public boolean owns(String songId) {
        Profile p = active();
        return p != null && p.owns(songId);
    }

    /**
     * Buys a single for the active profile: spends {@code price} shells and adds the song to
     * {@link Profile#ownedSongs} (no refunds). Bumps {@link #revision()} and saves.
     *
     * @return false when the song is already owned, the id is null or the shells are not enough
     */
    public boolean buySong(String songId, int price) {
        Profile p = active();
        if (p == null || songId == null || p.owns(songId) || price < 0 || p.shells < price) return false;
        p.shells -= price;
        p.ownedSongs.add(songId);
        dirtyProfiles.add(p.id);
        anyDirty = true;
        revision++;
        save();
        return true;
    }

    /**
     * Fills {@code out} (length ≥ {@link Depth#TOP_N}) with the profile's deepest chart depths,
     * sorted descending, zeros for empty slots (input for {@link Depth#nextGoal}).
     *
     * @return {@code out}
     */
    public float[] topDepths(Profile p, float[] out) {
        Arrays.fill(out, 0f);
        if (p == null) return out;
        int n = 0;
        for (Map<String, ChartRecord> bySong : p.records.values()) {
            for (ChartRecord r : bySong.values()) {
                if (r.plays <= 0) continue;
                float v = r.bestDepth;
                // insertion into the sorted prefix
                int i = Math.min(n, out.length);
                if (i == out.length && v <= out[out.length - 1]) continue;
                if (i == out.length) i--;
                while (i > 0 && out[i - 1] < v) {
                    out[i] = out[i - 1];
                    i--;
                }
                out[i] = v;
                if (n < out.length) n++;
            }
        }
        return out;
    }

    /** @return the player depth of a profile from its records. */
    public float computeDepth(Profile p) {
        return Depth.playerDepth(topDepths(p, new float[Depth.TOP_N]));
    }

    /**
     * The profile's deepest {@code n} charts, deepest first (the TOP 10 tab).
     *
     * <p>Allocates a new list and entries: call it when the tab opens or when
     * {@link #revision()} changes, never per frame (see
     * {@link #topEntries(Profile, int, List)} to reuse a list).
     */
    public List<TopEntry> topEntries(Profile p, int n) {
        return topEntries(p, n, new ArrayList<TopEntry>());
    }

    /**
     * Fills {@code out} (cleared first) with the profile's deepest {@code n} charts, deepest
     * first, and returns it. Reuses the list but creates the entries: refresh it when
     * {@link #revision()} changes, not per frame.
     *
     * @return {@code out}
     */
    public List<TopEntry> topEntries(Profile p, int n, List<TopEntry> out) {
        out.clear();
        if (p == null || p.records == null || n <= 0) return out;
        for (Map.Entry<String, Map<String, ChartRecord>> e : p.records.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            for (Map.Entry<String, ChartRecord> r : e.getValue().entrySet()) {
                Difficulty d = Difficulty.parse(r.getKey());
                ChartRecord rec = r.getValue();
                if (d == null || rec == null || rec.plays <= 0) continue;
                out.add(new TopEntry(e.getKey(), d, rec.bestDepth, rec.bestScore, rec.bestGrade));
            }
        }
        Collections.sort(out, TOP_ORDER);
        while (out.size() > n) out.remove(out.size() - 1);
        return out;
    }

    /**
     * @return index 0..9 of a chart in the profile's top 10 (the order of {@link #topEntries}),
     *         or −1 when it is not in it or has no record. Builds no list.
     */
    public int topTenIndex(Profile p, String songId, Difficulty d) {
        if (p == null || p.records == null || songId == null || d == null) return -1;
        ChartRecord mine = p.record(songId, d);
        if (mine == null || mine.plays <= 0) return -1;
        int before = 0;
        for (Map.Entry<String, Map<String, ChartRecord>> e : p.records.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            for (Map.Entry<String, ChartRecord> r : e.getValue().entrySet()) {
                Difficulty rd = Difficulty.parse(r.getKey());
                ChartRecord rec = r.getValue();
                if (rd == null || rec == null || rec.plays <= 0 || rec == mine) continue;
                if (compareTop(rec.bestDepth, rec.bestScore, e.getKey(), rd,
                    mine.bestDepth, mine.bestScore, songId, d) < 0) {
                    before++;
                    if (before >= Depth.TOP_N) return -1;
                }
            }
        }
        return before;
    }

    /** TOP 10 order: negative when entry a ranks before entry b (see {@link #TOP_ORDER}). */
    private static int compareTop(float ra, int sa, String ida, Difficulty da,
                                  float rb, int sb, String idb, Difficulty db) {
        int c = Float.compare(rb, ra);
        if (c != 0) return c;
        c = Integer.compare(sb, sa);
        if (c != 0) return c;
        c = ida.compareTo(idb);
        return c != 0 ? c : Integer.compare(da.ordinal(), db.ordinal());
    }

    /**
     * Local ranking of the active profile on a chart across every local profile (DESIGN §8.7).
     * Shown only when {@code count ≥ 2}. Allocates the result: screens that show it every
     * frame keep one {@link LocalRank} and use {@link #localRank(String, Difficulty, LocalRank)}.
     */
    public LocalRank localRank(String songId, Difficulty d) {
        return localRank(active(), songId, d, new LocalRank());
    }

    /** Local ranking of {@code who} on a chart (allocates; see the {@code out} overload). */
    public LocalRank localRank(Profile who, String songId, Difficulty d) {
        return localRank(who, songId, d, new LocalRank());
    }

    /** Allocation-free {@link #localRank(String, Difficulty)}: fills and returns {@code out}. */
    public LocalRank localRank(String songId, Difficulty d, LocalRank out) {
        return localRank(active(), songId, d, out);
    }

    /**
     * Allocation-free local ranking of {@code who} on a chart: fills and returns {@code out}
     * (rank 1-based, 0 when {@code who} has no record; count = profiles with a record).
     */
    public LocalRank localRank(Profile who, String songId, Difficulty d, LocalRank out) {
        ChartRecord mine = who != null ? who.record(songId, d) : null;
        int count = 0;
        int better = 0;
        for (int i = 0, n = profileView.size(); i < n; i++) {
            Profile p = profileView.get(i);
            ChartRecord r = p.record(songId, d);
            if (r == null || r.plays <= 0) continue;
            count++;
            if (mine != null && p != who && r.bestScore > mine.bestScore) better++;
        }
        return out.set(mine != null && mine.plays > 0 ? better + 1 : 0, count);
    }

    // ------------------------------------------------------------------ profile management

    /**
     * Normalises a diver name: control characters removed, trimmed, cut to 16 code points.
     *
     * @return the clean name, or null when it is empty
     */
    public static String sanitizeName(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.UNASSIGNED) {
                continue;
            }
            sb.appendCodePoint(Character.isWhitespace(cp) ? ' ' : cp);
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) return null;
        if (s.codePointCount(0, s.length()) > MAX_NAME_CODEPOINTS) {
            s = s.substring(0, s.offsetByCodePoints(0, MAX_NAME_CODEPOINTS)).trim();
        }
        return s.isEmpty() ? null : s;
    }

    /** @return a default name {@code DIVER-XXXX} (4 hex digits). */
    public String defaultName() {
        return String.format(Locale.ROOT, "DIVER-%04X", random.nextInt(0x10000));
    }

    /** Creates a profile with a random avatar; see {@link #create(String, AvatarSpec)}. */
    public Profile create(String name) {
        return create(name, null);
    }

    /**
     * Creates and saves a new profile (it does not become active; call {@link #switchTo}).
     *
     * @param name   raw name (sanitised; an invalid name becomes the default name)
     * @param avatar avatar, or null for a random one
     * @return the profile, or null when {@link #MAX_PROFILES} is reached
     */
    public Profile create(String name, AvatarSpec avatar) {
        if (!canCreate()) return null;
        String clean = sanitizeName(name);
        Profile p = newProfile(clean != null ? clean : defaultName(),
            avatar != null ? avatar.copy() : AvatarSpec.fromSeed(random.nextInt()));
        addProfile(p);
        dirtyProfiles.add(p.id);
        indexDirty = true;
        anyDirty = true;
        save();
        return p;
    }

    /**
     * Renames a profile and saves.
     *
     * @return false when the profile does not exist or the name is empty after sanitising
     */
    public boolean rename(String id, String name) {
        Profile p = profiles.get(id);
        String clean = sanitizeName(name);
        if (p == null || clean == null) return false;
        p.name = clean;
        for (Index.Entry e : index.profiles) if (e.id.equals(id)) e.name = clean;
        dirtyProfiles.add(id);
        indexDirty = true;
        anyDirty = true;
        revision++;
        save();
        return true;
    }

    /**
     * Takes a diver back to a new player's progress (Settings → RESET RECORDS; Kelvin 2026-10-18 20:16:
     * 「把所有记录重置，包括贝壳和所有进度」): chart records, depth and its history, the game log, statistics,
     * play count, shells, bought singles, read stories, revealed secrets, the tutorial (seen, and its
     * first-time shells), chapter mode and the sigil options that need depth. The name, the rest of the
     * sigil, settings, calibration, the map position and the reminder flags stay. Bumps
     * {@link #revision()} and saves.
     *
     * @return false when the id is unknown
     */
    public boolean resetRecords(String id) {
        Profile p = profiles.get(id);
        if (p == null) return false;
        if (p.records != null) p.records.clear();
        p.depth = 0f;
        if (p.depthHistory != null) p.depthHistory.clear();
        if (p.recentPlays != null) p.recentPlays.clear();
        p.stats = new Profile.Stats();
        p.playCount = 0;
        p.totalNotesHit = 0L;
        p.lastPlayedAt = 0L;
        p.lastPlayDay = 0;
        p.shells = 0L;
        p.exp = 0L;
        if (p.ownedSongs != null) p.ownedSongs.clear();
        if (p.seenChapters != null) p.seenChapters.clear();
        if (p.flags != null) {
            for (int i = p.flags.size() - 1; i >= 0; i--) {
                String f = p.flags.get(i);
                boolean progress = f == null || f.startsWith(Profile.FLAG_STORY) || f.startsWith(Profile.FLAG_REVEAL);
                if (progress) p.flags.remove(i);
            }
        }
        p.tutorialSeen = false;
        p.tutorialExpGranted = false;
        p.mainChapterMode = false;
        if (p.avatar != null) {
            AvatarSpec a = p.avatar.copy();
            a.relock(0);
            p.avatar = a;
        }
        dirtyProfiles.add(id);
        anyDirty = true;
        revision++;
        save();
        return true;
    }

    /**
     * Sets a diver's sigil to a copy of {@code spec} (the avatar editor), for the active diver or
     * any other. Bumps {@link #revision()} and saves.
     *
     * @return false when the id is unknown or {@code spec} is null
     */
    public boolean setAvatar(String id, AvatarSpec spec) {
        Profile p = profiles.get(id);
        if (p == null || spec == null) return false;
        p.avatar = spec.copy();
        p.avatar.sanitize();
        dirtyProfiles.add(id);
        anyDirty = true;
        revision++;
        save();
        return true;
    }

    /**
     * Makes another profile active, saving everything first.
     *
     * @return false when the id is unknown
     */
    public boolean switchTo(String id) {
        if (!profiles.containsKey(id)) return false;
        save();
        if (id.equals(index.activeProfileId)) return true;
        index.activeProfileId = id;
        indexDirty = true;
        anyDirty = true;
        revision++;
        save();
        return true;
    }

    /**
     * Deletes a profile and its files. The last profile cannot be deleted. Deleting the active
     * profile switches to the most recently played remaining one.
     *
     * <p>The updated index is on disk before the files go (this call waits for the writer): if
     * the app is killed in between, the diver is already gone from the index instead of coming
     * back as an empty profile on the next {@link #load}.
     *
     * @return false when the id is unknown or it is the last profile
     */
    public boolean delete(String id) {
        if (!profiles.containsKey(id) || profiles.size() <= 1) return false;
        profiles.remove(id);
        dirtyProfiles.remove(id);
        for (int i = index.profiles.size() - 1; i >= 0; i--) {
            if (id.equals(index.profiles.get(i).id)) index.profiles.remove(i);
        }
        if (id.equals(index.activeProfileId)) {
            Profile next = null;
            for (Profile p : profiles.values()) {
                if (next == null || p.lastPlayedAt > next.lastPlayedAt) next = p;
            }
            index.activeProfileId = next.id;
        }
        rebuildView();
        indexDirty = true;
        anyDirty = true;
        // Also waits for any queued write of this profile, which must not recreate its file.
        saveNow();
        FileHandle f = profileFile(id);
        deleteQuietly(f);
        deleteQuietly(f.sibling(f.name() + ".bak"));
        deleteQuietly(f.sibling(f.name() + ".tmp"));
        deleteQuietly(root.child(id + SCALE1_BACKUP_SUFFIX));
        deleteQuietly(root.child(id + SCHEMA1_BACKUP_SUFFIX));
        return true;
    }

    private Profile newProfile(String name, AvatarSpec avatar) {
        Profile p = new Profile();
        p.scoreScale = Profile.SCORE_SCALE;
        p.id = newId();
        p.name = name;
        p.createdAt = System.currentTimeMillis();
        p.avatar = avatar != null ? avatar : AvatarSpec.fromSeed(random.nextInt());
        return p;
    }

    private void addProfile(Profile p) {
        profiles.put(p.id, p);
        Index.Entry e = new Index.Entry();
        e.id = p.id;
        e.name = p.name;
        e.createdAt = p.createdAt;
        index.profiles.add(e);
        rebuildView();
    }

    private void rebuildView() {
        profileView.clear();
        profileView.addAll(profiles.values());
        revision++;
    }

    private String newId() {
        String id;
        do {
            id = "p" + Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString(0x1000 + random.nextInt(0xEFFF), 36);
        } while (profiles.containsKey(id) || profileFile(id).exists());
        return id;
    }

    // ------------------------------------------------------------------ recording

    /**
     * Records a finished run for the active profile and saves (DESIGN §6.1, §11, §12).
     * <ul>
     *   <li>Autoplay: nothing changes ({@link RecordOutcome#recorded()} false).</li>
     *   <li>Tutorial: marks {@code tutorialSeen}, grants the first-time shells once per diver
     *       ({@code tutorialExpGranted}, which only RESET RECORDS clears), no chart record.</li>
     *   <li>Otherwise: updates the chart record, shells, stats, logs and depth.</li>
     *   <li>A non-tutorial result without a song id (a gameplay bug: {@code Builder.chart} was
     *       not called) is logged and treated like autoplay, so the profile is never touched.</li>
     * </ul>
     */
    public RecordOutcome recordPlay(PlayResult r) {
        Profile p = active();
        RecordOutcome.Builder b = RecordOutcome.builder();
        if (p == null || r == null) {
            log("recordPlay before load() or without a result; nothing recorded");
            return b.recorded(false).build();
        }
        boolean unrecordable = !r.tutorial() && (r.songId() == null || r.difficulty() == null);
        if (unrecordable) log("recordPlay: result without song id/difficulty not recorded: " + r);
        float depthBefore = p.depth;
        long shellsBefore = p.shells;
        ChartRecord rec = r.tutorial() ? null : p.record(r.songId(), r.difficulty());
        int prevBest = rec != null ? rec.bestScore : 0;
        String prevGrade = rec != null ? rec.bestGrade : null;
        boolean firstPlay = rec == null || rec.plays <= 0;
        // Kelvin 2026-10-17: fifteen grades on the score; R+ is the chart's theoretical maximum
        String grade = r.tutorial() ? null : Grades.gradeOf(r.score(), r.totalNotes());

        if (r.autoplay() || unrecordable) {
            LocalRank lr = localRank(p, r.songId(), r.difficulty());
            return b.recorded(false)
                .best(prevBest, false, false)
                .grade(grade, prevGrade, false)
                .chartDepth(Depth.chartDepth(r.chartConstant(), r.score()), rec != null ? rec.bestDepth : 0f)
                .depth(depthBefore, depthBefore)
                .topTenIndex(topTenIndex(p, r.songId(), r.difficulty()))
                .shells(ShellBreakdown.NONE, shellsBefore, shellsBefore)
                .local(lr.rank, lr.count)
                .build();
        }

        ShellBreakdown shells;
        if (r.tutorial()) {
            boolean first = !p.tutorialExpGranted;
            p.tutorialExpGranted = true;
            p.tutorialSeen = true;
            shells = Shells.shellsForTutorial(first);
            b.recorded(true).tutorial(true).best(0, false, first).grade(null, null, false);
        } else {
            boolean newBest = !firstPlay && r.score() > prevBest;
            boolean gradeImproved = newBest && Grades.isBetterGrade(grade, prevGrade);
            boolean firstFc = r.fullCombo() && (rec == null || !rec.fullCombo);
            boolean firstAr = r.allResonance() && (rec == null || !rec.allResonance);
            int today = localDay(r.timestampMillis());
            boolean firstOfDay = p.lastPlayDay != today;
            // ARCHITECTURE §18: the PERFECT bonus never changes the shells, so the new-best bonus
            // compares min(score, 100,000,000) (or a better grade: R+ lies above it); NEW BEST
            // itself (record, result) stays on the raw score
            boolean shellBest = gradeImproved || (!firstPlay
                && Math.min(r.score(), Judgement.MAX_SCORE) > Math.min(prevBest, Judgement.MAX_SCORE));
            shells = Shells.shellsForPlay(r.chartConstant(), r.score(), r.totalNotes(), firstPlay, shellBest,
                gradeImproved, firstFc, firstAr, firstOfDay);

            rec = p.recordOrCreate(r.songId(), r.difficulty());
            if (rec.plays <= 0) rec.firstPlayedAt = r.timestampMillis();
            rec.plays++;
            rec.lastPlayedAt = r.timestampMillis();
            rec.bestScore = Math.max(rec.bestScore, r.score());
            if (Grades.isBetterGrade(grade, rec.bestGrade)) rec.bestGrade = grade;
            rec.bestAccuracy = Math.max(rec.bestAccuracy, r.accuracy());
            rec.maxCombo = Math.max(rec.maxCombo, r.maxCombo());
            rec.fullCombo |= r.fullCombo();
            rec.allResonance |= r.allResonance();
            rec.pushRecent(r.score());
            rec.bestDepth = Depth.chartDepth(r.chartConstant(), rec.bestScore);

            p.playCount++;
            p.totalNotesHit += r.count(Judgement.RESONANCE) + r.count(Judgement.ECHO);
            if (r.fullCombo()) p.stats.fullCombos++;
            if (r.allResonance()) p.stats.allResonance++;
            p.lastPlayDay = today;
            p.lastSongId = r.songId();
            p.lastDifficulty = r.difficulty().name();
            p.lastPlayedAt = r.timestampMillis();

            Profile.RecentPlay log = new Profile.RecentPlay();
            log.songId = r.songId();
            log.difficulty = r.difficulty().name();
            log.score = r.score();
            log.grade = grade;
            log.accuracy = r.accuracy();
            log.fullCombo = r.fullCombo();
            log.allResonance = r.allResonance();
            log.t = r.timestampMillis();
            p.recentPlays.add(0, log);
            while (p.recentPlays.size() > Profile.RECENT_PLAYS_MAX) p.recentPlays.remove(p.recentPlays.size() - 1);

            p.depth = computeDepth(p);
            p.depthHistory.add(new Profile.DepthPoint(r.timestampMillis(), p.depth));
            while (p.depthHistory.size() > Profile.DEPTH_HISTORY_MAX) p.depthHistory.remove(0);

            b.recorded(true)
                .best(prevBest, newBest, firstPlay)
                .grade(grade, prevGrade, gradeImproved)
                .chartDepth(Depth.chartDepth(r.chartConstant(), r.score()), rec.bestDepth)
                .topTenIndex(topTenIndex(p, r.songId(), r.difficulty()));
            LocalRank lr = localRank(p, r.songId(), r.difficulty());
            b.local(lr.rank, lr.count);
        }
        p.stats.totalPlayMs += Math.max(0, r.playTimeMs());
        p.shells += shells.total();
        b.depth(depthBefore, p.depth).shells(shells, shellsBefore, p.shells);
        Depth.unlocksBetween(Depth.meters(depthBefore), Depth.meters(p.depth), b.unlockList());
        dirtyProfiles.add(p.id);
        anyDirty = true;
        revision++;
        save();
        return b.build();
    }

    /** @return the local calendar day of a timestamp as yyyymmdd. */
    public static int localDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    // ------------------------------------------------------------------ export / import

    /**
     * @return the profile as an export code {@code KK1:} + base64(gzip(json)), or null when
     *         the id is unknown
     */
    public String exportCode(String id) {
        Profile p = profiles.get(id);
        if (p == null) return null;
        try {
            byte[] json = PlainJson.write(p, false);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(json.length / 3 + 64);
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(json);
            }
            return EXPORT_PREFIX + new String(Base64Coder.encode(bos.toByteArray()));
        } catch (IOException e) {
            log("Export failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Imports a profile from an export code. The imported profile gets a new id; a name
     * collision becomes {@code NAME (2)}; it does not become active.
     *
     * @throws ImportException with {@link ImportException.Reason#INVALID_CODE} or
     *                         {@link ImportException.Reason#LIMIT_REACHED}
     */
    public Profile importCode(String code) throws ImportException {
        if (!canCreate()) throw new ImportException(ImportException.Reason.LIMIT_REACHED, "Profile limit reached", null);
        if (code == null) throw new ImportException(ImportException.Reason.INVALID_CODE, "Empty code", null);
        StringBuilder sb = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (!Character.isWhitespace(ch)) sb.append(ch);
        }
        String c = sb.toString();
        if (!c.startsWith(EXPORT_PREFIX)) {
            throw new ImportException(ImportException.Reason.INVALID_CODE, "Not a KK1 code", null);
        }
        Profile p;
        byte[] json;
        try {
            byte[] gz = Base64Coder.decode(c.substring(EXPORT_PREFIX.length()));
            json = gunzip(gz);
            p = PlainJson.read(json, Profile.class);
        } catch (IOException | RuntimeException e) {
            throw new ImportException(ImportException.Reason.INVALID_CODE, "Corrupt code", e);
        }
        return adoptImported(p, json);
    }

    /** Imports a profile from an exported JSON file (desktop export folder). */
    public Profile importFile(FileHandle file) throws ImportException {
        if (!canCreate()) throw new ImportException(ImportException.Reason.LIMIT_REACHED, "Profile limit reached", null);
        Profile p;
        byte[] raw;
        try {
            raw = file.readBytes();
            p = PlainJson.read(raw, Profile.class);
        } catch (IOException | RuntimeException e) {
            throw new ImportException(ImportException.Reason.INVALID_CODE, "Unreadable file", e);
        }
        return adoptImported(p, raw);
    }

    /** @param raw the imported JSON as read (kept as the scale-1 backup when it is migrated) */
    private Profile adoptImported(Profile p, byte[] raw) throws ImportException {
        if (p == null) throw new ImportException(ImportException.Reason.INVALID_CODE, "Empty profile", null);
        // a code exported by an older build holds 10,000,000-scale scores
        boolean oldScale = p.migrateScoreScale();
        p.sanitize();
        p.id = newId();
        if (oldScale) backupBeforeScaleMigration(p.id, null, raw);
        String base = sanitizeName(p.name);
        if (base == null) base = defaultName();
        p.name = uniqueName(base);
        if (p.createdAt <= 0) p.createdAt = System.currentTimeMillis();
        refreshDepths(p);
        addProfile(p);
        dirtyProfiles.add(p.id);
        indexDirty = true;
        anyDirty = true;
        save();
        return p;
    }

    /**
     * Keeps the pre-migration JSON of an old-scale (10,000,000) profile as
     * {@code <id>.json.scale1.bak} (ARCHITECTURE §18), once: a backup that already exists and
     * parses is never replaced, so it always holds the first original. Written synchronously,
     * before the migrated profile's save is queued; if the app dies mid-write, the backup does
     * not parse and the next load (which migrates the still-old file again) rewrites it.
     *
     * @param preMigration the profile as read, still on the old scale (used only when no source
     *                     file is found), or null
     * @param raw          the original bytes when known (imports), else null: the source is then
     *                     the first old-scale {@code .tmp} / file / {@code .bak} that parses, in
     *                     {@link #readWithRecovery}'s order
     */
    private void backupBeforeScaleMigration(String id, Profile preMigration, byte[] raw) {
        FileHandle dst = root.child(id + SCALE1_BACKUP_SUFFIX);
        if (isFile(dst) && parses(dst, Profile.class)) return;
        byte[] bytes = raw;
        if (bytes == null) {
            FileHandle f = profileFile(id);
            FileHandle[] sources = {f.sibling(f.name() + ".tmp"), f, f.sibling(f.name() + ".bak")};
            for (FileHandle src : sources) {
                if (!isFile(src)) continue;
                try {
                    byte[] b = src.readBytes();
                    if (b.length == 0) continue;
                    Profile q = PlainJson.read(b, Profile.class);
                    if (q != null && q.scoreScale < Profile.SCORE_SCALE) {
                        bytes = b;
                        break;
                    }
                } catch (IOException | RuntimeException ignored) {
                    // not the source: try the next candidate
                }
            }
        }
        try {
            if (bytes == null && preMigration != null) {
                bytes = PlainJson.write(preMigration, true);
            }
            if (bytes == null) return;
            writeSynced(dst, bytes);
            log("Profile " + id + ": pre-migration scores kept in " + dst.name());
        } catch (IOException | RuntimeException e) {
            log("Cannot keep " + dst.path() + ": " + e.getMessage());
        }
    }

    private String uniqueName(String base) {
        if (!nameTaken(base)) return base;
        for (int n = 2; ; n++) {
            String suffix = " (" + n + ")";
            int room = MAX_NAME_CODEPOINTS - suffix.length();
            String b = base;
            if (b.codePointCount(0, b.length()) > room) b = b.substring(0, b.offsetByCodePoints(0, room)).trim();
            String candidate = b + suffix;
            if (!nameTaken(candidate)) return candidate;
        }
    }

    private boolean nameTaken(String name) {
        for (Profile p : profiles.values()) if (name.equalsIgnoreCase(p.name)) return true;
        return false;
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_IMPORT_BYTES) throw new IOException("Code too large");
            }
            return out.toByteArray();
        }
    }

    /**
     * Desktop only: writes {@code KandKGame/export/<name>-<yyyymmdd>[-n].json} (never overwrites).
     *
     * @return the written file, or null on other platforms or failure
     */
    public FileHandle exportToFile(String id) {
        Profile p = profiles.get(id);
        if (p == null || Gdx.app == null || Gdx.app.getType() != Application.ApplicationType.Desktop) return null;
        try {
            // Letters and digits of any script stay (CJK names are first-class); a second export of
            // the same name on the same day gets -2, -3 … instead of overwriting the first.
            String safe = p.name.replaceAll("[^\\p{L}\\p{N}_-]", "_");
            String base = safe + "-" + localDay(System.currentTimeMillis());
            FileHandle f = exportDir().child(base + ".json");
            for (int n = 2; f.exists(); n++) f = exportDir().child(base + "-" + n + ".json");
            f.parent().mkdirs();
            f.writeBytes(PlainJson.write(p, true), false);
            return f;
        } catch (IOException | RuntimeException e) {
            log("Export file failed: " + e.getMessage());
            return null;
        }
    }

    /** @return exported JSON files in the desktop export folder (empty elsewhere). */
    public FileHandle[] listExportFiles() {
        if (Gdx.app == null || Gdx.app.getType() != Application.ApplicationType.Desktop) return new FileHandle[0];
        try {
            FileHandle dir = exportDir();
            return dir.exists() ? dir.list(".json") : new FileHandle[0];
        } catch (RuntimeException e) {
            return new FileHandle[0];
        }
    }

    private static FileHandle exportDir() {
        return Gdx.files.external("KandKGame/export/");
    }

    // ------------------------------------------------------------------ saving

    /** Flags the active profile, the index and device settings as changed (debounced save). */
    public void markDirty() {
        if (index.activeProfileId != null) dirtyProfiles.add(index.activeProfileId);
        deviceDirty = true;
        indexDirty = true;
        anyDirty = true;
        quietTimer = 0f;
    }

    /** Flags only device settings as changed. */
    public void markDeviceDirty() {
        deviceDirty = true;
        anyDirty = true;
        quietTimer = 0f;
    }

    /** @return true when unsaved changes exist. */
    public boolean isDirty() {
        return anyDirty;
    }

    /**
     * Saves after {@link #AUTOSAVE_QUIET_SECONDS} without new changes (or at most
     * {@link #AUTOSAVE_MAX_SECONDS} after the first). Cheap when nothing is dirty; never call
     * {@link #markDirty()} every frame.
     */
    public void update(float delta) {
        drainFailedWrites();
        if (!anyDirty) {
            dirtyTimer = 0f;
            return;
        }
        quietTimer += delta;
        dirtyTimer += delta;
        if (autosaveSuspended) return;
        if (quietTimer >= AUTOSAVE_QUIET_SECONDS || dirtyTimer >= AUTOSAVE_MAX_SECONDS) save();
    }

    /**
     * Holds the debounced autosave of {@link #update} while true (timers keep running, so it
     * saves soon after the suspension ends). Main suspends it while gameplay is the screen: a
     * mid-song change (e.g. the FX auto-drop flagging device.json) then costs no serialisation
     * or disk I/O during the song. Explicit saves are not affected.
     */
    public void setAutosaveSuspended(boolean suspended) {
        autosaveSuspended = suspended;
    }

    /** True while {@link #update}'s autosave is suspended. */
    public boolean isAutosaveSuspended() {
        return autosaveSuspended;
    }

    /**
     * Saves every dirty file without blocking: serialises now, writes on the writer thread (see
     * the class doc). Afterwards {@link #isDirty()} is false until the next change or a failed
     * write.
     */
    public void save() {
        drainFailedWrites();
        if (!anyDirty) return;
        boolean ok = true;
        for (String id : new ArrayList<>(dirtyProfiles)) {
            Profile p = profiles.get(id);
            if (p == null) {
                dirtyProfiles.remove(id);
                continue;
            }
            if (queueWrite(profileFile(id), p)) dirtyProfiles.remove(id);
            else ok = false;
        }
        if (indexDirty) {
            if (queueWrite(root.child(INDEX), index)) indexDirty = false;
            else ok = false;
        }
        if (deviceDirty) {
            if (queueWrite(root.child(DEVICE), device)) deviceDirty = false;
            else ok = false;
        }
        anyDirty = !ok;
        quietTimer = 0f;
        dirtyTimer = 0f;
    }

    /**
     * Saves every dirty file and waits until all queued writes (earlier ones included) are on
     * disk. For {@code pause()} / {@code dispose()} and tests; elsewhere prefer {@link #save()}.
     */
    public void saveNow() {
        save();
        awaitWrites();
    }

    /**
     * Blocks until every queued write finished, then flags the files of failed writes dirty
     * again. No-op when nothing is queued.
     */
    public void awaitWrites() {
        Future<?> last = lastWrite;
        if (last != null) {
            boolean interrupted = false;
            while (true) {
                try {
                    last.get();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    break; // the write task logs and reports its own failure
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (lastWrite == last) lastWrite = null;
        }
        drainFailedWrites();
    }

    /** Flushes pending saves and stops the writer thread. The store stays usable (writes then run inline). */
    public void dispose() {
        saveNow();
        ExecutorService w = writer;
        writer = null;
        disposedWriter = true;
        if (w != null) {
            w.shutdown();
            try {
                w.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Re-flags files whose background write failed (render thread). */
    private void drainFailedWrites() {
        String name;
        while ((name = failedWrites.poll()) != null) {
            if (name.equals(INDEX)) indexDirty = true;
            else if (name.equals(DEVICE)) deviceDirty = true;
            else if (profiles.containsKey(name)) dirtyProfiles.add(name);
            else continue; // deleted meanwhile
            anyDirty = true;
        }
    }

    private FileHandle profileFile(String id) {
        return root.child(id + ".json");
    }

    /**
     * Serialises {@code value} now and queues its atomic write on the writer thread (inline when
     * the writer is stopped or cannot be started).
     *
     * @return false when serialisation failed (the file stays dirty)
     */
    private boolean queueWrite(final FileHandle file, Object value) {
        final byte[] json;
        try {
            json = PlainJson.write(value, true);
        } catch (IOException | RuntimeException e) {
            log("Save failed for " + file.path() + ": " + e.getMessage());
            return false;
        }
        Runnable task = new Runnable() {
            @Override
            public void run() {
                if (!writeAtomic(file, json)) failedWrites.add(fileKey(file));
            }
        };
        ExecutorService w = writer();
        if (w != null) {
            try {
                lastWrite = w.submit(task);
                return true;
            } catch (RejectedExecutionException e) {
                // stopped concurrently: write inline below
            }
        }
        task.run();
        return true;
    }

    /** The writer, started on first use; null after {@link #dispose()}. */
    private ExecutorService writer() {
        if (writer == null && !disposedWriter) {
            try {
                writer = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "kk-profile-writer");
                    t.setDaemon(true);
                    return t;
                });
            } catch (RuntimeException e) {
                disposedWriter = true;
                log("No writer thread, saving inline: " + e.getMessage());
            }
        }
        return writer;
    }

    /** Name used to re-flag a file: {@code index.json}, {@code device.json} or the profile id. */
    private static String fileKey(FileHandle file) {
        String n = file.name();
        return n.equals(INDEX) || n.equals(DEVICE) ? n : file.nameWithoutExtension();
    }

    /**
     * Writes {@code json}: {@code <file>.tmp} (synced) → copy the current file to
     * {@code <file>.bak} → rename the tmp over the file (see the class doc). Runs on the writer
     * thread (touches nothing but the files).
     *
     * @return false when the save failed: the live file is then unchanged (or, in the Windows
     *         delete-then-rename gap, missing) and a complete new version, if one was written,
     *         stays in {@code .tmp} where {@link #readWithRecovery} finds it
     */
    private static boolean writeAtomic(FileHandle file, byte[] json) {
        FileHandle tmp = file.sibling(file.name() + ".tmp");
        try {
            file.parent().mkdirs();
            writeSynced(tmp, json);
            if (isFile(file) && file.length() > 0) file.copyTo(file.sibling(file.name() + ".bak"));
            replace(tmp, file);
            return true;
        } catch (IOException | RuntimeException e) {
            log("Save failed for " + file.path() + ": " + e.getMessage());
            return false;
        }
    }

    /** Writes the bytes and asks the OS to put them on disk before the rename that publishes them. */
    private static void writeSynced(FileHandle f, byte[] data) throws IOException {
        OutputStream out = f.write(false);
        try {
            out.write(data);
            out.flush();
            if (out instanceof FileOutputStream) {
                try {
                    ((FileOutputStream) out).getFD().sync();
                } catch (IOException ignored) {
                    // best effort: the data is in the OS cache, which survives an app kill
                }
            }
        } finally {
            out.close();
        }
    }

    /**
     * Moves {@code tmp} over {@code file} without ever rewriting {@code file} in place.
     * libGDX's {@code FileHandle.moveTo} is copy + delete for Local files (Android's storage),
     * which truncates the live file first, so the rename is done on {@link File}s directly.
     */
    private static void replace(FileHandle tmp, FileHandle file) {
        Files.FileType t = tmp.type();
        if (t == Files.FileType.Local || t == Files.FileType.External || t == Files.FileType.Absolute) {
            File src = tmp.file();
            File dst = file.file();
            if (src.renameTo(dst)) return; // atomic replace on Android / POSIX
            // Windows refuses to rename onto an existing file. The previous version is in .bak
            // and the new one complete in .tmp (preferred on load), so this gap loses nothing.
            if (dst.isFile() && dst.delete() && src.renameTo(dst)) return;
        }
        tmp.moveTo(file); // last resort: copy + delete
    }

    /**
     * Reads a JSON file with crash recovery (see the class doc): a parseable {@code .tmp} wins
     * (an unparsable one is deleted), then the file, then {@code .bak}. A corrupt file or
     * {@code .bak} is renamed {@code .corrupt-<ts>}. Whatever was recovered is marked dirty so
     * the next save rewrites the file properly.
     *
     * @return the value, or null when nothing readable exists
     */
    private <T> T readWithRecovery(FileHandle file, Class<T> type) {
        FileHandle tmp = file.sibling(file.name() + ".tmp");
        if (isFile(tmp)) {
            T v = null;
            try {
                v = parse(tmp, type);
            } catch (IOException | RuntimeException e) {
                v = null;
            }
            if (v != null) {
                // The live file is older (or a half-copied fallback write): keep a corrupt one aside.
                if (isFile(file) && !parses(file, type)) quarantine(file);
                log("Recovered " + file.name() + " from an interrupted save (" + tmp.name() + ")");
                markFileDirty(file);
                return v;
            }
            log("Discarding incomplete " + tmp.path());
            deleteQuietly(tmp);
        }
        FileHandle[] candidates = {file, file.sibling(file.name() + ".bak")};
        for (int i = 0; i < candidates.length; i++) {
            FileHandle f = candidates[i];
            if (!isFile(f)) continue;
            try {
                T v = parse(f, type);
                if (v != null) {
                    if (i > 0) {
                        log("Recovered " + file.name() + " from " + f.name());
                        markFileDirty(file);
                    }
                    return v;
                }
                throw new IOException("empty JSON");
            } catch (IOException | RuntimeException e) {
                log("Corrupt " + f.path() + " (" + e.getMessage() + ")");
                quarantine(f);
            }
        }
        return null;
    }

    private static boolean isFile(FileHandle f) {
        try {
            return f.exists() && !f.isDirectory();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static <T> boolean parses(FileHandle f, Class<T> type) {
        try {
            return parse(f, type) != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private void markFileDirty(FileHandle file) {
        String n = file.name();
        if (n.equals(INDEX)) indexDirty = true;
        else if (n.equals(DEVICE)) deviceDirty = true;
        else dirtyProfiles.add(file.nameWithoutExtension());
    }

    private static <T> T parse(FileHandle f, Class<T> type) throws IOException {
        byte[] bytes = f.readBytes();
        if (bytes.length == 0) throw new IOException("empty file");
        return PlainJson.read(bytes, type);
    }

    private static void quarantine(FileHandle f) {
        try {
            f.moveTo(f.sibling(f.name() + ".corrupt-" + System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log("Cannot quarantine " + f.path() + ": " + e.getMessage());
        }
    }

    private static void deleteQuietly(FileHandle f) {
        try {
            if (f.exists()) f.delete();
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    private static void log(String msg) {
        if (Gdx.app != null) Gdx.app.log(TAG, msg);
    }
}
