package org.kelvinizer.map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.utils.Disposable;
import org.kelvinizer.library.SongMeta;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 节点上的圆形缩略图：后台线程把曲绘裁成 256² 的圆形（按歌曲设的圆形取景，没设就取中间），
 * 缓存到 {@code cache/thumbs/}，每 16 张拼成一页贴图，所有节点从同一张贴图画。
 * 缩略图没准备好时，节点先画一个占位圆。
 *
 * <p>Node thumbnails (DESIGN §3.3): each jacket is decoded, cropped to the song's circle framing (else its
 * centre), scaled to 256² in two bilinear steps and masked to a soft circle on a worker thread (CPU only, no GL),
 * cached as {@code local("cache/thumbs/<id>-<modified>-<length>[-c<framing hash>].png")} and packed 16 per 1024²
 * page so all nodes draw from one texture. The GL thread copies finished thumbs into the page pixmap and
 * re-uploads the page (with mipmaps) at most once per frame while thumbs keep arriving; until a
 * thumb is ready {@link #region(int)} returns null and the node draws a placeholder disc.
 *
 * <p>Pages are managed textures backed by retained pixmaps, so they survive an Android context
 * loss. The average jacket colour of each song is kept too ({@link #average(int, Color)}).
 */
final class ThumbAtlas implements Disposable {
    /** Thumb size, page size and layout. */
    static final int SIZE = 256, PAGE = 1024, COLS = PAGE / SIZE, PER_PAGE = COLS * COLS;
    /** Radius of the circular mask inside a 256² cell (leaves a transparent margin for mipmaps). */
    static final float MASK_R = 124f;
    /** World diameter of a full cell when the visible circle must be {@code d} across. */
    static float cellSizeFor(float d) {
        return d * SIZE / (2f * MASK_R);
    }

    private static final class Result {
        final int slot;
        final Pixmap pixmap;
        final int avg;

        Result(int slot, Pixmap pixmap, int avg) {
            this.slot = slot;
            this.pixmap = pixmap;
            this.avg = avg;
        }
    }

    private final SongMeta[] songs;
    private final Pixmap[] pages;
    private final Texture[] textures;
    private final boolean[] pageDirty;
    private final TextureRegion[] regions;
    private final int[] avgRgba;
    private final ConcurrentLinkedQueue<Result> done = new ConcurrentLinkedQueue<>();
    private Thread worker;
    private volatile boolean stopped;
    private boolean disposed;
    private int readyCount;

    /** Creates the atlas for {@code songs}; slot i belongs to {@code songs.get(i)}. */
    ThumbAtlas(List<SongMeta> songs) {
        this.songs = songs.toArray(new SongMeta[0]);
        int pageCount = Math.max(1, (this.songs.length + PER_PAGE - 1) / PER_PAGE);
        pages = new Pixmap[pageCount];
        textures = new Texture[pageCount];
        pageDirty = new boolean[pageCount];
        regions = new TextureRegion[this.songs.length];
        avgRgba = new int[this.songs.length];
    }

    /** Starts building in the background (idempotent). */
    void start() {
        if (worker != null || disposed || songs.length == 0) return;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                build();
            }
        }, "kk-thumbs");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    /** Number of ready thumbs. */
    int readyCount() {
        return readyCount;
    }

    /** The thumb of slot {@code i}, or null while it is not ready. */
    TextureRegion region(int i) {
        return i >= 0 && i < regions.length ? regions[i] : null;
    }

    /** Writes the average jacket colour of slot {@code i} into {@code out} (ink grey before ready). */
    Color average(int i, Color out) {
        if (i < 0 || i >= avgRgba.length || avgRgba[i] == 0) return out.set(0.3f, 0.35f, 0.5f, 1f);
        return out.set(avgRgba[i]);
    }

    /**
     * GL thread, once per frame: copies finished thumbs into their pages and re-uploads each
     * changed page once. Allocates only while thumbs arrive (a region per thumb, a texture per
     * page).
     */
    void update() {
        if (disposed) return;
        Result r;
        while ((r = done.poll()) != null) {
            int page = r.slot / PER_PAGE, cell = r.slot % PER_PAGE;
            Pixmap pm = pages[page];
            if (pm == null) {
                pm = new Pixmap(PAGE, PAGE, Pixmap.Format.RGBA8888);
                pm.setBlending(Pixmap.Blending.None);
                pm.setColor(0f, 0f, 0f, 0f);
                pm.fill();
                pages[page] = pm;
            }
            pm.setBlending(Pixmap.Blending.None);
            pm.drawPixmap(r.pixmap, (cell % COLS) * SIZE, (cell / COLS) * SIZE);
            r.pixmap.dispose();
            avgRgba[r.slot] = r.avg;
            pageDirty[page] = true;
        }
        for (int p = 0; p < pages.length; p++) {
            if (!pageDirty[p]) continue;
            pageDirty[p] = false;
            PixmapTextureData data = new PixmapTextureData(pages[p], null, true, false, true);
            if (textures[p] == null) {
                textures[p] = new Texture(data);
            } else {
                textures[p].load(data);
            }
            textures[p].setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            for (int i = p * PER_PAGE; i < Math.min(regions.length, (p + 1) * PER_PAGE); i++) {
                if (regions[i] == null && avgRgba[i] != 0) {
                    int cell = i % PER_PAGE;
                    regions[i] = new TextureRegion(textures[p], (cell % COLS) * SIZE, (cell / COLS) * SIZE, SIZE, SIZE);
                    readyCount++;
                }
            }
        }
    }

    // ------------------------------------------------------------------ worker
    private void build() {
        for (int i = 0; i < songs.length && !stopped; i++) {
            Pixmap thumb = null;
            try {
                thumb = make(songs[i]);
                if (thumb == null) continue;
                int avg = averageOf(thumb);
                if (stopped) break;
                done.add(new Result(i, thumb, avg));
                thumb = null;
            } catch (Throwable t) {
                log("thumb failed for " + songs[i].id() + ": " + t);
            } finally {
                if (thumb != null) thumb.dispose();
            }
        }
    }

    /** Loads the cached thumb or builds (and caches) it from the jacket; the result is masked. */
    private Pixmap make(SongMeta song) {
        FileHandle jacket = song.jacket();
        if (jacket == null || !jacket.exists()) return null;
        FileHandle cache = cacheFile(song, jacket);
        if (cache != null && cache.exists()) {
            try {
                Pixmap pm = new Pixmap(cache);
                if (pm.getWidth() == SIZE && pm.getHeight() == SIZE && pm.getFormat() == Pixmap.Format.RGBA8888) {
                    return pm;
                }
                pm.dispose();
            } catch (Throwable t) {
                log("thumb cache unreadable: " + cache.path());
            }
        }
        Pixmap src = new Pixmap(jacket);
        Pixmap mid = null, out;
        try {
            int[] sq = new int[3];
            song.circleSquare(src.getWidth(), src.getHeight(), sq);
            int sx = sq[0], sy = sq[1], side = sq[2];
            int midSize = side > SIZE * 2 ? SIZE * 2 : side;
            mid = new Pixmap(midSize, midSize, Pixmap.Format.RGBA8888);
            mid.setBlending(Pixmap.Blending.None);
            mid.setFilter(Pixmap.Filter.BiLinear);
            mid.drawPixmap(src, sx, sy, side, side, 0, 0, midSize, midSize);
            out = new Pixmap(SIZE, SIZE, Pixmap.Format.RGBA8888);
            out.setBlending(Pixmap.Blending.None);
            out.setFilter(Pixmap.Filter.BiLinear);
            out.drawPixmap(mid, 0, 0, midSize, midSize, 0, 0, SIZE, SIZE);
        } finally {
            src.dispose();
            if (mid != null) mid.dispose();
        }
        maskAndAverage(out);
        if (cache != null && !stopped) {
            try {
                cache.parent().mkdirs();
                PixmapIO.writePNG(cache, out);
            } catch (Throwable t) {
                log("thumb cache not written: " + t.getMessage());
            }
        }
        return out;
    }

    private static FileHandle cacheFile(SongMeta song, FileHandle jacket) {
        try {
            StringBuilder sb = new StringBuilder("cache/thumbs/");
            String id = song.id();
            for (int i = 0; i < id.length(); i++) {
                char c = id.charAt(i);
                sb.append(Character.isLetterOrDigit(c) && c < 128 ? c : '_');
            }
            sb.append('-').append(jacket.lastModified()).append('-').append(jacket.length());
            float[] box = song.jacketCircle();
            if (box != null) sb.append("-c").append(Integer.toHexString(Arrays.hashCode(box)));
            sb.append(".png");
            return Gdx.files.local(sb.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Applies the circular alpha mask {@code α = clamp(r₀ − d + 0.5, 0, 1)} and returns the average
     * colour (RGBA8888) of the visible disc. Pixels outside the disc take the average RGB so
     * mipmaps do not darken the rim.
     */
    private static int maskAndAverage(Pixmap pm) {
        ByteBuffer px = pm.getPixels();
        int w = pm.getWidth(), h = pm.getHeight();
        float c = (w - 1) * 0.5f;
        long sr = 0, sg = 0, sb = 0, n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - c, dy = y - c;
                if (dx * dx + dy * dy <= MASK_R * MASK_R) {
                    int i = (y * w + x) * 4;
                    sr += px.get(i) & 0xFF;
                    sg += px.get(i + 1) & 0xFF;
                    sb += px.get(i + 2) & 0xFF;
                    n++;
                }
            }
        }
        int ar = n > 0 ? (int) (sr / n) : 80, ag = n > 0 ? (int) (sg / n) : 90, ab = n > 0 ? (int) (sb / n) : 128;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - c, dy = y - c;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float a = Math.max(0f, Math.min(1f, MASK_R - d + 0.5f));
                int i = (y * w + x) * 4;
                if (a <= 0f) {
                    px.put(i, (byte) ar);
                    px.put(i + 1, (byte) ag);
                    px.put(i + 2, (byte) ab);
                    px.put(i + 3, (byte) 0);
                } else if (a < 1f) {
                    int oa = px.get(i + 3) & 0xFF;
                    px.put(i + 3, (byte) Math.round(oa * a));
                }
            }
        }
        px.position(0);
        return (ar << 24) | (ag << 16) | (ab << 8) | 0xFF;
    }

    /** Average colour (RGBA8888, opaque) of the mostly opaque pixels of a masked thumb. */
    private static int averageOf(Pixmap pm) {
        ByteBuffer px = pm.getPixels();
        long sr = 0, sg = 0, sb = 0, n = 0;
        int len = pm.getWidth() * pm.getHeight() * 4;
        for (int i = 0; i < len; i += 4) {
            if ((px.get(i + 3) & 0xFF) < 128) continue;
            sr += px.get(i) & 0xFF;
            sg += px.get(i + 1) & 0xFF;
            sb += px.get(i + 2) & 0xFF;
            n++;
        }
        if (n == 0) return 0x50608AFF;
        return ((int) (sr / n) << 24) | ((int) (sg / n) << 16) | ((int) (sb / n) << 8) | 0xFF;
    }

    private static void log(String msg) {
        if (Gdx.app != null) Gdx.app.log("ThumbAtlas", msg);
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        stopped = true;
        if (worker != null) {
            try {
                worker.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Result r;
        while ((r = done.poll()) != null) r.pixmap.dispose();
        for (int p = 0; p < pages.length; p++) {
            if (textures[p] != null) textures[p].dispose();
            if (pages[p] != null) pages[p].dispose();
            textures[p] = null;
            pages[p] = null;
        }
    }
}
