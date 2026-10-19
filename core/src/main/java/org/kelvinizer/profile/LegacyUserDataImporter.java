package org.kelvinizer.profile;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongLibrary;
import org.kelvinizer.library.SongMeta;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * 把原版游戏按歌名记录的 {@code KandKGame/user.json} 导入一个潜水员存档，歌名换成歌曲 id。
 * 旧的 10,000,000 分制分数乘 10 换到新分制；导入成功后文件改名为 {@code user.json.imported}。
 *
 * <p>Imports the old title-keyed {@code KandKGame/user.json} into a profile (ARCHITECTURE §7).
 *
 * <p>Old format: {@code {"data": {title: {DIFF: {"historyBest": int, "recentAttempts": [int…]}}},
 * "lastSelectedSongTitle": …, "lastSelectedDifficulty": …}}. Titles are mapped to song ids
 * with {@link SongLibrary#songIdForLegacyTitle(String)} (an exact folder-name match wins,
 * which resolves the old "marsh mally" Surface collision). After a successful import the file
 * is renamed to {@code user.json.imported}. The file is read as a libGDX {@link JsonValue} tree
 * (no reflection, any Android API level).
 *
 * <p>The old game scored out of 10,000,000; every imported score is converted to the
 * 100,000,000 scale of scoring v2 (× 10, ARCHITECTURE §18) with
 * {@link ChartRecord#toNewScale(int)}. Import only into a profile already on the current
 * {@link Profile#scoreScale} (every loaded or new profile is).
 */
public final class LegacyUserDataImporter {
    /** Path of the old save, relative to external storage. */
    public static final String LEGACY_PATH = "KandKGame/user.json";
    /** Suffix appended after import. */
    public static final String IMPORTED_SUFFIX = ".imported";

    private LegacyUserDataImporter() {
    }

    /** @return the old save file handle, or null when external storage is unavailable. */
    public static FileHandle legacyFile() {
        try {
            if (Gdx.files == null || !Gdx.files.isExternalStorageAvailable()) return null;
            return Gdx.files.external(LEGACY_PATH);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** @return true when an old save exists and has not been imported yet. */
    public static boolean available() {
        FileHandle f = legacyFile();
        try {
            return f != null && f.exists() && !f.isDirectory();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Merges an old save into {@code profile}. Unknown titles and difficulties are skipped.
     *
     * @return number of chart records imported, or −1 when the file could not be parsed
     */
    public static int importInto(Profile profile, SongLibrary library, FileHandle file) {
        JsonValue root;
        try (Reader r = file.reader("UTF-8")) {
            root = new JsonReader().parse(r);
        } catch (IOException | RuntimeException e) {
            log("Cannot parse " + file.path() + ": " + e.getMessage());
            return -1;
        }
        if (root == null || !root.isObject()) return -1;
        // the imported scores are converted to the 100 M scale (×10, ARCHITECTURE §18): bring the
        // target's own records to that scale first so a later load cannot multiply them again
        profile.migrateScoreScale();
        int imported = 0;
        JsonValue data = root.get("data");
        if (data != null && data.isObject()) {
            for (JsonValue te = data.child; te != null; te = te.next) {
                String songId = library != null ? library.songIdForLegacyTitle(te.name) : null;
                if (songId == null) {
                    log("Skipping unknown legacy title '" + te.name + "'");
                    continue;
                }
                SongMeta song = library.song(songId);
                for (JsonValue de = te.isObject() ? te.child : null; de != null; de = de.next) {
                    Difficulty d = Difficulty.parse(de.name);
                    if (d == null || song == null || !song.has(d)) continue;
                    if (importScore(profile, songId, d, song.constant(d), de)) imported++;
                }
            }
        }
        String lastTitle = text(root.get("lastSelectedSongTitle"));
        String lastSong = library != null ? library.songIdForLegacyTitle(lastTitle) : null;
        if (lastSong != null && profile.lastSongId == null) {
            profile.lastSongId = lastSong;
            Difficulty d = Difficulty.parse(text(root.get("lastSelectedDifficulty")));
            if (d != null) profile.lastDifficulty = d.name();
        }
        return imported;
    }

    private static boolean importScore(Profile profile, String songId, Difficulty d, float constant, JsonValue node) {
        if (node == null || !node.isObject()) return false;
        int best = clampScore(intOf(node.get("historyBest")));
        List<Integer> recent = new ArrayList<>();
        JsonValue arr = node.get("recentAttempts");
        if (arr != null && arr.isArray()) {
            for (JsonValue v = arr.child; v != null; v = v.next) {
                int s = clampScore(intOf(v));
                if (s > 0 && recent.size() < ChartRecord.RECENT_MAX) recent.add(s);
                if (s > best) best = s;
            }
        }
        if (best <= 0 && recent.isEmpty()) return false;
        ChartRecord rec = profile.recordOrCreate(songId, d);
        List<Integer> merged = new ArrayList<>(recent);
        if (rec.recent != null) merged.addAll(rec.recent);
        while (merged.size() > ChartRecord.RECENT_MAX) merged.remove(merged.size() - 1);
        rec.recent = merged;
        rec.plays = Math.max(rec.plays, Math.max(1, recent.size()));
        if (best > rec.bestScore) {
            rec.bestScore = best;
            rec.bestGrade = Grades.gradeOf(best);
        } else if (rec.bestGrade == null) {
            rec.bestGrade = Grades.gradeOf(rec.bestScore);
        }
        rec.bestDepth = Depth.chartDepth(constant, rec.bestScore);
        // a song played in the old game counts as owned, like a single played before shells existed
        if (!profile.ownedSongs.contains(songId)) profile.ownedSongs.add(songId);
        if (rec.firstPlayedAt == 0) rec.firstPlayedAt = System.currentTimeMillis();
        if (rec.lastPlayedAt == 0) rec.lastPlayedAt = rec.firstPlayedAt;
        return true;
    }

    /**
     * Renames the imported file to {@code user.json.imported} so it is not imported twice.
     *
     * @return true on success
     */
    public static boolean markImported(FileHandle file) {
        try {
            FileHandle dest = file.sibling(file.name() + IMPORTED_SUFFIX);
            if (dest.exists()) dest = file.sibling(file.name() + IMPORTED_SUFFIX + "-" + System.currentTimeMillis());
            file.moveTo(dest);
            return true;
        } catch (RuntimeException e) {
            log("Cannot rename " + file.path() + ": " + e.getMessage());
            return false;
        }
    }

    /** The value's text, or null when it is missing, JSON null or not a scalar. */
    private static String text(JsonValue v) {
        return v == null || v.isNull() || !v.isValue() ? null : v.asString();
    }

    /** The value as an int, or 0 when it is missing, not a number or an unparsable string. */
    private static int intOf(JsonValue v) {
        if (v == null) return 0;
        if (v.isNumber()) return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v.asLong()));
        if (v.isString()) {
            try {
                return Integer.parseInt(v.asString().trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /** An old-game score (0..10,000,000) on the 100,000,000 scale. */
    private static int clampScore(int s) {
        return ChartRecord.toNewScale(s);
    }

    private static void log(String msg) {
        if (Gdx.app != null) Gdx.app.log("LegacyImport", msg);
    }
}
