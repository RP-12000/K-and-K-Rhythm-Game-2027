package org.kelvinizer.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.ObjectSet;

/**
 * {@code welcome.png}、{@code Blue.png} 等全屏背景图的引用计数缓存：
 * 同一个文件不管几个画面在用，都只加载成一张贴图，最后一个使用者释放时才销毁。
 * 缺失或读不出的文件会被记住，不再重试；由 {@link UiKit} 持有，只在 GL 线程使用。
 *
 * <p>Ref-counted cache of the full-screen image hooks (DESIGN §2.8: {@code welcome.png},
 * {@code Blue.png}, {@code ResultPage.png}, {@code ChartVisualizer.png}): one {@link Texture} per
 * internal file however many screens use it (Profile and Settings share {@code Blue.png}).
 *
 * <p>{@link #acquire(String)} loads the file on first use (Linear filtering) and counts a
 * reference; {@link #release(String)} drops one and disposes the texture when the last holder let
 * go. A missing or unreadable file is remembered: it returns null, takes no reference and is not
 * retried. Screens acquire once (first show) and release in their {@code dispose()}, so a hook is
 * decoded once per session. Owned by {@link UiKit} (never static, ARCHITECTURE §0), which disposes
 * whatever is still held. GL thread only.
 */
public final class ArtCache implements Disposable {
    private static final class Entry {
        Texture texture;
        int refs;
    }

    private final ObjectMap<String, Entry> entries = new ObjectMap<>();
    private final ObjectSet<String> missing = new ObjectSet<>();
    private boolean disposed;

    /**
     * The texture of internal file {@code path}, counting one reference; null (and no reference)
     * when {@code path} is null, the file is missing or unreadable, or the cache was disposed.
     * Every non-null result needs exactly one {@link #release(String)}.
     */
    public Texture acquire(String path) {
        if (path == null || disposed || missing.contains(path)) return null;
        Entry e = entries.get(path);
        if (e == null) {
            Texture t = load(path);
            if (t == null) {
                missing.add(path);
                return null;
            }
            e = new Entry();
            e.texture = t;
            entries.put(path, e);
        }
        e.refs++;
        return e.texture;
    }

    /** Drops one reference to {@code path}; the last one disposes the texture. No-op for unknown paths. */
    public void release(String path) {
        if (path == null) return;
        Entry e = entries.get(path);
        if (e == null) return;
        if (--e.refs <= 0) {
            entries.remove(path);
            e.texture.dispose();
        }
    }

    /** Number of textures currently loaded (diagnostics and tests). */
    public int loadedCount() {
        return entries.size;
    }

    /** References held on {@code path} (0 when not loaded). */
    public int refCount(String path) {
        Entry e = path != null ? entries.get(path) : null;
        return e != null ? e.refs : 0;
    }

    private static Texture load(String path) {
        try {
            FileHandle f = Gdx.files.internal(path);
            if (!f.exists()) return null;
            Texture t = new Texture(f);
            t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            return t;
        } catch (RuntimeException e) {
            if (Gdx.app != null) Gdx.app.error("ArtCache", "cannot load " + path, e);
            return null;
        }
    }

    /** Disposes every texture still held; later {@link #acquire} calls return null. */
    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        for (Entry e : entries.values()) e.texture.dispose();
        entries.clear();
    }
}
