package org.kelvinizer.map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 洞穴地图的数据：每一层（航线页、单曲、主线各章、所有曲目）都是 assets/maps 下的一个 JSON 文件，
 * 歌曲坐标、路、区域名、文案位置、解锁条件、剧情入口全在文件里，以后的地图编辑器直接改这些文件即可；剧情正文在 maps/stories.json。
 *
 * <p>Cave map data (DESIGN §16, Kelvin 2026-10-12 22:47: "these parameters must remain flexible" for a
 * future map designer). Every level is one JSON file under {@code assets/maps/}, listed in
 * {@code maps/index.json}; story texts live in {@code maps/stories.json}. The format is documented
 * in {@code docs/MAP_FORMAT.md}. Coordinates in the files are x right, y down (like the mock-ups);
 * the loader flips y into the map world (y up). A broken file is logged and skipped, never thrown.
 */
final class CaveData {
    /** Level kinds. */
    static final String HUB = "hub", ROUTE = "route", CHAPTER = "chapter", GRID = "grid";
    /** Spot types. */
    static final String SONG = "song", STORY = "story", SHAFT = "shaft", PORTAL = "route", RISE = "rise",
        CONTACT = "contact", TUTORIAL = "tutorial";
    /** Secret songs and packs (special unlock): not shown at all, or shown garbled. */
    static final String HIDDEN = "hidden", GLITCH = "glitch";

    final Map<String, Level> levels = new LinkedHashMap<>();
    final Map<String, Story> stories = new LinkedHashMap<>();

    /** One map level. */
    static final class Level {
        String id = "", kind = ROUTE, title = "", titleZh = "", numeral = "", route = "", routeZh = "";
        /** Where RISE leads: another level id, or {@code "title"} for the Welcome screen. */
        String parent = "title";
        /** Where RISE leads in story mode, when that differs (null: {@link #parent}). */
        String storyParent;
        /** Spot the camera starts on and the wake light spreads from. */
        String start = "";
        /** Shown with a DEMO tag (MAIN before its chapters open). */
        boolean demo;
        /** Also shows every library song that no level places (SINGLES: new song folders appear here). */
        boolean collect;
        /** Shells a single of this level costs when its spot sets no {@code price} (0: the default, 300). */
        int price;
        /** Ocean depth (m) the backdrop is tinted for: shallow levels are light, deep ones dark. */
        float depth = 30f;
        /** World position of the level title plate (NaN: none). */
        float titleX = Float.NaN, titleY = Float.NaN;
        /** Overview zoom (0: fit the level). */
        float overviewZoom;
        /** Subtitle lines under the title (may be empty). */
        String sub = "", subZh = "";
        final List<Spot> spots = new ArrayList<>();
        final List<Link> links = new ArrayList<>();
        final List<Area> areas = new ArrayList<>();
        final List<Sign> signs = new ArrayList<>();
        final List<Text> texts = new ArrayList<>();
        final List<Pack> packs = new ArrayList<>();
        /** Soft light pools on the floor: x, y, radius, strength. */
        final List<float[]> pools = new ArrayList<>();

        Spot spot(String spotId) {
            for (Spot s : spots) if (s.id.equals(spotId)) return s;
            return null;
        }
    }

    /** A hole, story entrance, shaft or route on a level. */
    static final class Spot {
        String id = "", type = SONG, song, story, to, label, labelZh, sub, subZh, area, art;
        /** Shafts and routes: the level this leads to in story mode (null: {@link #to}). */
        String storyTo;
        /** Shafts and routes: the spot of the target level the camera starts on (null: its start). */
        String at;
        /** Songs: {@link #HIDDEN} or {@link #GLITCH} until revealed (null: none). */
        String secret;
        float x, y, r;
        boolean sealed;
        /** Song spot ids (or song ids) that must be cleared first. */
        String[] requires = new String[0];
        Color color;
        /** Songs: shells this single costs when it must be bought (0: the level's price). */
        int price;
    }

    /** A path between two spots. */
    static final class Link {
        String a, b, kind = "path", area;
    }

    /** A named area painted on the floor. */
    static final class Area {
        String id = "", name = "", nameZh = "", sub = "";
        float x, y;
        Color color = new Color(0.56f, 0.48f, 1f, 1f);
    }

    /** An edge signpost: shown at the screen edge while its target is off screen. */
    static final class Sign {
        String label = "", labelZh = "", sub = "";
        float x, y;
        Color color = new Color(0.5f, 0.85f, 1f, 1f);
    }

