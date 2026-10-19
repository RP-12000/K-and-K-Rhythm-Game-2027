package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongLibrary;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.profile.ChartRecord;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.Shells;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 当前这一层洞穴的世界：按 {@link CaveData} 里的层数据摆好每个点（歌曲洞、剧情入口、竖井、航线洞口），
 * 连好路，记下区域名、路牌、文案和边界；“所有曲目”层按曲包或全部铺开、按当前排序和筛选现排成网格。
 * 换潜水员或成绩变化时刷新上锁、读过、路的实线虚线和进度文字。
 *
 * <p>The world of the current cave level (DESIGN §16): the spots of a {@link CaveData.Level}
 * (song holes, story entrances, shafts, hub routes, contacts), its paths, area names, signposts,
 * text slots and bounds. {@code rise} spots are not built since only a pinch rises (Kelvin
 * 2026-10-16); their paths go with them, but they still count for the bounds, and a level that
 * started on one still opens there ({@link #startX}), its arrival ring and wake light from the
 * spot nearest to it, or from the rise spot itself when the camera opens nearer to that (SINGLES,
 * 2026-10-17). The ALL SONGS level is laid out here as a grid, by pack or flat, in the current
 * sort, with only the songs the {@link MapFilter} keeps. Per-diver state (records, locks, read
 * stories, solid / dashed paths, progress texts) is refreshed by {@link #refresh(Profile, float)}
 * on profile changes only.
 *
 * <p>World units: 1 unit = 1 reference pixel at zoom 1, y up (the data files are y down).
 */
final class MapWorld {
    /** Sort orders of the ALL SONGS grid. */
    static final int SORT_DEFAULT = 0, SORT_TITLE = 1, SORT_LEVEL = 2, SORT_BEST = 3, SORT_RECENT = 4;
    static final String[] SORT_NAMES = {"DEFAULT", "TITLE", "LEVEL", "BEST", "RECENT"};
    /** Grid geometry (data-file units, y down). */
    private static final float GRID_X0 = 200f, GRID_Y0 = 330f, PACK_PITCH_X = 264f, PACK_PITCH_Y = 336f,
        FLAT_PITCH_X = 304f, FLAT_PITCH_Y = 400f, GRID_R = 86f;
    private static final int PACK_COLS = 4, FLAT_COLS = 6;
    /** Collected songs: hex lattice round the start (pitch, row height) and the clearance a free point keeps. */
    private static final float COLLECT_PITCH = 540f, COLLECT_ROW = 380f, COLLECT_FREE = 300f;
    /** Secret states of a song for a diver (special unlock): none, garbled, hidden. */
    static final int SECRET_NONE = 0, SECRET_GLITCH = 1, SECRET_HIDDEN = 2;

    final CaveData.Level level;
    /** Every spot; song holes first (in {@link #nodes} order). */
    final MapNode[] all;
    /** Song holes. */
    final MapNode[] nodes;
    /** The tutorial hole, or null. */
    final MapNode launch;
    /** Where the camera starts and the wake light spreads from (may be null). */
    final MapNode start;
    /** Where the camera opens when the level starts on a dropped rise spot (NaN otherwise). */
    final float startX, startY;
    /** Paths: end node indices into {@link #all}, colour, directed, main / side. */
    final int[] la, lb;
    final Color[] lcolor;
    final boolean[] larrow;
    /** Per path: both ends cleared (solid) — refreshed. */
    final boolean[] lcleared;
    /** Areas, signposts and text slots (world coordinates). */
    final List<CaveData.Area> areas;
    final List<CaveData.Sign> signs;
    final List<CaveData.Text> texts;
    /** Level bounds (world) including a margin. */
    final float minX, maxX, minY, maxY;
    /** Left and top edges of the content (the bounds without the margin): ALL SONGS opens with this corner under the badge. */
    final float contentLeft, contentTop;
    /** ALL SONGS layout: flat grid and sort order. */
    final boolean flat;
    final int sort;
    /** The song card's header, e.g. {@code SINGLES · 单曲} or {@code 01 · SHORELINE}. */
    final String cardHeader;
    /** Stats of this level's songs. */
    int songs, charts, chartsPlayed;
    /** Library-wide stats (hub route plates). */
    int libSongs, libCharts, libChartsPlayed;
    private final SongLibrary library;
    private final CaveData data;

    MapWorld(CaveData data, CaveData.Level level, SongLibrary library, Profile p, int sort, boolean flat,
             Difficulty lens, MapFilter filter) {
        this.level = level;
        this.library = library;
        this.data = data;
        this.sort = sort;
        this.flat = flat;
        List<MapNode> songNodes = new ArrayList<>();
        List<MapNode> other = new ArrayList<>();
        texts = new ArrayList<>(level.texts);
        float[] frame = null;
        if (CaveData.GRID.equals(level.kind)) {
            if (filter != null && filter.active()) {
                // a filtered grid pans like the full one, so it too opens with its top left under the badge
                List<MapNode> fs = new ArrayList<>(), fo = new ArrayList<>();
                int t0 = texts.size();
                layoutGrid(data, level, library, p, sort, flat, lens, null, fs, fo);
                frame = new float[]{Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
                fs.addAll(fo);
                for (MapNode n : fs) grow(frame, n.x - n.r, n.x + n.r, n.y - n.r - 120f, n.y + n.r);
                for (CaveData.Text t : texts.subList(t0, texts.size())) grow(frame, t.x - 40f, t.x + 400f, t.y - 40f, t.y + 40f);
                texts.subList(t0, texts.size()).clear();
            }
            layoutGrid(data, level, library, p, sort, flat, lens, filter, songNodes, other);
        } else {
            for (CaveData.Spot s : level.spots) {
                int kind = kindOf(s.type);
                if (kind == MapNode.RISE) continue; // only a pinch rises (Kelvin 2026-10-16)
                SongMeta song = null;
                int secret = SECRET_NONE;
                if (kind == MapNode.SONG) {
                    song = s.song != null ? library.song(s.song) : null;
                    if (song == null) continue;
                    secret = spotSecret(s, p);
                    if (secret == SECRET_HIDDEN) continue;
                } else if (kind == MapNode.TUTORIAL) {
                    song = library.tutorial();
                }
                MapNode n = new MapNode(s, kind, song, kind == MapNode.SONG ? songNodes.size() : -1, s.x, s.y, s.r);
                if (secret == SECRET_GLITCH) markGlitch(n);
                (kind == MapNode.SONG ? songNodes : other).add(n);
            }
            if (level.collect) addUnplaced(data, level, library, songNodes);
        }
        nodes = songNodes.toArray(new MapNode[0]);
        all = new MapNode[nodes.length + other.size()];
        System.arraycopy(nodes, 0, all, 0, nodes.length);
        for (int i = 0; i < other.size(); i++) all[nodes.length + i] = other.get(i);
        MapNode l = null;
        for (int i = 0; i < all.length; i++) {
            all[i].idx = i;
            if (all[i].kind == MapNode.TUTORIAL) l = all[i];
        }
        launch = l;
        MapNode st = spotNode(level.start);
        CaveData.Spot dropped = st == null ? riseSpot(level, level.start) : null;
        if (dropped != null) st = nearest(dropped.x, dropped.y);
        if (st == null && all.length > 0) st = all[0];
        start = st;
        startX = dropped != null ? dropped.x : Float.NaN;
        startY = dropped != null ? dropped.y : Float.NaN;

        // paths
        List<int[]> ls = new ArrayList<>();
        List<Color> lc = new ArrayList<>();
        List<Boolean> lar = new ArrayList<>();
        for (CaveData.Link k : level.links) {
            MapNode a = spotNode(k.a), b = spotNode(k.b);
            if (a == null || b == null) continue;
            ls.add(new int[]{a.idx, b.idx});
            lc.add(linkColor(k));
            lar.add("main".equals(k.kind) || "side".equals(k.kind));
        }
        la = new int[ls.size()];
        lb = new int[ls.size()];
        lcolor = new Color[ls.size()];
        larrow = new boolean[ls.size()];
        lcleared = new boolean[ls.size()];
        for (int i = 0; i < ls.size(); i++) {
            la[i] = ls.get(i)[0];
            lb[i] = ls.get(i)[1];
            lcolor[i] = lc.get(i);
            larrow[i] = lar.get(i);
        }
        areas = level.areas;
        signs = level.signs;

        // requirements (spot ids on this level, or song ids)
        for (MapNode n : all) {
            if (n.spot == null || n.spot.requires.length == 0) continue;
            List<MapNode> need = new ArrayList<>();
            StringBuilder names = new StringBuilder();
            for (String id : n.spot.requires) {
                MapNode m = spotNode(id);
                if (m == null) m = node(id);
                if (m != null) need.add(m);
                SongMeta s = m != null ? m.song : library.song(id);
                if (names.length() > 0) names.append(" · ");
                names.append(s != null ? s.title() : id);
            }
            n.needs = need.toArray(new MapNode[0]);
            n.needsText = names.toString();
        }

        // bounds
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (MapNode n : all) {
            x0 = Math.min(x0, n.x - n.r);
            x1 = Math.max(x1, n.x + n.r);
            y0 = Math.min(y0, n.y - n.r - 120f);
            y1 = Math.max(y1, n.y + n.r);
        }
        if (!CaveData.GRID.equals(level.kind)) { // dropped rise spots keep the level's extent
            for (CaveData.Spot s : level.spots) {
                if (kindOf(s.type) != MapNode.RISE) continue;
                x0 = Math.min(x0, s.x - s.r);
                x1 = Math.max(x1, s.x + s.r);
                y0 = Math.min(y0, s.y - s.r - 120f);
                y1 = Math.max(y1, s.y + s.r);
            }
        }
        for (CaveData.Area a : areas) {
            x0 = Math.min(x0, a.x - 300f);
            x1 = Math.max(x1, a.x + 300f);
            y0 = Math.min(y0, a.y - 60f);
            y1 = Math.max(y1, a.y + 60f);
        }
        for (CaveData.Text t : texts) {
            x0 = Math.min(x0, t.x - 40f);
            x1 = Math.max(x1, t.x + 400f);
            y0 = Math.min(y0, t.y - 40f);
            y1 = Math.max(y1, t.y + 40f);
        }
        if (frame != null) {
            x0 = Math.min(x0, frame[0]);
            x1 = Math.max(x1, frame[1]);
            y0 = Math.min(y0, frame[2]);
            y1 = Math.max(y1, frame[3]);
        }
        if (!Float.isNaN(level.titleX)) {
            x0 = Math.min(x0, level.titleX - 300f);
            x1 = Math.max(x1, level.titleX + 300f);
            y1 = Math.max(y1, level.titleY + 120f);
        }
        if (x0 > x1) {
            x0 = 0f;
            x1 = 1920f;
            y0 = -1080f;
            y1 = 0f;
        }
        float m = 260f;
        contentLeft = x0;
        contentTop = y1;
        minX = x0 - m;
        maxX = x1 + m;
        minY = y0 - m;
        maxY = y1 + m + (CaveData.GRID.equals(level.kind) ? 180f : 0f); // pan headroom above the grid

        StringBuilder h = new StringBuilder();
        if (!level.numeral.isEmpty()) h.append(level.numeral).append(" · ");
        h.append(level.title);
        if (!level.titleZh.isEmpty()) h.append(" · ").append(level.titleZh);
        cardHeader = h.toString();
        refresh(p, 0f);
    }

    /** Grows box {x0, x1, y0, y1} to hold the given extent. */
    private static void grow(float[] box, float x0, float x1, float y0, float y1) {
        box[0] = Math.min(box[0], x0);
        box[1] = Math.max(box[1], x1);
        box[2] = Math.min(box[2], y0);
        box[3] = Math.max(box[3], y1);
    }

    /** The (unbuilt) rise spot {@code spotId} of {@code level}, or null. */
    private static CaveData.Spot riseSpot(CaveData.Level level, String spotId) {
        if (spotId == null || CaveData.GRID.equals(level.kind)) return null;
        for (CaveData.Spot s : level.spots) if (s.id.equals(spotId) && kindOf(s.type) == MapNode.RISE) return s;
        return null;
    }

    /** The node nearest to (x, y), or null. */
    private MapNode nearest(float x, float y) {
        MapNode best = null;
        float bd = Float.MAX_VALUE;
        for (MapNode n : all) {
            float dx = n.x - x, dy = n.y - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = n;
            }
        }
        return best;
    }

    static int kindOf(String type) {
        if (type == null) return MapNode.SONG;
        switch (type) {
            case CaveData.STORY:
                return MapNode.STORY;
            case CaveData.SHAFT:
                return MapNode.SHAFT;
            case CaveData.PORTAL:
                return MapNode.ROUTE;
            case CaveData.RISE:
                return MapNode.RISE;
            case CaveData.CONTACT:
                return MapNode.CONTACT;
            case CaveData.TUTORIAL:
                return MapNode.TUTORIAL;
            default:
                return MapNode.SONG;
        }
    }

    private Color linkColor(CaveData.Link k) {
        if (k.area != null) {
            for (CaveData.Area a : level.areas) if (a.id.equals(k.area)) return a.color;
        }
        if ("main".equals(k.kind)) return Theme.GROUND_SIG;
        if ("clue".equals(k.kind)) return Theme.GOLD;
        return Theme.INK1;
    }

    /**
     * Songs no level places go on a "collect" level, on the free points of a hex lattice round its
     * start ({@link #COLLECT_PITCH} apart, rows {@link #COLLECT_ROW} apart): nearest first, a step
     * up or down counting 4/3 of a step sideways so they fill a 4:3 tablet screen before spilling
     * past it, ties counter-clockwise from the right, so they ring the start (Kelvin 2026-10-17
     * 16:46: 从屏幕正中心开始，四周都有曲子; 17:21: 贴近4：3的平板). A point is free when no spot
     * of the level and no song placed before lies within {@link #COLLECT_FREE}.
     */
    private static void addUnplaced(CaveData data, CaveData.Level level, SongLibrary library, List<MapNode> songNodes) {
        List<SongMeta> songs = new ArrayList<>();
        for (SongMeta s : library.songs()) if (!placed(data, s.id())) songs.add(s);
        if (songs.isEmpty()) return;
        float ox = 0f, oy = 0f;
        List<float[]> taken = new ArrayList<>();
        for (CaveData.Spot s : level.spots) {
            if (s.id.equals(level.start)) {
                ox = s.x;
                oy = s.y;
            }
            if (kindOf(s.type) != MapNode.RISE) taken.add(new float[]{s.x, s.y});
        }
        int rings = 2; // enough points for every song: a spot blocks at most four of them
        while (3 * rings * (rings + 1) < 4 * taken.size() + songs.size()) rings++;
        List<float[]> slots = new ArrayList<>();
        for (int r = -rings; r <= rings; r++) {
            for (int q = -rings; q <= rings; q++) {
                if ((q == 0 && r == 0) || Math.abs(q) + Math.abs(r) + Math.abs(q + r) > 2 * rings) continue;
                float dx = COLLECT_PITCH * (q + r * 0.5f), dy = COLLECT_ROW * r;
                float angle = (float) Math.atan2(dy, dx);
                if (angle < 0f) angle += (float) (2 * Math.PI);
                slots.add(new float[]{ox + dx, oy + dy, dx * dx + 16f / 9f * dy * dy, angle});
            }
        }
        Collections.sort(slots, new Comparator<float[]>() {
            @Override
            public int compare(float[] a, float[] b) {
                return a[2] != b[2] ? Float.compare(a[2], b[2]) : Float.compare(a[3], b[3]);
            }
        });
        for (SongMeta s : songs) {
            for (float[] p : slots) {
                boolean free = true;
                for (float[] t : taken) {
                    float dx = t[0] - p[0], dy = t[1] - p[1];
                    if (dx * dx + dy * dy < COLLECT_FREE * COLLECT_FREE) {
                        free = false;
                        break;
                    }
                }
                if (!free) continue;
                songNodes.add(new MapNode(null, MapNode.SONG, s, songNodes.size(), p[0], p[1], CaveData.defaultRadius(CaveData.SONG)));
                taken.add(new float[]{p[0], p[1]});
                break;
            }
        }
    }

    private static boolean placed(CaveData data, String songId) {
        for (CaveData.Level l : data.levels.values()) {
            for (CaveData.Spot s : l.spots) if (songId.equals(s.song)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ ALL SONGS grid
    /**
     * Lays the ALL SONGS grid out. With a filter on, only the songs it keeps are placed, packs
     * with none of them are left out with their placeholders and notes, and an empty result says
     * so where the grid starts.
     */
    private void layoutGrid(CaveData data, CaveData.Level level, SongLibrary lib, Profile p, int sort, boolean flat,
                            Difficulty lens, MapFilter filter, List<MapNode> songNodes, List<MapNode> other) {
        List<SongMeta> list = new ArrayList<>(lib.songs());
        sortSongs(list, p, sort, lens);
        boolean filtering = filter != null && filter.active();
        // secret songs: hidden ones are left out, garbled ones never match a filter
        List<SongMeta> garbled = new ArrayList<>();
        for (int i = list.size() - 1; i >= 0; i--) {
            int sec = secretOf(data, list.get(i).id(), p);
            if (sec == SECRET_HIDDEN || (sec == SECRET_GLITCH && filtering)) list.remove(i);
            else if (sec == SECRET_GLITCH) garbled.add(list.get(i));
        }
        if (filtering) {
            for (int i = list.size() - 1; i >= 0; i--) if (!filter.accepts(list.get(i), p, lens)) list.remove(i);
            if (list.isEmpty()) {
                texts.add(text(96f, 150f, "NO MATCHES · " + MapFilter.LABELS[filter.status()],
                    "没有符合的歌 · " + MapFilter.LABELS[filter.status()], 22f, Theme.INK1));
                return;
            }
        }
        if (flat || level.packs.isEmpty()) {
            for (int i = 0; i < list.size(); i++) {
                float x = GRID_X0 + (i % FLAT_COLS) * FLAT_PITCH_X, y = GRID_Y0 + (i / FLAT_COLS) * FLAT_PITCH_Y;
                gridNode(list.get(i), garbled, songNodes, x, y);
            }
            texts.add(text(96f, 150f, "ALL SONGS · NO PACKS · " + SORT_NAMES[sort],
                "全部铺开 · 不分曲包 · 排序 " + SORT_NAMES[sort], 22f, Theme.INK1));
            return;
        }
        // the first pack lists songs on the left; the other packs stack on the right
        float rightX = GRID_X0 + PACK_COLS * PACK_PITCH_X + 120f;
        float ry = 150f;
        boolean first = true;
        for (CaveData.Pack pk : level.packs) {
            int packSecret = pk.secret == null || (p != null && p.hasFlag(packRevealFlag(pk))) ? SECRET_NONE : secret(pk.secret);
            if (packSecret == SECRET_HIDDEN) continue;
            List<SongMeta> in = new ArrayList<>();
            if (pk.songs == null) {
                in.addAll(list);
            } else {
                for (SongMeta s : list) for (String id : pk.songs) if (s.id().equals(id)) in.add(s);
            }
            if (filtering && in.isEmpty()) continue;
            String title = packSecret == SECRET_GLITCH ? garble(pk.title) : pk.title;
            String titleZh = packSecret == SECRET_GLITCH ? title : pk.titleZh;
            if (first) {
                first = false;
                texts.add(text(96f, 150f, title, titleZh, 22f, Theme.INK1));
                for (int i = 0; i < in.size(); i++) {
                    gridNode(in.get(i), garbled, songNodes, GRID_X0 + (i % PACK_COLS) * PACK_PITCH_X,
                        GRID_Y0 + (i / PACK_COLS) * PACK_PITCH_Y);
                }
                continue;
            }
            texts.add(text(rightX - 104f, ry, title, titleZh, 22f, Theme.INK1));
            int slots = (filtering ? 0 : pk.placeholders) + in.size();
            for (int i = 0; i < slots; i++) {
                float x = rightX + (i % 2) * PACK_PITCH_X, y = ry + 180f + (i / 2) * PACK_PITCH_Y;
                if (i < in.size()) {
                    gridNode(in.get(i), garbled, songNodes, x, y);
                } else {
                    other.add(new MapNode(null, MapNode.SLOT, null, -1, x, -y, GRID_R));
                }
            }
            float noteY = ry + 180f + ((slots + 1) / 2) * PACK_PITCH_Y - 150f;
            if (!filtering && !pk.note.isEmpty() && packSecret == SECRET_NONE) {
                texts.add(text(rightX + PACK_PITCH_X * 0.5f, noteY, pk.note, pk.noteZh, 18f, Theme.INK2));
            }
            ry = noteY + 120f;
        }
    }

    /** One ALL SONGS hole at data-file (x, y down); garbled when its song is in {@code garbled}. */
    private static void gridNode(SongMeta s, List<SongMeta> garbled, List<MapNode> songNodes, float x, float y) {
        MapNode n = new MapNode(null, MapNode.SONG, s, songNodes.size(), x, -y, GRID_R);
        if (garbled.contains(s)) markGlitch(n);
        songNodes.add(n);
    }

    /** Makes {@code n} a garbled secret: its label shimmers and bursts now and then. */
    private static void markGlitch(MapNode n) {
        n.glitch = true;
        n.labelGlitch.ambient(GlitchText.Level.G1).idleBursts(2.4f);
    }

    // ------------------------------------------------------------------ secrets and ALL SONGS locks
    /** Secret state of mode string {@code mode} ({@code hidden}, {@code glitch}, else none). */
    static int secret(String mode) {
        if (CaveData.HIDDEN.equals(mode)) return SECRET_HIDDEN;
        if (CaveData.GLITCH.equals(mode)) return SECRET_GLITCH;
        return SECRET_NONE;
    }

    /** Secret state of a song spot for diver {@code p} (none once its song is revealed). */
    static int spotSecret(CaveData.Spot s, Profile p) {
        if (s.secret == null || s.song == null || (p != null && p.hasFlag(revealFlag(s.song)))) return SECRET_NONE;
        return secret(s.secret);
    }

    /**
     * Secret state of song {@code songId} on ALL SONGS for diver {@code p}: the strongest of the
     * spots that place it and the packs that list it by id, until the song (or that pack) is revealed.
     */
    static int secretOf(CaveData data, String songId, Profile p) {
        if (p != null && p.hasFlag(revealFlag(songId))) return SECRET_NONE;
        int s = SECRET_NONE;
        for (CaveData.Level l : data.levels.values()) {
            for (CaveData.Spot sp : l.spots) if (songId.equals(sp.song)) s = Math.max(s, secret(sp.secret));
            for (CaveData.Pack pk : l.packs) {
                if (pk.secret == null || pk.songs == null || (p != null && p.hasFlag(packRevealFlag(pk)))) continue;
                for (String id : pk.songs) if (songId.equals(id)) s = Math.max(s, secret(pk.secret));
            }
        }
        return s;
    }

    /** Profile flag that reveals a secret song (set by its special unlock flow). */
    static String revealFlag(String songId) {
        return Profile.FLAG_REVEAL + songId;
    }

    /** Profile flag that reveals a secret ALL SONGS pack. */
    static String packRevealFlag(CaveData.Pack pk) {
        return Profile.FLAG_REVEAL + "pack:" + pk.title;
    }

    /** A garbled stand-in for {@code s} (same word shape, fixed per text). */
    static String garble(String s) {
        final String sym = "#?%&@!*$<>";
        int n = Math.max(3, Math.min(10, s.length()));
        StringBuilder b = new StringBuilder(n);
        int h = s.hashCode();
        for (int i = 0; i < n; i++) {
            if (i < s.length() && s.charAt(i) == ' ') {
                b.append(' ');
                continue;
            }
            h = h * 31 + 17;
            b.append(sym.charAt(((h >>> 3) & 0x7fffffff) % sym.length()));
        }
        return b.toString();
    }

    /**
     * The names of the unmet requirements of spot {@code s} on {@code l} for diver {@code p}
     * (empty when it is open): spot ids on that level (songs cleared, stories read, the tutorial
     * seen, shafts not sealed) or song ids.
     */
    static List<String> unmet(CaveData.Level l, CaveData.Spot s, Profile p, SongLibrary lib) {
        List<String> out = new ArrayList<>();
        for (String id : s.requires) {
            CaveData.Spot r = l.spot(id);
            String song = r != null ? r.song : id;
            boolean met;
            if (r == null) {
                met = songCleared(p, id);
            } else {
                switch (kindOf(r.type)) {
                    case MapNode.SONG:
                        met = r.song != null && songCleared(p, r.song);
                        break;
                    case MapNode.STORY:
                        met = p != null && r.story != null && p.hasFlag(storyFlag(r.story));
                        break;
                    case MapNode.TUTORIAL:
                        met = p != null && p.tutorialSeen;
                        break;
                    case MapNode.SHAFT:
                    case MapNode.ROUTE:
                        met = !r.sealed;
                        break;
                    default:
                        met = true;
                }
            }
            if (met) continue;
            SongMeta m = song != null ? lib.song(song) : null;
            out.add(m != null ? m.title() : r != null && r.label != null ? r.label : id);
        }
        return out;
    }

    /**
     * ALL SONGS lock of song {@code songId}: null when it is placed nowhere (SINGLES collects it)
     * or some spot that places it is open; else the unmet requirements of the first locked spot.
     */
    static String[] gridLock(CaveData data, SongLibrary lib, String songId, Profile p) {
        String[] first = null;
        for (CaveData.Level l : data.levels.values()) {
            if (CaveData.GRID.equals(l.kind)) continue;
            for (CaveData.Spot s : l.spots) {
                if (!songId.equals(s.song) || kindOf(s.type) != MapNode.SONG) continue;
                List<String> u = unmet(l, s, p, lib);
                if (u.isEmpty()) return null;
                if (first == null) first = u.toArray(new String[0]);
            }
        }
        return first;
    }

    private static CaveData.Text text(float x, float yDown, String en, String zh, float size, Color c) {
        CaveData.Text t = new CaveData.Text();
        t.text = en;
        t.textZh = zh.isEmpty() ? en : zh;
        t.x = x;
        t.y = -yDown;
        t.size = size;
        t.color = c;
        return t;
    }

    private static void sortSongs(List<SongMeta> list, final Profile p, int sort, final Difficulty lens) {
        switch (sort) {
            case SORT_TITLE:
                Collections.sort(list, new Comparator<SongMeta>() {
                    @Override
                    public int compare(SongMeta a, SongMeta b) {
                        return a.title().compareToIgnoreCase(b.title());
                    }
                });
                break;
            case SORT_LEVEL:
                Collections.sort(list, new Comparator<SongMeta>() {
                    @Override
                    public int compare(SongMeta a, SongMeta b) {
                        return Float.compare(a.has(lens) ? a.constant(lens) : 99f, b.has(lens) ? b.constant(lens) : 99f);
                    }
                });
                break;
            case SORT_BEST:
                Collections.sort(list, new Comparator<SongMeta>() {
                    @Override
                    public int compare(SongMeta a, SongMeta b) {
                        return Integer.compare(best(p, b, lens), best(p, a, lens));
                    }
                });
                break;
            case SORT_RECENT:
                Collections.sort(list, new Comparator<SongMeta>() {
                    @Override
                    public int compare(SongMeta a, SongMeta b) {
                        return Integer.compare(recent(p, a), recent(p, b));
                    }
                });
                break;
            default:
                break;
        }
    }

    private static int best(Profile p, SongMeta s, Difficulty d) {
        ChartRecord r = p != null ? p.record(s.id(), d) : null;
        return r != null && r.plays > 0 ? r.bestScore : -1;
    }

    private static int recent(Profile p, SongMeta s) {
        if (p == null || p.recentPlays == null) return Integer.MAX_VALUE;
        for (int i = 0; i < p.recentPlays.size(); i++) if (s.id().equals(p.recentPlays.get(i).songId)) return i;
        return Integer.MAX_VALUE;
    }

    // ------------------------------------------------------------------ lookups
    /** Node of a level spot id, or null. */
    MapNode spotNode(String spotId) {
        if (spotId == null) return null;
        for (MapNode n : all) if (n.spot != null && n.spot.id.equals(spotId)) return n;
        return null;
    }

    /** Song hole of a song id, or null. */
    MapNode node(String songId) {
        if (songId == null) return null;
        for (MapNode n : nodes) if (n.song != null && n.song.id().equals(songId)) return n;
        if (launch != null && launch.song != null && launch.song.id().equals(songId)) return launch;
        return null;
    }

    /** True on the hub (route holes, no lens). */
    boolean isHub() {
        return CaveData.HUB.equals(level.kind);
    }

    /** True on ALL SONGS. */
    boolean isGrid() {
        return CaveData.GRID.equals(level.kind);
    }

    // ------------------------------------------------------------------ per-diver refresh
    /**
     * Refreshes every node's record cache, locks, read stories, the path styles and the progress
     * texts for diver {@code p}. Allocation is fine here (profile changes only).
     */
    void refresh(Profile p, float unused) {
        songs = nodes.length;
        charts = 0;
        chartsPlayed = 0;
        for (MapNode n : all) {
            n.refresh(p);
            if (n.kind == MapNode.STORY) n.read = p != null && n.spot != null && p.hasFlag(storyFlag(n.spot.story));
        }
        for (MapNode n : nodes) {
            for (int d = 0; d < 4; d++) {
                if (n.has[d]) charts++;
                if (n.played[d]) chartsPlayed++;
            }
        }
        boolean grid = isGrid();
        for (MapNode n : all) {
            n.forSale = n.isSong() && !n.glitch && n.song != null && !inStory(data, n.song.id())
                && (p == null || !p.owns(n.song.id()));
            n.price = n.forSale ? priceOf(data, n.song.id()) : 0;
            if (grid && n.isSong()) {
                String[] c = gridLock(data, library, n.song.id(), p);
                n.lockConds = c != null ? c : new String[0];
                n.needsText = c != null ? String.join(" · ", c) : "";
                n.locked = c != null && !n.glitch;
            } else {
                n.locked = isLocked(n, p) && !n.glitch;
            }
        }
        for (int i = 0; i < la.length; i++) lcleared[i] = all[la[i]].cleared() && all[lb[i]].cleared();
        libSongs = library.songs().size();
        libCharts = 0;
        libChartsPlayed = 0;
        for (SongMeta s : library.songs()) {
            for (int d = 0; d < 4; d++) {
                Difficulty dd = Difficulty.byOrdinal(d);
                if (!s.has(dd)) continue;
                libCharts++;
                ChartRecord r = p != null ? p.record(s.id(), dd) : null;
                if (r != null && r.plays > 0) libChartsPlayed++;
            }
        }
    }

    /** {@code 8 SONGS · 18/28 CLEARED}. */
    static void appendProgress(StringBuilder sb, int songs, int charts, int played) {
        sb.append(songs).append(songs == 1 ? " SONG · " : " SONGS · ").append(played).append('/').append(charts)
            .append(" CLEARED");
    }

    /** Text of a hub route plate's {@code sub} placeholder ({@code @progress}, {@code @charts}). */
    String routeSub(String sub) {
        if (sub == null) return "";
        StringBuilder sb = new StringBuilder(40);
        if ("@progress".equals(sub)) appendProgress(sb, libSongs, libCharts, libChartsPlayed);
        else if ("@charts".equals(sub)) sb.append(libSongs).append(" SONGS · ").append(libCharts).append(" CHARTS");
        else return sub;
        return sb.toString();
    }

    /**
     * True when a story level (MAIN and later SIDE: any level but the hub, ALL SONGS and a collecting
     * route such as SINGLES) places song {@code songId}. Only songs placed in none must be bought
     * (Kelvin 2026-10-17: 只在单曲里出现、主线和支线都没有的歌要先买).
     */
    static boolean inStory(CaveData data, String songId) {
        for (CaveData.Level l : data.levels.values()) {
            if (CaveData.GRID.equals(l.kind) || CaveData.HUB.equals(l.kind) || l.collect) continue;
            for (CaveData.Spot s : l.spots) {
                if (songId.equals(s.song) && kindOf(s.type) == MapNode.SONG) return true;
            }
        }
        return false;
    }

    /** Shells single {@code songId} costs: its spot's {@code price}, else its level's, else {@link Shells#DEFAULT_PRICE}. */
    static int priceOf(CaveData data, String songId) {
        int levelPrice = 0;
        for (CaveData.Level l : data.levels.values()) {
            if (!l.collect) continue;
            for (CaveData.Spot s : l.spots) {
                if (songId.equals(s.song) && s.price > 0) return s.price;
            }
            if (levelPrice == 0) levelPrice = l.price;
        }
        return levelPrice > 0 ? levelPrice : Shells.DEFAULT_PRICE;
    }

    private boolean isLocked(MapNode n, Profile p) {
        if (n.spot == null || n.spot.requires.length == 0) return false;
        for (String id : n.spot.requires) {
            MapNode m = spotNode(id);
            if (m == null) m = node(id);
            if (m != null) {
                if (!m.cleared()) return true;
            } else if (!songCleared(p, id)) {
                return true;
            }
        }
        return false;
    }

    static boolean songCleared(Profile p, String songId) {
        if (p == null) return false;
        for (int d = 0; d < 4; d++) {
            ChartRecord r = p.record(songId, Difficulty.byOrdinal(d));
            if (r != null && r.plays > 0) return true;
        }
        return false;
    }

    /** Profile flag of a read story. */
    static String storyFlag(String storyId) {
        return Profile.FLAG_STORY + storyId;
    }
}
