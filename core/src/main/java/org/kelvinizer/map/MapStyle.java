package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import org.kelvinizer.ui.Theme;

/**
 * 地图专用常量：世界尺寸、节点样式、镜头物理、HUD 和歌曲卡片尺寸、下潜动画时间，以及少数不在 {@link Theme} 里的颜色。
 * 全是普通常量；颜色是共享实例，不能修改。
 *
 * <p>Map-only constants (DESIGN §3): world geometry, node anatomy, camera physics, HUD metrics and
 * the few colours that are not global {@link Theme} tokens. Everything is a plain constant;
 * colours are shared instances that must never be mutated.
 */
final class MapStyle {
    private MapStyle() { }

    // ------------------------------------------------------------------ world (§3.2)
    /** Centre column of the world. */
    static final float CENTRE_X = 1400f;
    /** World width (x runs 0..2800). */
    static final float WORLD_W = 2800f;
    /** Height of the surface band above y = 0. */
    static final float SURFACE_H = 600f;
    /** The LAUNCH POINT buoy (tutorial). */
    static final float LAUNCH_X = 1400f, LAUNCH_Y = 140f;
    /** Charted band: header height, node pitch, base height ({@code H = 600 + 460·n}). */
    static final float HEADER_H = 340f, NODE_PITCH = 460f, BAND_BASE_H = 600f;
    /** First node below the band top. */
    static final float FIRST_NODE_DY = 420f;
    /** Vertical jitter of a node (±). */
    static final float NODE_JITTER_Y = 60f;
    /** Node side offset {@code 520·(0.8 + 0.4·hash)}. */
    static final float NODE_SIDE = 520f;
    /** Uncharted band height and its contacts. */
    static final float UNCHARTED_H = 1400f, CONTACT_FIRST_DY = 400f, CONTACT_PITCH = 400f, CONTACT_SIDE = 480f;
    /** Contacts per uncharted band. */
    static final int CONTACTS_PER_BAND = 3;
    /** Camera bounds (§3.6): view kept inside x ∈ [−200, 3000], top ≤ +600, bottom ≥ last band − 200. */
    static final float BOUND_LEFT = -200f, BOUND_RIGHT = 3000f, BOUND_TOP = 600f, BOUND_BELOW = 200f;
    /** Header numeral x. */
    static final float HEADER_X = 240f;
    /** Corridor width per depth layer (§3.5 terrain), L1..L6. */
    static final float[] CORRIDOR = {2800f, 2500f, 2200f, 1900f, 1700f, 1700f};

    // ------------------------------------------------------------------ nodes (§3.3)
    /** Thumb diameter. */
    static final float THUMB_D = 168f;
    /** Glow diameter. */
    static final float GLOW_D = 360f;
    /** Rim width. */
    static final float RIM_W = 4f;
    /** Score ring band (inner/outer radius): four full rings, SURFACE innermost (Kelvin 2026-10-17 16:40). */
    static final float ARC_R0 = 95f, ARC_R1 = 119f;
    /** Gap between two score rings. */
    static final float ARC_GAP = 1.2f;
    /** Sonar dash ring radius. */
    static final float DASH_R = 125f;
    /** NEW dot radius position and size. */
    static final float NEW_R = 92f, NEW_D = 14f;
    /** Label top below the node centre (world units). */
    static final float LABEL_DY = 132f;
    /** Max label width (screen units). */
    static final float LABEL_MAX_W = 360f;
    /** Label raster cap (drawn scaled to {@code clamp(21/zoom, 18, 26)}). */
    static final float LABEL_RASTER = 22f;
    /** Lens chip under the label. */
    static final float CHIP_W = 72f, CHIP_H = 36f, CHIP_CAP = 18f;
    /** Zoom LOD thresholds (§3.3). */
    static final float LOD_DETAIL = 0.85f, LOD_OVERVIEW = 1.8f;
    /** Gate ring diameter (§3.5). */
    static final float GATE_D = 48f;
    /** Contact ring diameter (§3.3). */
    static final float CONTACT_D = 72f;

    // ------------------------------------------------------------------ camera (§3.6)
    /** Zoom range and its soft limits. */
    static final float ZOOM_MIN = 0.7f, ZOOM_MAX = 2.4f, ZOOM_SOFT_MIN = 0.6f, ZOOM_SOFT_MAX = 2.6f;
    /** Double-tap zoom levels. */
    static final float ZOOM_NEAR = 1.0f, ZOOM_FAR = 1.9f;
    /**
     * ALL SONGS opens at this zoom (more of the grid in view), and at {@link #GRID_ZOOM_PHONE} on a
     * phone-sized screen, where the song holes would otherwise be small (Kelvin 2026-10-13 22:59:
     * 「进去以后的歌曲图标小了点」).
     */
    static final float GRID_ZOOM = 1.25f, GRID_ZOOM_PHONE = 1.0f;
    /** Fling: velocity cap, decay rate, stop speed, catch speed, sample window. */
    static final float FLING_MAX = 8000f, FLING_DECAY = 4f, FLING_STOP = 20f, CATCH_SPEED = 300f,
        FLING_WINDOW = 0.08f;
    /** Rubber band factor. */
    static final float RUBBER = 0.55f;
    /** Long-press and double-tap intervals (s). */
    static final float LONG_PRESS = 0.45f, DOUBLE_TAP = 0.30f;
    /** Idle drift (P1): start delay, amplitude, period. */
    static final float IDLE_DELAY = 8f, IDLE_AMP = 6f, IDLE_PERIOD = 14f;
    /** Keyboard pan speed (world units per second at zoom 1). */
    static final float KEY_PAN = 1200f;

