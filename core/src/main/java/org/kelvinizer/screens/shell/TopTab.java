package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.library.SongLibrary;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.profile.Depth;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Panel;
import org.kelvinizer.ui.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家档案的 DST 10 标签页：最上面固定着下潜曲线 DIVE PROFILE（横轴是日期和时间，可以捏合放大、拖动，点圆点看时间），
 * 下面是 DEEPEST 10：决定你深度的最深十张谱面，不满十张的用空位占着；再下面是 OVERFLOW：第 11 到 15 深的五张。
 * 每行有名次、缩略图、曲名、难度、分数、这张谱的深度（米）和深度条；本机至少两位潜水员玩过同一谱面时显示
 * {@code LOCAL #1 / 3}。只显示，不能点。
 *
 * <p>DST 10 (DESIGN §8.2; TOP 10 until Kelvin 2026-10-18 00:37): the {@link DiveChart} pinned on top
 * (only the list under it scrolls), then {@code DEEPEST 10}, the ten charts whose average is the
 * player depth, with placeholder rows for empty slots so there are always ten, then
 * {@code OVERFLOW}, the next five (ranks 11–15, placeholders too), which are not in the average.
 * Both headings read the same in both languages and carry no explanation (「这两部分的英语可以不用」).
 * Rows 96 tall: rank, thumb 72, title (UI_BOLD 22), difficulty chip, score (NUMBER 24), the chart
 * depth in metres (NUMBER 24) and a bar of the depth relative to the deepest chart (overflow rows
 * in ink2: they do not count). A {@code LOCAL #1 / 3} tag (§8.7, P1) shows when at least two local
 * divers played the chart. Display only (rows are shorter than a touch target); the list scrolls
 * with map physics, the chart takes its own pinch, drag, wheel and taps.
 */
public final class TopTab extends ProfileTab {
    /** Rows under {@code OVERFLOW}. */
    public static final int OVERFLOW_N = 5;
    private static final int SLOTS = Depth.TOP_N + OVERFLOW_N;
    private static final float ROW_H = 96f, HEAD = 56f, PROFILE_H = 240f, PROFILE_BLOCK = 40f + PROFILE_H + 16f;
    private static final CharSequence TITLE = Lang.of("DIVE PROFILE", "下潜曲线");
    private static final CharSequence HINT = Lang.of("PINCH TO ZOOM · TAP A DOT", "双指缩放 · 点圆点看时间");

    private static final class Row {
        SongMeta song;
        String title = "";
        int diff;
        int score;
        float contribution;
        final StringBuilder depthText = new StringBuilder(12);
        final StringBuilder local = new StringBuilder(16);
    }

    private final Row[] rows = new Row[SLOTS];
    private final StringBuilder[] ranks = new StringBuilder[SLOTS];
    private final List<ProfileStore.TopEntry> entries = new ArrayList<>(SLOTS);
    private final ProfileStore.LocalRank localRank = new ProfileStore.LocalRank();
    private final ThumbCache thumbs;
    private final DiveChart chart;
    private int count;
    private DiverStats stats;

    /** Creates the tab; thumbnails come from {@code thumbs}. */
    public TopTab(Main game, ShellScreen host, ThumbCache thumbs) {
        super(game, host);
        this.thumbs = thumbs;
        chart = new DiveChart(draw, fonts);
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new Row();
            ranks[i] = Fonts.appendInt(new StringBuilder(4), i + 1);
        }
    }

    @Override
    public void refresh(Profile p, DiverStats stats) {
        this.stats = stats;
        chart.setStats(stats);
        chart.reset();
        ProfileStore store = game.profiles();
        SongLibrary lib = game.library();
        store.topEntries(p, SLOTS, entries);
        count = entries.size();
        float best = count > 0 ? Math.max(0.01f, entries.get(0).depth) : 1f;
        for (int i = 0; i < count; i++) {
            ProfileStore.TopEntry e = entries.get(i);
            Row r = rows[i];
            r.song = lib.song(e.songId);
            r.title = r.song != null ? r.song.title() : e.songId;
            r.diff = e.difficulty.ordinal();
            r.score = e.score;
            r.contribution = Math.max(0f, Math.min(1f, e.depth / best));
            r.depthText.setLength(0);
            Fonts.appendGrouped(r.depthText, -Depth.meters(e.depth)); // depths read negative
            r.local.setLength(0);
            store.localRank(p, e.songId, e.difficulty, localRank);
            if (localRank.count >= 2 && localRank.rank > 0) {
                r.local.append(Lang.t("LOCAL #", "本机第 ")).append(localRank.rank).append(" / ").append(localRank.count);
            }
        }
    }

    @Override
    public void layout(float x, float y, float w, float h) {
        super.layout(x, y, w, h);
        // the dive profile stays put on top; only the list under it scrolls
        scroll.setBounds(x, y, w, Math.max(0f, h - PROFILE_BLOCK));
        float ch = PROFILE_H - 20f;
        chart.setBounds(x, y + h - 40f - ch, w, ch);
    }

    @Override
    protected void onVisibility(boolean on) {
        if (!on) chart.cancel(-1);
    }

    @Override
    public void draw(Batch b, float alpha) {
        float pa = alpha * rowIn(0);
        float hy = y + h - 22f;
        boolean hint = stats != null && stats.historyCount >= 2;
        float hw = hint ? fonts.draw(b, Fonts.Role.UI_REGULAR, 14f, HINT, x + w, hy, Align.right, Theme.INK3, pa) : 0f;
        Panel.header(b, draw, fonts, TITLE, x, hy, x + w - (hint ? hw + 16f : 0f), pa);
        chart.draw(b, pa);
        super.draw(b, alpha);
    }

    @Override
    protected float contentHeight() {
        return 2f * HEAD + SLOTS * ROW_H + 24f;
    }

    @Override
    protected void drawContent(Batch b, float top, float alpha) {
        Panel.header(b, draw, fonts, "DEEPEST 10", x, top - 32f, x + w, alpha * rowIn(0));
        for (int i = 0; i < Depth.TOP_N; i++) {
            drawRow(b, i, top - HEAD - (i + 1) * ROW_H, false, alpha * rowIn(i + 1));
        }
        float oTop = top - HEAD - Depth.TOP_N * ROW_H;
        Panel.header(b, draw, fonts, "OVERFLOW", x, oTop - 32f, x + w, alpha * rowIn(Depth.TOP_N + 1));
        for (int j = 0; j < OVERFLOW_N; j++) {
            drawRow(b, Depth.TOP_N + j, oTop - HEAD - (j + 1) * ROW_H, true, alpha * rowIn(Depth.TOP_N + 2 + j));
        }
    }

    /** Row {@code i} (0-based rank) with its bottom at {@code ry}; a placeholder when the slot is empty. */
    private void drawRow(Batch b, int i, float ry, boolean overflow, float a) {
        if (!scroll.isVisible(ry, ry + ROW_H)) return;
        float e = Math.min(1f, a);
        float dx = 24f * (1f - e);
        float cy = ry + ROW_H * 0.5f;
        float scoreR = x + w - 300f, rhoR = x + w - 150f;
        float barX = x + w - 130f, barW = 120f;
        int band = overflow ? i - Depth.TOP_N : i;
        if (band % 2 == 0) draw.roundRect(x, ry + 4f, w, ROW_H - 8f, 12f, Theme.PANEL, 0.45f * a);
        boolean empty = i >= count;
        fonts.draw(b, Fonts.Role.NUMBER, 24f, ranks[i], x + 36f + dx, cy, Align.center | Fonts.VCENTER,
            empty ? Theme.INK3 : i == 0 ? Theme.GOLD : Theme.INK2, empty ? 0.6f * a : a);
        float tx = x + 164f + dx;
        if (empty) {
            // an empty slot: the outline of a thumb, a dash for the title and an empty bar
            draw.roundRectStroke(x + 72f + dx, cy - 36f, 72f, 72f, 10f, 1.5f, Theme.INK3, 0.45f * a);
            fonts.draw(b, Fonts.Role.UI_BOLD, 22f, "—", tx, cy, Align.left | Fonts.VCENTER, Theme.INK3, 0.6f * a);
            draw.roundRect(barX + dx, cy - 4f, barW, 8f, 4f, Theme.INK3, 0.2f * a);
            return;
        }
        Row r = rows[i];
        ChartRows.thumb(b, draw, fonts, thumbs, r.song, r.title, r.diff, x + 72f + dx, cy - 36f, 72f, a);
        float maxTitle = scoreR - 280f - tx; // 9 score cells at 24 are 259 wide (DESIGN §18)
        fonts.drawFit(b, Fonts.Role.UI_BOLD, 22f, r.title, tx, cy + 16f, Align.left | Fonts.VCENTER, maxTitle,
            0.7f, Theme.INK1, a);
        float cw = ChartRows.diffChip(b, draw, fonts, r.diff, tx, cy - 20f, a);
        if (r.local.length() > 0) {
            fonts.draw(b, Fonts.Role.UI_LABEL, 14f, r.local, tx + cw + 14f, cy - 20f, Align.left | Fonts.VCENTER,
                Theme.AUTO, a);
        }
        fonts.drawDigits(b, Fonts.Role.NUMBER, 24f, r.score, 9, true, scoreR + dx, cy, Align.right | Fonts.VCENTER,
            Theme.INK1, Theme.LEADING_ZERO, a);
        // the NUMBER digit face has no "m": the unit is UI text after the number
        float uw = fonts.draw(b, Fonts.Role.UI, 18f, "m", rhoR + dx, cy - 3f, Align.right | Fonts.VCENTER,
            Theme.INK2, a);
        fonts.drawFixed(b, Fonts.Role.NUMBER, 24f, r.depthText, rhoR + dx - uw - 6f, cy,
            Align.right | Fonts.VCENTER, overflow ? Theme.INK2 : Theme.GROUND_SIG, a);
        draw.roundRect(barX + dx, cy - 4f, barW, 8f, 4f, Theme.INK3, 0.3f * a);
        draw.roundRect(barX + dx, cy - 4f, Math.max(8f, barW * r.contribution), 8f, 4f,
            overflow ? Theme.INK2 : Theme.GROUND_SIG, overflow ? 0.6f * a : a);
    }

    // ------------------------------------------------------------------ chart input
    @Override
    public boolean touchDown(float px, float py, int pointer) {
        return chart.touchDown(px, py, pointer);
    }

    @Override
    public boolean touchDragged(float px, float py, int pointer) {
        return chart.touchDragged(px, py, pointer);
    }

    @Override
    public boolean touchUp(float px, float py, int pointer) {
        return chart.touchUp(px, py, pointer);
    }

    @Override
    public void cancel(int pointer) {
        chart.cancel(pointer);
    }

    @Override
    public void mouseMoved(float px, float py) {
        chart.mouseMoved(px, py);
    }

    @Override
    public boolean scrolled(float amountY) {
        return chart.scrolled(amountY);
    }
}
