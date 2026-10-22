# K&K 资源槽位（ASSET_SLOTS）

本文说明可以往 `assets/` 里放哪些文件（音效、音乐、图片、字体、粒子效果、歌曲、章节），放在哪里，以及缺少时游戏怎么处理。内容以当前代码为准（主要在 `audio/AudioService`、`library/SongLibrary`、`ui/Fonts`、`fx/HitFx`）。谱面文件本身的格式见 `docs/CHART_FORMAT.md`。

总则：

* 所有路径都相对于 `assets/`。
* 除了歌曲谱面以外，**每个槽位都是可选的**。缺少文件时，对应的声音保持安静，对应的画面只用程序绘制的效果，其他功能不受影响。
* 槽位文件名请用小写，并且与下文完全一致（Android 和 Linux 的文件名区分大小写）。
* 改动 `assets/` 之后，用 Gradle 重新构建一次。构建会自动重写 `assets/assets.txt`（游戏用这份清单查找歌曲文件夹）。

---

## 1. 声音

### 1.1 打击音效：`assets/Sounds/`

目前**不附带**任何音效，这个文件夹只预留文件名。详细规则写在 `assets/Sounds/README.txt`。

| 文件 | 何时播放 | 缺少时 |
|---|---|---|
| `hit.ogg` / `hit.wav` | 判定为 RESONANCE 或 ECHO 时（LOST 不播放）。设置“打击音模式”为“判定区内任意触摸”时，没打到音符的触摸也播放 | 不播放。设置里的打击音量滑块显示 `NO HIT SOUND INSTALLED` 并禁用 |
| `hit_echo.ogg` / `.wav` | ECHO 判定时代替 `hit.*` | ECHO 也用 `hit.*` |
| `hold_end.ogg` / `.wav` | STREAM（hold）完成时 | 不播放 |
| `mine.ogg` / `.wav` | 碰到 MINE 时，以及每一次接触 LANE MINE 时（每次接触都播放）。碰到水雷从不播放 `hit.*` | 不播放 |

* 每个槽位先找 `.ogg`，再找 `.wav`。
* 四个文件在第一次需要打击音时一起加载（进入游戏时，在遮罩下完成），各用音量 0 预播一次，避免第一次真正播放时的延迟。
* 在触摸事件里、判定之后立即播放，不等下一帧。音量是设备设置里的 SFX 音量（默认 80 %），为 0 时不播放。
* 建议：时长 0.3 s 以内，单声道，44.1 kHz 或 48 kHz，去掉开头的静音（开头的静音会直接变成延迟）。Android 的 SoundPool 对解码后的单个音效有约 1 MB 的限制。

### 1.2 结算音乐（每首歌 / 全局）

详细规则写在 `assets/Audio/README.txt`。结算画面按下面的顺序找音乐，用第一个找到的：

1. 这首歌自己的 `assets/Charts/<歌曲 id>/result.ogg` / `.mp3` / `.wav`。从 manifest 里这首歌的 `resultMusicStartMs`（毫秒，默认 0）开始播放。
2. 全局的 `assets/Audio/result.ogg` / `.mp3` / `.wav`，从 0 开始。
3. 临时备用：`assets/Introduction/Introduction.wav`（教程音乐），从 0 开始。
4. 都没有：安静。

播放方式：在升起（ASCENT）转场遮住画面时加载并静音开始，转场打开时用 1.2 s 淡入到“音乐音量 × 0.8”，循环播放；升级时短暂压低。按 CONTINUE 返回地图时 450 ms 淡出；按 RETRY 立即停止。

`resultMusicStartMs` 只作用于第 1 种（这首歌自己的 `result.*`），对全局音乐和备用音乐无效。写法见 `docs/CHART_FORMAT.md` §12.2。

### 1.3 菜单音乐

| 文件 | 说明 |
|---|---|
| `assets/background.ogg` / `.mp3` / `.wav` | 菜单背景音乐，循环，0.6 s 淡入。用于欢迎、首次设置、地图、玩家档案和设置画面；校准画面不播放（它播放自己的节拍声）。地图上选中歌曲、开始试听时，菜单音乐被压低。玩家可以在设置里关掉菜单音乐 |

