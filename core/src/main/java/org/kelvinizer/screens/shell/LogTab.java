package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongLibrary;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Theme;

/**
 * 玩家档案的 RECENT 标签页（原名 LOG，Kelvin 2026-10-17 改名）：最近 20 局，新的在上；
 * 每行有缩略图、曲名、难度、FC / AR 标记（2026-10-18 14:29 起中英文都这样写）、日期、分数和评级。
 * 只显示，不能点；滚动手感与地图相同。
 *
 * <p>RECENT (named LOG until 2026-10-17; DESIGN §8.2, P1): the last 20 plays, newest first: thumb, title, difficulty chip,
 * FC / AR tag (both languages since 2026-10-18 14:29), date (UI 20), score and grade pill. Display only; scrolls with
 * map physics.
 */
public final class LogTab extends ProfileTab {
    private static final float ROW_H = 96f;

    private static final class Row {
        org.kelvinizer.library.SongMeta song;
        String title = "";
        int diff;
        int score;
        String grade;
        String date = "";
        String tag;
        boolean allResonance;
    }

    private final Row[] rows = new Row[Profile.RECENT_PLAYS_MAX];
    private final ThumbCache thumbs;
    private int count;

    /** Creates the tab; thumbnails come from {@code thumbs}. */
    public LogTab(Main game, ShellScreen host, ThumbCache thumbs) {
        super(game, host);
        this.thumbs = thumbs;
        for (int i = 0; i < rows.length; i++) rows[i] = new Row();
    }

    @Override
    public void refresh(Profile p, DiverStats stats) {
        SongLibrary lib = game.library();
        long now = System.currentTimeMillis();
        count = Math.min(rows.length, p.recentPlays.size());
        for (int i = 0; i < count; i++) {
            Profile.RecentPlay rp = p.recentPlays.get(i);
            Row r = rows[i];
            r.song = rp != null ? lib.song(rp.songId) : null;
            r.title = r.song != null ? r.song.title() : (rp != null && rp.songId != null ? rp.songId : "?");
            Difficulty d = rp != null ? Difficulty.parse(rp.difficulty) : null;
            r.diff = d != null ? d.ordinal() : 0;
            r.score = rp != null ? rp.score : 0;
            r.grade = rp != null ? rp.grade : null;
            r.date = rp != null ? ShellFormat.dateTime(rp.t, now) : "";
            r.allResonance = rp != null && rp.allResonance;
            // AR and FC in both languages (Kelvin 2026-10-18 14:29)
            r.tag = rp == null ? null : (rp.allResonance ? "AR" : (rp.fullCombo ? "FC" : null));
        }
    }

    @Override
    protected float contentHeight() {
        return Math.max(1, count) * ROW_H + 24f;
    }

    @Override
    protected void drawContent(Batch b, float top, float alpha) {
        if (count == 0) {
            fonts.draw(b, Fonts.Role.UI, 22f, Lang.t("NO DIVES LOGGED YET", "还没有下潜记录"), x + w * 0.5f,
                top - ROW_H * 0.5f, Align.center | Fonts.VCENTER, Theme.INK2, alpha * rowIn(0));
            return;
        }
        float gradeX = x + w - 56f, scoreR = x + w - 120f;
        for (int i = 0; i < count; i++) {
            float ry = top - (i + 1) * ROW_H;
            if (!scroll.isVisible(ry, ry + ROW_H)) continue;
            float e = rowIn(i);
            float a = alpha * e;
            float dx = 24f * (1f - e);
            Row r = rows[i];
            float cy = ry + ROW_H * 0.5f;
            if (i % 2 == 0) draw.roundRect(x, ry + 4f, w, ROW_H - 8f, 12f, Theme.PANEL, 0.45f * a);
            ChartRows.thumb(b, draw, fonts, thumbs, r.song, r.title, r.diff, x + 16f + dx, cy - 36f, 72f, a);
            float tx = x + 108f + dx;
            fonts.drawFit(b, Fonts.Role.UI_BOLD, 22f, r.title, tx, cy + 16f, Align.left | Fonts.VCENTER,
                scoreR - 280f - tx, 0.7f, Theme.INK1, a);
            float cw = ChartRows.diffChip(b, draw, fonts, r.diff, tx, cy - 20f, a);
            float after = tx + cw + 14f;
            if (r.tag != null) {
                after += 14f + fonts.draw(b, Fonts.Role.UI_LABEL, 14f, r.tag, after, cy - 20f, Align.left | Fonts.VCENTER,
                    r.allResonance ? Theme.ALL_RESONANCE : Theme.FULL_COMBO, a);
            }
            fonts.draw(b, Fonts.Role.UI, 20f, r.date, after, cy - 20f, Align.left | Fonts.VCENTER, Theme.INK3, a);
            fonts.drawDigits(b, Fonts.Role.NUMBER, 24f, r.score, 9, true, scoreR + dx, cy, Align.right | Fonts.VCENTER,
                Theme.INK1, Theme.LEADING_ZERO, a);
            ChartRows.gradePill(b, draw, fonts, r.grade, gradeX + dx, cy, time, a);
        }
    }
}