    // ------------------------------------------------------------------ HUD (§3.9)
    /** Profile badge size, and the narrowest it may get beside the icon cluster. */
    static final float BADGE_W = 540f, BADGE_H = 120f, BADGE_MIN_W = 380f;
    /** Lens segments. */
    static final float LENS_SEG_W = 168f, LENS_H = 96f;
    /** Depth gauge column (from mapRight): x offsets and vertical insets. */
    static final float GAUGE_R_OUT = 80f, GAUGE_R_IN = 24f, GAUGE_TOP_INSET = 160f, GAUGE_BOTTOM_INSET = 160f;
    /** Gauge readout pill. */
    static final float READOUT_W = 140f, READOUT_H = 52f;

    // ------------------------------------------------------------------ song card (§4)
    /** Side card widths (aspect ≥ 2 / otherwise) and its padding. */
    static final float CARD_W_WIDE = 760f, CARD_W = 700f, CARD_PAD = 40f;
    /** Side card corner radius (left corners). */
    static final float CARD_RADIUS = 18f;
    /** Bottom sheet detents above SB. */
    static final float SHEET_PEEK = 300f, SHEET_FULL = 640f;
    /** Sheet drag handle. */
    static final float HANDLE_W = 80f, HANDLE_H = 8f, HANDLE_DY = 16f;
    /** Header icon buttons (64 visuals, {@code P} hits). */
    static final float HEADER_ICON = 64f;
    /** Jacket side (20:9 / 16:9 card, sheet) and its corner radius. */
    static final float JACKET_WIDE = 320f, JACKET = 300f, JACKET_RADIUS = 16f;
    /** Difficulty chip height, gap and corner radius. */
    static final float CHIP_ROW_H = 104f, CHIP_GAP = 16f, CHIP_RADIUS = 14f;
    /** Action row height, AUTO widths (20:9 / 16:9 and sheet). */
    static final float ACTION_H = 128f, AUTO_W_WIDE = 112f, AUTO_W = 104f, ACTION_GAP = 24f;
    /** Sheet DESCEND width and chip width. */
    static final float SHEET_DESCEND_W = 480f, SHEET_CHIP_W = 184f;
    /** Recent sparkline bars. */
    static final float SPARK_BAR_W = 20f, SPARK_GAP = 8f, SPARK_H = 40f;
    /** Swipe dismissal: velocity (units / s) and fraction of the size. */
    static final float DISMISS_V = 900f, DISMISS_FRACTION = 0.35f;
    /** Card exit (pow2In) and content crossfade timings (s). */
    static final float CARD_EXIT = 0.22f, SWAP_OUT = 0.12f, SWAP_IN = 0.2f, RECORD_FADE = 0.16f;
    /** Preview dwell before {@code playPreview} (s). */
    static final float PREVIEW_DWELL = 0.45f;
    /** The jacket loads after the card has rested this long on one song (hides the decode hitch). */
    static final float JACKET_DELAY = 0.28f;
    /** Jacket scanline reveal (s). */
    static final float JACKET_REVEAL = 0.42f;

    // ------------------------------------------------------------------ descent (§3.12)
    /** Descent timeline (s): camera dive start/end, startGameplay call. */
    static final float DIVE_ZOOM_START = 0.10f, DIVE_ZOOM_END = 0.65f, DIVE_START_GAMEPLAY = 0.55f;
    /** Zoom the camera dives to. */
    static final float DIVE_ZOOM = 0.2f;
    /** Upward snow streak speed at the end of the dive (virtual velocity, units / s). */
    static final float DIVE_STREAK_V = 3000f;
    /** Return (IRIS): camera start zoom and glide time; arc sweep and floater timings. */
    static final float RETURN_ZOOM = 0.4f, RETURN_GLIDE = 0.6f, ARC_SWEEP = 0.4f, FLOATER_T = 1.2f;

    // ------------------------------------------------------------------ filter and list (§3.10)
    /** Filter panel width and its drop animation (s). */
    static final float FILTER_W = 600f, FILTER_IN = 0.28f;
    /** List overlay width, row height, thumb size, chapter header height. */
    static final float LIST_W = 720f, LIST_ROW = 112f, LIST_THUMB = 88f, LIST_HEADER = 72f;
    /** Peek (long-press) ring time (s). */
    static final float PEEK_T = 0.45f;
    /** Opaque underlay under the translucent {@code panel} fill of the card and the overlays. */
    static final float UNDERLAY = 0.75f;

    // ------------------------------------------------------------------ colours (never mutate)
    /** Beyond-your-depth rim tint target (§3.3). */
    static final Color BEYOND = Theme.rgb(0xFF5CA8, 1f);
    /** Unplayed arc colour ({@code #FFFFFF @ .20}). */
    static final Color ARC_UNPLAYED = Theme.rgb(0xFFFFFF, 0.20f);
    /** Desaturation veil for nodes missing the lens chart. */
    static final Color VEIL = Theme.rgb(0x5A6278, 1f);
    /** Placeholder thumb disc. */
    static final Color THUMB_PLACEHOLDER = Theme.rgb(0x16205A, 1f);
    /** Waterline highlight. */
    static final Color WATERLINE = Theme.rgb(0xE8FBFF, 1f);

    /**
     * The design's deterministic string hash: {@code ((s.hashCode() * 0x9E3779B1) >>> 8) / 2^24}
     * (DESIGN §3.2), in 0..1.
     */
    static float hash01(String s) {
        return ((s.hashCode() * 0x9E3779B1) >>> 8) / 16777216f;
    }
}