### 1.4 歌曲音乐和试听

每首歌的音频放在歌曲文件夹里，命名为 `<歌曲 id>.ogg` / `.mp3` / `.wav`（见 §5）。卡片试听使用同一个文件：从 `previewStartMs` 放到 `previewEndMs`（写在歌曲文件夹的 `song.json` 或 manifest 的歌曲条目里，见 `docs/CHART_FORMAT.md` §12.2；默认从时长的 30 % 开始放 20 s；ogg 读不出时长，默认从 0 开始，所以建议为它写上起点），0.6 s 淡入，到终点前 0.8 s 淡出，然后从起点重新开始。起点小于 0 时先静音，等到歌曲开头；终点超过音乐结尾时放完后静音，等到终点，不跳回开头。

校准用的节拍声由程序合成，不需要文件。

---

## 2. 图片

### 2.1 全屏背景图（image hooks）

这些图片叠在程序绘制的海水背景上：按“铺满后裁剪”的方式缩放（不会拉伸变形），并朝当前深度的颜色着色。缺少时只显示程序背景。

| 文件 | 画面 | 不透明度（“背景图透明度”为 100 % 时） |
|---|---|---|
| `assets/welcome.png` | 欢迎画面 | 0.35 |
| `assets/Blue.png` | 玩家档案、设置 | 0.15 |
| `assets/ResultPage.png` | 结算 | 0.20 |
| `assets/Charts/<歌曲 id>/bg.png`（或 `.jpg` / `.jpeg`） | 这首歌的游戏画面 | 0.15 |
| `assets/ChartVisualizer.png` | 游戏画面（这首歌没有 `bg.*` 时） | 0.15 |

* 玩家设置里的“背景图”开关和“背景图透明度”（0–100 %）作用于上表所有图片：关掉时都不显示；透明度乘到上表的数值上。
* **地图没有图片槽位**，完全由程序绘制（DESIGN 里写的地图 `Blue.png` 视差层没有实现）。首次设置和校准画面也不使用图片。
* 游戏和结算画面还会自动用曲绘做一层很淡的颜色底（把曲绘缩到 16 × 16 再放大的模糊色块），不需要额外文件。
* 建议尺寸：至少 1920 × 1080，主体放在中间（不同屏幕比例会裁掉边缘）。

### 2.2 曲绘

`assets/Charts/<歌曲 id>/jacket.png`（或 `.jpg` / `.jpeg`）。没有 `jacket.*` 时，取文件夹里按文件名排序的第一张图片，但跳过 `bg.*`；所以现有歌曲的 `1.png`、`2.png` 等仍然可用。曲绘用于地图节点缩略图、歌曲卡片、游戏画面的颜色底和结算画面。建议用正方形。不是正方形的曲绘可以在谱面编辑器里选 16:9 的区域和圆形的区域（写进歌曲文件夹 `song.json` 的 `jacket16x9` / `jacketCircle`，见 CHART_FORMAT §12）：16:9 的用在歌曲卡片、结算画面和颜色底，圆形的用在地图节点、转场和个人页小图；没选时取图片中间。

---

## 3. 字体：`assets/fonts/`

| 文件 | 用途（`ui/Fonts.Role`） | 授权 |
|---|---|---|
| `ChakraPetch-Regular.ttf`、`ChakraPetch-SemiBold.ttf`、`ChakraPetch-Bold.ttf` | 界面文字 `UI_REGULAR`、`UI`、`UI_LABEL`、`UI_BOLD`；Bold 也用于数字 `NUMBER` | SIL OFL，许可文本 `OFL-ChakraPetch.txt` |
| `NotoSansSC-Medium.ttf` | 中文 `CJK`（自动用于中文字符） | SIL OFL，许可文本 `OFL-NotoSansSC.txt`。这是裁剪过的子集：GB2312 里的字（含希腊字母和日文假名），加上 Latin-1、界面用到的符号，以及 GB2312 没有的 ς、长音 ー 等 38 个字（2026-10-21）。子集外的字（例如生僻字）显示不出来；需要时换一个更大的子集文件，文件名保持不变 |
| `RubikGlitch-Regular.ttf` | 标题、标志、评级印章、DESCEND、判定字（`DISPLAY`、`DISPLAY_SOFT`、`DISPLAY_OUTLINE`），警告和章节名 `RUBIK` | SIL OFL，许可文本 `OFL-RubikGlitch.txt` |

