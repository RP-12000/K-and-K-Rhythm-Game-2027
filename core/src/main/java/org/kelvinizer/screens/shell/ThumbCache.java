package org.kelvinizer.screens.shell;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ObjectIntMap;
import org.kelvinizer.library.SongMeta;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 玩家档案列表（TOP 10、LOG）用的方形歌曲小缩略图：后台线程解码曲绘并缩小，再在 GL 线程拷进同一张贴图，每首歌一格。
 * 不碰曲库的完整曲绘贴图，地图的缓存不受影响；GL 上下文丢失后缩略图也能恢复。
 *
 * <p>Small square song thumbnails for the Profile lists (TOP 10, LOG): jackets are decoded and
 * box-downscaled to {@link #CELL}² on a background thread (no GL), then copied into one managed
 * page texture on the GL thread ({@link #update()}), one cell per song. The full jacket textures of
 * {@code SongLibrary} are never touched, so the map's cache is unaffected.
 *
 * <p>The page {@link Pixmap} is kept as the texture's managed data, so the thumbnails survive a
 * GL context loss. A song without a jacket (or whose jacket failed) returns null; draw a
 * procedural tile instead. {@link #get} allocates only when it queues a new song.
 */
public final class ThumbCache implements Disposable {
    /** Cell size in pixels. */
    public static final int CELL = 96;
    private static final int COLS = 10, ROWS = 5;
    private static final int LOADING = -1, FAILED = -2;

    private static final class Job {
        String songId;
        FileHandle file;
        Pixmap small;
    }

    private final ObjectIntMap<String> slots = new ObjectIntMap<>();
    private final TextureRegion[] regions = new TextureRegion[COLS * ROWS];
    private final ConcurrentLinkedQueue<Job> done = new ConcurrentLinkedQueue<>();
    private Pixmap page;
    private Texture texture;
    private ExecutorService worker;
    private int used;
    private volatile boolean disposed;

    /** Returns the thumbnail of {@code song}, or null while loading / without a jacket. */
    public TextureRegion get(SongMeta song) {
        if (song == null || disposed) return null;
        int slot = slots.get(song.id(), Integer.MIN_VALUE);
        if (slot >= 0) return regions[slot];
        if (slot != Integer.MIN_VALUE) return null;
        FileHandle f = song.jacket();
        if (f == null || used + count(LOADING) >= regions.length) {
            slots.put(song.id(), FAILED);
            return null;
        }
        slots.put(song.id(), LOADING);
        final Job job = new Job();
        job.songId = song.id();
        job.file = f;
        worker().execute(new Runnable() {
            @Override
            public void run() {
                Pixmap small = disposed ? null : decode(job.file);
                if (disposed) {
                    if (small != null) small.dispose();
                    return;
                }
                job.small = small;
                done.add(job);
            }
        });
        return null;
    }

    private int count(int state) {
        int n = 0;
        for (ObjectIntMap.Entry<String> e : slots) if (e.value == state) n++;
        return n;
    }

    private ExecutorService worker() {
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "kk-thumbs");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });
        }
        return worker;
    }

    /** Worker thread: decode, cover-crop and halve down to CELL² (no GL calls). */
    private static Pixmap decode(FileHandle f) {
        Pixmap src = null;
        try {
            src = new Pixmap(f);
            int side = Math.min(src.getWidth(), src.getHeight());
            int sx = (src.getWidth() - side) / 2, sy = (src.getHeight() - side) / 2;
            Pixmap cur = src;
            int curX = sx, curY = sy, curSide = side;
            while (curSide > CELL * 2) {
                int half = curSide / 2;
                Pixmap next = new Pixmap(half, half, Pixmap.Format.RGBA8888);
                next.setBlending(Pixmap.Blending.None);
                next.setFilter(Pixmap.Filter.BiLinear);
                next.drawPixmap(cur, curX, curY, curSide, curSide, 0, 0, half, half);
                if (cur != src) cur.dispose();
                cur = next;
                curX = 0;
                curY = 0;
                curSide = half;
            }
            Pixmap out = new Pixmap(CELL, CELL, Pixmap.Format.RGBA8888);
            out.setBlending(Pixmap.Blending.None);
            out.setFilter(Pixmap.Filter.BiLinear);
            out.drawPixmap(cur, curX, curY, curSide, curSide, 0, 0, CELL, CELL);
            if (cur != src) cur.dispose();
            return out;
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (src != null) src.dispose();
        }
    }

    /** GL thread: copies finished thumbnails into the page (call once per frame). */
    public void update() {
        Job job;
        while ((job = done.poll()) != null) {
            if (disposed || job.small == null || used >= regions.length) {
                if (job.small != null) job.small.dispose();
                if (!disposed) slots.put(job.songId, FAILED);
                continue;
            }
            ensurePage();
            int slot = used++;
            int px = (slot % COLS) * CELL, py = (slot / COLS) * CELL;
            page.drawPixmap(job.small, px, py);
            // the page is managed (survives context loss), so Texture.draw refuses it: upload the
            // cell directly; the page pixmap above already holds the same pixels for reloads
            texture.bind();
            Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, px, py, CELL, CELL, job.small.getGLFormat(),
                job.small.getGLType(), job.small.getPixels());
            job.small.dispose();
            regions[slot] = new TextureRegion(texture, px, py, CELL, CELL);
            slots.put(job.songId, slot);
        }
    }

    private void ensurePage() {
        if (page != null) return;
        page = new Pixmap(COLS * CELL, ROWS * CELL, Pixmap.Format.RGBA8888);
        page.setBlending(Pixmap.Blending.None);
        texture = new Texture(new PixmapTextureData(page, null, false, false, true));
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
    }

    @Override
    public void dispose() {
        disposed = true;
        if (worker != null) worker.shutdownNow();
        worker = null;
        Job job;
        while ((job = done.poll()) != null) if (job.small != null) job.small.dispose();
        if (texture != null) texture.dispose();
        if (page != null) page.dispose();
        texture = null;
        page = null;
        slots.clear();
        used = 0;
        if (Gdx.app != null) Gdx.app.debug("ThumbCache", "disposed");
    }
}