    /** A free text slot on the floor (文案位置). */
    static final class Text {
        String text = "", textZh = "";
        float x, y, size = 22f;
        Color color = new Color(0.66f, 0.71f, 0.84f, 1f);
    }

    /** ALL SONGS pack: songs the page shows (listed by id, every song, or a level's); no pack box since 2026-10-20. */
    static final class Pack {
        String title = "", titleZh = "", note = "", noteZh = "";
        /** Song ids, or null for every song. */
        String[] songs;
        /** A level id: the songs placed on that level (and, on a collecting level, the songs placed nowhere). */
        String level;
        /** Unused since the pack box (Kelvin 2026-10-19); kept so the map editor round-trips it. */
        int placeholders;
        /** {@link #HIDDEN} or {@link #GLITCH} until revealed (null: none). */
        String secret;
    }

    /** A story entry. */
    static final class Story {
        String id = "", number = "", title = "", titleZh = "";
        int minutes = 2;
        String[] pages = new String[0], pagesZh = new String[0];
    }

    /** Loads {@code maps/index.json} and everything it lists (missing files are skipped). */
    static CaveData load() {
        CaveData d = new CaveData();
        try {
            JsonValue index = read("maps/index.json");
            if (index == null) return d;
            JsonValue list = index.get("levels");
            if (list != null) {
                for (JsonValue v = list.child; v != null; v = v.next) {
                    Level l = parseLevel(read("maps/" + v.asString() + ".json"));
                    if (l != null) d.levels.put(l.id, l);
                }
            }
            JsonValue st = read("maps/" + index.getString("stories", "stories") + ".json");
            if (st != null) {
                for (JsonValue v = st.child; v != null; v = v.next) {
                    Story s = new Story();
                    s.id = v.name != null ? v.name : v.getString("id", "");
                    s.number = v.getString("number", "");
                    s.title = v.getString("title", "");
                    s.titleZh = v.getString("titleZh", s.title);
                    s.minutes = v.getInt("minutes", 2);
                    s.pages = strings(v.get("pages"));
                    s.pagesZh = strings(v.get("pagesZh"));
                    d.stories.put(s.id, s);
                }
            }
        } catch (RuntimeException e) {
            log("maps: " + e);
        }
        return d;
    }

    private static JsonValue read(String path) {
        try {
            FileHandle f = Gdx.files.internal(path);
            if (!f.exists()) {
                log("missing " + path);
                return null;
            }
            return new JsonReader().parse(f);
        } catch (RuntimeException e) {
            log("cannot read " + path + ": " + e.getMessage());
            return null;
        }
    }

