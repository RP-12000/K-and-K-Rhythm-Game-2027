package org.kelvinizer.screens.shell;

import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.Depth;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.ui.Avatar;

import java.util.List;

/**
 * 本机所有潜水员的显示缓存，供潜水员切换卡片和玩家档案的 PLAYER 卡片使用：
 * 名字、「贝壳 · 深度」、上次下潜（PLAYER 卡片上是「上次活动」的日期和时间）和头像参数。
 * 只在 {@link ProfileStore#revision()} 变化时重建，绘制时不分配内存。
 *
 * <p>Display cache of every local diver (the diver switcher rows and the Profile PLAYER cards):
 * name, {@code shells · depth}, the last-dive label (the switcher) or last activity (the cards)
 * and the sigil spec. Rebuilt only when
 * {@link ProfileStore#revision()} changed (or on {@link #invalidate()}), so drawing allocates
 * nothing.
 */
public final class DiverList {
    /** One diver's display data. */
    public static final class Entry {
        /** Profile id. */
        public String id;
        /** Diver name. */
        public String name = "";
        /** {@code 1,510 SHELLS · 2,560 m}. */
        public final StringBuilder meta = new StringBuilder(48);
        /** Relative last-dive label. */
        public String lastDive = "";
        /** {@code LAST ACTIVITY: OCT 18 · 02:49}. */
        public String lastActivity = "";
        /** Depth in metres. */
        public int depth;
        /** Play count. */
        public int plays;
        /** True for the active diver. */
        public boolean active;
        /** Sigil spec. */
        public final Avatar.Spec spec = new Avatar.Spec();
        /** Orbit ring unlocked (6,000 m). */
        public boolean orbit;
    }

    private final Entry[] entries = new Entry[ProfileStore.MAX_PROFILES];
    private int count;
    private int revision = Integer.MIN_VALUE;
    private String activeId;

    /** Creates an empty cache. */
    public DiverList() {
        for (int i = 0; i < entries.length; i++) entries[i] = new Entry();
    }

    /** Forces a rebuild on the next {@link #refresh}. */
    public void invalidate() {
        revision = Integer.MIN_VALUE;
    }

    /** Rebuilds from {@code store} when it changed; returns true when rebuilt. */
    public boolean refresh(ProfileStore store) {
        String act = store.activeId();
        if (store.revision() == revision && (act == null ? activeId == null : act.equals(activeId))) return false;
        revision = store.revision();
        activeId = act;
        List<Profile> list = store.list();
        long now = System.currentTimeMillis();
        count = Math.min(entries.length, list.size());
        for (int i = 0; i < count; i++) bind(entries[i], list.get(i), act, now);
        return true;
    }

    private static void bind(Entry e, Profile p, String activeId, long now) {
        e.id = p.id;
        e.name = p.name != null ? p.name : "";
        e.depth = p.depthMeters();
        e.plays = p.playCount;
        e.active = p.id != null && p.id.equals(activeId);
        e.meta.setLength(0);
        ShellFormat.appendDiverMeta(e.meta, p);
        e.lastDive = ShellFormat.lastDive(p.lastPlayedAt, now);
        e.lastActivity = ShellFormat.lastActivity(p.lastPlayedAt, now);
        AvatarView.toSpec(p.avatar, e.name, e.spec);
        e.orbit = Depth.orbitRingUnlocked(e.depth);
    }

    /** Number of divers. */
    public int size() {
        return count;
    }

    /** Diver {@code i} in store order. */
    public Entry get(int i) {
        return entries[i];
    }

    /** Index of the active diver, or -1. */
    public int activeIndex() {
        for (int i = 0; i < count; i++) if (entries[i].active) return i;
        return -1;
    }
}
