# K & K: Final UI/UX and Motion Design (DESIGN.md)

**Status:** final, binding for looks. This is the unified design. The *tactile* proposal is the backbone. It carries grafts from *cinematic* (ocean-zone metres, the sonar field, the Descent line transition, landmarks, uncharted contacts, readability rules) and from *graphic* (ARCHITECTURE alignment, the single-pass backdrop, fixed-cell digits, score decode, local ranking, zh subtitles, difficulty-safe layer names).

**What wins where:**
- Code structure follows `spec/ARCHITECTURE.md`; looks follow this file.
- Anything this design needs from FOUNDATION-owned code is listed in **Appendix A**.
- **ARCHITECTURE section references.** "ARCHITECTURE §10" is used to mean its Gameplay contract (one tap judges at most one note, neighbour-lane tolerance). Section numbers of this document are written as plain §N.
- **Wave 2 (owner decisions of 2026-10-12, as built).** Each decision is folded into the section it changes. Text it supersedes is marked **Replaced** (with what replaces it) instead of being left to contradict the new rule. §22 lists every decision and the section that now holds it. Where the code and this file disagree on a number, the code is the record and this file is corrected.

---

## 0. Principles

### 0.1 Pillars (tie-breakers, in order)

1. **Legible first.**
   - Anything read for more than about 1 s uses **Chakra Petch**: titles, names, labels, body text.
   - Glitch faces (Mokoto Mark / Mark 2 / outline, Rubik Glitch) appear only at cap ≥ 40, and only for single words, numbers or letters. The one exception is the judgement word: cap 32, because it is read by face and colour.
   - Gameplay numbers you read mid-song never glitch.
2. **Two signals.**
   - **Ground = cyan `#4FD8FF`.** Used for the ground line, ground notes, K₁ and the lower curtain panel.
   - **Sky = near-white `#F2F7FF`.** Used for the sky line, sky notes, K₂ and the upper curtain panel.
   - These hues are fixed on every screen and every depth. They are never re-tinted per layer.
3. **Sonar is the interaction language.**
   - Every touch is answered within one frame (press scale, ripple, ping).
   - A shared **heartbeat ping every 2.4 s** keeps the scene breathing. Breathing buttons, stamp rings and the prompt sync to it.
4. **Deeper = darker, sparser, stranger.**
   - One parameter, **depth in metres**, drives the palette everywhere.
   - The same metre scale is used for map layers and for the player's rating.
5. **Glitch is punctuation, not texture.** Clean glyphs carry information; glitch marks events. Every glitch has a meaning:
   - clean = signal / RESONANCE
   - static slices = ECHO
   - hollow / red jitter = LOST or damage
   - tears = crossing a boundary
6. **Free movement.**
   - The map never snaps. The camera glides only on explicit intent: tapping a node, using the ruler, recentre, edge arrows, or filter next/prev.
7. **Cheap by construction:**
   - one backdrop shader pass with no FBO
   - one shared procedural atlas
   - font pages kept small
   - zero per-frame allocation
   - gameplay ≤ 10 draw calls
8. **Adapt by anchoring.**
   - Everything is anchored to visible or safe edges.
   - Layouts switch at **aspect 1.6** (side card vs bottom sheet).
   - Gameplay switches at a **free band ≥ 104** (band layout vs middle-band layout). Since 2026-10-12 the persistent HUD sits in the screen corners at every aspect; the switch only moves the live counters into the bottom band (§5.2).

**Colour discipline:** roughly ink 70 % / white 20 % / accent ≤ 10 % per screen. Semantic colours (judgement, difficulty, grade, signals) are the only other hues.

### 0.2 Priorities and ownership

Priority tags:
- **[P0]** is required for the first playable redesign.
- **[P1]** should ship.
- **[P2]** is polish: do it last, and it may be cut without breaking anything else.

| Section | Primary owner (ARCHITECTURE §9) |
|---|---|
| §1, §2, §11, §12, §13 (AudioService), Appendix A | FOUNDATION |
| §3, §4 | MAP |
| §5 (except §5.9) | GAMEPLAY |
| §5.9 hit effects | FX |
| §6 | RESULT |
| §7–§10 | SHELL |

### 0.3 Conventions used in this document

- **Units.**
  - World units equal reference px.
  - Every screen uses `ExtendViewport(1920,1080)` with the camera centred at (960,540).
  - The map has its own camera; 1 map unit = 1 ref px at zoom 1.
- **Edge shorthands.**
  - `VL/VR/VB/VT` are the visible world edges (`Ui.left()` …).
  - `SL/SR/SB/ST` are the safe edges (`Ui.safeLeft()` …). These already include the 24-unit minimum margin and the cutout insets.
  - **Extra edge padding** (Settings, 0–48) is added to SL/SR/SB/ST.
- **`P` (touch target size)** = `max(112, Ui.dp(48))`: about 126 on a 420-dpi 1080p phone, 112 on tablets and desktop.
- **Type sizes are CAP HEIGHTS in ref units**, matching the `Fonts.get(role, refPx)` contract. Measured cap ratios (fontTools):

  | Family | Cap / em |
  |---|---|
  | Mokoto (all four faces) | 0.824 |
  | Chakra Petch | 0.700 |
  | Rubik Glitch | 0.700 |
  | Noto Sans SC (Latin cap) | 0.737 |

- **`uiScale`.**
  - All HUD and menu dimensions below are given at uiScale 1.0. Multiply by `uiScale` and scale each element about its anchor.
  - `uiScale` = Settings value (0.9–1.3), or **Auto** = 1.15 when the screen's landscape height is < 480 dp, else 1.0.
  - The gameplay playfield, notes, lines and map world are never scaled.
- **Easings** are libGDX `Interpolation` names: `pow2Out`, `pow3Out`, `pow3In`, `pow4In`, `pow4Out`, `pow5Out`, `pow3` (in-out), `exp5Out`, `exp5In`, `sine`, `linear`, `new SwingOut(s)`.
- **Durations** are in ms unless marked s.
- **Colours** are `#RRGGBB @ alpha`.

---

## 1. Visual identity

### 1.1 Viewport and adaptivity rules [P0]

| Device | Example screen | Visible world | x range | y range | Layout class | Gameplay HUD |
|---|---|---|---|---|---|---|
| 20:9 phone | 2400×1080 | 2400×1080 | −240..2160 | 0..1080 | WIDE | middle band |
| 19.5:9 phone | 2340×1080 | 2340×1080 | −210..2130 | 0..1080 | WIDE | middle band |
| 16:9 | 1920×1080 | 1920×1080 | 0..1920 | 0..1080 | WIDE | middle band |
| 16:10 | 1280×800 / 2560×1600 | 1920×1200 | 0..1920 | −60..1140 | WIDE (aspect 1.6) | middle band (free band 72) |
| 3:2 | 2160×1440 | 1920×1280 | 0..1920 | −100..1180 | TALL | bands (free band 112) |
| 4:3 tablet | 2048×1536 | 1920×1440 | 0..1920 | −180..1260 | TALL | bands (free band 192) |

"Gameplay HUD" names the layout class of §5.2. Since 2026-10-12 both classes put the persistent HUD in the screen corners; "bands" only adds the live counters in the bottom band.

**Layout classes:**
- **WIDE** is aspect ≥ 1.6. It uses a side card, and the depth gauge sits on the right of the map area.
- **TALL** is aspect < 1.6. It uses a bottom sheet. This matches `Ui.isTablet()`.

**Global rules:**
- **Viewport.** `renderUnderCutout = true` and `sensorLandscape` on Android. Backgrounds fill the full visible world; content stays inside the safe edges.
- **Touch targets.** Minimum touch target is `P`. A visual element may be smaller, but its hit rect is at least `P`×`P`.
- **Minimum text (cap height):**

  | Kind of text | Minimum cap |
  |---|---|
  | Essential | `max(20·uiScale, Ui.dp(7.5))` (≈ 23 on phones) |
  | Secondary | `18·uiScale` |
  | Decorative micro text (never the only carrier of information) | `16·uiScale` |
  | Over the moving playfield | 20 |
  | Map labels at any zoom | 18 |

- **Actions fire on release** inside the hit rect, with slop `Ui.dp(12)`. Long-press is 450 ms; double-tap interval is 300 ms. Only gameplay lanes act on touchDown.
- **BACK / Esc** go to each screen's `onBack()`. In gameplay this pauses instantly.
- **Map labels and all map UI text** are drawn in a screen-space text pass after the world pass, at projected positions, so they stay crisp at any zoom.
- **Resize.** Every layout recomputes in `onResize`. Desktop windows of any size are valid; they map onto the nearest class.

### 1.2 Palette [P0]

**Global tokens**

| Token | Hex | Use |
|---|---|---|
| `ink1` | `#EAF2FF` | primary text |
| `ink2` | `#A9B6D6` | secondary text, artist names |
| `ink3` | `#6C7899` | tertiary text, disabled, leading zeros (drawn at α 0.40) |
| `panel` | `#0A1033 @ 0.78` | cards, sheets, HUD plates |
| `panelHi` | `#16205A @ 0.88` | pressed or selected panel, chip backgrounds |
| `stroke` | `#FFFFFF @ 0.14` | hairlines, panel borders |
| `scrim` | `#02040F @ 0.65` | modal dim (pause uses 0.70) |
| `groundSig` | `#4FD8FF` | ground signal (see §0.1) |
| `skySig` | `#F2F7FF` | sky signal |
| `seam` | `#0A1033` | curtain seam line |
| `glitchC` / `glitchM` | `#00E5FF` / `#FF2E88` | RGB-split copies (≤ 0.35 α on text), tear bars (≤ 0.25 α) |
| `sync` | `#FFD36B` | the gold sync square in the middle of a pending DROP / STREAM head that needs a second finger (§5.8; keeps the existing gold identity). **Replaced 2026-10-12:** the simultaneous-note outline and connectors. |
| `syncRim` | `#9A6A1E` | 2 px darker-gold rim of the sync square, so it reads on pearl and cyan heads (`Theme.SYNC_RIM`) |
| `gold` | `#FFD27A` | records, NEW BEST, level, the YOU marker |
| `auto` | `#9CF5B0` | autoplay tint |
| `btnTop` / `btnBot` | `#6FE3FF` / `#36B9E8` | primary button gradient; label `#04142B` |

Gameplay note tokens live with the notes: `mine` `#FF3344`, `mineCore` `#2A0610` and `tide` `#8CFFD9` (§5.15); `mineLost` `#FF2A3D`, the red LOST word of a touched mine (§5.6); `skyTide` = 0.7·`skySig` + 0.3·`tide` (≈ `#D3F9F4`) and the sky TIDE head gradient `skyTideHeadDim` `#808EA0` → `skyTideHeadBright` `#ECF4FC` (§21.6); the ground T4 wave `tideWave` `#1FBF98`, `tideWaveCrest` `#C8FFF0`, `tideWaveBase` `#EAFFF8` (§5.15). In code they are the `Theme` constants of the same names in capitals (`MINE_LOST`, `SKY_TIDE`, `SKY_TIDE_HEAD_DIM`, …).

**Judgements.** Each judgement has its own colour *and* its own glyph face, so it reads without colour.

| Judgement | Window | Colour | Face |
|---|---|---|---|
| RESONANCE | ≤ 66.67 ms | `#66F0FF` | Mokoto Glitch clean, with G1 shimmer |
| ECHO | ≤ 133.33 ms | `#B49CFF` | Mokoto Glitch **Mark** (static slices, the "echo") |
| LOST | miss | `#FF5C7A` | Mokoto Glitch **Mark outline** (hollow), α 0.85 |
| EARLY / LATE | — | `#6FA8FF` / `#FFB067` | Chakra Petch Bold |

**Difficulties.** Names are unchanged. Source colours come from `DifficultyPanel` and are lifted for contrast on navy.

| Difficulty | Chip fill | Chip text | Border / extra | Accent when selected |
|---|---|---|---|---|
| SURFACE | `#F2F7FF` | `#0B1030` | — | `#F2F7FF` |
| SHALLOW | `#38B6FF` | `#04142B` | — | `#38B6FF` |
| DEEP | `#2F6BFF` | `#FFFFFF` | 2 px `#004AAD` | `#2F6BFF` |
| ABYSS | `#000000` | `#FFFFFF` | 3 px `#FF2E88`, plus a 2 px `#00E5FF` copy offset (+2,0); idle G2 every 4–7 s [P1] | `#FF2E88` |

**Depth layers (chapters).** Real ocean zones. Names never reuse difficulty names.

| # | Layer (EN / ZH) | Metres | bgTop | bgMid | bgBottom | Accent | Mote | Shaft colour / strength | Caustic | Vignette | Grain | Idle glitch interval |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| L1 | SHORELINE / 浅滩 | 0–200 | `#1C5A99` | `#134079` | `#0C2A5A` | `#7FE7FF` | `#D6FBFF` | `#D8FBFF` / 1.0 | 1.0 | 0.30 | 0.04 | 7.0 s |
| L2 | TWILIGHT / 暮光层 | 200–1,000 | `#143A78` | `#0F275E` | `#0A1A45` | `#6FA8FF` | `#BCD6FF` | `#9AD8FF` / 0.4 | 0 | 0.38 | 0.05 | 5.5 s |
| L3 | MIDNIGHT / 午夜层 | 1,000–4,000 | `#0D1C4C` | `#0A1338` | `#070C27` | `#8F7BFF` | `#5CF2FF` | — / 0 | 0 | 0.48 | 0.06 | 4.0 s |
| L4 | BLACKWATER / 黑水层 | 4,000–6,000 | `#0B1032` | `#080B24` | `#050717` | `#C77DFF` | `#FF8AE2` | — / 0 | 0 | 0.56 | 0.07 | 3.0 s |
| L5 | TRENCH / 海沟 | 6,000–10,935 | `#08091E` | `#050613` | `#03030A` | `#FF5CA8` | `#FF5CA8` | — / 0 | 0 | 0.64 | 0.08 | 2.2 s |
| L6 | BELOW / 未知 | ≥ 10,935 | `#0C0412` | `#06020A` | `#000000` | `#FF2E88` + `#00E5FF` | magenta / cyan | — / 0 | 0 | 0.70 | 0.10 | 1.6 s |

- **Surface band** (map only, above 0 m): top `#9FD8F0` → bottom `#2A8FB8`.
- **Contrast.** `ink1` on L1 bgMid is about 10:1, and contrast only improves deeper. The existing art averages `#0A0E34`–`#1A2147`, so the overlays sit naturally between L2 and L3.

**Grade colours** are defined in §12.

### 1.3 Typography [P0]

**Fonts shipped:**
- the five existing glitch TTFs
- **Chakra Petch** 400 / 600 / 700 (OFL)
- **Noto Sans SC Medium** GB2312 subset (OFL), incremental

**Role mapping** follows the ARCHITECTURE `Fonts.Role` enum, plus one requested role, `UI_LABEL` (Appendix A).

| Role | Font |
|---|---|
| `NUMBER` | Mokoto Glitch (clean) |
| `DISPLAY_SOFT` | Mokoto Glitch Mark |
| `DISPLAY` | Mokoto Glitch Mark 2 |
| `DISPLAY_OUTLINE` | Mokoto Glitch Mark outline |
| `RUBIK` | Rubik Glitch |
| `UI` | Chakra SemiBold |
| `UI_BOLD` | Chakra Bold |
| `UI_REGULAR` | Chakra Regular |
| `UI_LABEL` | Chakra SemiBold, `spaceX = round(0.10 × px)` |
| `CJK` | Noto Sans SC Medium |

**The zero-cost glitch.** Mokoto Glitch, Mark and Mark 2 have **identical advance widths** for every letter, digit and `%+-.,` (verified with fontTools; for example '0' = 979, '1' = 432, 'K' = 867 units). At the same cap height, swapping between them needs no relayout. The outline face is 12–35 % wider and is never swapped in.

**Type scale** (cap heights in ref units, at uiScale 1):

| Use | Role (rest face) | Cap | Glyph set | Notes |
|---|---|---|---|---|
| Logo "K & K" | DISPLAY_SOFT | 140 | LOGO `K& ` | swaps NUMBER ↔ DISPLAY. Left K `groundSig`, "&" `ink2 @ .7`, right K `skySig`. |
| Grade stamp letter | DISPLAY | 200 (PURE: 104) | GRADE `PURESABCD+` | swaps. "+" drawn at 55 % scale as a superscript. |
| Countdown | NUMBER | 132 | DIGITS | |
| Combo | NUMBER | 96 | DIGITS | swaps DISPLAY_SOFT / DISPLAY at milestones |
| Result score | NUMBER | 80 (wide, aspect < 2.0) / 88 (aspect ≥ 2.0) / fit between jacket and stamp, ≤ 112 (stacked; ≈ 108 at 4:3) | DIGITS | fixed cells; swaps at score-land |
| HUD score | NUMBER | 0.4 of the top band (top edge to the default sky line; top-left corner, §5.3) since 2026-10-20 07:01 (§24.33): 43.2 at 16:9 and 20:9, 48 at 16:10, 57.6 at 4:3. **Replaced:** 0.7 of the band from 06:27 (§24.32: 75.6, 84, 100.8); 40 at every aspect from 05:56 (§24.31; also 05:00–05:31, §24.30); 32 (80 % of 40) from 2026-10-12; 40 before that (band layout 44). | DIGITS | fixed cells; never glitches |
| ~~HUD accuracy~~ | NUMBER | **Removed 2026-10-20 05:56** (§24.31; first at 05:00, back 05:31–05:56, §24.30). Was `max(19.2·uiScale, 20)` (80 % of 24, never below the playfield minimum 20 of §1.1); 24 before 2026-10-12. | DIGITS | |
| UI numbers | NUMBER | 20 / 24 / 26 / 30 / 36 / 56 | DIGITS | depth, stats, counts, EXP |
| Judgement words | NUMBER / DISPLAY_SOFT / DISPLAY_OUTLINE | 32 | WORDS | one face per judgement (§1.2) |
| DESCEND label | DISPLAY | 40 | WORDS | |
| Screen titles (PROFILE, SETTINGS) | DISPLAY_SOFT | 44 | WORDS | the `PAUSED` title left with the pause panel (§5.12, variant B) |
| Alerts (LEVEL UP, NEW BEST, RANK UP, DIVE, toast layer name) | RUBIK | 30–56 | `A–Z 0–9 +!·-` | at most 1 on screen |
| Chapter name (map world) | RUBIK | 72 (overview 108) | FULL | G1 |
| Chapter numeral (map world) | DISPLAY_OUTLINE | 200 @ α .25 | DIGITS | |
| Song title, card | UI_BOLD | 38, `drawFit` min 0.7, then ellipsis | FULL + CJK | no marquee, no glitch font |
| Song title, result | UI_BOLD | 34 | FULL + CJK | |
| Song title, gameplay intro card | UI_BOLD | 46 | FULL + CJK | |
| Map node label | UI | `clamp(21/zoom, 18, 26)` | FULL + CJK | 2 px shadow `#02040F @ .6` at (+2,−2) |
| Player name | UI_BOLD | 22 (badge) / 36 (profile) | FULL + CJK | |
| Section header | UI_BOLD | 28 | | |
| Buttons | UI_BOLD | 26 (large primary 30) | | |
| Labels (caps, tracked) | UI_LABEL | 20 (secondary 18, micro 16) | | |
| Body | UI_REGULAR | 22 | | |
| Secondary / meta | UI | 20, `ink2` | | |
| Tutorial instructions | UI | 30 (headings UI_BOLD 40) | | |
| CJK fallback | CJK | same cap as the calling role | incremental | `Fonts` routes any char > U+024F automatically |

**Glyph sets** (requested in Appendix A). Display-size fonts are generated with tiny charsets, so big atlases stay small:

| Set | Characters |
|---|---|
| DIGITS | `0123456789,.:%+-/` plus space |
| WORDS | A–Z, 0–9, space, `·-+%` |
| GRADE | `PURESABCD+` |
| LOGO | `K& ` |

GRADE, LOGO and DIGITS sets may exceed the 160 px generation cap, up to 256 px, because each is ≤ 20 glyphs.

**Fixed-cell digits (`Fonts.drawDigits`)** [P0]:
- Each glyph is drawn straight from `BitmapFont.Glyph`, centred by its ink in a fixed cell. There is no GlyphLayout and no allocation.

  | Font | Cell width |
  |---|---|
  | Mokoto digit | 1.10 × cap |
  | Mokoto `,` and `.` | 0.45 × cap |
  | Mokoto `%` | 1.16 × cap |
  | Chakra digit | 0.92 × cap |

- **Scores** always show 8 digits with grouping (`09,812,345`). Leading zeros are drawn in `ink3 @ .40` until the value reaches them.
- **Depth** has no leading zeros (`2,560 m`). **Rating** is floored to 2 decimals. **Accuracy** is truncated to 2 decimals (`99.12%`).

**Widths at 8 digits + 2 commas:**

| Cap | Width |
|---|---|
| 24 | 233 |
| 30 | 291 |
| 40 | 388 |
| 44 | 427 |
| 80 | 776 |
| 88 | 854 |
| 104 | 1009 |

**Bilingual** [P1]. With Language = 中文, a display word gets a CJK subtitle at 0.5× its cap (minimum 18): judgement labels on the result screen (English only since 2026-10-18 16:45, §24.15), layer names, rank titles and toasts. Examples: `RESONANCE / 共鸣`, `TWILIGHT / 暮光层`. Gameplay popups stay EN only.

**Build rules:**
- Glyphs are baked white and tinted at draw time.
- No `getMaxFittingFont`. `drawFit` measures once and scales down, never regenerates.
- All fonts are generated during loading, never in a transition callback.
- Gameplay HUD fonts share one packer page when supported (Appendix A).

**Chart constants** (as built, Kelvin 2026-10-13 23:11, 23:12: 「显示定数的时候这样显示：.0~.3: "-"; .4~.6: 普通。.7~.9: "+".」「加和减都是superscript」). Players see a constant as its whole number with a superscript mark from the tenths digit: .0–.3 `-`, .4–.6 none, .7–.9 `+` (16.2 → 16⁻, 16.4 → 16, 16.8 → 16⁺). It is rounded to one decimal first (16.96 → 17⁻); text that is not a number shows as written, with no mark. The mark is the number's font at 0.6 of its cap height, its centre 0.3 cap above the number's centre and 0.06 cap after it (`Theme.CONST_MARK_*`; the rule is `library/ConstantLabel`). This covers the map's lens chips (§3.3), the song card chips (§4.5), the DIVE info (§3.12), the gameplay chip (§5) and the result chip (§6). The filter's constant range (§3.10), sorting, rating and EXP keep the exact value, and chart files and the chart editor keep writing e.g. `16.2`.

### 1.4 Glitch treatment [P0]

All glitch goes through `GlitchText`. The jitter seed is `hash(floor(time × 24))`, so it changes 24 times a second. Global intensity `I = settings.glitchIntensity` (0..1, default 0.6).

| Level | What it does | Used on | Cost |
|---|---|---|---|
| **G0** clean | nothing | body, lists, settings values, live HUD score, all notes and lines before judgement | 1× |
| **G1** shimmer | Two extra draws: `glitchC` at (−a, 0) and `glitchM` at (+a, 0), additive α .35, `a = 2.5·I`. Every 2–4 s the copies jitter ±1 px for 2 frames. | logo, chapter names, grade stamp at rest, combo, DESCEND label, RESONANCE word, PURE | 3× glyph quads |
| **G2** burst | 60–140 ms total:<br>• **font swap** NUMBER → DISPLAY → DISPLAY_SOFT → rest face, one face per 2 frames (only roles that have swap sets)<br>• split `a = 6·I`<br>• 1–3 **sub-UV slices**: bands 12–30 % of cap tall, shifted x ±(4..24)·I<br>• 1–3 **tear bars** over the text box: `glitchC`/`glitchM` rects 4–14 tall, α .25, x jitter ±24 | events: score lands, stamp impact, combo every 100, node select, chip change, title decode end, countdown digit appear | a few quads |
| **G2-red** (LOST variant) | as G2, but the split uses `#FF5C7A` only (d = 4·I) plus 2 frames of ±4 px jitter; no font swap | LOST word, combo break (combo ≥ 20), grade D landing | a few quads |
| **G3** tear (screen level, ≤ 200 ms) | 2–4 full-width tear bars 6–30 tall at α .08–.15, plus HUD-layer offset jitter ±4 px | layer crossing on the map, level-up, rank-up, transitions. **Never during play.** | a few quads |

**Sub-UV slices.** For each glyph quad intersecting a band, call `setRegion(u, vBandTop, u2, vBandBottom)` on one reused `TextureRegion` and shift the geometry. There are no scissors, no FBO and no flushes, and it works on dynamic text.

**Rules:**
- **Clean time.** A glitched word is in its clean state for at least 85 % of its on-screen time.
- **Short reads.** Nothing the player must read in under 200 ms ever bursts.
- **Limits per screen.** At most 2 ambient G1 elements, and at most 2 concurrent G2 bursts, with desynchronised timers.
- **Ambient G2 on menus.** One "hero" element per screen (logo, chapter name nearest the focus point, result stamp) bursts every *idle-glitch interval* × random(0.8, 1.2), using the interval from the current layer (§1.2).
- **I = 0.** No animated glitch at all. The static faces (Mark / Mark 2 / outline) remain, because they are the owner's choice. G2 becomes a 120 ms alpha dip 1 → 0.6 → 1, and G3 is skipped.
- **Photosensitivity:**
  - no full-screen flash above α .15
  - at most 3 luminance jumps of more than 20 % per second
  - **Reduce flashing** caps every flash at α .10 and disables tear bars, dropout and flicker
- **Gameplay.** Notes, lines and the HUD score are never glitched before judgement. After a miss, a LOST note may jitter for 2 frames (§5.8).

### 1.5 Motion language [P0]

**Tokens**

| Token | Duration | Easing | Used for |
|---|---|---|---|
| micro | 90 | `pow2Out` | press-in, colour flash |
| short | 160 | `pow3Out` | toggles, chip morph, tooltips |
| medium | 280 | `pow3Out` | cards, sheets, panels entering |
| exit | 0.7 × enter (typically 200) | `pow2In` | anything leaving (exits are always faster) |
| long | 480 | `exp5Out` | camera glides, screen-level moves |
| hero | 800–1500 | `exp5Out` / `pow5Out` | count-ups, the dive |
| pop | 320 | `new SwingOut(1.8f)` | badges, stamps, node select |
| ping | 1200 | radius `pow3Out`, α (1−t)² | sonar rings |
| count | 600–900 | `pow3Out` / `pow4Out` | numbers |
| stagger | 40 (list items) / 80 (buttons), at most 8 items | — | groups entering |
| ambient | 6–17 s | `sine`, incommensurate periods | sway, drift, breathing |

**Springs** use semi-implicit Euler with substeps ≤ 1/120 s: `a = −ω²(x − target) − 2ζω·v`.

| Preset | ω (rad/s) | ζ | Used for |
|---|---|---|---|
| snappy | 30 | 0.75 | press scale, toggle knob |
| bouncy | 18 | 0.42 | node pop, stamp settle, release overshoot |
| soft | 9 | 0.90 | parallax, gauge follow |
| jelly | 12 | 0.30 | node lag-wobble, Welcome K lean |
| camera | 12 | 1.00 | overscroll spring-back, zoom spring-back |
| sheet | 16 | 0.85 | sheet detents, card overshoot |

**Interaction primitives.** Every pressable control has these:

| Event | Response |
|---|---|
| **Down** | scale → 0.94 (snappy); brightness +12 %; ripple from the touch point (`ring_thin`, radius 0 → `min(1.2·max(w,h), max(w,h)/2 + 48)` over 380 ms `pow2Out`, stroke 3 → 1 px, α .30 → 0, plus a fill disc α .08 → 0, no scissor); optional light haptic. The ripple spills at most 48 px beyond the control. |
| **Release inside** | spring to 1.0 with bouncy overshoot (≈ 1.04); the action fires |
| **Drag out beyond slop** | press cancels; springs back with a small wobble |
| **Hover** (desktop) | lift 2 px, brightness +8 % |
| **Keyboard focus** | 2 px `ink1` ring, α .5 ↔ 1 at 1 Hz |
| **Disabled** | α .40; a tap triggers a "no" shake: ±8 px, 3 cycles, 240 ms |
| **Panel life** [P2] | a 120 px highlight segment travels around each card's border once per 6 s, α .25 |

- **Haptics** [P2]:
  - Calls: `Gdx.input.vibrate(Input.VibrationType.LIGHT / MEDIUM / HEAVY)`, guarded by `isPeripheralAvailable(Peripheral.HapticFeedback)`. Needs the `VIBRATE` permission.
  - When each fires: LIGHT for presses, chips and slider detents every 10 %; MEDIUM for DESCEND and stamp impact; HEAVY for level-up.
  - Default "Menus only".
- **Reduce motion** disables:
  - camera kicks and shakes
  - parallax, critters, node jelly lag, idle bob
  - snow streaking
  - G3 tears
  
  Slides become 160 ms fades.

### 1.6 Procedural UI kit [P0]

All of these are drawn from the shared atlas (§2.1) with ShapeDrawer and NinePatch. There are no hand-drawn assets.

| Component | Spec |
|---|---|
| Panel | `panel_9p` (48², radius 18) in `panel`; 1 px `stroke` border; 1 px top highlight `#FFFFFF @ .07` |
| Primary button | height `max(P, 112)`, radius 18; vertical gradient `btnTop → btnBot`; label UI_BOLD 26 `#04142B`; soft glow `soft_dot` at α .25 |
| Secondary button | 2 px `ink1 @ .6` outline, radius 18; label `ink1` |
| Destructive button | 2 px `#FF5C7A` outline; **hold to confirm** (a ring fills over 1.5 s; release early drains it in 150 ms) |
| Icon button | 88 visual circle (`panel` fill, 2 px `ink1 @ .7` stroke); hit `P` |
| Chip | radius 12; small chips 40 tall; difficulty chips 104 tall |
| Toggle | 96×52 pill; 44 knob (snappy); ON fill `groundSig` |
| Slider | track 6 px `ink3 @ .5`; fill `groundSig`; 44 knob that scales to 1.3 while grabbed; value bubble above (NUMBER 24); light haptic every 10 % |
| Segmented control | pill container; the selection pill slides with snappy spring |
| Text entry | a styled field that opens `Gdx.input.getTextInput` on mobile, or a scene2d TextField on desktop |
| Toast | `panel` pill, UI 22; enters top-centre at `ST − 140`; 280 ms in, holds 1.6 s, 200 ms out |
| Icons | ShapeDrawer on a 64-unit grid with 5 px strokes: gear, funnel, ?, crosshair, chevrons, pause bars, retry arc, pencil, dice, close ×, list, speaker, person, down-triangle ▼ (used for "deeper"; it is not a font glyph) |

---

## 2. Background system (`Backdrop`)

### 2.1 Shared generated atlas [P0]

`Draw` builds one 1024² atlas at boot in about 20 ms. Regions are Pixmaps computed per pixel and packed with `PixmapPacker → generateTextureAtlas(Linear, Linear, false)`. The ShapeDrawer white region, backdrop sprites, notes, HUD shapes and **particles** all come from this one texture.

| Region | Size | Content |
|---|---|---|
| `white` | 4² | solid |
| `soft_dot` | 64² | α = (1 − r)² (replaces `particle.png`) |
| `ring_soft` | 128² | α = exp(−((r − 0.42) / 0.07)²) |
| `ring_thin` | 256² | 2 px anti-aliased ring at r = 0.48 |
| `streak` | 64×16 | capsule with a horizontal α ramp (bright head) |
| `glow_v` | 4×128 | vertical gaussian (line glows, lane columns) |
| `shaft` | 64×256 | horizontal gaussian × vertical fade (LOW path) |
| `note_9p` | 96×32 | radius 8; vertical gradient α .92 → .55; 3 px white leading edge (bottom); 2 px rim `#0B1030 @ .6`. NinePatch splits 12/12/8/8. |
| `note_glow` | 128×48 | soft capsule glow |
| `hold_edge` | 16×64 | edge gradient for hold ribbons |
| `panel_9p` / `panel_stroke_9p` | 48² | radius 18, fill / 1 px stroke |
| `chip_9p`, `pill_9p` | 40² / 64×32 | radius 12 / 16 |
| `vignette` | 256² | α = smoothstep(0.45, 1.0, r) (LOW path) |

**Separate texture:** `noise` 128² RGBA, wrap REPEAT, Linear.
- R and G are two octaves of value noise (shafts).
- B is caustic noise.
- A is a blue-noise-like grain value.

### 2.2 Layers, back to front [P0 unless tagged]

| # | Layer | Technique | Motion | Depth behaviour |
|---|---|---|---|---|
| 0 | Water | **one** fragment shader on a full-screen quad, no FBO (§2.4) | shaft drift 0.012 uv/s; caustic scroll | palette and scalars from §2.3 |
| 1 | Image hook (optional) | cover-crop sprite tinted toward the layer bgMid (§2.8) | Ken Burns 1.00 → 1.06 over 20 s (ping-pong); 3 % touch parallax on menus | constant |
| 2 | Particulates | struct-of-arrays pool (preallocated `float[]`); 3 parallax planes (0.3 / 0.6 / 1.0 of camera pan) | bubbles: `ring_thin` 6–14 px rising 40–90 /s (L1–L2). Snow: `soft_dot` 2–6 px sinking 6–14 /s, swaying ±8 px on 4–9 s sines (L3+). L6: 6 px squares that blink. | counts in §2.3 (Low quality = × 0.5). Streak length × (1 + \|vy\|·0.004), capped 6×, oriented along velocity, where vy = camera or dive vertical speed. [P2] flakes glint +0.4 α where the shaft term is bright. |
| 3 | Bio-motes [P1] | additive `soft_dot` 24–64 px in the layer mote colour | wander on summed sines; α pulse .2–.6 | from L2. Flare to 1.0 when `sonar.waveAt() > .5` (decay 600 ms). Scatter from a finger within 220 (impulse + spring home). |
| 4 | Sonar rings | `SonarField` (§2.5) | heartbeat 2.4 s; touch pings | ring colour = layer accent |
| 5 | Tear bars [P1] | quads | at the idle-glitch interval, menus only | frequency rises with depth |

### 2.3 Depth blending and per-layer scalars [P0]

Depth `m` (metres) selects layer *i* with band fraction `f = (m − top_i) / (bottom_i − top_i)`:
- For `f < 0.75` the layer-*i* values hold.
- For `f ≥ 0.75`, all values lerp to layer *i+1* by `smoothstep((f − 0.75) / 0.25)` (the thermocline).

Colours lerp in linear RGB. `Backdrop.setDepth(m)` animates toward the target with τ = 0.6 s; `setDepthImmediate` jumps.

| Layer | Particulates (High) | Bio-motes |
|---|---|---|
| L1 | 70 bubbles | 0 |
| L2 | 80 (bubbles → snow over the band) | 4 |
| L3 | 100 snow | 12 |
| L4 | 110 snow | 18 |
| L5 | 70 snow | 24 |
| L6 | 40 pixels | 16 |

Shaft, caustic, vignette, grain and idle-glitch values are in the §1.2 table.

### 2.4 Shader [P0]

GLSL ES 2.0, `mediump`, compiled once at boot in two variants. **`CAUSTICS` is used only on Welcome and on the map while camera depth < 300 m.**

```glsl
#ifdef GL_ES
precision mediump float;
#endif
varying vec2 v_uv;       // 0..1 across the visible world
varying vec2 v_world;    // world coords + parallax offset
uniform vec3  u_top, u_mid, u_bot, u_shaftCol;
uniform float u_time, u_shaft, u_vig, u_grain, u_pulse, u_bright;
uniform vec2  u_fragToNoise;              // 1/128
uniform sampler2D u_noise;                // 128² RGBA, REPEAT, LINEAR
#ifdef CAUSTICS
uniform float u_caustic;
#endif
void main(){
  float y = v_uv.y;
  vec3 c = mix(mix(u_bot, u_mid, clamp(y*2.0, 0.0, 1.0)), u_top, clamp(y*2.0-1.0, 0.0, 1.0));
  vec2 n = texture2D(u_noise, vec2(v_world.x*0.0011 + (1.0-y)*0.35, u_time*0.012)).rg;
  float s = n.r*0.65 + n.g*0.35;
  c += u_shaftCol * (s*s*s) * u_shaft * smoothstep(0.15, 1.0, y);
#ifdef CAUSTICS
  float a = texture2D(u_noise, v_world*0.0016 + u_time*vec2(0.020, 0.010)).b;
  float b = texture2D(u_noise, v_world*vec2(0.0013, 0.0015) - u_time*vec2(0.015, 0.012)).b;
  c += u_shaftCol * u_caustic * pow(1.0 - abs(a-b), 8.0) * y * 0.30;
#else
  c += (texture2D(u_noise, gl_FragCoord.xy*u_fragToNoise).a - 0.5) * u_grain;   // dither + grain
#endif
  vec2 d = v_uv - 0.5;
  c *= (1.0 - u_vig*dot(d,d)*2.0) * u_bright * (1.0 + u_pulse*0.03);
  gl_FragColor = vec4(c, 1.0);
}
```

- **Fetches per pixel:** 2 by default, 3 with caustics.
- **LOW path (no shader).**
  - Gradient: two quads with vertex colours (ShapeDrawer `filledRectangle` with 4 colours).
  - Shafts: 4 skewed `shaft` quads (L1–L2), additive, swaying ±6° on 9–14 s sines.
  - Vignette: the `vignette` sprite.
  - No grain or caustics.
- **Automatic fallback to LOW:**
  - if the shader fails to compile, or
  - if the average frame time exceeds 18 ms over the first 120 frames the backdrop renders on a screen.
  
  The fallback persists `backgroundDetail = LOW` (device setting) and shows the toast "BACKGROUND SET TO LOW FOR SMOOTHER PLAY".

### 2.5 SonarField (shared motif) [P0]

One pooled instance, owned by `Backdrop` (Appendix A). It never allocates.

```java
public final class SonarField {
  public static final int MAX = 8;
  public void ping(float x, float y, float maxR, float durSec, Color color, float alpha, int rings); // rings 1..3: ring k starts k*0.07 s later at 0.85^k radius
  public float waveAt(float x, float y);   // 0..1 = max over rings of clamp(1 - |dist - r| / 40, 0, 1) * ringAlphaFactor
  public void setHeartbeat(float x, float y, float periodSec, float maxR, float alpha, Color c); // default 2.4 s, 900, 0.18
  public void clearHeartbeat();
  public void update(float dt);  public void draw(Batch b);
}
```

- **Drawing.** Rings with r ≤ 160 use the `ring_thin` sprite. Larger rings use a ShapeDrawer circle, width 2, `segments = clamp(r/10, 32, 96)`.
- **Touch ping.** `Backdrop.touch(x,y)` = `ping(x, y, 140, 0.38, accent, 0.35, 1)`.
- **Answering the ping.** Map nodes, bio-motes, uncharted contacts and result elements query `waveAt` to "answer" a passing ping. Nodes brighten +.25 for 300 ms when `waveAt > .5`.
- **Heartbeat sources:**

  | Screen | Source |
  |---|---|
  | Welcome | logo |
  | Map | selected node, else the K&K marker |
  | Result | grade stamp |
  | Profile | avatar |
  | Gameplay | none (gameplay uses bar pings instead, §2.7) |

### 2.6 Depth source per screen [P0]

| Screen | Depth fed to `setDepth` | Extras |
|---|---|---|
| Welcome | the player's depth (new players: 0 m) | caustics if < 300 m |
| Map | metres at the camera centre (§3.2 mapping) | parallax 0.5 of camera; streaks from camera vy |
| Gameplay | song layer `top + (bottom − top) × f_diff`, with f = Surface .10, Shallow .35, Deep .60, Abyss .85 | §2.7 |
| Result | same as the gameplay depth | menu intensity |
| Profile, Settings, Calibration | the player's depth (shafts × 0.6) | pings on tap only |
| FirstRun | 0 m | |

The game visibly darkens as the player improves.

### 2.7 Gameplay variant [P0 unless tagged]

- **Base.** Same shader without caustics; `u_bright = 0.70`; shafts × 0.35; grain .03; vignette fixed at .55. Then the **background dim** scrim (`#02040F @ backgroundDim`, default 0.35).
- **Jacket wash.**
  - At load, downscale the jacket to a 16×16 Pixmap and make it a Texture (Linear). It is owned by GameplayScreen and recreated per song.
  - Draw it full-screen, cover-cropped, at α .12. That gives a free "blurred jacket aura".
- **Beat pulse.** `u_pulse` goes 1 → 0 over 200 ms on each beat (chart BPM).
- **Bar ping.** Every 4 beats, `ping(960, 540, 700, 1.2, accent, 0.06, 1)`.
- **Combo life** [P1]. Bio-motes: 0 while combo < 10, ramping linearly to 24 at combo ≥ 100. On a combo break they scatter outward and dim over 500 ms.
- **Snow.** 24 flakes on the far plane only, α ≤ .20.
- **Abyss pressure** [P1]. Abyss difficulty only: corners pulse `#FF2E88 @ .06` on each bar downbeat, decaying over 400 ms.
- **No** shafts above 0.35, **no** caustics, **no** glitch.

### 2.8 Image hooks [P1]

Every hook is optional. If the file is missing, the screen is procedural only. All hooks are cover-cropped (never stretched) and tinted toward the layer bgMid.

| File | Screen | α (at Art opacity 100 %) |
|---|---|---|
| `welcome.png` | Welcome | .35 |
| `Blue.png` | Map (parallax 0.15 of camera) | .22 |
| `Blue.png` | Profile, Settings | .15 |
| `ResultPage.png` | Result | .20 |
| song `bg.png`, else `ChartVisualizer.png` | Gameplay | .15 |

Settings provide **Background art** On/Off and **Art opacity** 0–100 %, which multiplies the α values above.

### 2.9 Cost

| Screen | Backdrop quads | Draw calls | Shader |
|---|---|---|---|
| Menus | ≤ 140 | ≤ 3 (shader, overlay, atlas) | ≤ 3 fetches/px |
| Gameplay | ≤ 40 | ≤ 3 (shader, overlay + jacket wash, atlas) | 2 fetches/px |

No FBO is used anywhere in the backdrop, so it survives GL context loss.

---

## 3. Map song select: "Dive Chart"

### 3.1 Data [P0]

The manifest is `assets/Charts/index.json`, written by FOUNDATION. Refinements are in Appendix A. Song id = **folder name**.

| Chapter | Name | Metres | Songs |
|---|---|---|---|
| c1 | SHORELINE | 0–200 | Song 1, Song 2, Song 3 |
| c2 | TWILIGHT | 200–1,000 | Song 4, Song 5, Song 6 |
| c3 | MIDNIGHT | 1,000–4,000 | ICS4U, marsh mally |
| c4 | BLACKWATER | 4,000–6,000 | `uncharted: true`, no songs |
| c5 | TRENCH | 6,000–10,935 | `uncharted: true`, no songs |
| tutorial | Introduction | — | the LAUNCH POINT buoy |

- **Songs per layer:** target 4, minimum 3, maximum 6.
- **Optional per-song fields:** `previewStartMs`, `map: {nudgeX, nudgeY}`.
- **Unlisted folders** go into **Singles** (§16): new test songs appear there without editing the manifest.

### 3.2 World layout [P0]

**Coordinates.**
- x runs 0..2800 with the centre column at x = 1400.
- y = 0 is the sea surface; deeper is negative.
- The **surface band** is y 0..+600: sky gradient, a wavy water line at y = 0 (3 summed sines, 2 px plus glow), and the label "0 m · SURFACE".
- The **LAUNCH POINT** buoy sits at (1400, +140).

**Bands** stack downward from y = 0:
- A charted band has height `H = 600 + 460·n`: a 340 header, then the nodes, then a 260 thermocline gap.
- An uncharted band is 1400.
- With today's data:

  | Chapter | y range |
  |---|---|
  | c1 | 0..−1980 |
  | c2 | −1980..−3960 |
  | c3 | −3960..−5480 |
  | c4 | −5480..−6880 |
  | c5 | −6880..−8280 |

- Below −8280 is the BELOW easter egg (§3.11).

**World y → metres.** Inside band *b*: `m = top_m + (bandTop_y − y) / H_b × (bottom_m − top_m)`. Above y = 0 the depth is 0 m. This mapping feeds the backdrop, the gauge readout and the YOU line.

**Node placement** (deterministic):
- `hash01(s) = ((s.hashCode() * 0x9E3779B1) >>> 8) / 16777216f`
- `y_i = bandTop − 420 − 460·i + 60·(2·hash01(id + "y") − 1)`
- `x_i = 1400 + side_i · 520 · (0.8 + 0.4·hash01(id))`, where `side` alternates ±1. The first node of a band takes the opposite side to the last node of the previous band, so the path zig-zags continuously.
- Manifest `nudgeX/Y` are added after this.

**Uncharted contacts.** Three per uncharted band:
- `y = bandTop − 400 − 400·i`
- `x = 1400 ± 480·(0.7 + 0.5·hash01(chapterId + i))`

**Layer header.** Drawn in the text pass at world positions, 340 tall, at the band top:
- numeral `02` (DISPLAY_OUTLINE 200, α .25) at world x 240
- name `TWILIGHT` (RUBIK 72, G1, accent) to the right of the numeral, with a zh subtitle when zh
- `200 m – 1,000 m` (UI_LABEL 20, `ink2`)
- `2/3 CHARTED · 5/10 CLEARED` (UI 20, `ink2`)

**Thermocline** at every band top:
- a full-width sine polyline: amplitude 24, wavelength 600, phase speed 0.4 rad/s, 2 px accent α .30
- plus two echoes at ±18, α .12

### 3.3 Nodes (song buoys) [P0 unless tagged]

**Thumbnails** (MAP owns `map/ThumbAtlas`):
- Load the jacket Pixmap and scale it to 256² (BiLinear).
- Apply a CPU circular alpha mask: `α = clamp(r₀ − d + 0.5, 0, 1)`.
- Pack into 1024² pages, 16 per page, so all nodes draw in one call.
- Cache as `local("cache/thumbs/<id>-<lastModified>.png")`. Also keep the 16×16 aura and the average colour.
- Build progressively, one per frame, starting when Welcome shows. Show a placeholder disc until each one is ready.

**Anatomy at zoom 1** (world units)

| Part | Spec |
|---|---|
| Glow | `soft_dot` 360 ⌀, lens-difficulty colour, α .12 breathing ±.04 over 3 s |
| Thumb | 168 ⌀ |
| Rim | 4 px, lens colour |
| Score rings | **Since 2026-10-17 16:40** (Kelvin: 「外圈的分数环改为 4 圈完整的环，最内圈 surface，最外圈 abyss，然后 abyss 改成黑色的环。环的占比是 max（0，（min（ 分数，100M）-60M）/40M）」): four full rings in the band r 95–119 (6 apart, 4.8 wide), SURFACE innermost, then SHALLOW, DEEP, ABYSS outermost. Each fills clockwise from the top by `max(0, (min(best, 100,000,000) − 60,000,000) / 40,000,000)` (`MapRenderer.ringFill`) in the difficulty colour; ABYSS is black `#000000`.<br>• The rest of the ring, and a chart not played yet: `#FFFFFF @ .20`.<br>• No chart: no ring.<br>• A first play sweeps the fill in (400 ms) with a glow at its end.<br>• Drawn with `Draw.ringArc()` (since 17:43, Kelvin: 「abyss的环明显能看出来是两个边拼在一起的」): pieces within 0.2 px of the circle at the current zoom, joined edge to edge, both rims feathered over 1 px; the cover's rim the same.<br>• The grade dot and its FULL COMBO / ALL RESONANCE ring are gone (grade and score: the close-zoom label and the card).<br>~~Before: four 80° arcs at r 96–102 with 10° gaps, clockwise from the top: SURFACE 85°→5°, SHALLOW −5°→−85°, DEEP −95°→−175°, ABYSS 175°→95° (math angles).<br>• Played: filled in the difficulty colour, with a 10 px grade-colour dot at the arc's clockwise end. The dot gets a 2 px `#7FE7FF` ring for FULL COMBO and is iridescent for PURE (since 2026-10-17 16:02 the ring is purple `#B49CFF` for FULL COMBO and light blue `#A6DDFF` for ALL RESONANCE, §23.5).<br>• Unplayed: `#FFFFFF @ .20`.<br>• No chart: no arc.~~ |
| Sonar dashes | 24 dashes (8° each, 3 px) at r 125 (116 before the score rings), rotating 6°/s, α .15 (selected: .35 and 4× speed) |
| Label (text pass) | title UI `clamp(21/zoom, 18, 26)` `ink1` with shadow, centred, top at node y − 132, max 360 screen px, ellipsis. Below it a lens chip 72×36: constant NUMBER 18 on the difficulty fill (`15⁺`, §1.3 chart constants). |
| NEW dot | unplayed songs: 14 px accent dot at 45° (r 92), pulsing |
| Idle bob [P1] | ±6 on y, period 3.2–4.4 s, random phase |
| Jelly lag [P1] | 2-float spring offset driven by `−cameraVelocity × 0.012`, clamped ±24 (jelly preset); hard flings make buoys trail and wobble back |
| Ping answer | `waveAt > .5` → +.25 brightness and the arcs lift +4 for 300 ms |

**States**

| State | Look |
|---|---|
| Unplayed | dashed rim plus the NEW dot |
| Lens difficulty missing (e.g. no Abyss) | rim dashed `ink3`, thumb desaturated 40 %; still tappable (the card falls back, §4.5) |
| Filtered out | α .25, scale .85, label hidden, no ping answer |
| Pressed | scale .92 (snappy), rim α 1, dashes 4× |
| Selected | pop 1.0 → 1.18 → 1.06 (bouncy); label G2; becomes the heartbeat source; the K&K marker orbits it |
| Beyond your depth [P1] | band top below the YOU line: rim tinted 25 % toward `#FF5CA8`. Informational only, nothing is locked. |

**LOD**

| Zoom | Shows |
|---|---|
| ≤ 0.85 | label adds best score (NUMBER 18) and a grade pill; a chart not played yet on the lens shows `NEW` there instead (NUMBER 18 in a 76×34 `groundSig` pill under the chip; Kelvin 2026-10-17 16:40: 「放大后如果没有打过的歌出现一个 new，和图片右上角的那个分数的显示一样」) |
| 0.85–1.8 | as specified above |
| ≥ 1.8 (overview) | labels hidden; nodes drawn at world scale × 1.3 with rings only; chapter names at RUBIK 108 with completion `SHORELINE 7/10` |

**Uncharted contacts:**
- `ring_thin` 72 ⌀ at α .30, pulsing on the heartbeat, with "?" (UI_BOLD 28, `ink3`).
- Not selectable. A tap pings and shows the toast "UNCHARTED · MORE SIGNALS COMING".

### 3.4 K&K marker [P0; headlight P2]

- **Look.** Two motes, `groundSig` and `skySig`, 14 px each with a 6-sample fading trail. They orbit at r 120 with period 2.8 s, 180° apart.
- **Position.** On the last-played node, or the selected one. Before any play, on the LAUNCH POINT buoy.
- **On a new selection:**
  - The motes swim along the cached path spline to the new node (600 ms `pow3`); the second mote trails by 90 ms.
  - They leave a bubble trail of 1 bubble per 30 units.
- **Headlight** [P2]. Below 1,000 m: an additive gradient triangle 600 long, 30° spread, α .16, pointing at the selected node.

### 3.5 Path, terrain and life

**Dive path** [P0]:
- A Catmull-Rom spline through LAUNCH POINT, each band's gate (1400, bandTop) and all nodes in order. It has 24 samples per segment, cached in a `float[]`, and only segments whose bbox is in view are drawn.
- **Explored** (both end nodes played): solid 4 px accent at α .7, plus a 12 px glow strip at α .18.
- **Unexplored:** dashed 4 px (18 on / 12 off) `ink1 @ .25`. The dash phase flows downward at 40 /s, like a current.
- **Into uncharted bands:** dotted at α .12, ending in a "?".
- **Gate ring** (48 ⌀) at each band boundary: a tap flies the camera to that layer's header.

**Terrain walls** [P1]:
- Built once per band: left and right cliff polygons with edge `x = base ± (sum of 3 sines, total amplitude 120, + 30·hash noise)`.
- Triangulated once with `EarClippingTriangulator`, drawn as `PolygonSprite` on `white`.
- Fill = band bgBottom at −15 % luminance. L1–L2 add a 2 px rim light in the accent at α .2.
- **Corridor width** narrows into a trench:

  | Band | L1 | L2 | L3 | L4 | L5 |
  |---|---|---|---|---|---|
  | Corridor width | 2800 | 2500 | 2200 | 1900 | 1700 |

- About 300 triangles per band, culled per band.

**Landmarks** [P2] (static, sine sway only):

| Band | Landmark |
|---|---|
| SHORELINE | 8 kelp ribbons along the walls (12-segment paths, sway on 6–9 s sines) |
| TWILIGHT | 3 drifting jelly silhouettes (critters below) |
| MIDNIGHT | lantern field: 10 bio-motes near the walls |
| BLACKWATER | 3 rock pillars (static polygons) with 12 pulsing lichen dots |
| TRENCH | an anglerfish lure: a magenta mote on a swinging arc, 5 s |

**Critters** [P2] (≤ 20 sprites per visible band; disabled by Reduce motion):
- L1–L2: a school of 12 light-fish (`streak`) on a Lissajous path. Each fish is pushed away from a finger within 200 and springs back.
- L2–L3: 3 jellies (a `soft_dot` bell plus 4 line tentacles), nudged by touch.

**Water touch** [P1]:
- **Tap empty water:** a `SonarField` touch ping plus 8 rising bubbles.
- **Drag:** a wake of 1 bubble per 40 units moved (cap 30 alive).
- **Long-press water:** a bubble stream at 12/s.

### 3.6 Camera [P0]

Input uses a small custom recognizer, `map/MapGestures` (as built; it replaces the `GestureDetector(halfTapSquareSize = Ui.dp(12) px, tapCountInterval 0.3, longPressDuration 0.45, maxFlingDelay 0.15)` first specified here, because the camera must track fingers 1:1 from the first moved pixel, pinch and two-finger pan must run together, a touch during a fling must "catch" the camera, and the fling needs the 80 ms least-squares fit below). States: one finger starts as PRESS; moving beyond `Ui.slop()` turns it into PAN; a second finger turns any state into PINCH (and back to PAN when one finger lifts); holding still 0.45 s turns a PRESS into a long press. Taps are reported on release (never for a catch); two taps within 0.30 s and close together are a double tap. The map uses its own `OrthographicCamera` inside an `ExtendViewport(1920,1080)`; the HUD uses the screen viewport.

**Labels under the HUD and card** (as built): map labels slide under the HUD and the song card instead of drawing over them. Each frame the HUD clusters, the card (or sheet), the filter panel and the list register occluder rects. A node label fades out over the first 28 units it enters an occluder and fades back as it leaves; a layer header fades only when it is mostly covered (gone at 60 % of its area under one occluder).

| Behaviour | Spec |
|---|---|
| Pan | 1:1 finger tracking: `worldΔ = −screenΔ × (worldW/screenW) × zoom`. Two-finger pan works during a pinch. |
| Catch | a touch during a fling stops it instantly. If speed was > 300 /s at touchDown, that touch is consumed (it never selects). |
| Fling | velocity = least-squares fit over the last 80 ms, capped 8,000 /s; decay `v *= exp(−4·dt)` (travel ≈ v/4); stop below 20 /s. Any direction: movement is free. |
| Bounds | view kept inside x ∈ [−200, 3000] (if the view is wider than 3,200, lock x = 1400); view top ≤ +600; view bottom ≥ −8,480 |
| Rubber band | overscroll `d` displays as `0.55·d·D / (D + 0.55·d)` (D = view dimension); release springs back (camera preset, ≈ 0.4 s). A fling into a bound converts its leftover velocity into overscroll and bounces. |
| Pinch zoom | about the fingers' midpoint (that world point stays fixed). Range 0.7–2.4, soft beyond (0.6 / 2.6), springs back. Every level but the hub opens at 0.7, the closest zoom (Kelvin 2026-10-19 18:46, §24.26). |
| Overview chip and rise (Kelvin 2026-10-17 19:39–19:42; **replaced 2026-10-19 18:46** by the two-finger rise hold, §24.26) | a pinch that reaches the overview lights `全图 OVERVIEW · 再捏合 ↑ 上浮` at the top while the fingers stay on and 1 s after they lift, then it fades (0.3 s). Once it has faded, a pinch at the overview that pulls 8 % on past it (the soft limit shows it giving and springing back) lights it again and does not rise; a pinch that starts while it is lit rises one level when the fingers lift at the overview, i.e. two pinches within a second. The wheel: one scroll (notches less than 0.25 s apart) is one pinch; reaching the overview lights the chip, a notch past it bumps the zoom (`MapCamera.bump`) and lights it again, and a new scroll that starts while it is lit rises. With a song card open the zoom stops at the overview with no chip and no rise (PAGE UP does not rise either). No rise ring any more (`MapScreen.updateRise`). |
| Double-tap water | toggles zoom 1.0 ↔ 1.9 about the tap point, 380 ms `exp5Out` |
| **Snap** | **none**, ever. The camera glides only on intent. |
| Fly-to | duration `clamp(0.35 + dist/6000, 0.45, 1.1)` s, `pow3`. Zoom hops +0.4 at the midpoint along a `sine` arc, so long jumps hop out and back in. Snow streaks scale with speed. Any touch cancels a glide. |
| Idle drift [P1] | after 8 s idle: ±6 units on a 14 s sine; stops on touch |
| Desktop | left-drag pans; wheel zooms ×1.1 per notch at the cursor; WASD/arrows pan 1,200 /s; `+`/`−` zoom; PgUp/PgDn jump layers; Tab / Shift-Tab cycle nodes in path order; Enter opens the card, then DESCENDs; Esc closes the card, then goes back to Welcome |
| Persistence | `profile.mapCamX/Y/mapZoom` plus the selected node, saved on hide. First visit: centred on the first unplayed node of c1 at zoom 1.0. |
| Focus point | centre of the *uncovered* map area. Side card: `x = (VL + cardLeft)/2`. Bottom sheet: `y = (sheetTop + VT)/2`. |

### 3.7 Input priority [P0]

`InputMultiplexer` order: overlays (filter, list, sheets, toasts) → card → HUD → map.
- HUD controls claim touches inside their hit rects.
- On the map, a node gets the press state on touchDown, but the select decision waits for tap vs pan classification. A pan that starts on a node cancels its press.
- The card swallows every touch inside its rect.

### 3.8 Selection flow [P0]

1. **touchDown on a node.** Press state, light haptic.
2. **Tap** (release within slop, not a catch):
   - The node pops and its label does a G2.
   - `sonar.ping(node, 420, 0.6, accent, 0.35, 2)`.
   - The motes travel to it.
   - The camera glides the node to the focus point (420 ms `exp5Out`). Zoom → 1.0 if it was > 1.2.
   - The card enters (§4.1).
   - Preview audio follows the dwell rule (§4.8).
3. **Tap another node while the card is open.**
   - Card content crossfades: old content rises 16 and fades (120 ms), new content rises in from below (200 ms).
   - The jacket does a 2-frame G2.
   - Camera and motes move as in step 2.
4. **Long-press a node (peek)** [P1]. A radial ring fills around the node over 450 ms, then a floating tooltip shows the title and best score per difficulty. No camera move. Release dismisses it.
5. **Dismiss:**
   - Triggers: tap empty water (not a drag); swipe the card right or the sheet down (velocity > 900 /s, or past 35 % of its size); BACK / Esc.
   - Exit: 220 ms `pow2In`.
   - Audio: preview fades 400 ms; menu BGM returns over 800 ms.
6. **Lock-on leader line** [P2]. A 1 px accent line runs from the node rim to the card edge, with a 6 px diamond at the node end.

### 3.9 HUD around the map [P0 unless tagged]

The map area is VL..`mapRight`, where `mapRight` = VR when no card is open, or the card's left edge while it is open (animated with the card spring).

| Element | Placement | Spec |
|---|---|---|
| Profile badge (split into a player frame top left and a shell capsule top right since Kelvin 2026-10-19 06:28, §24.19; both larger since 2026-10-20 03:26 and the capsule no longer a control since 03:38, §24.29; the player frame a frameless block since 14:10, §24.34; the capsule beside the depth tag, the depth larger and a band under the name since 15:36, the avatar ring as tall as the band and the tag since 15:46 and 150 across since 16:05, §24.35; the name higher, lined up with the tag's tip and its band deeper since 16:37–16:39, §24.36; no DEPTH label and drawn at 75% since 17:34, §24.37) | TL at (SL, ST − 120), 540×120 panel | • avatar 88 ⌀ at left<br>• row 1: name UI_BOLD 22 (fit 260) plus `LV 12` pill (NUMBER 20, `gold`) right-aligned<br>• row 2: depth `2,560 m` (NUMBER 24) plus rank title (UI_LABEL 20, band accent) with a 24 px emblem<br>• row 3: EXP bar 400×6 (`gold` fill)<br>Tap → Profile. After a level-up it bounces once and the level rolls. |
| Icon cluster | top-right of the map area: right edge `mapRight − 24`, top ST; 3 × `P` with gap 16 | Filter (funnel) · Tutorial (?) · Settings (gear) |
| Depth gauge | x `mapRight − 80 .. mapRight − 24` (56 wide), y from `ST − 160` down to `SB + 160` | • linear in world y over +600..−8,280<br>• layer segments as 6 px bars in accent colours; chapter ticks labelled with metres (UI_LABEL 18)<br>• song dots (lens colour if played)<br>• viewport bracket; readout pill 140×52 left of the bracket: `1,240 m` (NUMBER 22)<br>• **YOU marker:** a gold diamond 20 px plus the two signal dots at the player's depth<br>• drag the bracket to scrub; tap a tick to fly to it |
| Recentre | `(mapRight − 24 − P, SB)` | crosshair → flies to the K&K node. **Removed 2026-10-16** (Kelvin: 「那个定位的也去掉」), with the rise plate under it; ALL SONGS' SORT button takes this corner (§16.4). |
| Lens difficulty | BL at (SL, SB): segmented control 4 × 168×96 | full names (UI_BOLD 20, `drawFit` min .85); selected segment filled with the difficulty fill. Hidden while the card or sheet is open (the card has chips). |
| Layer chip | right of the lens control, gap 16 | `02 · TWILIGHT · 2/3 CHARTED` (UI 20). Tap → fly to that header. |
| Edge arrows [P1] | screen edges, on the line toward the target | when the K&K node or the selected node is off-screen: 64 px chevron plus `ink2` label (UI 18). Tap → fly. |
| YOU line [P1] | world y of the player's depth | full-width dotted gold line, 2 px, dash 10/10, α .6; label `YOU · 2,560 m` (UI_LABEL 18, `gold`) pinned at `VL + 24` in the text pass |
| Layer-crossing toast | `(SL, ST − 180)`, slides from the left | §3.11 |
| Coach marks (first visit) [P1] | centre | §10.1 |

### 3.10 Filter and list [P1]

**Filter panel.** Drops from the Filter button: 600 wide, `panel`, 280 ms `pow3Out`.
- **Status chips:** ALL / NEW / NOT CLEARED / NO FULL COMBO / BELOW S / IN TOP 10.
- **Constant range:** dual-knob slider 1.0–17.0, step 0.1.
- **Search field:** `getTextInput`; flies to the first match.
- **LIST toggle.**
- Non-matching nodes take the *Filtered out* state; matches pulse their halo.
- A pill `5 MATCH ‹ ›` glides the camera between matches.
- Nothing is reordered; the map stays spatial.

**As built 2026-10-16** (Kelvin: 「filter只在all songs里面出现，并且只保留new/full combo/resonanted（也就是满分）这三种filter」; replaces the list above on the cave map):
- The Filter button (funnel) is shown only on ALL SONGS. Its panel `FILTER` holds three chips: `NEW` (the lens chart never played), `FULL COMBO` and `RESONATED` (`ALL RESONANCE` until 2026-10-18 14:29, §24.13), all judged on the lens difficulty; a song without a chart for the lens never matches (`map/MapFilter`). One chip at a time; tapping the lit chip clears it. The count line reads `n MATCHES`, or `ALL SONGS` with no chip lit.
- Non-matching songs are left out and the grid is laid out again, anchored at its top-left under the badge; the camera keeps the unfiltered grid's bounds. Pack placeholders and pack notes are hidden while a chip is lit. With no match the grid shows `NO MATCHES · <chip>` (`没有符合的歌 · <chip>`).
- Switching the lens re-runs the filter.
- Removed: the constant range, search, LIST (and the L key), RESET and the match pill. The list overlay below is no longer opened from the map.
- **2026-10-16 22:39** (Kelvin: 「把filter并到sort里面」): the funnel button and its panel are gone; the three chips are the `筛选 FILTER` group at the bottom of the SORT panel (§16.4), same rules. The SORT button stays lit with its dot while a filter is on; F opens the SORT panel. There is no match count any more.

**List overlay** (the accessibility and fast-access fallback):
- A left panel 720 wide, full height, grouped by chapter headers.
- Rows 112 tall: thumb 88, title UI_BOLD 24, artist UI 20, lens constant chip, best grade pill.
- Scrolls with the same 1:1, fling and rubber-band physics as the map.
- Tapping a row closes the list, flies to the node and opens the card.

### 3.11 How "diving deeper" is shown

As the camera descends, these effects stack continuously:
- **Light.** The palette blends (§2.3). Shafts and caustics are gone by L3.
- **Particles.** Bubbles become snow; streaks follow camera vy; bio-motes appear and answer the heartbeat.
- **Shape and texture.**
  - The walls close into a trench.
  - Vignette tightens from 0.30 to 0.70 and grain grows.
  - The idle-glitch interval shrinks from 7.0 s to 1.6 s.
- **Numbers.** The depth readout ticks; the gauge bracket descends; the YOU line marks your depth.
- **Thermocline crossing** (the camera centre crosses a band top; not repeated within 4 s):
  - a G3 tear (180 ms)
  - a full-width ping from the boundary
  - a toast (280 ms in, holds 1.6 s, 200 ms out): numeral DISPLAY_OUTLINE 36 + name RUBIK 36 (G1) + `1,000 m` NUMBER 24, e.g. `03 · MIDNIGHT · 1,000 m`
  - optional "whoomp" SFX [P2]
- **First visit to a layer** [P1]. A sonar sweep band moves down at 1,200 /s; nodes resolve one by one (150 ms stagger) from static to clean with a G2. This uses `profile.seenChapters` (Appendix A).
- **Uncharted bands (c4, c5).**
  - static grain α .25 over the band
  - label `SIGNAL NOT FOUND` (UI_LABEL 20)
  - metres that decode-loop with unknown digits (`4,0?? m`)
  - the contacts from §3.3
- **Edges.**
  - **Below −8,280 (overscroll):** BELOW static and a scrambling depth counter (easter egg).
  - **Above y = 0:** surface shimmer and `0 m · SURFACE`.

### 3.12 Transitions [P0]

TransitionLayer styles are defined in Appendix A: `CURTAIN`, `DIVE`, `ASCENT`, `IRIS`.

**Welcome → Map ("the descent", no TransitionLayer):**
- Welcome exit (450 ms): the logo flies up 200 and fades (`pow2In`), and the motes dive out of the bottom.
- `Backdrop.setDepth` ramps toward the map camera depth; Backdrop is shared, so the water is continuous.
- At 450 ms, `switchNow(map)`. The map's onShow plays the arrival: camera from saved y + 1,200 down to its saved position (800 ms `exp5Out`), with snow streaking upward.

**Map → Gameplay ("The Descent", style DIVE), about 1.35 s plus loading:**

| t (ms) | Event |
|---|---|
| 0 | DESCEND release: button flash; label G2; `sonar.ping(button, 600, 0.6, accent, 0.4, 3)`; medium haptic. Chart parsing may start on a worker thread (no GL). |
| 0–220 | Card exits (`pow2In`); HUD elements slide off their nearest edges (200). Preview and BGM fade out over 400. |
| 100–650 | Map camera zooms into the node (zoom → 0.2, `exp5In`); the thumb fills the view; snow streaks upward (virtual vy → 3,000 /s); backdrop depth ramps to the gameplay depth. |
| 550 | `game.startGameplay(...)` → DIVE **close**: upper panel `skySig` from the top and lower panel `groundSig` from the bottom (350 ms `pow3In`) meet at y = 540; 2 px `seam` line. |
| covered | After 300 ms covered, the seam shows `SYNCING SONAR` (UI_LABEL 20) and a depth counter (NUMBER 36) rolling to the gameplay depth. Minimum cover 250 ms; the open waits until the chart is loaded. |
| open (420 ms) | **Line split:** the seam splits; the upper panel's lower edge flies 540 → 960 and the lower panel's upper edge flies 540 → 120 (`exp5Out`). The panels fade over the last 180 ms. Their edges become the judgement lines, at the same y and the same colours as gameplay's lines, then cross-fade into them over 150 ms. The start sequence (§5.11) begins. |

**As built (2026-10-13):** the DIVE is the X2c water walls (pearl from the top, cyan from the bottom, wavy foaming edges; close 350 ms `pow2In`; covered at least `DIVE_MIN_COVER` 1.5 s, or `DIVE_ART_COVER` 4.5 s when the art is shown (Kelvin 2026-10-16: 「曲绘制作信息那一段就要4.5秒，其余部分时长不变」), with spray and two ripple rings; the walls part to the judgement lines, 420 ms `exp5Out`, and fade over 350 ms). The song's art disc (radius 325 since 2026-10-21, 250 before; §24.46) sits on the walls at the centre while they close. **Info to the right of the art** (Kelvin 2026-10-13 22:16: 「合拢以后，曲绘往左侧移动，然后右侧依次弹出曲作者，标题，谱师画师等信息」):
- Once the walls meet, the disc glides left (0.40 s `pow3Out`, from 0.05 s) so that the disc, a 72 gap and the info column are centred together.
- The rows pop in on its right from 0.20 s, 0.10 s apart (each 0.25 s: slides in from 40 left with `pow3Out` and fades in), stacked about y = 540 with 16 between them: `MUSIC` + artist, the title (UI_BOLD 44) with the difficulty and constant (UI_BOLD 22, difficulty accent) on a 104-tall pill rimmed `skySig`, `CHART` + charter, `ILLUSTRATION` + illustrator. Credit rows are 60-tall dark pills rimmed `groundSig`: label UI_LABEL 15 `ink2`, name UI 22 `ink1`. A row whose name is empty is left out.
- The column is at most `worldW − 160 − 500 − 72` wide; longer content scales down to fit.
- On open the rows fade over 150 ms, the art over 460 ms, while the disc's rim widens toward 600.
- When no art is set (a DIVE that does not start from a song on the map, such as the tutorial), the seam shows `SYNCING SONAR` and the depth counter instead.

**Gameplay → Result ("Ascent", style ASCENT):**
- Starts once the last note has passed and the music has ended, plus `BANNER_FINISH_DELAY_MS` (3,000: the end banner's 1 s wait and 2 s on screen; the tutorial, which has no banner, keeps `FINISH_DELAY_MS` 1,500) (§5.13).
- **The DIVE's water walls, and nothing but the water** (Kelvin 2026-10-13 06:57: 「游玩界面->结算界面的转场和选歌界面->游玩界面的那个转场一样」; 06:22: 「不需要任何其他的元素」). It replaces the panels and the downward snow streaks. There is no art disc, title chip, `SYNCING SONAR`, depth counter or judgement-line glow.
- **Close (350 ms `pow2In`):** pearl water from the top and cyan water from the bottom, with wavy foaming edges, meet at y = 540.
- **Covered (at least 600 ms):** spray flies off the seam and two ripple rings sweep out from the centre. Gameplay Music is disposed, the result music is loaded and `result.present(...)` runs.
- **Open:** the walls part to y = 960 and y = 120 (420 ms `exp5Out`) and fade out over 350 ms. This is result t = 0.

**Result → Map (style IRIS, then the map's "return"):**
- **Iris close:** a filled disc in the current layer bgBottom with a 4 px bright `ink1` ring edge expands from the CONTINUE button (380 ms `pow3In`).
- **Iris open:** the disc fades over 300 ms while the ring keeps expanding.
- **The map's return:** the camera starts on the node at zoom 0.4 and glides to the saved zoom (600 ms `exp5Out`). Newly played arcs sweep-fill (400 ms). If it was a new best, a `NEW BEST` floater (RUBIK 30, `gold`) rises 80 and fades over 1.2 s. The motes do one celebratory loop at 2× speed.
- **Convention (as built, ARCHITECTURE §15.1 item 10):** every way back to the map from a song (Result CONTINUE, Pause → Quit) uses IRIS, and the map recognises the IRIS to play this return and reopen the card on the song; a CURTAIN return plays the plain arrival. The played song's jacket stays loaded from DESCEND through gameplay and result (the card closes with `keepJacket`), so neither screen decodes it again.

**Other transitions:**

| From → to | Style |
|---|---|
| Result → Retry | DIVE without the map zoom (close and line split only) |
| Pause → Quit to map | IRIS |
| Map ↔ Profile / Settings / Calibration | CURTAIN |
| Welcome → FirstRun | IRIS |
| FirstRun → Tutorial | DIVE |

**CURTAIN** (menus) has a close of 360 ms `pow3In` and an open of 400 ms `exp5Out`:
- panels `#0A1033` (top) and `#070C27` (bottom)
- a 2 px `groundSig` seam with a scan beam racing left to right (250 ms)
- the destination name on the seam (UI_LABEL 20)

### 3.13 Layouts per aspect

```
20:9 (2400×1080), card open — map area 1640 wide, focus x ≈ 580
┌──────────────────────────────────────────────── 2400 ───────────────────────────────────────────────┐
│[(av) NAME   LV12 | 2,560 m ECHO HUNTER I ▬▬▬]        (⏷)(?)(⚙)  ┃┌────────── SONG CARD 760 ─────────┐│
│                                                     1,240 m ◂─┃│ ‹        02 · TWILIGHT         › ││
│   02  TWILIGHT  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~  ┃│ ┌──────┐ BEST                     ││
│              ◉ selected  ●● (K&K motes)                    ◆YOU│ │jacket│ 09,812,345  [S+] FC      ││
│        ○─ ─ ─ ─ ─ ─ ─ ─ ─ ○                                   ┃│ └──────┘ RATING 15.84 · TOP10 #4  ││
│  ······ YOU · 2,560 m ··························              ┃│ Song Title / Artist               ││
│                                                       (⌖)     ┃│ [SURFACE][SHALLOW][ DEEP ][ABYSS] ││
│                                                               ┃│ ▂▃▅▆▅▇   NEXT ▼ +84 m AT 9,967,000││
│                                                               ┃│ [AUTO] [======= DESCEND ⌄⌄ ======]││
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
16:9 (1920×1080): same structure, card 700, map area 1220.
16:10 (1920×1200): card 700; the extra 120 goes into the gap above DESCEND (§4.3).

4:3 (1920×1440), bottom sheet
┌──────────────────────────── 1920 ────────────────────────────┐
│[(av) NAME LV12 | 2,560 m]                   (⏷)(?)(⚙)       │
│                                                         ┃    │
│        map ≈ 1920×800 at full detent (focus shifted up) ┃gauge
│                 ◉                                       ┃    │
├─────────────────────────── ▬▬ ───────────────────────────────┤  full 640 / peek 300
│ ┌jacket 300┐ 02 · TWILIGHT   BEST 09,812,345 [S+] RATING …  │
│ └──────────┘ ACC · LOCAL · PLAYS · ▂▃▅▆▅▇ · NEXT ▼ +84 m …   │
│ Song Title · Artist                       BEST 09,812,345 S+ │  ← peek starts here
│ [SURFACE][SHALLOW][ DEEP ][ ABYSS ]      [AUTO][ DESCEND ⌄⌄ ]│
└──────────────────────────────────────────────────────────────┘
```

---

## 4. Song card and difficulty

### 4.1 Container [P0]

**Side card (WIDE, aspect ≥ 1.6):**
- Attached to VR, spanning the full visible height. Its background extends under the cutout; content stays inside SR/ST/SB.
- Width: 760 when aspect ≥ 2.0, otherwise 700.
- Left corners radius 18, `panel` fill.
- Enters from +width over 320 ms with a 12 px overshoot (sheet spring).

**Bottom sheet (TALL):**
- Full width, attached to VB.
- Detents measured above SB: **peek 300** and **full 640**.
- Drag handle: 80×8 pill, 16 below the sheet top.
- On release, the detent is chosen from the projected position `pos + v × 0.15` and reached with the sheet spring.
- Enters to peek over 320 ms.

Content is laid out top-down except the action row, which is anchored to the bottom. Any extra height goes into the gap above the action row.

### 4.2 Side card content (round 5, Kelvin 2026-10-19 06:41–07:01; blueprint 1920 × 1200, y measured from the top)

The card is 700 wide (760 at aspect ≥ 2.0) with padding 40, so on 16:10 the content runs x 1260–1880. Everything below is at scale 1 (16:10); §4.3 says how other screens differ. The decisions behind it are in §24.20.

| y (from top) | Element |
|---|---|
| 100 (centre) | header: ‹ at the left, flush with the jacket's left edge (x 1260), and › at the right, flush with its right edge (x 1880): icon buttons (`P` hits; 76 visuals, 64 until 2026-10-19 13:37) that step through songs in path order, wrapping round at the ends (› on the last song opens the first; 2026-10-17) · between them, centred, the layer chip `SINGLES · 单曲` (UI_LABEL 26, accent; 20 until 13:37). No × since 2026-10-19 13:37 (§24.22) |
| 177–526 | **jacket** 16:9 across the content width (620 × 349, centre crop), radius 14, no float or tilt; a 1.2 stroke (140, 200, 255) α .31 over it and a soft glow of its own colours under it (the jacket blurred over 1.16 × 1.24 of its size, α .47 at the middle, feathered to 0 at the edges, as on the result screen) |
| 546–660 | **title band** across the whole panel: (8, 13, 38) α .59, a 1.2 line (120, 200, 255) α .27 at each edge |
| 602 (baseline) | **title** UI_BOLD 34 from x 1260, one line; shrinks to fit, down to 29. Past 30 characters, or too wide even at 29 (CJK titles can be with fewer), it is 29 and scrolls (`ui/Marquee`, §24.20) |
| 642 (baseline) | artist UI 22 `ink2` from x 1260 · `BPM 182` UI 20 `ink3` right-aligned at x 1880 (the range `BPM 140-400` when the song changes tempo) |
| 674–822 | **grade ring** at the right: centre (1806, 748), outer diameter 148, stroke 4 in the grade colour, purple (`FULL_COMBO`) once the chart has a full combo (R and R+ stay light blue, §24.21), no fill; the letter in RubikGlitch 78 in the middle (superscript − / +; R and R+ sweep). No FULL COMBO / RESONATED ribbon since 2026-10-19 |
| 742 (baseline) | **best score** UI_BOLD 42 from x 1260, letter spacing .02, dim leading zeros (`LEADING_ZERO`); no BEST label |
| 790 (baseline) | `DEPTH` (UI 13, letter spacing .12, `ink3`) 10 `-2,076 / -5,760 m` (UI_BOLD 17, `ink2`) 32 `PLAYS` 10 `2` (or `2 · LOCAL #1 / 3`); the row shrinks as one to stay 16 off the ring |
| 838–954 | **difficulty chips**: 4 × 143×116, gap 16 (§4.5) |
| 984–1024 | micro-goal centred on the content, x 1570 (§4.6; right-aligned at x 1880 until 2026-10-19 16:36, §24.24; the recent sparkline left of it went the same day, §24.21). With AUTO on, `AUTOPLAY · NOT RECORDED` takes this row (left-aligned at x 1260) |
| 1048–1176 | **action row** on SB: AUTO toggle 104×128 · gap 24 · **DESCEND** 492×128 (§4.7) |

When there is no record the score, the ring and the DEPTH row give way to `NOT YET DIVED` (UI_BOLD 26) with `FIRST DIVE · +40 SHELLS` (UI 17, accent) under it. The tutorial's card shows `LEARN THE TWO LINES` and `START HERE` / `COMPLETED · REPLAY ANYTIME` there instead.

### 4.3 Other aspects

- The card scale is `min(uiScale, safe height / need)`, never below 0.8, where need is the blueprint's height: 1,152 (1,186 at aspect ≥ 2.0, whose jacket is taller). A shorter screen (16:9, 20:9 phones) scales the whole card down like the result screen: about .9 on 16:9 and .87 on a 20:9 phone at UI scale 1.15. Text keeps the §1.1 least sizes (`MapUi.cap`); the chip names may shrink further, to the card's own scale, so they stay whole.
- A taller screen (4:3) keeps scale 1 and shares the extra height evenly between the four gaps: header → jacket, band → ring, DEPTH row → chips, chips → micro-goal (39 / 14 / 48 / 30 on 16:10; the first is measured from the arrows' foot, 38 under the header's centre). A screen too short even at 0.8 closes them towards 16 / 6 / 44 / 12.
- At aspect ≥ 2.0 the card is 760 wide, so the jacket and the title band are 680 wide and the jacket 382 high.

### 4.4 Bottom sheet content (TALL)

All rows are measured upward from SB.

| Rows (from SB) | Shown at | Content |
|---|---|---|
| SB .. +128 | peek and full | chips 4 × 184×104 (gap 16) from SL + 40 · AUTO 112×128 · DESCEND 480×128 right-aligned at SR − 40 |
| baseline +156 | peek and full | artist UI 20 `ink2` |
| baseline +190 | peek and full | title UI_BOLD 34 (`drawFit` 900) · right-aligned: BEST NUMBER 28 + grade pill |
| +292 .. +592 | full only | jacket 300×300 at SL + 40; to its right, two columns: (layer chip, meta, record block: ACC / rating line / LOCAL / PLAYS) and (micro-goal) |

### 4.5 Difficulty chips and lens [P0]

**Chip anatomy:**
- name UI_BOLD 20 (`drawFit` min .85), centred at the top
- constant NUMBER 30 as its whole number with a superscript mark (`15⁺`, §1.3 chart constants), below it
- **Selected:** filled with the difficulty fill (text colours from §1.2), scale 1.06 (bouncy), soft glow in the difficulty accent.
- **Unselected:** `panelHi` with a 2 px outline in the difficulty fill at α .6; name `ink1`, constant in the difficulty fill.
- **Missing chart:** no chip at all (Kelvin 2026-10-17; it used to be a dashed `ink3` outline with `—`). The other chips keep their width and close up from the left, so a song without ABYSS shows SURFACE / SHALLOW / DEEP in their usual places. A swipe or Q / E past the last chart shakes the current chip.

**Changing difficulty:**
- The fill sweeps in left to right (160 ms); the constant slot-rolls in (160 ms).
- The card accent (DESCEND glow, jacket rim, border highlight) recolours to the difficulty accent.
- The record block crossfades (160 ms).
- G2 on the chip label.

**Lens:**
- The selected difficulty is the global **lens**. Map node rims, arcs and node chips recolour, and the choice is persisted to `profile.lastDifficulty`.
- A horizontal swipe on the chip row steps the difficulty.
- If the lens difficulty is missing on a song, the card opens on the nearest *lower* difficulty (Abyss → Deep); the lens itself stays.

### 4.6 Record, rating line and micro-goal

**Record block:** best score, mini stamp, badge, accuracy and plays for the current difficulty. When there is no record: `NOT YET DIVED` (UI 20, `ink2`). The side card shows it the round 5 way (§4.2): the score, the DEPTH / PLAYS row and the grade ring, no accuracy. Since 2026-10-19 (§24.21) the clear badge is the grade's colour: purple once the chart has a full combo, no ribbon.

**Rating line** (one of):
- `RATING 15.84 · TOP 10 #4`
- `RATING 12.30 · NOT IN TOP 10`
- first play: `FIRST DIVE · +40 EXP`

**Recent sparkline** (removed 2026-10-19, §24.21: 「最近6次成绩就不要再显示了，太挤了」):
- 6 bars, 20 wide with gap 8, height ∝ `clamp((score − 8M)/2M, 0.05, 1)`.
- Newest at the right and brightest.
- Empty slots are hidden (no fake zeros).

**Micro-goal** [P1] (algorithm in §11.5):
- Copy: `NEXT ▼ +84 m AT 9,967,000` (UI 20, accent; ▼ procedural).
- If the next step is out of reach below PURE: `PURE = ▼ +12 m`.
- If no further gain is possible: `MAXED`.

### 4.7 DESCEND and AUTO [P0]

**DESCEND:**
- Primary button. Label `DESCEND` in DISPLAY 40 with G1.
- A procedural double chevron to the right of the label sinks in a 900 ms loop.
- Breathing outer glow α .20 ↔ .35, synced to the heartbeat (2.4 s).
- **Press:** scale .96, y −4, and a "pressure" gradient rises inside the button (0 → 100 % over 250 ms).
- **Release:** the Descent (§3.12).

**AUTO:**
- Toggle chip: `AUTO` UI_BOLD 20 with an indicator dot.
- When on:
  - DESCEND reads `WATCH` and is tinted `auto`
  - a line `AUTOPLAY · NOT RECORDED` (UI 18, `auto`; `AUTOPLAY · 不记录成绩` in 中文) appears above the action row
  - no records or EXP are saved

### 4.8 Preview audio [P0]

| Event | Audio behaviour |
|---|---|
| Select | menu BGM ducks to 25 % over 300 ms |
| Dwell 450 ms on the same selection | `audio.playPreview(song)`: starts at `previewStartMs` (default 30 % of duration); fades in 600 ms while the menu fades to 0; loops a 20 s window with 800 ms fades at its edges |
| Rapid re-selection | resets the dwell; the previous preview fades out over 300 ms and is disposed. **Never more than one preview `Music` alive.** |
| Deselect | preview fades out 400 ms; menu returns to 100 % over 800 ms |

---

## 5. Gameplay

Fixed geometry from the charts:
- **As built 2026-10-19 (owner 03:51 「坐标系我们现在修改为1920*1200」):** the charts' reference space is **1920×1200** (format v3, 16:10 is the blueprint; `gameplay/Field` stretches it to every screen, `ky = worldH / 1200`, 0.9 on 16:9). Every y below is in the old 1920×1080 space, which is what a 16:9 screen shows, unchanged: in the new space the lines are at 133.33 / 1066.67, the input zones ±480, the drawn band ±120 and the default spawn 1080. Format v1 / v2 charts are converted on load (×10/9), so they look and play exactly as before. **Since 2026-10-19 14:55 (owner 「我觉得换成1080/120」)** the charts are format v4: lines at 1080 / 120 (972 / 108 on 16:9), the default fall still 1080 over 2 beats (owner 15:19); the zones stay ±480, so the two zones meet at 600 instead of overlapping, and the band ±120 reaches the frame edges. v1–v3 charts have their lines moved on load (`ChartMapper.toLines1080`); their notes keep their numbers and still reach the lines on their beats.
- reference space 1920×1080
- ground line y 120; sky line y 960
- zones ±108: **y 12–228** and **852–1068** (drawn band; since 2026-10-12 19:09 the input zones are ±432, roughly half the screen each, see CHANGES §7.11)
- the middle band y 228–852 is not judged
- default lane bounds −240..2160 (10 × 240) in shipped v1 charts; v2 charts default to 8 lanes (ids −4..−1, 1..4) in 0..1920. Lane geometry always comes from `LaneLayout` (ARCHITECTURE §13).
- ground notes spawn at y ≈ 1092; sky notes at y ≈ −12

### 5.1 Draw order and blend blocks [P0]

As built 2026-10-12 (`GameplayScreen`):

1. Backdrop (shader) → image hook → jacket wash
2. **Normal block A** (atlas): zone hint, full-height lane separators (§21.2), the tide wash (§19.2), hit error meter frame, PERFECT glint
3. **HUD-under text** (font page): combo, judgement word (and its EARLY/LATE tag)
4. **Normal block B** (atlas): judgement line cores and lane ticks, TIDE STREAM bands and LANE MINE columns (§20.2), afterimage copies, notes (TIDE heads first, then lane notes and mines), hold ribbons
5. **Additive block** (atlas, one blend switch, `setEmittersCleanUpBlendFunction(false)`): line glows, approach glow, line flashes, note glows, lane columns, contact rings, hit FX and the water rings (§20.5)
6. **HUD-over:** tutorial captions, start intro card, countdown, end banner
7. **Top layer:** pause scrim (while paused) → the corner HUD shapes (progress bar, chip plate, pause icon with its double-tap hint) → the pause menu's buttons → one recorded text pass for the corner text (score, chip, title, time, live counters) and the menu labels

**Replaced 2026-10-12:** "The persistent HUD is drawn **under** the notes". The corner HUD is drawn **last** (owner: lane glows and held bodies must never cover it); only the combo, the judgement word, the hit error meter and the glint stay under the notes. The top layer costs two draw calls per running frame (corner shapes and corner text each need their own flush after the additive block).

**Zone-overlap guard** [P0]. HUD anchors assume the default line positions. If chart events (JudgementUp/Down) move a line so its ±108 zone overlaps a HUD element's rect, that element fades to α .15 (120 ms) while overlapping. This is a cheap per-frame rect test. **As built for the corners (2026-10-12):** at 16:9 the top corners sit inside the sky zone by design, so the zone rule would dim them for the whole song. A corner element (score cluster, pause icon, chip + title, time) fades to α .15 (120 ms) only while a judgement line itself comes within `LINE_GUARD` = 8 of it inside the lane window, or while an at-lane judgement word overlaps it. The default lines never fade a corner at any UI scale. Combo, word, meter and live counters keep the zone rule.

### 5.2 HUD placement rule [P0]

**As built 2026-10-12 (owner decision; replaces the first rule and §21.5's preview K3 where they differ).** At every aspect the persistent HUD sits in the four screen corners, each element anchored to the `Ui.safe*()` edges: **score top-left, pause control top-right, difficulty chip + song title bottom-left, time remaining bottom-right** (the accuracy under the score until 2026-10-20, §24.31; the score's x leaves the cutout inset out since 05:56, and its size and height come from the top band since 06:27, §5.3), and the progress bar along the top edge (1 % of the screen's height since 2026-10-20, §24.42). Combo, judgement word and hit error meter stay in the middle band. The corners are drawn last (§5.1).

- **Band layout** when `VT − 1068 ≥ 104` (3:2, 4:3, tall desktop windows): the corners as above, and the live counters [P1] centred in the bottom band (§5.4).
- **Middle-band layout** otherwise (16:10, 16:9, 19.5:9, 20:9): the corners as above, no live counters.

**Replaced:** "In the middle band, HUD rects stay above y 252 and below y 828 … Nothing persistent is placed inside a zone." At 16:9 and 20:9 the corners lie inside the outer judgement zones by the owner's decision (the lines are only 120 units from the edges). Score, chip, title and time are **display-only**: they have no hit rect and every touch on them reaches gameplay. The pause control's box also passes every touch to gameplay first (§5.12). At 4:3 and 3:2 the corners are clear of the zones.

### 5.3 Corner layout (16:9 shown; every aspect anchored to the safe edges) — as built 2026-10-12

**Replaced 2026-10-12:** the middle-band layout of the first design (pause at SL with an 80 ⌀ icon at y 828, score and accuracy right-aligned at SR at caps 40 / 24, chip and title at y 252, time at baseline 260). The owner moved the HUD to the corners (§5.2); the numbers below are the build's (`GameplayHud`, `PauseControl`), at UI scale 1. Corner sizes scale with `k = clamp(uiScale, 0.9, 1.3)`.

```
y1080 ┌──────────────────────── progress bar 1 % of height ───────────────────────────┐
 1048 │ 000,812,345  (score, cap 43.2)                                      ( Ⅱ )     │
      │                                                              pause ⌀64 at     │
      │                         sky zone 852–1068                     (1862, 1026)    │
  960 │━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ SKY LINE (#F2F7FF) ━━━━━━━━━━━━━━━━━━━━━━━━━━│
  852 ├ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─┤
  636 │                                  1,204  (combo cap 96, α .30)                │
  504 │                                  COMBO  (AUTOPLAY in an autoplay run)         │
  444 │                           RESONANCE  LATE                                     │
  380 │                           [hit error meter, optional]                         │
  228 ├ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─┤
  120 │━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ GROUND LINE (#4FD8FF) ━━━━━━━━━━━━━━━━━━━━━━━━━━│
   24 │ [DEEP 15⁺]  Song Title                                                    1:52 │
    0 └───────────────────────────────────────────────────────────────────────────────┘
```

| Element | Anchor | Style |
|---|---|---|
| **Score** | top-left: left-aligned 24 from the visible left edge VL plus the Settings edge padding, whatever the cutout (`GameplayHud.SCORE_LEFT`; SL until 2026-10-20 05:56, so a phone's cutout inset pushed it in, 134 from the edge with a 110 inset; §24.31), cap top 0.3 of the top band under the top edge (0.15 from 06:27 to 07:01), the band running from the pause control's top edge (VT less the cutout inset and the edge padding) to the default sky line (16:9: cap top 1047.6, baseline 1004.4; 1063.8 from 06:27; at ST, 1056, before 2026-10-20 06:27) | NUMBER **0.4 of the top band** (`SCORE_BAND_TOP` 0.3, `SCORE_BAND_CAP` 0.4; Kelvin 2026-10-20 07:01, §24.33): 43.2 at 16:9 and 20:9, 48 at 16:10, 57.6 at 4:3, whatever the UI scale, never under `Ui.minEssentialCap()` (0.7 of the band from 06:27, §24.32; 40 × k from 05:56, §24.31; 32, 80 % of 40, before), `drawDigits` **9 digits** grouped (`000,000,000`, §18.1), `ink1 @ .80`, leading zeros `ink3 @ .40`. Changed cells pop (scale 1.08 → 1, 90 ms). No glitch. In an autoplay run tinted `auto`. |
| ~~Accuracy~~ | **Removed 2026-10-20 05:56** (§24.31; first at 05:00, back 05:31–05:56, §24.30) | was NUMBER `max(19.2·k, 20)` `99.12%`, α .60, left-aligned at SL with its cap top 12 under the score's baseline; `AUTOPLAY` (UI_LABEL 20, `auto`) stood in its place in an autoplay run, under the combo since 05:57 (Combo row). |
| **Pause** | top-right. With SR′ = SR + 24 and ST′ = ST + 24 (the visible edge less the cutout inset and the Settings edge padding): ring centre x SR′ − 26k − 32k; y halfway between ST′ and the default sky line (1080 of the 1920×1200 frame, a tenth of the world height under the visible top; Kelvin 2026-10-20 05:25, §24.30), the ring's outer edge never closer than 10 to ST′ (`TOP_CLEAR`; only a large EXTRA EDGE PADDING reaches it). 16:9 (1862, 1026) = (W − 58, halfway down the band 1080–972); 20:9 (2102, 1026); 4:3 (1862, 1188); 16:10 (1862, 1080). Until 05:25 the centre was ST′ − 50k (16:9 1030, 4:3 1210, 16:10 1090, 1022.5 on a phone at k 1.15) | ring ⌀ 64·k (r 32, 3 px stroke) with two 6×22 bars 7 off centre, `ink1 @ .55`; cyan (`btnTop`) while paused. **Touch box:** `max(P, P·k)` on each side and never less than the ring plus 8 of slack, anchored at the cutout-safe top-right corner and extended to the screen edges (16:9: x 1808–1920, y 968–1080). Input, hint and the other modes: §5.12. |
| Difficulty chip + title | bottom-left: the chip's left edge under the score's (24 from VL plus the Settings edge padding, whatever the cutout; SL before), its bottom at SB, 50 tall (Kelvin 2026-10-20 22:12: 「surface 2那一串锚定屏幕左下角边缘」, §24.43; in the 21:17 round the row was centred in the bottom band, from the bottom edge up to the default ground line, §24.42) | × 1.25 since 2026-10-20 (§24.42): chip UI_BOLD 22.5 name + NUMBER 22.5 constant on the difficulty fill at α .70, padding 17.5, radius 12.5. Title: UI 25 α .60, 20 after the chip, max 700 and never closer than 32 to the time, ellipsis. (Chip 40 tall with caps 18, title 20 with 16 and 560 before.) |
| Time remaining | bottom-right: right-aligned at SR, centred on the chip row | NUMBER 25 `1:52` (20 × 1.25 since 2026-10-20 22:12: 「时间点的大小也放大」, §24.43), α .50 |
| Progress | along the top edge, flush with ST′, VL..VR, 1 % of the screen's height thick (`PROGRESS_SHARE`: 12 at 16:10, 10.8 at 16:9, 8.64 at 20:9, 14.4 at 4:3; Kelvin 2026-10-20, §24.42; 6 below ST′ and 3 thick before) | track `ink1 @ .12`, fill `groundSig @ .60` |
| **Combo** | centre (960, baseline 540) | NUMBER 96, α .30, shown from combo ≥ 3 (§5.5). Label `COMBO` UI_LABEL 18, α .40, baseline 504. In an autoplay run the label is `AUTOPLAY` instead (UI_LABEL 20, `auto` #9CF5B0, α .70, centred on baseline 504), from the first frame, with or without a combo (Kelvin 2026-10-20 05:57: 「然后autoplay放到中间。」, §24.31). |
| **Judgement word** | centre (960, baseline 444) | cap 32 (§5.6); EARLY/LATE tag to its right |
| Hit error meter (optional, off) | centre (960, 380), 320×24, at every aspect | §5.6 |

- The intro slide (§5.11) moves each corner in from its edge.
- On 20:9 the corners land in the x < 0 / x > 1920 gutters, which only lanes ±5 (old 0/9) use during bound animations.
- At UI 1.3 the pause ring is r 41.6 at (1844.6, 1015) on 16:9; its outer edge then sits 11.45 above the sky line, which is why `LINE_GUARD` is 8 (§5.1).
- **Open (owner):** the Settings edge padding moves the top corners down with the safe edges; at 16:9 the sky line comes within 8 of the score from a padding of about 25 (UI 1.0), and the corner then fades to α .15 (§5.1). Light corner text is also hard to read for the moment a bright note passes under it (a 1-unit dark shadow would fix it without a draw call). Neither is changed yet.

### 5.4 Band layout (4:3: y −180..1260; 3:2: y −100..1180)

**As built 2026-10-12:** the four corners of §5.3, at the same safe-edge anchors (4:3: score cap top 1236, pause ring at (1862, 1210), chip at SB = −156), so the top and bottom bands hold the corners clear of both zones. The progress bar runs along the top edge (flush with ST′ and 1 % of the screen's height thick since 2026-10-20, §24.42; 6 below ST′ and 3 thick before). Only one element is band-specific:
- **Bottom band** VB..12, centre `(max(VB, SB − 24) + 12) / 2`: **Live counters** [P1] `R 1,204 · E 31 · L 2` (UI_BOLD 22, judgement colours), centred. Setting "Live counters", default On, with no row in Settings since 2026-10-18 04:06: with the stretched field no landscape screen gets the band layout, so the counters never showed. Drawn in the top layer with the corner text.
- **Combo, judgement word and hit error meter** stay in the middle band, as in §5.3 (the meter no longer moves to the bottom band).

**Replaced 2026-10-12:** the top-band row (pause at the left centred on yb, chip + title right of it, accuracy + score NUMBER 44 on one row at the right), the progress hairline at y 1084–1087, and the time and the meter in the bottom band.

- **Note entry** (as built 2026-10-12, `gameplay/EdgeEntry`; owner 12:42 「第0刻的音符会露出一点在屏幕里面。我想要整个谱面出现得更自然」). A note **slides in from the screen edge**, at any aspect, when its first movement (its first movement event, or the default approach) moves toward its line at an average speed `v > 0`, its spawn `sp` (its distance when that movement starts) lies outside the 16:9 reference frame (ground `lineY + sp ≥ 1080`, sky `lineY − sp ≤ 0`, with the line's y at that beat), and the body it would draw at that spawn, in the pose of that beat and at the current note size, reaches inside the visible area. The body is the turned head (a mine's body with its studs), a tail turned toward the line, a LANE MINE column, a TIDE's pearls or wave, or a TIDE STREAM's bar and band; soft glows do not count. So the default spawn 972 (y 1092 / −12) slides in on 16:10, 3:2 and 4:3, where it lies inside the screen; on every screen, 16:9 and 20:9 included, a turned note whose corner would show inside on its first frame slides in; an upright note spawning just beyond a 16:9 edge keeps its body off screen and draws as before. Before its first movement it is drawn at `sp + v·(start − beat)`, at full opacity and with the angle, opacity and tail of the start beat, so it crosses the edge and joins its authored path at the start beat. Its slide begins with its leading edge 60 units beyond the screen edge (`EdgeEntry.MARGIN`, the largest draw-cull margin; every glow reaches less far), plus what a turned body reaches, with the line at its nearest to that edge on the way; before that it is not drawn. Its trail copies (§17) stream in from the edge behind it. Spawns inside the reference frame (deliberate on-screen appearances) and first movements that do not approach the line keep the fade-in (as built: over the first 150 ms after the first event, `NoteRenderer.FADE_IN_MS`, not over 80 units of travel). Drawing only: judging is untouched. The song clock starts before the first slide (§5.11).
- **Replaced 2026-10-12:** every note fading in over its first 80 units of travel whenever its spawn point was visible (`VT > 1052` or `VB < 28`), so on 16:10, 3:2 and 4:3 the notes appeared inside the screen, at y 1092 / −12.

### 5.5 HUD behaviour [P0]

- **Score.** Each judgement eases the displayed value to the new one over 120 ms. Digits come from cached glyphs; no strings are built. With FC / AR INDICATOR on (the default; Kelvin 2026-10-18 16:17, §9.3) its colour tells the run so far: `ALL_RESONANCE` light blue `#A6DDFF` while every judgement is RESONANCE, `FULL_COMBO` purple `#B49CFF` while the combo holds through ECHOs, `ink1` white once it broke (a LOST or a touched mine; `ScoreKeeper.isFullComboSoFar`). With the switch off it is always white; AUTO keeps its own colour.
- **Combo.**
  - Each increment: α .30 → .50, decaying back over 160 ms, plus a scale punch 1.06 → 1.
  - Every 100: scale 1.25 → 1 (`SwingOut(1.6)`), a G2 with face swap, and a `ring_thin` r 0 → 360 (400 ms `pow3Out`, α .25).
  - Break (combo ≥ 20): G2-red for 2 frames, then fades out over 300 ms. No shaming text.

### 5.6 Judgement feedback [P0 unless tagged]

**Central word.** Shows the newest judgement only.

| Judgement | Face | Colour | Extra |
|---|---|---|---|
| RESONANCE | NUMBER | `#66F0FF` | G1 |
| ECHO | DISPLAY_SOFT | `#B49CFF` | — |
| LOST | DISPLAY_OUTLINE | `#FF5C7A` at α .85 | G2-red |

- Size: cap 32.
- Motion: scale 1.2 → 1.0 over 0–60 ms, hold, fade over 300–420 ms. A new judgement restarts it.
- **Touched mine (owner 2026-10-12 00:42: a touched mine shows a red judgement).** A touched MINE, and every LANE MINE contact, shows the word `LOST` in `mineLost` `#FF2A3D` (redder than the LOST pink, so a missed note and a touched mine read differently), centre or at the lane per the Judgement display setting, with the LOST face and motion. It is feedback only: no judgement is recorded, the score and the resonance chain do not change, the combo breaks (§5.15), and there is no meter tick and no EARLY/LATE. **One touch, mine and note (2026-10-12):** when the same touch touches a mine and also judges a note (the engine emits MINE, then HIT, in one input event; e.g. a tap in a red LANE MINE lane that takes that line's TIDE), the centre word stays the red `LOST` that explains the combo reset. The hit still adds its hit error meter tick, and the next judgement replaces the word as usual (`GameplayHud.judgement`: the hit's word is skipped when the mine word was set at the same moment). With Judgement display = At lane each word shows at its own lane.

**EARLY/LATE tag:**
- To the right of the word (gap 16): UI_BOLD 20 `EARLY` `#6FA8FF` / `LATE` `#FFB067`.
- Setting: Off / **ECHO only** (default) / All.
- "Show ms" appends ` −23 ms`.

**Per-lane micro feedback,** just outside the zone edge (ground y 240, sky y 840), at the lane x:
- **ECHO:** an 18×10 chevron in the EARLY or LATE colour.
  - EARLY points toward where the note came from (ground ∧, sky ∨); LATE points the other way.
  - It rises 12 units away from the line over 300 ms and fades.
- **LOST:** a `×` from `HitFx.lost` (§5.9) at the same spot, plus the line segment under the lane flashing `#FF5C7A @ .40`, decaying over 200 ms.

**Judgement display setting:**
- Centre (default).
- At lane: the word at the lane x, 24 outside the zone edge, cap 22, 300 ms.
- Off.

**Hit error meter** [P1] (optional, default off):
- 320×24 bar on a ±133 ms scale, with the inner ±66 band shaded.
- The last 12 hits are 2×14 ticks in their judgement colours, fading over 2 s, plus a median caret.

### 5.7 Judgement lines and lane field [P0 unless tagged]

| Part | Spec |
|---|---|
| Core | 4 px, line colour (sky `skySig`, ground `groundSig`) at α .95, across the current bounds. Geometry follows chart events (JudgementUp/Down, Left/RightBound); the zones follow the animated lines. |
| Glow | `glow_v` stretched to 48 tall, additive, α .18. Height × 1.0 at combo 0–49, × 1.15 at 50–99, × 1.3 at 100+. Beat pulse: α +.10, decaying over 180 ms. |
| Zone hint | the touch zone (±0.4 of the height since 2026-10-12, `Field.zoneHalf`) as two gradient quads: line colour α .07 at the line → 0 at the zone edge (`FieldRenderer.ZONE_HINT_A`; α .035 until 2026-10-18 19:52, which over the wider zone left no visible tint) (setting "Zone hint", default On) |
| Lane ticks | at each lane boundary, a 2×20 tick crossing the line, `ink1 @ .75` |
| Lane separators (owner 2026-10-11 22:40: "clearer") | full-height separators between the two lines at every lane boundary: 2 px `ink1`, α .34 at each line easing to .16 at mid-field (quadratic; built as 6 linear pieces), plus a 10 px soft glow `ink1 @ .05`; × lane opacity (the brighter of the two lanes sharing a boundary). Drawn in normal block A, under the notes. Replaces the earlier 300-unit fading guides (built 2026-10-12). |
| Approach glow [P1] | in the last 250 ms before a note arrives in a lane, that lane's line segment brightens, α 0 → .25 |
| Hit ripple (particle quality High) | the line is drawn as 48 segments. Each DROP / STREAM-head hit adds `A·exp(−((x − x0)/120)²)·sin(28t)·(1 − t/0.22)` with A = 4 px and t in s; at most 4 ripples per line. A TIDE hit has no ripple: the whole line swells instead (§19.2). |
| Hit bead (Low / Off) | a soft 40 px bead that spreads ±120 along the line over 200 ms |

### 5.8 Notes [P0]

- **Drawing.** `note_9p` NinePatch with rotation `currentAngle` (+180° for sky notes, so the leading edge faces the line).
- **Pivot (Kelvin 2026-10-16 13:50, 13:52, approved 14:15; CHART_FORMAT §6.1.1).** A note turns about its pivot: `Note.pivotX/Y`, in reference units from its origin in its own frame (+y away from its line; a sky note's frame is turned 180° with it), stretched by `kx`/`ky` but not by note size. Origin: the centre of a DROP or MINE, of a STREAM head, of a LANE MINE's leading 32 units (its base; the column left out), of a TIDE STREAM head bar; for a TIDE, the middle of the on-screen part of its bar, halfway up the 18-unit bar. Default = the origin (charts without `pivot` turn about the centre); `(0, −20)` (a MINE or LANE MINE `(0, −16)`) is the old turn about the leading edge, exact at note size 1.0 on 16:9. `NoteShapes.pivotShiftX/Y` place lane notes and mines, trail copies and `EdgeEntry` follow. TIDE and TIDE STREAM turn too (`NoteRenderer.tideTurn`: one transform about the pivot), drawn as if infinitely long: the bar is stretched by `1/cos` (|cos| ≥ 0.2) so that, turned, it still crosses the window, each lane's pearls and wave staying above that lane; a turned band takes no part in the sky/ground overlap knock-out.
- **Size.** `laneWidth × 5/6 × min(noteSize, 1.15)` × `40 × noteSize`, where `noteSize` is the Settings value (0.8–1.3, default 1.0). At default that is 200×40.
- **Anchor (Kelvin 2026-10-13: a row of sky keys and a row of ground keys at the same distance from their lines must line up at any note size and screen ratio).** A chart distance is measured to the near edge of the reference body (note size 1.0, 16:9; 40 tall, a MINE 32). Away from the line the drawn body is centred on that reference body's centre after the playfield stretch, so a DROP, STREAM head, MINE or LANE MINE moves by `refH/2·k − h/2` along its own axis (`k` = `ky` along the travel, `kx` across); within 300 units of the line this eases back (smoothstep), so the near edge still touches the line at the judgement time. Nothing moves at note size 1.0 on a 16:9 screen; TIDE notes are not affected. `NoteShapes.centreShiftX/Y`; trail copies follow.
- **VISUAL HIT POINT 目押校准 (Kelvin 2026-10-20 03:20, §24.29).** `Settings.hitPointPx` (−100..+100, default 0 since 2026-10-20 22:37, §24.44; 20 before) is the distance from a TAP or HOLD key's midline to its line at the judgement time, in units of note size 1.0: 0 puts the line through the key's middle (「有些人目押的时候习惯等判定线横穿音符的时候判定」), so at the default the midline is what travels the approach to the line; 20 is the near edge on the line, as before the setting existed. Every note is drawn `(20 − hitPointPx) × noteSize` further along its path (`NoteRenderer.hitShift`, taken off `drawPos`): DROPs, STREAMs (a held head stays that far past the line), MINEs, LANE MINEs, TIDEs and TIDE STREAMs alike, with their trail copies (`TrailGeometry.setShift`) and the tutorial's TIDE caption, so every note still reaches its own hit point at the same moment as the others. Drawing only: judging, sounds and the song clock are untouched. Set on VISUAL (§9.2) or measured on its own screen (§9.7).
- **Fill.**
  - line colour, α .92 at the leading edge → .55 trailing
  - 3 px white leading edge
  - 2 px dark rim
  - High quality: `note_glow` at α .22 (additive block)
- **Sync notes — the gold square (owner 2026-10-12 03:08: 「sync的就在中间的部分加一个金色的正方形。其他的都不变」).** A gold `sync` square sits in the middle of a **pending** DROP or STREAM head: side 18 × `noteSize` (45 % of the note height), a 2 px `syncRim` rim, turning with the head and taking its opacity; nothing else on the note changes. **Rule: the square shows only when a second finger is needed:** another DROP or STREAM head on the same line and beat (`Note.syncLanes()` > 1), or a judged note (TIDE included) on the other line at the same beat (`Note.syncAcrossLines()`). A DROP whose only same-beat partner is a same-line TIDE has no square, because one tap takes both (joint judging, ARCHITECTURE §20). Never on mines, TIDE notes or afterimage copies; it leaves with the head's judgement. The head's additive glow is drawn as a 3×3 grid without its centre (8 quads), so the square stays true gold (`#FFD36B`, not washed to `#FFFFA3`) on cyan and pearl heads. **Replaced:** the 3 px `sync` outline and the 2 px `sync @ .5` connectors between simultaneous notes on the same line.
- **Sync highlight (Kelvin 2026-10-13 06:53; look settled 12:02–14:23).** Notes that share a beat (the gold-pearl rule, `NoteShapes.showsPearl`) get an outline in the additive block. The `SYNC HINT` setting (§9.2) picks OFF, ON (the outline, default) or ENHANCED (outline and gold pearls); named GLOW / GLOW + PEARLS / OFF until 2026-10-18 04:34.
  - DROP, STREAM and TIDE STREAM heads: a solid border reaching 8 beyond the body and starting 3 inside its edge, plus a soft halo 40 wide. That is about half the old high-opacity glow's border, which reached 16 (「边框再加粗一点，大概相当于之前原版亮度变化中的边框的一半粗」). Ground notes use white `SYNC_HIGHLIGHT` #FFFFFF, border α .9 and halo .6. Sky notes are white, so they use the deep gold `SYNC_HIGHLIGHT_SKY` #FF8000, border 1.0 and halo .3, which stays gold beside a white body (14:41: 「除了天键的用金色以外其他的都用白色高亮吧」).
  - MINE and LANE MINE heads: the same frame in `MINE_HIGHLIGHT` #FF8A80 at 30 % strength (「红区的sync弄得很暗就行了」; 14:48 「水雷保留浅红色」).
  - Sky TIDE: a ring 6 beyond each pearl and a halo 28, in the sky gold. Ground TIDE: a white border 8 and halo 40 along the wave's crest.
  - The outline takes the note's shown opacity, so it fades and flashes with the note (「opacity变化的时候要注意边框和note要一起变透明度」; flashing above 1: CHART_FORMAT §6.1).
- **Holds.**
  - Head: drawn as a tap note.
  - Body (**as built, §21.7**): ribbon 82 % of the lane on the ground line and 56 % on the sky line, line colour fading head → tail (α .42 → .16; held .62 → .30), bright sides (ground up to 8 px, a quarter of the ribbon when narrower; sky 6 px), a 3 px tail cap in the ribbon's own colour at α .8. Where a ground ribbon overlaps a sky ribbon of the same lane, the ground fill leaves a hole under the sky ribbon. Length comes from the model (`Note.tailHeightAt`: the default rule, or `HeightEvents`, §20.1). **Replaced:** ribbon α .35 / held α .60 and the equal-width sky ribbon.
  - While held: the fill above, head glow, and a gradient pulse quad sliding along at 300 /s.
  - Early release or break: desaturate to `#6C7899 @ .30` over 200 ms (ribbon α .30 → .12 head → tail).
- **Missed (LOST) notes.** Desaturate toward `#6C7899`, jitter ±3 px for 2 frames, then the existing miss fade.
- **No glitch on any note before it is judged.**

### 5.9 Hit effects (FX) [P0]

**Loading and pools:**
- Load with `ParticleEffect.load(file, atlas)` using the shared `Draw` atlas (regions `ring_soft`, `soft_dot`, `streak`). Keep the prototypes and dispose them.
- Two pools per effect, ground and sky. The sky pool mirrors angles and gravity at load. Initial 8, max 24 each.
- X/Y offset 0 (removes today's +50/+50).
- All emitters additive, drawn in the one additive block (§5.1).
- Updated with game delta; frozen while paused.

**Burst recipe** (`.p`, emits exactly N on the first update): `Duration 1–16 ms`, `Count min = max = N`, `Emission 0`, `continuous: false`. `background particles.p` is retired; Backdrop snow replaces it.

| Effect | Emitter | Values |
|---|---|---|
| **RESONANCE tap** | `ring` | count 1 · life 280 · scale 0 → 260 (curve 0.30 / 0.85 / 1.0 at t 0 / .35 / 1) · tint `#66F0FF` · α 1 / .55 / 0 at 0 / .4 / 1 · `ring_soft` |
| | `core` | count 1 · life 160 · scale 0 → 90 · white · α .9 → 0 · `soft_dot` |
| | `sparks` | count 10 · life 220–320 (independent) · spawn line width 120 · velocity 450–900 · angle 25–155° (ground) · gravity −900 (ground) · X scale 22 → 0, Y scale 6 → 2 · aligned · tint `#F2FFFF` → line colour · α 1 / .8 / 0 at 0 / .6 / 1 · `streak` |
| **ECHO tap** | `ring` | life 240 · scale 0 → 200 · tint `#B49CFF` · peak α .8 |
| | `echo ring` | **delay 70 ms** · life 260 · scale 0 → 240 · peak α .45 (the "echo") |
| | `sparks` | count 6 · velocity 300–600 · life 200–260 · tint `#B49CFF` → line colour |
| **Hold** | `stream` | continuous · **Duration 1000** (never 0) · count 0/40 · emission 22/s (High) / 12/s (Low) · life 260–360 · spawn line 100 · velocity 120–260 · angle 60–120° · scale 12 → 0 · α .9 → 0 · tint line colour (RESONANCE head) or `#B49CFF` (ECHO head). End with `allowCompletion()`, never `free()`. |
| **Hold seal** (completed hold) | — | the head grade's tap effect at 80 % scale |
| **LOST** (`HitFx.lost(x, y, s)`) | `x` | 2 `streak` particles rotated ±45°, 48×6, life 220, no motion, tint `#FF5C7A`, α .8 → 0. The caller places it at y 240 / 840. |

- **Quality levels:**

  | Level | Behaviour |
  |---|---|
  | High | everything above, plus line ripple |
  | Low | sparks × 0.5, no core, no echo ring, hold 12/s, bead instead of ripple |
  | Off | no HitFx; line bead and flash remain |

- **Auto-drop.** If quality is High and average fps < 55 over 3 s of play, switch to Low silently for the rest of the session. Persist it; only Settings tells (the particle quality description turns gold, §9.2). The result screen's "EFFECTS SET TO LOW FOR SMOOTHER PLAY" (中文 「为了更流畅，粒子质量已自动调低」 since 2026-10-18 21:37) was removed by Kelvin 2026-10-19 02:24 (§24.18).
- **Budget.** RESONANCE is 12 quads, ECHO 8. The worst case (12 hits/s + 4 holds) is under 100 live quads, versus about 1,650 today.

**FX tuning (as built, binding; replaces the table above where they differ).** Tuned at 1920×1080, 2400×1080, 2048×1536 and 1280×800; the `.p` files in `assets/ParticleEffects/` hold these values (Theme recolours them at load). The budget is unchanged: RESONANCE 12 quads, ECHO 8. Pools are pre-filled (ARCHITECTURE §9: 18 pools, 224 warmed instances) instead of "initial 8".
1. **RESONANCE ring:** `ring_thin` (a crisp 2 px wavefront) at size 260, crest at r ≈ 125, plus a `ring_soft` **halo** (α .42, size ≈ 553 so its crest sits just inside the ring). Life 300; scale curve **.42** / .76 / .92 / 1 at t 0 / .25 / .55 / 1; α 1 / .82 / .38 / 0 at 0 / .3 / .62 / 1. The .42 start (spec: .30) keeps the thin ring ≥ 130 units from its first visible frame, so it does not alias on ≤ 0.7 px/unit screens. Why: `ring_soft` alone has its crest at 0.21 × size (≈ 55) and read as a small fuzzy bubble. Quads: halo + ring + core + **9** sparks = 12.
2. **Core:** `soft_dot` size 110, growing .45 → 1 by t .35, white, α .9 → .55 (t .4) → 0, life 160.
3. **Sparks:** 9 `streak`s, velocity 630–1260 (×1.4, so they still reach the larger ring) easing to .25; X scale 32 → ≈ 5, Y scale 8 → 3.6; gravity −900 ramped in over the life (libGDX gravity is a velocity offset, not an acceleration); spawn `square` 120 × 0 (libGDX `line` spawns from a corner, not the centre); angle 25–155° (mirrored for the sky line).
4. **ECHO:** `ring` = `ring_thin` 220, life 260, α 1, scale curve **.52** / .80 / .93 / 1 (same reason as 1); `echo ring` = `ring_soft` with its crest at r ≈ 74, delay 70 ms, life 260, peak α .45; 6 sparks, velocity 420–840, life 200–260. Total 8 quads.
5. **Hold stream:** `soft_dot` size 40 (scale .75 → 1 → .35) instead of 12 → 0 (12 px dots were invisible); tint from a bright head (line colour 60 % toward white) to the line colour (ECHO head: `#B49CFF`); α holds (.9 → .77 at .5 → 0) instead of a linear fade. Everything else as specified: Duration 1000, count 0/40, 22/s High and 12/s Low, life 260–360, spawn 100, velocity 120–260, angle 60–120°.
6. **Hold seal:** the head grade's **Low** burst at 80 % scale (`SEAL_SCALE`; RESONANCE ring + 5 sparks = 6 quads, ECHO ring + 3 sparks = 4), not the full tap burst: 4 holds completing together on top of 12 taps/s now peak at 92–94 quads instead of 122–124.
7. **Budget guard:** a burst that would take the estimated live total past **90** steps down to its Low variant, then to its ring alone (1 quad). The ring is never refused, so no judgement loses its cue. The worst case above never triggers it; 6-note chords every second on top of it peak at 93–98 quads.
8. **LOST ×:** four `streak` arms at 45°, 135°, 225° and 315°, each 48 × 6, life 220, no motion, `#FF5C7A`, α .8 → 0 (4 quads). The spec's two streaks rotated ±45° made a lopsided × because the `streak` region is bright at one end. A missed hold head gets no stream and no seal (`holdStart` returns −1).
9. **Low quality** also drops the RESONANCE halo: RESONANCE Low is 6 quads, ECHO Low 4.
10. **Mine shock** is drawn by gameplay's `FeedbackLayer` in the same additive block, at every quality level (FX's duplicate `HitFx.mineShock` was removed at integration): one `ring_soft` tinted `mine`, r 30 → 150 over 220 ms `pow2Out`, α .7 → 0 (as §5.15), at every MINE touch and every LANE MINE contact. **Replaced:** the TIDE sweep (two bars running from the touch x to the window ends). A TIDE hit has no point effect at all; its effect is the tide wash of §19.2 (`gameplay/TideWash`, normal block A), which never calls `HitFx`.
11. **Auto-drop:** ignores the first 1 s after `clear()` (`AUTO_DROP_WARMUP_SEC`; song-start hitches never count), then averages consecutive 3 s windows of frames with delta > 0 against 55 fps. It is **not re-armed** in the same session if the player sets High again. `Main` persists LOW and `particlesAutoDropped` without a toast; nothing else shows it (the result screen's notice and `consumeAutoDropNotice()` went on 2026-10-19).
12. **Water rings (as built 2026-10-12, §20.5):** DROP and STREAM heads add the water rings to the bursts above (`fx/WaterRings`, one quad per ring, drawn by `HitFx` in the same additive block, no new draw call). `HitFx.tap` adds them to a DROP's burst (and to every other caller's tap: Settings live preview, calibration, the tutorial's grade demos); `HitFx.ripple` plays them alone for a STREAM head, whose burst is the hold stream. A completed lane STREAM still gets the seal (item 6); a TIDE STREAM end gets no seal. The hold stream rate has one source, `HitFx.holdRate(quality)` (22 / 12 / 0 per s), which the TIDE STREAM light bands share (§20.3). Rings have their own budget (5 ring quads per RESONANCE hit at High, about 30 live in the worst case; a RESONANCE set steps down to the Low pair, then to ring 0, when more than `RING_GUARD` = 60 rings are alive). The FX-lab worst case (12 taps/s + 4 streams + chords) is particles < 100 plus up to 33 ring quads.

### 5.10 Lane touch feedback [P0]

For every pointer down inside a zone (keyboard lanes included):
- **Column glow:** `glow_v` stretched lane-wide from the line 220 units into the field. α .20 with 40 ms attack; held while the pointer is down; release fade 160 ms `pow2Out`. During an active hold: α .28 with a 2 Hz shimmer ±.04.
- **Contact ring** at the finger x on the line: r 40 → 90 over 160 ms, white α .30. An empty tap (nothing judged) gets α .15. **Not for a judged TIDE hit** (2026-10-12; §19.2 v3 "no point effects for TIDE"): a TIDE drop or TIDE STREAM head hit draws no contact ring and no brackets at the finger. Lane hits, empty taps (e.g. the new finger of a TIDE STREAM swap) and the column glow under a held pointer are unchanged.
- **Contact brackets:** two 2×24 ticks at the *resolved* lane's edges on the line, `ink1 @ .8`, 160 ms. They show which lane the touch resolved to, including the neighbour-lane tolerance (ARCHITECTURE §10).

**Stray touches** (outside both zones and not on a control):
- a grey dot, 24 px, `ink1 @ .15`, 160 ms
- Only in the tutorial since 2026-10-18 19:52 (its zone lessons show them). Before, the setting SHOW TOUCHES drew them in every game (On / Off from 04:06; until then STRAY-TOUCH DOTS Auto, on while `profile.playCount < 5`, / On / Off, and a saved Auto reads as Off), and with the zones at ±0.4 of the height (2026-10-12) they almost never showed (19:52 「show touches还不起作用」).

**SHOW TOUCHES** (setting, default Off; Kelvin 2026-10-18 19:52 「触控点要明显一点」; `gameplay/TouchMarks`): a mark under every finger that is down, following it: a white ring (radius 64, 5 px, `ink1 @ .85`) with a faint fill (`ink1 @ .14`) and a centre dot (radius 8, α .9). On release the ring widens by a third and fades over 250 ms. Drawn over the notes and hit effects, under the corner HUD; positions come from the engine's pointer table once a frame.

### 5.11 Start sequence and countdown [P0]

**As built 2026-10-12, 16:06** (owner 16:06 「quiet buffer是指，前三秒倒数，然后0.3秒的buffer什么都不做。然后剩下的从乐曲开始得地方计时。比如说乐曲最开始得时间戳是-0.5秒（第一个按键在0秒，但是第一个按键从顶部落下来要0.5秒），那么顺序就是：1. 3秒倒计时，除了倒计时什么都没有。2. 0.3秒buffer，屏幕上除了背景什么都不显示，倒计时也没有。3. 游戏开始，从-0.5这个时间戳开始，音符出现在屏幕边缘然后开始下落。也就是说到第3+0.3+0.5=3.8秒音符才正式落到判定线上。」; Kelvin then chose to keep the stage visible during the countdown and the buffer, with only the notes waiting).

t = 0 is when the DIVE open completes. The run begins with a **3.3 s pre-roll** of wall time (`RunOverlay.PREROLL_SEC` = 3 × `COUNT_SEC` 1.0 + `BUFFER_SEC` 0.3, kept by `GameplayScreen`) while the song clock does **not** run: no note is drawn, nothing moves, nothing is judged. The stage shows throughout: background, judgement lines, lanes and the corner HUD (song name and difficulty stay in the corner; there is no intro card). At t = 3.30 the game starts: the song clock runs from the **start time** `min(0, floor(first visibleFrom) − 1)` ms (`GameEngine.leadInMs()`, `START_EPS_MS` 1), which the screen moves to 1 ms before the earliest moment any part of any note is drawn when that is earlier (a slide in from the screen edge, §5.4, its trail copies, and the audio / visual offsets; `GameplayScreen.startBeforeFirstNote`), so the first frame of the game shows no part of a note. The notes appear at the screen edge and fall; the music starts when the clock crosses song time 0, at t = 3.30 − start / 1000. In the owner's example (a first note falling for 0.5 s onto the line at song time 0) the clock starts at −501 ms and the note lands at t ≈ 3.80; Song 1 SURFACE starts at −661 ms (its first notes are visible from −659.3 ms), so its first notes land at t 3.961. A chart with no note drawn before song time 0 starts its clock at 0: the music starts at t 3.30.

| t (s) | Event |
|---|---|
| 0.00–0.35 | lane ticks pop in from the centre outward (15 ms stagger); zone hint fades in |
| 0.10–0.40 | HUD clusters slide in from their edges (300 ms `pow3Out`) |
| 0.00 / 1.00 / 2.00 | countdown `3` / `2` / `1`, one second each (`RunOverlay.COUNT_SEC`), keyed to the pre-roll: NUMBER 132 at centre; scale 1.2 → 1.0 over the first 333 ms (`pow3Out`), α 1 → 0 over 917 ms, G2 on appear (250 ms); a `ring_thin` contracts r 600 → 0 onto the centre over the whole second (`pow2In`), so the `1` and its ring are gone at t 3.00 |
| 3.00–3.30 | buffer (`RunOverlay.BUFFER_SEC` 0.3): the stage only, no countdown, no notes |
| 3.30 | the game starts: the song clock runs from its start time, judging opens, notes appear at the screen edge and fall |
| 3.30 − start / 1000 | song time 0: the music starts |

- **Pause during the pre-roll** (BACK / Esc / Pause, the pause control, the app going to the background): the menu opens over the waiting stage; RESUME starts the pre-roll again from the `3` (no resume countdown). A pause after t 3.30 resumes with the 3 × 0.6 s countdown of §5.12, unchanged.
- The tutorial runs the same sequence; its first captions (beat 0, song 27 ms) fade in after the game starts.

**Replaced 2026-10-12 16:06:** the intro card (t 0.20–1.20: layer chip, title, artist and difficulty chip) and the lead-in on song time: `GameEngine.LEAD_IN_MS` 3300 (`SongClock.prepare(−3300)`, `min(−3300, the first visibleFrom)`), the countdown at song −2100 / −1500 / −900 ms (t 1.20 / 1.80 / 2.40, 0.6 s steps) and the quiet buffer at song −300 … 0 while the notes already moved. Before that (12:42): `DIVE` (RUBIK 56, G2) flashing for 300 ms at song time 0 after a 3.0 s lead-in.

### 5.12 Pause [P0]

**As built 2026-10-12 (owner decisions; replaces the panel menu and the hold-to-pause default).**

**Pause input** (`gameplay/PauseControl`, pure Java). The control is the top-right ring of §5.3.
- **Double-tap (default, Settings → PAUSE INPUT, §9.2):** two touch-downs in the pause box within **300 ms** (by the events' own timestamps) pause; the **second down** triggers. The first counted down plays a 300 ms **hint**: ring and bars brighten, a cyan arc 6 outside the ring drains clockwise with the double-tap window, and a thin 2-unit ring pulses out 24 (all × the ring's scale). While paused the ring is cyan.
- **Every touch reaches gameplay first.** The engine sees each touch before the pause control, so a touch inside a judgement zone always judges, also under the corners. A down **does not count** (and does not cancel a pending first tap) when it judged a note or a TIDE, re-grabbed a hold, covers a running TIDE STREAM of its line, or **touched a MINE or LANE MINE** (such a down has already cost the combo). Downs outside the box neither count nor cancel; drags and resting fingers never count; a pending first tap is dropped on pause, resume and a new run.
- **Hold 0.35 s** and **Single tap** stay as options under the same counting rule: a counted down arms them. Hold pauses after 0.35 s in the box, with a 4-unit `ink1` ring filling clockwise 6 outside the icon (released early it drains in 150 ms); it disarms when the arming finger leaves the box, lifts, grabs a hold or covers a running TIDE STREAM. Single tap pauses on release inside the box.
- **Keys and the app.** BACK, Esc and the keyboard's Pause/Break key pause at once, and so does the app going to the background (`Screen.pause()`). **P is not a pause key:** it is lane 4 of the upper row (U I O P [), so pausing on P would break keyboard play.
- Saved profiles are not migrated: a profile saved before 2026-10-12 keeps `pauseMode` 0 (Hold) until the player picks DOUBLE-TAP.

**Pause menu, variant B** (`gameplay/PauseMenu`; owner 2026-10-12 00:58 「然后用方案B」 and 23:35 「暂停菜单东西太多了。简洁一点」; mocks P2 / P4 / P5).
- **Look.** No panel: the field dims behind `scrim @ .70` and three big buttons sit in a row at the centre: **RETRY 重开** (left, 220×220), **RESUME 继续** (centre, 300×258, filled cyan like a primary button) and **QUIT 退出** (right, 220×220); gap 44, corner radius 18, all × UI scale (shrunk only if the row would leave the safe area). Each button: an icon (48; RESUME 56) and its label (UI_BOLD 26; RESUME 30) in the interface language, RETRY / RESUME / QUIT in English and 重开 / 继续 / 退出 in 中文, never both (2026-10-18 17:22, §24.16; `SUBLABELS_ALWAYS = false`, true would draw the Chinese sublabel (18) under the English labels as before).
- **The corner HUD stays lit** above the scrim (§5.1 top layer). The open menu costs 6–8 draw calls (the old panel cost 11).
- **Motion.** Opens in 200 ms (`pow3Out`, fade and scale .96 → 1); ignores touches for its first 80 ms (the finger that paused). Every button acts on release.
- **Keys.** BACK, Esc, Pause and Enter while paused = RESUME.
- **Replaced:** the 760×600 panel with `PAUSED`, the song line, progress, the offset row (output + ±5 ms nudges), the Music / Hit sound mini sliders, and the note legend under the panel. Offsets and volumes live in Settings; the note legend left the pause menu, and it became the Settings NOTE GUIDE panel (§9.2, gone since 2026-10-18).
- **Resume:** a 3 × 0.6 s countdown (same visuals) with notes frozen. A hold detached by the pause can be re-grabbed within 100 ms after the resume (ARCHITECTURE §10).
- **Judgement is blocked** while paused and during any countdown.
- RETRY restarts the run; QUIT returns to the map through the IRIS.

### 5.13 End [P0]

- **End banner** (Kelvin 2026-10-17 03:58: 「最后一个按键结束的时候不要着急出现clear，等到音乐结束/谱面结束后1秒再用大一点的字显示，带上点glitch特效」): at `max(lastNoteEnd, music end) + END_BANNER_DELAY_MS` (1,000), held `END_BANNER_MS` (2,000). RUBIK 104 (was 56; narrowed to fit 86 % of the world width), scale punch 1.25 → 1 over 0.35 s, fade in 0.15 s and out 0.3 s. Glitch: a G3 burst on entry (0.4 s, with screen tears), G1 shimmer and short G2 bursts about every 0.6 s while it stays, a G2 burst on the way out. Scrim strip 220 tall. Nothing is shown between the last note and the banner except the HUD.

  | Run | Banner |
  |---|---|
  | All RESONANCE | `ALL RESONANCE` (light blue `#A6DDFF` since 2026-10-17 16:02, §23.5; was iridescent) |
  | No LOST and no mine touched | `FULL COMBO` (purple `#B49CFF` since 2026-10-17 16:02; was `#7FE7FF`) |
  | Otherwise | `CLEAR` (`ink1`) |
  | Autoplay | `AUTOPLAY COMPLETE` |

  FULL COMBO and ALL RESONANCE also get a full-screen ping (maxR 1,400) and a bio burst.
- **Music** plays to its end; it does not fade (Kelvin 2026-10-16: 「等音乐和谱面都结束了再结束」).
- **ASCENT** starts when the banner has gone: at `max(lastNoteEnd, music end) + BANNER_FINISH_DELAY_MS` (3,000) (`GameEngine.endMs()`, `finishAtMs()`, `setFinishDelayMs`). The music end is the WAV/MP3 length minus the song's audio offset; when the length is unknown, only `lastNoteEnd` counts.
- **No pause after the end** (2026-10-17): once the music and the notes have ended the pause control does nothing, so a stray double tap during the banner cannot lose the record.
- **Quitting during the outro** (pause → QUIT after the last note) records nothing, as before (Kelvin 2026-10-16).

### 5.14 Hit sound slot (reserved, not configured) [P0]

- **Files.** `audio.hitSound()` loads `assets/Sounds/hit.(ogg|wav)` if present. Optional variants: `hit_echo.*` (played for ECHO instead of `hit`), `hold_end.*` (a completed STREAM), and `mine.*` (a touched MINE, §5.15; a mine never plays `hit`). All four load together; the owner-facing description is `assets/Sounds/README.txt` and `docs/ASSET_SLOTS.md`.
- **Priming.** Primed at load with a volume-0 play, to avoid the SoundPool first-play delay. Gameplay calls `hitSound()` in `begin()` while the DIVE covers the screen, so the first judgement never waits for a load.
- **When it plays.** `playHit(j)` is called **inside the input handler** right after judgement, not on the next frame.
  - Mode **On judgement** (default) or **On any zone tap**.
  - Volume: `sfxVolume`.
- **Absent files.** Silent when missing. The Settings slider shows "NO HIT SOUND INSTALLED" and is disabled.


### 5.15 Lanes v2, mines and universal notes [P0 unless tagged]
Model and judging rules are in ARCHITECTURE §13. Visual rules:

**Lanes.**
- Lane ticks, separators (the old guides, §5.7), column glow, approach glow and contact brackets use each lane's *current* span from `LaneLayout`, so they follow width animations frame by frame.
- Every lane visual is multiplied by the lane's `opacity`. A lane at opacity 0 still exists and is still playable; only its furniture disappears.
- Notes in a lane use the lane's current screen width in the §5.8 size rule (`laneWidth × 5/6 × …`), so notes in a narrowing lane narrow with it.

**Names.** Shown in the tutorial, result stats and the future editor. Owner's choice (2026-10-11), all from `NoteType`: DROP 水滴 (tap), STREAM 水流 (hold), MINE 水雷 (mine), TIDE 潮汐 (universal); with a duration, TIDE STREAM 潮汐水流 (universal, §19) and LANE MINE 轨道水雷 (mine, §20.2). **Replaced:** "pause-menu legend" (the pause menu has no legend since variant B, §5.12).

**Mine** (new tokens: `mine` `#FF3344`, `mineCore` `#2A0610`):
- Body: rounded bar **70 % of lane width** (the touch box), 32 tall × `noteSize`, fill `mineCore @ .92`, 3 px rim `mine`.
- Spikes: three 6×6 studs on the leading and trailing edges at ¼, ½, ¾ width, `mine`.
- **Turning (owner 2026-10-12: mines behave like DROP / STREAM):** a mine turns with its `RotationEvents` (`angleAt`, +180° on the sky line) like a lane note, and its afterimage copies turn with the angle of their own moment (§17.1). **Replaced:** the axis-aligned mine.
- Pulse: `note_glow` tinted `mine`, α .18 ↔ .34 at 2 Hz (additive block). It never glitches before it passes.
- Approach warning [P1]: in its last 250 ms, the lane segment of the line glows `mine @ .20` instead of the normal approach glow.
- **Dangerous window: ±66.67 ms** around its time, the RESONANCE window (owner 2026-10-13 21:55: 「普通水雷生效范围为+-66ms」; `Params.MINE_WINDOW_MS`; ±100 ms on 2026-10-12–13, ARCHITECTURE §13.2).
- Passed untouched: fades to α 0 over 120 ms after crossing the line, with no FX and no sound.
- Touched:
  - a `mine` shock ring at the touch x on the line, r 30 → 150 over 220 ms, α .7 → 0 (one `ring_soft`; quality Off keeps it);
  - the lane's line segment flashes `mine @ .45`, decaying over 220 ms;
  - the combo break of §5.5 plays at **any** combo (not only ≥ 20), G2-red for 2 frames;
  - the mine body cracks: it jitters ±3 px for 2 frames and fades over 160 ms;
  - **the red `LOST` word** in `mineLost` (§5.6; owner 2026-10-12 00:42). If the same touch also judges a note, the centre word stays this red LOST (the hit only ticks the meter; §5.6). **Replaced:** "no central judgement word". Still no EARLY/LATE and no hit sound;
  - optional sound slot `assets/Sounds/mine.(ogg|wav)`, reserved like §5.14 and silent when absent; it plays for every MINE touch and every LANE MINE contact.

**Universal (TIDE 潮汐)** (new token `tide` `#8CFFD9`):
- **TIDE drop body: the T4 look (owner 2026-10-12 04:36, 「天键地键tide的改为T4」; numbers from the approved mock-ups).** One shape per group across the **current window** `[left, right]`, inset 12 at each end; `u` = the note size setting; "behind" is the side the note comes from.
  - Sky line, a string of pearls: a 2u thread at α .45, 7u behind the leading edge, carrying 3 beads per lane alternating r 11u / 7u in `skySig`, each over a soft glow (4 bead radii, α .35).
  - Ground line, a rolling wave: crest at 20u + 9u·sin(π·x / (laneW/2) + 6·t) behind the leading edge (t = song seconds, frozen while paused; phase from the window's left edge with the mean lane width, so it does not jump when lanes resize); fill `tideWave` `#1FBF98` at α .85 in 8 strips per lane, crest line `tideWaveCrest` `#C8FFF0` 3u, leading-edge base line `tideWaveBase` `#EAFFF8` 3u; glow under the wave (64u, α .30) at particle quality High only.
  - TIDE heads draw **first** in the note pass, so lane notes and mines stay on top of the wave or the pearls (a joint DROP stays visible).
  - Afterimage copies use T4 at the copy's song time (129 quads per ground copy, 25 per sky copy at 8 lanes).
  - **Replaced** for TIDE drops: the 18-tall bar with the `tide` α .70 → .30 fill, white leading edge and the 10×10 lane-boundary diamonds. A **TIDE STREAM head keeps that bar** (§19.1), in `tide` on the ground line and in the pearl gradient on the sky line (§21.6).
- High quality: `glow_v` along the bar of a TIDE STREAM head, `tide @ .20`, additive.
- Approach [P1]: in its last 250 ms the whole zone hint of its line brightens toward `tide @ .06`.
- **Hit (replaced by §19.2):** no point effect at the touch x (no burst, ring, ripple or contact ring); the tide wash floats a band of light off the line and the line swells. **Replaced:** "the normal tap FX of the judgement at the touch x, plus a tide sweep … the line ripple uses A = 6 px".
- **Sync rule (replaced 2026-10-12):** a lane note on the same line and beat as a TIDE is **judged with it** by one tap (joint judging, ARCHITECTURE §20), so that pair needs no mark and shows no square; the gold square of §5.8 appears only when a second finger is needed. **Replaced:** "a lane note at the same time on the same line gets the gold outline and a connector to the nearest point of the tide bar".

**Result.** The stats block (§6.2) has a `MINES` row with `minesTouched / minesTotal` in `mine` when > 0, else `ink3` "0 / n"; on a chart without mines too, as `0 / 0` (Kelvin 2026-10-19 02:40: 「没有水雷也表0/0」; until then the row showed only when `minesTotal > 0`). A LANE MINE counts as one mine, touched if it had at least one contact. It never affects grade, FC or score display.

---

## 6. Result screen: "DIVE LOG"

### 6.1 Data [P0]

Inputs: `PlayResult` plus `RecordOutcome`. Persistence happens before the screen shows; the screen only animates deltas.

Shown on screen:
- title, artist, difficulty and constant, jacket
- score, grade, FC/AR
- R/E/L counts, early/late, mean offset, histogram
- accuracy, max combo / total
- previous best and diff
- play rating, top-10 index
- depth before → after, rating before → after, rank before → after
- EXP itemised, level before → after, unlocks
- local rank

The extra fields are requested in Appendix A.

### 6.2 Layouts

> **Since Kelvin 2026-10-19 06:28 there is one layout, built from the 16:10 blueprint: §24.19**, changed by round 8 (15:2x, §24.25: no clear badge, the chip in the jacket's corner, three columns on screens wider than 16:8.5). The tables below are the layouts it replaced.

**Layout choice.** If the visible height is ≥ 1,360 (4:3), use **Stacked**. Otherwise use **Three-column**.
- In Three-column, the middle block is designed as a 1,080-tall block centred on y = 540; the header anchors to ST and the strip to SB.
- 16:10 and 3:2 therefore get extra breathing room above and below.

**Three-column, 16:9 (1920×1080):**

```
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ DIVE LOG · 02 TWILIGHT                                                    [AUTOPLAY mark]     │ header cap top ST−24
│ ┌──────────────┐  09,812,345  (NUMBER 80)                              ╭──────────╮          │
│ │              │  NEW BEST  +123,456                                   │    S+    │ stamp     │
│ │ jacket 440   │  RESONANCE   1,640 ▬▬▬▬▬▬▬▬▬▬▬▬▬▬                     ╰──────────╯ (1632,760)│
│ │ (96..536,    │  ECHO           41 ▬                                   [ FULL COMBO ]          │
│ │  500..940)   │  LOST           12 ·                                                          │
│ └──────────────┘  EARLY 18 · LATE 23 · AVG +9 ms                     ┌ACCURACY┐┌MAX COMBO┐   │
│ Song Title        ▁▂▃▅█▇▅▃▂▁ echo trace (600..1380, 380..480)          │ 99.12% ││ 1,201   │   │
│ Artist  [DEEP 15⁺]                                                    └────────┘└─────────┘   │
│──────────────────────────────────────────────────────────────────────────────────────────────│ strip 32..220
│ (av) NAME  LV 12 ▬▬▬▬▬▬▬── +93 EXP │ DEPTH 2,536 m → 2,560 m  ▼ +24 m · R 9.12 │[RETRY][CONTINUE]│
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

| Element | Position and size | Style |
|---|---|---|
| Header | (SL + 72, cap top ST − 24) | No header line since 2026-10-18 17:22 (§24.16; it was `DIVE LOG · 02 TWILIGHT` UI_LABEL 20 `ink2`, typed on); the meta line keeps the band's baseline at the right. Autoplay: `AUTO` watermark DISPLAY_OUTLINE 200 at α .06, right. |
| Jacket | x 96–536, y 500–940 | 440², radius 16, panel shadow; sonar frame |
| Title / artist / chip | title cap top 456 (UI_BOLD 34, fit 440); artist cap top 404 (UI 22 `ink2`); chip 220×52 at y 320–372 | |
| Score | x 600, cap top 920 | NUMBER 80, 8 cells (776 wide) |
| Best line | baseline 800, x 600 | NEW BEST (RUBIK 30, `gold`) + `+123,456` (NUMBER 26, `gold`); or `BEST 9,900,000  −87,655` (UI 20 + NUMBER 20, `ink3`); or `FIRST DIVE` (UI_LABEL 20, accent) |
| Judgement rows | baselines 700 / 640 / 580 | label UI_LABEL 22 in judgement colour at x 600 (English in both languages since 2026-10-18 16:45, §24.15); count NUMBER 30 right-aligned at x 960; bar x 990–1380, 10 tall (LOST bar `#FF5C7A`) |
| Early/late line | baseline 526 | `EARLY 18 · LATE 23 · AVG +9 ms` UI 20 `ink2` |
| **Echo trace** | x 600–1380, y 380–480 | 27 bins of 10 ms over −135..+135, drawn as a mirrored waveform (bars 1 px apart). Bins within ±66 ms in `#66F0FF`, the rest `#B49CFF`. 2 px `ink1` 0-ms line; `EARLY` / `LATE` end labels (UI_LABEL 16); mean caret + `μ +9 ms`. |
| **Grade stamp** | centre (1632, 760) | 340 ⌀ ring (two concentric strokes, 4 px + 2 px) in grade colour; letter DISPLAY 200 (PURE 104); rotation −6° |
| Clear badge | centre (1632, 548), 320×56 | `FULL COMBO` / `ALL RESONANCE` / `CLEAR`, UI_BOLD 22 |
| Tiles | 2 × 184×110 at x 1440–1824, y 380–490 | label UI_LABEL 18; value NUMBER 30 |
| Strip panel | SL..SR, y 32–220 | • avatar 96 ⌀ at SL + 24<br>• name UI_BOLD 22; `LV 12` NUMBER 22 `gold`; EXP bar 420×10; `+93 EXP` NUMBER 24 `gold` (tap → breakdown)<br>• depth block starting x 660: `DEPTH` UI_LABEL 18; `2,536 m → 2,560 m` NUMBER 30; `▼ +24 m · R 9.12 (+0.04)` UI 20 accent; rank chip (UI_LABEL 20, band accent, emblem 32) |
| Buttons | vertically centred in the strip | RETRY secondary 260×112 at x 1312–1572; **CONTINUE** primary 300×112 at SR − 300..SR (outermost, thumb side) |

**Three-column, aspect ≥ 2.0 (20:9, 19.5:9):**
- Left column anchored at SL + 72.
- Right column (384 wide) anchored at SR − 72.
- Centre column 880 wide, centred on x 960 (520–1400); score NUMBER 88; bars x 1010–1400.
- The strip spans SL..SR, and its blocks spread proportionally.
- The gutters show a ghost layer numeral (DISPLAY_OUTLINE 460, α .05) in the left gutter [P2].

**Stacked, 4:3 (1920×1440, y −180..1260):** laid out again by Kelvin 2026-10-19 01:42 from Kelvin's colour marks on the 4:3 gallery (结算 pages 1–2, PDF pages 80–81: 「每一个颜色对应某一个物件应该被移动到哪里」), then 02:24 (§24.18). Everything under the top row keeps to the left half; the lower right is left empty (Kelvin's hatched area: 「未来白色区域会有人物角色」).

| Band | Content |
|---|---|
| y 1180–1236 | header (meta line at the right) |
| y 849–1160 | jacket 300 at x 96 (y 860–1160); score centred between the jacket and the stamp (x 919, NUMBER fit to the gap, ≤ 112, ≈ 108; baseline ≈ 993); under it the best line (NEW BEST pill ≈ 919–965) and under that the clear badge 320×56 (centre y 877; after the tutorial, which has no best line, in the best line's place, centre ≈ 942); stamp ring 300 ⌀ at (1600, 1010), letter cap 176 |
| y 654–820 | title (cap top 820), artist (762) and chip (654–706) under the jacket at x 96; beside them the tiles, 2 × 250×120 at x 436 and 710, y 677–797 |
| y 90–625 | left half (x 96–960), spread down to the strip: judgement rows (RESONANCE baseline 596, then ECHO 526, LOST 466 and MINES 406; MINES shows `0 / 0` on a chart without mines), RESONANCE CHAIN, early/late, echo trace (y 178–278; tapped, its card grows 30 up over the early/late line and down to 60 above the strip), then the offset hint (baseline 100) centred on the trace's 0-ms line |
| y SB..SB+200 (≈ −156..44) | strip x 24–984: player and shells left, depth block from x 560 (the `before → after` line shrinks, never below 0.7, to end inside the strip) |
| y SB..SB+112 (≈ −156..−44) | bottom right: RETRY 380×112 at x 1064–1444, CONTINUE 420×112 at x 1476–1896 |
| x 988–1896, y −44..828 | empty |

**Background.** Gameplay depth with menu intensity, plus the jacket 16×16 aura at α .35 and motes. `ResultPage.png` at α .20.

### 6.3 Choreography (t = 0 when the ASCENT open starts) [P0]

| t (s) | Element | Animation |
|---|---|---|
| 0.00 | Result music | starts at volume 0; fades to `musicVolume × 0.8` over 1.2 s; loops (§6.7) |
| 0.00–0.45 | Panels | part (`exp5Out`) |
| 0.00–0.35 | **Input guard** | all touches ignored (a finger may still be down from the last note) |
| 0.10–0.60 | Jacket | x −220 → 0 (`pow3Out`); rotation −4° → 0 (`SwingOut(1.6)`); α 0 → 1; RGB split 6 → 0 px over the first 150 ms; sonar frame traces clockwise (400 ms) |
| 0.25–0.70 | Title | decode: each character shows 2 random glyphs from `#%&0-9A-F` for one frame each, 18 ms stagger. CJK titles fade instead. Split + slices at the end (no face swap: Chakra). |
| 0.30–0.55 | Artist, chip | fade + rise 8 (`pow3Out`); chip pops 0 → 1 (pop) |
| 0.40 | Header | types on at 20 ms/char |
| 0.60–1.72 | **Score decode** | all 8 cells scramble (random digits at 30 Hz, `ink3`) and **lock left to right**, one every 0.14 s. Each lock flashes `ink1` → accent → `ink1` over 120 ms; leading zeros lock dim. Optional tick SFX per lock [P2]. |
| 0.80–2.00 | Judgement rows | staggered 120 ms; counts roll 0 → n (900 ms `pow3Out`); bars fill to n/N (`pow3Out`) |
| 1.10–1.90 | Echo trace | a sonar sweep line crosses left to right (800 ms, linear); each bin rises as the line passes (`SwingOut(1.6)`, 200 ms); then `μ` fades in |
| 1.40–2.00 | Early/late line | fade 200 ms |
| **1.80** | **Score lands** (hold point) | digit brightness 1 → 1.6 → 1 (180 ms); G2 with face swap; camera kick 4 px down (damped sine, 140 ms) |
| 2.00–2.22 | **Grade stamp in** | scale 2.6 → 1.0; α 0 → 1 over the first 80 ms; rotation −12° → −6°; `pow4In` |
| **2.22** | **Impact** (hold point) | camera kick 8 px; 3 rings from the stamp centre (r 0 → 480, staggered 80 ms, 700 ms, α .6 → 0; count per grade, §12); 12 streak sparks; flash α .12 for 90 ms over the stamp column only; RGB split 6 px decaying over 250 ms; settle 1.0 → .96 → 1.0 (bouncy); medium haptic; thump SFX [P2] |
| 2.40+ | PURE / S+ | a diagonal shimmer band crosses the letter (600 ms). PURE: 40 bio-motes rise for 2 s, then the iridescent cycle runs. |
| 2.30–2.90 | Tiles | pop with 80 ms stagger; values count up (600 ms `pow3Out`) |
| 2.50–3.00 | Clear badge | pill expands from its centre (300 ms `pow3Out`); text decodes outward; a shine quad sweeps across (500 ms) |
| 2.70–3.20 | Best line | NEW BEST pops (pop); `+diff` counts over 500 ms; border pulses `gold` (1.2 s loop). Otherwise the best line fades in over 250 ms. |
| 3.00–3.35 | Strip | rises y −160 → 0 (`pow3Out`) |
| 3.20–4.00 | **Depth** (phase D) | depth counts old → new (800 ms `pow4Out`); a mini gauge marker sinks with bubbles; `▼ +24 m` pops (pop) (`±0 m` in `ink3` when unchanged); R delta fades in |
| (if rank changed) | **Rank up** (+0.60 s, hold point ≥ 300 ms) | rank chip flips (scaleX 1 → 0 → 1, 400 ms) to the new title; `RANK UP · ECHO HUNTER II` toast (RUBIK 40, new band accent) with G3 and a ring burst |
| 3.40–4.30 | **EXP** (phase E; waits for rank-up if one is running) | `+93 EXP` chip pops; bar fills old → new (900 ms `pow3`) |
| (if level up) | **Level up** (hold point ≥ 450 ms) | bar reaches 100 % and flashes (120 ms); `LEVEL UP` (RUBIK 48, G3); level digit slot-rolls (300 ms); 24 particles; heavy haptic; music ducks to 60 % for 600 ms; bar resets and fills the remainder (300 ms); each extra level adds 0.35 s; unlock toast (e.g. `NEW PATTERN · WAVES`) |
| max(4.40, end of E + 0.10) | Buttons | rise y −40 → 0 (`SwingOut(1.4)`), stagger 80; interactive as soon as visible |
| idle | — | jacket breathes ±1 % (4 s); stamp G2 every 2.5–4 s; heartbeat ping from the stamp (2.4 s); trace sweep every 4 s; EXP bar glint every 6 s; CONTINUE glow α .3 ↔ .6 at 0.8 Hz |

> Since Kelvin 2026-10-20 18:45 the depth (phase D, 3.20–5.70 when the depth went deeper) and the shells (phase E, after it) run as in §24.40.

### 6.4 Skip and input [P0]

- **First tap** (anywhere except buttons, after the input guard):
  - The timeline time scale becomes `max(6, remaining / 0.75)`, so the rest plays in about 0.75 s.
  - Hold points still play at minimum length: score-land 100 ms, stamp impact 150 ms, rank-up 300 ms, level-up 450 ms.
- **Second tap** jumps to the final state at once.
- Buttons enable **250 ms after** a skip completes, which prevents an accidental RETRY.
- **Keys:**
  - BACK / Esc during the choreography = jump to the end; afterwards = CONTINUE.
  - Space / Enter = skip, then CONTINUE.
  - R = RETRY.

### 6.5 Idle interactions [P1]

> Since 2026-10-19 06:28 (§24.19) the depth, the echo trace and the shells open from the player frame, the EARLY / LATE / AVG line and the shell capsule, and the jacket no longer tilts; the echo trace card was dropped at 17:48 (§24.25). The offset hint below is unchanged.

- **Tap the stamp:** it re-stamps with a small impact (4 px kick) plus a ping.
- **Tap the jacket:** it tilts ±3° with a 0.97 bounce.
- **Tap the echo trace:** it expands (spring) to show its bins in detail, with a line `PEAK n  ·  MEAN +31 ms` at the top (峰值 / 平均 in 中文 since 2026-10-18 20:34: 「点开下面的early/late柱状图出现的也是中文，early late不必那，peak改为"峰值"，mean改为平均」; its `EARLY` / `LATE` labels stay English).
- **Offset hint.** If `|meanOffsetMs| > 25` over ≥ 50 non-LOST hits, a link appears under the trace, centred on its 0-ms line (since Kelvin 2026-10-18 21:56: 「就是居在那个柱状图0ms那里」; the stacked tablet layout too since 2026-10-19, when the hint moved from under the tiles to under the trace). The auto-drop notice that sat under it (§5.9) was removed on 2026-10-19 02:24 (§24.18):
  - Copy: `YOU'RE HITTING LATE (+38 ms) · ADJUST`; in 中文 `你打得偏晚（+38 ms） · 调整` (偏早 when early) since Kelvin 2026-10-18 21:37 (§24.17).
  - It opens a confirm sheet: `Shift audio offset for SPEAKER from +12 to +50 ms?` with **APPLY**, **CALIBRATE** and **CANCEL**.
  - The new offset = old + mean (convention in §9.4). The sheet does not print this formula (Kelvin 2026-10-17 14:50: the small line under the question was removed).
- **Tap the EXP chip:** a popover itemises base, first play, new best, FC, AR and daily.
- **Drag the background:** ripples and bubbles.

### 6.6 Variants [P0]

> Since 2026-10-19 06:28 (§24.19) the strip note shows in the offset hint's place.

| Case | Behaviour |
|---|---|
| Autoplay | `AUTO` watermark; strip reads `AUTOPLAY · NOT RECORDED` (`AUTOPLAY · 不记录成绩` in 中文); stamp drawn in `ink3` with no rings or sparks; no depth or EXP phases; buttons at 2.6 s |
| Tutorial | no header (`TUTORIAL COMPLETE` until 2026-10-18 17:22); score and judgement rows shown; no grade, rating or depth; EXP phase shows the tutorial award (first time only); buttons **REPLAY** / **CONTINUE** (→ map; first run lands with coach marks). Nothing is recorded. |
| First play of a chart | `FIRST DIVE` tag in place of the best line |
| No improvement | best line in `ink3`; stamp uses 1 ring |

### 6.7 Result music [P0]

- **Slot chain** (`audio.playResult(song)`):
  1. song folder `result.(ogg|mp3|wav)`
  2. `assets/Audio/result.*`
  3. **`assets/Introduction/Introduction.wav`** (temporary, as requested)
  4. silence
- **Playback.** Starts at `resultMusicStartMs` (default 0), loaded while ASCENT covers the screen. Fades in over 1.2 s to `musicVolume × 0.8` and loops.
  - `resultMusicStartMs` is a per-song manifest key (`Charts/index.json`, song entry object) and applies only to that song's own `result.*`; the global `Audio/result.*` and the fallback start at 0.
  - The slot is documented for the owner in `assets/Audio/README.txt` and `docs/ASSET_SLOTS.md`.
- **Exit.** Fades out over 450 ms with the IRIS (300 ms on RETRY), then is disposed. If the player leaves before t = 1 s, the music never reaches full volume.

---

## 7. Welcome [P0 unless tagged]

- **Background.** Player depth (0 m for new players). `welcome.png` at α .35, cover-cropped, Ken Burns, 3 % touch parallax.
- **Layout:**
  - Logo centred at (960, 640).
  - Tagline at baseline 520.
  - Prompt at baseline 220.
  - Corners use the safe edges.

| t (s) | Event |
|---|---|
| 0.00 | fade from `#02040F` (400 ms) |
| 0.10–0.70 | two motes (`groundSig`, `skySig`) spiral down a helix from y 1,160 to (960, 640); radius 220 → 0 (`exp5Out`) |
| 0.70 | the motes collide: `sonar.ping(960, 640, 900, 0.7, accent, 0.4, 2)`; the logo appears with G2 |
| 0.75–1.25 | `K`, `&`, `K` decode with 60 ms stagger into DISPLAY_SOFT 140 (left K `groundSig`, `&` `ink2 @ .7`, right K `skySig`); rest G1; idle G2 every 1.8–3.5 s |
| 1.10 | tagline `TWO SIGNALS · ONE DESCENT` (UI_LABEL 20, `ink2`; placeholder copy for the owner), baseline 480 (520 until 2026-10-20 21:17, §24.42) |
| 1.20 | prompt `TAP ANYWHERE TO START` / `点击任意位置开始` (`TAP TO DESCEND` / `点击下潜` until 2026-10-20 21:17, §24.42) (UI_BOLD 26) breathing α .45 ↔ 1.0, synced to the 2.4 s heartbeat |
| idle | two ring emitters at (760, 640) and (1160, 640) alternate every 1.2 s: r 0 → 1,100 over 3.2 s, α .18 → 0; left ring `groundSig`, right ring `skySig` (interference) |

- **Input:**
  - Accepted from 0.3 s (today it is locked for 3.5 s).
  - A tap before 1.2 s fast-forwards to the final state (300 ms); the next tap descends.
  - Any key works.
- **Play** [P1]:
  - Dragging leaves ripples and bubbles.
  - The two K glyphs lean toward the finger (±16 px offset, ±4° rotation, jelly spring), and the shafts tilt ±4°.
  - Tapping a K pings it with a jiggle and a G2.
  - Desktop: hovering a K brightens it.
- **Corners:**
  - BL: profile chip `DIVING AS KELVIN · LV 12 · 2,560 m ▾` (UI 20, panel pill). Tap → the switcher sheet: profile rows (avatar 72, name, LV, depth) and `+ NEW DIVER` (`MANAGE` → Profile went on 2026-10-20 21:17, §24.42; on a full device the button and its footer go too, §24.47). Selecting a row switches in place: the logo does a G2, the chip updates, and the backdrop calls `setDepth`.
  - BR: nothing since 2026-10-20 21:17 (the Settings gear went, §24.42; Settings opens from the map's HUD).
  - Bottom centre: version (UI 16, `ink3`).
- **Music.** `audio.playMenu()` (background.wav loop, 0.6 s fade-in).
- **Exit.** The Route hub (§16), with the depth ramp of §3.12. On first run: IRIS → FirstRun (§10.1).

---

## 8. Profile and accounts (local only, no server)

### 8.1 Storage and data [P0]

- **Storage** follows ARCHITECTURE §7: `index.json` plus `<id>.json`, atomic writes, `.bak`, corrupt files renamed. Maximum **12** profiles (8 until 2026-10-21, §24.47).
- **Device settings** live in `device.json` (Appendix A).
- **Profile additions** (Appendix A):
  - avatar {seed, shape, pattern, palette, glyph, frame}
  - `ratingHistory` (last 30 `[t, R]`)
  - `recentPlays` (≤ 20)
  - stats {totalPlayMs, fullCombos, allResonance}
  - `seenChapters`, `calibrated`, `lastPlayDay`
- **Legacy import.** `KandKGame/user.json` is imported on first run (`LegacyUserDataImporter`).

### 8.2 Profile screen layout

**16:9 and 20:9.** Columns anchored to SL + 72 and SR − 72. Header: back chevron plus `PROFILE` (DISPLAY_SOFT 44).

**Left column (560 wide):**
- avatar 300 ⌀ (tap → avatar editor)
- name UI_BOLD 36 with a pencil icon (tap → rename)
- `LV 12` (NUMBER 26, `gold`), EXP bar 520×10, `3,120 / 4,050` (NUMBER 20)
- depth `2,560 m` (NUMBER 56), `R 9.12` (UI 20 `ink2`)
- rank title (UI_BOLD 28, band accent) with a 64 px emblem
- next tier: `440 m TO ECHO HUNTER III` (UI 20 `ink2`) with a mini bar

**Right column:** tabs `STATS · TOP 10 · LOG · DIVERS` (segmented, UI_BOLD 22).

| Tab | Content |
|---|---|
| STATS | • 3×2 tiles 340×140, gap 24: PLAYS, PLAY TIME, CHARTS PLAYED x/28, FULL COMBOS, ALL RESONANCE, AVG ACCURACY (value NUMBER 36, label UI_LABEL 18)<br>• played per difficulty: 4 mini bars (`SURFACE 8/8`)<br>• grade distribution: stacked bar 1,100×16 with counts<br>• **dive profile** [P1]: rating history drawn as depth going *down* (deeper = better), polyline plus gradient fill, 1,100×200 |
| TOP 10 | rows 96 tall: `#`, thumb 72, title UI_BOLD 22, difficulty chip, score NUMBER 24, chart rating NUMBER 24, contribution bar. This is exactly what forms R. Scrolls with map physics. |
| LOG [P1] | last 20 plays: thumb, title, difficulty, score, grade pill, date (UI 20) |
| DIVERS | cards 520×128 in 2 columns: avatar 88, name, `LV · depth · rank`, last played. The active card has an `ACTIVE` tag and accent border. `+ NEW DIVER` card. Actions on a card's `⋯`: RENAME, EXPORT, DELETE. IMPORT button below the grid. |

**4:3:** single column. The header block (avatar 240 beside name, level and depth) on top, tabs below, content below.

**Since 2026-10-17:** the left column holds the avatar, the name, the shell count (between the name and the depth, no `SHELLS` label) and the depth (§23.4), and under the card PLAYS and PLAY TIME as two bars down to the bottom (on 4:3 at the right of the header block). The tabs are `STATS · TOP 10 · RECENT · DIVERS` (LOG renamed RECENT). STATS, TOP 10 and renaming by tapping the name are as in §23.8. Since 2026-10-18 the tabs are `STATS · DST 10 · RECENT · PLAYER` (§24.4, §24.6). Since 2026-10-21 PLAYER puts three cards a row wherever three fit (§24.46).

### 8.3 Create, rename, switch, delete [P0]

- **Create.** `+ NEW DIVER` opens the creation card (identical to §10.1 step 1) as a sheet. A full device (12 profiles) shows no `+ NEW DIVER`, neither on the PLAYER tab nor in the Welcome sheet (§24.47). The English texts say PLAYER for DIVER since 2026-10-21 02:04 (`+ NEW PLAYER`, §24.48).
- **Rename.** `getTextInput`. Allowed: 1–16 code points, trimmed, control characters removed. An empty name is rejected with a shake. Duplicate names are allowed (ids are unique). Since 2026-10-17 14:55, tapping the name on the Profile screen renames in place, with only the keyboard (§23.8); the DIVERS tab's RENAME and naming a new diver still use the rename dialog.
- **Switch** (from a DIVERS card or the Welcome sheet):
  - `saveNow()` first.
  - The avatar flips (scaleX 1 → 0 → 1, 220 ms) to the new one.
  - Stats count to the new values (400 ms).
  - `Backdrop.setDepth(new player depth)`, plus a G2 on the header.
- **Delete.** Destructive hold for 1.5 s; the card shakes harder as the ring fills. The last profile cannot be deleted (disabled; shakes on tap). Deleting the active profile switches to the most recently played remaining one.

### 8.4 Export and import [P1]

- **Export:**
  - Copies a profile code to the clipboard via `Gdx.app.getClipboard()`. The code is `KK1:` + base64(gzip(profile JSON)).
  - Desktop also writes `KandKGame/export/<name>-<yyyymmdd>.json`.
- **Import:**
  - **PASTE CODE** reads the clipboard; desktop can also pick from the export folder.
  - The imported profile gets a new id; a name collision becomes `NAME (2)`.
  - The 12-profile cap is enforced (8 until 2026-10-21); on a full device the PLAYER tab has no IMPORT button (§24.47).

### 8.5 Avatar: "sonar sigil" [P0 simple / P1 full]

- **Parameters:**

  | Parameter | Options |
  |---|---|
  | shape (5) | circle, hexagon, squircle, diamond, shield |
  | pattern (8) | rings (4 concentric), spokes (12 radial), waves (5 sine polylines), dots (hex lattice r 6), spiral (Archimedean, 3 turns), grid (4×4 rounded squares), split-K (a big K cut diagonally in two colours), constellation (7 seeded points plus links) |
  | palette pair (10) | `#4FD8FF/#F2F7FF`, `#7FE7FF/#134079`, `#6FA8FF/#0F275E`, `#8F7BFF/#0A1338`, `#C77DFF/#080B24`, `#FF5CA8/#050613`, `#FFD27A/#0A1033`, `#66F0FF/#B49CFF`, `#9CF5B0/#0D1C4C`, `#FF2E88/#00E5FF` |
  | glyph | none, initial (NUMBER/UI_BOLD 120), K |
  | frame | none, dashed sonar, double, iridescent |

- **Rendering.** Drawn once with ShapeDrawer into a 256² `FrameBuffer`, owned by an `AvatarCache` in Main. Re-rendered lazily when its parameters change, and on resume after context loss (bump a generation counter in `Main.resume()`).
- **REROLL** (dice): spring rotation ±18°; cycles 6 variants in 400 ms like a slot machine; settles with a bouncy pop.
- **Editor:** ‹ › steppers for shape, pattern, palette, glyph and frame. Locked options show a lock and the level needed.

### 8.6 Level unlocks (cosmetic only) [P1]

> **Superseded 2026-10-17 by §23** (shells, depth and fifteen grades); kept for history.

| Level | Unlock |
|---|---|
| 3 | waves pattern |
| 5 | dashed sonar frame |
| 8 | grid pattern |
| 10 | +3 palettes (the last three pairs) |
| 12 | double frame |
| 16 | split-K pattern |
| 20 | spiral pattern |
| 25 | constellation pattern |
| 30 | K glyph |
| 40 | animated orbit ring |
| 50 | iridescent frame |

### 8.7 Local ranking [P1]

`ProfileStore.localRank(songId, diff)` returns (rank, count) across local profiles. It is shown as `LOCAL #1 / 3` on the card and the result screen, only when count ≥ 2.

---

## 9. Settings and calibration

### 9.1 Shell [P0]

- **Screen.** Full screen with the backdrop at the player's depth (shafts × 0.6). Enter and leave through CURTAIN.
- **Navigation.** A left rail 300 wide: `AUDIO · VISUAL · GAMEPLAY · OTHER` (icon plus UI_BOLD 22; in 中文 音频 · 视觉 · 游玩 · 其他, 画面 until 2026-10-18 14:29; OTHER, with a three-dot icon, holds what ACCOUNT and ABOUT held until 2026-10-18 02:27, §24.6). On 4:3 the rail becomes a top tab row.
- **No TIMING tab since 2026-10-18** (Kelvin 00:24: 「timing分开到audio和visual里面」). Its audio half is the TIMING section at the top of AUDIO (at the end until 03:25, §24.8); its display half (the early / late switches, SHOW MS, HIT ERROR METER) sat in VISUAL until 04:06 and opens GAMEPLAY since (§24.9). `SettingsScreen.TAB_*` number the rail tabs in order: AUDIO 0, VISUAL 1, GAMEPLAY 2, OTHER 3.
- **Pages off the rail**: the licences page and a licence text (from OTHER). The rail keeps their tab lit, the title names the page and BACK steps back one page at a time. GAMEPLAY VISUALS (LIVE PREVIEW until 14:29) was such a page of VISUAL from 2026-10-18 04:06; since 15:17 VISUAL's ADJUST… opens it as a screen of its own (§9.3), and BACK there returns to VISUAL.
- **Version line**: `VERSION 0.1.0-beta.1` / `版本 0.1.0-beta.1` (UI 16, `ink3`) under the rail on WIDE when it fits. K & K and its line sat beside it from 03:25 to 04:06; they are a row of OTHER again (§9.2).
- **Rows** are 104 tall:
  - left: label UI 24, description UI_REGULAR 18 `ink3` (two lines on CHART OFFSET since 2026-10-18 14:29, `SettingRow.setDescription2`), and a small `SHARED` / `公用` tag (UI_LABEL 16) on device-scoped rows, the settings every diver on the device shares (named `DEVICE` / `本机` until 2026-10-18 04:48: 「那些在后面跟着的"本机"能去掉吗还是说有什么特殊含义」, then 「那可以标注一下公用」)
  - right: the control (slider 480; toggle; segmented)
- Changes apply live and are saved on leave (`markDirty` + `saveNow`).
- Entry: a 320 ms `pow3Out` slide with a 40 ms row stagger.

### 9.2 Settings

**AUDIO** (2026-10-18 03:25: 「延迟和声音这两个分区互换顺序，音频延迟和音频输出也互换顺序，确保音频延迟是在第一个」): the TIMING section first, CHART OFFSET at the top, then SOUND. Since 2026-10-21 its rows share the height the list leaves under them, each row's content in the middle of its taller row (`SettingsPage.setSpreadCentred`, §24.46). Since 2026-10-18 14:29 the setting names are Kelvin's Chinese with the English translated back from them, four switches are gone and some descriptions are Kelvin's (§24.13).

**AUDIO, TIMING section** (its own tab until 2026-10-18)

| Setting | Default | Scope | Notes |
|---|---|---|---|
| Chart offset (active preset) | 0 | device | `CHART OFFSET` / `谱面延时` (AUDIO OFFSET / 音频延迟 until 14:29); −300..+300 ms; slider plus [−10][−1][+1][+10]; the first row of AUDIO. Three description lines since 2026-10-18 19:52 (「谱面延时下面的小字变成3行」): `ADJUST THE SYNCHRONIZATION OF MUSIC` / `调整音乐的同步性`, then the two from 14:29, `MOSTLY EARLY → TAP – TO DECREASE` / `EARLY 较多 → 点击减少（–）` and `MOSTLY LATE → TAP + TO INCREASE` / `LATE 较多 → 点击增加（+）` (+ delays the chart) |
| CALIBRATE… | — | — | opens §9.4. Since 2026-10-18 02:27 a button left of the nudges, under the AUDIO OFFSET label, not a row of its own (§24.6). From 02:27 to 14:29 the offset row's description named the output and, until the diver had calibrated, read `SPEAKER · NOT CALIBRATED YET` (`扬声器 · 还没有校准`) in gold |
| Audio preset | SPEAKER | device | `AUDIO PRESET` / `音频预设` (AUDIO OUTPUT / 音频输出 until 14:29), description `TAP TO SWITCH THE PRESET` / `点击切换预设项目`; SPEAKER / WIRED / BT, each with its own chart offset |

**AUDIO, SOUND section**

| Setting | Default | Scope | Notes |
|---|---|---|---|
| Music volume | 80 % | device | `MUSIC VOLUME` / `音乐音量` (MUSIC / 音乐 until 14:29) |
| Note volume | 80 % | device | `NOTE VOLUME` / `打击音效音量` (`HIT SOUND VOLUME` in English until 2026-10-18 19:52; HIT SOUND / 打击音效 until 14:29), description `ADJUST THE VOLUME OF NOTE SOUND EFFECTS` / `调整音符的打击音效音量` (`ADJUSTS THE NOTES' HIT SOUND VOLUME` until 19:52); disabled, with ` · NONE INSTALLED YET` / ` · 还没有打击音效` added, when no hit sound is installed |
| ~~Hit sound mode~~ | On judgement | diver | **removed 2026-10-18 14:29** (「打击音效时机（只要判定时的）」): hit sounds play on judgement only; `Settings.sanitize` pins `hitSoundMode` to `HIT_ON_JUDGEMENT` |
| UI sound volume | 60 % | device | `UI SOUND VOLUME` / `界面音效音量` (Kelvin 2026-10-18 15:17: 「音频那里加一个界面音效音量，和打击特效一样暂时还没有install」), under NOTE VOLUME; description `ADJUSTS THE MENU AND BUTTON SOUNDS · NONE INSTALLED YET` / `调整菜单和按钮的音效音量 · 还没有界面音效`; disabled (dimmed) like NOTE VOLUME, since no UI sounds ship yet; `DeviceSettings.uiVolume`. Hidden until 15:17 |
| ~~Song preview~~ | On | diver | **removed 14:29**: always on (`sanitize` pins `previewOn`) |
| ~~Menu music~~ | On | diver | **removed 14:29**: always on (`sanitize` pins `menuMusicOn`) |

The **visual offset is gone** (Kelvin 2026-10-18 00:24: 「visual offset感觉跟audio offset有点重合，去掉」). Gameplay and the calibration screen use 0; `DeviceSettings.visualOffsetMs` is still read and written so older `device.json` files round-trip, but nothing uses it.

**VISUAL** (2026-10-18 04:06: 「实施预览里的放在"画面中"一个和calibrate类似的那种点进去才显示的东西……其余的9~12，19~21留在"画面"中」): a GAMEPLAY VISUALS row first (LIVE PREVIEW until 14:29), whose ADJUST… opens the GAMEPLAY VISUALS page, then the rest. Since 2026-10-20 03:20 VISUAL HIT POINT comes before it (「加在游戏画面预览之前，和谱面延时类似的一个结构」, §24.29).

| Setting | Default | Scope | Notes |
|---|---|---|---|
| Visual hit point | 0 px (20 before 2026-10-20 22:37) | diver | `VISUAL HIT POINT` / `目押校准`, the first row of VISUAL since 2026-10-20 03:20: −100..+100 px, slider plus [−10][−1][+1][+10], laid out like CHART OFFSET with three description lines, `FROM A TAP OR HOLD KEY'S MIDDLE TO THE LINE AT THE HIT` / `判定时 TAP、HOLD 按键中线到判定线的距离`, `0 = THE LINE THROUGH THE KEY'S MIDDLE (DEFAULT)` / `0 = 判定线横穿按键中间（默认）` and `20 = BOTTOM EDGE ON THE LINE` / `20 = 按键下边缘碰线` (the other way round, with the default on 20, before 22:37). `Settings.hitPointPx`; how notes are drawn by it: §5.8 |
| CALIBRATE… (hit point) | — | — | `CALIBRATE…` / `校准…` left of the hit point's nudges, under its label, as on CHART OFFSET (`SliderRow.setLead`); opens the VISUAL HIT POINT screen (§9.7) |
| GAMEPLAY VISUALS → ADJUST… | — | — | `GAMEPLAY VISUALS` / `游戏界面` (`游玩界面视觉参数` from 14:29 to 2026-10-18 19:52, 「"游玩界面视觉参数"改成"游戏界面"」; `LIVE PREVIEW` / `实时预览` until 14:29), description `GLITCH, PARTICLES, NOTE SCALE, JUDGEMENTS, MULTI-PRESS` / `故障、粒子、按键缩放、判定和多押，边调边看` (TOUCHES / 触控 until 19:52, when SHOW TOUCHES left the screen and MULTI-PRESS ASSIST came in); a PRIMARY button like CALIBRATE… that opens the GAMEPLAY VISUALS screen (§9.3; a page of the list from 04:06 to 15:17) |
| Reduce motion | Off | diver | |
| Background detail | High | device | Low / High; auto-fallback (§2.4) |
| Background brightness | 100 % | diver | `BACKGROUND BRIGHTNESS` / `背景亮度` (ART OPACITY / 插画不透明度 until 14:29), description `BRIGHTNESS OF THE MENU AND SONG ARTWORK` / `菜单和曲目背景插画的亮度` (Kelvin 14:29: 「我还没想好，你帮我写一下」); `Settings.artOpacity`. The BACKGROUND ART switch above it is **removed** since 14:29: the artwork always shows (`sanitize` pins `backgroundArt`) |
| UI scale | Auto | device | or 90–130 % |
| Extra edge padding | 0 | device | 0–48 |
| Show FPS | Off | device | |

**GAMEPLAY VISUALS screen** (`VisualsScreen`, §9.3; titled `GAMEPLAY VISUALS` / `游戏界面` since 2026-10-18 19:52, `游玩界面视觉参数` before, `LIVE PREVIEW` / `实时预览` until 14:29, which the preview keeps as its caption): its own screen since 2026-10-18 15:17, before that a page of the list beside a small preview (04:06–15:17) and VISUAL's first section (03:25–04:06, §24.8). Two columns of stacked rows (label line, description, then the control across the column):

| Setting | Default | Scope | Notes |
|---|---|---|---|
| **SCREEN** / 画面 (left) | | | |
| Background brightness (gameplay) | 65 % | diver | `BACKGROUND BRIGHTNESS` / `背景亮度`, description `THE BACKGROUND DURING A GAME` / `下潜时背景的亮度` (`… A DIVE` until 2026-10-18 19:54: 「所有的这类形容游戏的"dive"都改成game」): shows 1 − `Settings.backgroundDim`, 10–100 % (BACKGROUND DIM / 背景压暗 0–90 % until 14:29; the stored dim is unchanged) |
| Glitch intensity | 60 % | diver | 0–100 % with detents at 0 / 35 / 60 / 100; description `GLITCH ON JUDGEMENT WORDS AND TITLES` / `判定字和标题的故障特效` (Kelvin 16:17); the JUDGEMENT WORDS strip under the preview shows it |
| Reduce flashing | Off | diver | `NO WHITE FLASHES · SOFTER GLITCH` / `没有白闪 · 故障效果更柔和`; the words glitch softer, no red flash on LOST |
| Particle quality | High | device | description `HIT EFFECTS DURING A GAME` / `下潜时的打击特效` (`… A DIVE` until 19:54); Off / Low / High; auto-drop (§5.9), when the description turns gold `LOWERED AUTOMATICALLY FOR SPEED` / `为了流畅已自动调低` |
| **NOTES & JUDGEMENTS** / 音符与判定 (right) | | | |
| Note scale | 100 % | diver | `NOTE SCALE` / `按键缩放` (NOTE SIZE / 音符大小 until 14:29), description `NOTE SIZE DURING A GAME` / `下潜时音符的大小` (`… A DIVE` until 19:54); 80–130 % |
| Multi-press assist | On | diver | `MULTI-PRESS ASSIST` / `多押辅助` (SYNC HINT / 同时提示 until 2026-10-18 14:29; Kelvin 2026-10-13), description `NOTES THAT SHARE A BEAT` / `同一拍上的音符`, listed OFF / ON / ENHANCED (`关 / 开 / 增强`, 2026-10-18 04:34; GLOW / GLOW + PEARLS / OFF before): ON outlines notes that share a beat (§5.8 sync highlight); ENHANCED adds the gold pearls; OFF shows neither. The row maps its order onto `Settings.syncHint` (0 glow, 1 pearls, 2 off), so saved values keep their meaning. Here since 2026-10-18 19:52 (「把多押辅助加进游玩界面视觉里面」; GAMEPLAY's first row before), and the preview shows it (§9.3) |
| Judgement position | Centre | diver | `JUDGEMENT POSITION` / `判定位置` (JUDGEMENT DISPLAY / 判定显示 until 14:29), description `WHERE TO DISPLAY THE JUDGEMENT WORD` / `共鸣 / 回响 / 迷失显示在哪里` (`WHERE THE JUDGEMENT WORD SHOWS` until 2026-10-18 19:52); Centre / At lane / Off; on GAMEPLAY until 2026-10-18 03:25 (Kelvin: 「我估计判定显示是不是也和实时预览有关」), and the preview places its word the same way since |
| FC / AR indicator | On | diver | `FC / AR INDICATOR` / `FC/AR指示` (Kelvin 2026-10-18 16:17: 「开启以后，如果是AR分数显示为淡蓝色，如果爆了echo但还是FC分数变为紫色，如果FC都不是了就变成白色」; 16:19: 「加到右侧」), description `SCORE IS BLUE ON AR, PURPLE ON FC` / `分数变色：AR 淡蓝 · FC 紫 · 否则白色`: the dive's score colour (§5.5) and the preview's small score; `Settings.fcArIndicator` |
| Zone hint | On | diver | `FAINT TINT ON EACH TOUCH ZONE` / `给每个触控区淡淡着色`; the tint covers the whole ±0.4 zone at α .07 (§5.7) in a game and in the preview since 2026-10-18 19:52 |
| ~~Approach glow~~ | On | diver | **removed 2026-10-18 16:19** (「把接近光晕去掉，感觉效果不明显」): the glow stays on (`sanitize` pins `approachGlow`) |

**GAMEPLAY** (04:06: 「14~18移到游玩里，放在最开头」): the in-dive hints first.

| Setting | Default | Scope | Notes |
|---|---|---|---|
| Early / late hint | ECHO lit | diver | `EARLY / LATE HINT` / `过早 / 过晚提示` (`TOO EARLY / TOO LATE HINT` in English from 14:29 to 2026-10-18 19:52; `EARLY / LATE ON` / `早 / 晚提示` from 06:03 to 14:29), one row with two chips, RESONANCE (`共鸣`) and ECHO (`回响`), drawn like the Profile STATS difficulty toggles in the judgement colours (`ChipsRow`; Kelvin 2026-10-18 06:03: 「resonance和echo的early/late合并在一个选项里，就叫early/late on，然后后面给两个选项按钮，就和user profile里面的surface/shallow/deep/abyss一样，可以多选，这里也可以都不选」). Any combination can be lit, none included. RESONANCE: only outside ±33 ms, i.e. not PERFECT (Kelvin 2026-10-12 22:04); ECHO: every ECHO. Stored together in `Settings.earlyLate` as before; two separate switches until 06:03 |
| Show ms | Off | diver | |
| Hit error meter | Off | diver | |
| Pause input | **Double-tap** (2026-10-12; **replaced:** Hold 0.35 s) | diver | `PAUSE INPUT` / `暂停方式`, description `CHOOSE HOW TO PAUSE` / `下潜时暂停键怎样触发` (`HOW THE PAUSE BUTTON RESPONDS IN A DIVE` until 2026-10-18 19:52); listed DOUBLE-TAP / HOLD 0.35 S / SINGLE TAP; rules in §5.12. Profiles saved earlier keep their value (no migration). |
| Show touches | Off | diver | `SHOW TOUCHES` / `显示触控`, a switch (Kelvin 04:06: 「空点标记改为"显示触控"，只有一个开一个关」; STRAY-TOUCH DOTS Auto / On / Off before), description `A RING UNDER EACH FINGER DURING A GAME` / `游戏中在每个手指下面显示一个圈`. Here, between PAUSE INPUT and HAPTICS, since 2026-10-18 19:52 (「把这个里面的"显示触控"移出来到"游玩"，放在暂停方式和震动之间」; on GAMEPLAY VISUALS from 04:06). On: a ring under every finger in a game (§5.10 SHOW TOUCHES); until 19:52 only the stray-touch dot where a touch hit no zone. The old AUTO reads as off. `Settings.strayTouches` STRAY_ON / STRAY_OFF |
| Haptics [P2] | Menus only | diver | Off / Menus only / Menus + judgements |

With the one EARLY / LATE ON row (06:03: 「这样保证gameplay也不用往下滚动」) GAMEPLAY has six rows and fits without scrolling (§24.12). JUDGEMENT DISPLAY moved to VISUAL and the NOTE GUIDE to the end of OTHER on 2026-10-18 03:25 (§24.8); the NOTE GUIDE left Settings on 05:14 (§24.11). LIVE COUNTERS has no row since 04:06 (「游玩里的实时计数取消掉，我从来没看到过这个在哪里」): the counters only show in the band layout (§5.2), which no landscape screen uses since the field stretches; `Settings.liveCounters` stays for old saves. LANGUAGE moved to the top of OTHER on 04:34 (「语言塞进其他里面」).

**NOTE GUIDE 音符图鉴** (owner decisions 2026-10-12; replaces the pause-menu legend of §5.12, §19.3 and §20.2). **Not shown since 2026-10-18 05:14** (Kelvin: 「把音符图鉴那一部分删了」, §24.11): no page adds the row any more; `NoteGuide` and `NoteGuideRow` were removed from the code in 0.1.0-beta.1. The rest of this block describes it as it was built. A display-only row (`screens/shell/NoteGuideRow` + `gameplay/NoteGuide`): the last row of GAMEPLAY until 2026-10-18 03:25, the last row of OTHER since. A dashed-outline panel titled `NOTE GUIDE` + `音符图鉴`, right-aligned subtitle `EVERY NOTE TYPE AND HOW TO PLAY IT`. Two columns of three rows: DROP 水滴, STREAM 水流, MINE 水雷 | TIDE 潮汐, TIDE STREAM 潮汐水流, LANE MINE 轨道水雷. Each row: a static miniature drawn by the game's own note painters at the current look (ground DROP head; short ground STREAM; plain mine at rotation 0; T4 ground wave over one lane at a fixed phase; TIDE STREAM head and band; short LANE MINE column with stripes and head cap), the name (EN, plus zh with Language = 中文), and the rule:

| Note | 中文 rule | EN rule |
|---|---|---|
| DROP | 落到线上时点一下 | `TAP WHEN IT REACHES THE LINE` |
| STREAM | 按住，同一根手指按到结尾 | `HOLD WITH THE SAME FINGER TO THE END` |
| MINE | 别碰；碰到会断连击 | `DON'T TOUCH: IT BREAKS YOUR COMBO` |
| TIDE | 整条线：线上任意位置点一下 | `TAP ANYWHERE ON ITS LINE` |
| TIDE STREAM | 任意手指按在线上就算 | `KEEP ANY FINGER ON THE LINE` |
| LANE MINE | 变红的时候别碰这条轨道 | `STAY OUT OF ITS LANE WHILE IT'S RED` |

No GROWING 伸长 and no AFTERIMAGE 残影 entry (the tutorial teaches both). The panel takes no input; a touch on it scrolls the page. It must fit at 16:9, 20:9, 4:3 and 16:10 in both languages at UI 1 and 1.3: a long EN rule may use the smaller raster or wrap to a second line; on a narrow (top-tab) layout the two columns may stack into one six-row column.

**As built.** Where the owner's words left a choice open, these defaults were chosen:
- The outline is neutral: dashed `ink3` α .7, 2 thick, dashes 10 / gaps 6, on the rows' edges inside the list (not the mock's yellow, not outset).
- `音符图鉴` shows only with Language = 中文; the subtitle stays English in both languages (UI_LABEL 16, decorative).
- Names: UI_LABEL at `max(20·k, dp 7.5)`, `ink1`. Rules: UI_REGULAR `ink2` at 18·k. Where one EN rule does not fit (UI 1.3 on the 1240-wide list, i.e. 1920×1080 and 1280×800), all six EN rules take 16·k together; wrapping happens only on forced narrow widths. 中文 always fits at 18·k.
- The miniatures are 64 wide, ground side, frozen, drawn by `NoteRenderer`'s own painters (`paintLaneNote`, `paintMine`, `paintTideT4`, `paintTideBand` + `paintTideCap` + `paintTide`, `paintLaneMineColumn`), so they follow any later look change.
- The panel is 294 tall at UI 1 (382 at UI 1.3, 374 with the smaller EN raster). At UI 1.3 the page scrolls to show it. Draw calls on the tab: +0 in EN, +1 in 中文 (the CJK page), +1 more where the page newly scrolls (its indicator).

**OTHER** (Kelvin 2026-10-18 02:27: 「账号和关于合并为一个"其他"」; 03:25: 「其他里面"只保存在这台设备上"去掉，K&K 两道信号一同下潜放在左下角那里。其他四个不要再分区了，直接放在一起，然后底下放音符图鉴」; 04:06: 「把K&K 两道信号一同下落那个放在开源许可证下面」; 04:34: 「语言塞进其他里面」; 05:14: 「把音符图鉴那一部分删了，K & K那一个版本信息大一点」; 05:28: 「居中然后竖直排列，上面那个大框写K&K，下面小框写版本，two signals one descent先不要了」; 05:39: 「往下挪一点，还可以再大一点」; 05:58: 「根据屏幕比例自动调整大小吧，K & K放左侧，version放右侧，还是原来那样。我要"其他"这个界面不用往下滚动」): the former ACCOUNT and ABOUT tabs as one list with no section headers: LANGUAGE (EN / 中文, §1.3; default EN; diver scope; on GAMEPLAY until 04:34), SAVE BACKUP / 备份存档 → EXPORT (copies the active diver as a `KK1:` code; BACKUP CODE / 备份码 until 14:29), RESET RECORDS (destructive hold 2 s, then the confirmation of §24.17: CONFIRM, then the diver's name typed in full; it takes the diver back to a new player's progress, shells and bought songs included), TUTORIAL → PLAY (replays the tutorial), OPEN-SOURCE LICENSES → VIEW (LANGUAGE, TUTORIAL and OPEN-SOURCE LICENSES have no description since 14:29), and last the brand row (`InfoRow.brand`, 05:58): `K & K` at the left in the welcome screen's logo face (DISPLAY_SOFT, LOGO glyph set, `ink1`) and the version (`VERSION 0.1.0-beta.1` / `版本 0.1.0-beta.1`, UI, `ink2`) at the right, both centred in the row, no hairline. The row takes the height the other rows leave in the list (`SettingRow.fills`), at most 300 and at least 64 (× uiScale). The title is cap 100 × uiScale (the 05:39 size) or half the row's height when that is less, and the version keeps the 05:39 ratio to it (30 beside 100, never under the minimum essential cap), so OTHER fits wherever its other rows leave the row's minimum (the limits are in §24.12). From 05:28 to 05:58 the block was centred, K & K over the version (cap 72, UI 24 and 200 tall until 05:39, then cap 100, UI 30 and 300 tall). `TWO SIGNALS · ONE DESCENT` is no longer shown there (it was the K & K line's second line until 05:28; between 05:14 and 05:28 that line was drawn 1.35 × larger). The NOTE GUIDE under it was removed on 05:14. The STORED ON THIS DEVICE ONLY line is gone (03:25). Diver switching, import, rename and creation live on the Profile screen (00:24: 「account只保留backup code的export按钮和reset records」).

**Licences page.** VIEW on OPEN-SOURCE LICENSES opens a licences page inside Settings (`SettingsScreen.buildLicenses`, data in `screens/shell/Licenses`): the game's own MIT licence, every song's composer, charter and cover artist (from `meta.json`), the three typefaces (Chakra Petch, Noto Sans SC and Rubik Glitch, all OFL 1.1) and the open-source software the game ships (libGDX, FreeType, LWJGL, GLFW, OpenAL Soft, stb, jemalloc, Jackson and the rest). A row with VIEW opens the full text from `assets/licenses/` or `assets/fonts/OFL-*.txt`, line by line from the list's left edge (centred as one block on 2026-10-21 from 01:3x until 02:47, §24.46, §24.49); Esc or the back arrow returns. Licence texts stay in English in both languages.

### 9.3 GAMEPLAY VISUALS screen [P1]

Kelvin 2026-10-18 15:17: 「我其实倾向于你重新设计游玩界面视觉参数这一档，把它做成类似谱面延时校准那样的独立界面」; 15:53, on the two mock-ups: 「方案1可以但是我感觉下半部分很空」; 16:17, notes on the second mock-up (ECHO and RESONANCE swapped, the strip's caption centred, no TAP TO TEST in the preview, a line under GLITCH INTENSITY, a new FC / AR INDICATOR); 16:19: 「加到右侧，把接近光晕去掉，感觉效果不明显」. `screens/VisualsScreen`, a shell screen like calibration (header with BACK and the glitch title, CURTAIN in and out, BACK returns to Settings' VISUAL).

- **Layout** (× UI scale): under the header a 24 gap, then three columns between the safe edges. Left `SCREEN` / `画面` and right `NOTES & JUDGEMENTS` / `音符与判定` (headers UI_LABEL 18 `ink2` with a hairline to the column's end, in a 44 band), each column as wide as leaves the preview the screen's shape, within 380–500 (460 at least in English, whose labels are longer) and so the preview keeps 320; 40 between columns. The rows of a column share its height (`SettingsPage.setSpread`), so both columns reach the bottom; they never scroll: when the rows need more than the height (a phone at a large UI scale) the column is laid out at a smaller scale, down to 0.7 of the UI scale.
- **Centre**: the live preview fills the column from the top of the side columns down to the JUDGEMENT WORDS strip (170 tall, 24 above it, a dark rounded panel with a hairline outline). The strip's caption `JUDGEMENT WORDS` / `判定字样` sits centred at its top; under it `ECHO`, `RESONANCE` and `LOST` (RESONANCE in the middle since 16:17) in their dive faces and colours at cap 46 (smaller when they do not fit), with equal space between and around them, each bursting every 1.5 s in turn (G2, LOST G2-red; RESONANCE keeps its G1 shimmer) at the glitch intensity being set. Under everything one line, UI_REGULAR 18 `ink3`: `CHANGES APPLY AT ONCE` / `改动马上生效` (`TAP THE PREVIEW TO TRY A HIT · ` / `在预览里点一下就能试打 · ` in front of it until 2026-10-18 19:52, when the preview stopped taking taps).
- **Live preview** (`screens/shell/LivePreview` + `gameplay/PreviewRun`; rewritten on 2026-10-18 19:52, Kelvin: 「现在的实时预览界面失真很严重，combo没有出现，然后判定区提示还在之前的那种0.1倍屏幕高度的旧版判定区。重写那一段的逻辑」, 「live preview就不要支持点击了，只看就行了。按键随机出现，每5~12combo断一次连击」): a whole game screen at the screen's own shape, scaled into the panel (centred, nothing stretched) and clipped to it. `PreviewRun` plays it on the game's own engine and renderers with the diver's settings, read every frame: the depth gradient under the BACKGROUND BRIGHTNESS scrim, both lines with the zone hint over the real ±0.4 zone (§5.7), the notes at NOTE SCALE with MULTI-PRESS ASSIST, the hit effects at PARTICLE QUALITY, the judgement word where JUDGEMENT POSITION puts it (glitch intensity, reduce flashing), COMBO, and the score at the top-left with FC / AR INDICATOR. Each round (since 2026-10-20 03:20: 「FC / AR指示切换的频率太慢了。预览部分每12组键一个循环，每三组单单双循环，每次都是第5组爆echo，第9组爆miss」) is 12 groups, one a beat at 120 BPM from beat 2: single, single, double, four times; each group on a random line, a single in a random lane, a double's lanes at least two apart (`PreviewRun.GROUPS`). The run presses every note itself, as a touch at its lane on its line within ±50 ms (a RESONANCE), presses group 5 85–120 ms early or late (an ECHO, `ECHO_GROUP`) and leaves group 9, a double, alone (two LOSTs, `LOST_GROUP`); a new chart follows 0.7 s after the last note, about every 7.2 s. So the score is light blue (ALL RESONANCE) until group 5 at about 3 s, purple (FULL COMBO) until group 9 is missed at about 5.2 s, then white, and light blue again with the next round. Until 03:20 a round was 40 random drops, one a beat, with a double 1–6 drops after the previous one (「出现双押后随机等待1~6次下落后再出现双押」), one press in six an ECHO and a LOST whenever the combo reached the round's target of 5–12, so the colour changed about once in 21 s. It takes no taps. The caption `LIVE PREVIEW` / `实时预览` sits at the bottom-right (UI 18 × UI scale, `ink2`; 「预览的Live Preview放在右下角，分数放在左上角」), and of the corner HUD only the score shows (no accuracy since 2026-10-20, §24.31; no progress hairline, pause control, title or time). Since 2026-10-20 03:20 (「手机上的实时预览分数位置不对」) those corners sit the minimum margin (24) from the screen's edges, without the device's cutout insets and EXTRA EDGE PADDING, which, scaled into the panel with the rest of the screen, pushed the score in from the panel's corner on phones (`GameplayHud.setScoreOnly`; inferred from the code, not seen on Kelvin's phone). Since 06:27 the score's size and height follow the top band as in a game (§24.32, §24.33), measured from the screen's own top edge. Until 19:52 the panel drew its own mini playfield (§24.15): a fixed loop of six notes, a 9-digit score at the top-right and the caption at the top-left, a zone hint still over the ±0.1 band, no COMBO, and a tap judged as in a dive that fired a hit effect on the lane it fell in.
- Changes apply at once (`Main.applySettings`) and leaving marks the diver and the device dirty and saves, as in Settings.
- Until 15:17 the preview was a 600×340 panel beside the GAMEPLAY VISUALS page of the list (below it on 4:3), and on the VISUAL tab before 04:06 (on TIMING until that tab went away).

### 9.4 Calibration screen [P0]

**Click track,** synthesised at first use into `local("cache/calib_120.wav")`:
- 44.1 kHz, mono, 16-bit, 120 BPM
- 4 lead-in clicks + 24 measured
- each click is a 1 kHz sine, 30 ms long (2 ms attack, exponential decay τ 6 ms); every 4th beat is an accent at 1.5 kHz
- about 15 s

It plays through `AudioService.playFile` on the **same `Music` path and `SongClock` as songs**, so the measured offset is the one gameplay needs.

**Procedure:**
- **Lead-in** shows a visual 4-3-2-1. **During the measured beats there is no visual beat cue**, because visuals bias the result.
- **Taps:** taps anywhere are timestamped with `SongClock.timeAtNanos(Gdx.input.getCurrentEventTime())`. Taps more than ±250 ms from the nearest beat are dropped, and ≥ 16 valid taps are required.
- **Screen:**
  - the whole screen is a tap pad with a ripple per tap
  - a scatter strip (−300..+300 ms since 2026-10-21, −150..+150 before; ±150 ms takes three quarters of its width and the two stretches beyond it share the rest, fainter, §24.50) at y 200; each tap drops a dot onto it with a spring, coloured `#66F0FF` within ±66 and `#B49CFF` beyond up to ±150; a tap beyond ±150 is a hollow grey ring (`ink2`, since 2026-10-21 03:04)
  - a running median caret
  - 24 progress dots
- **Result:**
  - `offset = round(median(tap − beat))`; spread = MAD.
  - If MAD > 35 ms: `INCONSISTENT · TRY AGAIN`.
  - Otherwise: `+42 ms · YOUR TAPS LAND 42 ms AFTER THE CLICK` with **APPLY** / `应用` (to the active output; `APPLY TO SPEAKER` until 2026-10-20 22:37), RETRY and VERIFY. The value and its `ms` are centred together (the value's right edge sat on the centre line until 2026-10-21, §24.45); so is the intro's current offset.
- **VERIFY** [P1] loops 16 gameplay-style notes on both lines to the click track with the new offset, showing live judgements and ±5 nudges. The judgement words are RESONANCE / ECHO / LOST in both languages, as in a game (共鸣 / 回响 / 迷失 in 中文 until 2026-10-21, §24.46).
- **Convention:** `judgeMs = clock.timeAtNanos(event) − audioOffsetMs`. A positive offset means the player hears audio late. Calibration measures the raw clock, so APPLY sets `offset = median` directly: it stores `round(median(tap − beat))`, clamped to −300..+300 ms, as the **active output's** audio offset in `device.json` (SPEAKER, WIRED or BT each keep their own).
- **Quick mode** (first run, where the whole screen is step 2 in a card, §10.1; step 3 from 2026-10-20 22:30 to 2026-10-21): 12 measured beats, ≥ 8 valid, no VERIFY; APPLY moves on to step 3.
- **Texts removed 2026-10-20 22:37** (§24.44): the intro's `USE THE SPEAKER OR HEADPHONES YOU PLAY WITH`, its foot `THE SCREEN STAYS STILL WHILE YOU TAP · VISUAL CUES WOULD BIAS THE RESULT` and the run's `LISTEN · THE SCREEN STAYS STILL ON PURPOSE`. The content is `screens/CalibrationPanel`; `CalibrationScreen` is its full-screen shell, and the first run lays the same panel out in its card.
- **Where gameplay reads it** (ARCHITECTURE §6): `GameplayScreen` reads the audio offset of the active output and `visualOffsetMs` in `begin()` and again when the pause menu resumes, so a change applies to the next run or after a resume. (Since variant B the pause menu has no offset row, §5.12; the offset is changed in Settings → AUDIO → TIMING or here. Since 2026-10-18 the visual offset is 0, §9.2.)

### 9.5 Visual offset test [P1] (removed 2026-10-18)

**Removed** with the visual offset (§9.2). The calibration screen has the audio page only. The text below is kept as the record of what it was.

On the second tab of the calibration screen, a ring flashes on each click. The player nudges `visualOffsetMs` in ±5 steps until flash and click line up.
- **Convention (shared with gameplay and Settings):** `visualTime = judgeTime + visualOffsetMs`. The ring flashes when `clock.nowMs() − audioOffsetMs + visualOffsetMs` crosses each beat, so the value the player settles on means the same thing in gameplay, where notes, lines, lanes and the touch hit-test geometry are all evaluated at the visual time.
- **Sign:** a **positive visual offset makes notes (and the flashes) arrive earlier**; it compensates a display that shows frames late, so set it to the display lag. Settings states it as "+ MEANS NOTES ARRIVE EARLIER". Range −150..+150 ms, stored per device.

### 9.6 Post-play offset suggestion

See §6.5.

### 9.7 VISUAL HIT POINT calibration [P1]

Kelvin 2026-10-20 03:20: 「下面的校准会单独弹出来一个界面」 (§24.29). `screens/HitPointScreen`, a shell screen like calibration (header with BACK and the glitch title `VISUAL HIT POINT` / `目押校准`, CURTAIN in and out), opened by CALIBRATE… on VISUAL's first row; BACK returns to VISUAL. No music plays on it (the menu music and any preview stop as it opens).

- **Intro**: `TAP WHEN A KEY REACHES THE LINE` / `看到按键到线就点` (UI_BOLD 44); `NO SOUND · TAP ANYWHERE THE WAY YOU PLAY BY EYE` / `全程没有声音 · 按你平时目押的习惯点屏幕任意位置` and `4 WARM-UP KEYS, THEN 16 MEASURED` / `先落 4 个练习，再测 16 个` (UI_REGULAR 24, `ink2`); a diagram of a key at the judgement time at its real size (200 × 40) on a panel 600 × 220: the line labelled `LINE` / `判定线` at its left end on the side away from the key, the key, its dashed midline labelled `MIDDLE` / `按键中线` left of the key and never on the line, and the gold distance between them in px beside it, or above it when they are under 40 apart (to scale for −60..+80, clamped beyond); `CURRENT · 0 px` / `当前 · 0 px`; START (PRIMARY, 440 wide, also SPACE or ENTER); at the bottom `0 = THE LINE THROUGH THE KEY'S MIDDLE (DEFAULT) · 20 = THE KEY'S BOTTOM EDGE ON THE LINE` / `0 = 判定线横穿按键中间（默认）· 20 = 按键下边缘碰线` (`ink3`; the default was 20 until 2026-10-20 22:37), or `RUN INTERRUPTED · TAP START TO TRY AGAIN` / `校准被打断 · 点「开始」再试一次` in gold after the app went to the background mid-run.
- **Run**: silent on purpose (a sound would make it the audio calibration, §9.4). 20 keys, 4 warm-up then 16 measured, one every 600 ms from 1.5 s, fall onto a ground line at y 120 at 1.08 units a ms (the default approach: 1080 in 2 beats at 120 BPM), in four lanes 240 apart in a fixed order, drawn like DROPs at NOTE SCALE with the current hit point. A tap anywhere (or any key) counts for the nearest key within ±250 ms, once per key, timed by the touch event's own clock; a ripple marks it and nothing says early or late, which would bias the taps. At the left: `WARM-UP` / `练习`, then `KEEP TAPPING` / `继续点`, `TAP WHEN A KEY REACHES THE LINE`, 16 progress dots and `VALID TAPS · n / 16` / `有效点击 · n / 16`. BACK during the run returns to the intro.
- **Result**, 0.6 s after the last key: a tap `t` ms after its key's judgement time saw the key's midline `current − 1.08 · t / noteSize` px from the line (positive above it); the result is that distance for the median tap, rounded and clamped to −100..+100, the value that puts the key's middle where the player taps. A player who taps when the line crosses a key's middle gets 0 whatever the current value. **In px only since 2026-10-20 22:37** (「目押校准不要再有“毫秒”这个标准了啊，下面的散点图指标应该是px」, §24.44; the line and the strip were in ms before). Shown: the value (NUMBER 96) and `px`, centred together (since 2026-10-21, §24.45), `WHEN YOU TAP, THE KEY'S MIDDLE IS 32 px BELOW THE LINE` / `你点的时候，按键中间在判定线下方 32 px` (or `ABOVE` / `上方`, or `WHEN YOU TAP, THE LINE RUNS THROUGH THE KEY'S MIDDLE` / `你点的时候，判定线正好穿过按键中间`), `SPREAD ±4 px · 16 OF 16 TAPS` / `偏差 ±4 px · 有效 16 / 16 次` (the median absolute deviation as the distance a key travels in it), the diagram at the new value, APPLY / 应用 and RETRY / 重试, and the −150..+150 px strip of where each tap saw the midline, with their median (`TapStrip.inPixels()`: `BELOW THE LINE` / `线下方` at the left, `ABOVE THE LINE` / `线上方` at the right, unit `px`, no RESONANCE band). Fewer than 12 valid taps: `NOT ENOUGH TAPS` / `有效点击不够`; a spread over 35 ms: `INCONSISTENT · TRY AGAIN` / `不稳定 · 再试一次` over `YOUR TAPS SPREAD ±38 px` / `点击偏差 ±38 px`; both offer RETRY only. APPLY stores the value for the active player, saves, shows the toast `VISUAL HIT POINT · 0 px` / `目押校准 · 0 px` and marks the result `APPLIED` / `已应用`.
- The value is a distance, so on a chart or speed faster than the default the same value is a shorter time.
- **In the first run** (step 3 since 2026-10-21, step 4 from 2026-10-20 22:37, §10.1) the same content (`screens/HitPointPanel`, which `HitPointScreen` wraps) is laid out in the step's card: the title and START where the audio calibration had them, the two intro lines joined into one, no diagram on the result; during the run the line lies 100 above the card's bottom edge across the card, the keys fall in its right part and fade in under the card's top edge and out just under the line, and the text column sits at the card's left. APPLY starts the tutorial.

---

## 10. First run and tutorial

### 10.1 First-run flow [P0]

| Step | Screen | Content |
|---|---|---|
| 1 | Welcome | tap |
| 2 | **COMFORT** (FirstRun step 1 of 3 since 2026-10-21, §24.45; progress dots at the top right; no header, so no title and no BACK icon, since 2026-10-20, §24.42; the system BACK = previous step, from step 1 back to Welcome; each step's card centred on the screen. **No WHO'S DIVING? step since 2026-10-21** (「第一部直接不要了，默认随机一个头像就可以了」): the player keeps the default `DIVER-` + 4 hex and the random avatar of a fresh install (`AvatarSpec.fromSeed`, options unlocked at 0 m) and changes both on the Profile screen; that step had the avatar, REROLL and the swatches, its name field until 2026-10-20 22:37, and `WE FOUND EARLIER RECORDS — …` when `user.json` existed (an old save that could not be read is now a toast at start-up)) | glitch intensity slider with a sample (60 %; the English `SIGNAL` in both languages since 2026-10-20; no `LIVE SAMPLE` label under it and the gaps above and below it tighter since 22:30, card top to sample 236, sample to the first row 170), Reduce motion, Reduce flashing, and the line `THIS GAME USES FLICKERING GLITCH EFFECTS. YOU CAN TURN THEM DOWN HERE.` CONTINUE / SKIP. |
| 3 | **CALIBRATION** (step 2 since 2026-10-21, step 3 from 2026-10-20 22:30; **TUNE YOUR SONAR** before, with `USING BLUETOOTH? IT ADDS DELAY.`, an output selector [SPEAKER \| WIRED \| BT] and CALIBRATE) | the audio calibration of §9.4 itself (「直接把延迟校准的设置界面搬过来，放在现在那个框里」) in a card 1400 × 700 (narrower or shorter screens shrink it; its layout scales with the height), quick mode, no VERIFY. **APPLY** moves on to the visual hit point (「音押校准点“应用”……以后进入目押校准界面」). SKIP moves on too and, unless the player is calibrated, leaves the reminder: a toast on the map after the 2nd play (once). |
| 4 | **VISUAL HIT POINT** (step 3 since 2026-10-21, step 4 from 2026-10-20 22:37) | the visual hit point calibration of §9.7 in the same card. **APPLY** starts the tutorial (「目押校准完了进introduction」); so does SKIP. |
| 5 | Tutorial | DIVE → IntroductionScreen (§10.2). A **SKIP** pill (264×56, label `HOLD TO SKIP`, English in both languages; hold 0.6 s) sits right-aligned at SR at the top of the middle band, under the pause corner (top y 828; touch box y 744–856), after 10 s on first run and at once on replays. A SKIP completed during the 3-2-1 resume countdown ends the run at once: `GameplayScreen.finishNow()` clears the countdown, so the song can no longer resume behind the result. **Replaced 2026-10-12:** "right of the pause control", which is inside the sky zone at 16:9, where a touch plays notes first. A press on the pill is never a pause tap (the pause box starts at y 968). |
| 6 | Tutorial result | §6.6. Nothing recorded; first-time EXP (enough for Lv 2). |
| 7 | Map | IRIS → first layer-reveal sweep, then coach marks, each dismissed by the first real interaction: a ghost finger (procedural dot with trail) demonstrates a drag (1.5 s, `DRAG TO EXPLORE`); a pinch hint (`PINCH TO ZOOM`); a pulse on the first node (`TAP A SONG`). |
| later | Later launches | Welcome → Map directly. The tutorial is never forced again. |

### 10.2 Tutorial (`IntroductionGuidelines`, `GuidingText`) [P0]

**As built 2026-10-17: the owner's chart.** Kelvin hand-charted the Introduction (2026-10-17 05:24, 102 notes) and asked for the captions to be laid out again for it (06:00: 「你还要根据新的introduction重新排版文字解说」). The opening, the HUD tour, the judgements and the two lines are unchanged from tutorial v3 (2026-10-12, owner: 「我想要先测试游戏的introduction，和一个基本的chart是否有问题。introduction你可能要重新写。要包括所有基本的玩法。」); the lessons are re-timed and re-placed to the new chart's notes. The first redesign's lesson list (v2) and the copy and HUD-tour tables below stay only as history.
- **Chart** `assets/Introduction/Surface.json`, hand-charted by the owner (the v3 generator no longer owns it): format v2, 8 lanes, 120 BPM, `offsetMs` 27 (beat b plays at 0.027 + 0.5·b s), notes on a quarter-beat grid, the first at 76. 102 notes: 42 DROP, 14 STREAM, 16 TIDE, 8 TIDE STREAM, 20 MINE, 2 LANE MINE (80 judged). The last note ends at 252 (126.027 s).
- **Captions** in EN and 中文 (Language = 中文 picks the Chinese set; judgement words and the EARLY / LATE tags stay English, as in the HUD), timed and placed by hand in `IntroductionGuidelines`. A centred caption is gone before its notes appear; where the gap is too short the caption sits on a side or half-screen plate over lanes that stay free while it shows, and some English bodies break into two to four lines to fit. Story and requiem keep the owner's copy. The dialogue moved to clear the outro's notes (her reply 228.5 → 230, `"YOUR degenerate soul, Kelvinizer"` 236 → 238, the last line 240 → 240.5, both quotes on plates over free lanes); since 2026-10-21 its four lines are empty placeholders at those beats and places, waiting for new copy (§24.51). FINAL DIVE is dropped: no free beat between the TIDE-line lesson and the outro.
- **Checker** (a development tool outside the repo): it moves every note as drawn and reports any plate over an unjudged note, outside the safe area or over another caption at 16:9, 20:9 and 4:3. The only reports are the two closing dialogue lines (no plate) over the closing chords, which pass behind them (both empty since 2026-10-21, §24.51).
- **Lessons** (beats; plate positions at 16:9):

| # | Lesson | Captions | Notes | Content |
|---|---|---|---|---|
| — | Opening | 0–32 | — | `WELCOME TO`, `Kelvinizer and Kathlina` and the three story lines |
| T0 | HUD tour | 32–40.5 | — | one label per beat beside its corner: `SCORE · TOP LEFT` / 分数 · 左上 (`SCORE & ACCURACY · TOP LEFT` until 2026-10-20, §24.31), the pause label worded for Settings → PAUSE INPUT: `DOUBLE-TAP HERE TO PAUSE` / 双击这里暂停 (the default), `HOLD HERE TO PAUSE` / 按住这里暂停 or `TAP HERE TO PAUSE` / 点击这里暂停; only in double-tap mode the control plays a demo double tap every 1.6 s from 34 to 39.5, never pausing; `COMBO · CENTRE` with a ghost combo, `SONG & DIFFICULTY · BOTTOM LEFT`, `TIME · BOTTOM RIGHT` |
| T1 | Judgements | 40.5–52.5 | — | RESONANCE ±66 ms, ECHO ±133 ms, LOST, and the EARLY / LATE line; demo hits beside the words (five cyan water rings, one violet ring); a demo ECHO with EARLY or LATE at the centre every 1.5 s |
| T2 | Lines and zones | 52.5–64 | — | `NOTES FALL TO THE BOTTOM OR RISE TO THE SURFACE` with ghost DROPs falling and rising in the outer lanes and the two line labels; `HALF THE SCREEN EACH` / `UPPER HALF: SURFACE JUDGEMENT · LOWER HALF: BOTTOM JUDGEMENT` with the zone hint pulse and the stray-touch dot demo |
| L1 | Ground DROPs | 64–73.5, centred | 76–80 | `TAP THE LOWER HALF WHEN THEY LAND`; lanes +3, +2, then −2 and +1 together |
| L2 | Sky DROPs | 80.5–88, right plate over lanes +2..+4 | 84–88 | `THEY RISE: / TAP THE UPPER HALF`; lanes +1, −1, then −4 and −2 together |
| L5 | TIDEs | 88.5–94, centred | 96–104 | `TAP ANYWHERE IN ITS HALF OF THE SCREEN`; ground, sky, sky, then a ground and a sky TIDE together |
| L3 | STREAMs | 104.5–110, left-half plate | 108–123 | `KEEP THE SAME FINGER DOWN / UNTIL THE STREAM ENDS`; ground +3, +2, then −2 and +1 together; sky +2, +3, then −4 and −2 together |
| L7 | TIDE STREAMs | 120–126.5, right-half plate | 128.25–150 | `KEEP ANY FINGER IN ITS HALF / — SWAPPING IS FINE`; ground, sky, ground, then a ground and a sky TIDE STREAM held 136–150, their opacity pulsing every beat |
| — | Requiem | 136–150 | (the held pair) | the two requiem verses, over the held TIDE STREAM pair |
| — | Chorus | — | 152–182 | TIDEs at 152 and 156; TIDE + DROP at 160 (ground, lane −2) and 164 (sky, lane +2), one tap for both; a ground STREAM 168–174 (lane −2) and a sky one 176–182 (lane +2) under DROPs |
| L8 | MINEs | 176.5–190, right-edge plate over lanes +3 / +4 | 184–190 | `DON'T TOUCH: / THEY BREAK / YOUR COMBO, / NOT YOUR SCORE`; mines at 185 (+2), 187 (+1), 188 (−1), 189 (+1) between ground DROPs; the demo mine left of the heading (lane +3) is touched by a ghost finger every 1.6 s (its red LOST shows above the demo mine, not in the centre, because real notes are playing) and is gone before the first mine appears (183) |
| L8b | LANE MINEs | 190–200, left-edge plate over lanes −4 / −3 | 192–200 | `THEY CLOSE / A WHOLE LANE. / STAY OUT / WHILE IT'S RED`; a 4-beat LANE MINE in ground lane −2 (192–196), then one in sky lane +2 (196–200), DROPs beside them; each closed lane's outline (over its line's zone) pulses from when its lane mine appears until its block ends |
| L12 | TIDE as the line | 200.5–214, left plate over lanes −4..−2 | 202–214 | `A TIDE CAN BE THE LINE` / `TAP AS NOTES LAND ON IT: THEY JUMP TO THE LINE` (潮汐也能当判定线 / 音符碰到潮汐时就点，它会瞬移到判定线上); three ground TIDEs appear 300 above the line, each with a DROP in lane +1 that lands on it, and both jump to the line on that beat (202, 204, 206); then three sky TIDEs 300 under the sky line with DROPs in lane −1 (210, 212, 214); one tap judges each pair (owner 2026-10-16) |
| ~~L10~~ | ~~More to discover~~ | removed 2026-10-20 (gallery p.26: 「删了」, §24.42); was 216.5–222, left plate over lanes −4..−2 | — | was `AFTERIMAGES, / MOVING NOTES — / YOU'LL MEET THEM IN SONGS` (only mentioned) |
| E | Outro | dialogue 224–248 (empty placeholders since 2026-10-21, §24.51) | 216–252 | a ground TIDE STREAM 216–236 under DROPs and STREAMs; his quote on a plate over lanes −3..−1 (224–236), hers over lanes +2 / +3 (230–237); mines in lanes +1 and +4 every half beat 232–239.5; the soul line (238) and the last line (240.5) over the closing chords 240–247.5; `K & K` 248–251 (the HUD combo gives way under it, §24.52) |

- **Sync pearls in the chart:** 48 notes share a beat (NoteShapes.showsPearl): every pair above, the eight TIDE + DROP joints (160, 164 and the six L12 pairs) included.
- **Demo windows** (`IntroductionGuidelines` constants): zone hint 58.5–136 (stray dots to 123) and 150–164; no sync demo and no stack badge (−1: no GOLD PEARLS caption, no stacked TIDEs); demo mine 176.5–182.5; LANE MINE outlines 190–200 for the lane mines at 192 (ground −2) and 196 (sky +2).
- **Screen hooks** (`GameplayScreen`, protected, for the tutorial): `drawNoteDemo`, `noteDemoHeight`, `noteDemoWidth`, `drawMineDemo`, `demoMineTouch`, `extraTouchDown` (the SKIP pill; it sees a down only when `PauseControl.judges` says the down did not count, before the pause control). `GameplayHud.demoPauseDoubleTap` and `demoJudgement` play the T0 / T1 demos, and `demoMineWord` puts the demo mine's LOST above the mine in every mode but Off; `GuidingText.Style.TIMING` draws the EARLY / LATE line; `GuidingText.Anchor.TIDE_STACK` and `HUD_TIME` were added.
- **Draw calls:** the grade lesson peaks at 11 (EN) / 12 (中文) at beat 44.6, against 8 there in v2 (the demo word on the HUD's word path +2, the TIMING row's bold page +1); elsewhere within one call of v2 (中文 within two).

**Copy changes** (first redesign, history; **replaced** by the v3 captions above):

| Old | New |
|---|---|
| "Hit the LOWER HALF of the screen" | `TAP NEAR THE GROUND LINE WHEN NOTES LAND` |
| "Hit the UPPER HALF of the screen" | `TAP NEAR THE SKY LINE WHEN NOTES ARRIVE` |
| hold variants | `HOLD NEAR THE … LINE UNTIL THE NOTE ENDS` |

- During the zone beats the ±108 zone hint pulses at α .25 (1 s `sine`) and stray-touch dots are forced on (v3: 58.5–111 and the TIDE lessons).
- `TAPS FAR FROM A LINE DON'T COUNT` became v3's `FAR TAPS DON'T COUNT`, still shown with a stray-touch dot demo.

**HUD tour** (**replaced 2026-10-12** by the T0 row above: each label is anchored to its corner element through `GuidingText.Anchor.HUD_SCORE`, `HUD_PAUSE`, `HUD_CHIP` and `HUD_TIME`, resolved by `GameplayHud.targetX / targetY`, so the tour follows the corner layout at every aspect). The first redesign's table, for history:

| Line | Position |
|---|---|
| `SCORE · TOP RIGHT` | (SR − 300, 700) |
| `COMBO · CENTRE` | (960, 640) |
| `HOLD HERE TO PAUSE` (ring demo loops) | (SL + 260, 760) |
| `SONG & DIFFICULTY · BOTTOM LEFT` | (SL + 300, 330) |

**Grades:** `RESONANCE — RIGHT ON THE BEAT (±66 ms)`, `ECHO — CLOSE (±133 ms)`, `LOST — MISSED` (v3 adds the EARLY / LATE line `— TOO SOON / TOO LATE`). Each word is drawn in its own face and colour, with a demo hit effect beside it.

**Fonts:**
- Instructions: UI 30 on a `panel @ .6` plate placed in the middle band (y 300–780).
- Headings: UI_BOLD 40.
- Story beats ("Kelvinizer and Kathlina", the quote): RUBIK 48–72.
- The finale `K & K`: DISPLAY_SOFT 140 with the LOGO glyph set `K& ` (it read `K and K` until 2026-10-21, and that set never had the lowercase letters, §24.52). This removes the 300-px Rubik font (about 44 MB of atlas).

**GuidingText:**
- world coordinates through the ExtendViewport, anchored to safe edges
- pre-split lines, one cached GlyphLayout per entry
- never mutate a shared font's alpha (use `Fonts.draw` with alpha)

---

## 11. Level, rating and ranks [P0]

> **Superseded 2026-10-17 by §23** (shells, depth and fifteen grades); kept for history.

### 11.1 Chart rating ρ(c, s)

`c` = chart constant (parsed from its String, falling back to 1.0); `s` = score from 0 to 10,000,000.

```java
static float chartRating(float c, int s) {
    if (s >= 10_000_000) return c + 1.0f;                                         // PURE
    if (s >=  9_800_000) return c + 0.5f + 0.5f * (s - 9_800_000) / 200_000f;
    if (s >=  9_500_000) return c +        0.5f * (s - 9_500_000) / 300_000f;
    return Math.max(0f, c - (9_500_000 - s) / 500_000f);
}
```

The curve is continuous at 9.5 M (c), 9.8 M (c + 0.5) and 10 M (c + 1.0).

| Score | 10M | 9.9M | 9.8M | 9.65M | 9.5M | 9.0M | 8.5M | 8.0M | 7.0M |
|---|---|---|---|---|---|---|---|---|---|
| c 3.7 | 4.70 | 4.45 | 4.20 | 3.95 | 3.70 | 2.70 | 1.70 | 0.70 | 0 |
| c 10.0 | 11.00 | 10.75 | 10.50 | 10.25 | 10.00 | 9.00 | 8.00 | 7.00 | 5.00 |
| c 15.4 | 16.40 | 16.15 | 15.90 | 15.65 | 15.40 | 14.40 | 13.40 | 12.40 | 10.40 |
| c 16.2 | 17.20 | 16.95 | 16.70 | 16.45 | 16.20 | 15.20 | 14.20 | 13.20 | 11.20 |

### 11.2 Player rating R

- **Formula.** `R = (sum of the best 10 ρ over distinct (song, difficulty)) / 10`. Empty slots count as 0, so new players rise as they explore.
- `Rating.TOP_N = 10`; revisit (raise to 20) when the library passes about 60 charts.
- **Storage.** `ChartRecord.bestRating = ρ(c, bestScore)`, recomputed on load if a constant changes.
- **Display.** Floored to 0.01.
- **Exclusions.** Autoplay and tutorial never count.
- **Today's ceiling.** The best-10 constants are 16.2, 16.0, 15.9, 15.9, 15.8, 15.7, 15.5, 15.4, 15.2, 14.9, which average 15.65, so the maximum **R = 16.65**. That matches the "~0–16" target.

### 11.3 Depth (metres)

Piecewise-linear through the **same zone boundaries as the map layers**, so the map and the player's skill share one unit.

| R | 0 | 2 | 6 | 11 | 14 | 17 |
|---|---|---|---|---|---|---|
| metres | 0 | 200 | 1,000 | 4,000 | 6,000 | 11,000 |

- Above R 17: `11,000 + (R − 17) × 1,666.7`.
- Displayed as `floor(depth)` with grouping: `2,560 m`.
- **Metres per R:** 100 (SHORELINE), 200 (TWILIGHT), 600 (MIDNIGHT), 667 (BLACKWATER), 1,667 (TRENCH). Progress accelerates the deeper you are, and early gains are real (R 0.5 = 50 m, R 1 = 100 m).

**Examples:**

| R | Depth |
|---|---|
| 2.38 | 276 m (a Surface-only player) |
| 3.55 | 509 m |
| 8.0 | 2,200 m |
| 10.0 | 3,400 m |
| 12.0 | 4,666 m |
| 15.0 | 7,666 m |
| 16.0 | 9,333 m |
| **16.65 (today's max)** | **10,416 m** |

**Challenger Deep (10,935 m) needs R ≈ 16.96.** That is just out of reach with today's charts, which makes it aspirational.

**Where everything shows:**
- the map badge, Welcome chip, result strip and profile
- the gauge YOU marker and the YOU line

Deltas read `▼ +24 m` (a down-triangle means a gain, always labelled).

### 11.4 Rank titles (tiers by depth)

| Band | Metres | ≈ R | Title (EN / ZH) | Tier I / II / III start (m) | Emblem colour |
|---|---|---|---|---|---|
| SHORELINE | 0–199 | 0–1.99 | **SNORKELER** / 浮潜者 | 0 / 70 / 140 | `#7FE7FF` |
| TWILIGHT | 200–999 | 2.00–5.99 | **LANTERNFISH** / 灯笼鱼 | 200 / 470 / 730 | `#6FA8FF` |
| MIDNIGHT | 1,000–3,999 | 6.00–10.99 | **ECHO HUNTER** / 回声猎手 | 1,000 / 2,000 / 3,000 | `#8F7BFF` |
| BLACKWATER | 4,000–5,999 | 11.00–13.99 | **PRESSURE BORN** / 承压者 | 4,000 / 4,670 / 5,330 | `#C77DFF` |
| TRENCH | 6,000–10,934 | 14.00–16.95 | **TRENCH WALKER** / 海沟行者 | 6,000 / 7,650 / 9,300 | `#FF5CA8` |
| BELOW | ≥ 10,935 | ≥ 16.96 | **RESONANT** / 共鸣者 | single tier | iridescent + G1 |

- **Display.** `ECHO HUNTER II`.
- **Emblem.** A procedural hexagon in the band colour with 1–3 chevrons for the tier.
- **Rank-up** fires when the title or tier changes.

### 11.5 Micro-goal inversion (song card)

The next step is defined as **ΔR = +0.05**, which means this chart's contribution must rise by 0.5.

```java
// top10: best ρ values sorted descending, length 10, zeros for empty slots. rhoCur: this chart's best ρ, or -1.
static int microGoal(float c, float rhoCur, float[] top10) {
    boolean inTop = rhoCur >= 0 && rhoCur >= top10[9];
    float need = (inTop ? rhoCur : top10[9]) + 0.5f;
    if (need > c + 1.0f) return -1;                      // unreachable below PURE
    float s;
    if (need <= c)              s = 9_500_000 - (c - need) * 500_000;
    else if (need <= c + 0.5f)  s = 9_500_000 + (need - c) * 600_000;
    else if (need <  c + 1.0f)  s = 9_800_000 + (need - c - 0.5f) * 400_000;
    else                        s = 10_000_000;
    return (int) Math.min(10_000_000, Math.ceil(Math.max(0, s) / 1000.0) * 1000);
}
```

- **Shown metres** = `floor(depth(R + 0.05)) − floor(depth(R))`.
- **Example.** c 15.4, best 9,750,000 (ρ 15.817), R 15.00: need = 16.317 → **9,967,000**, and +0.05 R = 7,750 − 7,666 = **+84 m**. Copy: `NEXT ▼ +84 m AT 9,967,000`.
- **Fallbacks:**
  - If −1: the gain at PURE, shown as `PURE = ▼ +12 m`.
  - If that gain is 0: `MAXED`.

### 11.6 EXP per play

```
L    = clamp(totalNotes / 1700, 0.5, 1.2)
base = (30 + 6c) × (0.4 + 0.6 × (score / 10,000,000)²) × L
EXP  = round(base) + bonuses
```

| Bonus | EXP |
|---|---|
| first play of this chart + difficulty | +40 |
| new best (not on first play) | +20, or +40 if the grade improved |
| first FULL COMBO on this chart | +50 |
| first ALL RESONANCE (PURE) on this chart | +100 |
| first completed play of the local day | +50 |
| tutorial, first completion | flat **150** (no base); later completions 0 |
| autoplay, quit from pause, retry before the end | 0 |

**Examples** (1,693 notes, L ≈ 1.0):

| Play | EXP |
|---|---|
| Surface c 1.4 at 9.0 M, first play | 34 + 40 = **74** |
| Shallow c 10.0 at 9.5 M, repeat | **84** |
| Deep c 15.4 at 9.8 M, new best with grade A+ → S | 119 + 40 = **159** |
| Abyss marsh mally c 16.2, PURE (2,026 notes, L 1.19) | 152 + 100 + 40 = **292** (+50 if also its first FC) |

### 11.7 Level curve

- **Formula.** `need(L → L+1) = round(100 + 30·L + 2.5·L²)`, using `Math.round`.
- **Cap.** Lv **99**. At the cap the bar reads `MAX` and EXP keeps accumulating.

| Level | EXP to next | Cumulative to reach | ≈ plays (~100 EXP) |
|---|---|---|---|
| 1 | 133 | 0 | 0 |
| 2 | 170 | 133 | 1 (the tutorial alone) |
| 3 | 213 | 303 | 3 |
| 5 | 313 | 776 | 8 |
| 10 | 650 | 2,965 | 30 |
| 15 | 1,113 | 7,091 | 70 |
| 20 | 1,700 | 13,780 | 140 |
| 30 | 3,250 | 37,345 | 370 |
| 40 | 5,300 | 78,660 | 790 |
| 50 | 7,850 | 142,725 | 1,430 |
| 99 | — | 951,727 | long tail |

**Meaning.** Level measures time and dedication, and unlocks cosmetics (§8.6). Depth measures skill. The UI never mixes the two: LV is always a chip, and depth is always metres.

---

## 12. Grades and stamps [P0]

> **Superseded 2026-10-17 by §23** (shells, depth and fifteen grades); kept for history.

> Mines (§5.15): grade is score-based, so a touched mine never lowers the grade; it only removes the FULL COMBO / ALL RESONANCE badges, because it breaks combo. FULL COMBO = `maxCombo == x` **and no mine touched**: a MINE or LANE MINE touched before the first note or after the last one also removes both badges (`ScoreKeeper.isFullCombo()`), although it only resets an empty or finished combo.

ECHO weight is **0.65** (Params), so with N = 1,693 notes each ECHO costs about 2,067 points and each LOST about 5,907.

| Grade | Score ≥ | ≈ ECHO allowed (all else RESONANCE) | ≈ LOST allowed | Colour | Stamp extras |
|---|---|---|---|---|---|
| **PURE** | 10,000,000 (every note RESONANCE) | 0 | 0 | iridescent cycle `#66F0FF → #B49CFF → #FFD27A` (3 s) | triple ring; shimmer every 2.5 s; 40 rising motes; constant G1; word `PURE` at cap 104 |
| **S+** | 9,900,000 | 48 | 16 | `#FFD27A` | 3 rings; one shimmer pass |
| **S** | 9,800,000 | 96 | 33 | `#FFE7B0` | 3 rings |
| **A+** | 9,500,000 (rating = constant) | 241 | 84 | `#7FE7FF` | 2 rings |
| **A** | 9,200,000 | 386 | 135 | `#5CB8FF` | 2 rings |
| **B** | 8,800,000 | 580 | 203 | `#8E9BFF` | 1 ring |
| **C** | 8,000,000 | 967 | 338 | `#8A93B8` | 1 ring |
| **D** | < 8,000,000 | — | — | `#6C7899` | 1 thin ring; lands with G2-red; no sparks |

- **Storage.** The grade is stored as a String in `RecordOutcome.grade` / `ChartRecord.bestGrade`: `"PURE"`, `"S+"`, `"S"`, `"A+"`, `"A"`, `"B"`, `"C"`, `"D"`. No Greek glyphs and no "R" (which would collide with RESONANCE and rating).
- **Display sizes:**

  | Context | Spec |
  |---|---|
  | Result stamp | DISPLAY 200 (PURE 104) in a 340 ring |
  | Card mini stamp | DISPLAY 40 in a 72 ring |
  | Map node | grade dot on the arc end (§3.3) |
  | Lists and LOD labels | pill: UI_BOLD 20 on grade colour at α .25, text in grade colour; PURE pill reads `PURE` |

- **Badges** (independent of grade):
  - **FULL COMBO** = no LOST and no mine touched (`maxCombo == x` and `minesTouched == 0`); broken holds count as LOST. A MINE or LANE MINE touched anywhere in the run, also before the first note or after the last one, removes it (2026-10-12).
  - **ALL RESONANCE** = grade PURE and FULL COMBO (so a touched mine removes it too; §18.3).
  - Shown on the end banner, the result badge, the card and the node arc dot.
- **No fail state.** There is no life gauge. Every finished run is recorded; D is the floor. `FIRST DIVE` and `NEW BEST` are tags, not grades.

---

## 13. Audio slots [P0 unless tagged]

| Slot | Source | Behaviour |
|---|---|---|
| **MENU** | `assets/background.(ogg\|mp3\|wav)` (optional) | Loops; 0.6 s fade-in on Welcome, Map, Profile, Settings. Ducks to 25 % on node select (300 ms); goes to 0 while a preview plays; fades out over 400 ms on DESCEND; returns over 800 ms. |
| **PREVIEW** | song music from `previewStartMs` (default 30 % of duration) | 450 ms dwell; 600 ms fade-in; 20 s window loop with 800 ms edge fades; at most one alive (§4.8) |
| **GAMEPLAY** | `SongClock` (GAMEPLAY-owned) | audio-anchored clock, never seeked during play; ends at last note + 1.5 s; disposed before the result shows |
| **RESULT** | chain in §6.7; temporary `Introduction/Introduction.wav` | 1.2 s fade-in to `musicVolume × 0.8`; loops; 450 ms fade-out on CONTINUE, 300 ms on RETRY; ducks to 60 % during level-up |
| **HIT** (reserved) | `assets/Sounds/hit.(ogg\|wav)`, optional `hit_echo.*`, `hold_end.*`, `mine.*` (§5.15) | not shipped; silent when absent; primed at load with a volume-0 play; played inside the input handler; volume `sfxVolume` |
| **UI SFX** [P2] | synthesised at first launch into `local("cache/sfx/")`:<br>• ping: 1.2 kHz sine, 300 ms exponential decay, −2 semitone drop<br>• tick: 2 ms noise burst<br>• thump: 80 Hz sine, 120 ms<br>• whoosh: filtered noise sweep, 400 ms | volume `uiVolume` (0.6) |
| **CALIBRATION** | synthesised click WAV (§9.4) | played through `AudioService.playFile` on the gameplay `Music` path |

- **Volume model.** `musicVolume` applies to MENU, PREVIEW, RESULT and the song. `sfxVolume` applies to HIT. `uiVolume` applies to UI SFX.
- **Android pause.** Every slot pauses on `pause()`. On resume, menus fade back in over 300 ms; gameplay stays in the pause menu.

---

## 14. Performance budget [P0]

Targets assume a mid-range phone (Adreno 610 / Mali-G57 class) at 60 fps.

| Screen | Draw calls | Peak quads | Textures bound | GPU notes |
|---|---|---|---|---|
| Welcome | ≤ 8 | ≤ 400 | noise, atlas, 2 font pages, welcome.png | caustic variant (3 fetches/px) |
| Map | ≤ 12 | ≤ 1,500 (terrain ≤ 600 tris in view, nodes, path, ≤ 120 particulates + bio, critters ≤ 20) | atlas, thumb page(s), ≤ 3 font pages, Blue.png, noise | caustics only < 300 m |
| Map + card | ≤ 14 | +200 | + selected full jacket | |
| Gameplay | **≤ 10** (≤ 12 if HUD fonts are not on one shared page) | notes ≤ 250, FX ≤ 100, HUD ≤ 120, lines + ripple ≤ 220, backdrop ≤ 40 | noise, art/wash, atlas, 1–2 font pages | 2 fetches/px |
| Result | ≤ 12 | ≤ 1,000 at the impact peak | atlas, jacket, 2–3 font pages, ResultPage.png, noise | |

**Gameplay as built (2026-10-12, GL spy at 1920×1080):** running frames 6–9 draw calls (average 7.2 in the `auto` scene, 5.4 before the corner HUD): drawing the corners last (§5.1) costs one atlas flush and one text flush after the additive block. The open pause menu costs 6–8 (the old panel cost 11), the countdown 8. LANE MINE columns, growing bars, the sync square, the water rings and the tide wash add no draw call. The tutorial's grade lesson peaks at 11 (EN) / 12 (中文) (§10.2). Moving the corner shapes into block B would save one call, but the additive lane glows would then wash over the pause icon and the chip plate.

**Rules:**
- **Zero allocation per frame** in render, update and input paths:
  - digits via `drawDigits`
  - static strings via cached GlyphLayouts, rebuilt only when the text changes
  - pooled tweens, ripples, pings and particles
  - struct-of-arrays snow
  - static `Color`s
  - one reused `Vector3` for projections
  - no lambdas, iterators or `String.format` in hot paths
- **No FBO** in any per-frame path. The only FBO is the avatar cache, rebuilt after context loss.
- **Memory:**
  - font atlases ≤ 6 pages of 1024² (display sets are tiny; versus about 100 MB of worst-case pages today)
  - thumbnails in 1024² pages (16 per page)
  - full jackets only for the selected song and the song in play
- **Automatic quality drops:**
  - particles High → Low (§5.9)
  - backdrop → LOW (§2.4)
  - both persist and announce themselves once
- **Update cost.** CPU update under 2 ms per frame in gameplay; GC stays idle during songs.


## 16. Route hub and song categories (owner request 2026-10-11) [P0 unless tagged]

The owner wants the start of the game to offer five entry points: **main story chapters (主线章节), side chapters (支线章节), collaboration chapters (联动章节), singles (单曲) and all songs (所有曲目)**. Test songs live in **Singles** for now. Side and collab are reserved (empty) until content exists.

### 16.1 Flow
- Welcome `TAP TO DESCEND` → **Route hub** (replaces Welcome → Map). The hub opens focused on the last route the diver used (`profile.lastRoute`, default `singles` while the main story is empty, else `main`).
- First run: Welcome → FirstRun → tutorial → result → CONTINUE → Route hub.
- Hub → MAIN / SIDE / COLLAB / SINGLES → the Dive Chart (§3) showing only that route's chapters. The Welcome→Map descent of §3.12 now plays Hub → Map.
- Hub → ALL SONGS → the song browser (§16.4).
- BACK: map or browser → hub (CURTAIN, label `ROUTES`); hub → Welcome.
- Result CONTINUE and pause-menu Quit return to **where the song was started** (the map of that route, or the browser) with the same selection, per §3.12's return.

### 16.2 Route hub screen
A dark sonar-console scene over the Backdrop at the diver's depth.
- **Five route cards** in a horizontal row that the player can drag freely (1:1 pan, inertia, rubber band at the ends, soft snap so one card is centred; never a rigid list). Card 560×720 (TALL layouts: 520×660; at 4:3 the row may show 2.2 cards, at 20:9 about 3.5). Spacing 48. The centred card is scale 1.0, neighbours 0.88 with α .75, sliding along a slight arc (y +24 per step away from centre).
- **Order and content**:

  | # | Route | EN title | ZH | Accent | Art (procedural, from the Draw atlas) | Status line |
  |---|---|---|---|---|---|---|
  | 1 | main | MAIN STORY | 主线章节 | `groundSig` | the depth-layer strip: one horizontal band per main chapter (7 today) in the layer colours, a K&K mote pair diving | `7 CHAPTERS · n SONGS` or `7 CHAPTERS · COMING SOON · 敬请期待` |
  | 2 | side | SIDE STORIES | 支线章节 | `#8F7BFF` | branching path lines from the surface | `n CHAPTERS` or `COMING SOON` |
  | 3 | collab | COLLABORATIONS | 联动章节 | `#FF5CA8` | two interlocking sonar rings | `n CHAPTERS` or `COMING SOON` |
  | 4 | singles | SINGLES | 单曲 | `#7FE7FF` | scattered buoys on a lagoon | `n SONGS` |
  | 5 | all | ALL SONGS | 所有曲目 | `ink1` | a sonar grid with dots for every song | `n SONGS · m CHARTS` |

  Card anatomy: `panel` rounded rect with a 2 px accent rim; art area top 60 %; title RUBIK 56 (G1 idle shimmer every 3–6 s), zh subtitle UI 26 when zh (always on the hub: these names are new), status UI_LABEL 20 `ink2`, and a progress line for routes with songs (`CLEARED a/b` from the profile).
- **Reserved routes** (no chapters/songs): the card is drawn desaturated with a diagonal `COMING SOON · 敬请期待` ribbon; tapping it jiggles the card (±8 px, 3 cycles, 300 ms), pings the SonarField at the tap and shows a toast `THIS ROUTE IS STILL BEING CHARTED · 航线测绘中`. It never navigates.
- **Interaction**: tapping the centred card enters it (press spring, card flies toward the camera 180 ms, then the route's transition); tapping a side card centres it first; horizontal drag/fling scrolls; keyboard ←/→ moves focus, Enter enters, Esc backs out. Idle: the centred card's art animates, bubbles rise behind it, a sonar sweep crosses the row every 4 s.
- **Corners** (same as Welcome §7): BL profile chip with the switcher sheet; BR settings gear; TR a `TUTORIAL` pill (replays the tutorial). Title `ROUTES · 航线` (UI_LABEL 22, `ink2`) at the top centre.
- **Music**: menu loop continues from Welcome.
- **Layouts**: WIDE row centred at y 540; TALL (4:3, 3:2) row centred at y 600 with the corners in the bands; all anchored to the safe edges; min touch targets per §1.1.

### 16.3 The Dive Chart per route
- The map (§3) shows the chapters of one route. MAIN = **seven** chapter layers c1–c7 descending one below another (owner: "7 main chapters, most likely layer by layer downward; details not decided"), all `uncharted` for now, plus the tutorial LAUNCH POINT. c1–c5 keep SHORELINE/TWILIGHT/MIDNIGHT/BLACKWATER/TRENCH; c6 and c7 are placeholders `CHAPTER VI · 第六章` (10,935–13,000 m) and `CHAPTER VII · 第七章` (13,000–16,000 m) drawn with the L6 BELOW palette (c7 slightly darker: bg × 0.85, accent hue +20°) until the owner designs them. The number, names and depths of chapters come only from the manifest; no code may assume 5 or 7. The BELOW easter egg (§3.11) moves below the last main chapter; SIDE and COLLAB = their own chapters (none yet); SINGLES = one band titled `SINGLES · 单曲` at the surface layer (0–200 m palette) holding every single; its band grows with the number of songs (same node rules as §3.2, no 6-song cap).
- The map header shows the route name (`MAIN STORY`, `SINGLES`, …) beside the layer chip; BACK returns to the hub.
- Camera, lens and selection are persisted **per route**.

### 16.4 ALL SONGS browser
**Superseded 2026-10-19 by §24.24** (a pack box over a grid of five or four to a row, the card always open; since §24.27 two to a row as a net, and since §24.28 without the pack box). The notes below are the earlier versions.
- Full-screen, draggable vertical browser over the Backdrop (inertia + rubber band, not a static list): songs grouped under sticky headers by route then chapter (`SINGLES`, `MAIN STORY · SHORELINE`, …), each row 132 tall with jacket thumb 104, title, artist, the four difficulty chips with constants, best grade and score.
- Top bar: search field (ui.TextField), sort segmented control (`DEFAULT / TITLE / LEVEL / BEST / RECENT`), and the §3.10 status filter chips.
- Tapping a row opens the song card (§4, side card WIDE / bottom sheet TALL) with chips, preview audio, AUTO and DESCEND (DIVE transition from the row). Result CONTINUE comes back here with the row selected.
- Reuse the map's SongCard, filter model and list rendering; do not fork their visuals.
- **As built: the SORT button** (Kelvin 2026-10-13 21:58: 「all songs界面的各种选项有点乱了，整理在一个"sort" button，放在右下角的那个空白处，位于定位和横着的那个框之间」). ALL SONGS is the grid level of the cave map. Its options are no longer a chip row under the badge:
  - The SORT button sits in the bottom-right corner, 24 in from the map area's right edge (since 2026-10-16, when the rise plate and recentre were removed; before that it sat between them): 80 tall, `panel` 0.9, `surfaceTop` rim), the sort icon (three left-aligned bars, shorter going down) and `SORT` (UI 20). It is lit in `groundSig` while its panel is open, and shows a `groundSig` dot at its top right while the layout or order is not the default. Where the bottom row has no room for it beside the lens control, it sits above the lens control's top, still right-aligned.
  - Tapping it raises the **sort panel** (`map/SortPanel`, 740 wide, 280 ms `pow3Out`) above the button, right-aligned with it: `排序 SORT` (UI_BOLD 24), `分组 LAYOUT` with `曲包 PACKS` / `全部 ALL`, then `顺序 ORDER` with `默认 DEFAULT`, `歌名 TITLE`, `定数 LEVEL`, `最高分 BEST`, `最近 RECENT` three per row, then (since 2026-10-16 22:39) `筛选 FILTER` with `NEW`, `FULL COMBO`, `ALL RESONANCE` (§3.10). A tap re-sorts or re-filters at once and keeps the panel open; a touch outside it, SORT again or BACK closes it.
  - The panel is at the HUD scale on every screen and shrinks (to 0.8 at most) when the room above the button is short.
  - On a phone-sized screen (short side under 480 dp, `Ui.compactScreen()`), ALL SONGS opens at zoom 1.0 instead of 1.25 (`MapStyle.GRID_ZOOM_PHONE`), so its holes, names and chips are 25% larger (Kelvin 2026-10-13 22:59: 「我是说排序面板和之前一样大小，但是进去以后的歌曲图标小了点」). Tablets and desktops keep 1.25. Every screen opens with the grid's top-left corner just under the badge, inside the safe left edge (`MapScreen.startCamera`, `MapWorld.contentLeft` / `contentTop`), now that no chip row sits there: on a phone both first rows clear the bottom row, and a 16:9 phone shows the right column whole.

### 16.5 As built 2026-10-16: the cave mouth, main-line modes, ALL SONGS locks
Kelvin 21:58 / 22:14 (circled preview) / 22:39, approved 22:43 (preview v2). Map keys are in MAP_FORMAT §1, §2 and §5.
- **Hub (`hub.json`, renamed `CAVE MOUTH · 洞口`)**: SINGLES left, MAIN enlarged in the centre (r 230), COLLAB right (`COMING SOON`). MAIN has no sub-line since 2026-10-18 20:17 (`DEMO · 7 CHAPTERS` / `演示 · 7 章` before; 「主线底下的"演示 · 7章"不要了」). COLLAB is an empty shaft like MAIN behind the iron grate of a sealed hole since 20:18 (`"art": "shaft"`, sealed; a ring of cover art before; 「Collab里面暂时不要填充曲绘，就一个和主线一样空的洞，然后装上那种网」). No SIDE hole (side stories live in MAIN), no ALL SONGS hole, no `ROUTES · 航线` plate. The hub takes no zoom: pinch only pans, the wheel, `+` / `-` and double-tap do nothing (`MapCamera.zoomLocked`), and it always opens centred at zoom 1.
- **ALL SONGS button**: the hub's bottom-right corner (256×88, `panel`, `ALL SONGS` UI 22 over `所有曲目` UI 16 and a chevron, no icon). It is the only way into ALL SONGS, which still rises back to the hub.
- **Story mode (default)**: MAIN goes straight into chapter 1 (`storyTo`); chapter 1 rises to the hub and each later chapter to the one before it (`storyParent`).
- **Chapter mode**: MAIN goes to `main.json` (`LAUNCH POINT · 起潜点`, a title no longer drawn since §24.28): every chapter shaft and side-story entrance on one layer, at the size of the hub's SINGLES (r 132–140) and spaced out; chapters 3–7 are sealed placeholders. A side entrance opens chapter 1 on its own spot (`at`). Every chapter rises to this layer; a rise lands on the entrance taken down.
- **Mode toggle**: left of Settings on the hub (252×64 pill: a path icon and `STORY MODE` / `故事模式 · 点一下切换`, or four tiles and `CHAPTER MODE` / `章节模式 · 点一下切换`; in English the second line is `TAP TO SWITCH`, §24.28). It shows once every unsealed shaft on the chapter-mode layer is open (sealed placeholders are not chapters yet, so the demo shows it). The choice is saved per diver (`Profile.mainChapterMode`).
- **Light pools**: the pools that marked the old rise holes are gone; levels that started on a rise spot now start on their title (`titleAt`), so the camera opens on the level name. Arriving there, the ring and the wake light start from that spot when the camera opens nearer to it than to the hole nearest it (SINGLES and LAUNCH POINT open centred on it; Kelvin 2026-10-17 20:20 「点进Singles的入口的圆圈闪的一下的那个动画怎么还是在左上角」: on SINGLES the ring had opened on Song 1); the chapters, whose title sits by the left edge, keep the hole nearest it.
- **ALL SONGS locks**: a song is locked there when every spot that places it is locked; it shows the gold padlock. Tapping it still opens the card (difficulties and constants readable); AUTO is greyed (α .4, a tap shakes it) and DESCEND becomes a dark plate with a gold padlock and the condition, `先通关 X` / `CLEAR X FIRST` (UI_BOLD 24). Several conditions take turns: each holds 3 s, then fades out 0.5 s while the next fades in 0.5 s. DESCEND and Enter only shake it.
- **Secret songs** (special unlock, flow TBD): a song spot or a pack marked `secret`. `hidden`: not shown at all; `glitch`: a disc of re-rolling noise cells with a dashed grey rim, a garbled shimmering name and a `??` chip; a tap only flickers it (no card), and the filters, TAB, card prev / next and long-press skip it. The profile flags `reveal:<song id>` and `reveal:pack:<title>` lift it.
- **ALL SONGS packs**: SINGLES, MAIN STORY, SIDE and COLLAB as separate groups; the `APPEARS HERE ONCE CHAPTERS OPEN` and `COMING SOON` captions are gone.
- **SINGLES layout (Kelvin 2026-10-17 16:46: 「singles地图要重拍。我想对于singles一点进去坐标为（0，0）开始，也就是从屏幕正中心，四周都有曲子」)**: `singles.json` starts on its rise spot at (0, 0), so the camera opens with (0, 0) at the screen centre, the level name on it (`titleAt` [0, 30]). The songs sit round it on a hex lattice 540 wide with rows 380 apart, sized for a 4:3 tablet (Kelvin 17:21: 「地图编排贴近4：3的平板一点，手机可以再四处拖动没关系，然后可以间隔在大一点再排开一点」): on 1920 × 1440 every hole and label shows at the start, clear of the badge and the lens control; on 16:9 and wider screens the top and bottom rows run to the screen edges and the rest is a drag away. (The first cut, 560 × 200, fitted a 16:9 screen.) A ring of six (Song 1 top left, then clockwise Song 2, Song 3, Song 4, Song 5, Song 6), marsh mally top right and ICS4U bottom right outside it, with paths round the ring and to the outer two. Songs placed nowhere (`collect`, today Aleph-0) take the free lattice points nearest the start (MAP_FORMAT §1), so Aleph-0 lands top left. The bottom-left outer point stays free for the next one. Collect distances count a step up or down as 4/3 of a step sideways, so the four outer corners fill before anything spills off a 4:3 screen.

---

## 17. Afterimage trails "拖拽残影" (owner request 2026-10-11) [P0 unless tagged]
Model, chart keys and lifetime rules are in ARCHITECTURE §17. The owner's words: notes drag "a string of copies like themselves, fainter and fainter; the copies are only a show; the charter sets their density and time; every note type has it; teach it in the tutorial".

### 17.1 Look
- A copy is the note drawn with the **same sprites and colours** as the live note at the copy's moment (DROP body + leading edge + rim, STREAM head/body/tail, MINE body + studs, TIDE drop in its T4 shape at the copy's song time, TIDE STREAM bar + band), only fainter: opacity `alpha · (1 − k/(n+1))^fade` × the note's own opacity then.
- **As built 2026-10-12:** mine copies turn with the angle of their own moment (a rotating mine leaves a fan). A LANE MINE is copied like a STREAM, only for moments before its judgement, simplified to column fill, rims, tail cap and head cap (40 quads; stripes, studs, veil and line glow are live-only). A T4 TIDE copy costs 129 quads on the ground line and 25 on the sky line at 8 lanes (the bar was 11); the trail budget and the planner's stride still bound the total.
- Copies never glitch, never glow (no additive pass) and never show the sync square (§5.8), the tide wash or any FX. (**Replaced:** "sync outlines/connectors, the tide sweep", which no longer exist.)
- Copies sit **beneath** every live note of the same line, farthest first, so the live note always reads on top.
- **Readability guard:** a copy's opacity is capped at **.85 ×** the live note's, so the real note is always the brightest one in its trail (the tutorial teaches "hit the bright one at the front").
- `path` mode: copies lie along the note's real path (a vertical column for a straight fall, a slant for a sliding lane, a fan for a rotating note). `level` mode: copies stay level with the note at its earlier sideways positions and fall straight down with it (the owner's Phigros reference).
- A missed note's copies desaturate with it (§5.8 LOST look at the copy's moment); a hit note's `path` copies keep falling and vanish one by one as they reach the line, like the trail draining into the line.
- Particle quality Low / Off: at most 16 copies per note (every 2nd copy, opacity × 1.25 within the cap). Over the frame budget, all trails thin evenly (ARCHITECTURE §17).

### 17.2 Charting guidance (for CHART_FORMAT.md)
- Readable defaults: `length 0.5`, `density 16` (8 copies), `alpha 0.6`, `fade 1`.
- Long dense trails (`length ≥ 1`, `density ≥ 24`) are for slow, sparse moments; on dense streams prefer `length ≤ 0.25`.
- `level` only shows anything when the lane or window moves sideways (or the note rotates).

### 17.3 Tutorial lesson [P0]
- **Now (owner's chart, 2026-10-17, §10.2):** afterimages are not mentioned since 2026-10-20 (§24.42); until then they were only mentioned, in L10 on a left plate (216.5–221.5): `MORE TO DISCOVER` / `更多变化`, `AFTERIMAGES, MOVING NOTES — YOU'LL MEET THEM IN SONGS` / `残影、移动的音符……以后在曲目里慢慢认识`. The chart has no trails. The v3 lessons below are history.
- **As built 2026-10-12 (tutorial v3, §10.2):** lesson **L10**, a side plate beside its notes: heading `AFTERIMAGES` / `残影`, caption `THEY ARE ONLY LIGHT — HIT THE BRIGHT NOTE AT THE FRONT` / `残影只是光影——只打最前面、最亮的那一个` (beats 216–223); three ground DROPs at 220, 222 and 224 with `path` trails (`length 0.75`, `density 12`). Lesson **L11** (moving notes, 223–228.5) adds the two `level` trails during the window slide (DROPs at 228 and 230, `length 1`) and one more `path` trail at 232. The `AFTERIMAGE_LESSON` switch is gone: v3 always teaches it. **Replaced:** "beats 216–228 as lesson L9, behind the switch `AFTERIMAGE_LESSON`" and the longer first caption `SOME NOTES DRAG AFTERIMAGES. …` / `有的音符会拖出残影。…`.
- Captions never cover the trails (the v3 checker tests it).
- **Replaced:** "The pause-menu note legend gains a line `AFTERIMAGE 残影`". There is no pause legend any more, and the Settings NOTE GUIDE (§9.2, gone since 2026-10-18) had no AFTERIMAGE entry by the owner's decision: the tutorial mentions it.

## 18. Scoring v2 display (owner's final formula 2026-10-11) [P0 unless tagged]
Formula, streak rules and migration are in ARCHITECTURE §18: `score = 100,000,000 · (r + 0.55e)/t + 10,000 · m_r/t + r_p` (owner, 2026-10-14). This section overrides the score parts of §5.5, §6, §11 and §12.

### 18.1 Gameplay HUD
- Score: **9 digits**, grouped, leading zeros dim (`000,000,000`); eased and popped as in §5.5. It only ever rises.
- Combo (centre) = consecutive RESONANCE + ECHO, as before. The resonance chain is not shown in the HUD.
- Accuracy uses ECHO weight 0.55.
- [P1] PERFECT glint: a RESONANCE with `|Δt| ≤ 33 ms` draws a 2 px white line under the central word, sweeping left → right in 90 ms. No new word.
- [P1] Hit error meter (§5.6): the inner ±33 ms band is shaded one step brighter than the ±66 band.

### 18.2 Result (DIVE LOG)
- Score decode: 9 digits (`100,010,812` when the chain and PERFECT points push past 100 M).
- Stats rows: RESONANCE `r` with a small `PERFECT 完美 m_pr` beside it (UI 18, ink2); ECHO; LOST; MINES (when any); MAX COMBO `max/x`; new row **RESONANCE CHAIN 共鸣连击** `m_r/x` in the RESONANCE colour with the same bar treatment as MAX COMBO.
- [P1] Tapping the score opens a small breakdown: `ACCURACY SCORE · 88,123,456`, `RESONANCE CHAIN · 9,812`, `PERFECT RESONANCE +812`, `TOTAL`, without a title (2026-10-18 17:26, §24.16; `ACCURACY`, `CHAIN` and `PERFECT` under a `SCORE` title until then). In 中文 it reads 准度分 / 共鸣链 / 完美共鸣 / 总计 since 20:34 (「点一下分数弹出的细则框还是分中英文」); from 17:26 to 20:34 the English names showed in both languages.
- Tapping the PERFECT count slides `EARLY n · LATE n` out to its right in the same font (UI 18; words in the EARLY / LATE colours, numbers ink2), starting where the bars start (owner, 2026-10-14). They are the RESONANCE hits that are not PERFECT (33–67 ms off) split by sign, so PERFECT + EARLY + LATE = RESONANCE. The EARLY / LATE line under the rows stays the ECHO-range hits. Any tap hides them again.

### 18.3 Grades (replaces the §12 table; colours, stamps and sizes unchanged)

> **Superseded 2026-10-17 by §23** (shells, depth and fifteen grades); kept for history.

| Grade | Score ≥ | Typical run (simulated, 300–1,000 notes) |
|---|---|---|
| **PURE** | every note RESONANCE (base 100,000,000) | 100 % RESONANCE |
| **S+** | 96,000,000 | ≈ 99.5–99.9 % RESONANCE with long chains |
| **S** | 93,000,000 | ≈ 98–99 % RESONANCE |
| **A+** | 90,000,000 (rating = constant) | ≈ 96–97 % RESONANCE |
| **A** | 87,000,000 | ≈ 93–95 % RESONANCE |
| **B** | 82,000,000 | ≈ 85–90 % RESONANCE |
| **C** | 70,000,000 | |
| **D** | < 70,000,000 | |
- Grades use `min(score, 99,999,999)` unless PURE, so the PERFECT bonus never lifts a grade. A touched mine never lowers the grade; it removes the FULL COMBO / ALL RESONANCE badges (combo breaks), wherever in the run it is touched.
- Badges: FULL COMBO = `maxCombo == x` **and no mine touched** (`ScoreKeeper.isFullCombo()`: `totalNotes > 0 && maxCombo >= totalNotes && minesTouched == 0`, 2026-10-12; a mine touched before the first note or after the last one resets only an empty or finished combo, so the mine condition is explicit); ALL RESONANCE = PURE and FULL COMBO.
- The ≈ ECHO/LOST-allowed columns of §12 no longer apply (one ECHO also cuts the chain, so its cost depends on where it lands).

### 18.4 Chart rating and micro-goal (replace §11.1 and §11.5 code; same shape, 100 M scale)

> **Superseded 2026-10-17 by §23** (shells, depth and fifteen grades); kept for history.

```java
static float chartRating(float c, int score) {
    int s = Math.min(score, 100_000_000);                                          // bonus ignored
    if (s >= 100_000_000) return c + 1.0f;                                         // PURE
    if (s >=  96_000_000) return c + 0.5f + 0.5f * (s - 96_000_000) / 4_000_000f;
    if (s >=  90_000_000) return c +        0.5f * (s - 90_000_000) / 6_000_000f;
    return Math.max(0f, c - (90_000_000 - s) / 5_000_000f);
}
// micro-goal inversion: need ≤ c → 90M − (c − need)·5M; ≤ c+0.5 → 90M + (need − c)·12M;
// < c+1 → 96M + (need − c − 0.5)·8M; else PURE (100M). Round up to 10,000; cap 100,000,000.
```
- Card copy unchanged in shape: `NEXT ▼ +84 m AT 96,170,000`. Regenerate the §11.1 table and the §11.5 example on the new scale in the repo copy.

### 18.5 EXP

> **Superseded 2026-10-17 by §23** (shells, depth and fifteen grades); kept for history.

- `base = (30 + 6c) × (0.4 + 0.6 × (min(score, 100,000,000) / 100,000,000)²) × L`; bonuses unchanged. Regenerate the §11.6 examples.

### 18.6 Old records
- Old scores (this build's earlier profiles and the old game's `user.json`, both on the 10,000,000 scale) are multiplied by 10 once (ARCHITECTURE §18). No toast; the card simply shows the converted number.

---

## 19. TIDE streams and the tide wash (owner request 2026-10-11 21:14) [P0 unless tagged]
Rules are in ARCHITECTURE §19. This section overrides the TIDE "Hit" bullet of §5.15 and the TIDE sweep of §5.9 item 10.

### 19.1 TIDE STREAM look
- **Head:** the TIDE bar of §5.15 (window-wide, diamonds at lane boundaries).
- **Band:** from the head to the tail, spanning the same window width as the bar (inset 12 each end); fill `tide` α .14 at the head → .08 at the tail; 2 px `tide` edges along both window ends; a 3 px tail cap, white α .5. Two faint ripple lines (1 px, `tide @ .18`) scroll toward the line inside the band.
  **As built 2026-10-12 (§21.6, §21.7):** the rails are 2 px at α .6 and the tail cap 4 px at α .75, both in the band's own colour (`skyTide` on the sky line, `tide` on the ground line); a cap inside an overlap still draws. In the rows where a ground band overlaps a sky band, the ground band draws nothing under it (the sky band is not inset, `SKY_TIDE_STREAM_INSET` = 0). Its length follows `Note.tailHeightAt` (§20.1). **Replaced:** the white α .5 tail cap.
- **Layering:** the band draws **beneath** lane notes and mines, so a lane note crossing it stays crisp. Above the lane field.
- **While held:** the head pins to the line (like a hold), band α × 1.6, and the tide level of §19.2 stays up.
- **Broken:** desaturates to `#6C7899 @ .30` over 200 ms, like a hold.
- **Names:** `TIDE STREAM 潮汐水流` in the tutorial and result stats. (**Replaced:** "the pause legend".)

### 19.2 Tide band and wash (the TIDE hit effect) — v3, owner review 2026-10-11 22:40
History: 21:14 asked for a tide that brightens the screen and then darkens; 22:12 corrected it to a band of light that floats up **as a whole** (no circular ripple at the finger; that ripple moved to DROP/STREAM, §20.5); 22:40, after the mock-up video: the band was **too crisp**, and **no band may appear while the tide pulls back** — the pull-back is only the fading feel of receding water. Mock-up videos: `preview/` A1/A2 (v2).
- **No point effects for TIDE:** no burst, ring or ripple at the touch x for a TIDE hit (drop or stream head). The judgement word, EARLY/LATE and the hit sound stay.
- **Rise (0–380 ms): a soft band.** A window-wide band (inset 12 at each end) with a soft vertical profile (Gaussian, σ ≈ 30 units, no hard core and no white line), additive, peak α .30, in the judgement colour of the hit (§21.1: RESONANCE → `tide`, ECHO → `echo`). It lifts off the hit line and floats **as a whole** toward the other line (upward from the lower line, downward from the upper line) to **45 %** of the gap (ECHO 30 %) with `pow2Out`. Under it, the water it has lifted: a fill from the line up to the band, α .15, brighter toward the band (0.35 → 1 of that), soft 55-unit top edge.
- **Dissolve (300–480 ms):** the band widens (σ × 2.2) and fades to nothing at the top. There is no second/trailing band.
- **Recede (380–900 ms): no band.** The lifted water draws back toward the line (its soft top edge falls from the peak to the line, `pow2In`) while its α fades from .15 to 0 — the feel of a wave sliding back down the sand.
- **Darken:** while the water recedes, a veil (`#02040F`, normal blend), strongest on the hit half and about half as strong on the far half, peaks at α .16 at 480 ms and is gone by 850 ms.
- **Line:** the hit line swells as a whole (glow in the hit colour, 4 px lift) and settles over 320 ms, instead of the point ripple of §5.7.
- **Layering, several at once, LOST, `reduceFlashing`:** beneath notes, the judgement word and the HUD, above the backdrop and lane field; one effect per line per frame/group (a new one on the same line restarts it); both lines at once float toward each other; LOST plays nothing; `reduceFlashing`: α × 0.5, no veil, travel at most 30 %. Particle quality Off keeps the rise and recede (no dissolve widening, no line swell).
- **TIDE STREAM (`潮汐水流`):** the head hit plays the rise/recede above. While held, **soft** thin bands (Gaussian σ ≈ 9, additive α .13, no hard edge) keep lifting off the line and floating away at a **fixed** rate equal to the STREAM hold particles (22/s High, 12/s Low, 0 Off), each travelling 25 % of the gap in 600 ms and dimming as it goes; the clock starts at the head judgement and ignores finger swaps (§20.3). The low tide level (12 % of the gap, α .12, breathing) stays under them. Completion: one rise/recede at 30 % travel (no veil). Break: the level drains to the line over 150 ms with a brief veil (α .12); emission stops.
- **As built 2026-10-12 (`gameplay/TideWash`).** Built to the numbers above, with these decisions:
  - **No contact ring either:** a judged TIDE hit draws no §5.10 contact ring or brackets at the finger (§5.10); only the judgement word, EARLY/LATE and the hit sound remain at the touch.
  - **Blend.** Drawn in normal block A (after the lane field, before the HUD shapes), not additively: an additive pass beneath the notes would cost a blend switch (two more draw calls a frame). Colour c at α is drawn as `FIELD + c / ADD_GAIN` at α × `ADD_GAIN` (`FIELD` `#0B1A35`, the measured field colour under the gap; `ADD_GAIN` 1.3), which adds exactly c × α over `FIELD` and stays within a few percent of it over the field's other dark blues. A band lying on the lifted water or the level has its α divided by (1 − the α beneath its centre), so the stack adds up as the additive design does.
  - **ECHO** rises lower **and** fainter: α × .72 (`ECHO_K`, measured on the approved mock-up `v4_tide_drop.mp4`) on the ECHO rise/recede, its line swell and an ECHO stream's completion; not on the TIDE STREAM bands (α .13 for both judgements).
  - The hit band and water fade in over 40 ms (`BAND_IN`) so the band does not pop on. A completion plays no line swell.
  - **Clocks:** hit effects run on run seconds; band k of a TIDE STREAM is born at `head + k / rate` on the engine's judge time (head = the stream's head judgement), so finger and key swaps cannot move it; a quality change mid-stream continues on the same clock at the new rate. An emitter that starts more than 250 ms after its head (`LATE_START_MS`) starts its bands now instead of replaying the past. One emitter per running TIDE STREAM (12 slots including ending ones); two streams of one group on one line draw one set of bands. Both freeze while paused.
  - **Colours** follow §21.1 / §21.6: RESONANCE in `tide` on the ground line and `skyTide` on the sky line, ECHO in `echo` on both.
  - **Open:** a broken TIDE STREAM still shows the LOST × at the window centre and the window flash (§19.2's "no point effects" is about hits); the violet bands after an ECHO head are subtle under the held teal body.

### 19.3 Holding rules in the UI
- Tutorial (STREAM lesson): add `KEEP THE SAME FINGER DOWN UNTIL THE STREAM ENDS` / `同一根手指按到底，中途不能换手`.
- Tutorial (TIDE lesson): one TIDE STREAM with `TIDE STREAMS: KEEP ANY FINGER ON THE LINE UNTIL IT ENDS — YOU MAY SWAP FINGERS` / `潮汐水流：一直有手指按在这条判定线上直到它结束，可以换手指`.
- **Replaced 2026-10-12:** "Pause legend: the TIDE STREAM row reads `any finger on the line`; the STREAM row reads `same finger to the end`." The pause menu has no legend (§5.12, variant B). The rules lived in the Settings NOTE GUIDE (§9.2, gone since 2026-10-18): STREAM `HOLD WITH THE SAME FINGER TO THE END` / 按住，同一根手指按到结尾; TIDE STREAM `KEEP ANY FINGER ON THE LINE` / 任意手指按在线上就算.
- **As built (tutorial v3, §10.2):** the STREAM lesson L3 says `KEEP THE SAME FINGER DOWN UNTIL THE STREAM ENDS` / `同一根手指按到底，中途不能换手`; the TIDE STREAM lesson L7 says `KEEP ANY FINGER ON THE LINE — SWAPPING IS FINE` / `这条线上一直有手指按着就行，可以换手指`.

---

## 20. Growing bars, LANE MINE and TIDE STREAM particles (owner request 2026-10-11 22:04) [P0 unless tagged]
Rules are in ARCHITECTURE §20.

### 20.1 Growing bars
- A STREAM, TIDE STREAM or LANE MINE whose `HeightEvents` make it longer while it is held simply draws the longer body; the head stays pinned on the line, the tail cap keeps moving away. No other change in look.
- At the note's end the remaining body fades with the normal completion (120 ms) or break look, from whatever length it has.
- **As built 2026-10-12:** `HeightEvents` (event type `Height`) are read on STREAMs, TIDE STREAMs and LANE MINEs in v2 charts only; on other notes and in v1 charts they are ignored and counted (`LoadReport.heightEventsIgnored()`). Values clamp at 0. Without them a LANE MINE's column shrinks linearly from `height` to 0 over its duration after the judgement, like a hold. Examples: `docs/CHART_FORMAT.md` §4.1.

### 20.2 LANE MINE (轨道水雷)
- **Body:** a hazard column filling the lane's current width (inset 6 each side) from head to tail: fill `mineCore @ .85`; diagonal hazard stripes in `mine` α .35 (45°, period 24, stripe width 10) drifting toward the line at 40 /s of song time; 3 px `mine` rims on both sides with 6×6 studs every 64 units; a head cap drawn as the normal MINE body (§5.15) and a 3 px `mine` tail cap. The column is drawn in the band layer of block B (beneath trails, lane notes and mines); the head cap with the notes.
- **Pinned (owner 2026-10-12 03:03: 「轨道水雷没法上下移动。碰到判定线就不能再动了」):** from its judgement beat on, a LANE MINE's head stays exactly on its line and its angle is frozen at its angle at the judgement, whatever movement or rotation events follow. Before the judgement it can move and turn like any note.
- **The other line stays visible (owner 2026-10-12 02:58):** where the column crosses the **other** judgement line it leaves a gap of ±10 around that line (`NoteShapes.OTHER_LINE_GAP`), so that line, its ripples and ticks stay as bright as elsewhere (checked by a pixel probe).
- **Pulse:** `note_glow` tinted `mine`, α .18 ↔ .34 at 2 Hz, additive (like a MINE).
- **While it blocks** (from `T`, when it reaches the line, owner 2026-10-13; ARCHITECTURE §20): the lane's line segment glows `mine @ .30` (additive, 40 tall), and a `mine @ .10` veil fills that lane's part of its own line's zone, so the closed lane reads at a glance. Both ramp in over 60 ms.
- **Contact:** the touched-mine feedback of §5.15 at the contact x, every time it triggers: shock ring, line flash, the red `LOST` word (§5.6), the `mine.*` sound slot, and the head cap jitters. The combo break plays at any combo.
- **Ends:** after its tail crosses the line, it fades over 120 ms.
- **Names:** `LANE MINE 轨道水雷`. The Settings NOTE GUIDE (§9.2, gone since 2026-10-18) said `STAY OUT OF ITS LANE WHILE IT'S RED` / 变红的时候别碰这条轨道. (**Replaced:** the pause-legend line `keep out of the lane while it is red`.)
- **Trails:** copied like a STREAM, only for moments before the judgement, simplified to column fill, rims, tail cap and head cap (40 quads); stripes, studs, veil and line glow are live-only (§17.1).

### 20.3 TIDE STREAM particles (revised 2026-10-11 22:12)
- They are the peeling light bands of §19.2 (whole-window, floating upward from the line), emitted at the same rate as a STREAM's hold particles. No particles at the finger's x. The bands run on their own fixed clock from the head judgement: swapping fingers (or keys) never pauses, restarts or shifts them (Kelvin, 2026-10-11 22:15).
- **As built:** the rate has one source, `HitFx.holdRate(quality)` (22 / 12 / 0 per s), used by the lane STREAM hold stream and by `TideWash`, so the two cannot drift (libGDX truncates fractional particle emission per frame, so over 10 s the hold stream emits 218 particles against 220 bands; equal over the first 2 s). Clock details in §19.2 "As built".

### 20.4 Tutorial
- **Now (owner's chart, 2026-10-17, §10.2):** L8b `LANE MINES` / `轨道水雷` sits on a left plate (190–200) beside a LANE MINE in ground lane −2 (192–196) and one in sky lane +2 (196–200), each closed lane outlined while it shows; the TIDE + DROP joints (160, 164 and the six L12 pairs) have no caption of their own. The v3 lessons below are history.
- **Done in tutorial v3 (2026-10-12, §10.2)**, with the v3 wording:
  - LANE MINE: lesson L8b, a side plate `LANE MINES` / `轨道水雷`, `THEY CLOSE A WHOLE LANE. STAY OUT WHILE IT'S RED` / `它会封住整条轨道，变红的时候别碰`, beside a 4-beat LANE MINE in ground lane +3 (193–197). **Replaced:** `LANE MINES CLOSE A WHOLE LANE — STAY OUT WHILE IT IS RED` / `轨道水雷会封住整条轨道，变红的时候别碰`.
  - Joint TIDE (now P0): lesson L6 `TIDE + DROP` / `潮汐 + 水滴`, `ONE TAP ON THE DROP TAKES BOTH` / `打中水滴，同一拍的潮汐一起算`, with joint pairs at 160 (ground) and 163 (sky). **Replaced:** `A TIDE ON THE SAME BEAT AS A NOTE IS TAKEN WITH THAT NOTE` / `和音符同一拍的潮汐，打中音符就一起算`.
- A growing bar is not taught (it is a charting effect).

### 20.5 Water ripples for DROP and STREAM (owner 2026-10-11 22:12)
The circular "water ripple spreading from one point" goes to DROP and STREAM, combined with their existing hit FX (§5.9 as tuned: bursts, rings, sparks stay).
- **DROP / STREAM head hit, RESONANCE (revised by the owner 2026-10-11 22:52: "bigger difference from ECHO; five rings, thicker"):** at the hit point on the line, **five** concentric water rings expand, staggered 65 ms apart: ring k (k = 0..4) starts at 65·k ms and grows r 0 → (160 − 20k) over 480 ms; each about **4–5 px** thick (soft-edged, `ring` rather than `ring_thin`), `resonance` colour, α .62 → 0 (`pow2Out` radius, linear fade), with a very slight wobble (radius ± 2 at 18 Hz) so they read as water rather than UI rings.
- **ECHO (unchanged, owner: "echo is fine"):** one thin ring (2 px `ring_thin`), `echo` violet, r 0 → 100 over 420 ms, α .55 → 0. LOST: none. (As built the ECHO ring is drawn from the `ring_water` levels too, see below; `ring_thin` itself is unchanged for the particles.)
- **STREAM while held:** a small ring (r 0 → 70, α .35 → 0, 360 ms) at the hold point every 320 ms, in addition to the hold particle stream.
- Mines keep their own shock ring; TIDE has no ripples (§19.2).
- Quality: High all five RESONANCE rings; Low rings 0 and 2 only (ECHO keeps its one ring); Off none. Rings share the FX additive block (no new draw calls) and the existing FX pools (pre-filled, no allocation); budget: 5 ring quads per RESONANCE hit at High.
- **As built 2026-10-12 (`fx/WaterRings`):**
  - **One stroke at every radius.** A sprite ring's stroke grows with its size, so every ring (ECHO included) is drawn from 11 `ring_water` atlas levels of one gaussian ring (FWHM 3 texels, radius 6 × 1.35^i texels): each frame a ring uses the level whose stroke is closest to the wanted width (within about ± 16 %).
  - **Mock-up match** (reversible constants, measured on the approved `v4_ripples.mp4`, which draws every ring twice, glow plus line): strokes 5 / 2.75 / 3.25 units and α factors 1.45 / 1.25 / 1.4 (RESONANCE / ECHO / held, result capped at 1), giving RESONANCE 3.6 px at α .81, ECHO 2.4 px at .60 and held rings 2.7 px at 1280×720. Factors of 1 restore the plain design α.
  - 128 ring slots (a full set recycles its oldest ring); rings run on effect time and freeze while paused. Held-STREAM rings continue at Low quality, like the hold stream; they turn violet after an ECHO head.
  - Every caller of `HitFx.tap` gets the rings, so the Settings live preview, calibration and the tutorial's grade demos show them too (§5.9 item 12).

---

## 21. Owner review 2026-10-11 22:40 (after the first preview videos) [P0]
Kelvin paused coding to review previews of everything; these are Kelvin's first notes. **2026-10-12:** every item below is decided and built (§22 is the index).
### 21.1 ECHO and RESONANCE hit effects in different colours
Every hit effect takes the colour of its judgement: RESONANCE → `resonance` cyan family (TIDE effects: `tide`), ECHO → `echo` violet (#B49CFF), on every surface: DROP/STREAM bursts and sparks (already so in `HitFx`), the water ripples (§20.5), held-STREAM motes after an ECHO head (already so), the TIDE rise/recede and line swell (§19.2), TIDE STREAM bands after an ECHO head, LANE MINE contact FX keep `mine` red. The judgement word keeps its colour. A test scene shows R and E side by side on both lines.
**Built 2026-10-12**: every surface listed, on both lines; the visual scenes `lookripples` and `looktidedrop` show R and E side by side. Sky RESONANCE TIDE effects use `skyTide` (§21.6).
### 21.2 Clearer lane separators
§5.7 now draws full-height separators (α .34 at the lines → .16 mid-field) instead of the short fading guides, ticks α .75. Lane opacity events still apply. **Built 2026-10-12** (`FieldRenderer.drawUnder`: quadratic in 6 pieces plus a 10 px glow at .05).
### 21.3 Sky (天键) and ground (地键) notes clearly different — decided 22:49: keep pearl white
Today sky notes use `skySig` pearl white #F2F7FF and ground notes `groundSig` cyan #4FD8FF (§1); the first mock-up wrongly drew both cyan. Options shown to Kelvin: (a) keep pearl white for sky (matches the white upper line); (b) sky in warm gold (#FFCE70 → #96682B gradient) — then the `sync` highlight (#FFD36B) must change to a white outline so it is not confused with gold notes. **Kelvin chose (a) pearl white at 22:49** — no colour change; every mock-up and test scene must draw sky notes in `skySig` pearl white and ground notes in `groundSig` cyan. Whatever Kelvin picks applies to every sky element (notes, hold ribbons, trail copies, sky hold motes, upper line glow) through `Theme.SKY_SIG`.
### 21.4 TIDE band softer, no band on the way back
See §19.2 v3.

### 21.5 Gameplay HUD in the corners (Kelvin 22:54; final decisions 2026-10-12) — built
**Final (owner 2026-10-12; the rules now live in §5.1–§5.4 and §5.12):**
- Corners at every aspect, anchored to the `Ui.safe*()` edges: **score + accuracy top-LEFT** (score 80 % of its §1.3 size, the 9-digit score, glint and meter kept; accuracy 80 % but never below 20; since 2026-10-20 no accuracy and the score at full size, a fixed distance from the screen's left edge, §24.31, sized and placed by the top band since 06:27, §24.32, §24.33), **pause top-RIGHT** (00:58: 「右上角双击暂停」), difficulty chip + song title bottom-left, time remaining bottom-right, progress hairline along the top edge. The HUD is drawn last.
- 04:04 approved the layout and asked for a smaller, higher pause control: 「游玩界面的布局不错，暂停键可能要稍微缩小一点，上移一点即可。保持暂停键在右上角的状态」. Ring ⌀ 80 → 64 (r 32·k, stroke 3·k), bars 8×28 → 6×22 at ±7, centre (safe right − 26k − r, safe top − 50k) = (W − 58, 50 below the top) at 1920×1080; the touch box does not shrink. Since 2026-10-20 05:25 the centre is halfway between the top edge and the sky line (§24.30).
- Pause = **double tap** on the control by default (two downs within 300 ms; the second triggers; first-tap hint 300 ms); every touch in the box also reaches gameplay first; a touch that judges, re-grabs or covers a hold, or touches a mine, neither counts nor cancels. Hold 0.35 s and Single tap stay as options under the same rule. BACK, Esc and the Pause key pause at once; **P is not a pause key** (it is an upper-row lane key).
- Score, accuracy, chip, title and time are display-only.

**Preview K3 text of 22:54, as history (replaced where marked):** at every aspect the gameplay HUD sits in the screen corners, outside the two judgement lines, not in the middle band of §5.2/§5.3: ~~pause top-left, score + accuracy top-right~~ (**replaced:** swapped, see above) (80 % of the §5.2 size), difficulty chip + song title bottom-left, time bottom-right; each anchored to the `Ui.safe*()` edges with the §5.2 margins. At 16:9 the corners fall inside the outer lanes' judgement zones (the lines are only 120 units from the edges): score, accuracy, chip, title and time are display-only and take no input; ~~the pause control keeps its hold-350 ms rule~~ (**replaced:** double tap by default), its touch is also delivered to gameplay like any other touch, and ~~the hold is cancelled the moment that touch judges or holds a note~~ (**replaced:** a judging touch does not count toward the double tap). At 4:3 and 3:2 the extra height puts the corners clear of the lines; the same corner layout applies. Lane glows and held-STREAM bodies never draw over the HUD (it is drawn last).

### 21.6 TIDE colours per line (Kelvin 22:55) — preview K4
TIDE and TIDE STREAM follow the line colour like every other note: sky (upper-line) TIDE heads use `skySig` pearl (dim #808EA0 → #ECF4FC gradient, white leading edge, `tide` diamonds); sky bands, rails and RESONANCE effects (rise/recede, line swell, stream light bands) use `skyTide` = 0.7·`skySig` + 0.3·`tide`. Ground TIDE keeps `tide` #8CFFD9. ECHO effects are `echo` violet on both lines (§21.1).
**Built 2026-10-12** (mech for the notes, look for the effects): `Theme.SKY_TIDE` (≈ `#D3F9F4`), `SKY_TIDE_HEAD_DIM`, `SKY_TIDE_HEAD_BRIGHT`; the sky TIDE STREAM head's pearl gradient is drawn at α .85 at the leading edge and .60 at the trailing edge. Sky TIDE **drops** now use the T4 pearls in `skySig` (§5.15), so the gradient applies to TIDE STREAM heads.

### 21.7 Sky and ground holds at the same time (Kelvin 23:00) — preview O1–O3; redraw kept 2026-10-12
**Decision (owner 2026-10-12 04:44: 「长条改的也很自然，先保留吧我再看看」, kept for now).** The ribbons and bands below are built, with the redraw's two numbers: `NoteShapes.SKY_HOLE_PAD` = **0** (the ground hole is exactly the sky ribbon, no extra 3 px each side) and `NoteShapes.SKY_TIDE_STREAM_INSET` = **0** (the sky TIDE STREAM band is full width; in the rows where a ground band overlaps it, the ground band draws nothing under it). The first proposal's 3 / 56 are kept as `SKY_HOLE_PAD_FIRST` / `SKY_TIDE_STREAM_INSET_FIRST`: switching back is a change of these two constants, and every geometry function takes them as parameters, so the tests check both settings. Other as-built details: at most 4 sky bodies knocked out per ground body (more overlaps draw without a hole); a body counts as upright for the knock-out when |sin angle| < 0.01; tail caps always draw, also inside an overlap; ground ribbon edges up to 8 px (a quarter of the ribbon when narrower). **Replaced** in the proposal text below: "sky width + 3 px each side" (now + 0) and "the sky band is inset 56 units … only its 56-unit side strips show" (now no inset).

Problem: a sky STREAM and a ground STREAM in the same lane draw equal-width translucent ribbons that blend into one grey strip (two heads, no visible ends); a sky and a ground TIDE STREAM stack into one darker grey-teal field with coincident rails and identical white tail caps. Proposal shown to Kelvin:
* Lane STREAM ribbons: sky ribbons are always 56 % of the lane width (ground stays 82 %), sky edges 6 px. Fill fades head → tail (.42 → .16; held .62 → .30). A 3 px tail cap in the ribbon's own colour at α .8. Where a ground ribbon overlaps a sky ribbon in the same lane, the ground fill leaves a hole under the sky ribbon (sky width + 3 px each side) so the colours never mix; the ground edges stay.
* TIDE STREAM bands: the sky band is inset 56 units from each window end; rails at α .6 and the tail cap (4 px, α .75) in the band's own colour. Where both bands overlap, the ground fill leaves a hole under the sky band (only its 56-unit side strips show).
* Lane notes keep drawing above the bands (§19.1).
Implementation is a few extra quads per overlapped stretch (split the ground fill into side strips over the overlap); no per-pixel blending.

## 22. Wave 2: the owner decisions of 2026-10-12 and where they live [P0]
Each decision below is written into the section named in the last column; this table is only the index. \"Build\" marks a decision the owner's words left open, taken while building it.

| Decision | Built as | Section |
|---|---|---|
| HUD in the corners at every aspect: score + accuracy top-left (80 %), pause top-right, chip + title bottom-left, time bottom-right; drawn last; display-only (22:54, 00:58) | `GameplayHud` corner layout and top layer | §5.1–§5.4, §21.5 |
| Smaller, higher pause control, kept top-right (04:04) | ring ⌀ 64 at (W − 58, 50 below the top), touch box unchanged | §5.3 |
| Pause by double tap on the top-right control (00:58 「右上角双击暂停」); Hold and Single tap kept; no migration | `PauseControl`, `Settings.pauseMode` default `PAUSE_DOUBLE_TAP` | §5.12, §9.2 |
| Build: a down that touches a MINE or LANE MINE never counts toward the double tap | `PauseControl.judges`, `GameEngine.lastInputMine()` | §5.12 |
| Build (accepted): P is not a pause key; the keyboard's Pause key pauses | `PauseControl.pausesAtOnce` | §5.12 |
| Pause menu variant B: no panel, RETRY / RESUME / QUIT with zh sublabels (00:58, 23:35) | `PauseMenu` | §5.12 |
| The note legend leaves the pause menu and becomes the Settings NOTE GUIDE 音符图鉴 (mock-up P13 v2), with no GROWING and no AFTERIMAGE entry | `gameplay/NoteGuide` and `screens/shell/NoteGuideRow` (not shown since 2026-10-18, removed in 0.1.0-beta.1) | §5.12, §5.15, §9.2, §17.3, §19.1, §19.3, §20.2 |
| Mines dangerous within ±66.67 ms; LANE MINEs block from T | `Params.MINE_WINDOW_MS`, `Params.LANE_MINE_LEAD_MS` | §5.15, ARCHITECTURE §13.2, §20 |
| A touched mine shows a red LOST (00:42) | `Theme.MINE_LOST`, `GameplayHud.mineWord`, `FeedbackLayer.mineWord` | §5.6, §5.15 |
| Build: the red word stays when the same touch also judges a note; FULL COMBO and ALL RESONANCE need no mine touched anywhere in the run | `GameplayHud.judgement`, `ScoreKeeper.isFullCombo()` | §5.6, §5.13, §12, §18.3, ARCHITECTURE §13.2, §18 |
| Build: the tutorial's pause label follows Settings → PAUSE INPUT (hold / double tap / tap), and the double-tap demo plays only in double-tap mode; a SKIP during the resume countdown no longer lets the song resume behind the result | `IntroductionGuidelines(boolean zh, int pauseMode)` (generated from `PAUSE_LABELS`), `IntroductionScreen`, `GameplayScreen.finishNow()` | §10.1, §10.2 |
| Mines behave like DROP / STREAM: they turn, and so do their copies | `NoteRenderer`, `TrailGeometry` | §5.15, §17.1 |
| LANE MINE end to end; pinned once it reaches its line (03:03); the other line stays visible across it (02:58) | `Note.isLaneMine()`, `GameEngine`, `NoteShapes`, `NoteRenderer` | §20.2, ARCHITECTURE §20 |
| Growing bars | `HeightEvents`, `Note.tailHeightAt` | §20.1, ARCHITECTURE §20 |
| A TIDE on the exact beat and line of a DROP / STREAM is judged with it | `GameEngine` joint judging, `GameEvent.FLAG_JOINT` | §5.15, ARCHITECTURE §20 (replaces the §13.2 tie rule) |
| Sync = a gold square in the middle of the head, nothing else changes (03:08); build decision: only when a second finger is needed | `NoteShapes.showsSyncSquare`, `Note.syncLanes()` | §1.2, §5.8, §5.15, §17.1 |
| TIDE drops in the T4 look: sky pearls, ground rolling wave (04:36) | `NoteShapes` T4, `NoteRenderer.paintTideT4` | §5.15 |
| Sky TIDE colours (22:55, K4) | `Theme.SKY_TIDE`, pearl gradient | §21.6 |
| Sky / ground overlap: the redraw is kept for now (04:44), `SKY_HOLE_PAD` 0 and `SKY_TIDE_STREAM_INSET` 0, switchable to the first proposal | `NoteShapes` | §5.8, §19.1, §21.7 |
| Water rings for DROP / STREAM; every effect in its judgement colour | `fx/WaterRings`, `HitFx` | §5.9 item 12, §20.5, §21.1 |
| TIDE hit = the v3 tide wash and line swell; TIDE STREAM light bands on a fixed clock | `TideWash`, `FieldRenderer.swell` | §19.2, §20.3 |
| Full-height lane separators | `FieldRenderer` | §5.7, §21.2 |
| Build (accepted): no contact ring at the finger for a judged TIDE hit | `GameplayScreen` | §5.10, §19.2 |
| Tutorial rewritten: every basic mechanic, EN and 中文 captions | tutorial v3 | §10.2, §17.3, §20.4 |
| Build (accepted): the SKIP pill under the pause corner | `IntroductionScreen` | §10.1 |

**Open points for the owner** (found while building, not changed):
- The Settings edge padding moves the top corners toward the sky line at 16:9; past about 25 they fade to α .15 (§5.3).
- Light corner text is hard to read while a bright note passes under it (e.g. the accuracy over a ground TIDE wave at note size 1.3); a 1-unit dark shadow would fix it without a draw call (§5.3).
- A profile saved before 2026-10-12 keeps HOLD as its pause input until the player changes it (§5.12).
- In the tutorial the SKIP pill (bottom centre, over lanes −1 / +1) covers the sky DROPs of those lanes and the sky TIDEs for a moment as they enter at the bottom edge (owner's chart, 2026-10-17; v3 had it over two sky TIDE bars and a LANE MINE column). Judged acceptable.
- The two requiem lines show for 3.25 s; reading them at 228 words per minute needs about 3.95 s (owner copy, unchanged).
- A broken TIDE STREAM still shows a LOST × at the window centre (§19.2).
- `HitFx.tap` gives the Settings live preview, calibration and the tutorial's grade demos water rings too (§5.9 item 12).
- The Chinese caption wording of the tutorial (the 中文 set in `IntroductionGuidelines`) waits for the owner's read.

## 23. Shells, depth and fifteen grades (owner request 2026-10-17 04:42, confirmed 05:38 and 05:44) [P0]

Replaces §8.6, §11 and the grade tables of §12 / §18.3–18.5.

### 23.1 Shells (`profile/Shells`)
- Shells replace EXP one to one: the per-play formula and bonuses of §18.5 / §11.6 are unchanged (first play +40, new best +20, better grade +40, first FULL COMBO +50, first ALL RESONANCE +100, daily first play +50, first tutorial completion 150; none for autoplay, quit or restart).
- There is no level: no LV, EXP bar or LEVEL UP anywhere. The map badge shows a gold shell pill with the count and the depth below the name (no bar since 14:10, §23.4). The result strip counts the shells from before to after; the `+N 🐚` chip opens the breakdown (no title since 2026-10-18 17:22, SHELLS THIS DIVE until then; its rows and TOTAL follow the language, §24.16). The Profile header's first row is `SHELLS` with the count.

### 23.2 Buying singles
- A song must be bought when no story level places it (any level except `hub`, `grid` and `collect` routes) and the diver does not own it. Today that is Aleph-0, collected into SINGLES.
- Price: the spot's `price`, else the `collect` level's `price`, else 300 (MAP_FORMAT §1, §2).
- For sale: the hole shows a gold dashed rim and a shell icon, the label line the price (map and ALL SONGS). The card shows difficulties and constants; AUTO is disabled; DESCEND becomes a gold `HOLD TO BUY 🐚 300` plate that fills left to right over 0.6 s while held and buys on completion (no dialog, no refund). Short of shells it is grey, `NEED 🐚 120 MORE`, and a press shakes it. A purchase toasts `BOUGHT · title`; the hole turns into a normal song hole. Owned ids live in `Profile.ownedSongs`.

### 23.3 Depth (`profile/Depth`)
- **Since 14:10, corrected 15:22** (Kelvin 14:10: 「把计算规则改简单一点：分数系统取消掉。每张谱的深度改为40*(定数+max（0，（分数-96M）/2M））^2」; 15:22: 「哦哦错了，定数公式是40*max(0，定数+（分数-96M）/2M））^2」): chart depth = 40 m × max(0, c + bonus)², bonus = (min(score, 100,000,000) − 96,000,000) / 2,000,000. So 96,000,000 counts exactly 40 × c², 100,000,000 and above add 2 to the constant, and lower scores take from it (−3 at 90,000,000, −18 at 60,000,000) down to a depth of 0. ~~14:10 as first built: the bonus was max(0, …), so any finished run counted at least 40 × c².~~ ~~05:38: 40 m × c² × f(score), f piecewise linear (0 up to 60 M, 0.5 at 90 M, 1 at 96 M, 1.2 at 98 M, 1.5 at 100 M).~~
- Player depth = the mean of the 10 deepest charts (empty slots count 0; autoplay and the tutorial never count). Shown in whole metres, rounded down.
- **Shown negative since 14:20** (Kelvin: 「把这些公式前面都加一个负号……更贴和"深度"和下潜的主题」): every depth on screen has a minus sign (`-5,525 m`, card `-4,000 / -5,760 m`, gains `-84 m`, result `▼ -24 m`, locks `UNLOCKS AT -7,000 m`). The code keeps positive metres below the surface and adds the sign when it formats (`ShellFormat.appendDepth`, the badge, card, TOP 10, result strip).
- No chart rating ρ, player rating R, rank titles or emblems remain. The card shows `DEPTH cur / max m` (max at 100,000,000) or `FIRST DIVE · +40 SHELLS`; its NEXT goal (since 15:32, Kelvin: 「给出推分建议的时候给到下一个整百，如果只推一首歌没发到的话就到某个50，还没发到就到某个整10，还没发到就显示最多推到什么地方」) is the player depth this chart alone can bring: the next whole 100 m, else the next 50 m, else the next 10 m, at the lowest score above the best (a multiple of 10,000) that gets there (`NEXT ▼ -2,600 m AT 94,990,000` at -2,580 m); when even the next 10 m is out of reach, `MAX ▼ -2,766 m AT 100,000,000` names the deepest it can go; when it cannot deepen the player by a metre, MAXED (`Depth.nextGoal`). ~~Until 15:32: the lowest grade threshold (§23.5) above the best score that deepens the player by at least 1 m, shown as the gain (`NEXT ▼ -84 m AT 98,000,000`).~~ The result header reads `CHART DEPTH -n m · TOP 10 #k`; the TOP 10 tab lists metres.

### 23.4 Depth tiers and avatar unlocks
Tiers (Kelvin 14:10: 「以后划分几档按照这样划分：0~2000，2000~4000，4000~6000，6000~7000，7000~8000，8000~9000，后面每500一档」; `Depth.tierMeters`, `tierOf`): 0, 2,000, 4,000, 6,000, 7,000, 8,000, 9,000 m, then every 500 m. Tiers 1 to 11 unlock the avatar options (my reading; the 05:38 depths 70 … 9,300 m are gone):

| Depth | Unlock |
|---|---|
| 2,000 m | pattern WAVES |
| 4,000 m | frame DASHED SONAR |
| 6,000 m | pattern GRID |
| 7,000 m | +3 palettes |
| 8,000 m | frame DOUBLE |
| 9,000 m | pattern SPLIT-K |
| 9,500 m | pattern SPIRAL |
| 10,000 m | pattern CONSTELLATION |
| 10,500 m | glyph K |
| 11,000 m | ORBIT RING |
| 11,500 m | frame IRIDESCENT |

- The result's LEVEL UP alert becomes UNLOCKED with the option (several at once: `N NEW AVATAR OPTIONS` and their names). The avatar editor shows `-9,500 m` on a locked option; a locked palette in the diver form toasts `UNLOCKS AT -7,000 m`.
- **14:10:** 「个人档案下面的进度条取消掉，只保留一个简洁的数字」 (14:25 again for the left column): the Profile header keeps only avatar, name, SHELLS and the depth (no NEXT UNLOCK, option, `TO GO` or bar); the map badge keeps the name, shells and the depth (no `NEXT n m`, no bar). The result strip's mini gauge stays; its marker now sinks through the current tier (`Depth.tierProgress`).

### 23.5 Grades (`profile/Grades`)
| Grade | Score |
|---|---|
| R+ | the chart's theoretical maximum (`Judgement.maxScore(totalNotes)`, every note a PERFECT RESONANCE) |
| R | ≥ 100,000,000 |
| SS+ | 99,000,000 – 99,999,999 |
| SS | 98,000,000 – 98,999,999 |
| S+ | 96,000,000 – 97,999,999 |
| S | 94,000,000 – 95,999,999 |
| A+ | 92,000,000 – 93,999,999 |
| A | 90,000,000 – 91,999,999 |
| B+ | 87,000,000 – 89,999,999 |
| B | 80,000,000 – 86,999,999 |
| C+ | 77,000,000 – 79,999,999 |
| C | 70,000,000 – 76,999,999 |
| D+ | 67,000,000 – 69,999,999 |
| D | 60,000,001 – 66,999,999 |
| F | ≤ 60,000,000 |

Colours: R+ and R steady light blue `#A6DDFF` (Kelvin 15:34: 「R和R+用浅蓝色显示，或者是带有点光泽的感觉」) ~~(R+ cycled cyan / violet / gold, R was the resonance cyan)~~; SS+, SS, S+ and S gold `#FFD27A` (18:10: 「S和SS都用金色（+号默认和不带加号的一样）」); below S a neutral grey lightening one letter at a time to white (19:34: 「F能不能用灰色，然后逐渐向A+的纯白色过渡？」): F `#808080`, D `#A0A0A0`, C `#C0C0C0`, B `#E0E0E0`, A `#FFFFFF`, each "+" the colour of its letter (`Grades.GRADE_RGBA`) ~~(SS gold, S silver, A blue, B periwinkle, C and D grey-blue, F muted red; 18:10 as first built, never shipped: A and B white, C, D and F grey-blue `#6C7899`)~~. Stamps keep §12's sizes (3 rings for R+ to S, 2 for A+ to B, 1 below).

- **White sweep** (Kelvin 16:02: 「结算界面和选歌界面的R和R+白光要持续扫过」; `Theme.sweep`): a diagonal white band (slope 0.45, half width 0.32 × cap, α .85) crosses the letters and the superscript left to right in 1.0 s, back to back on R+ (every 1.0 s) and every 1.4 s on R. On the result stamp (additive) from 2.40 s, SS+ still once; on the card's grade ring (sheet: the grade pill) and the close-zoom grade under each hole (`MapRenderer.grade`, normal blending). RECENT pills do not sweep (TOP 10 has no grade pill). ~~15:34 as first built: a glint toward white every 4 s (R) / 2.5 s (R+) on the select screen and RECENT, the stamp's band on the same beat.~~
- **Superscript "+" on the select screen** (16:02: 「+号在选歌界面也写成上标」): the card ring, the sheet pill and the close-zoom label draw a trailing "+" like the stamp, at 0.55 × the letters' cap, top-aligned with them, 0.04 × cap after them; the whole grade shrinks to fit (ring 62, pill 60 units × card scale). Since 16:40 the RECENT pills too (「那边也改成上标，但是不用闪光」; `ChartRows.gradePill`, same proportions, no sweep).
- **FULL COMBO purple `#B49CFF`, ALL RESONANCE light blue `#A6DDFF`** (16:02: 「full combo标识改为紫色，all resonance标识改为浅蓝色」; `Theme.FULL_COMBO`, `Theme.ALL_RESONANCE`): the card badges, the result badge and its ACCURACY / MAX COMBO tints, the RECENT tags, ~~the ring round a hole's grade dot (§3 difficulty arcs; gone with the score rings, 16:40)~~, and the end banner with its sonar ping and burst (§5.13; the AR burst alternates light blue and white). Replaces `#7FE7FF` for FULL COMBO and the iridescent cycle for ALL RESONANCE; the R+ motes and the iridescent avatar frame keep the cycle.

### 23.6 Saves
Schema 4 since 15:22 (schema 3 was the 14:10 formula as first built): a schema 2 or 3 profile clears its depth history once (its points were on an older formula; the dive profile starts again with the next play) and every depth is recomputed on load. Schema 2: a schema 1 profile is copied once to `<id>.json.schema1.bak`, then EXP becomes shells 1:1, every song with a recorded play is owned, stored grades are recomputed (a former PURE becomes R), depths are recomputed from the bests and the rating history is dropped. Equipped avatar options stay. RESET RECORDS kept shells and bought songs until 2026-10-18 20:16; since then it clears them too (§24.17).

### 23.7 RETRY transition
The DIVE without cover art (RETRY, the tutorial) shows nothing on the closed walls (no SYNCING SONAR line or depth counter; Kelvin 05:55).

### 23.8 Profile STATS, TOP 10, RECENT and rename (Kelvin 2026-10-17 14:24, 14:41, 14:47, 14:55, 15:25)
「首先把dive profile移动到top10里面，然后把grade distribution去掉。原来的charts played的那个地方改为一个点一下切换难度的按钮，可以分开看surface/shallow/deep/abyss的数据。点击对应的数据后就能看到下面一排出现charts cleared/full combo/all resonance，avg accuracy去掉。played per difficulty也去掉。下面还空着一块放一个直方图显示展示打了的曲目的分数」(14:24)
「可以，然后top10往下滚动的过程中dive profile固定不动。直方图不是这样的，做成跟结算界面的那个打击偏移的直方图类似的样式」(14:41)
「蓝色：把plays和play time移动到下面的空隙里。黑色圈：shells删掉，贝壳数移动到名称和depth之间。绿色：把四个难度展开到第一行，想看某几个的组合就点亮两个及以上，至少要点亮一个（只亮一个就不能再deselct了）。红色：把直方图拉长到填满整个屏幕。白色：log改名为recent。」(14:47, colours on the plan image)
「还有现在就是修改用户名的界面很丑，能不能点击名称修改的时候只弹出一个键盘？」(14:55)
「然后白色的方框和箭头：把1/9 played等字样放到下面5/9charts cleared，上面的选项按钮只保留surface shallow deep abyss」(15:25, on 方案图2)
- **Header** (`ProfileHeader`): avatar, name, the shell count (gold, centred between the name and the depth, no `SHELLS` label) and the depth. PLAYS and PLAY TIME moved here from STATS: two full-width bars under the card down to the bottom (label UI_LABEL 20 left, NUMBER 36 right, at most 140 tall, gap 16). On 4:3 they sit at the right of the header block.
- **STATS** (`StatsTab`):
  - Row one: four difficulty toggles (112 tall, gap 24) with only the name (UI_BOLD 30, vertically centred; `6/9 PLAYED` dropped at 15:25). Lit: tinted card, border and lamp in the difficulty colour. Unlit: plain card, ring lamp, grey text. All four start lit (kept while the app runs). Any combination can be lit, but at least one: tapping the last lit toggle only shakes it.
  - Row two (140 tall), summed over the lit difficulties, with a stripe of their colours: CHARTS CLEARED (best score D or better) out of the lit difficulties' charts (`13/31`, the `/31` in `ink3`; 15:25), FULL COMBOS and ALL RESONANCE (`RESONATED` since 2026-10-18 14:29, English in both languages, §24.13).
  - **BEST SCORES** fills the rest of the tab down to the bottom (at least 300 tall; the tab scrolls only when it is shorter). It is the lit difficulties' best scores as a histogram drawn like the result screen's echo trace (`EchoTrace`, dropped 2026-10-19 17:48, §24.25): 41 mirrored bins of 1,000,000 from 60,000,000 (lower scores in the first bin, 100,000,000 and up in the last). Bins from 96,000,000 (`Depth.BONUS_FROM`, where a chart counts exactly 40 × c²: lower scores take from the constant, higher ones add; marked by a 2 px line) are RESONANCE cyan, lower ones ECHO purple. Ticks 60M, 70M, 80M, 90M, 96M, 100M; a caret `μ 87.23M` at the mean best score. The tallest bin counts at least 3 charts. The bins rise as a sweep crosses (0.8 s, starting 0.3 s after a show or a toggle); an idle sweep repeats every 4 s. Nothing played: `NO CHARTS PLAYED YET` (`NO SHALLOW CHARTS PLAYED YET` with one lit).
- **TOP 10** (`TopTab`): the DIVE PROFILE (moved from STATS, 200 tall) is pinned on top (14:41); only `YOUR DEPTH IS THE AVERAGE OF THESE TEN` and the rows under it scroll. **Since 2026-10-18 the tab is DST 10, the sentence is gone and DEEPEST 10 / OVERFLOW list the charts (§24.4).**
- **RECENT** (`LogTab`): the LOG tab, renamed (14:47); content unchanged.
- **Rename** (14:55): tapping the name renames in place (`TextField.Mode.KEYBOARD`). On a phone only the soft keyboard comes up (`Input.openTextInputField`; Android shows its own one-line field above the keyboard) and the name is underlined while it is open; DONE renames, BACK cancels. On desktop the name turns into an inline field: Enter renames, Esc cancels. An empty name is rejected with a shake. The DIVERS tab's RENAME and naming a new diver still use the rename dialog (§8.3).

## 24. Settings, 中文 and the DST 10 tab (Kelvin 2026-10-18 00:24–20:56) [P0]

### 24.1 Settings (00:24)
「设置界面要修改：timing分开到audio和visual里面，visual offset感觉跟audio offset有点重合，去掉。……新手教程和account合并，account只保留backup code的export按钮和reset records，about里面的新手教程保留，然后加一个"开源许可证"，里面加上必要的信息，并把所有的music charts，typefaces等都移到这个里面」
- Five tabs, no TIMING (four since 02:27, §24.6); the TIMING section ends AUDIO and the EARLY / LATE section sits in VISUAL between PLAYFIELD and INTERFACE (§9.1, §9.2). The visual offset and its calibration page are gone (§9.2, §9.5).
- ACCOUNT: BACKUP CODE → EXPORT and RESET RECORDS only. ABOUT: TUTORIAL → PLAY, K & K and the version, OPEN-SOURCE LICENSES → VIEW, YOUR DATA. The music, chart and typeface credits that ABOUT listed moved into the licences page (§9.2).
- `+ PEARLS` is the English label of the middle SYNC HINT option (was `GLOW + PEARLS`, which did not fit its segment; 中文 光晕 + 珍珠). Since 04:34 the options are OFF / ON / ENHANCED (§24.10).

### 24.2 中文 everywhere but the map (00:24)
「然后现在gameplay点击英文转中文除了底下的note guide发生了变化其他的一个都没变。除了选歌界面上的英文不变其他的都要变。」
- `ui/Lang`: `Lang.of(en, zh)` is a text that reads the language each time it is drawn, so switching LANGUAGE in Settings changes every screen at once (set from `Settings.language` in `Main.applySettings()`); `Lang.t` picks a string for text built per frame, `Lang.list` builds option lists. CJK glyphs come from the Noto Sans SC fallback and are warmed while a transition covers the screen.
- In 中文: welcome, first run, Settings and its licences page, calibration, the profile screen, the tutorial captions, the transition credits (音乐 / 谱面 / 曲绘; 谱师 since 20:45), toasts and notices, the NOTE GUIDE (Chinese only). From this round until 16:41–16:45 also the result screen, the gameplay HUD (自动演示, 连击, 早 / 晚, 长按跳过) and the pause menu (继续 / 重开 / 退出, Chinese only); these are English in both languages again (§24.15), except the pause menu and the result screen's RETRY / CONTINUE, offset sheet and shell breakdown, which follow the language again since 17:22 (§24.16), and its score breakdown and the expanded trace's PEAK / MEAN since 20:34 (§24.17). The transition credits read 音乐 / 谱师 / 曲绘 since 20:45 (谱面 before).
- Stays English: the map song select (Kelvin's words), song titles and artists, difficulty names (SURFACE, SHALLOW, DEEP, ABYSS), the DST 10 tab's `DST 10`, `DEEPEST 10` and `OVERFLOW` (00:37 「这两部分的英语可以不用」), the licence texts, and the glitch-font words on the playfield: the judgements RESONANCE / ECHO / LOST and the end banner (CLEAR / FULL COMBO / ALL RESONANCE). Rubik Glitch has no Chinese glyphs; Kelvin chose to keep them English (02:22, decision card on the preview). Since 16:41–16:45 the whole dive and the result screen too (§24.15).

### 24.3 DIVERS (PLAYER since 02:27): tap only switches (00:34)
「然后这里只允许切换diver，现在点击diver那个标签卡还可以编辑，我不要」
- Tapping a diver card switches to that diver (the active one does nothing). The rename / export / delete menu (`DiverActionsDialog`) no longer opens from the card, so deleting a diver has no entry point for now; NEW DIVER and IMPORT stay.

### 24.4 DST 10 (00:37:57)
「把"Your Depth is the average of these ten"去掉，只写一个Deepest 10，然后后面没有的6~10 用空位占着，然后后面再放5个Overflow。这两部分的英语可以不用。上面的Top 10改成DST 10」
- The tab is `DST 10` in both languages; the result header reads `CHART DEPTH -n m · DST 10 #k` (`谱面深度 -n m · DST 10 #k`).
- Under the pinned DIVE PROFILE: the header `DEEPEST 10` and ten rows, empty slots drawn as a dim row with a dash; then `OVERFLOW` and the 11th to 15th deepest charts (not counted in the depth), five rows, empty ones likewise.

### 24.5 DIVE PROFILE dates and zoom (00:38:29, 00:40:02)
「Dive Profile横坐标要有日期」「中间可以放大查看具体时间点」
- `screens/shell/DiveChart`: the x axis is the play time of each depth point; ticks step from 1 minute to a year so labels stay 100 apart, and the date (`10月17日` / `OCT 17`) is written under the first tick and at each day change.
- Pinch (phone) or the mouse wheel over the chart (desktop) zooms around the fingers or cursor down to a 10-minute window; a drag pans; a double tap shows everything again. Tapping a dot shows `10月18日 01:40 · -2,081 m` (`OCT 18 · 01:40 · -2,081 m`) above or below it; tapping again hides it.
- The two depth labels have their own column on the right; the curve, the dots and the callout stop left of it.

### 24.6 OTHER, CALIBRATE beside the nudges and the PLAYER tab (02:27, after the first preview)
「账号和关于合并为一个"其他"，然后红框框起来的校准放在音频延迟下面那个空地方，蓝色的地方改成"玩家"，英文版改成player」 (red box: the CALIBRATE row; blue box: the profile's DIVERS tab)
- Settings has four tabs: AUDIO, VISUAL, GAMEPLAY and OTHER (其他, three-dot icon). OTHER lists ACCOUNT (BACKUP CODE, RESET RECORDS, STORED ON THIS DEVICE ONLY) then ABOUT (TUTORIAL, K & K and the version, OPEN-SOURCE LICENSES); the licences pages keep OTHER lit and BACK returns to it (§9.1, §9.2).
- CALIBRATE… is a button in the AUDIO OFFSET row, left of `[–10][–1][+1][+10]` (`SliderRow.setLead`), up to 320 wide and as tall as the nudges. The CALIBRATE row and its description are gone; the not-calibrated hint is now the offset row's gold description (§9.2).
- The profile's fourth tab reads `PLAYER` / `玩家` (was `DIVERS` / `潜水员`). Only the tab label changed then: its cards, NEW DIVER and the rest of the screen still said diver (潜水员) until 2026-10-18 20:15, when the Chinese texts took 玩家 everywhere (§24.17); the English keeps DIVER.

### 24.7 LAST ACTIVITY on the PLAYER cards (02:53)
「蓝色圈起来的地方改为last activity：时间点」 (blue circle: the card's last line, `DIVED TODAY`)
- The third line of every PLAYER card is `LAST ACTIVITY: OCT 18 · 02:49` (`上次活动：10月18日 02:49`): the date and time of the diver's last recorded dive (`Profile.lastPlayedAt`), with the year added when it is not this year's (`OCT 18, 2025 · 02:49`, `2025年10月18日 02:49`) and a dash before the first dive (`ShellFormat.lastActivity`).
- The diver switcher sheet keeps its relative `DIVED TODAY` label.

### 24.8 Settings tidied (03:25, after the delivery)
「设置里的延迟和声音这两个分区互换顺序，音频延迟和音频输出也互换顺序，确保音频延迟是在第一个。2. 画面那里有点乱，先把实时预览相关的所有参数放在一起，不相关的整理到一起，我来决定顺序。3. 游玩底下的"音符图鉴"放到其他里面。我估计判定显示是不是也和实时预览有关。4. 其他里面"只保存在这台设备上"去掉，K&K 两道信号一同下潜放在左下角那里。其他四个不要再分区了，直接放在一起，然后底下放音符图鉴」
- AUDIO starts with TIMING: AUDIO OFFSET (with CALIBRATE… and the nudges), then AUDIO OUTPUT; SOUND follows (§9.2).
- VISUAL has two sections, LIVE PREVIEW (glitch intensity, reduce flashing, particle quality, background dim, note size, zone hint, approach glow, judgement display) and MORE (the rest). Without the EARLY / LATE header its two switches read `EARLY / LATE ON RESONANCE` / `共鸣的早 / 晚提示` and `EARLY / LATE ON ECHO` / `回响的早 / 晚提示`. Kelvin decides the order next.
- JUDGEMENT DISPLAY was not shown by the preview (its word always sat in the middle). It moved from GAMEPLAY into LIVE PREVIEW, and the preview now follows it (§9.3).
- GAMEPLAY keeps PAUSE INPUT, LIVE COUNTERS, HAPTICS and LANGUAGE; the NOTE GUIDE is the last row of OTHER.
- OTHER: BACKUP CODE, RESET RECORDS, TUTORIAL, OPEN-SOURCE LICENSES and the NOTE GUIDE with no section headers; STORED ON THIS DEVICE ONLY is gone; K & K, its line and the version are in the bottom-left corner (§9.1).

### 24.9 LIVE PREVIEW page, hints in GAMEPLAY, SHOW TOUCHES (04:06, after the numbered preview)
「实施预览里的放在"画面中"一个和calibrate类似的那种点进去才显示的东西。游玩里的实时计数取消掉，我从来没看到过这个在哪里。14~18移到游玩里，放在最开头。空点标记改为"显示触控"，只有一个开一个关，移到实时预览中。其余的9~12，19~21留在"画面"中。把K&K 两道信号一同下落那个放在开源许可证下面。」 (the numbers are those of the 03:25 preview sheet: 1–8 the LIVE PREVIEW section, 9 reduce motion … 21 show FPS)
- VISUAL: a LIVE PREVIEW row whose PRIMARY ADJUST… (调整…) opens the LIVE PREVIEW page (`SettingsScreen.PAGE_PREVIEW`, built in `buildPreview`); then reduce motion, background detail, background art, art opacity, UI scale, extra edge padding, show FPS (§9.2).
- The LIVE PREVIEW page holds 1–8 and SHOW TOUCHES, with the preview (§9.3).
- STRAY-TOUCH DOTS (Auto / On / Off) became the switch SHOW TOUCHES, default off; the old AUTO reads as off (`GameplayScreen` shows the dots only for STRAY_ON, or in the tutorial).
- GAMEPLAY: sync hint, early / late on RESONANCE, early / late on ECHO, show ms, hit error meter, then pause input, haptics, language. LIVE COUNTERS is gone from the list (§9.2).
- OTHER: K & K, its line and the version are the row under OPEN-SOURCE LICENSES again; the corner under the rail shows the version only, as before 03:25 (§9.1).

### 24.10 Sync hint names, LANGUAGE in OTHER, the SHARED tag (04:34 and 04:48, after the 04:06 preview)
「同时提示的种类分成"关/开/增强"，以这个顺序，语言塞进其他里面，然后那些在后面跟着的"本机"能去掉吗还是说有什么特殊含义」; after the answer, 04:48: 「那可以标注一下公用」
- SYNC HINT reads OFF / ON / ENHANCED (`关 / 开 / 增强`) in that order; ON is the glow, ENHANCED the glow and the pearls. Stored values are unchanged (§9.2).
- LANGUAGE is the first row of OTHER; GAMEPLAY ends with PAUSE INPUT and HAPTICS.
- The DEVICE tag (`本机`) only marked settings stored for the device (`device.json`, shared by every diver on it and not carried by a backup code). It now reads `SHARED` / `公用` (§9.1).

### 24.11 No NOTE GUIDE, a centred K & K block (05:14, 05:28 and 05:39)
「把音符图鉴那一部分删了，K & K那一个版本信息大一点。」; on the 05:14 preview, with a big box drawn under the K & K line and a small box under it, 05:28: 「居中然后竖直排列，上面那个大框写K&K，下面小框写版本，two signals one descent先不要了。」; on the 05:28 preview, 05:39: 「往下挪一点，还可以再大一点」
- The NOTE GUIDE is gone from OTHER, and no other page shows it. `gameplay/NoteGuide` and `screens/shell/NoteGuideRow` stayed in the code, unused, until 0.1.0-beta.1 removed them (§9.2).
- 05:14 first drew the K & K line (`K & K`, `TWO SIGNALS · ONE DESCENT`, the version on the right) 1.35 × larger. Since 05:28 the last row of OTHER is a centred block instead (`InfoRow.centred`): `K & K` large in the welcome screen's logo face (DISPLAY_SOFT with the LOGO glyph set, cap 100 × uiScale, `ink1`), the version under it (UI 30 × uiScale, `ink2`), 300 × uiScale tall with about 100 of space above the title, no hairline. 05:28 had cap 72, UI 24 and 200 tall; 05:39 moved it down and made it larger (`InfoRow.BRAND_CAP`, `BRAND_VALUE_CAP`, `BRAND_HEIGHT`). `TWO SIGNALS · ONE DESCENT` is not shown (「先不要了」); the welcome screen keeps its own tagline.
- Since 05:58 the block is a row again, K & K at the left and the version at the right (§24.12).

### 24.12 OTHER and GAMEPLAY without scrolling (05:58 and 06:03)
「修改成这样：根据屏幕比例自动调整大小吧，K & K放左侧，version放右侧，还是原来那样。我要"其他"这个界面不用往下滚动。你来试一下」; 06:03: 「然后resonance和echo的early/late合并在一个选项里，就叫early/late on，然后后面给两个选项按钮，就和user profile里面的surface/shallow/deep/abyss一样，可以多选，这里也可以都不选。两个按钮分别是resonance和echo，这样保证gameplay也不用往下滚动。」
- OTHER ends with `InfoRow.brand`: `K & K` at the left (logo face), the version at the right, centred in the row, no hairline. `TWO SIGNALS · ONE DESCENT` stays out (05:28).
- The row fills: `SettingsPage` adds up the other rows and the bottom pad, and the row takes what is left of the list, between 64 and 300 × uiScale (`InfoRow.BRAND_MIN_H`, `BRAND_HEIGHT`). The title is cap 100 × uiScale or half the row's height when that is less; the version keeps the 05:39 ratio (30 beside 100). A phone (density 2.75: touch target 132, Auto UI scale 1.15) gives the row 212, so the title is cap 106 and the version about 32; a 16:9 desktop window gives 300 (cap 100, version 30); 4:3 and 16:10 have room to spare and keep 300; UI scale 1.3 with 48 of edge padding leaves 100 (cap 50). Below 64 × uiScale the row stops shrinking and the page scrolls (limits below).
- GAMEPLAY's two early / late switches became one row, `EARLY / LATE ON` / `早 / 晚提示` with the chips RESONANCE / 共鸣 and ECHO / 回响 (`ChipsRow`, §9.2), so GAMEPLAY has six rows and fits without scrolling.
- The list's 40-unit bottom pad now counts only when the rows alone overflow (`SettingsPage`), so a page whose rows fit never scrolls for the pad's sake: GAMEPLAY fits even at UI scale 1.3 with 48 of edge padding (816 of list, 811 of rows), on desktop and on a phone (density 2.75).
- Limits. Every other row stays at least one touch target (48 dp, `Ui.touchTarget()`), so OTHER needs five targets plus 64 × uiScale and GAMEPLAY six targets. At 16:9 with no edge padding (912 of list, Auto UI scale 1.15) OTHER fits on screens down to 309 dp of landscape height and GAMEPLAY down to 341 dp; with 48 of edge padding (816) the limits are 349 dp and 381 dp. Desktop windows from 1280 × 720 up, tablets and the test phone (393 dp, touch target 132) fit both pages at every setting. A 360-dp phone (touch target 144) fits both at the default padding, and its GAMEPLAY scrolls once the edge padding exceeds 24 (by 88 at 48). Rows never shrink under the touch target, since a control's hit rect is at least one target tall and neighbours would overlap.

### 24.13 Kelvin's names, four switches gone, PROFILE tabs in English, RESONATED (14:29)
「设置里修改的中文名称+用这些中文重新进行英语翻译：音频延迟：谱面延时；音频输出：音频预设；音乐：音乐音量；画面：视觉；音符大小：按键缩放；判定显示：判定位置；实时预览：游玩界面视觉参数；背景压暗：背景亮度；……同时提示：多押辅助；早/晚提示：过早/过晚提示；插画不透明度：背景亮度；……打击音效：打击音效音量；备份码：备份存档。设置里删除的：背景插画的toggle switch，打击音效时机（只要判定时的），曲目试听，菜单音乐。档案界面修改的中文名称：档案：账号与统计。档案界面保留原来的英语翻译（不受语言影响）：统计（stats），最近（recent），玩家（players），已通关铺面（cleared），全连（full combo），全共鸣（英文名称改为resonated，选歌界面同步修改）。档案recent里面游玩信息的"全连"用FC代替，"全共鸣"用AR代替，中文英文都一样。……」
- Settings names: Kelvin's Chinese, the English translated back from it (§9.1, §9.2): CHART OFFSET 谱面延时, AUDIO PRESET 音频预设, MUSIC VOLUME 音乐音量, HIT SOUND VOLUME 打击音效音量, the VISUAL tab 视觉, GAMEPLAY VISUALS 游玩界面视觉参数 (row and page title; the preview box keeps its LIVE PREVIEW / 实时预览 caption), NOTE SCALE 按键缩放, JUDGEMENT POSITION 判定位置, MULTI-PRESS ASSIST 多押辅助, TOO EARLY / TOO LATE HINT 过早 / 过晚提示, SAVE BACKUP 备份存档, and two BACKGROUND BRIGHTNESS 背景亮度 rows: the artwork's opacity on VISUAL and, on GAMEPLAY VISUALS, the gameplay dim shown inverted (1 − dim). Other places that name the offset or the preset follow: the result screen's offset suggestion, the calibration toast and preset label, the first-run line and the map's reminder toast (`TUNE YOUR SONAR IN SETTINGS · CHART OFFSET`, English like the rest of the map).
- Removed: BACKGROUND ART, HIT SOUND MODE, SONG PREVIEW and MENU MUSIC. Their features stay on (hit sound on judgement only), and `Settings.sanitize` turns them back on in older saves, which would otherwise have no way back.
- Descriptions: CHART OFFSET has two lines, `EARLY 较多 → 点击减少（–）` / `LATE 较多 → 点击增加（+）` (`MOSTLY EARLY → TAP – TO DECREASE` / `MOSTLY LATE → TAP + TO INCREASE`), which replace the output name and the gold not-calibrated note; AUDIO PRESET `点击切换预设项目` / `TAP TO SWITCH THE PRESET`; HIT SOUND VOLUME `调整音符的打击音效音量` / `ADJUSTS THE NOTES' HIT SOUND VOLUME`; VISUAL's BACKGROUND BRIGHTNESS, which Kelvin left open, `菜单和曲目背景插画的亮度` / `BRIGHTNESS OF THE MENU AND SONG ARTWORK`. LANGUAGE, TUTORIAL and OPEN-SOURCE LICENSES have none.
- PROFILE: the Chinese title is 账号与统计 (档案 before; English PROFILE, also on the map button's curtain and the welcome screen's way in). The tabs read `STATS`, `DST 10`, `RECENT`, `PLAYER` and the STATS tiles `CHARTS CLEARED`, `FULL COMBOS`, `RESONATED` in both languages. RECENT tags a run `FC` or `AR` in both languages.
- The map (English only) says `RESONATED` for `ALL RESONANCE`: the card's clear badge (removed 2026-10-19, §24.21) and the filter chip. The result screen's badge and the end banner keep `ALL RESONANCE` (全共鸣 on the result screen in 中文 until 16:45, §24.15).

### 24.14 UI SOUND VOLUME, GAMEPLAY VISUALS as its own screen, FC / AR INDICATOR (15:17–16:19, before the 14:29 round was delivered)
15:17: 「音频那里加一个界面音效音量，和打击特效一样暂时还没有install。然后我其实倾向于你重新设计游玩界面视觉参数这一档，把它做成类似谱面延时校准那样的独立界面。」 15:53 (on two mock-ups: a centred preview between two columns, or the full dive with a side panel): 「方案1可以但是我感觉下半部分很空」. 16:17 (on the revised mock-up, colour-coded): 「红色：交换两者的位置。蓝色：剧中放置。绿色：删掉。黑色：加一行小字说明。然后再加一个"FC/AR指示"开关，开启以后，如果是AR分数显示为淡蓝色，如果爆了echo但还是FC分数变为紫色，如果FC都不是了就变成白色。」 16:19: 「加到右侧，把接近光晕去掉，感觉效果不明显」
- AUDIO gets UI SOUND VOLUME under HIT SOUND VOLUME, dimmed until UI sounds exist (§9.2).
- GAMEPLAY VISUALS is a screen of its own (`VisualsScreen`, §9.3): the preview large in the middle, the JUDGEMENT WORDS strip under it (so the lower half is not empty), the settings in two columns that reach the bottom. The Settings list no longer has the page or the small preview (`SettingsScreen.PAGE_PREVIEW` and `GlitchRow` are gone; the glitch sample words moved to the strip).
- 16:17: ECHO and RESONANCE swapped (RESONANCE in the middle), the strip's caption centred, no TAP TO TEST inside the preview, `GLITCH ON JUDGEMENT WORDS AND TITLES` / `判定字和标题的故障特效` under GLITCH INTENSITY.
- FC / AR INDICATOR (default on, right column under JUDGEMENT POSITION, 16:19): the dive's score is light blue while all RESONANCE, purple while a FULL COMBO with ECHOs, white once broken (§5.5); the preview's small score shows the same over its loop.
- APPROACH GLOW has no row (16:19); the glow stays on.

### 24.15 The dive and the result screen stay English; the preview's sky line (16:41–16:45)
16:41: 「然后游戏界面里的英文都不用改，重开，退出等保留，但是early late就不要改成早，晚了，然后combo就不要改成连击了，autoplay也不要改成自动演示了。」 16:43 (on the GAMEPLAY VISUALS preview, 设置12): 「你的上轨道呢？」 16:45: 「结算界面的英文也全部保留，游戏界面的重开退出也都保留英文吧。」
- The dive looks the same in both languages: `AUTOPLAY`, `COMBO`, the `EARLY` / `LATE` tags and the tutorial's `HOLD TO SKIP` are English in 中文 too, like the judgement words and the end banner (§24.2). The tutorial captions still follow the language.
- The pause menu is variant B as built before §24.2: an icon, the English label and the Chinese sublabel under it, in both languages (§5.12). Replaced at 17:22 by one language at a time (§24.16).
- The result screen is English in both languages, without the Chinese subtitles of §1's bilingual rule: the judgement rows, RESONANCE CHAIN, EARLY / LATE / AVG, ACCURACY / MAX COMBO, the buttons, the chart offset nudge and its sheet, and the unlock notice (`Depth`'s Chinese unlock labels went away with it). Since 17:22 the buttons, the sheet and the shell breakdown follow the language again (§24.16).
- The live preview (§9.3) draws both lines, the white sky line under the caption band and the cyan ground line; three of its six notes rise to the sky line, words and bursts sit at the line a note was hit on, and taps are judged against both touch zones as in a dive.

### 24.16 One language at a time on the pause menu and the result's controls; lighter result popovers (17:22–17:26)
17:22 (on 设置13, green boxes round the pause menu, the result's RETRY / CONTINUE and the chart offset sheet, a black box round the DIVE LOG line): 「绿框框起来的语言还是分开吧，然后点击贝壳查看的明细也分开，，并把"这次下潜获得的贝壳"这个小字删掉。黑框框起来的删了。」 17:26: 「分数点开后的那个弹窗把"分数"这个小字去掉，然后指标分别是"accuracy score"，"resonance chain"，"perfect resonance"，最下面total，中英文都用这一套。」
- The pause menu shows one language: RETRY / RESUME / QUIT in English, 重开 / 继续 / 退出 in 中文; the English labels lost their Chinese sublabel (§5.12).
- On the result screen RETRY (REPLAY) / CONTINUE (重开 / 再玩一次 / 继续), the chart offset sheet (谱面延时, the question 「把扬声器的谱面延时从 0 改成 +31 ms？」, 取消 / 校准 / 应用 and the APPLY toast) and the shell breakdown (基础, 首次下潜, 新纪录, 全连, 全共鸣, 今日首次下潜, 新手教程, 合计) follow the language. The rest of the screen stays English (§24.15).
- The shell breakdown has no title (SHELLS THIS DIVE / 这次下潜获得的贝壳 went away); its rows start under the card's top edge.
- No header line: `DIVE LOG · 01 SHORELINE` (and the tutorial's `TUTORIAL COMPLETE`) is gone; the meta line stays at the top right.
- The score breakdown has no title (`SCORE` went away) and reads `ACCURACY SCORE`, `RESONANCE CHAIN`, `PERFECT RESONANCE` and `TOTAL` in both languages (中文 names since 20:34, §24.17).

### 24.17 Preview rewritten, SHOW TOUCHES as rings, a full RESET RECORDS, 玩家 (19:52–21:56)
19:52: 「英语翻译需要改的（中文不改了）：hit sound volume: note volume (adjust the volume of note sound effects; pause type: pause input (choose how to pause); too early/ too late hint: early / late hint. 其他需要修改的点：1. 预览的Live Preview放在右下角，分数放在左上角。2. show touches还不起作用，触控点要明显一点。3. live preview里面我不理解为什么点击会在对应的轨道上出现粒子效果。我建议live preview就不要支持点击了，只看就行了。按键随机出现，每5~12combo断一次连击。4. "游玩界面视觉参数"改成"游戏界面"。把这个里面的"显示触控"移出来到"游玩"，放在暂停方式和震动之间，把多押辅助加进游玩界面视觉里面，出现双押后随机等待1~6次下落后再出先双押。5. 谱面延时下面的小字变成3行，把这一句话插入第一行：中文：调整音乐的同步性；英文：Adjust the synchronization of music。6. 现在的实时预览界面失真很严重，combo没有出现，然后判定区提示还在之前的那种0.1倍屏幕高度的旧版判定区。重写那一段的逻辑。7. "where the judgement word shows"改为"where to display the judgement word"。」 19:54: 「所有的这类形容游戏的"dive"都改成game」. 20:15: 「账号与统计里面的player还有很多潜水员相关的，都改为玩家」. 20:16: 「然后设置里面的清空记录是把所有记录重置，包括贝壳和所有进度。先要玩家长按，然后再点确认，然后再把用户名完整打一遍才能删除」. 20:17: 「主线底下的"演示 · 7章"不要了」. 20:18: 「Collab里面暂时不要填充曲绘，就一个和主线一样空的洞，然后装上那种网」. 20:34: 「点一下分数弹出的细则框还是分中英文。中文分别是"准度分"，"共鸣链"，"完美共鸣"，"总计"。然后点开下面的early/late柱状图出现的也是中文，early late不必那，peak改为"峰值"，mean改为平均。下面的重开/继续字体可以做大一点。」 20:45: 「transition的"谱面"改成"谱师"」. 20:56: 「然后稍微放宽一点长条的判定，长条判定改为可以提前0.25秒松手」. 21:37, on the preview sheet with the result's hint and auto-drop lines boxed: 「框起来的"You're Hitting Late"这个信息中文语言下也用中文」. 21:56, the two lines circled: 「黑色圈起来的居中吧」, 「就是居在那个柱状图0ms那里」.
- **English texts** (§9.2): NOTE VOLUME with `ADJUST THE VOLUME OF NOTE SOUND EFFECTS`; PAUSE INPUT (already the label) with `CHOOSE HOW TO PAUSE`; `EARLY / LATE HINT`; `WHERE TO DISPLAY THE JUDGEMENT WORD`. Descriptions that called a game a dive now say game (`THE BACKGROUND DURING A GAME`, `HIT EFFECTS DURING A GAME`, `NOTE SIZE DURING A GAME`, `A RING UNDER EACH FINGER DURING A GAME`); the Chinese stays as it was. Names that are the game's theme keep DIVE (DIVE IN, DIVE PROFILE, FIRST DIVE, the DIVE transition and the like).
- **CHART OFFSET** has three description lines, `ADJUST THE SYNCHRONIZATION OF MUSIC` / `调整音乐的同步性` first (§9.2).
- **游戏界面**: the GAMEPLAY VISUALS screen's Chinese title (游玩界面视觉参数 before). SHOW TOUCHES left it for GAMEPLAY, between PAUSE INPUT and HAPTICS; MULTI-PRESS ASSIST came in as the right column's second row (§9.2).
- **Live preview** rewritten on the game's own engine and renderers (`gameplay/PreviewRun`, §9.3): it plays a random chart by itself with doubles 1–6 drops apart and a combo break every 5–12, shows COMBO and the zone hint over the real ±0.4 zone, puts the score top-left and the caption bottom-right, and takes no taps (the footnote lost its TAP THE PREVIEW TO TRY A HIT).
- **Zone hint** in a game: α .07 over the ±0.4 zone (§5.7); α .035 had been tuned for the ±0.1 band and left no visible tint once the zone widened.
- **SHOW TOUCHES** is a ring under every finger in a game (§5.10, `gameplay/TouchMarks`); the stray-touch dot is the tutorial's only.
- **RESET RECORDS** (§9.2, `screens/shell/ResetDialog`): holding the button 2 s opens a confirmation with the diver's sigil, name, shells and depth, what goes (`ALL PROGRESS WILL BE ERASED:` / `这位玩家的全部进度都会清空：`, `SCORES, DEPTH, GAME LOG, SHELLS AND BOUGHT SONGS.` / `成绩、深度、下潜记录、贝壳和买下的曲目。`) and `THIS CANNOT BE UNDONE.` / `清空后无法恢复。` in red, with CANCEL and CONFIRM. CONFIRM swaps the warning for a name field (`TYPE THE DIVER NAME IN FULL TO RESET` / `把玩家名完整输入一遍才能重置`); RESET is enabled only while the text is the name exactly (spaces at the ends aside). A tap outside, BACK or CANCEL closes it without changes. `ProfileStore.resetRecords` now takes the diver back to a new player's progress: chart records, depth and its history, the game log, statistics and play count, shells, bought singles, seen chapters, read stories (`story:`) and revealed secrets (`reveal:`), the tutorial (seen, and its first-time shells), chapter mode, and the sigil options that need depth (pattern, palette, glyph and frame fall back to defaults; the shape stays). The name, settings, calibration and the reminder flags stay. Until then it kept shells, bought songs, play count and play time.
- **玩家**: every Chinese text that said 潜水员 says 玩家 (the PLAYER tab's cards and counts, 新建玩家, import, rename, the switcher, the first run and welcome, settings descriptions). The English keeps DIVER.
- **Hub**: MAIN without its sub-line; COLLAB an empty sealed shaft with the grate (§16.5).
- **Result in 中文**: the score breakdown reads 准度分 / 共鸣链 / 完美共鸣 / 总计 and the expanded trace `峰值 n  ·  平均 +31 ms` (its EARLY / LATE labels stay English, §6.5, §18.2); RETRY / CONTINUE (重开 / 继续) are drawn at cap 34 instead of 26 in 中文 only.
- **Result lines in 中文** (21:37): the offset hint reads `你打得偏晚（+31 ms） · 调整` (偏早 when early) and the auto-drop notice `为了更流畅，粒子质量已自动调低`; the strip note of the same kind follows too: `下一步 · 在地图上选一首歌` after the tutorial, `AUTOPLAY · 不记录成绩` after autoplay (`ResultModel.hintText`, `fxNoticeText()`, `stripNote`). English unchanged. Since 21:56 both lines are centred on the trace's 0-ms line (the middle of the trace; on the stacked layout under the tiles until 2026-10-19), the notice's dot moving with its text (`ResultScreen.drawHint`). The notice was removed on 2026-10-19 02:24 (§24.18).
- **Credits**: the transition's chart credit reads 谱师 in 中文 (谱面 before).
- **Holds** (20:56): releasing a STREAM up to 0.25 s before its end already completes it (`Params.HOLD_RELEASE_GRACE_MS` = 250 since 2026-10-12; a release earlier than that breaks it). Asked whether to widen it further or the 100 ms a finger may leave its lane, Kelvin chose to keep it as it is (21:20). No change.

### 24.18 The 4:3 result screen laid out again from Kelvin's marks (2026-10-19 01:42)
01:42, with colour marks on the result pages of the 4:3 gallery (PDF pages 80–81): 「粗略过了一遍，问题特别多。着重看80和81页结算界面上我做的标记，每一个颜色对应某一个物件应该被移动到哪里。我要你先把结算界面的内容完成修改，然后只截结算界面的图给我看。」 Kelvin's note on page 81: 「未来白色区域会有人物角色」.
- **Stacked layout** (§6.2): the score moves up between the jacket and the stamp; the clear badge lies across the foot of the stamp's ring (on an opaque backing, drawn after the stamp); the title block goes under the jacket with the tiles beside it; the judgement rows, the early/late line and the trace move down, with the offset hint and the auto-drop notice under the trace (§6.5); the strip keeps to the left half (its depth line shrinks to fit, never below 0.7) and RETRY / CONTINUE go to the bottom right. Everything under the top row stays left of the screen's middle, so the lower right is empty for the character Kelvin plans there.
- Kelvin's yellow mark put the tiles where the score had been (x ≈ 490–1480); they stop at the middle (x 436–960) because the right part of that band lies in the white area Kelvin marked.
- Only the stacked layout (visible height ≥ 1,360) changed.
- **02:24**, on the new 4:3 shot: 「黑色：删掉，然后整个左半侧稍微再拉长一点，间距宽一点。蓝色：想办法把clear/full combo/all resonance和new best都放在分数下面那一片去。」
  - Black (the auto-drop notice): removed on every layout, phones too. The drop itself stays (§5.9); Settings still shows it in the particle quality description (§9.2).
  - The left half: the top row moves up 16 (jacket 860–1160) and everything under it follows; the rows spread wider (gaps 80 / 70, or 70 / 60 / 60 with MINES; were 70 / 58 and 62 / 50 / 50) and the trace grows (130, or 100 with MINES; was 100 / 84); the offset hint takes the notice's place, nearer the strip.
  - Blue: the clear badge leaves the stamp for the place under the NEW BEST pill, both under the score (§6.2), without its opaque backing; after the tutorial, which has no best line, it sits in the best line's place.
- **02:40**: 「没有水雷也表0/0。蓝色圈起来的部分偏低了一点」 (blue: the bottom of the tapped trace, its tick labels).
  - The MINES row shows on every result, `0 / 0` on a chart without mines (§5.15), on phones too; the three-row layouts are gone.
  - The tapped trace's card (§6.5) ends 60 above the strip instead of 40 and grows 30 up over the early/late line it hides; its tick labels sit 12 inside the card's bottom edge (they hung 2 below it, on phones too).

### 24.19 One result layout from the 16:10 blueprint; the HUD in two corners (Kelvin 2026-10-19 03:16–06:28)
> Round 8 (15:2x, §24.25) moves the chip into the jacket's bottom-right corner and the stamp, best line and score to the jacket's bottom left, drops the clear badge, and gives screens wider than 16:8.5 three columns. The table below is round 7.

Asks, in order: 03:16 / 03:19 on the phone layout (the stamp beside the score, no ACCURACY / MAX COMBO tiles, so accuracy is no longer shown, MAX COMBO beside RESONANCE CHAIN); 03:47 「先改一版成熟的16: 10作为蓝本，然后所有剩下的都通过16：10进行拉伸/压缩高度即可」; then, on the mock-ups: titles up to 50 characters in full on one line, centred from the start (04:55, 04:58, 05:12 「曲绘一致用16：9的长方形，然后我要不论长短曲名都要歌名一开始就居中」), a larger jacket and less on screen (05:25 「是不是要适当减少一点显示的信息啊…别人的界面感觉就很整洁」), 05:58 「把A的深色标题外框加在b上」, 06:04 「如果说average太高了就在early late avg下面显示建议调整。重开/继续稍微小一点。然后主界面的depth，用户名和贝壳也这样子分开到屏幕左上角和右上角」 (「就是地图界面上也这样分开到两个角落」), and 06:28 「好的先这样。把原来的那种贝壳和profile的小的边界加上」.

**One layout** (`ResultLayout`) replaces Three-column and Stacked (§6.2). Blueprint 1920 × 1200, world y up, safe edges 24 in:

| Element | Position and size | Style |
|---|---|---|
| Player frame | x 24, y 1076–1160 (84 high), as wide as its text + 22 | `draw.panel` (PANEL α .78, 1 px white α .14, radius 18, top highlight); avatar 60 at x 36; name UI_BOLD 22, baseline 1124, from x 112 (shrinks past 420); `DEPTH` UI_LABEL 13 `ink2` + `-2,698 m` NUMBER 17, baseline 1088, then `▼ 212` (metres deeper, NUMBER 17, `resonance`). Tap → depth breakdown. |
| Shell capsule | right x 1896, centre y 1118, 44 high | `gold` α .16 pill on a deep gold fill (#443A2B α .88, `Theme.SHELL_CAPSULE`, since 2026-10-19 13:59, §24.23); shell icon 28, count NUMBER 22 `gold`, then `+151` NUMBER 17 `ink1`. Tap → shells breakdown. |
| Title band | full width, y 900–1060 | fill (8, 13, 38) α .43, a 1 px line (120, 200, 255) α .27 at each edge; title UI_BOLD 48 centred on x 960, baseline 988, shrinks only past 1,728 wide; a title of up to 50 characters always shows whole, a longer one keeps its size and scrolls (04:55); artist UI 24 `ink2`, baseline 932; chip x 96, y 920–968 (UI_BOLD 22, pad 22, superscript − / +) |
| Jacket | x 96–1136, y 279–864 (1040 × 585, 16:9, centre crop) | radius 18, 1 px stroke; a soft glow of its own colours behind it (never above the band); its lower 58 % darkens to (6, 10, 32), 86 % at the bottom |
| Over the jacket | stamp ring r 84 at (234, 407); score NUMBER 66 from x 366, baseline 337 (dim leading 0); best line pill 30 high from x 366, top 453; clear badge 168 × 40 at x 932–1100, y 418–458 | badge fill (32, 41, 98) α .92, UI 18 |
| Under the jacket | RESONANCE CHAIN / MAX COMBO x 96–600 (line y 193, bars 16, labels baselines 219 / 143); early / late line baseline 97; offset hint baseline 53 | as before; the hint keeps its rule (§6.5) |
| Counts | labels x 676, values right-aligned at 1036; RESONANCE 219, PERFECT n 191 (UI 14), ECHO 153, LOST 111, MINES 69 | MINES always shown, `0 / 0` in `ink3` when nothing was touched (02:40 「没有水雷也表0/0」 overrides the mock-up's note), LOST colour when one was |
| Buttons | 96 high at the bottom right: CONTINUE 360 wide ending at 1896, RETRY 326 wide, gap 28 | |
| Character area | x 1176–1896, y 148–864 | empty (the AUTO watermark is fitted into it after autoplay) |

- **Other aspects**: shorter than 16:10 (16:9, phones) scale everything by safe height / 1,152; taller ones (4:3) keep the sizes and put half the extra height above the jacket and half under it. The left column keeps to the safe left edge, the capsule, buttons and character area to the right one, the title to the middle; extra width goes to the character area.
- **Taken off the screen, shown on tap** (popover cards, opaque since text runs under them, `Panel.cardSolid`): the player frame → depth before → after, the chart's depth and its DST 10 place; the EARLY / LATE / AVG line → the echo trace card (its sweep runs when it opens, then every 4 s; dropped at 17:48, §24.25); the shell capsule → the shells breakdown; PERFECT n → its early / late split, sliding out to the right; the score → the score breakdown. The strip, the CHART DEPTH line, the inline trace, the bars after the counts and MINES' TOUCHED are gone; the jacket no longer tilts on a tap.
- **Timing** (§6.3): the frame and the capsule drop in from 40 above at the strip time (3.00 s); the depth counts in the frame (3.20–4.00) and `▼ n` pops after it; the shells count in the capsule (3.40–4.30) and `+n` pops. The offset hint fades in 0.3 s after the early / late line; after autoplay (`AUTOPLAY · NOT RECORDED`) and the tutorial (`NEXT · PICK A SONG ON THE MAP`) the hint's place shows the strip note.
- **The map** (§3, `MapHud`): the profile badge splits the same way. The player frame (avatar, name, `DEPTH -2,698 m`, no gain) is at the top left, the shell capsule 24 left of Settings, or of the story / chapter toggle when the hub shows it; both open the Profile (the capsule not since 2026-10-20 03:38; both larger since 03:26, §24.29). The ALL SONGS grid still starts under a 120 band.
- **Song card at 16:10** (03:47, §4): the phone's right-side card stays on tablets, taller; its header-to-chips and chips-to-sparkline gaps take 96 of every 120 units of extra height. No bottom sheet any more. (Its content was laid out again in round 5, §24.20.)

### 24.20 The song card, round 5; titles that scroll (Kelvin 2026-10-19 06:41–07:01)
Asks, on mock-ups of the 16:10 card: 06:41 「best去掉。分数挪到左边，等级挪到右边，稍微放大一点。depth放在分数下面，plays也放在分数下面。」, 07:00 「图标放圆圈这么大。depth/plays稍微往下挪一点，选歌难度往下挪，然后next那一行也往下挪，现在太挤了点」, 07:01 「然后这里最多显示30个字符，太长了就滚动显示，滚动到最后以后停顿2秒，然后空一段距离再接着播放，循环播放。」 (with 04:55: the result screen shows titles of up to 50 characters whole, the song card up to 30; 04:58 one line only; 05:12 jackets 16:9).

- **Layout**: §4.2 (positions) and §4.3 (other screens). Compared with the card of §24.19: a 16:9 jacket across the content width with a stroke and a glow, a full-width title band like the result screen's, the score at the left without BEST, DEPTH and PLAYS under it, the grade letter as large as its ring at the right, the chips, the micro-goal row and the action row lower (the action row stands on SB, 24 above the screen's bottom like the result screen's buttons). Accuracy and the ACC line are no longer on the card.
- **Not on the mock-ups, chosen in code** (the ribbon and the sparkline went on 2026-10-19, §24.21): FULL COMBO / RESONATED is a ribbon across the ring's foot; an unplayed chart shows `NOT YET DIVED` and `FIRST DIVE · +40 SHELLS` where the score and DEPTH row would be; with AUTO on, `AUTOPLAY · NOT RECORDED` takes the micro-goal's row; the small bars left of NEXT are the recent sparkline (one bar per recent play, §4.6), not a missing glyph.
- **Scrolling titles** (`ui/Marquee`, shared by the card and the result screen): rest 1 s with the start at the window's left edge; scroll left at 80 / s until the end reaches the right edge; rest 2 s; keep scrolling: the end leaves, a gap of 160 follows and the next copy comes in, passing the left edge without a stop, up to its end and the next 2 s rest; loop. The window fades over up to 24 at each edge, less as a copy's start nears the left edge or its end the right one, so the first letter is crisp while the title rests at its start and the last one while it rests at its end. A title that is past the limit but fits (a card title of 31 characters at 29) rests with its start at the left edge instead. Card: the window is the content (x 1260–1880 on 16:10), the clock restarts when the song changes but not on a difficulty change. Result screen: past 50 characters, window x 96–1824, the clock starts with the title's fade-in.
- **Not changed**: the header, the chip sizes, DESCEND and AUTO, the map HUD (the mock-ups' combined player box is the older HUD; §24.19's two corners stay).

### 24.21 A full combo paints the grade purple; no ribbon and no recent bars on the card (Kelvin 2026-10-19 13:09)
「修改几点：1. 选歌界面的full combo/resonanted去掉。如果一首歌full combo了那么它对应的等级直接显示紫色。比如说第一遍是full combo A，那么结算界面和以后选歌界面就显示紫色的A。如果后面打了不是full combo的S，则结算界面还是金色的S但是以后选歌界面变成紫色的S。2. 最近6次成绩就不要再显示了，太挤了。」

- **Select screen** (the card's ring and letter; the grade pill and letter under a hole at close zoom): the chart's best grade is drawn in `FULL_COMBO` purple #B49CFF once the chart has a full combo (`ChartRecord.fullCombo`, sticky, so a later best without one stays purple). R and R+ need ALL RESONANCE and stay light blue. The FULL COMBO / RESONATED ribbon across the ring's foot is gone (`Theme.gradeColor(int, boolean, Color)`).
- **Result screen**: the stamp (letter, rings, impact rings, sparks and the sonar ping) is purple when this play is a full combo (`ResultModel.fullCombo`), otherwise the grade colour. The FULL COMBO / ALL RESONANCE badge stayed until round 8 (§24.25).
- **Recent sparkline** removed from the card; the micro-goal row stays where it was (right-aligned at x 1880, 984–1024).
- **Not changed**: the RECENT tab's FC / AR tags and grade pills, the STATS tiles, the ALL SONGS filter chips.

### 24.22 The card header: no ×, ‹ and › at its two ends, a little larger (Kelvin 2026-10-19 13:37)
「选歌卡片右上角的叉不要了，把左侧/右侧调节放在两边，然后这两个箭头和"singles · 单曲"这个标题可以稍微大一点」

- ‹ sits flush with the jacket's left edge and › with its right edge; the level's name is centred between them.
- The arrows grow from 64 to 76 and the name from 20 to 26. The header row's half height becomes 38 and the gap under it 39 (they were 32 and 45), so the jacket and everything below it stay where they were.
- Without the ×, the card still closes by a tap on the map, a swipe or fling to the right, or Back (Esc on a computer).

### 24.23 A deeper shell capsule (Kelvin 2026-10-19 13:59)
「贝壳数的那个光晕加深一点盖过单曲」

- With the card open on 16:10 the map's shell capsule moves left onto the level's big title (`单曲` under `SINGLES`), which showed through its `gold` α .16 wash. The wash now lies on a deep gold fill (#443A2B α .88, `Theme.SHELL_CAPSULE`), so nothing behind the capsule shows through; the shell icon, the count and the hit area are unchanged.
- The result screen's capsule (§24.19) gets the same fill, so the two stay alike.

### 24.24 ALL SONGS: a pack box, five or four to a row, the card always open; map titles of 15 / 20 characters (Kelvin 2026-10-19 12:40–14:01)
Asked over five rounds of mock-ups: 12:40 the card always open on the right, five songs to a row, no free zoom, two pinch-outs within 1 s rise; 13:14 the packs as multi-select toggles, all on by default, the checked packs shown flat, SORT without its pack grouping; 13:15 no headings; 13:19 a dark frame around the packs; 13:26 「改成多行…先预留6个的位置（现在只放3个，“全部”站最左侧两行（一个1*2的区域），然后单曲/第一章）…下面的歌曲能显示两行，可以放大到每行4个或者缩小到每行5个，没有别的选项。每行四个就显示分数等级信息。」; 13:40 「然后每排4个的状态标题最多20个字符，5个的最多15个。在其他的地图中也是这样。放到最大的话在地图内最多显示20个字符，后面的滚动（选歌卡片不变）。」; 14:00 「每行5个也要循环滚动」; 14:01 CHAPTER 1 right of SINGLES, 「就可以交给写代码那边处理了」.

**The grid and its gestures below gave way on 2026-10-19 21:18 to §24.27** (two to a row, staggered, at the maps' default size, linked into a net, no zoom); the card, the bottom row and the titles stand, and the pack box went on 2026-10-20 (§24.28).

Blueprint 1920 × 1200, **y measured down from the top**; shorter screens shrink the page with their height (never grow), x follows the room left of the card (`map/GridPage.layout`).

- **Pack box** (gone since 2026-10-20, §24.28): x 24–1194, y 146–348, corner 18, fill #0A1033 α 205/255, white stroke α 40 width 1, a white α 20 hairline inside its top edge (18 in at each end). Inside, padding 16, gaps 12 across and 10 down, cells 80 tall with corner 12. `ALL` / `全部` takes the first column over both rows (x 40–254, y 162–332): solid cyan with ink #010E1F when every pack is on (a tap turns every pack on), else drawn like an unchecked cell. Pack cells: three columns of 296 (x 266–562, 574–870, 882–1178) filled row by row in data order (SINGLES, then CHAPTER 1). Checked: fill #152B4B α 215, cyan stroke α 200 width 2, the name in cyan at x + 24 (Noto Sans SC Medium / Chakra Petch SemiBold 27), a cyan dot r 7 at x + 296 − 28. Unchecked: fill #0A1033 α 120, `ink3` stroke α 140, the name in `ink3`, an `ink3` ring r 7. Several may be on; the last one on cannot be turned off (it shakes). The choice is kept in `Profile.gridPacksOff`. With more than six packs more rows follow and the cells drag up and down inside the box (ALL stays), fading over the box's bottom 24 while more lie below.
- **Grid**: the songs of the checked packs, each once, flat, in the SORT order; two rows on screen.

  | Level | Node scale | Column centres x | Row centres y | Name baseline | Chip top |
  |---|---|---|---|---|---|
  | Five to a row (default) | 1.0 (r 68.8, the old grid's zoom-1.25 size) | 177 + 216 i | 449, 724 | centre + 103 | baseline + 15 |
  | Four to a row | 1.25 | 204 + 270 i | 464, 800 | centre + 124 | baseline + 15 |

  Only the nodes scale; names (Chakra Petch SemiBold 25), chips (72 × 36) and prices stay the same size. Four to a row adds a score row, baseline at the name's + 85, centred on the column: the best score's 9 digits (SemiBold 22, `ink1`, leading zeros `ink1` α 60/255), a gap of 10, then the grade pill (28 tall from 24 over the baseline to 4 under, padding 13, `Theme.gradeColor` with full combo purple, fill α 50, stroke α 210 width 2), or a cyan `NEW` pill alone when unplayed; songs for sale keep their price and get no score row. The selected song's dashed outer ring becomes a solid cyan ring at the outer radius + 1 (84 / 105), width 3, α 240, with a soft glow; no leader line. The grid is clipped at the box's bottom (348) and fades out from y 1006 to 1066, above the lens row (a mask shader on what the grid draws).
- **Gestures**: pinch apart switches to four, together to five (`MapScreen.updateGridPinch`, a ratio of 1.12); at five, closing by 1.08 counted as a zoom-out and two within 1 s rose to the hub (until 2026-10-19 18:46; now two fingers held still rise, §24.26). The wheel: up four, down five (at five one continuous scroll counted as one pinch until §24.26). `+` / `−` switch too. A switch keeps the selected song (or the first one under the box) in the upper or lower row it was nearer and glides every song to its new place over 0.25 s; the level is kept in `Profile.gridFour`. With more than two rows the grid drags up and down with inertia and a rubber band; a scroll bar x 1177–1183 on a track y 370–1062 (white α 26, thumb `ink2` α 170, as long as the track × 2 / rows).
- **Card**: always open (`SongCard.pinned`), on the last played song when the page opens, else the first one; a tap on a song switches it, ‹ › step through the grid order and loop; Back rises to the hub. A lens the selected song lacks moves the card to the first song in the grid that has it.
- **Bottom**: the lens row as before; SORT at x 1016–1194, y 1096–1176; its panel keeps ORDER and FILTER only and opens above it (x 458–1194, y 569–1080).
- **Titles on every map** (the card's 30 and the result's 50 unchanged): characters count in half widths, a full-width (CJK) one as 2. Five to a row, and every other map away from its closest zoom: up to 15; four to a row, and the closest zoom (`MapStyle.LOD_DETAIL`, scores shown): up to 20. A title within the limit is centred; a longer one loops inside a window as wide as its first 15 / 20 characters, on the card's `Marquee` timing at the 25 px name's size: 50 / s, a gap of 100, edge fades of 16. Each title keeps its own clock; a level switch, a lens change and a pack change restart them.
- **Chosen in code**: the overview chip moved into the top row between the player frame and the shell capsule on this page, clear of the box (gone since §24.26); a save with no pack on shows every song.
- **Micro-goal centred** (16:36, Kelvin's OK on the six-aspect shots: 「可以，先这样。MAXED和推分建议移到同一水平线居中位置即可」): the card's NEXT / MAX goal and MAXED keep their row above the action row but are centred on the content (`SongCard.goalRight`), on every map's card; `AUTOPLAY · NOT RECORDED` keeps its place.

### 24.25 Result screen, round 8: no clear badge, the chip in the jacket's corner, three columns on phones (Kelvin 2026-10-19 14:15–17:48)
Asked on mock-ups (notes 结算第八版排版参数): 14:15 「你看一下别的游戏的这种手机端的截图，稍微重拍一下20：9的andriod手机结算界面屏幕比例」 (three columns chosen from two drafts); 14:38 「能不能把分数和等级移动到这里？我想尽可能让手机和平板的排版差别不要那么大」; 14:43 「绿色框垂直拉伸一点到蓝色框的位置。结算界面的“clear/full combo/all resonance”也去掉吧」; 14:59 「…顺便把shallow10-这类等级信息移动到曲绘的右下角」; 15:16 「算了，平板还是保留原来那种，只是把等级信息移动到曲绘右下角」; 15:17 「改完后交给写代码那边处理」; 15:24 「调整一下吧，把蓝框的移动带绿框」 (the stamp, best line and score as one group into the jacket's bottom left, left of the chip). On the real screenshots: 17:21 「18：9中间那一栏宽了，窄一点。其他的都还行」; 17:22 「划分点是16：8.5，小于8.5就用手机排版，大于等于8.5就用平板」; 17:48 「早晚的那个柱状图不要了，延伸到人物那边去了。16：8.5和更窄的屏幕中较宽的一批（16：10以下），改成像我之前展示的那个arcaea的截图一样，两边留出空间，中间适当留一点空间但不要多。你要确保的是20：9的角色区域比例和16：10的角色区域比例要一样」.

Coordinates are **y measured down from the top**, as in the notes.

- **Every aspect**: no CLEAR / FULL COMBO / ALL RESONANCE badge (a full combo shows in the stamp's purple, §24.21; ALL RESONANCE in the R / R+ grade). The difficulty chip leaves the title band for the jacket's bottom-right corner, 16 in from its right and bottom edges, the same look and size (48 high, UI_BOLD 22, pad 22), over a soft shadow ((0, 0, 12) α .47 blurred by 6 and 3 down; six stacked round rects, `JacketBlock.drawChip`); it pops at 0.60 s, when the jacket has landed (`Choreo.CHIP_T`; it popped with the artist before). The band keeps the title and the artist, centred; the artist no longer leaves room for the chip. After autoplay the AUTOPLAY pill (round 7's badge look: fill (32, 41, 98) α .92 tinted `auto` α .10, `auto` stroke and text, the expand, decode and shine at 2.50 s) takes the best line's place, its left on the best line's x and its bottom on the best line's bottom; the grey `BEST n` line autoplay showed there is gone. Unchanged: the player frame, the shell capsule, the band, RETRY / CONTINUE and the end banner. The echo trace card is gone (17:48): the EARLY / LATE / AVG line opens nothing (`EchoTrace`, `ResultModel.histogram` and `Choreo.TRACE_*` removed). The character area has the blueprint's shape on every aspect, 720 : 716 (`ResultLayout.CHAR_ASPECT`; 4:3 still adds half its extra height to it).
- **The blueprint** (16:8.5 and taller: 16:10; 16:9 shrinks it and 4:3 splits its extra height as in §24.19): the jacket and everything under it as round 7. The chip is x 877–1120 (its width follows the text), y 857–905, on the jacket x 96–1136, y 336–921. The stamp, the best line and the score move to the jacket's bottom left, smaller: stamp ring r 66 (was 84) centred (190, 830); NEW BEST pill 27 high (was 30) from x 296, top 789; score NUMBER 60 (was 66) from x 296, baseline 892 (the chip text's), its dim leading zero as before; the AUTOPLAY pill from x 296 with its bottom at 816. The jacket's shade stays. Wider than 16:10 (16:9, 16:8.5; 17:48, like Kelvin's Arcaea screenshot) the width past the scaled blueprint's goes 40 % to each side and 20 % between the jacket and the character area (`ResultLayout.SIDE_SHARE`): the jacket, the rows under it, the character area and the buttons move in, the player frame, the capsule and the band stay at the safe edges. 16:9 (1920 × 1080): 78 a side, the gap 75 (was 231); 16:8.5 (2032 × 1080): 123 a side, the gap 97 (was 343).
- **Wider than 16:8.5** (20:9, 19.5:9, 18:9; Kelvin 17:22 「划分点是16：8.5，小于8.5就用手机排版，大于等于8.5就用平板」, the split was 2:1 before; `ResultLayout.columns`): three columns on the notes' 2400 × 1080 frame, scaled by safe height / 1,032; the corners, the band and the buttons stay as on the blueprint (scaled by safe height / 1,152).

  | Element | Position and size |
  |---|---|
  | Jacket | x 88–1018, y 304–826 (930 × 522), whole: no shade; the 1-unit stroke (140, 200, 255) α .31 and the glow as round 7 |
  | Chip | its bottom-right corner, 14 in (218 × 43 at 20:9) |
  | Best line | NEW BEST 36 high from x 88, top 856 (the jacket's bottom + 30); the AUTOPLAY pill in the same place; with no new best the row shows the grey `BEST n` line (FIRST DIVE on a first play) as on tablets, and the score does not move up |
  | Score | NUMBER 70 from x 88, baseline 980 |
  | Stamp | ring r 82 centred (923, 945): the jacket's right − 95, the score's middle |
  | Middle column x 1090–1650 | RESONANCE CHAIN / MAX COMBO × 1.15 (line y 418, MAX COMBO baseline 475.5, the note count UI 16.1); EARLY / LATE / AVG × 1.1, baseline 539.5; the offset hint UI 18.7, baseline 585.5, its rule 9 under it; a divider at y 641.5, white α 40, 1.2 wide, across the column; the counts × 1.25 (labels 21.3 from x 1090, values 32.5 right-aligned at 1650, PERFECT 17.5): RESONANCE 723.5, PERFECT 758.5, then ECHO 832.3, LOST 906.2 and MINES 980 (the score's baseline) evenly |
  | Character area | y 304–945, as wide as the blueprint's shape at that height: x 1731.5–2376 at 20:9 (644.5 wide; it was 1710–2376, a little flatter than 16:10's); the buttons under it |

  Narrower than 20:9 (Kelvin 17:21 「18：9中间那一栏宽了，窄一点」) the left column stays and the middle column, its gap and the character area share the width right of x 1090 as at 20:9 (560 : 81.5 : 644.5), the character area's height shrinking with its width from its top: at 18:9 (2160 wide) the middle column is 1090–1545.5 and the character area x 1611.8–2136, y 304–825; just past 16:8.5 the middle column is about 400 wide. Wider than 20:9 the width past the frame's goes 40 % to each side and 20 % between the middle column and the character area, as on 16:9. The text keeps its size; an early / late line wider than the middle column shrinks to fit it (`ResultLayout.earlyLateMaxW`).
- **Chosen in code**: narrower than 20:9 the buttons narrow with the character area (524 wide at 18:9; their height stays), or RETRY would cover MINES' value; at 20:9 they keep their size. With three columns the score breakdown has no room under the score and opens above it, its pointer down. The stamp letter's fit scales with the ring (round 7's 2 × 84 − 44 at r 84), so a wide letter such as SS+ keeps its size relative to the smaller ring; the tutorial seal's COMPLETE (18 at r 84) scales the same way and stays inside the ring. The mock-ups leave open what the best row shows without a new best; the columns keep the grey BEST line (or FIRST DIVE) like the blueprint.

### 24.26 Map: the closest zoom on arrival, a two-finger hold rises, the maps re-laid, faint titles (Kelvin 2026-10-19 18:46–20:50)
Asked with a 20:9 SINGLES screenshot at the closest zoom, 18:46: 「我要初始大小就有这么大，也就是默认最大状态。选歌界面图标也全部大一点，至少要达到这个大小。然后上浮规则修改：双指静止不动长按1.5秒上浮，这样缩到最小也不用出现"全图"这个小的弹窗了」 and 「所以你也要重新设计地图」; on the first mock-ups, 19:59: 「"上浮"和rise根据中英文选择。然后主线的标题字太大了点，做成半透明的，这种标题都做成半透明的，不要抢镜，把乐曲都放中间一点，特别是16：10现在的主线怎么只能看到一首歌呢。」; on the second, 20:50: 「可以先推到我电脑上了，我再看看有哪些要改的」.

- **Opening zoom**: every level but the hub (which does not zoom) opens at `MapStyle.ZOOM_MIN` 0.7, the closest zoom (`MapScreen.startCamera`; it was 1). The opening screen shows 1,344 × 840 of the map at 16:10, 1,680 × 756 at 20:9 and 1,344 × 1,008 at 4:3.
- **Rise hold** (`MapGestures`; `MapStyle.RISE_HOLD` 1.5 s, `RISE_RING` 0.3 s): two fingers that both stay within 2 × `Ui.slop()` of where they were when the second one landed rise one level after 1.5 s (`MapScreen.mapRiseHold` → `rise`). A finger moving further cancels it for that touch, and the pinch goes on as before. From 0.3 s a ring shows between the fingers (`MapHud.setRiseHold`): a `panel` disc of radius 90 × k at α .88, a 10-wide track (`ink1` α .16) at radius 70, a `groundSig` arc filling clockwise from the top over 0.3–1.5 s, a chevron 14 above the centre and `RISE` / `上浮` by the language (UI 18, 26 below the centre); it fades in over its first eighth. No ring and no rise where nothing rises (`MapScreen.riseTarget`: the hub, a song card open outside ALL SONGS, a transition or a dive running). BACK, Esc and PAGE UP still rise.
- **Gone**: the overview chip (`全图 OVERVIEW · 再捏合 ↑ 上浮`), the double-pinch rise and the wheel's rise at the overview (the map still gives a little there, `MapCamera.bump`); on ALL SONGS the pinch and the wheel only switch five / four (§24.24; since §24.27 they do not zoom there at all).
- **Bigger icons**: a story hole's default radius 76 → 96 (`CaveData`), as wide as a song hole's ring; the hub's SINGLES and COLLAB 140 → 170, at (430, 560) and (1490, 560).
- **Faint titles** (`MapRenderer`): the level title UI_BOLD 34 (was 54) at `titleY + 10` and its Chinese UI 20 (was 30, `ink2`) at `titleY − 24`, both `ink1` at α `TITLE_ALPHA` .45 (was .85). Area names UI_BOLD 44 / UI 26 / UI 18 (were 64 / 34 / 22) at α .2 close up, .45 at the overview (was .8).
- **Layouts** (map coordinates, y down): songs in two staggered rows at y −120 and 110, 500 apart in a row and offset 250 between the rows, the title above them at y −318, so the opening screen holds both rows whole at 16:10, 20:9 and 4:3, clear of the HUD corners.
  - SINGLES: one zigzag chain ICS4U, Song 1–6, marsh mally (x −970..1030); Song 1–5 on the opening screen.
  - LAUNCH POINT (its title not drawn since §24.28): the chapters 01 (−450, 10), 02 (0, 50), 03 (450, 90) and on rightwards; the side entrances at (−1000, −230) and (−1000, 300).
  - 01 SHORELINE: story 01, Song 1–3 and story 02 in the rows; the shaft to 02 at (900, −20); ECHO VALLEY up and right (y ≈ −760), THE WRECK down and right (y ≈ 540), their area names outside them.
  - 02 TWILIGHT: Song 4, story 04, Song 6 and ICS4U in the rows; the shaft to 03 at (720, −20).
- **Songs placed nowhere** (`MapWorld.addUnplaced`): the free points of the hex lattice through the level's first song, pitch 500 and rows 230 (`COLLECT_PITCH`, `COLLECT_ROW`), nearest the start first (vertical distance × 4/3, ties counter-clockwise from the right), at least 300 (`COLLECT_FREE`) from every spot, the title and each other. Aleph-0 lands under the two rows at (30, 340), the next ones at (−470, 340) and (530, 340).
- **Chosen in code**: a path's alpha is capped at 1 (a cleared path's 1.02 wrapped round when packed, so solid cleared paths drew nearly invisible); edge signs keep their whole pill on screen and fade out over the last 40 units before a hole or the label under it (`MapRenderer.holeClear`).

### 24.27 ALL SONGS as a net: two to a row, staggered, at the maps' default size, no zoom (Kelvin 2026-10-19 21:16–21:53)
Asked at 21:16, 「感觉非常好！全部歌曲的图标也用默认大小，那种大号一点的，然后一排改为3首曲目」 and 「先不支持缩小吧」, then at 21:18 with a screenshot of the singles map at its default zoom: 「等一下，改成这样：观察这个图片现在的这种一排两个错开的样式，我要你这样子排列所有的曲目，然后不支持缩小，每一行之间像网格一样用线连起来」; at 21:53, on the mock-ups: 「16：10的稍微小一点吧，平板会放大这个大小的，这里手机端观感可以但是平板太大了」. It replaces §24.24's grid and gestures; the card, the bottom row and the titles stay (the pack box went on 2026-10-20, §24.28).

HUD units on a phone (`map/GridPage`). A tablet, as on the result screen any screen no wider than 16 : 8.5 (16:10, 16:9, 4:3), draws the songs, their labels, the links and the spacing 0.8 as big (`GridPage.TABLET`, `size`); the page's edges and the box keep theirs.

- **Size**: a map's look at its default zoom 0.7 (`GridPage.LOOK` = 1 / 0.7): song r 154.3 (108 / 0.7; 123.4 on a tablet) with its rings, the dashes at 178.6; the name at cap 26 with its top 10 under the dashes, the chip (72 × 36) 15 under the name's baseline. The score row has §24.24's four-to-a-row size: 9 digits at cap 19.25, a gap of 12, the grade pill 35 tall (its top 30 over the baseline, padding 16; the NEW pill 30 wider than its text), its baseline 93 under the name's, so the pill sits 12 under the chip.
- **Places**: two songs to a row on four half steps, the even rows on half steps 1 and 3 and the odd ones on 0 and 2 (`GridPage.column`), as the singles map's two rows lie. The half step is the singles map's 357 (250 / 0.7) where the room left of the card allows, else as much as puts the outer rings 10 in from the page's left and 26 (× the box scale) in from its right (about 283 at 16:10 and 4:3, against a tablet's 286, which 16:9 has room for), centred in that room. Rows lie the singles map's 329 (230 / 0.7) apart (263 on a tablet), or further where a score row (131 either side of its song) would come within 4 of the next row's score rings, which no screen so far needs. So 20:9 has the singles map's spacing and a tablet nearly 0.8 of it. The first row's dashes sit 22 under the box (under the grid's top since §24.28).
- **Links** (`MapWorld`): every song to the songs a half step either side of it in the next row, in `ink1` and without arrows; solid when both ends are cleared, dashed otherwise, as a map's paths; straight, and as wide as a map's at 0.7. A link breaks off where it passes its upper song's name (the name's width and 12 either side, from 12 under its baseline to 8 over its cap), so a long name is never crossed (`MapRenderer.drawPaths`).
- **No zoom**: no five / four levels; pinches do nothing, the wheel scrolls (120 a notch), `+` / `−` do nothing. A two-finger hold still rises (§24.26). The grid drags as before and scrolls until the last row's labels clear the fade; the scroll bar's thumb is the band's share of the whole. Titles take the 20-character limit. `Profile.gridFour` is unused (kept so that older saves load).
- **Taps** take the nearest song whose dashes (+ 10) or labels (the score row's 131, + 20, either side, down to the score row) hold the point.

### 24.28 English shows no Chinese on the map; no LAUNCH POINT; ALL SONGS without the pack box (Kelvin 2026-10-20 01:34–01:49)
Asked at 01:34, 「main一点进去那个launch point字样就不要了」; at 01:35, 「然后如果语言选择是英语就不要出现中文。」; at 01:49, 「然后把全部歌曲上面选曲包的那个功能去掉吧，现在在那里很突兀而且曲包很少。」.

- **No LAUNCH POINT**: the chapter-mode MAIN layer (`assets/maps/main.json`, the chapters 01–03 in a row) draws no title: its `title` and `titleZh` are empty strings (an absent `title` would read as the id in capitals). `titleAt` stays, so the camera bounds do not change. Story mode's MAIN opens on 01 SHORELINE, whose title stays.
- **English shows no Chinese**: the map (`map` package) does not use `ui/Lang` and drew both languages whatever the setting; that was all the Chinese an English screen still showed, apart from the language picker. Now `MapScreen.zh` / `MapRenderer.zh` decide: Chinese draws as before, English draws no CJK character. Data's `…Zh` fields are skipped; mixed texts such as a sign's `SIDE · 支线` lose the parts of their ` · ` list that hold a CJK character (`MapWorld.latin`); strings in code read `zh ? "中文 ENGLISH" : "ENGLISH"`. Texts drawn every frame are built in `MapRenderer.refreshLabels` (`signText`, `areaSub`), which runs again when the language changes.

  | Place | Chinese (as before; English drew this too) | English now |
  |---|---|---|
  | Hub | `SINGLES` / `单曲`, `MAIN` / `主线`, `COLLAB` / `联动`; `ALL SONGS` over `所有曲目`; the mode toggle's `故事模式 · 点一下切换` / `章节模式 · 点一下切换` | the English names alone; `ALL SONGS` alone, centred; `TAP TO SWITCH` |
  | Levels | titles and chapter holes with their Chinese line (`浅滩`, `第三章`); area names with theirs (`回声谷`) and the sub `SIDE · 支线` under it | the English lines; the sub as `SIDE`, on the Chinese name's line (y − 22) |
  | Edge signs | `ECHO VALLEY 回声谷 · 支线` | `ECHO VALLEY` |
  | Fall flash | `01 SHORELINE  浅滩`, `第 1 层 · LEVEL 1` | `01 SHORELINE`, `LEVEL 1` |
  | Tips | `未开放 SEALED · 敬请期待 COMING SOON`, `敬请期待 · COMING SOON`, `未解锁 LOCKED · 先通关 X 才能打开`, `剧情待定 · STORY COMING SOON` | `SEALED · COMING SOON`, `COMING SOON`, `LOCKED · CLEAR X FIRST`, `STORY COMING SOON` |
  | Return | `首次通关 FIRST CLEAR · S`; the toast `新解锁 n 首 · n UNLOCKED` | `FIRST CLEAR · S`; `n UNLOCKED` |
  | Story card | `STORY 01 · 2 MIN · 剧情`, `标题待定  TITLE TBD`, `pagesZh`, `DONE · 完成` | `STORY 01 · 2 MIN`, `TITLE TBD`, `pages` (no fall-back to `pagesZh`; `……` without them), `DONE` |
  | Song card header | `SINGLES · 单曲`, `01 · SHORELINE · 浅滩` | `SINGLES`, `01 · SHORELINE` (`MapWorld.cardHeaderEn`) |
  | Sort panel | `排序 SORT`, `顺序 ORDER`, `筛选 FILTER`, `默认 DEFAULT` … `最近 RECENT` | `SORT`, `ORDER`, `FILTER`, `DEFAULT` … `RECENT` (relabelled by `TextChip.setLabel`) |

  The one exception is the language picker's `中文` in Settings, so that an English screen still shows the way back. Player names and song titles show as written.
- **ALL SONGS without the pack box**: the box (ALL and the pack cells, §24.24) is gone with its taps, drag and saved choice (`GridPage.Listener`, `MapWorld.GridPack` and `packs` removed). The grid shows the songs of every pack in `all.json` (hidden secret packs left out), each once, in the SORT order. Its top is where the box's top was (blueprint y 146, 24 under the player frame, `GridPage.gridTop`): the first row's dashes 22 under it, the clip at it (none since 16:37, §24.36), the scroll bar's track y 168–1062. `Profile.gridPacksOff` is unused (kept so that older saves load).

### 24.29 VISUAL HIT POINT, a 12-group preview round, the preview's score on phones, a larger player frame and shell capsule (Kelvin 2026-10-20 03:20–03:38)
Asked at 03:20, 「FC / AR指示切换的频率太慢了。预览部分每12组键一个循环，每三组单单双循环，每次都是第5组爆echo，第9组爆miss。然后手机上的实时预览分数位置不对。最后在视觉里面加一个功能：目押校准，按px来，指的是tap和hold底部按键中线（矩形横的对称轴）在判定时距离判定线的距离。目前是按键下边缘碰到判定线就判定，但有些人目押的时候习惯等判定线横穿音符的时候判定，就会出现眼耳不协调。加在游戏画面预览之前，和谱面延时类似的一个结构，下面的校准会单独弹出来一个界面」; at 03:26, with a 16:10 map marked with two green boxes, 「然后名字+深度框大一点，现在特别小的名字和低的depth就在角落缩成一团了，参考我给你的图片的绿框大小」; at 03:38, 「然后贝壳就不要再包括通往存档的跳转了」.

- **VISUAL HIT POINT** / 目押校准: the first row of VISUAL (§9.2), how every note is drawn by it (§5.8), and its calibration screen behind CALIBRATE… (§9.7).
- **The preview's round**: 12 groups, single, single, double, four times; group 5 an ECHO, group 9 two LOSTs, so the FC / AR colour goes light blue → purple → white every 7.2 s (§9.3).
- **The preview's score on phones**: the preview's HUD corners no longer take the device's cutout insets or EXTRA EDGE PADDING (§9.3).
- **Map player frame** (`MapHud.Badge`, × UI scale): 160 high (84 before) with its top at the safe top (it was centred on the Settings row), at least 460 wide (`MapStyle.FRAME_MIN_W`) or as wide as its text: avatar 114 centred 80 from its left; the name UI_BOLD cap 35 from x 163, its baseline 7 over the centre, shrinking past 560; `DEPTH` cap 21 `ink2` and the depth NUMBER cap 27, 16 apart, baseline 42 under the centre; right padding 40, radius 26. On 16:10 that is x 24–484 and 24–184 from the top, about Kelvin's box (x 8–476, y 14–178). The ALL SONGS grid starts 24 under it (`MapHud.frameBottom`).
- **Map shell capsule** (`MapHud.ShellCapsule`): 88 high (44 before), at least 200 wide (`CAPSULE_MIN_W`), the shell icon 45 and the count NUMBER cap 35 centred in it, still 24 left of Settings (or the mode toggle) on its row; about Kelvin's box (214 × 96 at x 1578–1792). Since 03:38 it is not a control: no tap, no press tint; only the player frame opens the Profile.
- Not changed: the result screen's player frame and capsule (§24.19), and the capsule's tap there (shells breakdown).

### 24.30 The HUD score and accuracy kept; the pause control centred in the top band (Kelvin 2026-10-20 05:00–05:31)
Asked at 05:00, 「acc的显示指标干脆不要再出现了（计分还是要），现在除了打歌的时候有其他时候都没了。把分数可以再放大一点。」, built and delivered (no accuracy on the HUD or in the GAMEPLAY VISUALS preview, the score at cap 40). Then at 05:25, 「如果是autoplay，autoplay标识改到combo那里，淡绿色标出来，然后分数还要再往下移动一点吗，再放大一点，高度要接近整个上沿，始终靠左角落居中。暂停键也要剧中一点，始终靠右角落并居中于上沿。这种属于小改动，直接修改就行了」; at 05:27, 「我想了想还是改回原来那样的acc和autoplay的位置吧。因为acc还是有用的，能帮助玩家提前判断自己的情况」; and at 05:31, 「分数还是改回原样，就是说原来的分数/acc排版不要动了，autoplay排版也不要动了。暂停键还是改」.

- **Score, accuracy and AUTOPLAY as before 05:00** (§5.3; until 05:56, §24.31): the score NUMBER 32 (80 % of 40), the accuracy under it at `max(19.2·k, 20)`, `AUTOPLAY` in its place after an autoplay run. The GAMEPLAY VISUALS preview shows the score and the accuracy again (§9.3), and the tutorial's tour says `SCORE & ACCURACY · TOP LEFT` / 分数与准确率 · 左上 again (§10.2).
- **Pause control** (`PauseControl.layout`, §5.3): still in the right corner (x unchanged); its centre is now halfway between the top edge and the default sky line, so the band above the sky line has the ring in its middle: 16:9 and phones 1026 (band 1080–972), 16:10 1080 (1140–1020), 4:3 1188 (1260–1116). It was 50·k under the top edge before (1030, 1090, 1210; 1022.5 on a phone at k 1.15). The ring's outer edge keeps 10 clear of the top edge, under the progress hairline (`TOP_CLEAR`), which only a large EXTRA EDGE PADDING reaches (48 at 16:9: 988.5 instead of 1002). The touch box still holds the ring plus 8 and reaches the screen edges, and the tour's pause label follows the icon.

### 24.31 No accuracy after all; the score at full size, a fixed distance from the left edge; AUTOPLAY in the middle (Kelvin 2026-10-20 05:56–05:57)
Asked at 05:56, 「还是去掉准确率吧。改回原来那版大一点的分数。分数始终距离屏幕左侧一个固定距离」, and at 05:57, 「然后autoplay放到中间。」

- **No accuracy** on the HUD or in the GAMEPLAY VISUALS preview, as at 05:00 (§9.3); the tutorial's tour says `SCORE · TOP LEFT` / 分数 · 左上 (§10.2). The accuracy still counts in the score, its breakdown on the result screen, records and ratings.
- **The score** is NUMBER cap 40 × k again (the 05:00 size; until 06:27, §24.32), its cap top at the safe top as before. Its left edge is 24 from the visible left edge plus the Settings edge padding (`SCORE_LEFT`), without the device's cutout inset, so it keeps the same distance from the screen's left edge on every screen: on a phone with a 110 px cutout inset on the left it moved from 134 to 24. The live preview's score was already 24 from its corner (§9.3). The difficulty chip and title at the bottom-left, the time and the pause control still keep clear of a cutout.
- **AUTOPLAY** is no longer under the score: in an autoplay run the label under the combo reads `AUTOPLAY` in `auto` light green (UI_LABEL 20, α .70) instead of `COMBO`, from the first frame of the run, with or without a combo (§5.3). The score keeps its `auto` tint.

### 24.32 The score sized by the top band: 0.15 / 0.7 / 0.15 (Kelvin 2026-10-20 06:26–06:27)
Asked at 06:26, 「分数还要往下移动一点，16：10的还是太高了」, then at 06:27, 「上沿->分数上沿->分数下沿->上判定线default位置的三短距离比例为0.15，0.7，0.15」.

- **The top band** runs from the top edge as the pause control has it (the visible top less the cutout inset and EXTRA EDGE PADDING; in the live preview the screen's own top) down to the default sky line, a tenth of the world height under the visible top (`PauseControl.sky()`). It splits 0.15 above the score's cap, 0.7 for the cap and 0.15 under it (`GameplayHud.SCORE_BAND_TOP`, `SCORE_BAND_CAP`), so the score is centred on the pause control's height and its size follows the screen's shape: cap 75.6 at 16:9 and 20:9 (band 108), 84 at 16:10 (band 120), 100.8 at 4:3 (band 144), whatever the UI scale, never under `Ui.minEssentialCap()`. Before, it was NUMBER 40 × k with its cap top 24 under the top edge (§24.31): 16 higher than now at 16:10.
- **The left edge** stays 24 from the screen's left edge plus EXTRA EDGE PADDING (§24.31).
- **The time and the chip constant** keep the NUMBER raster at the §5.3 score cap 40 × k (`numRaster()`); the score is drawn from a raster at its own cap, prewarmed with the others, so the small text is not drawn shrunk from the large one (§14).

### 24.33 The top band split 0.3 / 0.4 / 0.3 (Kelvin 2026-10-20 07:01)
Asked at 07:01, on the 0.7 cap of §24.32: 「太大了太大了！改回0.3+0.4+0.3」.

- **The top band** of §24.32 now splits 0.3 above the score's cap, 0.4 for the cap and 0.3 under it (`GameplayHud.SCORE_BAND_TOP`, `SCORE_BAND_CAP`): cap 43.2 at 16:9 and 20:9, 48 at 16:10, 57.6 at 4:3 (75.6, 84 and 100.8 from 06:27). The split is still even above and under, so the score stays centred on the pause control's height and only its size changes; at 16:9 its cap top moves from 1063.8 down to 1047.6.
- **The left edge, the live preview and the rasters** stay as in §24.32.

### 24.34 The map's player block without a frame (Kelvin 2026-10-20 13:22–14:10)
Asked at 13:22: 「个人profile的那个稍微高了一点，以及观感不佳试着重新设计一下，我始终对这个细节不满意」. Four plans were drawn after Kelvin's four references (13:38), then a fifth after 「方案3的圆形头像框加在方案2里吧，或者想办法把2，3结合一下」 (14:07), which Kelvin chose at 14:10. Measures below are × UI scale and at 16:10 (y from the top).

- **No panel** (`MapHud.Badge`): the block is 100 high (`MapStyle.FRAME_H`; the frame of §24.29 was 160) with its top on the shell capsule's top (36) and its left edge 12 further in than the frame's (36). It is anchored to the screen's left edge, 24 plus EXTRA EDGE PADDING plus 12, so a cutout no longer pushes it in (Kelvin 14:39: 「手机20：9的往左侧移动一点，锚定左侧屏幕边缘」); the capsule and Settings keep the safe right edge.
- **Avatar**: a disc of radius 50 (`AV_R`, fill #05091C) with a white ring 3 wide at radius 47 (alpha 0.92) and a soft shadow 3 under it; the avatar, 58 wide (`AV_ART`), sits 1 under the disc's centre.
- **Name**: UI_BOLD cap 31, 118 from the block's left edge, baseline 42 under its top, over a soft shadow about 2 under it; at most 560 wide (shrinking to 0.8 as before) and never within 24 of the capsule.
- **Depth tag**: a hexagon pointed at both ends (tips 15 deep) from 116, 42 high with its bottom on the block's, fill #080D26 at 0.9, a 1.6 cyan edge (#38B6FF, 0.9) and a shadow 3 under it. Inside, `DEPTH` (UI cap 15, tracking 0.12 of the font size, INK2) and the depth (NUMBER cap 24, INK1) share a baseline 91 under the block's top, 10 apart, with 10 of padding inside each tip; the tag grows with the number.
- **Tap**: only the avatar opens Profile (Kelvin 14:40: 「然后现在就只保留点击头像进入profile界面」), a circle at least a touch target across; a touch on the name or the tag reaches the map. The block's bounds, from the avatar to the end of the name or the tag, still fade the map's labels under it.
- **ALL SONGS** still starts 24 under the block, so its grid top moves up from 208 to 160.
- **New helpers**: `Draw.convexFill` and `Draw.convexStroke` (anti-aliased convex polygons, feathered over one pixel like `ringArc`) and `MapRenderer.uiTracked` (UI text with tracking).
- **Not changed**: the result screen's player frame and capsule (`CornerHud`, §24.19), the map's shell capsule, Settings and the other map controls.

### 24.35 The map's shell capsule beside the depth tag, the depth larger, a band under the name, a larger avatar (Kelvin 2026-10-20 15:10–16:27)
Asked at 15:10 with a phone screenshot: 「我感觉贝壳有点太大了，喧宾夺主了，我觉得深度才应该有那么大，但我不想把深度单独放出来，除非你有更好的设计。反正贝壳肯定是要小一点」. After shots of three depth sizes, at 15:36: 「之前的号方案的那种光带加在名字上吧，然后把贝壳移动到depth旁边」 and 「用最下面一版的深度大小我看看」. At 15:46: 「头像圈再放大一点，直径跟名字区域+深度高度加在一起相等」 and the depth number 「稍微小一点」. At 16:05: 「把头像圈直径扩大1.5倍我看看」, and at 16:27, after the full-screen shot, 「可以，推送吧」. Measures below are × UI scale and at 16:10.

- **Depth tag** (`MapHud.Badge`): 62 high (42 before), tips 22 deep (15), 14 of padding (10); `DEPTH` UI cap 17 (15) and the depth NUMBER cap 36 (24; 40 at 15:36, a little smaller at 15:46), centred in the tag on a baseline 87 under the column's top (see Column).
- **Name**: UI_BOLD cap 24 (31) on a baseline 29 under the column's top (42 under the block's top before).
- **Name band** (the band of plans 3 and 6): navy #080D26 at 0.84 from the avatar's centre (the disc covers its start), 0 to 36 under the column's top; solid to 0.6 of the name's width, then fading out to nothing 140 past the name's end, and never within 24 of Settings or the mode toggle.
- **Shell capsule** (`MapHud.ShellCapsule`): 16 right of the depth tag's right tip, centred on the tag, 46 high (88) and at least 110 wide (200); the shell icon 26 and the count NUMBER cap 22 (45 and 35), paddings 14 / 7 / 16. It no longer sits left of Settings, so the top right holds only Settings (and the hub's mode toggle), and the name may run up to 24 short of them.
- **Avatar**: at 15:46 the white ring's outer edge went from 97 to 100 across, exactly the name band's top to the depth tag's bottom; at 16:05 the circle grew 1.5 times to 150 across: the disc's radius 75 (50), the white ring's 73.5 (47) at width 3, its outer edge the disc's, and the avatar inside it 90 (58).
- **Column**: the name band and the depth tag, 100 high together and unchanged in size, sit centred against the circle, their top 25 under the block's top (`COL_Y`, `COL_H`); the name starts at 168 (118) and the tag's left tip at 166 (116), 50 further right with the wider circle.
- **Block**: its top is the top of Settings (still 36 at 16:10), no longer the capsule's top; it is 150 high (100, `MapStyle.FRAME_H`), so ALL SONGS, which starts under it, starts 50 lower.
- **Not changed**: the avatar-only tap, the capsule's lack of a tap and the result screen's `CornerHud`.

### 24.36 ALL SONGS without a hard top, the name higher and lined up with the depth tag, a deeper band (Kelvin 2026-10-20 16:37–16:39)
Asked at 16:37 with a screenshot of ALL SONGS scrolled down: 「这个顶上不要做成有边界的。然后玩家名称稍微往上挪一点，现在离depth太近了」, and at 16:39: 「然后那个banner稍微深一点，名字左对齐depth六边形左端点」. Measures below are × UI scale and at 16:10.

- **ALL SONGS top** (`GridPage`): nothing clips the grid at its top any more (a straight line 24 under the player block cut it before); it scrolls on under the player block, Settings and the top of the screen as a map does. The fade above the lens row is unchanged. A song showing beside or under the player block takes a tap (`inBand` keeps only the faded bottom), after the HUD's own controls. `reveal` still brings the selected song and its labels below the block (`gridTop`), and the scroll bar is unchanged.
- **Name** (`MapHud.Badge`): its baseline 21 under the column's top (29), its band moving with it, −8 to 28 (0 to 36), so 10 lie between the band and the depth tag (2).
- **Name's left**: 165 (168), so that its first stroke lines up with the depth tag's left tip at 166 (the glyphs carry about 1 of side bearing).
- **Band**: alpha 0.95 (0.84).
- **Not changed**: the avatar, the depth tag, the shell capsule and the result screen's `CornerHud`.

### 24.37 The map's player block without the DEPTH label, at 75% (Kelvin 2026-10-20 17:34)
Asked at 17:34: 「depth那个字不要了，然后整体等比例缩小到原来的75%」. Measures below are × UI scale and at 16:10.

- **Depth tag** (`MapHud.Badge`): only the number, e.g. `-2,211 m`, in both languages. It starts where the DEPTH label started (a tip and a padding in from the left tip), and the tag is the tips, the paddings and the number wide, so it is narrower and the shell capsule beside it moves left with it (a fixed room with the number centred since §24.38). `MapRenderer.uiTracked`, which only drew that label, is gone.
- **Scale** (`MapHud.BLOCK_K` 0.75): the block and the capsule are drawn at 0.75 of their measures (§24.34–§24.36): the circle 112.5 across (150), the avatar 67.5 (90), the name's cap 18 (24) and its band, the tag 46.5 high (62) with the number's cap 27 (36), the capsule 34.5 high (46) and at least 82.5 wide (110) with the count's cap 16.5 (22), and every gap, offset and shadow between them; `FRAME_H` is drawn as 112.5. The caps go through `MapUi.cap` at their scaled size, so on the smallest screens the name and the count keep their legible minimums.
- **Not changed**: the block's top left corner (`BLOCK_IN` in from the screen's left margin, its top at the top of Settings), the name's room up to 24 short of Settings and `NAME_MAX`, the 24 between the block and ALL SONGS (so the grid starts 37.5 higher), the avatar-only tap and the result screen's `CornerHud`.

### 24.38 The depth tag keeps room for a minus sign and five digits, the number centred (Kelvin 2026-10-20 17:58)
Asked at 17:58: 「预留5位数的空间加一个负号的空间啊，然后预留了空间以后，如果没有填满数字就剧中」.

- **Room** (`MapHud.depthRoomU`): the tag is the tips, the paddings and the width of `-88,888 m` in the NUMBER font's widest digit (the grouping comma and the unit included), whatever the depth, so it and the shell capsule beside it no longer move as the depth changes. A longer number (six digits) still widens the tag (`tagW` takes the larger).
- **Number**: centred between the tips (`Badge.numberX` is the tag's centre, drawn `Align.center`), so `-2,211 m`, `-36 m` and `0 m` sit in the middle and a five-digit depth about fills the room.

### 24.39 ALL SONGS: the player block over the songs, the card through a rise (Kelvin 2026-10-20 18:45)
Asked at 18:45: 「1. 全部歌曲界面歌曲名字的显示图层高于profile，我要profile的图层最大。2. 全部歌曲上浮的过程中侧板会收起来。我要上浮的时候侧板也保持不动。」

- **Layers** (`MapScreen.draw`): on ALL SONGS the grid's UI and NUMBER text (titles, scores, constants: `MapRenderer.drawTextUi`, `GridPage.drawUi`, `MapRenderer.drawTextNumbers`) goes down right after the grid's shapes, before the HUD's atlas phase, so the player block (avatar, name and band, depth tag, shell capsule) covers the songs scrolled under it (they slide up to the top of the screen since §24.36). That is two more texture switches on that page; the maps keep the old order, their labels fading out under the block.
- **Rise** (`MapScreen.goTo`, `enter`): a rise from ALL SONGS no longer closes the card as it starts. The card stays where it is (input is locked while the transition runs; the preview dwell stops and the menu music comes back up as when it closes) and is snapped closed, its jacket released, at the swap, when the level above is entered. Every other way out still closes the card first.

### 24.40 Result screen: the depth rolls to its new value, the shells bubble merges into the capsule (Kelvin 2026-10-20 18:45)
> Since Kelvin 2026-10-20 19:45 the player frame is the map's block and the gain sits in its own pointed frame; the tag, not the line, grows (1.3×): §24.41.

Asked at 18:45: 「如果说深度值下降了，就显示旧的深度值，右下角浮现这次下降了多少，然后滚动显示到新的深度值。整个过程大概持续2.5秒，要引起玩家的注意。贝壳就普通一点，如果深度值更新了就等深度值完成再开始。就只是在左侧浮现一个加多少的贝壳泡，然后和贝壳那个东西合并，像两个水滴合并一样，再显示新的值。」 Measures below are × UI scale and at 16:10; the frame and the capsule keep their look (§24.19).

- **Depth** (phase D, `Choreo.DEPTH_T` 3.20, `DEPTH_D` 2.50; only for a recorded run that went deeper): the old depth shows; the line (number, unit and gain) grows to 1.7× (`CornerHud.DEPTH_K`, `pow3Out` over 0.35 s) about a pivot 0.35 of the cap above its baseline (`PIVOT`, so it grows up and stays inside the panel), in the layer accent's glow, with an accent ring; `▼ 40` (RESONANCE, cap 15) floats up into its lower right from 0.25 s (0.45 s, 3 under the baseline at rest); from 0.70 s the digits roll as an odometer to the new depth (`pow3` over 1.30 s; the units turn with the fraction, a column turns only while the one on its right goes 9 → 0, each cell cut to the line by `TextQueue.cellClipped`, a new leading digit rolls in and the minus sign moves with it) while bubbles rise from the number; it lands with a white flash (0.35 s), a 0.12 bump and a white ring, and shrinks back from 2.05 s (`sine`). The gain stays. When the scaled line would not fit, the panel stretches with it (a short name) and comes back.
- **Shells** (phase E, `SHELLS_D` 1.30): after phase D when it runs, else at `SHELLS_T` 3.40. A `+127` bubble (NUMBER 17 on the capsule's fill) rises 10 into place 22 left of the capsule (0.30 s, pop), is drawn in from 0.45 s (`pow2In` over 0.40 s) and shrinks as it enters; within 12 of the capsule a neck grows between their ends (`CornerHud.neck`: 14 slices, waist up to 0.92 of the smaller radius) so the two read as one drop. All three share one opaque fill (`SHELL_CAPSULE` mixed 0.16 toward gold), so no seam shows. Then the capsule wobbles (damped, 0.6 s), its left end flashes gold (0.5 s) and the count rolls on (`pow3Out` over 0.40 s, gold toward white). The capsule no longer shows `+N` beside the count.
- **Reduced motion**: no scale, roll, rings, wobble or pop; the depth counts on to the new value in place.
- Buttons follow the last phase as before (`Choreo.lastEnd`); a skip runs these phases fast like the rest.

### 24.41 Result screen: the map's player block, the gain in a pointed frame (Kelvin 2026-10-20 19:45)
Asked at 19:45 with an Arcaea result screen (the potential in a diamond, its change in a larger diamond frame beside it): 「depth不要出现了，就和之前那样的六边形架构啊，然后出现的下降也是有一个六边形外框。你看arcaea是怎么做的，原来的是菱形，后面变动的外框也是菱形。我们不要做的像arcaea那么大但是思路是一样的。然后点一下右上角的贝壳还是可以弹出来这次加的细则」 Measures at 16:10.

- **Block**: the panel with `DEPTH -2,698 m` (§24.19) gives way to the map's player block (§24.34–§24.38; `MapHud`'s measures copied into `CornerHud`): the avatar in a navy disc with a white ring and a soft shadow, the name (UI_BOLD) on its fading navy band with a soft shadow, and under it the depth in a tag pointed at both ends (navy, cyan edge) with room for `-88,888 m`, the number and its unit centred; no DEPTH label. It is drawn at 2/3 of the block's full measures (the map's at 3/4): 100 high from y 32 to 132 (`ResultLayout.frameH` 100 and `frameY` at 132; a panel 84 high from 40 before), centred on the capsule's 82 and 8 clear of the title band, its left 12 in from the safe left as on the map. Caps: name 16, depth 24.
- **Gain**: `▼ 40` (RESONANCE, cap 16) sits in its own smaller pointed frame (46 high to the tag's 62; navy at 0.9, a RESONANCE edge, a shadow), its left tip 10 under the tag's right end and its centre 18 under the tag's, so it shows at the tag's lower right, tucked under it as Arcaea's change frame is under its potential, but smaller than the tag (Arcaea's is larger). It floats up 10 into place from 0.25 s as before and stays.
- **Emphasis**: the whole tag, with the gain's frame, grows to 1.3× about its left tip (`CornerHud.DEPTH_K`; the line grew 1.7×) in the accent glow, with the rings centred on the tag; its edge brightens toward white while it is up and flashes as the number lands (bump 0.08). The digits roll inside it as before, in the final value's cells.
- **Unchanged**: the timings (§24.40), the shells bubble and capsule and the taps: the block opens the depth breakdown and, once the screen is idle, the capsule opens this run's shells breakdown (`基础`, `新纪录`, `今日首次下潜`, `合计`).

### 24.42 Start screen, first run and the dive HUD from the annotated gallery (Kelvin 2026-10-20 21:17, 21:18)
Asked at 21:17: 「开始界面右下角把设置去掉，two signals one descent往下挪一点。底下点击下潜换成点击任意位置开始。左下角的玩家更改只保留更换玩家和新建玩家的选项，不要管理」; at 21:18 for the notes on the front pages of the annotated tablet gallery: 「前面introduction和游戏刚进去的时候的玩家设置那部分还有很多批注，你看一下，然后修改。」 Page numbers are the gallery's own. Measures at 16:10.

- **Welcome** (§7): no Settings gear at the bottom right (Settings opens from the map's HUD); the tagline's baseline 480 (520 before); the prompt `TAP ANYWHERE TO START` / `点击任意位置开始` (p.1 asked for 「点击任意位置开始游玩」; the 21:17 wording is used). A tap still starts anywhere but on a K, which pings and jiggles (§7 Play), and on the profile chip.
- **Switcher** (§7 corners; also p.32: 「去掉，只保留新建玩家」): the player rows and `+ NEW DIVER` (`SwitcherSheet`); `MANAGE` (→ Profile) is gone. Managing players stays on the Profile screen.
- **First run** (§10.1; pp.2, 4, 5): no header on any step, so no step title and no BACK icon (「初次进入游戏这部分不要了」, 「删了，返回键也删了」); the step dots stay at the top right, the system BACK still steps back and the titles still name the screen on the curtain from Calibration. Each step's card is centred on the screen between the safe top and the buttons (「整一个区域放在屏幕居中处」, `FirstRunScreen.cardY`); on short screens the gaps inside steps 2 and 3 shrink (step 2: card top to sample 282 → 150, sample to the first row 226 → 160; step 3: card top to the motif 342 → 260). Step 1: the name field holds the default `DIVER-XXXX` (「默认名字直接放进来」), and `DiverForm` hides the hint `EMPTY KEEPS DIVER-XXXX` while the field holds exactly that name; CONFIRM alone, centred; no LATER (「初次登入不要了」). Step 2: the live sample is the English `SIGNAL` in both languages (「这里直接显示英语的Signal」; `信号` in 中文 before).
- **Dive HUD** (§5.3; p.7): the progress bar is 1 % of the screen's height thick, flush with the top edge (「上面的进度条可以厚一点，大约占屏幕高度的1%，手机端也一样」). The bottom row (chip, title, time) is centred in the bottom band, from the bottom edge up to the default ground line, the chip and the title 1.25× (「这个东西放大一点，放在下沿左侧的正中间」, 「时间点是下沿右侧正中间」); the time keeps its size. (At 22:12 the row went back to the bottom edge and the time grew too, §24.43.) The UI and UI_BOLD pages are rastered at the title's 25 and the chip's 22.5 (20 and 22 before), so the larger text stays sharp and the page count is unchanged. The page's notes on the score and the pause control were overtaken by the same day's later rounds (§24.30–§24.33) and are left as they are.
- **Tutorial** (§10.2; p.26): the outro's `MORE TO DISCOVER` / `更多变化` plate (216.5–221.5) is gone (「删了」); afterimages and moving notes are no longer mentioned.

### 24.43 Dive HUD bottom row back on the bottom edge, the time larger (Kelvin 2026-10-20 22:12)
Asked after the §24.42 round: 「时间点的大小也放大。surface 2那一串锚定屏幕左下角边缘」.

- **Chip + title**: the chip sits at (SL, SB) again, its bottom on the safe bottom edge as before §24.42, so the row keeps to the bottom-left corner at every aspect instead of the middle of the bottom band (which put it 72 above the edge at 4:3); the 1.25× sizes of §24.42 stay. `BOTTOM_CLEAR` is gone. The chip's left edge lines up with the score's, a fixed distance from the screen's left edge whatever the cutout (as the score's since §24.31), so on a phone with a 110 cutout inset it starts 24 from the edge instead of 134.
- **Time**: NUMBER 25 (`TIME_CAP`; 20 × 1.25 like the chip and the title), right-aligned at SR and centred on the chip row, so it moves down with the row. Its digits still come from the score's NUMBER raster, so the page count is unchanged.

### 24.44 First run in four steps: no name, the two calibrations in the card, the visual hit point in px with default 0 (Kelvin 2026-10-20 22:30, 22:37)
Asked at 22:30: 「英文模式新玩家第一页用player name而不是diver name。然后第二步那个闪烁特效上方空间和下方空间占地太大了，稍微收窄一点，"实时示例"删了。然后校准那里直接把延迟校准的设置界面搬过来，放在现在那个框里（那个框横向拉长一点）。点击应用到扬声器后就直接进introduction。」; at 22:37: 「第一页“玩家改名”删了，直接统一用默认名字，后续玩家自己在profile里面改名。延迟校准那一页删掉的内容：“用你平时玩的时候用的扬声器或耳机”，“点击时保持画面不动”那一行，“用耳朵听”那一行。目押校准不要再有“毫秒”这个标准了啊，下面的散点图指标应该是px，然后默认值设为0。延迟校准以后加入目押校准，类似的idea，默认值为0而不是20……那么现在音押校准点“应用”（应用到扬声器改为应用）以后进入目押校准界面，目押校准完了进introduction」. Measures at 16:10.

- **Step 1** (§10.1): no name field; the player keeps the default name and renames on the Profile screen. `DiverForm.withoutName()` hides the field for good and moves the swatches up beside the avatar. The create-player sheet keeps its field, labelled `PLAYER NAME` in English (`DIVER NAME` before).
- **Step 2**: card top to sample 282 → 236, sample to the first row 226 → 170 (short screens: 160 → 130); no `LIVE SAMPLE` / `实时示例`.
- **Step 3**: the audio calibration (§9.4) in a 1400 × 700 card instead of TUNE YOUR SONAR: `CalibrationPanel` holds everything the calibration screen drew and did; `CalibrationScreen` is now its full-screen shell and the first run lays it out in the card (`layoutCard`, positions scaled with the card's height). Quick mode, no VERIFY. APPLY (`APPLY` / `应用` everywhere, `APPLY TO SPEAKER` before) moves on to step 4; SKIP too, leaving the calibration reminder for an uncalibrated player.
- **Calibration texts** (both places): `USE THE SPEAKER OR HEADPHONES YOU PLAY WITH`, the foot `THE SCREEN STAYS STILL WHILE YOU TAP · …` and the run's `LISTEN · THE SCREEN STAYS STILL ON PURPOSE` are gone.
- **Step 4**: the visual hit point calibration (§9.7) in the same card (`HitPointPanel`, wrapped by `HitPointScreen` the same way): title and START where step 3 had them, the intro in one line, the run's line 100 above the card's bottom with the keys fading in under the card's top edge and out under the line. APPLY or SKIP starts the tutorial.
- **px only** (§9.7): the result line, the spread and the strip are in px (where each tap saw the key's midline, −150..+150, `BELOW THE LINE` / `ABOVE THE LINE`); nothing on the screen is in ms any more. The value means what it meant: the distance from a key's midline to its line at the judgement time.
- **Default 0** (§5.8, §9.2): `Settings.HIT_POINT_DEFAULT` 0. Profile schema 5: a player still on the old default 20 moves to 0 once (`Profile.migrateToSchema5`); any other value is kept. The drawing already put a key's midline `hitPointPx × size` from its line at the judgement time (the positions are measured to the key's edge and `NoteRenderer.hitShift` adds the difference), so at 0 the midline is what travels the approach to the line; only the default changed, and judging is untouched.

### 24.45 First run in three steps, the calibration values centred with their units (Kelvin 2026-10-21 00:41, 00:42)
Asked at 00:41: 「第一部直接不要了，默认随机一个头像就可以了」; at 00:42: 「那个+40ms和-32px不居中」, 「其他的我感觉都还行」.

- **No WHO'S DIVING?** (§10.1): the first run opens on COMFORT, `STEP 1 OF 3` / `第 1 步，共 3 步`; the calibrations are steps 2 and 3. A fresh install's player already has the default name and a random avatar (`ProfileStore` creates them with `AvatarSpec.fromSeed`, options unlocked at 0 m), which stay; both change on the Profile screen. `FirstRunScreen` names its steps `COMFORT`, `AUDIO` and `HIT`; `DiverForm` loses `withoutName()` and serves the create-player sheet only.
- **Old save**: an import is reported by the start-up toast as before (`IMPORTED N EARLIER RECORDS`); one that could not be read, said under the removed step's form until now, is a start-up toast during the first run (`EARLIER RECORDS WERE FOUND BUT COULD NOT BE READ` / `找到了以前的记录，但读不出来`, `lost`).
- **Centred values** (§9.4, §9.7): the result's value and its unit (`+40 ms`, `-32 px`) are centred together on the panel's centre line, 14 apart; the value's right edge sat on the line before, so the pair leaned left. `APPLIED` follows the unit. The audio calibration's current offset on its intro is centred the same way (12 apart). Both screens and the first run's card alike.

### 24.46 The later pages of the annotated tablet gallery (Kelvin 2026-10-21 01:04)
Notes on pages 71, 89, 103, 108 and 115 of the annotated 4:3 gallery, done as one round: p.71 「再大一点」 (the art on the DIVE), p.89 「把每一列的高度扩大，间隔开一点」 (AUDIO), p.103 「居中」 (a licence text), p.108 「这里为什么还是中文，都用lost」 (VERIFY), p.115 「一行拍三个」 (PLAYER).

- **DIVE art** (§3.12 as built): the art disc's radius is 325 (`TransitionLayer.ART_R`, 250 before). The map's dive zoom stops at that size, so the song's hole grows into the disc as before.
- **AUDIO** (§9.1): the rows share the height the list leaves under them, like the GAMEPLAY VISUALS columns, and each row's content sits in the middle of its taller row (`SettingsPage.setSpreadCentred`; the hairline stays at the row's bottom). A list whose rows do not fit (a phone) scrolls as before.
- **Licence text** (§9.2): the text is centred in the list as one block, its lines still left-aligned so the licence's own indentation stays (`TextRow`); a line wider than the list is fitted from its left. The header above it stays at the left like every section header. Undone at 02:47: the text is at the left again (§24.49).
- **VERIFY** (§9.4): the live judgement word is RESONANCE / ECHO / LOST in both languages (Rubik Glitch, like the playfield); it read 共鸣 / 回响 / 迷失 in 中文.
- **PLAYER** (§8.2): three cards a row wherever three fit (each at least 330 wide), else two, else one; cards are at most 520 wide as before. On a card under 440 wide (16:10 and 16:9, about 349) the avatar is 72 and the margins 18, the meta line shows a gold shell glyph before the count instead of the word (`3,617 · -2,486 m` after the glyph, as on the map's price chips), and the last-activity line is fitted to the card. 4:3 and phones keep full cards (520 and about 473). The `+ NEW DIVER` hint reads `UP TO 8 ON THIS DEVICE` in English (`UP TO 8 DIVERS ON THIS DEVICE` before); 12 since §24.47.

### 24.47 Twelve players, and no NEW DIVER or IMPORT on a full device (Kelvin 2026-10-21 01:44)
Asked at 01:44: 「把允许的玩家数增加到12，然后到12以后就直接不要再显示“导入”和“添加玩家”了。」

- **Cap** (§8.1): `ProfileStore.MAX_PROFILES` is 12 (8 before). Every text that names it is built from the constant: the PLAYER count (`12 / 12 DIVERS` / `12 / 12 位玩家`), the new-diver hint (`UP TO 12 ON THIS DEVICE` / `这台设备最多存 12 位玩家`), the switcher's count, and the full-device line and toast of the create sheet and the import dialog (`ShellFormat.deviceFull`, `diversMax`).
- **PLAYER** (§8.2): with 12 players the `+ NEW DIVER` card and the IMPORT button are gone, and the tab's scroll height leaves the button out. Before, the card stayed in the grid, dimmed, reading `8 / 8 DIVERS` and `THIS DEVICE IS FULL`, and IMPORT stayed under the grid (its dialog then said the device was full). Twelve cards are four rows of three; at 16:10, 4:3 and on a phone they fit without scrolling.
- **Welcome switcher** (§7): with 12 players the `+ NEW DIVER` button is gone and its footer with it (24 under the rows instead of 150 and the hairline); the rows scroll where they do not fit, as before.
- No screen deletes a player at present (`DiverActionsDialog.DeleteDialog` is opened nowhere), so a full device stays full.

### 24.48 PLAYER for DIVER in the English texts (Kelvin 2026-10-21 02:04)
Asked at 02:04 about the PLAYER count: 「12/12 players，不是divers」. Taken for every English text that names a player profile; the Chinese has said 玩家 since 2026-10-18 20:15, and the name field's label became `PLAYER NAME` on 2026-10-20 22:30.

- **Now PLAYER:** the PLAYER tab's `12 / 12 PLAYERS` and `NEW PLAYER` card; the Welcome switcher's title `PLAYERS` and `+ NEW PLAYER`; the create sheet's title `NEW PLAYER` and its toast `NEW PLAYER · NAME`; the name hints `TAP TO NAME YOUR PLAYER`; the IMPORT dialog's `IMPORT PLAYER`, `COPY A PLAYER CODE (KK1:…) ON ANY DEVICE, THEN PASTE IT HERE.` and `THAT IS NOT A VALID PLAYER CODE`; the full-device texts `12 / 12 PLAYERS · THIS DEVICE IS FULL` and `12 PLAYERS MAX`; Settings OTHER's `COPY THIS PLAYER AS A KK1: CODE` and `ERASES ALL OF THIS PLAYER'S PROGRESS`; the reset confirmation's `TYPE THE PLAYER NAME IN FULL TO RESET`; the map badge's fallback `NO PLAYER`; and the rename and delete dialogs that no screen opens (`RENAME PLAYER`, `PLAYER NAME`, `DELETE PLAYER?`, `PLAYER DELETED`, `THE LAST PLAYER CANNOT BE DELETED`).
- **Unchanged:** a new player's default name `DIVER-XXXX` (a name, the same in both languages; players who have it keep it), the Welcome chip's `DIVING AS NAME`, and the world's diving words (DESCEND, DIVE, depth). Classes and methods in the code keep "diver".

### 24.49 Licence text at the left again (Kelvin 2026-10-21 02:47)
Asked at 02:47: 「lisence那部分还是左对齐吧」. A licence's full text is drawn line by line from the list's left edge again, as before §24.46 (`TextRow`), under its header; the centring of 01:3x is gone.

### 24.50 The audio calibration's strip to ±300 ms (Kelvin 2026-10-21 02:52, 03:04)
Asked at 02:52: 「然后你那个音频的calibration，下面那个要做成+-300ms的，+-150ms的区域可以稍微大一点，超过150ms的edge case也要出现在那个坐标轴上，但是占的区域可以小一点。这个更改也适用于用户第一次玩的时候调参数的时候」.

- **Axis** (§9.4, `TapStrip`): −300..+300 ms in two scales. ±150 ms takes three quarters of the strip's width (`INNER_SHARE` 0.75), and each stretch from 150 to 300 ms takes an eighth; the axis line is fainter there (alpha 0.12 instead of 0.22). Ticks −300, −150, −100, −50, 0, +50, +100, +150, +300; the RESONANCE band (±66 ms) and the caret follow the same mapping.
- **Taps beyond 150 ms** sit in the outer stretches; until now they sat on the ends as rings. A tap is at most 250 ms from its beat (the click is 120 BPM), so every tap now lands on the axis.
- **Hollow grey rings** (03:04: 「然后+-150~+-300那片区间如果有点击，显示空心的灰色圆圈」): a tap in either outer stretch, early or late, is drawn as a hollow ring in `ink2` (#A9B6D6) instead of an ECHO dot. Taps within ±150 ms keep their RESONANCE and ECHO dots.
- **Where:** the Settings calibration screen and the first run's calibration card share the panel, so both have it. The VISUAL HIT POINT strip (px, §9.7) keeps −150..+150 on one scale.

### 24.51 The tutorial's copy after the final dive taken out (Kelvin 2026-10-21 04:39, 04:40)
Asked at 04:39: 「现在把introduction后半截的文案都去掉先，留着占位。我们后续会补更好的文案。」; at 04:40: 「就是final dive 之后的文案吧，应该是。」.

- **What goes:** the four dialogue lines of the outro (§10.2, row E), in both languages: his quote (224–235), her reply (230–236), the soul line (238–247) and the last line (240.5–247). The final dive is where FINAL DIVE stood in tutorial v3 (198–216), now the TIDE-line lesson, which keeps its caption.
- **Placeholders:** each line stays in `IntroductionGuidelines` with empty text (`PLACEHOLDER`) and its beats, place, size and style, so new copy only fills the text in. `GuidingText` draws nothing for an empty caption, not even a plate, and does not prewarm it.
- **Kept:** the opening story, every lesson caption, the requiem, and the closing title (a title, not copy; it reads `K & K` since 04:57, §24.52).

### 24.52 The tutorial's closing title reads K & K (Kelvin 2026-10-21 04:57)
Asked at 04:57, looking at §24.51: 「可以，但是中间的＆不见了。」.

- **Cause:** the finale's text was `K  and  K`, drawn from the LOGO glyph set (`K& AND`, uppercase only), so the lowercase "and" had no glyphs; the HUD combo, on the same spot, showed in the gap.
- **Now:** it reads `K & K`, like the game's name everywhere else (Welcome, the brand row that ends OTHER, the window title), in both languages. The LOGO set is `K& ` (the unused `AND` went).
- **Combo:** from the finale's fade-in to the end of the run the HUD combo and its COMBO label give way, as under the end banner: `GameplayScreen.centreCover()` is the banner's cover, and `IntroductionScreen` raises it with the finale's fade-in and keeps it at 1.

## Appendix A: Contract additions requested from FOUNDATION

These are small; everything else fits ARCHITECTURE as written.

1. **Ui**
   - `uiScale()` (Settings value, or Auto = 1.15 if landscape height < 480 dp, else 1.0).
   - `touchTarget()` = `max(112, dp(48))`.
   - `freeBandTop()` = `top() − 1068` and `freeBandBottom()` = `12 − bottom()`.
   - Extra edge padding folded into the `safe*()` edges.
2. **Fonts**
   - (a) A `UI_LABEL` role (Chakra SemiBold, `spaceX = round(0.10·px)`).
   - (b) `NUMBER` charset adds `A–Z`, space and `·`.
   - (c) Glyph sets for display sizes: `get(Role, refPx, GlyphSet {FULL, DIGITS, WORDS, GRADE, LOGO})`. Sets of ≤ 20 glyphs may exceed the 160 px cap, up to 256 px.
   - (d) `drawDigits(batch, role, cap, long value, int minDigits, boolean grouping, x, y, align, Color color, Color leadingZero, float alpha)` with fixed cells (Mokoto 1.10 × cap; `,` and `.` 0.45; `%` 1.16; Chakra 0.92).
   - (e) Gameplay HUD fonts share one `PixmapPacker` page where possible (RGBA8888; LuminanceAlpha only if verified on device).
3. **GlitchText**
   - Pooled per-element `GlitchState` (level, start, seed).
   - `draw(batch, restRole, cap, text, x, y, align, color, alpha, state, time)`.
   - Levels G1 / G2 / G2-red / G3 (§1.4).
   - Font swap among NUMBER / DISPLAY / DISPLAY_SOFT for roles with swap sets.
   - Sub-UV slices; tear bars.
   - Global `glitchIntensity` and `reduceFlashing` honoured.
4. **Theme.** Every token in §1.2 and §12. Layer palettes indexed by layer with band edges 0 / 200 / 1,000 / 4,000 / 6,000 / 10,935 m and the 75 % thermocline blend. `Theme.depthParams(float m, DepthParams out)` fills colours and scalars.
5. **Draw.** The atlas regions of §2.1, `region(String)`, the separate `noise` texture. `ParticleAtlasFactory` wraps this same atlas.
6. **Backdrop**
   - New calls: `setFocus(x, y)` (heartbeat), `setCameraMotion(vx, vy, parallaxX, parallaxY)`, `setMode(MENU | GAMEPLAY)`, `setBeat(float phase01, boolean barStart)`, `setComboLife(int combo)`, `setDetail(LOW | HIGH)`, `sonar()` → `SonarField`.
   - Built-in shader variants and auto-fallback (§2.4).
7. **SonarField** (new, `ui/`): API in §2.5.
8. **TransitionLayer**
   - Styles `CURTAIN`, `DIVE` (signal curtain close + line-split open), `ASCENT` (the DIVE water walls without art or text, since 2026-10-13), `IRIS` (disc iris).
   - `setCoveredLabel(String)`.
   - Minimum cover 250 ms; the open waits for `onCovered` work to finish.
9. **PlayResult.** Add `short[] deltaHistogram` (27 bins of 10 ms over −135..+135; non-LOST hits only).
10. **RecordOutcome.** Add:
    - `isFirstPlay`, `gradeImproved`
    - `depthBefore` / `depthAfter` (int m)
    - `rankBefore` / `rankAfter` (title + tier)
    - `topTenIndex` (−1 if none)
    - `expBreakdown` (base, firstPlay, newBest, fc, ar, daily, tutorial)
    - `unlocks` (List<String>)
    - `localRank`, `localCount`
11. **ProfileStore / Profile**
    - Profile fields: avatar {shape, pattern, palette, glyph, frame} beside `avatarSeed`; `ratingHistory` (30); `recentPlays` (20); stats {totalPlayMs, fullCombos, allResonance}; `seenChapters`; `calibrated`; `lastPlayDay`.
    - `recordPlay` for the tutorial grants the first-time EXP and marks `tutorialSeen` without a ChartRecord.
    - `localRank(songId, diff)`.
    - `exportCode(id)` / `importCode(String)`.
12. **DeviceSettings** (new, `profiles/device.json`): `musicVolume`, `sfxVolume`, `uiVolume`, `outputs {speaker, wired, bt}` offsets, `activeOutput`, `visualOffsetMs`, `particleQuality`, `backgroundDetail`, `uiScale` (0 = Auto), `edgePadding`, `showFps`.
    - The effective `audioOffsetMs` = the active output's value.
    - If declined, these fields stay in `Profile.settings` and the UI drops only the DEVICE tag.
13. **Settings (diver)** add: `hitSoundMode`, `previewOn`, `menuMusicOn`, `reduceMotion`, `reduceFlashing`, `backgroundArt`, `artOpacity`, `zoneHint`, `approachGlow`, `strayTouches` (AUTO/ON/OFF), `judgementDisplay`, `earlyLate`, `showTimingMs`, `showTimingMeter`, `liveCounters`, `haptics`, `pauseMode`. `backgroundDim` default stays 0.35. (`fcArIndicator`, default on, since 2026-10-18 16:17.)
14. **Rating / Leveling.** Formulas and constants exactly as in §11; grade thresholds as in §12; `Rating.depthOf(R)`, `Rating.rankOf(depthM)`, `Rating.microGoal(...)`.
15. **AudioService.** `duckMenu(float level, float sec)`; preview fade-in 0.6 s; `playFile(FileHandle, boolean loop)` with clock access for calibration; `resultMusicStartMs`; hit priming; `uiVolume` slot [P2].
16. **Library manifest.** Chapters gain `depthBottom` and `uncharted`; titles SHORELINE / TWILIGHT / MIDNIGHT / BLACKWATER / TRENCH with zh names (§1.2); depths per §3.1. Optional per-song `map {nudgeX, nudgeY}`.

## Appendix B: Build order and priority by workstream

| Workstream | P0 (first playable) | P1 | P2 |
|---|---|---|---|
| FOUNDATION | viewport and Ui, Theme, Fonts (roles, sets, digits), GlitchText, Draw atlas, Backdrop shader + LOW, SonarField, TransitionLayer styles, Rating/Leveling, AudioService slots | bilingual subtitles, DeviceSettings | UI SFX synthesis |
| GAMEPLAY | draw order, HUD layouts (middle band + bands), judgement feedback, lines, notes, lane feedback, start/countdown, pause (hold), end, hit-sound call | approach glow, hit error meter, live counters, combo life, Abyss pressure | — |
| FX | .p rewrite (RESONANCE / ECHO / hold / seal / LOST ×), pools, quality levels, auto-drop | — | — |
| MAP | world, nodes, path, camera physics, card/sheet, chips/lens, preview dwell, HUD, gauge, Descent, return | walls, filter + list, micro-goal, edge arrows, YOU line, reveal sweep, water touch, jelly lag, peek | landmarks, critters, headlight, leader line, panel life |
| RESULT | layouts, choreography, score decode, stamp, skip, music, variants | idle interactions, offset hint | ghost numeral, SFX |
| SHELL | Welcome, profile create/rename/switch/delete, avatar (simple), settings, calibration, first-run, tutorial copy | full avatar editor + unlocks, export/import, LOG tab, dive-profile chart, verify, visual offset test | haptics |

This is the build plan of the first redesign. **Replaced 2026-10-12** for GAMEPLAY: "HUD layouts (middle band + bands)" and "pause (hold)" are now the corner layout (§5.2–§5.4) and the double-tap pause with menu B (§5.12).

## Appendix C: Open decisions for the owner

1. Which K is the ground signal (cyan) and which is the sky signal (white). Also per-layer story lines and the Welcome tagline copy.
2. Chapter assignment of songs (proposed: §3.1), and whether uncharted layers show from day one (proposed: yes).
3. **Mokoto licence.** If it cannot be cleared:
   - Rubik Glitch takes the logo, stamp, DESCEND and screen-title roles.
   - Chakra Petch Bold with fixed cells takes the NUMBER role.
   
   The layout is unchanged; only the swap-glitch is lost.
4. Whether to ship a hit-sound file and a real result track. The slots and fallbacks are already in place.

## Appendix D: QA matrix (every screen)

**Sizes:**

| Size | Aspect |
|---|---|
| 2400×1080 | 20:9 |
| 2340×1080 | 19.5:9 |
| 1920×1080 | 16:9 |
| 1280×800 | 16:10 |
| 2160×1440 | 3:2 |
| 2048×1536 | 4:3 |

Each one also with a simulated left cutout inset of 80 px.

**Checks:**
- **Replaced 2026-10-12:** "No persistent HUD inside y 12–228 or 852–1068; the pause control is outside both zones" is false at 16:9 / 20:9 by the owner's corner decision (§5.2). Check instead: every corner element inside the `Ui.safe*()` edges (the pause ring against the cutout-safe edges it is placed from), no two HUD elements overlapping, the four corner alphas at 1.00 while the lines rest at their default positions (UI 1.0 and 1.3), the pause box ≥ `P` × UI scale, and notes under the corners still judged (a real-input bot hits them).
- Every hit rect ≥ `P`.
- Essential text cap ≥ 20 × uiScale; map labels ≥ 18.
- Gameplay ≤ 10 draw calls (`GLProfiler`); as built 6–9 while running (§14).
- 0 allocations per frame during a song (allocation tracker).
- Layout class switches exactly at aspect 1.6 and at a free band of 104.
- Result skip: one tap finishes within about 1.2 s; buttons enable 250 ms later.
- Glitch at intensity 0 shows no animated glitch. Reduce flashing caps flashes at α .10.