    private static Level parseLevel(JsonValue v) {
        if (v == null) return null;
        Level l = new Level();
        l.id = v.getString("id", "");
        if (l.id.isEmpty()) return null;
        l.kind = v.getString("kind", ROUTE);
        l.title = v.getString("title", l.id.toUpperCase());
        l.titleZh = v.getString("titleZh", "");
        l.numeral = v.getString("numeral", "");
        l.route = v.getString("route", l.title);
        l.routeZh = v.getString("routeZh", l.titleZh);
        l.parent = v.getString("parent", "title");
        l.storyParent = v.getString("storyParent", null);
        l.start = v.getString("start", "");
        l.demo = v.getBoolean("demo", false);
        l.collect = v.getBoolean("collect", false);
        l.price = Math.max(0, v.getInt("price", 0));
        l.depth = v.getFloat("depth", 30f);
        l.overviewZoom = v.getFloat("overviewZoom", 0f);
        l.sub = v.getString("sub", "");
        l.subZh = v.getString("subZh", "");
        JsonValue t = v.get("titleAt");
        if (t != null && t.size >= 2) {
            l.titleX = t.getFloat(0);
            l.titleY = -t.getFloat(1);
        }
        for (JsonValue s = child(v, "spots"); s != null; s = s.next) {
            Spot p = new Spot();
            p.id = s.getString("id", "s" + l.spots.size());
            p.type = s.getString("type", SONG);
            p.song = s.getString("song", null);
            p.story = s.getString("story", null);
            p.to = s.getString("to", null);
            p.label = s.getString("label", null);
            p.labelZh = s.getString("labelZh", null);
            p.sub = s.getString("sub", null);
            p.subZh = s.getString("subZh", null);
            p.area = s.getString("area", null);
            p.art = s.getString("art", null);
            p.x = s.getFloat("x", 0f);
            p.y = -s.getFloat("y", 0f);
            p.r = s.getFloat("r", defaultRadius(p.type));
            p.sealed = s.getBoolean("sealed", false);
            p.requires = strings(s.get("requires"));
            p.storyTo = s.getString("storyTo", null);
            p.at = s.getString("at", null);
            p.secret = s.getString("secret", null);
            p.color = color(s.getString("color", null), null);
            p.price = Math.max(0, s.getInt("price", 0));
            l.spots.add(p);
        }
        for (JsonValue s = child(v, "links"); s != null; s = s.next) {
            Link k = new Link();
            if (s.isArray()) {
                k.a = s.getString(0);
                k.b = s.getString(1);
                if (s.size > 2) k.kind = s.getString(2);
                if (s.size > 3) k.area = s.getString(3);
            } else {
                k.a = s.getString("a", null);
                k.b = s.getString("b", null);
                k.kind = s.getString("kind", "path");
                k.area = s.getString("area", null);
            }
            if (k.a != null && k.b != null) l.links.add(k);
        }
        for (JsonValue s = child(v, "areas"); s != null; s = s.next) {
            Area a = new Area();
            a.id = s.getString("id", "");
            a.name = s.getString("name", "");
            a.nameZh = s.getString("nameZh", "");
            a.sub = s.getString("sub", "");
            a.x = s.getFloat("x", 0f);
            a.y = -s.getFloat("y", 0f);
            a.color = color(s.getString("color", null), a.color);
            l.areas.add(a);
        }
        for (JsonValue s = child(v, "signs"); s != null; s = s.next) {
            Sign g = new Sign();
            g.label = s.getString("label", "");
            g.labelZh = s.getString("labelZh", "");
            g.sub = s.getString("sub", "");
            g.x = s.getFloat("x", 0f);
            g.y = -s.getFloat("y", 0f);
            g.color = color(s.getString("color", null), g.color);
            l.signs.add(g);
        }
        for (JsonValue s = child(v, "texts"); s != null; s = s.next) {
            Text x = new Text();
            x.text = s.getString("text", "");
            x.textZh = s.getString("textZh", x.text);
            x.x = s.getFloat("x", 0f);
            x.y = -s.getFloat("y", 0f);
            x.size = s.getFloat("size", 22f);
            x.color = color(s.getString("color", null), x.color);
            l.texts.add(x);
        }
        for (JsonValue s = child(v, "packs"); s != null; s = s.next) {
            Pack p = new Pack();
            p.title = s.getString("title", "");
            p.titleZh = s.getString("titleZh", "");
            p.note = s.getString("note", "");
            p.noteZh = s.getString("noteZh", p.note);
            JsonValue songs = s.get("songs");
            // "all" (any string) = every song; no "songs" = none (MAP_FORMAT §5), not every song
            p.songs = songs == null ? new String[0] : songs.isString() ? null : strings(songs);
            p.level = s.getString("level", null);
            p.placeholders = s.getInt("placeholders", 0);
            p.secret = s.getString("secret", null);
            l.packs.add(p);
        }
        for (JsonValue s = child(v, "pools"); s != null; s = s.next) {
            if (s.size >= 4) l.pools.add(new float[]{s.getFloat(0), -s.getFloat(1), s.getFloat(2), s.getFloat(3)});
        }
        return l;
    }

    static float defaultRadius(String type) {
        switch (type) {
            case STORY:
                return 96f;
            case SHAFT:
                return 156f;
            case PORTAL:
                return 132f;
            case RISE:
                return 70f;
            case CONTACT:
                return 34f;
            default:
                return 108f;
        }
    }

    private static JsonValue child(JsonValue v, String name) {
        JsonValue a = v.get(name);
        return a != null ? a.child : null;
    }

    private static String[] strings(JsonValue a) {
        if (a == null) return new String[0];
        if (a.isString()) return new String[]{a.asString()};
        String[] out = new String[a.size];
        int i = 0;
        for (JsonValue s = a.child; s != null; s = s.next) out[i++] = s.asString();
        return out;
    }

    private static Color color(String hex, Color fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try {
            return Color.valueOf(hex);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static void log(String msg) {
        if (Gdx.app != null) Gdx.app.log("CaveData", msg);
    }
}
