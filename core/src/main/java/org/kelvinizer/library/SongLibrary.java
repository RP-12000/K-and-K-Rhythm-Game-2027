package org.kelvinizer.library;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FileTextureData;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import org.kelvinizer.audio.Mp3Info;
import org.kelvinizer.audio.WavDurationUtil;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.ChartMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 曲库：读取 {@code Charts/index.json} 和各谱面的头信息，在每个歌曲文件夹里找音乐、谱面、曲绘、背景图和结算音乐。
 * 完整谱面在转场遮住画面时按需加载，曲绘延迟加载、可在后台线程解码；新手教程也作为一首「伪歌曲」放在这里。
 *
 * <p>The song catalogue (ARCHITECTURE §8, DESIGN §3.1).
 *
 * <p>{@link #load()} reads only chart headers ({@link ChartHeaderReader}), so it is fast;
 * full charts are parsed on demand by {@link #loadChart(SongMeta, Difficulty)} while a
 * transition covers the screen. Jackets are loaded lazily as mipmapped textures, either
 * synchronously by {@link #jacket(SongMeta)} or without a hitch by {@link #requestJacket(SongMeta)}
 * (decode on a worker) + {@link #jacketIfReady(SongMeta)} (upload), and released with
 * {@link #releaseJackets()}. The 16×16 jacket {@link #wash(FileHandle) wash} shared by gameplay,
 * result and the song card's glow (DESIGN §2.7, §6.2, §4.3) is computed once per jacket, off the render thread by
 * {@link #prepareWash(FileHandle)} alongside the chart parse or from {@link #requestJacket}'s own decode
 * ({@link #washIfReady(SongMeta)}).
 *
 * <p>Discovery order: the manifest {@code Charts/index.json}; folders on disk that the
 * manifest does not list go into the last charted chapter. Without a manifest, folders come
 * from {@code assets.txt} (lines starting with {@code Charts/}), then from listing
 * {@code Charts/}. File lookups inside a song folder use the same index, so they also work
 * where directory listing is unavailable (classpath assets).
 */
public final class SongLibrary implements Disposable {
    /** Name of the charts directory inside the assets root. */
    public static final String CHARTS_DIR = "Charts";
    /** Manifest file name inside the charts directory. */
    public static final String MANIFEST_NAME = "index.json";
    /**
     * Per-song settings file inside a song folder: the keys of a manifest song entry, e.g. {@code previewStartMs},
     * {@code previewEndMs}, {@code jacket16x9} and {@code jacketCircle}; where both set a key, this file wins.
     */
    public static final String SONG_SETTINGS_NAME = "song.json";
    /** Default tutorial folder (sibling of {@code Charts/}). */
    public static final String DEFAULT_TUTORIAL = "Introduction";

    private static final String TAG = "SongLibrary";
    private static final String[] AUDIO_EXT = {"ogg", "mp3", "wav"};
    private static final String[] IMAGE_EXT = {"png", "jpg", "jpeg"};

    private final FileHandle root;
    private final FileHandle chartsRoot;
    private final List<ChapterMeta> chapters = new ArrayList<>();
    private final List<ChapterMeta> chaptersView = Collections.unmodifiableList(chapters);
    private final List<SongMeta> songs = new ArrayList<>();
    private final List<SongMeta> songsView = Collections.unmodifiableList(songs);
    private final Map<String, SongMeta> byId = new HashMap<>();
    private final Map<String, Texture> jackets = new HashMap<>();
    /** Songs whose jacket decode was requested and not released (render thread writes, decoder reads). */
    private final Set<String> wantedJackets = ConcurrentHashMap.newKeySet();
    /** Decoded jacket pixmaps waiting for their upload in {@link #jacketIfReady}. */
    private final Map<String, Pixmap> decodedJackets = new ConcurrentHashMap<>();
    /** Songs whose jacket failed to decode (not retried). */
    private final Set<String> failedJackets = ConcurrentHashMap.newKeySet();
    private ExecutorService jacketDecoder;
    /** 16×16 wash pixmaps by jacket path (any thread) and their textures (render thread). */
    private final Map<String, Pixmap> washPixmaps = new ConcurrentHashMap<>();
    private final Set<String> washFailed = ConcurrentHashMap.newKeySet();
    /** Washes queued by {@link #washIfReady} and not prepared yet (by jacket path). */
    private final Set<String> wantedWashes = ConcurrentHashMap.newKeySet();
    private final Map<String, Texture> washes = new HashMap<>();
    /** Asset index from assets.txt: folder path relative to the root → file names. Null if absent. */
    private Map<String, List<String>> assetIndex;
    private SongMeta tutorial;
    private boolean loaded;
    private long loadMillis;

    /** Creates a library over the internal assets ({@code Charts/} and {@code assets.txt}). */
    public SongLibrary() {
        this(Gdx.files.internal(CHARTS_DIR));
    }

    /**
     * Creates a library over a charts directory (tests may pass an absolute handle). The
     * assets root is its parent: {@code assets.txt} and the tutorial folder are looked up there.
     */
    public SongLibrary(FileHandle chartsRoot) {
        this.chartsRoot = chartsRoot;
        this.root = chartsRoot.parent();
    }

    /**
     * Reads the manifest and every chart header. Safe to call again (reloads). Never throws:
     * broken songs are skipped and logged.
     */
    public void load() {
        long t0 = System.nanoTime();
        chapters.clear();
        songs.clear();
        byId.clear();
        tutorial = null;
        assetIndex = readAssetIndex();

        String tutorialId = DEFAULT_TUTORIAL;
        JsonValue manifest = readManifest();
        Set<String> listed = new LinkedHashSet<>();
        if (manifest != null) {
            tutorialId = text(manifest.get("tutorial"), DEFAULT_TUTORIAL);
            JsonValue chs = manifest.get("chapters");
            if (chs != null && chs.isArray()) {
                for (JsonValue ch = chs.child; ch != null; ch = ch.next) {
                    if (!ch.isObject()) continue;
                    int depth = intOf(ch.get("depthMeters"), 0);
                    ChapterMeta chapter = new ChapterMeta(
                        text(ch.get("id"), "c" + (chapters.size() + 1)),
                        chapters.size(),
                        textOrNull(ch, "title"),
                        textOrNull(ch, "titleZh"),
                        textOrNull(ch, "subtitle"),
                        depth,
                        intOf(ch.get("depthBottom"), depth),
                        boolOf(ch.get("uncharted"), false));
                    chapters.add(chapter);
                    JsonValue list = ch.get("songs");
                    if (list == null || !list.isArray()) continue;
                    for (JsonValue entry = list.child; entry != null; entry = entry.next) {
                        String id = entry.isObject() ? text(entry.get("id"), null) : text(entry, null);
                        if (id == null || id.isEmpty() || listed.contains(id)) continue;
                        listed.add(id);
                        SongMeta song = buildSong(id);
                        if (song == null) continue;
                        if (entry.isObject()) applyManifestEntry(song, entry);
                        applySongSettings(song);
                        attach(chapter, song);
                    }
                }
            }
        }
        if (chapters.isEmpty()) {
            chapters.add(new ChapterMeta("c1", 0, "SHORELINE", "浅滩", "Chapter I", 0, 200, false));
        }

        // Folders on disk that the manifest does not list go into the last charted chapter.
        ChapterMeta lastCharted = chapters.get(0);
        for (ChapterMeta ch : chapters) if (!ch.uncharted()) lastCharted = ch;
        for (String id : discoverFolders()) {
            if (listed.contains(id) || id.equals(tutorialId)) continue;
            listed.add(id);
            SongMeta song = buildSong(id);
            if (song == null) continue;
            applySongSettings(song);
            attach(lastCharted, song);
        }

        tutorial = buildFolderSong(tutorialId, root.child(tutorialId));
        loaded = true;
        loadMillis = (System.nanoTime() - t0) / 1_000_000L;
        log("Loaded " + songs.size() + " songs (" + chartCount() + " charts) in " + chapters.size()
            + " chapters in " + loadMillis + " ms");
    }

    private void attach(ChapterMeta chapter, SongMeta song) {
        song.chapterId = chapter.id();
        song.chapterIndex = chapter.index();
        song.indexInChapter = chapter.songCount();
        song.globalIndex = songs.size();
        chapter.addSong(song);
        songs.add(song);
        byId.put(song.id(), song);
    }

    private static void applyManifestEntry(SongMeta song, JsonValue entry) {
        if (entry.has("previewStartMs")) song.previewStartMs = floatOf(entry.get("previewStartMs"), Float.NaN);
        if (entry.has("previewEndMs")) song.previewEndMs = floatOf(entry.get("previewEndMs"), Float.NaN);
        if (entry.has("resultMusicStartMs")) song.resultMusicStartMs = floatOf(entry.get("resultMusicStartMs"), 0f);
        if (entry.has("jacket16x9")) song.jacket16x9 = boxOf(entry.get("jacket16x9"));
        if (entry.has("jacketCircle")) song.jacketCircle = boxOf(entry.get("jacketCircle"));
        JsonValue map = entry.get("map");
        if (map != null && map.isObject()) {
            song.mapNudgeX = floatOf(map.get("nudgeX"), 0f);
            song.mapNudgeY = floatOf(map.get("nudgeY"), 0f);
        }
    }

    /** Applies the song folder's {@link #SONG_SETTINGS_NAME} when it has one; an unreadable file is ignored. */
    private void applySongSettings(SongMeta song) {
        FileHandle f = song.folder().child(SONG_SETTINGS_NAME);
        if (!f.exists()) return;
        try (InputStream in = f.read()) {
            JsonValue v = new JsonReader().parse(in);
            if (v != null && v.isObject()) applyManifestEntry(song, v);
            else log("Ignoring " + f.path() + ": not a JSON object");
        } catch (IOException | RuntimeException e) {
            log("Ignoring " + f.path() + " (" + e.getMessage() + ")");
        }
    }

    private SongMeta buildSong(String id) {
        SongMeta song = buildFolderSong(id, chartsRoot.child(id));
        if (song == null) log("Skipping '" + id + "': no readable chart");
        return song;
    }

    /** Reads the headers of one song folder; returns null when it has no readable chart. */
    private SongMeta buildFolderSong(String id, FileHandle folder) {
        List<String> files = filesIn(folder, id);
        SongMeta song = new SongMeta(id, folder);
        ChartHeaderReader.Header display = null;
        int displayRank = Integer.MAX_VALUE;
        for (Difficulty d : Difficulty.values()) {
            FileHandle f = folder.child(d.fileName());
            if (files != null ? !containsIgnoreCase(files, d.fileName()) : !f.exists()) continue;
            if (files != null) f = folder.child(actualName(files, d.fileName()));
            ChartHeaderReader.Header h;
            try {
                h = ChartHeaderReader.read(f);
            } catch (IOException e) {
                log("Bad chart header " + f.path() + ": " + e.getMessage());
                continue;
            }
            int i = d.ordinal();
            song.present[i] = true;
            song.chartFiles[i] = f;
            song.constantText[i] = h.chartConstant;
            song.constantBase[i] = ConstantLabel.base(h.chartConstant);
            song.constantMark[i] = ConstantLabel.mark(h.chartConstant);
            song.constants[i] = parseConstant(h.chartConstant);
            song.chartTitles[i] = h.title;
            song.charters[i] = h.charter;
            // Display metadata: SHALLOW first, then SURFACE, then whatever exists.
            int rank = d == Difficulty.SHALLOW ? 0 : d == Difficulty.SURFACE ? 1 : 2 + i;
            if (rank < displayRank) {
                displayRank = rank;
                display = h;
            }
        }
        if (display == null) return null;
        song.title = nonEmpty(display.title, id);
        song.artist = nonEmpty(display.artist, "");
        song.illustrator = nonEmpty(display.illustrator, "");
        song.charter = nonEmpty(display.charter, "");
        song.bpm = display.bpm;
        song.bpmMin = display.bpmMin;
        song.bpmMax = display.bpmMax;

        song.music = pickMusic(folder, id, files);
        song.jacket = pickNamedOrFirst(folder, files, "jacket", IMAGE_EXT, "bg");
        song.background = pickNamed(folder, files, "bg", IMAGE_EXT);
        song.resultMusic = pickNamed(folder, files, "result", AUDIO_EXT);
        if (song.music != null && "wav".equalsIgnoreCase(song.music.extension())) {
            song.durationMs = WavDurationUtil.getDurationMillisSafe(song.music);
        } else if (Mp3Info.isMp3(song.music)) {
            song.durationMs = Mp3Info.durationMs(song.music);
        }
        return song;
    }

    // ------------------------------------------------------------------ queries

    /** @return true once {@link #load()} has run. */
    public boolean isLoaded() { return loaded; }

    /** @return chapters in manifest order (unmodifiable). */
    public List<ChapterMeta> chapters() { return chaptersView; }

    /** @return every song in map order: by chapter, then position (unmodifiable). */
    public List<SongMeta> songs() { return songsView; }

    /** @return the song with this id (folder name), or null. */
    public SongMeta song(String id) {
        return id == null ? null : byId.get(id);
    }

    /** @return the chapter with this id, or null. */
    public ChapterMeta chapter(String id) {
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).id().equals(id)) return chapters.get(i);
        }
        return null;
    }

    /** @return number of difficulty charts across every song. */
    public int chartCount() {
        int n = 0;
        for (int i = 0; i < songs.size(); i++) n += songs.get(i).chartCount();
        return n;
    }

    /**
     * @return the tutorial pseudo-song (manifest {@code tutorial}, default "Introduction"),
     *         not part of {@link #songs()}; null when its folder is missing
     */
    public SongMeta tutorial() { return tutorial; }

    /**
     * Finds the song id for a title saved by the old title-keyed {@code user.json}
     * (ARCHITECTURE §7): an exact folder-name match wins (this resolves the old
     * "marsh mally" Surface ambiguity), then a display-title match, then any chart's title.
     *
     * @return the song id, or null
     */
    public String songIdForLegacyTitle(String title) {
        if (title == null) return null;
        if (byId.containsKey(title)) return title;
        for (SongMeta s : songs) if (s.id().equalsIgnoreCase(title)) return s.id();
        for (SongMeta s : songs) if (s.title().equalsIgnoreCase(title)) return s.id();
        for (SongMeta s : songs) {
            for (Difficulty d : Difficulty.values()) {
                if (title.equalsIgnoreCase(s.chartTitle(d))) return s.id();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ charts

    /**
     * Parses the full chart of a song (notes included) via {@link ChartMapper}; call it while
     * a transition covers the screen.
     *
     * @throws IOException when the chart is missing or unreadable
     */
    public Chart loadChart(SongMeta song, Difficulty d) throws IOException {
        FileHandle f = song.chartFile(d);
        if (f == null) throw new IOException(song.id() + " has no " + d + " chart");
        return ChartMapper.read(f, song.music(), song.jacket());
    }

    /** Parses the tutorial chart (its SURFACE chart). */
    public Chart loadTutorialChart() throws IOException {
        if (tutorial == null) throw new IOException("Tutorial folder missing");
        return loadChart(tutorial, tutorial.easiest());
    }

    // ------------------------------------------------------------------ jackets (render thread unless noted)

    /**
     * @return the song's jacket texture (lazily loaded; mipmaps + trilinear filtering where the
     *         GPU can mipmap it, else linear, see {@link #canMipmap(int, int)}), or null when
     *         the song has no jacket or it failed to load
     */
    public Texture jacket(SongMeta song) {
        if (song == null || song.jacket() == null) return null;
        Texture t = jackets.get(song.id());
        if (t != null) return t;
        Pixmap pm = decodedJackets.remove(song.id());
        wantedJackets.remove(song.id()); // a decode still running for it disposes its result
        try {
            if (pm == null) pm = new Pixmap(song.jacket());
            t = upload(song, pm);
        } catch (RuntimeException e) {
            log("Jacket failed for " + song.id() + ": " + e.getMessage());
            return null;
        }
        jackets.put(song.id(), t);
        return t;
    }

    /**
     * Starts decoding the song's jacket on the decoder thread (one daemon thread, in request
     * order) unless it is loaded, decoded, already requested or failed before. Render thread.
     *
     * @return true when the texture is already loaded ({@link #jacketIfReady} returns it)
     */
    public boolean requestJacket(SongMeta song) {
        if (song == null || song.jacket() == null) return false;
        final String id = song.id();
        if (jackets.containsKey(id)) return true;
        if (failedJackets.contains(id) || decodedJackets.containsKey(id) || !wantedJackets.add(id)) return false;
        final FileHandle file = song.jacket();
        decoder().execute(new Runnable() {
            @Override
            public void run() {
                if (!wantedJackets.contains(id) || decodedJackets.containsKey(id)) return;
                Pixmap pm;
                try {
                    pm = new Pixmap(file);
                } catch (RuntimeException e) {
                    failedJackets.add(id);
                    wantedJackets.remove(id);
                    log("Jacket failed for " + id + ": " + e.getMessage());
                    return;
                }
                // the wash from the same decode, so washIfReady never decodes the jacket a second time
                try {
                    washFrom(file.path(), pm);
                } catch (RuntimeException e) {
                    washFailed.add(file.path());
                    log("Jacket wash failed for " + file.path() + ": " + e.getMessage());
                }
                Pixmap old = decodedJackets.put(id, pm);
                if (old != null) disposeQuietly(old);
                // released while decoding: drop it unless the render thread already took it
                if (!wantedJackets.contains(id) && decodedJackets.remove(id, pm)) disposeQuietly(pm);
            }
        });
        return false;
    }

    /**
     * The song's jacket once {@link #requestJacket} decoded it (uploads it on this call, render
     * thread), else null (also before any request; this does not start one).
     */
    public Texture jacketIfReady(SongMeta song) {
        if (song == null) return null;
        Texture t = jackets.get(song.id());
        if (t != null) return t;
        Pixmap pm = decodedJackets.remove(song.id());
        if (pm == null) return null;
        wantedJackets.remove(song.id());
        try {
            t = upload(song, pm);
        } catch (RuntimeException e) {
            failedJackets.add(song.id());
            log("Jacket upload failed for " + song.id() + ": " + e.getMessage());
            return null;
        }
        jackets.put(song.id(), t);
        return t;
    }

    /**
     * Uploads a decoded jacket. FileTextureData keeps the texture managed (reloaded from the file
     * after an Android context loss); the upload consumes and disposes {@code pm}.
     */
    private static Texture upload(SongMeta song, Pixmap pm) {
        boolean mip = canMipmap(pm.getWidth(), pm.getHeight());
        Texture t;
        try {
            t = new Texture(new FileTextureData(song.jacket(), pm, null, mip));
            pm = null;
        } finally {
            if (pm != null) disposeQuietly(pm);
        }
        t.setFilter(mip ? Texture.TextureFilter.MipMapLinearLinear : Texture.TextureFilter.Linear,
            Texture.TextureFilter.Linear);
        return t;
    }

    private ExecutorService decoder() {
        if (jacketDecoder == null) {
            jacketDecoder = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "kk-jacket-decoder");
                t.setDaemon(true);
                return t;
            });
        }
        return jacketDecoder;
    }

    /**
     * Whether a texture of this size can be mipmapped here. Power-of-two sizes always can.
     * Shipped jackets are 720×720: on a GLES 2.0 context (Android, {@code useGL30} off) libGDX
     * builds mipmaps with {@code glGenerateMipmap}, which leaves a non-power-of-two texture
     * incomplete (it samples black) unless {@code GL_OES_texture_npot} is present. Desktop GL
     * and GLES 3 handle NPOT mipmaps.
     */
    static boolean canMipmap(int width, int height) {
        if (MathUtils.isPowerOfTwo(width) && MathUtils.isPowerOfTwo(height)) return true;
        if (Gdx.app == null || Gdx.graphics == null) return false;
        if (Gdx.app.getType() == Application.ApplicationType.Desktop) return true;
        return Gdx.graphics.isGL30Available() || Gdx.graphics.supportsExtension("GL_OES_texture_npot");
    }

    private static void disposeQuietly(Pixmap pm) {
        try {
            pm.dispose();
        } catch (RuntimeException ignored) {
            // already disposed by a failed upload
        }
    }

    /** @return true when the song's jacket texture is currently loaded. */
    public boolean isJacketLoaded(SongMeta song) {
        return song != null && jackets.containsKey(song.id());
    }

    /** Disposes one song's jacket texture if loaded, and drops a pending or decoded request. */
    public void releaseJacket(SongMeta song) {
        if (song == null) return;
        Texture t = jackets.remove(song.id());
        if (t != null) t.dispose();
        dropDecoded(song.id());
    }

    /** Disposes every loaded jacket except {@code keep} (may be null); pending requests are dropped too. */
    public void releaseJacketsExcept(SongMeta keep) {
        String keepId = keep != null ? keep.id() : null;
        Texture kept = keepId != null ? jackets.remove(keepId) : null;
        for (Texture t : jackets.values()) t.dispose();
        jackets.clear();
        if (kept != null) jackets.put(keepId, kept);
        for (String id : decodedJackets.keySet()) {
            if (!id.equals(keepId)) dropDecoded(id);
        }
        wantedJackets.retainAll(keepId != null ? Collections.singleton(keepId) : Collections.<String>emptySet());
    }

    private void dropDecoded(String id) {
        wantedJackets.remove(id);
        Pixmap pm = decodedJackets.remove(id);
        if (pm != null) disposeQuietly(pm);
    }

    // ------------------------------------------------------------------ jacket wash (DESIGN §2.7, §6.2)

    /**
     * Computes the 16×16 wash of {@code jacket} (decode, then halve with bilinear filtering down
     * to 16×16) unless it exists or failed before. Any thread: Main's chart loader calls it right
     * after the parse, so {@link #wash(FileHandle)} only uploads 1 KB on the render thread.
     */
    public void prepareWash(FileHandle jacket) {
        if (jacket == null) return;
        String key = jacket.path();
        if (washPixmaps.containsKey(key) || washFailed.contains(key)) return;
        Pixmap src = null;
        try {
            src = new Pixmap(jacket);
            washFrom(key, src);
        } catch (RuntimeException e) {
            washFailed.add(key);
            log("Jacket wash failed for " + key + ": " + e.getMessage());
        } finally {
            if (src != null) src.dispose();
        }
    }

    /** The wash of the decoded jacket {@code src} under {@code key} unless it exists ({@code src} stays theirs). */
    private void washFrom(String key, Pixmap src) {
        if (washPixmaps.containsKey(key)) return;
        Pixmap cur = src;
        try {
            while (cur.getWidth() > 32 || cur.getHeight() > 32) {
                int w = Math.max(16, cur.getWidth() / 2), h = Math.max(16, cur.getHeight() / 2);
                Pixmap next = new Pixmap(w, h, Pixmap.Format.RGBA8888);
                next.setFilter(Pixmap.Filter.BiLinear);
                next.drawPixmap(cur, 0, 0, cur.getWidth(), cur.getHeight(), 0, 0, w, h);
                if (cur != src) cur.dispose();
                cur = next;
            }
            Pixmap out = new Pixmap(16, 16, Pixmap.Format.RGBA8888);
            out.setFilter(Pixmap.Filter.BiLinear);
            out.drawPixmap(cur, 0, 0, cur.getWidth(), cur.getHeight(), 0, 0, 16, 16);
            if (washPixmaps.putIfAbsent(key, out) != null) out.dispose();
        } finally {
            if (cur != src) cur.dispose();
        }
    }

    /**
     * The 16×16 wash texture of {@code jacket} (Linear, managed), shared by every caller and owned
     * by the library (do not dispose it); computed here if {@link #prepareWash} has not run. Null
     * without a jacket or when it cannot be decoded. Render thread.
     */
    public Texture wash(FileHandle jacket) {
        if (jacket == null) return null;
        String key = jacket.path();
        Texture t = washes.get(key);
        if (t != null) return t;
        prepareWash(jacket);
        Pixmap pm = washPixmaps.get(key);
        if (pm == null) return null;
        t = new Texture(new PixmapTextureData(pm, Pixmap.Format.RGBA8888, false, false, true));
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        washes.put(key, t);
        return t;
    }

    /** {@link #wash(FileHandle)} of the song's jacket (null for a null song). */
    public Texture wash(SongMeta song) {
        return song != null ? wash(song.jacket()) : null;
    }

    /**
     * The wash of the song's jacket when it is ready (uploading the prepared 16 × 16 pixmap on this call), else
     * null after queueing {@link #prepareWash} on the decoder thread, so the render thread never decodes for it (the
     * song card's glow; {@link #requestJacket} prepares it from its own decode). Render thread.
     */
    public Texture washIfReady(SongMeta song) {
        final FileHandle file = song != null ? song.jacket() : null;
        if (file == null) return null;
        final String key = file.path();
        Texture t = washes.get(key);
        if (t != null) return t;
        if (washPixmaps.containsKey(key)) return wash(file);
        if (washFailed.contains(key) || !wantedWashes.add(key)) return null;
        decoder().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    prepareWash(file);
                } finally {
                    wantedWashes.remove(key);
                }
            }
        });
        return null;
    }

    /** Disposes every loaded jacket. */
    public void releaseJackets() {
        releaseJacketsExcept(null);
    }

    /**
     * Releases jacket and wash textures and stops the decoder. The library stays usable (both
     * reload lazily).
     */
    @Override
    public void dispose() {
        if (jacketDecoder != null) {
            jacketDecoder.shutdownNow();
            jacketDecoder = null;
        }
        releaseJackets();
        failedJackets.clear();
        for (Texture t : washes.values()) t.dispose();
        washes.clear();
        for (Pixmap pm : washPixmaps.values()) disposeQuietly(pm);
        washPixmaps.clear();
        washFailed.clear();
    }

    // ------------------------------------------------------------------ discovery helpers

    /** The manifest as a libGDX {@link JsonValue} tree (no reflection), or null when absent or unreadable. */
    private JsonValue readManifest() {
        FileHandle f = chartsRoot.child(MANIFEST_NAME);
        if (!f.exists()) {
            log("No manifest " + f.path() + "; falling back to folder discovery");
            return null;
        }
        try (InputStream in = f.read()) {
            JsonValue v = new JsonReader().parse(in);
            if (v != null && v.isObject()) return v;
            log("Manifest is not a JSON object; falling back to folder discovery");
            return null;
        } catch (IOException | RuntimeException e) {
            log("Manifest unreadable (" + e.getMessage() + "); falling back to folder discovery");
            return null;
        }
    }

    /** Reads {@code assets.txt} into folder → file names; null when absent. */
    private Map<String, List<String>> readAssetIndex() {
        FileHandle f = root.child("assets.txt");
        if (!f.exists()) return null;
        Map<String, List<String>> index = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(f.reader("UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim().replace('\\', '/');
                if (line.isEmpty()) continue;
                int slash = line.lastIndexOf('/');
                String dir = slash < 0 ? "" : line.substring(0, slash);
                String name = line.substring(slash + 1);
                List<String> names = index.get(dir);
                if (names == null) {
                    names = new ArrayList<>();
                    index.put(dir, names);
                }
                names.add(name);
            }
        } catch (IOException | RuntimeException e) {
            log("assets.txt unreadable: " + e.getMessage());
            return null;
        }
        return index;
    }

    /** @return song folder names found on disk (sorted), from assets.txt or a directory listing. */
    private Set<String> discoverFolders() {
        Set<String> out = new TreeSet<>();
        if (assetIndex != null) {
            String prefix = CHARTS_DIR + "/";
            for (Map.Entry<String, List<String>> e : assetIndex.entrySet()) {
                String dir = e.getKey();
                if (!dir.startsWith(prefix)) continue;
                String id = dir.substring(prefix.length());
                if (id.isEmpty() || id.indexOf('/') >= 0) continue;
                if (hasChartFile(e.getValue())) out.add(id);
            }
            if (!out.isEmpty()) return out;
        }
        try {
            for (FileHandle f : chartsRoot.list()) {
                if (f.isDirectory()) out.add(f.name());
            }
        } catch (RuntimeException e) {
            log("Cannot list " + chartsRoot.path() + ": " + e.getMessage());
        }
        return out;
    }

    private static boolean hasChartFile(List<String> names) {
        for (Difficulty d : Difficulty.values()) if (containsIgnoreCase(names, d.fileName())) return true;
        return false;
    }

    /**
     * @return file names in a song folder from assets.txt or a listing, or null when neither
     *         is available (callers then probe with {@code exists()})
     */
    private List<String> filesIn(FileHandle folder, String id) {
        if (assetIndex != null) {
            List<String> names = assetIndex.get(folder == null ? "" : relativePath(folder, id));
            if (names != null) return names;
        }
        try {
            FileHandle[] list = folder.list();
            if (list.length > 0) {
                List<String> names = new ArrayList<>(list.length);
                for (FileHandle f : list) if (!f.isDirectory()) names.add(f.name());
                Collections.sort(names);
                return names;
            }
        } catch (RuntimeException ignored) {
            // classpath folders cannot be listed: probe instead
        }
        return null;
    }

    private String relativePath(FileHandle folder, String id) {
        String chartsPrefix = chartsRoot.path();
        String p = folder.path();
        if (p.startsWith(chartsPrefix + "/")) return CHARTS_DIR + "/" + id;
        return id;
    }

    private static FileHandle pickMusic(FileHandle folder, String id, List<String> files) {
        for (String ext : AUDIO_EXT) {
            String name = id + "." + ext;
            if (files != null) {
                // assets.txt is only rewritten by a Gradle build: skip names that no longer exist
                // (e.g. a .wav replaced by an .mp3 since)
                if (containsIgnoreCase(files, name)) {
                    FileHandle f = folder.child(actualName(files, name));
                    if (f.exists()) return f;
                }
            } else {
                FileHandle f = folder.child(name);
                if (f.exists()) return f;
            }
        }
        if (files == null) return null;
        for (String name : files) {
            String lower = name.toLowerCase(Locale.ROOT);
            String base = stripExtension(lower);
            if (hasExtension(lower, AUDIO_EXT) && !base.equals("result") && !base.startsWith("hit")
                && !base.startsWith("hold_end") && folder.child(name).exists()) {
                return folder.child(name);
            }
        }
        // a stale assets.txt may not list the current file at all: look for it directly
        for (String ext : AUDIO_EXT) {
            FileHandle f = folder.child(id + "." + ext);
            if (f.exists()) return f;
        }
        return null;
    }

    private static FileHandle pickNamed(FileHandle folder, List<String> files, String base, String[] exts) {
        for (String ext : exts) {
            String name = base + "." + ext;
            if (files != null) {
                if (containsIgnoreCase(files, name)) return folder.child(actualName(files, name));
            } else {
                FileHandle f = folder.child(name);
                if (f.exists()) return f;
            }
        }
        return null;
    }

    private static FileHandle pickNamedOrFirst(FileHandle folder, List<String> files, String base, String[] exts,
                                               String excludeBase) {
        FileHandle named = pickNamed(folder, files, base, exts);
        if (named != null || files == null) return named;
        for (String name : files) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (hasExtension(lower, exts) && !stripExtension(lower).equals(excludeBase)) return folder.child(name);
        }
        return null;
    }

    private static boolean hasExtension(String lowerName, String[] exts) {
        for (String ext : exts) if (lowerName.endsWith("." + ext)) return true;
        return false;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static boolean containsIgnoreCase(List<String> names, String name) {
        return names != null && actualName(names, name) != null;
    }

    private static String actualName(List<String> names, String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) return names.get(i);
        }
        return null;
    }

    private static float parseConstant(String text) {
        if (text == null) return 1f;
        try {
            float v = Float.parseFloat(text.trim());
            return Float.isNaN(v) || Float.isInfinite(v) ? 1f : v;
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    private static String textOrNull(JsonValue node, String field) {
        return text(node.get(field), null);
    }

    /** The value's text, or {@code def} when it is absent, JSON null or not a scalar. */
    private static String text(JsonValue v, String def) {
        return v == null || v.isNull() || !v.isValue() ? def : v.asString();
    }

    /** The value as an int (numbers, numeric strings and booleans), else {@code def}. */
    private static int intOf(JsonValue v, int def) {
        if (v == null || !v.isValue() || v.isNull()) return def;
        if (v.isNumber() || v.isBoolean()) {
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v.asLong()));
        }
        try {
            return (int) Double.parseDouble(v.asString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * A jacket framing box {x, y, w, h} (fractions of the image, y from the top), or null (the centre crop) unless it
     * is an object of four numbers with a positive size inside the image.
     */
    private static float[] boxOf(JsonValue v) {
        if (v == null || !v.isObject()) return null;
        float x = floatOf(v.get("x"), Float.NaN), y = floatOf(v.get("y"), Float.NaN);
        float w = floatOf(v.get("w"), Float.NaN), h = floatOf(v.get("h"), Float.NaN);
        float slack = 1e-3f;
        if (!(w > 0f && h > 0f && x >= -slack && y >= -slack && x + w <= 1f + slack && y + h <= 1f + slack)) {
            return null;
        }
        x = Math.max(0f, x);
        y = Math.max(0f, y);
        return new float[] {x, y, Math.min(w, 1f - x), Math.min(h, 1f - y)};
    }

    /** The value as a float (numbers and numeric strings), else {@code def}. */
    private static float floatOf(JsonValue v, float def) {
        if (v == null || !v.isValue() || v.isNull()) return def;
        if (v.isNumber()) return v.asFloat();
        if (v.isBoolean()) return def;
        try {
            return Float.parseFloat(v.asString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * The value as a boolean ({@code true}/{@code false}, either as JSON or as text, or a number ≠ 0), else
     * {@code def}.
     */
    private static boolean boolOf(JsonValue v, boolean def) {
        if (v == null || !v.isValue() || v.isNull()) return def;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) return v.asDouble() != 0.0;
        String s = v.asString().trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        return def;
    }

    private static String nonEmpty(String s, String fallback) {
        return s == null || s.trim().isEmpty() ? fallback : s;
    }

    private static void log(String msg) {
        if (Gdx.app != null) Gdx.app.log(TAG, msg);
    }
}