**不再使用 Mokoto 字体**（2026-10-13，Kelvin 选定）：Mokoto Glitch 是收费字体，文件里写着 “All rights reserved”，不能放进公开仓库。按 DESIGN 附录 C 的预案替换：Rubik Glitch 接管标题、标志、印章、DESCEND 和判定字，Chakra Petch Bold 接管数字。每种用途对应的字体文件只写在 `ui/Fonts.java` 的 `Role` 枚举里。失去的只有三款 Mokoto 字形等宽才能实现的“字形切换”故障效果，其他故障效果（色差、切片、撕裂条）不变。

---

## 4. 粒子效果：`assets/ParticleEffects/`

打击特效是标准的 libGDX 粒子编辑器 `.p` 文件：

| 文件 | 用途 | 发射器名称 |
|---|---|---|
| `resonance.p` | RESONANCE 爆发：清晰的声呐环 + 柔光晕 + 白色核心闪光 + 9 条火花（12 个四边形） | `halo`、`ring`、`core`、`sparks` |
| `echo.p` | ECHO 爆发：环 + 稍后出现的柔和“回声环” + 6 条火花（8 个四边形） | `echo ring`、`ring`、`sparks` |
| `hold.p` | 按住 STREAM 时持续上升的光点流 | `stream` |
| `lost.p` | LOST 的红色 ×，四条短线，不移动 | `x 45`、`x 225`、`x 135`、`x 315` |

修改时注意：

* **图片路径不是 PNG 文件**，而是共享图集里由程序生成的区域名：`ring_soft`、`ring_thin`、`soft_dot`、`streak`。请保持这些名字。用粒子编辑器打开时，把编辑器的图片目录指向这些区域导出的 PNG 即可。
* **发射器名称不要改**：代码按名称找到发射器来着色和降级。
* **颜色在加载时被代码覆盖**：按 `ui/Theme` 的颜色重新着色（RESONANCE、ECHO、地面线 / 天空线颜色、LOST 红色），所以在 `.p` 里改颜色不起作用。改颜色要改 `Theme`。
* 天空线的特效是加载时把地面版本上下翻转得到的，不需要单独的文件。
* 低画质会去掉 `core`、`halo`、`echo ring`，火花减半，光点流降到每秒 12 个；关闭画质时没有打击特效。
* 性能预算：同屏所有打击特效合计不超过 100 个四边形（每秒 12 次打击加 4 条按住的 STREAM）。加大粒子数量可能超出预算；超出时代码会自动把新的爆发降成低画质版，再降成只有一个环。
* `resonance.p`、`echo.p`、`hold.p` 只用于 DROP / STREAM。TIDE 命中没有点状特效（没有爆发、环和火花）；错过或断开的 TIDE 仍然在窗口中间显示 `lost.p` 的 ×。
* 下面这些由代码直接绘制，没有 `.p` 文件，改样子要改代码：碰到水雷的冲击环（`gameplay/FeedbackLayer`）；TIDE 的潮水（`gameplay/TideWash`：从线上浮起的光带、退潮和短暂变暗，TIDE STREAM 按住时不断浮起的细光带；判定线本身向场内抬起 4 px 并发光，320 ms 内复原，在 `gameplay/FieldRenderer`）；DROP / STREAM 的水波纹（`fx/WaterRings`，RESONANCE 五个圆环、ECHO 一个细环、按住时的小圆环，用共享图集里程序生成的 `ring_water` 各级圆环）。
* 水波纹不算在上面的 100 个四边形里，它有自己的上限：同时存在的环估计超过 60 个时，新的 RESONANCE 会少画几个环（`HitFx.RING_GUARD`）。

---

## 5. 添加一首歌

