package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongLibrary;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.profile.ChartRecord;
import org.kelvinizer.profile.Grades;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.Shells;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 当前这一层洞穴的世界：按 {@link CaveData} 里的层数据摆好每个点（歌曲洞、剧情入口、竖井、航线洞口），
 * 连好路，记下区域名、路牌、文案和边界；“所有曲目”层把所有曲包里的歌按当前排序和筛选平铺（位置由 {@link GridPage} 排）。
 * 换潜水员或成绩变化时刷新上锁、读过、路的实线虚线和进度文字。
 *
 * <p>The world of the current cave level (DESIGN §16): the spots of a {@link CaveData.Level}
 * (song holes, story entrances, shafts, hub routes, contacts), its paths, area names, signposts,
 * text slots and bounds. {@code rise} spots are not built since only a pinch rises (Kelvin
 * 2026-10-16); their paths go with them, but they still count for the bounds, and a level that
 * started on one still opens there ({@link #startX}), its arrival ring and wake light from the
 * spot nearest to it, or from the rise spot itself when the camera opens nearer to that (SINGLES,
 * 2026-10-17). On ALL SONGS this lists the songs of every pack (hidden secret packs left out),
 * flat, in the current sort, with only the songs the {@link MapFilter} keeps (Kelvin 2026-10-19); {@link GridPage}
 * places them. Per-diver state (records, locks, read
 * stories, solid / dashed paths, progress texts) is refreshed by {@link #refresh(Profile)}
 * on profile changes only.
 *
 * <p>World units: 1 unit = 1 reference pixel at zoom 1, y up (the data files are y down).
 */
final class MapWorld {
    /** Sort orders of the ALL SONGS grid. */
    static final int SORT_DEFAULT = 0, SORT_TITLE = 1, SORT_LEVEL = 2, SORT_BEST = 3, SORT_RECENT = 4;
    static final String[] SORT_NAMES = {"DEFAULT", "TITLE", "LEVEL", "BEST", "RECENT"};
    /**
     * Collected songs: the hex lattice of the placed songs (pitch, row height) and the clearance a free point keeps.
     */
    private static final float COLLECT_PITCH = 500f, COLLECT_ROW = 230f, COLLECT_FREE = 300f;
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
    /**
     * Left and top edges of the content (the bounds without the margin): ALL SONGS opens with this corner under the
     * badge.
     */
    final float contentLeft, contentTop;
    /** ALL SONGS: sort order and whether no song is left to show. */
    final int sort;
    boolean gridEmpty;
    /**
     * The song card's header, e.g. {@code SINGLES · 单曲} or {@code 01 · SHORELINE}, and the same without the Chinese
     * name for an English screen (Kelvin 2026-10-20: 如果语言选择是英语就不要出现中文).
     */
    final String cardHeader, cardHeaderEn;
    /** Stats of this level's songs. */
    int songs, charts, chartsPlayed;
    /**
     * Hub route plates: {songs, charts, charts of the lens difficulty, those played, full combos, all resonances} per
     * level a plate leads to, "" for the whole library.
     */
    private final Map<String, int[]> routeCounts = new HashMap<>();
    private final SongLibrary library;
    private final CaveData data;
    /** The selected difficulty the hub plates count (Kelvin 2026-10-21 20:15). */
    private final Difficulty lens;

    MapWorld(CaveData data, CaveData.Level level, SongLibrary library, Profile p, int sort, Difficulty lens,
             MapFilter filter) {
        this.level = level;
        this.library = library;
        this.data = data;
        this.sort = sort;
        this.lens = lens != null ? lens : Difficulty.SURFACE;
        List<MapNode> songNodes = new ArrayList<>();
        List<MapNode> other = new ArrayList<>();
        texts = new ArrayList<>(level.texts);
        if (CaveData.GRID.equals(level.kind)) {
            layoutGrid(data, level, library, p, sort, lens, filter, songNodes);
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
            all[i].clearScore = level.clearScore;
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
        if (isGrid()) { // a net (Kelvin 2026-10-19 21:18): each song to the songs half a step off it in the next row
            for (int i = 0; i < nodes.length; i++) {
                for (int j = (i / 2 + 1) * 2; j < Math.min(nodes.length, (i / 2 + 2) * 2); j++) {
                    if (Math.abs(GridPage.column(i) - GridPage.column(j)) != 1) continue;
                    ls.add(new int[]{i, j});
                    lc.add(Theme.INK1);
                    lar.add(false);
                }
            }
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
        cardHeaderEn = h.toString();
        if (!level.titleZh.isEmpty()) h.append(" · ").append(level.titleZh);
        cardHeader = h.toString();
        refresh(p);
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
     * Songs no level places go on a "collect" level, on the free points of the hex lattice its own songs sit on
     * (through its first song, {@link #COLLECT_PITCH} apart in a row, rows {@link #COLLECT_ROW} apart, each row
     * half a pitch along; Kelvin 2026-10-19 19:59 put the singles in two such rows round the start): nearest the
     * start first, a step up or down counting 4/3 of a step sideways, ties counter-clockwise from the right, so
     * they ring the start (2026-10-17 16:46: 从屏幕正中心开始，四周都有曲子). A point is free when no spot of
     * the level, no song placed before and not the level's title lies within {@link #COLLECT_FREE}.
     */
    private static void addUnplaced(CaveData data, CaveData.Level level, SongLibrary library, List<MapNode> songNodes) {
        List<SongMeta> songs = new ArrayList<>();
        for (SongMeta s : library.songs()) if (!placed(data, s.id())) songs.add(s);
        if (songs.isEmpty()) return;
        float ox = 0f, oy = 0f;
        CaveData.Spot first = null;
        List<float[]> taken = new ArrayList<>();
        for (CaveData.Spot s : level.spots) {
            if (s.id.equals(level.start)) {
                ox = s.x;
                oy = s.y;
            }
            if (kindOf(s.type) != MapNode.RISE) taken.add(new float[]{s.x, s.y});
            if (first == null && kindOf(s.type) == MapNode.SONG) first = s;
        }
        if (!Float.isNaN(level.titleX)) taken.add(new float[]{level.titleX, level.titleY});
        // lattice offset: the first song is a lattice point (the start, when the level places none)
        float ax = first != null ? first.x - ox : 0f, ay = first != null ? first.y - oy : 0f;
        float rowsOff = Math.round(ay / COLLECT_ROW);
        ay -= rowsOff * COLLECT_ROW;
        ax -= rowsOff * COLLECT_PITCH * 0.5f;
        ax -= Math.round(ax / COLLECT_PITCH) * COLLECT_PITCH;
        int rings = 2; // enough points for every song: a spot blocks at most four of them
        while (3 * rings * (rings + 1) < 4 * taken.size() + songs.size()) rings++;
        List<float[]> slots = new ArrayList<>();
        for (int r = -rings; r <= rings; r++) {
            for (int q = -rings; q <= rings; q++) {
                if (Math.abs(q) + Math.abs(r) + Math.abs(q + r) > 2 * rings) continue;
                float dx = ax + COLLECT_PITCH * (q + r * 0.5f), dy = ay + COLLECT_ROW * r;
                if (dx * dx + dy * dy < 1f) continue; // never on the start itself
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
                songNodes.add(new MapNode(null, MapNode.SONG, s, songNodes.size(), p[0], p[1],
                    CaveData.defaultRadius(CaveData.SONG)));
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
     * Adds the songs of every pack (hidden secret packs left out) to the grid, flat and in the sort order (Kelvin
     * 2026-10-19 13:14: no grouping; the pack box that checked packs went on 2026-10-20: 选曲包的那个功能去掉吧). With a
     * filter on, only the songs it keeps are added.
     */
    private void layoutGrid(CaveData data, CaveData.Level level, SongLibrary lib, Profile p, int sort, Difficulty lens,
                            MapFilter filter, List<MapNode> songNodes) {
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
        List<String> on = new ArrayList<>();
        for (CaveData.Pack pk : level.packs) {
            int packSecret = pk.secret == null || (p != null && p.hasFlag(packRevealFlag(pk)))
                ? SECRET_NONE : secret(pk.secret);
            if (packSecret != SECRET_HIDDEN) on.addAll(packSongs(data, pk, lib));
        }
        for (SongMeta s : list) {
            if (!on.contains(s.id()) || (filtering && !filter.accepts(s, p, lens))) continue;
            MapNode n = new MapNode(null, MapNode.SONG, s, songNodes.size(), 0f, 0f, MapRenderer.SONG_R);
            n.still = true;
            if (garbled.contains(s)) markGlitch(n);
            songNodes.add(n);
        }
        gridEmpty = songNodes.isEmpty();
    }

    /**
     * The song ids of pack {@code pk}: its list, every library song ({@code "songs": "all"}), or the songs placed on
     * its {@code level} and, when that level collects, every song placed on no level.
     */
    private static List<String> packSongs(CaveData data, CaveData.Pack pk, SongLibrary lib) {
        List<String> out = new ArrayList<>();
        CaveData.Level l = pk.level != null ? data.levels.get(pk.level) : null;
        for (SongMeta s : lib.songs()) {
            boolean in;
            if (pk.level != null) {
                in = l != null && (onLevel(l, s.id()) || (l.collect && !placed(data, s.id())));
            } else if (pk.songs == null) {
                in = true;
            } else {
                in = false;
                for (String id : pk.songs) in |= s.id().equals(id);
            }
            if (in) out.add(s.id());
        }
        return out;
    }

    /** True when level {@code l} has a song spot for {@code songId}. */
    private static boolean onLevel(CaveData.Level l, String songId) {
        for (CaveData.Spot s : l.spots) if (songId.equals(s.song) && kindOf(s.type) == MapNode.SONG) return true;
        return false;
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
     * {@code s} without its Chinese parts, for an English screen (Kelvin 2026-10-20: 如果语言选择是英语就不要出现中文): the
     * parts of a {@code " · "} list that hold a CJK character are left out, e.g. {@code SIDE · 支线} gives {@code SIDE}.
     */
    static String latin(String s) {
        if (s == null || s.isEmpty() || !hasCjk(s)) return s == null ? "" : s;
        StringBuilder b = new StringBuilder(s.length());
        for (String part : s.split(" · ")) {
            if (part.isEmpty() || hasCjk(part)) continue;
            if (b.length() > 0) b.append(" · ");
            b.append(part);
        }
        return b.toString();
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) if (MapRenderer.units(s.charAt(i)) == 2) return true;
        return false;
    }

    /**
     * The names of the unmet requirements of spot {@code s} on {@code l} for diver {@code p}
     * (empty when it is open): spot ids on that level (songs cleared, with the level's
     * clearScore when it sets one; stories read, the tutorial seen, shafts not sealed) or song ids.
     */
    static List<String> unmet(CaveData.Level l, CaveData.Spot s, Profile p, SongLibrary lib) {
        List<String> out = new ArrayList<>();
        for (String id : s.requires) {
            CaveData.Spot r = l.spot(id);
            String song = r != null ? r.song : id;
            boolean met;
            if (r == null) {
                met = songCleared(p, id, l.clearScore);
            } else {
                switch (kindOf(r.type)) {
                    case MapNode.SONG:
                        met = r.song != null && songCleared(p, r.song, l.clearScore);
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
     * or some spot that places it is open; else the unmet requirements of the first locked spot,
     * with its level's clearScore in {@code score[0]}.
     */
    static String[] gridLock(CaveData data, SongLibrary lib, String songId, Profile p, int[] score) {
        String[] first = null;
        for (CaveData.Level l : data.levels.values()) {
            if (CaveData.GRID.equals(l.kind)) continue;
            for (CaveData.Spot s : l.spots) {
                if (!songId.equals(s.song) || kindOf(s.type) != MapNode.SONG) continue;
                List<String> u = unmet(l, s, p, lib);
                if (u.isEmpty()) return null;
                if (first == null) {
                    first = u.toArray(new String[0]);
                    score[0] = l.clearScore;
                }
            }
        }
        return first;
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
                        return Float.compare(a.has(lens) ? a.constant(lens) : 99f,
                            b.has(lens) ? b.constant(lens) : 99f);
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
    void refresh(Profile p) {
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
        int[] lockScore = new int[1];
        for (MapNode n : all) {
            n.forSale = n.isSong() && !n.glitch && n.song != null && !inStory(data, n.song.id())
                && (p == null || !p.owns(n.song.id()));
            n.price = n.forSale ? priceOf(data, n.song.id()) : 0;
            n.forSale &= n.price > 0; // "price": 0 on its spot: free (Kelvin 2026-10-21, Aleph-0)
            if (grid && n.isSong()) {
                String[] c = gridLock(data, library, n.song.id(), p, lockScore);
                n.lockConds = c != null ? c : new String[0];
                n.needsText = c != null ? String.join(" · ", c) : "";
                n.clearScore = c != null ? lockScore[0] : 0;
                n.locked = c != null && !n.glitch;
            } else {
                n.locked = isLocked(n, p) && !n.glitch;
            }
        }
        for (int i = 0; i < la.length; i++) lcleared[i] = all[la[i]].cleared() && all[lb[i]].cleared();
        routeCounts.clear();
        routeCounts.put("", countSongs(null, p));
        for (MapNode n : all) {
            String to = n.spot != null ? n.spot.to : null;
            CaveData.Level l = to != null ? data.levels.get(to) : null;
            if (l != null && !routeCounts.containsKey(to)) routeCounts.put(to, countSongs(l, p));
        }
    }

    /**
     * {songs, charts, lens charts, played lens charts, full combos, all resonances} of level {@code l}: the songs of
     * {@link #under} it, each once; the whole library when {@code l} is null. Cleared counts only the lens difficulty
     * (Kelvin 2026-10-21 20:15: not all charts together); an ALL RESONANCE is also a full combo.
     */
    private int[] countSongs(CaveData.Level l, Profile p) {
        int[] c = new int[6];
        for (SongMeta s : library.songs()) {
            if (l != null && !under(l, s.id(), 0)) continue;
            c[0]++;
            for (int d = 0; d < 4; d++) if (s.has(Difficulty.byOrdinal(d))) c[1]++;
            if (!s.has(lens)) continue;
            c[2]++;
            ChartRecord r = p != null ? p.record(s.id(), lens) : null;
            if (r == null || r.plays <= 0) continue;
            c[3]++;
            if (r.fullCombo || r.allResonance) c[4]++;
            if (r.allResonance) c[5]++;
        }
        return c;
    }

    /**
     * True when song {@code id} counts for a plate leading to level {@code l}: placed on it or on a level below it
     * by {@code parent} (MAIN counts every chapter, Kelvin 2026-10-21 20:54), or placed nowhere when {@code l}
     * collects. {@code depth} stops a loop of parents.
     */
    private boolean under(CaveData.Level l, String id, int depth) {
        if (onLevel(l, id) || l.collect && !placed(data, id)) return true;
        if (depth > 8) return false;
        for (CaveData.Level c : data.levels.values()) {
            if (c != l && l.id.equals(c.parent) && under(c, id, depth + 1)) return true;
        }
        return false;
    }

    /**
     * The counts behind a plate's progress bar ({@link #countSongs}: index 2 the lens charts, 3 played, 4 full
     * combos, 5 all resonances), or null when the plate shows none.
     */
    int[] routeProgress(CaveData.Spot spot) {
        return spot != null && "@progress".equals(spot.sub) ? countsFor(spot) : null;
    }

    /** The counts of the level {@code spot} leads to and the levels below it, else of the whole library. */
    private int[] countsFor(CaveData.Spot spot) {
        int[] c = spot != null && spot.to != null ? routeCounts.get(spot.to) : null;
        if (c == null) c = routeCounts.get("");
        return c != null ? c : new int[6];
    }

    /** {@code 6 SONGS · 2/6 CLEARED}, without the difficulty's name (Kelvin 2026-10-21 20:28). */
    static void appendProgress(StringBuilder sb, int songs, int charts, int played) {
        sb.append(songs).append(songs == 1 ? " SONG · " : " SONGS · ").append(played).append('/').append(charts)
            .append(" CLEARED");
    }

    /**
     * Text of a plate's {@code sub} placeholder ({@code @progress}, {@code @charts}): it counts the songs of the level
     * the plate leads to and the levels below it, or of the whole library without one (Kelvin 2026-10-21: SINGLES
     * showed them all).
     */
    String routeSub(CaveData.Spot spot, String sub) {
        if (sub == null) return "";
        int[] c = countsFor(spot);
        StringBuilder sb = new StringBuilder(40);
        if ("@progress".equals(sub)) appendProgress(sb, c[0], c[2], c[3]);
        else if ("@charts".equals(sub)) sb.append(c[0]).append(" SONGS · ").append(c[1]).append(" CHARTS");
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

    /**
     * Shells single {@code songId} costs: its spot's {@code price} (0: free), else its level's, else
     * {@link Shells#DEFAULT_PRICE}.
     */
    static int priceOf(CaveData data, String songId) {
        int levelPrice = 0;
        for (CaveData.Level l : data.levels.values()) {
            if (!l.collect) continue;
            for (CaveData.Spot s : l.spots) {
                if (songId.equals(s.song) && s.price >= 0) return s.price;
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
            } else if (!songCleared(p, id, level.clearScore)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code p} played {@code songId} on some difficulty with a best of at least {@code score}. */
    static boolean songCleared(Profile p, String songId, int score) {
        if (p == null) return false;
        for (int d = 0; d < 4; d++) {
            ChartRecord r = p.record(songId, Difficulty.byOrdinal(d));
            if (r != null && r.plays > 0 && r.bestScore >= score) return true;
        }
        return false;
    }

    /**
     * What a lock asks of {@code names} on one line (the tip, the song card): {@code 通关 X} / {@code CLEAR X},
     * or with a level's clearScore {@code 在 X 的任意难度中拿到 B 及以上} /
     * {@code GET B OR ABOVE ON ANY DIFFICULTY OF X} (Kelvin 2026-10-21 22:54).
     */
    static String clearPhrase(String names, int score, boolean zh) {
        if (score <= 0) return zh ? "通关 " + names : "CLEAR " + names;
        String[] p = clearParts(names, score, zh);
        return p[0] + " " + p[1] + (zh ? "" : " ") + p[2];
    }

    /**
     * {@link #clearPhrase} with a clearScore in three parts, which the label under a hole sets on two lines:
     * {@code 在 X} · {@code 的任意难度中} · {@code 拿到 B 及以上} / {@code GET B OR ABOVE} · {@code ON ANY DIFFICULTY}
     * · {@code OF X}. A score where a grade starts goes by that grade (80,000,000: B), any other in full.
     */
    static String[] clearParts(String names, int score, boolean zh) {
        String g = null;
        for (int i = Grades.R; i < Grades.F; i++) if (Grades.GRADE_MIN_SCORE[i] == score) g = Grades.GRADES[i];
        String s = g != null ? g : String.format(Locale.ROOT, "%,d", score);
        if (zh) return new String[] {"在 " + names, "的任意难度中", "拿到 " + s + (g != null ? " 及以上" : " 分及以上")};
        return new String[] {"GET " + s + (g != null ? " OR ABOVE" : " OR MORE"), "ON ANY DIFFICULTY", "OF " + names};
    }

    /** Profile flag of a read story. */
    static String storyFlag(String storyId) {
        return Profile.FLAG_STORY + storyId;
    }
}
