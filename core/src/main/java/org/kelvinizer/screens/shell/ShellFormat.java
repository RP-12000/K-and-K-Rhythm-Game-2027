package org.kelvinizer.screens.shell;

import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Lang;

import java.util.Calendar;

/**
 * 菜单画面共用的文字格式化：深度（2,560 m）、等级（LV 12）、评分、准确率、游玩时长和「上次下潜」之类的日期。
 * {@code append*} 方法写入调用方的 {@link StringBuilder}，不分配内存，可每帧调用；
 * 返回 String 的方法会分配，只用来生成缓存的标签。
 *
 * <p>Text helpers shared by the SHELL screens (Welcome, Profile, FirstRun, Settings). The
 * {@code append*} methods write into a caller-owned {@link StringBuilder} without allocating, so
 * they may run per frame; the {@code String}-returning helpers allocate and are meant for
 * cached labels rebuilt only when the underlying data changes ({@code ProfileStore.revision()}).
 */
public final class ShellFormat {
    private static final String[] MONTHS = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT",
        "NOV", "DEC"};
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private ShellFormat() {
    }

    /** Audio output {@code index} (DeviceSettings: 0 speaker, 1 wired, 2 Bluetooth) in the interface language. */
    public static String outputName(int index) {
        if (index == 1) return Lang.t("WIRED", "有线耳机");
        if (index == 2) return Lang.t("BT", "蓝牙");
        return Lang.t("SPEAKER", "扬声器");
    }

    /** Appends a depth with grouping, its minus sign (below the surface, 2026-10-17) and the unit: {@code -2,560 m}. */
    public static StringBuilder appendDepth(StringBuilder sb, long meters) {
        Fonts.appendGrouped(sb, -Math.max(0L, meters));
        return sb.append(" m");
    }

    /** Appends {@code 1,510 SHELLS} ({@code 1,510 贝壳} in Chinese). */
    public static StringBuilder appendShells(StringBuilder sb, long shells) {
        Fonts.appendGrouped(sb, Math.max(0L, shells));
        return sb.append(Lang.t(" SHELLS", " 贝壳"));
    }

    /** Appends a diver's one-line summary: {@code 1,510 SHELLS · -2,560 m} (they replace LV and the rank, 2026-10-17). */
    public static StringBuilder appendDiverMeta(StringBuilder sb, org.kelvinizer.profile.Profile p) {
        appendShells(sb, p.shells).append(" · ");
        return appendDepth(sb, p.depthMeters());
    }

    /** Appends an accuracy 0..1 as a truncated percentage: {@code 99.12%}. */
    public static StringBuilder appendAccuracy(StringBuilder sb, float accuracy01) {
        Fonts.appendTruncated(sb, Math.max(0f, accuracy01) * 100.0, 2);
        return sb.append('%');
    }

    /** Appends a duration as {@code H:MM} (play time). */
    public static StringBuilder appendHoursMinutes(StringBuilder sb, long millis) {
        long minutes = Math.max(0L, millis) / 60000L;
        Fonts.appendInt(sb, minutes / 60L);
        sb.append(':');
        long m = minutes % 60L;
        if (m < 10) sb.append('0');
        return Fonts.appendInt(sb, m);
    }

    /**
     * Relative "last dive" label for a timestamp (allocates; cache it): {@code NO DIVES YET},
     * {@code DIVED TODAY}, {@code DIVED YESTERDAY}, {@code DIVED 3 DAYS AGO}, then the date; in
     * the interface language.
     */
    public static String lastDive(long when, long now) {
        if (when <= 0L) return Lang.t("NO DIVES YET", "还没有下潜");
        int days = dayDiff(when, now);
        if (days <= 0) return Lang.t("DIVED TODAY", "今天下潜过");
        if (days == 1) return Lang.t("DIVED YESTERDAY", "昨天下潜过");
        if (days < 14) return Lang.zh() ? days + " 天前下潜过" : "DIVED " + days + " DAYS AGO";
        return Lang.zh() ? date(when) + "下潜过" : "DIVED " + date(when);
    }

    /**
     * {@code LAST ACTIVITY: OCT 18 · 02:49} ({@code 上次活动：10月18日 02:49}), with the year when it is
     * not this year's, or a dash before the first dive (the PLAYER cards, Kelvin 2026-10-18 02:53;
     * allocates, cache it).
     */
    public static String lastActivity(long when, long now) {
        StringBuilder sb = new StringBuilder(40).append(Lang.t("LAST ACTIVITY: ", "上次活动："));
        if (when <= 0L) return sb.append('—').toString();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int thisYear = c.get(Calendar.YEAR);
        c.setTimeInMillis(when);
        int year = c.get(Calendar.YEAR);
        boolean zh = Lang.zh();
        if (zh && year != thisYear) Fonts.appendInt(sb, year).append('年');
        appendDate(sb, c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        if (!zh && year != thisYear) Fonts.appendInt(sb.append(", "), year);
        sb.append(zh ? " " : " · ");
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        if (h < 10) sb.append('0');
        Fonts.appendInt(sb, h).append(':');
        if (m < 10) sb.append('0');
        return Fonts.appendInt(sb, m).toString();
    }

    /** Short date {@code OCT 11} ({@code 10月11日} in Chinese; allocates, cache it). */
    public static String date(long when) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(when);
        return appendDate(new StringBuilder(8), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).toString();
    }

    /**
     * Appends a short date: {@code OCT 11} ({@code 10月11日} in Chinese); {@code day} ≤ 0 writes the
     * month alone ({@code OCT}, {@code 10月}). {@code month0} is 0-based, as in {@link Calendar}.
     */
    public static StringBuilder appendDate(StringBuilder sb, int month0, int day) {
        if (Lang.zh()) {
            Fonts.appendInt(sb, month0 + 1).append('月');
            return day > 0 ? Fonts.appendInt(sb, day).append('日') : sb;
        }
        sb.append(MONTHS[Math.max(0, Math.min(11, month0))]);
        return day > 0 ? Fonts.appendInt(sb.append(' '), day) : sb;
    }

    /** Short date and time {@code OCT 11 · 14:32}, or {@code TODAY · 14:32} (allocates). */
    public static String dateTime(long when, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(when);
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        String hm = (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
        int days = dayDiff(when, now);
        if (days <= 0) return Lang.t("TODAY · ", "今天 · ") + hm;
        if (days == 1) return Lang.t("YESTERDAY · ", "昨天 · ") + hm;
        return date(when) + " · " + hm;
    }

    /** Calendar days between two timestamps in local time (0 = same day). */
    public static int dayDiff(long from, long to) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(from);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(to);
        a.set(Calendar.HOUR_OF_DAY, 12);
        b.set(Calendar.HOUR_OF_DAY, 12);
        return (int) Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / (double) DAY_MS);
    }

    /** First displayable character of a name for the avatar initial ({@code K} when none). */
    public static char initialOf(String name) {
        if (name == null) return 'K';
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (Character.isSurrogate(c)) return 'K';
            return Character.toUpperCase(c);
        }
        return 'K';
    }
}
