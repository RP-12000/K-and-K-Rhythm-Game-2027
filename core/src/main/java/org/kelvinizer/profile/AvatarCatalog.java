package org.kelvinizer.profile;

/**
 * 头像表：现在只有一个默认头像（紫色同心圆）。以后的头像（角色等）通过打某些歌曲或者地图节点的奖励获得，加在这里；
 * 深度不再解锁头像。
 *
 * <p>The avatar table (Kelvin 2026-10-30 16:37 「头像暂时固定为一个默认选项，预留代码空间…这些头像可以通过一些歌曲的方式来
 * 获得」): one default avatar for now, purple rings. Each entry has an id (stored in {@link Profile#avatarId} and
 * {@link Profile#ownedAvatars}), its sigil and how it is won: {@link Source#DEFAULT} (everyone), {@link Source#SONG}
 * ({@code ref} is the song id) or {@link Source#MAP_NODE} (a map node's {@code avatarReward}, docs/MAP_FORMAT.md;
 * {@code ref} is {@code level/node}). Depth no longer unlocks avatars or their options.
 */
public final class AvatarCatalog {
    /** How an avatar is won. */
    public enum Source { DEFAULT, SONG, MAP_NODE }

    /** One avatar: id, sigil, source and the song or map node that gives it (null for the default). */
    public static final class Entry {
        public final String id;
        public final Source source;
        public final String ref;
        private final AvatarSpec spec;

        Entry(String id, AvatarSpec spec, Source source, String ref) {
            this.id = id;
            this.spec = spec;
            this.source = source;
            this.ref = ref;
        }
    }

    /** The default avatar's id. */
    public static final String DEFAULT = "default";

    private static final Entry[] ENTRIES = {new Entry(DEFAULT, rings(), Source.DEFAULT, null)};

    private AvatarCatalog() {
    }

    /** The default sigil: purple rings on a circle, no glyph or frame. */
    private static AvatarSpec rings() {
        AvatarSpec a = new AvatarSpec();
        a.palette = 4;
        a.glyph = 0;
        return a;
    }

    /** @return the entry for {@code id}, or the default one for an unknown id. */
    public static Entry get(String id) {
        for (Entry e : ENTRIES) if (e.id.equals(id)) return e;
        return ENTRIES[0];
    }

    /** @return a copy of {@code id}'s sigil (the default one for an unknown id). */
    public static AvatarSpec specOf(String id) {
        return get(id).spec.copy();
    }

    /** @return true when {@code p} may show avatar {@code id}: the default, or one it has won. */
    public static boolean owns(Profile p, String id) {
        if (DEFAULT.equals(id)) return true;
        return id != null && p.ownedAvatars != null && p.ownedAvatars.contains(id) && get(id).id.equals(id);
    }
}