1. 新建文件夹 `assets/Charts/<歌曲 id>/`。**文件夹名就是歌曲 id**，存档里的成绩按它记录，以后改名会丢失这首歌的成绩。
2. 放入音频 `<歌曲 id>.ogg`（或 `.mp3` / `.wav`），文件名与文件夹同名。
3. 放入谱面：`Surface.json`、`Shallow.json`、`Deep.json`、`Abyss.json` 中至少一个（格式见 `docs/CHART_FORMAT.md`）。歌名、曲师、画师、谱师显示 `Shallow.json` 里的值（没有就用 `Surface.json` 的）。
4. 放入曲绘 `jacket.png`（正方形）。
5. 可选：游戏背景 `bg.png`；结算音乐 `result.ogg`。
6. 在 `assets/Charts/index.json` 某个章节的 `songs` 列表里加上歌曲 id；需要设置试听段（起点和终点）、结算音乐起点或微调地图位置时，改用对象写法（试听段也可以写在歌曲文件夹的 `song.json` 里，见 `docs/CHART_FORMAT.md` §12.2）：

   ```json
   "songs": [
     "Song 1",
     { "id": "My New Song", "previewStartMs": 42000, "previewEndMs": 62000, "resultMusicStartMs": 15000,
       "map": { "nudgeX": 0, "nudgeY": 0 } }
   ]
   ```

   不写进 manifest 也能玩：没有列出的歌曲文件夹按名称排序，自动追加到最后一个非“未探明”的章节。
7. 用 Gradle 重新构建（更新 `assets/assets.txt`），然后在地图上检查节点、卡片、试听和游玩。

列表中的顺序就是地图上节点从上到下的顺序，节点左右交替排列，章节的高度随歌曲数量增加。节点重叠时用 `map.nudgeX` / `map.nudgeY`（地图世界单位，y 向上为正）微调。

---

## 6. 添加一个章节

章节是地图上的一个深度层，在 `assets/Charts/index.json` 的 `chapters` 数组里，按从浅到深的顺序排列：

```json
{
  "id": "c4", "title": "BLACKWATER", "titleZh": "黑水层", "subtitle": "Chapter IV",
  "depthMeters": 4000, "depthBottom": 6000, "uncharted": false,
  "songs": ["My New Song"]
}
```

1. `id` 不能与其他章节重复。
2. 写英文名 `title`、中文名 `titleZh`、副标题 `subtitle`。目前 `titleZh` 只在语言设为中文时的结算画面上显示，地图上显示英文名。
3. `depthMeters`（顶部）和 `depthBottom`（底部）以米为单位，和前后章节首尾相接。背景颜色按深度自动选择（分界：0、200、1000、4000、6000、10935 m）；游戏中的背景深度是“章节顶部 + 章节深度范围 × 难度比例”（Surface 10 %、Shallow 35 %、Deep 60 %、Abyss 85 %）。
4. `uncharted`：`true` 时这一层在地图上画成“未探明”区域，**不显示歌曲节点**。有歌的章节必须写 `false`。目前的 c4 BLACKWATER 和 c5 TRENCH 是未探明的占位章节；要开放其中一层，把 `uncharted` 改成 `false` 并填入 `songs` 即可。
5. 构建并在地图上检查。

`schemaVersion` 保持 1。ARCHITECTURE §16 计划中的路线结构（主线、支线、联动、单曲，schema 2）在本版本中还不能读取。

---

## 7. 教程

教程使用 `assets/Introduction/`（与 `Charts/` 同级）：谱面 `Surface.json`，音乐 `Introduction.wav`。文件夹名可以在 manifest 的 `"tutorial"` 里修改。教程不属于任何章节：在地图上它是最上面的 LAUNCH POINT 浮标（选中后按 DIVE IN 进入），地图右上角也有教程按钮。`Introduction.wav` 同时是结算音乐的临时备用（§1.2）。

教程谱面 `Surface.json` 是 Kelvin 自己写的（2026-10-17，102 个音符），字幕类 `core/.../gameplay/IntroductionGuidelines.java` 里每条字幕的拍点和位置是按这份谱面手工排的：字幕不能挡住还没判定的音符，所以**改谱面时要一起检查字幕**，步骤见 `docs/DEVELOPER.md` §6.4。原来的生成脚本不再管这两个文件。替换音乐 `Introduction.wav` 时，节拍（BPM）和时长要和谱面一致。
