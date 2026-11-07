# 开发人员手册（DEVELOPER）

本手册说明代码在哪里、每个模块做什么，以及常见的改动该改哪个文件。内容以 **0.1.0-beta.1**（2026-10-21）的代码为准，手册里的类名都用脚本和代码树对过（见 §8）。

* 画面和交互的规范：`docs/DESIGN.md`（英文）。代码结构和约定：`docs/ARCHITECTURE.md`（英文；§21 是 2026-10-12 第二轮改动在代码里的位置索引）。两份规范对代码结构有分歧时以 ARCHITECTURE 为准，对外观有分歧时以 DESIGN 为准。
* 开发日志（和原版的对比，以及之后每一轮改了什么、修好了什么）：`docs/CHANGES.md`。谱面格式：`docs/CHART_FORMAT.md`（v5；旧格式和各条规则的细节见 `docs/CHART_FORMAT_v4.md`）。地图格式：`docs/MAP_FORMAT.md`。资源槽位：`docs/ASSET_SLOTS.md`。
* 怎么打开和运行工程：仓库根目录的 `README.md`。

---

## 1. 工程一览

| 路径 | 内容 |
|---|---|
| `core/src/main/java/org/kelvinizer/` | 全部游戏代码（谱面编辑器也编译这里的代码；§4 逐个包说明） |
| `android/src/main/java/org/kelvinizer/android/` | Android 启动器（`AndroidLauncher`）。清单文件 `AndroidManifest.xml` 和图标目录 `res/` 在 `android/` 目录下 |
| `assets/` | 资源：谱面和曲绘（`Charts/`）、教程（`Introduction/`）、地图数据（`maps/`）、字体（`fonts/`）、开源许可证全文（`licenses/`）、粒子（`ParticleEffects/`）、音效和结算音乐槽位（`Sounds/`、`Audio/`）、背景图 |
| `docs/` | 文档 |
| `build.gradle`、`settings.gradle`、`gradle.properties`、`core/build.gradle`、`android/build.gradle` | Gradle 构建。游戏版本 `projectVersion`（现在 0.1.0-beta.1）和依赖的版本号都在 `gradle.properties`（libGDX 1.14.1、ShapeDrawer 2.5.0） |

构建要点：

* 代码按 **Java 11** 编译（`sourceCompatibility = 11`）：不能用 record、switch 表达式、text block 等更新的语法。运行 Gradle 和游戏用 JDK 21。
* 依赖：libGDX（含 FreeType）、ShapeDrawer、Jackson **只用流式核心** `jackson-core`（不能用 databind，原因见 `profile/PlainJson`）。
* 构建时 `generateAssetList` 任务把 `assets/` 下的文件列表写进 `assets/assets.txt`（git 忽略）。曲库在不能列目录的场合（jar 里的 classpath、Android）靠它找文件，所以**加了资源要重新构建一次**。
* 仓库里没有单元测试。开发时用的测试、截图和性能工具不在仓库里。

---

## 2. 代码约定（摘要，完整的见 ARCHITECTURE §0）

* **坐标**：世界坐标固定以 1920 × 1080 为最小可见区域（`ExtendViewport`），较长的一边向外延伸。游戏场地另外按 `gameplay/Field` 拉伸到整个可见区域（谱面坐标 → 世界坐标；谱面按 1920 × 1200 的参考画面写，2026-10-19 起），四角的 HUD 不拉伸。安全边（刘海、圆角、设置里的额外边距）用 `ui/Ui` 的 `safeLeft()` 等取得。界面元素按 `Ui.uiScale()` 缩放。
* **服务**：字体、绘图、背景、转场、音频、曲库、存档都由 `Main` 创建并持有，画面通过 `game.xxx()` 取用。GL 对象（贴图、着色器、帧缓冲）**不能放在 static 字段里**（Android 会丢失 GL 上下文）。
* **不在每帧分配内存**：游戏、渲染和特效代码在加载后不再 `new`，用预先分配的数组和对象池。
* **文字**：所有文字都通过 `ui/Fonts` 绘制；含中文的字符串会自动换到中文字体（`NotoSansSC-Medium.ttf`）。
* **按钮在抬起时生效**（`ui/Pressable`）；判定用触摸事件自己的时间戳。
* **注释**：类注释先写一两句中文，再写英文；方法和行内注释用英文（Kelvin 的规则，现在每个类都是这样）。注释写现在的行为和原因；照 Kelvin 的决定定下来的数字，后面注明「Kelvin 日期 时间」，方便以后查开发日志。
* **排版**（`.editorconfig`）：4 个空格缩进，UTF-8，LF 换行，行尾不留空格，文件末尾留一个换行。**每行最多 120 个字符**，代码和注释都算。
* **长行怎么拆**：语句的续行比开头多缩进 4；`&&`、`||`、`+`、`?`、`:` 这些运算符放在新行的开头；方法声明的参数一行放不下时，续行和左括号对齐；一行写不下的 `if` / `for` / `while` 加上大括号写成多行；长字符串拆成几段用 `+` 连（编译器会合成一个常量）。
* **版本号**三处要一致：`Params.VERSION`（游戏里设置 → 其他最下面一行显示）、`gradle.properties` 的 `projectVersion`、`android/build.gradle` 的 `versionName`；发新的安卓包时 `versionCode` 加 1。内测版写成 `0.1.0-beta.N`。
* **不留死代码**：用不到的字段、方法和整类直接删掉，不要「先留着」。谱面编辑器和地图编辑器会编译游戏的源码，删公开的类或方法前先在这两个工程里搜一下有没有用到。

---

## 3. 从启动到结算：一局的流程

1. `android/AndroidLauncher` 创建 `Main`。`Main.create()` 建好所有服务，读曲库（`SongLibrary.load()`，只读谱面头）和存档（`ProfileStore`）。
2. 欢迎画面 `screens/WelcomeScreen` → 第一次启动走 `screens/FirstRunScreen` 再进教程，之后进地图 `map/MapScreen`。
3. 在地图上点歌曲、选难度、按 DESCEND：`Main.startGameplay(song, diff, autoplay)` 播放 DIVE 转场（`transition/TransitionLayer`）。转场盖住画面的同时，后台线程（`kk-chart-loader`）用 `SongLibrary.loadChart` → `elements/ChartMapper` 读出完整的 `elements/Chart`；两件事都完成后，回到 GL 线程调用 `gameplay/GameplayScreen.begin(...)`。
4. 游戏中：每次触摸先交给 `gameplay/GameEngine`（判定、计分，输出 `GameEvent`），然后才交给 `gameplay/PauseControl`；判定了音符、重新按住长条或按在进行中的 TIDE STREAM 上的按下不算暂停点击（碰水雷的按下也不算）。手机的返回键直接暂停。每帧 `GameEngine.update()` 之后，`GameplayScreen` 读取事件，驱动 `GameplayHud`、`FeedbackLayer`、`fx/HitFx`、`TideWash`、`FieldRenderer`，再按 DESIGN §5.1 的顺序绘制：背景 → 判定区提示、轨道分隔线、潮水冲刷 → 连击数和判定字 → 判定线、TIDE STREAM 光带、LANE MINE 光柱、残影和音符 → 加法混合的光和打击特效 → 教程说明、倒计时、结束横幅 → 最上层（暂停遮罩、四角 HUD、暂停菜单）。
5. 歌曲结束（最后一个音符过去、音乐也放完之后再等 1.5 s，`GameEngine.finishAtMs()`）：`GameEngine.fillResult` 生成 `play/PlayResult`，`Main.showResult` 用 `ProfileStore.recordPlay` 记录成绩（得到 `play/RecordOutcome`），在 ASCENT 转场下准备 `screens/ResultScreen`。
6. 结算画面的 CONTINUE 用 IRIS 转场回到地图，地图播放返回动画。
7. 教程是 `gameplay/IntroductionScreen`（继承 `GameplayScreen`），由 `Main.startTutorial()` 打开，谱面是 `assets/Introduction/Surface.json`。

---

## 4. 包和类

下面列出 `core/src/main/java/org/kelvinizer/` 里的每个包和每个类（不含内部类），以及两个启动器。“包内”表示只在本包内可见。

### 4.1 根包 `org.kelvinizer`

| 类 | 作用 |
|---|---|
| `Main` | 服务中心：创建并持有所有共用服务（`ui/UiKit` 里的绘图、字体、背景、转场、故障字、提示条、头像，以及 `SongLibrary`、`ProfileStore`、`AudioService`、`HitFx`），缓存所有画面，负责画面切换（`transitionTo`、`startGameplay`、`startTutorial`、`showResult`）和让设置生效（`applySettings`）。 |
| `Params` | 全局常量：游玩场地和谱面的参考画面 1920 × 1200（2026-10-19 起；界面视口仍是至少 1920 × 1080，`Ui.REF_H`），判定窗口和权重（`play/Judgement` 里定义的别名），水雷窗口 `MINE_WINDOW_MS`（±133.33 ms，只有按下才算）、轨道水雷提前量 `LANE_MINE_LEAD_MS`（0，到线才封锁）、共鸣保护 `RESONANCE_GUARD_MS`（150 ms），判定区半高 `JUDGE_ZONE_HALF`（480，上下各约半屏；7.184–7.188 是 160，只认落点附近）和画出来的判定带 `JUDGE_BAND_HALF`（120），结束延迟 `FINISH_DELAY_MS` 等。只放常量。 |

### 4.2 `audio`：声音和歌曲时钟

| 类 | 作用 |
|---|---|
| `AudioService` | 菜单这边的声音：菜单音乐（循环、淡入、选歌时压低）、地图试听、结算音乐、打击音效槽位（`hit.*`、`hit_echo.*`、`hold_end.*`、`mine.*`）和校准时的播放。每帧调用 `update`。 |
| `HitOutput` | 打击音的专用输出接口：每一下排到指定的时刻才响（`play(id, volume, atNanos)`），暂停时取消还没响的（`cancelPending`）。安卓 8.0 起由 `android/AndroidHitOutput` 提供，`AudioService.setHitOutput` 接上；没有时打击音照旧走 libGDX `Sound`。 |
| `HitMixer` | `HitOutput` 的混音部分（纯 Java，可以在电脑上测试）：按输出线程给的时间戳（哪一帧在什么时刻放出来）把每一下放到对应的帧，来不及的立刻响；同一个声音 20 ms 以内的几下只响一下（`MERGE_MS`，多押合成一声）。安卓上歌曲也在这里混（`songOpen`、`songPlay`、`songPause`、`songSeek`、`songHeard`）：按段落放，每段从哪一帧开始放是算好的，暂停和跳转对到采样，各有 5 ms 淡出淡入，音乐和打击音加起来接近满幅时柔和压缩。 |
| `MusicOutput` | 平台自己的放歌输出接口（`open(file)` 返回一个 `Music`，`startDelayMs`）。安卓 8.0 起由 `android/AndroidHitOutput` 提供，`Main` 交给 `AudioService.setMusicOutput`，`GameplayScreen` 再交给 `SongClock.setMusicOutput`；打不开时照旧用 libGDX `Music`。 |
| `MixerMusic` | 混在 `HitMixer` 里放的歌曲，套上 libGDX 的 `Music` 接口，`SongClock` 照旧用它；`getPosition` 是此刻真正放出来的那一采样。不支持循环和声像。 |
| `PcmBuffer` | 解码出来的歌曲（16 位立体声，按 32768 帧一块存），解码线程往后加，混音线程同时读。 |
| `Resampler` | 把解码出来的声音转成输出的采样率（带窗的 sinc 插值，32 个点）；采样率相同时原样通过。 |
| `ClickTrack` | 校准用的 120 BPM 节拍声，第一次使用时合成，缓存为 `cache/calib_120.wav`。 |
| `SongClock` | 以音频位置为准的歌曲时钟：在音频锚点之间用 `System.nanoTime()` 插值，处理开场留白、暂停和恢复；MP3 的解码延迟在这里扣掉；测量设备开始出声的延迟（存在 `DeviceSettings.musicStartLatencyMs`；还不知道时在倒计时里静音试播测一次），提前这么多开播，开播后 1 秒内不跟随还在跳的位置。暂停后继续：安卓上倒数时音乐先静音地放（`startCountIn`）：从暂停点前 3 秒放起，时间仍停在暂停点，时钟跟着这段音乐走（`countInMs`、`countInLeftMs`），到暂停点时由 `GameplayScreen` 继续，音量一下子回到原样，谱面接着走时音乐已经在放；没有倒数的继续照旧（安卓上先把音乐跳回暂停的位置）。继续后谱面和音乐有偏差时最多快或慢 10% 慢慢追上，不再一下子跳过去（`RESUME_SLEW`）。安卓 8.0 起歌曲走游戏自己的输出（`setMusicOutput`），这时开始延迟是已知的，不再测，也不用等它稳定；Logcat 标签 `KKSync` 记下暂停、倒数和继续后的偏差。 |
| `WavDurationUtil` | 只读 WAV 文件头得到时长（试听起点的默认值、歌曲长度）。 |
| `Mp3Info` | 只读 MP3 文件头（Info/Xing 头和 LAME 标签）得到时长，以及电脑上解码后开头多出来的延迟；`SongClock` 用它把 MP3 对齐到谱面。安卓按 Info 头里的目录表跳转，会差到一秒左右，所以安卓上带目录表的恒定码率 MP3 用 `seekableCopy` 做的副本播放（只清掉目录表那一位，缓存在本地的 `cache/music/`，和原文件对不上时重写）。 |

### 4.3 `elements`：谱面数据模型（纯数据，加载后不变）

| 类 | 作用 |
|---|---|
| `Chart` | 一张可玩的谱面（v2 模型）：轨道、轨道模式（`laneLimit()`，0 = 无限轨）、判定线几何、按轨道和线预先建好的音符索引、漩涡（`veils()`）、悬浮键（`floatNotes()` 水滴和水流、`floatDrags()` DRAG，7.185；悬浮水雷和长水雷在 `mines()` 里，7.186）；内部类 `Chart.LoadReport` 记录加载时修正、丢弃的内容和 LANE MINE 冲突。`playable(n)`：引擎要判的键（不是假键）。同一拍的悬浮键排在线上的键后面，从左到右；它们不进浪花组、也不和别的键一起判。定轨 / 无轨分段（7.184）：`fixedLanesAt(beat)` 那一拍的轨道数（0 = 无轨），`fixedLaneX(j, k)` 第 j 条轨道的中心。自定义线条（7.187）：`strokes()`，按文件里的顺序，引擎不读。 |
| `ChartMapper` | 读写谱面的入口：有 `"format": 5` 的文件交给 `ChartFormatV5`，其他交给 `LegacyChartFormat`；两边都填同一个原始模型，再由 `build` 生成 `Chart`（轨道、音符清理、判定线、漩涡、加载报告，不干净时记一次日志）。`read(FileHandle)` 自动读同一文件夹的 `song.json`。写出 v5，无轨谱面（`Chart.laneless()`）写 v6：`toJson(chart)` 带 `"song"`（单独存放的谱面，能原样读回），`toJson(chart, false)` 不带（歌曲文件夹里的谱面）。v6 文件里的横坐标从屏幕中线量起，读入时用 `absoluteX` 按双精度加 960。 |
| `ChartFormatV5` | 谱面格式 v5（2026-10-27）的读写：默认值不写，键名统一，事件写成 `beats` / `values` / `ease`，一个音符一行。 |
| `LegacyChartFormat` | 旧格式 v1–v4 的读取：把 v1 精确转换成 v2（20 条原始轨道 → 上下两条线各 ±1..±5），v1、v2 的 y 值乘 10/9 换到 1200 高的画面（`toFrame1200`），v1、v2、v3 的判定线以 600 为中心拉长 36/35 换到 1080 / 120（`toLines1080`）。只读不写。 |
| `SongInfo` | 歌曲文件夹 `song.json` 里整首歌共用的值：歌名、曲师、画师、BPM、变速、offset；`mergeInto` 写回时保留文件里的其他键。 |
| `ChartUpgrade` | 一次性把旧谱面转换成 v5 的桌面工具：转换前后逐项比对，一致才写入，同时写 `song.json`，保留谱面编辑器的键（`editor`，音符、叠加层和判定线事件上的 `rel` / `flow` / `link` / `grp`，`EVENT_KEYS`；同一拍的几个无轨键按 x 最近的对上）。谱面编辑器的无轨工作文本（`"laneless": true`）写成 v6。`--laneless [--check] <歌曲文件夹>...`（`toLaneless`、`lanelessFolder`）把 v5 谱面转成无轨的 v6：每个键取轨道给的 x，读回逐项比对（轨道模型以外），一致才写。 |
| `Events` | 一段带缓动的关键帧：`MovementEvents`、`RotationEvents`、`DisappearEvents`、`HeightEvents`、轨道的宽度和透明度事件、`LineEvents` 都用它。 |
| `Floating` | 悬浮键在哪里、多大、填了多少（2026-11-06，CHART_FORMAT §11）：写的中心 `x`（从屏幕中线量）、`y`（0–1200，从下往上，默认 600）、直径 `size`（`defaultSize(类型, 长度)`：水滴、水流 250，DRAG 190，水雷 190，长水雷 220，7.188 起）、从哪一拍显示 `appear`、四组事件（`xEvents` / `yEvents` / `sizeEvents` / `fillEvents`）和 DRAG 的 `chain`。`xAt` / `yAt` / `sizeAt` 每个事件结束后停在结束值；`fillAt(拍, 判定拍)` 是填充（0 正好满，等于直径是空的，负数溢出），没有填充事件时从出现到判定拍匀速填满、之后接着溢出；`discDiameter` 是画出来的颜色圆的直径 \|直径 − 填充\|。不可变，建好后不分配内存。 |
| `JudgementLine` | 两条判定线的高度、全局轨道缩放（`laneScaleAt`）、镜头平移（`cameraFrameAt`，中轴线左右移动），以及让它们动起来的 `LineEvents`。旧的窗口左右边界读取时换算成缩放和平移（CHART_FORMAT_v4 §3.5）。叠加层（2026-11-05，CHART_FORMAT §10.2）：`upperLayers` / `lowerLayers` / `cameraLayers` 加在线高和镜头上（`layersOf(Line)`、`hasLayers()`）。 |
| `Lane` | 一条轨道：id 负数在左，0 只能是插入的轨道；宽度和透明度可以随时间变化；`implicit()` 是无限轨加载时自动补上、存档时不写出的轨道；`insert()` 是插入事件（CHART_FORMAT_v4 §3.6），插入前宽 0；`deletion()` 是删除事件（§3.7），删完宽 0。 |
| `LaneLayout` | 每帧算出判定线的缩放和镜头，把键自己的横坐标（从屏幕中线量起，左负右正）换成屏幕位置（`noteX`，键宽 `noteWidth` 一律 240 × 缩放；判定宽度 `judgeWidth` 在定轨的那几拍至少是一条轨道宽，7.184）；也照旧算各轨道的 x 位置和宽度（给读旧谱和谱面编辑器）。无限轨（`tiled()`）各轨道保持自己的宽度、从中轴线（`axis()`，x 960 加镜头平移）向两边排，完全在屏幕外的宽度为 0；有插入的轨道时再以画面中心为中心统一缩小（`squeeze()`），删除轨道时统一放大，画面两边的轨道不动；有限轨按宽度比例分满镜头框（CHART_FORMAT_v4 §3）。 |
| `LanePlacement` | 无轨道（谱面 v6，2026-11-05）用：读旧谱（v5 及更早）时给每个键算出自己的横坐标（从屏幕中线量起），x = 轨道中心 − 960 + 横移；轨道不动时完全一样，插入、删除、改宽度的那几拍按 1/8 拍采样。`Chart` 的构造函数调用它。 |
| `Layers` | 叠加层（2026-11-05，CHART_FORMAT §10.2）：一个通道上可以互相重叠的事件，每个在自己的拍子里把值加上去，事件外加 0；结束拍算上，除非同一列表里另一个事件正好从这一拍开始。`of(events)`、`at(beat)`（不分配内存）、`low()` / `high()` 等边界。不可变。 |
| `Line` | 判定线：`LOWER` 地面线（默认 y 120，音符向下落到线上），`UPPER` 天空线（默认 y 960，音符向上落到线上）。 |
| `Note` | 一个音符：类型、线、轨道、判定拍、时长、运动 / 旋转 / 透明 / 尾长事件、残影、故障和扭曲段（`distortions()`）；派生出 `isTideStream()`、`isLaneMine()`、`isTideMine()`、`isBlockMine()`、`hasTideBand()`、`isSync()`、`tailHeightAt(beat)` 等；旋转中心 `pivotX()` / `pivotY()`（2026-10-16，CHART_FORMAT_v4 §6.1.1）。LANE MINE 到线后固定在线上。运动事件原样使用，没有事件覆盖的拍子在画面外；只有完全没有运动事件的音符用默认接近（CHART_FORMAT_v4 §9）。叠加层（2026-11-05，CHART_FORMAT §10.2）：`layer(x, position, rotation)` 设置，`xAt` / `positionAt` / `angleAt` 把它们加上。悬浮键（7.185，CHART_FORMAT §11）：`setFloating(Floating)`（读谱面时在建 `Chart` 之前调用，水滴、水流、DRAG 能悬浮，7.186 起水雷、长水雷也能）、`floating()` / `floats()`；这种键的线一律是 `LOWER`，没有轨道、自己的 x（`x()` 是 NaN）、旋转中心和公转，`visibleFrom()` 是它的 `appear`。 |
| `NoteOrbit` | 键的公转中心（谱面的 `orbit` 键，2026-10-29）：参考画面里的一个绝对点，可以用事件移动，事件结束后停在终点；可选公转半径和公转角度，和移动冲突时由 `orbitConflict` 决定谁优先；`orbitRigid`（2026-11-02）让半径横竖按同一比例缩放，形状在手机上不变形。只改绘制，判定、打击效果和声音仍在轨道上。 |
| `NoteTrail` | 一个音符的一段拖拽残影参数（谱面的 `trail` 键，可以写多段）：长度、密度、透明度、模式 PATH / LEVEL、起止拍。只影响画面。 |
| `NoteDistortion` | 一段扭曲（谱面的 `distortion` 键，音符和漩涡都可以写多段，CHART_FORMAT_v4 §6.11）：起止拍、强度（0..4，可渐变）。0 不扭曲，1 是漩涡平常的晃动，2 是两倍；音符段外是 0（`NOTE_BASE`），漩涡段外是 1（`VEIL_BASE`）。只影响画面。 |
| `NoteGlitch` | 一个音符的一段故障效果（谱面的 `glitch` 键，可以写多段）：起止拍、强度（可渐变）、跳变速度、色散 / 错位 / 闪断的比重。只影响画面。 |
| `NoteType` | 六种 JSON 类型 `tap` / `hold` / `mine` / `universal` / `tideMine` / `drag` 及显示名：DROP 水滴、STREAM 水流、MINE 水雷、TIDE 浪花、TIDE MINE 浪花水雷、DRAG（2026-11-05，排在最后），带时长的 LANE MINE 轨道水雷、TIDE STREAM 潮汐、TIDE STREAM MINE 潮汐水雷。`judged()`（水雷两种不判定）、`inLane()`（浪花两种没有轨道）。 |
| `Stroke` | 自定义线条（7.187，CHART_FORMAT §13）：表演用、从不判定的直线或曲线。点（和悬浮键的中心一样量）、`curve` / `closed` / `front`、显示的拍子 `from` / `to`、画入 `drawIn` / 收回 `drawOut`、线宽、颜色（RGBA8888）、透明度和透明度事件、故障和扭曲段。`of(...)` 修正非法值、从不抛异常（不到两个点时返回 null）；`opacityAt` 和键一样超过 1 会闪，`headAt` / `tailAt` 是画到哪里、收到哪里，`trace(step)` 把直线按步长分段，曲线按 Catmull-Rom 算。不可变，纯 Java。 |
| `Veil` | 漩涡 VEIL（谱面顶层的 `veils`，CHART_FORMAT_v4 §6.10）：圆形或多边形（最多 16 个点）的遮挡区，中心、角度、透明度可以按事件变，存在的时间、故障和扭曲段。`blocksAt(beat)` 存在且透明度大于 0；`contains(lx, ly)` 按准确的轮廓判断（在漩涡自己的坐标里）。`Veil.of` 修正非法值，从不抛异常。纯 Java，不可变。 |

### 4.4 `library`：曲库

| 类 | 作用 |
|---|---|
| `ChapterMeta` | 一个章节（地图上的一个深度层）：id、英文名、中文名、副标题、深度范围、是否“未探明”、歌曲列表。来自 `Charts/index.json`。 |
| `ConstantLabel` | 定数怎么显示给玩家：整数加右上角的小号 `-` / `+`（.0–.3 是 `-`，.4–.6 不加，.7–.9 是 `+`；DESIGN §1.3）。排序、筛选、评分仍用精确值。 |
| `ChartHeaderReader` | 只读谱面开头的字段（v5：格式、谱师、定数；旧格式还有标题、曲师、画师、BPM、难度名），让曲库加载很快。v5 谱面的歌曲信息由 `SongLibrary` 从 `song.json` 读。 |
| `Difficulty` | 四个难度 SURFACE / SHALLOW / DEEP / ABYSS，对应 `Surface.json` / `Shallow.json` / `Deep.json` / `Abyss.json`。名字也是存档的键，不要改。画面上显示的名字在 `ui/Theme.DIFFICULTY_NAMES`：2026-10-27 起是 SHALLOW / MEDIUM / DEEP / ABYSS。 |
| `SongLibrary` | 曲库：读 manifest，在每个歌曲文件夹里找音乐、谱面、曲绘、背景图和结算音乐；按需加载完整谱面（`loadChart`）和曲绘；教程也作为一首“伪歌曲”放在这里。 |
| `SongMeta` | 一首歌的头信息：id（= 文件夹名，也是存档的键）、各难度的定数（精确值、谱面里写的文字、显示用的整数和上标）、文件、试听起点、结算音乐起点、地图上的位置微调、曲绘取景（`jacket16x9`、`jacketCircle`，`crop` 和 `circleSquare` 算裁剪）。 |

### 4.5 `play`：判定和计分

| 类 | 作用 |
|---|---|
| `ShellBreakdown` | 一局获得的贝壳明细（结算画面的贝壳弹窗；2026-10-17 前叫 `ExpBreakdown`）。 |
| `Judgement` | 三级判定 RESONANCE / ECHO / LOST，以及判定窗口和计分常量：`TIER1_MS`（66.67）、`TIER2_MS`（133.33）、`PERFECT_MS`（33.33）、权重、`MAX_SCORE`（100,000,000）。 |
| `PlayResult` | 一局结束时的成绩快照（不可变），交给存档和结算画面。 |
| `RecordOutcome` | 记录一局成绩后改变了什么（最高分、评级、这张谱的深度、玩家深度和贝壳的前后值、新解锁的头像外观），结算画面用它做动画。 |
| `ScoreKeeper` | 一局的分数、连击、共鸣链、PERFECT、EARLY / LATE 和水雷统计：计分公式 `score()` / `baseScore()`、水雷扣的分 `minePenalty()`（2026-11-01 起，分数显示成负数；水雷扣得比已得的分多时 `score()` 低于 0，画面上显示成正数）、`isFullCombo()`（没有 LOST 且没有碰到水雷）、`isAllResonance()`、`recordDrag()`（DRAG 的共鸣：Δt 0，算 PERFECT，不进早晚统计）。纯 Java。 |

### 4.6 `gameplay`：游戏画面、引擎、教程

| 类 | 作用 |
|---|---|
| `DeferredBatch` | 包内。先记录、再按贴图分组重放的 Batch：分散绘制的文字合并成每个字体页一次绘制调用。 |
| `GlitchCapture` | 包内。音符故障和扭曲的录制 Batch：`NoteRenderer` 画正在故障或扭曲的音符时先录下它的四边形；扭曲时 `bend` 把它们切成约 24 单位的小格、按漩涡的晃动移动每个角，故障时再切成左右错开的横条、加上红蓝两层、偶尔变暗，画进图集批次（CHART_FORMAT_v4 §6.9、§6.11）。 |
| `EdgeEntry` | 从屏幕边缘入场（DESIGN §5.4）：出生点在参考画面之外、出生时本体却会露进可见范围的音符（场地拉伸之后就是任何屏幕上转了角度、会露出一角的音符），在第一段运动之前按它的平均速度从屏幕边缘滑进来。按当前可见范围算出每个音符的入场拍，供 `NoteRenderer`、`TrailRenderer` 和开场留白（`GameplayScreen`）使用。距离用参考单位，可见范围按 `Field` 换回参考单位来算。纯 Java，只影响画面，不影响判定。 |
| `FadeShader` | 包内。整体淡化：在 SpriteBatch 默认着色器上再乘一个透明度，夹在 `begin` 和 `end` 之间画的东西整体变淡（暂停后继续的倒数里谱面淡入用）。第一次用时编译，编不过就什么也不改。 |
| `Field` | 场地拉伸：谱面按 1920 × 1200 写（按 1920 × 1080 写的旧谱面读取时换算，`ChartMapper.fromFrame1080`），游戏时参考画面的中心 (960, 600) 对准屏幕中心，x 乘 `kx = worldW / 1920`、y 乘 `ky = worldH / 1200`（16:10 屏幕正好是 1，16:9 是 0.9）。引擎拉伸判定线和轨道窗口，渲染把到线的距离和尾巴长度乘 `ky`，判定区是 ±480·`ky`，上下各约半屏（7.184–7.188 是 ±160·`ky`；画面上的判定带是 ±120·`ky`，见 `Field.bandHalf`）。音符厚度、文字、波纹和四角 HUD 不拉伸。`GameplayScreen` 加载时和每帧按视口设置；默认 1（无头测试不受影响）。 |
| `FeedbackLayer` | 包内。判定线附近的短暂反馈：“轨道上”显示的判定字（含碰到水雷的红色 LOST）、触摸接触环、ECHO 小箭头、水雷冲击环。 |
| `FieldRenderer` | 包内。两条判定线：线（7.178 起 6 像素粗，`LINE_HALF`；线上一直有小泡泡冒出来、长大后直接消失，`drawBubbles`，个数、大小、寿命是 `BUBBLE_*` 常量）、跟着键位置和宽度的光柱（按下、自动播放）和闪光、键快到时线上的提示（7.174 起没有轨道分隔线和刻度）、TIDE 命中时整条线的涌起（`swell`）、判定区提示（从线往外到 ±0.4 屏高的渐变，线上的不透明度 `ZONE_HINT_A` 0.07，再乘设置里判定区提示的 `zoneHintStrength()`：50% 是 1，100% 是 2，0 不画）。 |
| `FloatNotes` | 包内。悬浮键的画法（7.185，DESIGN §26，`NoteRenderer` 在线上的键之后调用）：空泡泡原地浮现、从中心被颜色填满（水滴淡蓝，DRAG 紫色带菱形，水流白色珍珠（7.189 起，之前金色）、外面一圈按拍分段的金环，按住时从顶上顺时针倒计时），同一拍的用细线连着，DRAG 链用紫线连到下一个；打中一闪、散出一圈小水泡，没打到变灰瘪下去。水雷（7.186）红色带六个铆钉，长水雷外面一圈红色倒计时，画在别的悬浮键上面；踩中红色爆开（长水雷碰一次爆一次），没踩中到时间直接消失。效果每帧按引擎的状态算出来（不生成对象，暂停时停住），最早的画在最上面。7.187 起照效果图 v4：暗底、键色圈带柔光的空泡泡，里面长出玻璃球（不画水滴，DRAG 留菱形）；双押线 5 宽带柔光、跟着两个键里淡的那个变透明，这一拍判定线上也有键（sync）时再连一根到最近的线上的键（`linePair`，位置由 `NoteRenderer.lineKey` 给）；故障、扭曲通过 `Host.floatGlitchBegin` / `floatGlitchEnd` 交给 `NoteRenderer` 的故障画法。 |
| `FloatSpace` | 包内。悬浮键在世界里的位置和大小：中心的 x 跟着镜头和横向拉伸，y 跟着纵向拉伸，直径乘全局缩放和 `ky`；`reach` 是离中心几个半径。`FloatNotes` 画和引擎判都用它。纯 Java。 |
| `GameEngine` | 一局的模型：歌曲时间、触摸和按键输入、判定（TIDE 组、联合判定、hold 和 TIDE STREAM 的按住规则）、水雷和 LANE MINE（和同轨 note 撞在一起时 note 优先）、漩涡（在漩涡里按下的手指抬起前无效，`INPUT_VEILED`、`pointerVeiled`）、计分、事件流、暂停和恢复（`startCountIn` 让暂停中的音乐在倒数时静音地先放起来）、自动播放、DRAG（手指在位置上就接住：`DRAG_REACH`、`catchDragsAt`、`updateDrags`、`lastInputCaught()`、`dragCaughtMs(i)`）、悬浮键（7.185：只认按下时画出来的圆里，`KIND_FLOAT`；按住的手指可以离开中心 `FLOAT_HOLD_REACH` 1.3 个半径；DRAG 任何手指在圈里就接住）、悬浮水雷和长水雷（7.186：在圈里才算；和键配对时看手指在哪，两个圈都在才按 10-31 的规矩互扣，在键上不在水雷上的地方回响照旧）、系统收走手势（`gestureCancelled`，7.181）。纯 Java，不碰 GL，加载后不分配内存。 |
| `GameEvent` | 引擎事件流里的一条：HIT、HOLD_START、HOLD_END、LOST、MINE、EMPTY、CATCH（DRAG 提前接住，时间是它的判定拍），加上标志（如 `FLAG_JOINT` 联合判定、`FLAG_LANE_MINE` 轨道水雷接触、`FLAG_DRAG`、`FLAG_FLOAT` 悬浮键（`x` / `y` / `width` 是它的中心和直径，没有线））。画面按它播放特效和文字。 |
| `GameKeys` | 测试用的键位（游戏不读键盘，7.174 起引擎的按键接口是空的，测试把按键换成在轨道中心按下手指）：两排各 10 个键对应两条线的 ±1..±5 轨道，G/H、T/Y 对应插入的 0 号轨，空格只判定下线的 TIDE。 |
| `GameplayHud` | 包内。游戏 HUD：四角（左上分数、右上暂停按钮、左下难度标签和歌名、右下剩余时间、顶边进度条）、中间的连击数和判定字、误差条。分数离屏幕左边固定 `SCORE_LEFT`（24）加额外边距，不按设备的安全边距；从顶边到上判定线默认位置这一段按 `SCORE_BAND_TOP` 0.3 / `SCORE_BAND_CAP` 0.4 / 0.3 分，分数的字高占 0.4，和暂停按钮的中线对齐。不显示准确率（计分照样算）。进度条厚度是屏幕高度的 1 %（`PROGRESS_SHARE`）；难度、歌名和时间在同一行，贴着安全底边，难度标签和分数一样离左边固定距离，三样都是 DESIGN §5.3 的 1.25 倍。自动游玩时连击数下面写 AUTOPLAY，代替 COMBO。开着「FC/AR 指示」（`Settings.fcArIndicator`）时分数按 `fcState` 变色：全共鸣淡蓝、FC 紫、断了白色。`setScoreOnly`（实时预览）时四角只剩分数，只离边 `Ui.MIN_MARGIN`。教程用的演示钩子：`demoPauseDoubleTap`、`demoJudgement`、`demoMineWord`。底部留带、在带里放实时计数的布局（`Ui.useBandHud`）在场地拉伸之后不会出现 |
| `GameplayScreen` | 游戏画面：先把输入交给引擎、再交给暂停控件；绘制场地、潮水、残影、音符、特效和 HUD；开场、倒计时、暂停菜单和结束。继续时的倒数（3 × 1 秒、画面定住）跟着静音先放的音乐走，数完正好到暂停点。倒数时谱面（轨道区、潮水、判定线、音符、残影、光和定住的击打特效）用 `FadeShader` 从 0.05 倍透明度匀速淡回原样（`countFade`）。教程画面继承它（受保护的演示钩子在这里）。 |
| `GuidingText` | 教程的一条定时字幕：按拍子淡入淡出；样式 `GuidingText.Style`，锚点 `GuidingText.Anchor`（参考画面上的点、安全边、判定线或 HUD 元素）。文字为空的字幕是占位，不画。 |
| `IntroductionGuidelines` | 教程的全部字幕（英文和中文），按语言和暂停方式构造（`IntroductionGuidelines(zh, pauseMode)`）。拍点和位置按 Kelvin 手写的谱面手工排，直接改，改完按 §6.4 检查。最后一潜之后的四句对白现在是空的占位（`PLACEHOLDER`），拍点和位置留着，写好新文案直接填；结尾是 K & K |
| `IntroductionScreen` | 教程画面：在普通游玩之上显示字幕和演示（判定字、EARLY / LATE、暂停双击、水雷），以及 SKIP 按钮；结果标记为教程（不记录谱面成绩，只记下“看过教程”和第一次的贝壳）。结尾的 K & K 淡入后中间的连击数让开（`centreCover`）。 |
| `NoteRenderer` | 包内。画所有音符：DROP、STREAM、MINE、LANE MINE 柱、TIDE（天空线珍珠串、地面线滚浪）、TIDE STREAM 水带、DRAG（`paintDrag`，细的淡紫色横条加菱形）。7.178 起水滴、水流、Drag、水雷、长水雷按 Kelvin 11-05 定的效果图画（DESIGN §25）：用 `ui/SkinBaker` 烘焙的键皮肤，水流身体有圆头、白边、发光和往头流的光条（`paintBody`、`paintBars`），天键身体下面的挖空跟着天键的圆头走（`ribbonHoles`、`fillAround`），水雷三颗铆钉，长水雷有红边、圆头和压在线上的红条（`paintLaneMineHead`）。还画金色同步方块和发光，以及 sync 高亮边框（`setSyncHint`、`syncFrame` / `frameGlow`、`crestGlow`，粗细和强度是 `HL_*` 常量）。转过的 TIDE / TIDE STREAM 绕旋转中心画成无限长（`tideTurn` / `tideTurnEnd`，2026-10-16）。它的 `paint*` 方法也给残影和教程演示用。目押校准（`setHitPoint`）：`hitShift()` =（按键高的一半 20 − 设定值）× 按键缩放，`drawPos` 减掉它，所有音符沿自己的路一起挪；残影由 `TrailGeometry.setShift` 跟着挪。 |
| `NoteShapes` | 音符外形的纯数字和规则：上下线重叠时的挖空（7.178 起跟着天键的圆头：`holeHalfAt`、`holeCurveRow`）、水流身体的圆头宽度（`ribbonEndHalfWidth`）和光条位置（`barOffset`）、TIDE 的 T4 外形、sync 珍珠（`showsPearl`）、LANE MINE 柱、按键离线远时按中心定位（`centreShiftX/Y`，天地两排在任何音符大小和屏幕比例下对齐）、旋转中心（`pivotShiftX/Y`，CHART_FORMAT_v4 §6.1.1）和转过的 TIDE 能伸多远（`tideTurnReach`）。渲染器和残影按它画，测试直接检查它。 |
| `PauseControl` | 右上角暂停按钮的位置（靠右，上下居中在屏幕顶边和天线之间；`edgeTop()`、`sky()` 也给 HUD 的分数用）、触摸区和输入规则（双击、按住 0.35 s、单击；哪些按下不算）。纯 Java。 |
| `PauseMenu` | 包内。暂停菜单（方案 B）：暗幕和三个大按钮 RETRY / RESUME / QUIT。 |
| `PreviewRun` | 「游戏界面」中间实时预览的那一局：自己持有一个 `GameEngine`（时钟用模拟的时间源 `SongClock.setTimeSource`）和 `FieldRenderer`、`NoteRenderer`、`FeedbackLayer`、`GameplayHud`（`setScoreOnly`：四角只剩分数），每局生成一张 12 组的谱（`GROUPS`，120 BPM 每拍一组，单、单、双循环四次，每组随机在上线或下线，双押车道至少隔一条），自己按每个音符（±50 ms 共鸣），第 5 组（`ECHO_GROUP`）早或晚 85–120 ms 打成回响，第 9 组（`LOST_GROUP`，双押）不打，所以分数每局淡蓝 → 紫 → 白；打完 0.7 s 换新谱。按当前玩家的设置每帧绘制，用世界坐标画一整个游戏画面，由 `screens/shell/LivePreview` 缩小、裁剪进框里。不接收点击。 |
| `RunOverlay` | 包内。盖在 HUD 之上的时刻：3-2-1 倒计时（开场每步 1 s，在歌曲时钟开始前；恢复也是每步 1 s）、结束横幅（CLEAR / FULL COMBO / ALL RESONANCE）。开场的预备时间常量 `PREROLL_SEC` 也在这里。 |
| `ScaledGlitch` | 包内。连击数、倒计时这类会缩放的数字的故障闪烁，固定格宽，数字变化时不抖动。 |
| `StrokeRenderer` | 包内。自定义线条的画法（7.187，DESIGN §27，`NoteRenderer` 调用）：不在前面的线条在浪花和所有键之前画，`front` 的在悬浮键之后画；每张谱只描一次点（`STEP` 8），每帧取画入、收回之间的那段，像悬浮键的中心一样放到屏幕上，扭曲时按漩涡的水波晃，画柔光、线、圆头和画入时的笔尖；故障时断成一段一段横跳、带红蓝色散或者暗下去闪，图案只跟故障段的步数有关（暂停时不动）。每帧不分配内存。 |
| `TideWash` | 包内。TIDE 命中的潮水效果（光带浮起、退潮、变暗）和 TIDE STREAM 按住时的细光带。 |
| `TouchMarks` | 包内。「显示触控」：游戏中每个按着的手指下面一个白色的圈（半径 `R` 64）和中间的小点，跟着手指；松开后圈放大三分之一、`FADE_SEC` 0.25 s 淡出。在漩涡里按下的手指不管开没开都画漩涡的 glitch 触点（直径和一个键一样宽，`VeilRenderer.drawTouch`），这根手指只画它。每帧从引擎的指针表读位置，画在音符和特效上面、四角 HUD 下面。教程里点到两个判定区外时另外闪一个淡点（`FeedbackLayer.stray`）。 |
| `TrailGeometry` | 残影副本在某一时刻的位置和角度。纯 Java。`setShift` 让副本和活音符一样按目押校准挪。 |
| `TrailPlan` | 残影的纯规则：哪些副本存在、多亮、超出预算时怎么稀疏。 |
| `TrailRenderer` | 包内。画残影：在活音符之前画每个副本，最远的先画，用和活音符相同的画法。 |
| `VeilRenderer` | 画漩涡和 glitch 触点（DESIGN §24.56）：把漩涡下面已经画好的画面复制到一张贴图，用一个着色器画出折射、同心圆或光网、平常的晃动和故障；判定线留在水面上，漩涡下的 HUD（连击数、判定字、误差条）在最显眼的那块漩涡里再画一次（`clipBegin` / `clipEnd`）。着色器编译不了时退回简单的圆或轮廓。`wobbleX/Y` 是晃动的公式，音符的扭曲也用它。公开，谱面编辑器的预览可以直接用。 |
| `Veils` | 漩涡在屏幕上的位置：中心和形状都跟着场地拉伸（横向乘 `Field.kx()`、纵向乘 `Field.ky()`，和其他事件一样）；`contains` / `containsAny` 按准确的轮廓判断一个点在不在漩涡里，引擎和渲染共用。纯 Java，不分配内存。 |

### 4.7 `fx`：打击特效

| 类 | 作用 |
|---|---|
| `FxPool` | 包内。一种粒子特效的对象池，限制同时存在的数量（现在只有 LOST 用）。注意 `screens/result` 里有一个同名但无关的类。 |
| `HitFx` | 打击特效的入口：DROP 和长条的水花（`Splash`）、LOST ×；高 / 低 / 关画质，帧率持续偏低时自动降为低画质。 |
| `ParticleAtlasFactory` | 包内。把 `assets/ParticleEffects/lost.p` 加载到 `ui/Draw` 的共享图集上，让粒子和场景在同一张贴图里。 |
| `Splash` | 包内。水花本体：圆形闪光、圆波纹、溅起的水珠，按住长条时 base 上的光、喷泉和一圈圈波纹；固定数组，游玩时不分配内存。 |
| `SplashForm` | 包内。共鸣和回响两种样子的全部数字（颜色、亮度、大小、水珠、圈），调两者的差别只改这个文件。 |
| `WaterRings` | 包内。圆波纹层：任意半径、寿命、线宽和颜色的正圆，每个画成一条线加一层辉光。 |

### 4.8 `profile`：存档和设置

| 类 | 作用 |
|---|---|
| `AvatarSpec` | 程序生成头像“声呐印记”的参数（形状、花纹、配色、符号、边框）。2026-10-30 起选项不再按深度解锁，头像都从 `AvatarCatalog` 来。 |
| `AvatarCatalog` | 头像表：现在只有默认头像（紫色同心圆）。每个头像有 id 和来源（默认 / 歌曲 / 地图节点的 `avatarReward`，见 `docs/MAP_FORMAT.md`）；存档记 `avatarId` 和 `ownedAvatars`。以后加头像加在这里。 |
| `ChartRecord` | 一个玩家在一张谱面上的记录：最高分、准确率、最大连击、FULL COMBO / ALL RESONANCE、评级、次数、最近成绩、最好成绩的深度 `bestDepth`。 |
| `DeviceSettings` | 本机设置（`device.json`）：音量、各音频输出的延迟、画质、界面缩放、额外边距等，所有玩家共用。 |
| `LegacyUserDataImporter` | 把原版的 `KandKGame/user.json`（按歌名记录的旧成绩）导入玩家存档。 |
| `Depth` | 深度：每张谱的深度（40 ×（定数 + 加成）²，加成在 9600 万分以上每 200 万加 1）、玩家深度（最深 10 张平均）、卡片上的 NEXT 小目标、深度分档（`tierMeters`）和深度徽章的 16 档（`crestTier`：1–6 档底座，7–9 深渊、10–12 幽光、13–15 心海，16 彩虹）。这里都是正数，界面显示时加负号（`ShellFormat.appendDepth`）。 |
| `Grades` | 15 档评级（R+ … F）的分数门槛、颜色和比较。 |
| `PlainJson` | 包内。存档的 JSON 读写：只用 Jackson 流式核心，按公共字段自动绑定（兼容 Android 5–7）。 |
| `NameWidth` | 名字长度：最多 15 个英文或 10 个中文的宽度（`units`、`cut`、`clip`），起名、改名和显示都用它。 |
| `Profile` | 一个玩家的存档：名字、头像、贝壳、珍珠（先只显示）、深度、买过的歌、成绩记录、个人设置、地图位置等；旧存档的转换（`migrateToSchema2`）。 |
| `ProfileStore` | 多玩家存档（最多 12 个，`MAX_PROFILES`）：读写、原子保存、备份和损坏恢复、导入导出、记录成绩（`recordPlay`）、买单曲（`buySong`）、重置记录（`resetRecords`，清空全部进度：成绩、深度、记录、统计、贝壳、买下的曲目、剧情和隐藏曲目标记、教程、章节模式和要靠深度解锁的头像样式；名字、设置和校准保留）。 |
| `Shells` | 每局贝壳（原来的经验公式和奖励）和单曲默认价格。 |
| `Settings` | 跟随玩家的设置：暂停方式 `pauseMode`、判定字位置、EARLY / LATE（`earlyLate`）、语言、显示触控 `strayTouches`（开 / 关，开着时游戏中每个手指下有一个圈，见 `gameplay/TouchMarks`）、FC/AR 指示、触感、sync 提示 `syncHint`、目押校准 `hitPointPx`（−100..100 px，默认 0 = 判定线横穿按键中间）等。设置画面里已经去掉的几项（背景插画、打击音效时机、曲目试听、菜单音乐、实时计数）字段还在，旧存档照样能读，`sanitize()` 把前四项固定在默认值 |

### 4.9 `map`：潜水地图（选歌）

除 `MapScreen` 外都是包内类。

| 类 | 作用 |
|---|---|
| `AutoChip` | 歌曲卡片上的 AUTO（自动播放）开关。 |
| `CardChip` | 歌曲卡片上的一个难度按钮（难度名和定数）。 |
| `CardRecord` | 歌曲卡片上一个难度的成绩区：最高分、评级（打过全连就是紫色）、准确率、这张谱的深度（当前 / 满分）、排名、次数、小目标。 |
| `DescendButton` | DESCEND 按钮（带下沉的双箭头和光晕）；没买的单曲变成「HOLD TO BUY」购买模式（按住 0.6 秒，占满 AUTO 和 DESCEND 那一整行，AUTO 隐藏）；所有曲目里锁着的歌显示锁和解锁条件（要分数时分两行）。 |
| `SortPanel` | 所有曲目的排序面板：从 SORT 按钮升起，选顺序（默认、歌名、定数、最高分、最近）和筛选（NEW / FULL COMBO / RESONATED，一次一个，再点取消）。 |
| `GridPage` | 所有曲目页：网格的位置（一排两首、相邻两行错开半格，手机上和地图默认缩放一样大、平板小两成）、选中圈半径、网格的裁剪和渐隐（遮罩着色器）、滚动条和点击范围。网格跟着地图镜头上下滚（x 锁住、不缩放），从玩家块下面 24 开始（`MapHud.frameBottom`）；节点、名字、成绩行和行与行之间的连线由 `MapRenderer` 画，连线由 `MapWorld` 建 |
| `TextChip` | 排序面板里的文字小按钮（选中时填满青色，放不下时折成两行；`setLabel` 换语言时换字）。 |
| `CaveData` | 读 `assets/maps/` 的地图数据（每层一个 JSON）和剧情，格式见 `docs/MAP_FORMAT.md`。 |
| `CaveFx` | 层与层之间的转场：旋涡进入（X1）、下坠（X3）、上浮（X4）。 |
| `MapBubbles` | 在地图上拖动、点击时冒出的气泡。 |
| `MapCamera` | 地图镜头的物理：跟手拖动、惯性、越界回弹、双指缩放、飞行到节点。 |
| `MapFilter` | 筛选条件（NEW / FULL COMBO / ALL RESONANCE 之一或不筛），按镜头难度判断每首歌；`MapWorld` 不把不符合的歌排进网格。 |
| `MapGestures` | 地图的手势识别（拖动、双指、点击、长按、双击；两根手指静止按住 1.5 秒上浮）。 |
| `MapHud` | 地图四周的 HUD：左上深度徽章（头像坐在 `DepthCrest` 的六边形上，顶边和设置键对齐；点头像展开 / 收起名字条，记在存档 `mapNameShown`，每次启动第一次进地图时露 2 秒；六边形下面是贝壳和珍珠胶囊，超过 9,999 显示 9,999+ 加高光，点它弹出确切数目），右上设置按钮和它左边进玩家档案的玩家按钮，镜头难度切换，上浮圈（两根手指静止按住时出现在两指中间），所有曲目的 SORT 按钮（右下角，底栏放不下时在难度切换上方），主界面右下角的「所有曲目」按钮，以及玩家按钮左边的故事模式 / 章节模式切换按钮（主线章节全部解锁后出现）。尺寸常量在类里和 `MapStyle` |
| `MapNode` | 地图上的一个点（歌曲洞、剧情、竖井、航线洞口、上浮洞、未知信号）：位置、成绩、上锁和已读状态、动画状态。 |
| `MapRenderer` | 画地图世界：光斑、区域名、路、解锁虚线、各种点、锁、层名牌、路牌和提示，按贴图排序控制绘制次数；也给 HUD 画字。 |
| `MapScreen` | 公开。洞穴地图：把上面这些组合起来，处理层的切换、每层进来的镜头（最近的缩放 `MapStyle.ZOOM_MIN`，主界面除外）、上浮（只在子层：两根手指静止按住 1.5 秒，或返回键 / Esc / PageUp；主界面和开着歌曲卡片时不上浮，所有曲目页除外）、选歌（没有镜头难度谱面的歌点了只晃一下）、剧情、DESCEND（X2c）和从游戏返回的解锁动画（X5）。 |
| `MapStyle` | 地图专用常量：世界尺寸、节点样式、镜头物理、HUD 尺寸和少量地图专用颜色。 |
| `MapUi` | 地图上 HUD、卡片和面板的界面缩放。 |
| `MapWorld` | 一层地图的内容：从数据建点和路、算上锁和通关、所有曲目的歌（所有曲包里的，含排序和筛选；位置由 `GridPage` 排）。`rise` 点不建节点，只用于起始镜头和镜头范围。 |
| `PeekTip` | 长按节点时显示各难度最高分的小浮窗。 |
| `SongCard` | 歌曲卡片（右侧弹出；排版是第五版，DESIGN §4.2）：最上面一行 ‹ 关卡名 ›、16:9 曲绘和柔光（`SongLibrary.washIfReady`）、标题带（超过 30 个字符的歌名用 `ui/Marquee` 滚动）、分数和 DEPTH / PLAYS、评级圈、难度、NEXT、AUTO 和 DESCEND。成绩区的文字由 `CardRecord` 算好缓存。 |
| `StoryCard` | 剧情卡（X6）：墨水晕开、青色细线、打字机文字、翻页。 |
| `ThumbAtlas` | 节点上的圆形缩略图：在后台线程裁剪缩放曲绘，缓存到 `cache/thumbs/`。 |
| `VelocityTracker` | 拖动松手时的速度估计（卡片滑动、列表滚动）。 |

### 4.10 `screens`：其他画面

| 类 | 作用 |
|---|---|
| `CalibrationScreen` | 校准：音频延迟（跟着 120 BPM 节拍点击，取中位数），可以试打验证。内容都在 `CalibrationPanel` 里，这个类只是全屏的外壳（标题栏、返回） |
| `CalibrationPanel` | 延迟校准的全部内容（说明、音频预设、节拍音轨、点击、结果、验证），全屏的 `CalibrationScreen` 和首次设置第 2 步共用：`layoutScreen` 按全屏排，`layoutCard` 按卡片排；首次设置用快速模式、没有验证，「应用」以后通过 `Host.applied()` 进下一步。 |
| `HitPointScreen` | 目押校准（设置 → 视觉第一行的「校准…」打开，返回回到视觉）的全屏外壳，内容在 `HitPointPanel` |
| `HitPointPanel` | 目押校准的全部内容，`HitPointScreen` 和首次设置第 3 步共用：开头一张示意图（判定线、实际大小的按键、按键中线和两者的距离，`drawDiagram`），没有声音地落 4 + 16 个按键，点屏幕任意位置算最近的按键（±250 ms），每次点击时按键中线离线多少 px（`seenPx` = 当前 − 1.08 × 晚了的毫秒 ÷ 按键缩放），取中位数就是新数值；少于 12 次或偏差超过 35 ms 只能重试，「应用」存进当前玩家。结果和分布条（`shell/TapStrip` 的 px 模式）都用 px。`layoutCard` 按卡片排（线在卡片底边上面 100，按键在卡片顶边下面淡入）。 |
| `FirstRunScreen` | 首次设置三步（`COMFORT` / `AUDIO` / `HIT`）：COMFORT（故障强度、减少动态 / 闪烁）、延迟校准（`CalibrationPanel` 放在 1400 × 700 的卡片里，「应用」进下一步）、目押校准（`HitPointPanel` 放在同一张卡片里，「应用」进教程）。没有头像和名字那一步：新玩家用默认名和建档时的随机头像（`ProfileStore` 建的）。没有标题栏和返回键，每一步的卡片放在屏幕正中（`cardY`）。 |
| `ProfileScreen` | 玩家档案：左栏 `shell/ProfileHeader`（头像、名字、贝壳、深度），右栏 STATS / DST 10 / RECENT / PLAYER 四个标签页 |
| `ResultScreen` | 结算画面：用 `screens/result` 的组件按时间线播放成绩；RETRY / CONTINUE、谱面延时提示和明细弹窗也在这里 |
| `VisualsScreen` | 游戏界面（GAMEPLAY VISUALS）：设置 → 视觉 → 调整… 打开的独立画面，像校准画面一样，返回回到视觉页。中间是 `shell/LivePreview` 大预览（`gameplay/PreviewRun` 自己打一局），下面一条「判定字样」（ECHO、RESONANCE、LOST 按故障强度轮流闪）；左列「画面」（背景亮度、故障效果强度、减少闪烁、粒子质量），右列「音符与判定」（按键缩放、多押辅助、判定位置、FC/AR 指示、判定区提示（0–100% 拖动条，默认 50%）。设置行竖排（`SettingRow.setStacked`），两列用 `SettingsPage.setSpread` 排到底，放不下时缩小而不滚动；列宽、留白等数字在类开头的常量里 |
| `SettingsScreen` | 设置：AUDIO、VISUAL、GAMEPLAY、OTHER 四页（中文 音频、视觉、游玩、其他），每页的行在 `buildAudio` … `buildOther` 里建立。AUDIO：谱面延时（`SliderRow`，–10 / –1 / +1 / +10 微调，三行说明 `OFFSET_DESC`、`OFFSET_EARLY`、`OFFSET_LATE`，左边「校准…」打开 `CalibrationScreen`）、音频预设、音乐音量、打击音效音量、界面音效音量（`UI_NONE`，没有界面音效时一直灰着），各行用 `SettingsPage.setSpreadCentred` 平分高度。VISUAL：目押校准（排法同谱面延时，说明 `HIT_DESC`、`HIT_EDGE`、`HIT_MIDDLE`，「校准…」打开 `HitPointScreen`）、游戏界面（「调整…」打开 `VisualsScreen`）、减少动态、背景细节、背景亮度、界面缩放、额外边距、显示帧率。GAMEPLAY：过早 / 过晚提示（`ChipsRow`：RESONANCE、ECHO 可多选）、显示毫秒、偏差条、暂停方式、显示触控、震动，一页放得下不滚动。OTHER：语言、备份存档、重置记录（按住 2 秒后弹出 `shell/ResetDialog`，确认并输入玩家名以后才调用 `ProfileStore.resetRecords`）、新手教程、开源许可证（VIEW 打开 `buildLicenses` 的许可证页，数据在 `shell/Licenses`，带 VIEW 的条目用 `showLicense` 显示全文），最后一行是 `InfoRow.brand`（左边 K & K，右边版本号），它占满剩下的高度，所以 OTHER 不滚动。设置里去掉的几项由 `Settings.sanitize` 固定在默认值 |
| `WelcomeScreen` | 欢迎画面：开场动画、K & K 标志、TAP ANYWHERE TO START、左下角的玩家切换。没有设置入口，设置从地图进 |

### 4.11 `screens/result`：结算画面的组件

| 类 | 作用 |
|---|---|
| `Choreo` | 结算的时间线：固定节拍、随成绩变化的深度、贝壳和解锁提示阶段（2026-10-30 起深度不再解锁头像，这一段留给以后的头像奖励）、跳过和停顿点。 |
| `CornerHud` | 左上深度徽章（和地图左上角一样，缩到 0.72；名字条默认展开，点头像收起；加深时整个徽章以六边形中心放大，右边浮出装着 ▼ 加深米数的小六边形框，数字像里程表一样滚到新值，落地后换成新档的样子）和右上贝壳、珍珠胶囊（珍珠在最右；深度动画之后贝壳左边浮出 +N 贝壳泡、像水滴一样融进胶囊，再数到新值；超过 9,999 显示 9,999+ 加高光）。点六边形看深度明细，点贝壳 / 珍珠看明细，第一行是确切的总数（弹窗由 `ResultScreen` 画）。 |
| `FxPool` | 结算画面的装饰粒子（印章火花、升级光点、气泡）。和 `fx/FxPool` 无关。 |
| `GlyphQuads` | 字体之外的逐字特效：分数格子的错位故障、扫过文字的光带（结算印章；地图和歌曲卡片上 R / R+ 的白光也借它画，见 `map/MapRenderer.grade()`）。 |
| `GradeStamp` | 评级印章：砸下、冲击、抖动，R、SS+ 和 R+ 扫过的光泽；这一局全连时是紫色。 |
| `JacketBlock` | 全宽标题带（曲名、曲师居中；超过 50 个字符的曲名在带里滚动）和 16:9 曲绘（背后自己颜色的柔光、下部渐暗（三栏时不暗）、入场和声呐边框），曲绘右下角里的难度块（带阴影，`drawChip`），角色区里的 AUTO 水印。 |
| `ResultKit` | 结算组件共用的绘制环境（服务、临时颜色、文字队列、变换辅助）。 |
| `ResultLayout` | 当前屏幕比例下结算画面每个元素的位置：16:10（1920 × 1200）是蓝本（`blueprint`），矮的屏幕整体按高度缩小，高的屏幕把多出的高度一半放在曲绘上面、一半放在下面；比 16:10 宽的屏幕把多出的宽度四成放左边、四成放右边、两成放中间；比 16:8.5 宽的手机分三栏（`columns`，按 2400 × 1080 的框，比 20:9 窄时中间一栏按比例变窄）：左边曲绘和下面的分数，中间统计，右边角色区。角色区在所有比例上都是 16:10 的形状（720 : 716）。 |
| `ResultModel` | 结算要显示的全部数值和字符串，在转场遮住画面时一次算好。 |
| `RoundedImage` | 不用着色器画圆角图片（曲绘），以及同样形状、由上往下渐深的纯色（曲绘下部的渐暗）。 |
| `ScorePanel` | 分数的解码动画和与最高分的对比行（NEW BEST 等，压在曲绘下部分数上面）。 |
| `StatsBlock` | 判定数量各行（含 MINES、PERFECT）、RESONANCE CHAIN / MAX COMBO 那条线、EARLY / LATE / AVG 一行、三栏时判定数上面的分隔线，自动游玩时 NEW BEST 位置上的 AUTOPLAY 标签。没有 CLEAR 等通关标记。 |
| `TextQueue` | 结算画面的文字按字体页分组绘制，控制绘制次数。 |

### 4.12 `screens/shell`：菜单画面共用的部件

欢迎、首次设置、玩家档案、设置和校准这些“外壳”画面共用的基类、行和对话框。全部公开。

| 类 | 作用 |
|---|---|
| `ActionRow` | 设置行：右侧一到两个按钮（如 CALIBRATE…、REPLAY）。 |
| `AvatarView` | 会动的头像：翻转切换、随机时的动画。 |
| `ChartRows` | DST 10 和 RECENT 列表行共用的缩略图和难度标签。 |
| `ChipsRow` | 设置行：右侧几个可以多选的开关按钮，样子和个人页 STATS 的难度按钮一样，全都不选也可以（游玩页的过早 / 过晚提示：RESONANCE、ECHO）。 |
| `ChoiceRow` | 设置行：分段选择（如 OFF \| LOW \| HIGH）。 |
| `CreateDiverSheet` | 新建玩家的弹出卡片。 |
| `DiverActionsDialog` | 玩家的改名、导出、删除菜单。现在没有入口（PLAYER 页的卡片只切换），留着给以后的删除玩家入口用 |
| `DiverForm` | 新建玩家卡片的内容：默认头像和名字输入（英文标签 `PLAYER NAME`，最多 15 个英文或 10 个中文的宽度）。框里正好是默认名时不显示下面「留空就用 …」的提示。首次设置不用它。 |
| `DiverList` | 所有本地玩家的显示缓存（名字、贝壳和深度、最后一次下潜、最后活动时间） |
| `DiverStats` | 玩家档案页的统计数字：贝壳、深度、游玩次数和时长，每个难度玩过 / 通过 / FULL COMBO / ALL RESONANCE 的谱面数，最高分按每 100 万分一格的分布（STATS 直方图）和平均分，下潜曲线（每个点的深度 `history` 和时间 `historyT`）。 |
| `DiveChart` | DST 10 顶上的下潜曲线 DIVE PROFILE：横轴是时间，刻度从一分钟到一年自动选，刻度下面写日期；双指捏合放大缩小（最窄 10 分钟），拖动平移，双击回到全部；点圆点弹出那次下潜的日期、时间和深度。右边两个深度刻度单独占一栏。 |
| `DiversTab` | PLAYER 标签页：玩家卡片（点卡片只切换，最后一行是 `ShellFormat.lastActivity` 的「LAST ACTIVITY: 日期 时间」）、新建、导入。放得下就一行三张；卡片窄于 440 时头像 72，贝壳数用贝壳图标加 `DiverList.Entry.metaShort`。存满 12 个后没有新建卡片和导入按钮 |
| `ImportDialog` | 导入备份码（从剪贴板粘贴）。 |
| `InfoRow` | 设置列表里的只读行：分节标题或信息行；`brand` 是 OTHER 最后的品牌行（左边用开始画面的 glitch 字写 K & K，右边版本号）：它占满列表剩下的高度（`fills`），在 `BRAND_MIN_H` 到 `BRAND_HEIGHT` 之间；K & K 字高最多 `BRAND_CAP`，行矮时是行高的一半，版本号按 `BRAND_VALUE_CAP` / `BRAND_CAP` 的比例跟着。 |
| `Licenses` | 开源许可证页的数据：K & K 自己（MIT）、三种字体（OFL）和随游戏打包的软件库，每条有版权行、许可证名和全文文件（`assets/licenses/`，字体的在 `assets/fonts/OFL-*.txt`）。改 Gradle 依赖时跟着改。 |
| `LivePreview` | `VisualsScreen` 中间的预览框：按屏幕比例放一整个缩小的游戏画面，画背景渐变和背景亮度的暗幕，用画笔的变换矩阵和一次裁剪把 `gameplay/PreviewRun` 那一局画进框里，再画边框和右下角的「实时预览」。不接收点击。 |
| `LogTab` | RECENT 标签页：最近 20 局。 |
| `Modal` | 弹出卡片和对话框的基类（暗幕、滑入、返回键关闭）。 |
| `ProfileHeader` | 玩家档案左栏：头像、名字（点名字原地改名，弹出手机键盘）、贝壳、深度，下面 PLAYS 和 PLAY TIME 两条（平板在顶部横条右边）。 |
| `ProfileTab` | 玩家档案右栏一个标签页的基类（可滚动区域）。 |
| `RenameDialog` | 改名对话框（`DiverActionsDialog` 用；现在没有入口，个人页点名字原地改名）。 |
| `ResetDialog` | 重置记录的确认框：设置 → 其他 → 重置记录按住 2 秒后弹出；第一步显示头像、玩家名、贝壳和深度和会清空的内容，点「确认」进第二步；第二步把玩家名完整输入一遍，对上了「重置」才能点（首尾空格不算）。「重置」调用设置画面传进来的动作（`ProfileStore.resetRecords`）；取消、返回或点框外关闭。 |
| `ScrollArea` | 菜单列表的滚动物理和裁剪（拖动、惯性、回弹）。 |
| `SettingRow` | 设置列表一行的基类：左侧标题、说明（`setDescription2` 加第二行；`setDescription3` 加第三行，这一行随之高一行，谱面延时用）和本机设置的 SHARED / 公用 标记，右侧控件。`fills` / `fillHeight` 让一行占满列表剩下的高度（每页最多一行）。`setStacked` 竖排：标题一行（数值或开关在右边）、说明，控件在下面占满整行（`VisualsScreen` 用） |
| `SettingsPage` | 设置的一页：可滚动的设置行列表。各行放得下时不会滚动（底部 40 的留白只在放不下时才算）；`fills` 的行拿到其他行剩下的高度；`setSpread` 把剩下的高度平分给各行，让一列排到底（`VisualsScreen`）；`setSpreadCentred` 一样平分，但每行的内容放在加高后的格子中间（音频页）。 |
| `ShellFormat` | 外壳画面共用的文字格式化（不分配内存）；`deviceFull` / `diversMax` 是存满时的提示。 |
| `ShellScreen` | 外壳画面的基类：背景、标题栏、返回、转场和弹出卡片的管理。 |
| `SliderRow` | 设置行：滑块和右侧的数值。 |
| `StatsTab` | STATS 标签页：四个难度开关（只写难度名，可点亮组合，至少亮一个），亮着的难度合计的 CHARTS CLEARED（通过/总数）/ FULL COMBOS / ALL RESONANCE，和一直到底的最高分直方图（画法和以前结算界面的偏差图一样）。 |
| `SwitcherSheet` | 欢迎画面的玩家切换卡片：各个玩家和 `+ NEW PLAYER`（存满 12 个后没有，底部收起），没有 MANAGE |
| `TabRail` | 设置的分类导航（宽屏在左侧，4:3 在顶部）。 |
| `TapStats` | 校准点击的统计（中位数和离散度）。 |
| `TapStrip` | 校准时的点击散点条（EARLY 在左、LATE 在右），−300..+300 ms 两段刻度：±150 ms 占四分之三宽度，以外的两段压窄、画淡，落在那两段里的点击画灰色空心圈。`inPixels()` 换成目押校准用的 px 模式：−150..+150 均匀刻度，两头写「线下方」「线上方」，单位 px，没有 RESONANCE 区间。 |
| `TextRow` | 设置列表里的一大段纯文字（许可证全文）：按原文换行，从列表左边逐行显示，只画看得见的行，不接收输入。 |
| `Tappable` | 由所有者自己绘制外观的可按控件（卡片、行、自定义芯片）。 |
| `ThumbCache` | 玩家档案列表用的小缩略图（后台解码，合进一张贴图）。 |
| `ToggleRow` | 设置行：右侧开关，点整行也能切换。 |
| `TopTab` | DST 10 标签页：上面固定的下潜曲线（`DiveChart`），下面滚动的是 DEEPEST 10（决定你深度的最深 10 张，不满用空位）和 OVERFLOW（第 11 到 15 深的 5 张，不算进深度）。 |
| `WidgetSet` | 一组可按控件的输入分发、更新和绘制。 |


### 4.13 `transition`

| 类 | 作用 |
|---|---|
| `TransitionLayer` | 全局转场，由 `Main` 持有、画在画面之上：CURTAIN、DIVE（下潜进游戏：水墙合上后曲绘左移，右边依次弹出曲作者、歌名和难度、谱师、画师）、ASCENT（和 DIVE 一样的水帘，到结算）、IRIS（光圈回地图）。画面被完全盖住时执行加载回调。有曲绘时 DIVE 至少盖住 `DIVE_ART_COVER` 4.5 s，没有曲绘（教程、重来）是 `DIVE_MIN_COVER` 1.5 s。曲绘圆半径 `ART_R` 325。 |

### 4.14 `ui`：共用视觉服务和控件

| 类 | 作用 |
|---|---|
| `Anim` | 动画用语：时长常量、缓动函数、可复用的 Tween。 |
| `ArtCache` | 全屏背景图（`welcome.png`、`Blue.png`、`ResultPage.png`、`ChartVisualizer.png`）的引用计数缓存。 |
| `Avatar` | 程序生成的“声呐印记”头像：渲染到缓存的帧缓冲，边框实时绘制。 |
| `Backdrop` | 共用的程序海水背景（着色器、光柱、焦散、雪花、气泡、声呐），颜色随深度变化。 |
| `Button` | 按钮（PRIMARY、SECONDARY、DESTRUCTIVE 等样式，可设按住时长）。 |
| `DepthPalette` | 六个海洋深度层的配色表，按深度（米）插值。 |
| `DepthParams` | 深度配色算出的一组参数（由 `DepthPalette` 填写）。 |
| `Draw` | 共用绘图服务：唯一的 SpriteBatch、程序生成的 1024² 图集（所有界面、背景、粒子的小图，含 `ring_water` 水波纹圆环）、ShapeDrawer。`ringArc()` 画抗锯齿的粗圆环（地图的分数环和封面边），`convexFill()` / `convexStroke()` 画抗锯齿的凸多边形和描边（地图玩家块两头尖的深度标签）。7.178 起图集里还有 `SkinBaker` 烘焙的键皮肤（`SKIN_*`、`HOLD_*`、`TEAR`、`BUBBLE`），`skin(名字)` 取出 `Draw.Skin`（小图和它在键自己坐标里的位置；能拉伸的皮肤是左头加一小段均匀的中间），`noteHead()` 给校准页画游戏里的水滴。7.187：`target(batch)` 把所有画图方法暂时改画到另一个 Batch（悬浮键故障时画进 `GlitchCapture`），`sink()` 是现在画到哪里。 |
| `DepthCrest` | 深度徽章的 16 档图片（`assets/ui/depth/tierNN.png`，由 `tools/depth-tiers/make_tiers.py` 生成）：头像下面的底层（六边形、翅膀、宝石、小点）和头像上面的顶层（珍珠或钻石），数字由调用的地方写。地图和结算页共用。 |
| `Fonts` | 字体服务：按“角色”（DISPLAY、NUMBER、UI、UI_BOLD 等）和字高取字体，按屏幕像素栅格化并缓存，中文自动换到中文字体。 |
| `FrameProbe` | 帧时间探测：背景太慢时自动切到低画质。 |
| `GlitchState` | 单个元素的故障状态（静止等级、突发、随机种子）。 |
| `GlitchText` | 故障风文字：G0–G3 各级效果，所有故障字都经过它。 |
| `EdgeGuard` | 接口：游玩时把屏幕边缘从系统手势要过来（`guardEdges(on)`，渲染线程调用）。安卓 10 起由 `android/AndroidLauncher` 实现，`Main.guardEdges` 转过去；`GameplayScreen` 显示时开、离开和暂停菜单打开时关。 |
| `Haptics` | 触感反馈（振动），由设置开启。 |
| `Icons` | 程序绘制的图标（暂停、设置、返回等）。 |
| `KkScreen` | 所有画面的基类：世界视口 1920 × 1080、共用的 Batch、把触摸换算成世界坐标、返回键（BACK / Esc → `onBack`）、转场期间锁定输入。系统收走整个手势时（`touchCancelled`）调 `onGestureCancel`，默认把每根手指的按下都取消（libGDX 只报一根）。 |
| `KkServices` | 接口：`KkScreen` 依赖的服务（由 `Main` 实现）。 |
| `Lang` | 界面语言：`Lang.of(英文, 中文)` 是跟着语言走的文字（建一次，换语言后自动换字），`Lang.t(英文, 中文)` 取当前语言的一句，`Lang.zh()` 判断当前是不是中文。`Main.applySettings()` 按当前玩家的 LANGUAGE 设置。选歌地图（`map` 包）不用它，见 §6.6 第 6 条。 |
| `Marquee` | 放不下的歌名的滚动节奏：开头停 1 秒，每秒 80 向左滚，末尾到右边停 2 秒，空 160 接下一遍，循环；还有两端渐隐的宽度。选歌卡片（超过 30 个字符）和结算（超过 50 个字符）共用，见 DESIGN §24.20。 |
| `Panel` | 卡片、暗幕、分隔线、分节标题的绘制辅助。 |
| `Pressable` | 所有可按控件的基类：按下缩放、变亮、波纹、触感，抬起时生效。 |
| `Ripple` | 按下时的波纹。 |
| `Segmented` | 分段选择控件。 |
| `SkinBaker` | 包内。键皮肤烘焙（7.178，DESIGN §25）：开局在 `Draw` 的构造里把效果图的水滴（地、天、灰三种）、Drag 横条和菱形、水雷、小水滴、水流的圆头（填充、白边、发光）、侧面发光、白边、光条和判定线的小泡泡按 2D 画布的画法（叠加、`shadowBlur` 用高斯模糊、sRGB 渐变）画成小图。电脑上约 0.1 秒，全解释执行约 0.8 秒；三种水滴共用一次外形和发光，水流圆头的三部分共用一次外形。要改样子就改这里的数字和颜色。 |
| `Slider` | 滑块控件。 |
| `SonarField` | 共用的声呐波纹（背景和画面里的扩散圆环）。 |
| `Spring` | 阻尼弹簧（按钮缩放等动画）。 |
| `TextField` | 文字输入框（系统输入对话框；`Mode.KEYBOARD` 只弹出手机键盘）。 |
| `Theme` | 所有颜色常量（DESIGN §1.2），以及取派生颜色的辅助方法；定数上标的大小和位置（`CONST_MARK_*`）。 |
| `Toast` | 提示条队列（顶部中间弹出）。 |
| `Toggle` | 开关控件。 |
| `Ui` | 视口计算：可见区域、安全边、界面缩放、触摸目标大小。 |
| `UiKit` | 把所有视觉服务打包，统一创建、调整大小、恢复和释放。 |

### 4.15 启动器

| 类 | 作用 |
|---|---|
| `android/AndroidLauncher` | Android 入口：全屏沉浸、可画到刘海区域、保持屏幕常亮，安卓 8.0 起建一个 `android/AndroidHitOutput` 交给 `Main`（App 切到后台时让它暂停），创建 `Main`。它也是 `ui/EdgeGuard`：安卓 10 起游玩时把整个游戏画面设成不接系统手势（`setSystemGestureExclusionRects`，布局变了就重设），系统的边缘返回就不会取消屏幕上的手指。横屏（可翻转 180°）和振动权限写在 `android/` 目录的 `AndroidManifest.xml` 里。 |
| `android/AndroidHitOutput` | 安卓 8.0 起的打击音输出：一条低延迟 `AudioTrack`（设备自己的采样率，`PERFORMANCE_MODE_LOW_LATENCY`），一直放着（没有打击时放静音，输出不会休眠），在自己的高优先级线程里用 `HitMixer` 一块一块写，按 `AudioTrack.getTimestamp` 定位（拿到第一个之前每块都取，之后大约每秒取一次，从后台回来或欠载后重新取）；欠载时队列加长一个设备缓冲。它也是 `MusicOutput`：游戏的歌曲交给 `android/SongDecoder` 解码，混进同一条输出放（队列加长到 4 个设备缓冲）。 |
| `android/SongDecoder` | 在后台线程里用系统的 `MediaExtractor` + `MediaCodec` 解码歌曲（用提取器给的格式配置，MP3 开头结尾的裁剪和系统播放器一样），转成输出的采样率存进 `PcmBuffer`；2 秒内解不出第一段声音就放弃，照旧用系统播放器。Logcat 标签 `SongDecoder` 记下解出来的总帧数和 MP3 文件头里的帧数。 |

---

## 5. 要改……在哪里改

| 想改的东西 | 在哪里 | 说明 |
|---|---|---|
| **判定窗口** | `play/Judgement`（`TIER1_MS`、`TIER2_MS`、`PERFECT_MS`）；`Params.MINE_WINDOW_MS`（水雷 ±133.33 ms，只有按下才算；和同轨 tap / STREAM 头的窗口互相扣掉）、`Params.LANE_MINE_LEAD_MS`（轨道水雷到线才封锁）、`Params.RESONANCE_GUARD_MS`（轨道水雷让给的 note 打出共鸣以后同轨轨道水雷睡 150 ms）；`GameEngine`（`HOLD_LEAVE_MS`、`NEIGHBOUR_TOLERANCE`、`MINE_BOX`、`LANE_MINE_BOX`）；判定区高度 `Params.JUDGE_ZONE_HALF` | 规则见 ARCHITECTURE §5、§10、§13、§20，谱面作者看 `docs/CHART_FORMAT_v4.md` §6 |
| **计分公式** | `play/ScoreKeeper.score()` / `baseScore()`；权重和满分在 `play/Judgement`；评级阈值在 `profile/Grades`，深度在 `profile/Depth`，贝壳在 `profile/Shells` | FULL COMBO / ALL RESONANCE：`ScoreKeeper.isFullCombo()` / `isAllResonance()`。旧存档的分数换算在 `ProfileStore` |
| **音符的样子** | 数字和规则：`gameplay/NoteShapes`；绘制：`gameplay/NoteRenderer`；残影：`gameplay/TrailRenderer`；故障：`gameplay/GlitchCapture`（图样的数字在它的常量和 `emit` 里）；扭曲：`GlitchCapture.bend`，晃动的公式在 `gameplay/VeilRenderer.wobbleX/Y`；漩涡：`gameplay/VeilRenderer`（着色器的数字在它的字符串里）；颜色：`ui/Theme`；小图：`ui/Draw` 的图集；键皮肤（7.178 起水滴、水流、Drag、水雷的样子和判定线的小泡泡）：`ui/SkinBaker` | sync 的规则（金珍珠和高亮边框共用）在 `NoteShapes.showsPearl`；sync 高亮边框的粗细、强度在 `NoteRenderer` 的 `HL_*` 常量，颜色是 `Theme.SYNC_HIGHLIGHT`、`SYNC_HIGHLIGHT_SKY`、`MINE_HIGHLIGHT`；透明度大于 1 的闪烁在 `Note.shownOpacity`；上下线重叠的两个常量 `SKY_HOLE_PAD`、`SKY_TIDE_STREAM_INSET`（改成 `*_FIRST` 就是第一版方案） |
| **HUD 的位置和大小** | `gameplay/GameplayHud`（四角、连击、判定字、误差条）；暂停按钮的位置和触摸区：`gameplay/PauseControl`；安全边：`ui/Ui` | 布局数字见 DESIGN §5.2–§5.4 |
| **暂停规则** | `gameplay/PauseControl`（双击 300 ms、按住 0.35 s、单击；`judges` 决定哪些按下不算）；输入顺序和按键（返回键、Esc、Pause）：`gameplay/GameplayScreen`；暂停菜单：`gameplay/PauseMenu`；默认方式：`profile/Settings.pauseMode`；引擎的暂停和恢复：`GameEngine.pause()` / `resume()` | 规则见 DESIGN §5.12 |
| **教程的课程和字幕** | 谱面 `assets/Introduction/Surface.json` 是 Kelvin 手写的（2026-10-17）；字幕、拍点常量和暂停那一行的三种说法（`PAUSE_LABEL_EN` / `PAUSE_LABEL_ZH`）在 `gameplay/IntroductionGuidelines`，按谱面手工排，直接改。演示动画和 SKIP：`gameplay/IntroductionScreen`；字幕的样式和锚点：`gameplay/GuidingText`。改完检查字幕有没有挡住音符 | 课程表见 DESIGN §10.2，步骤见 §6.4 |
| **打击特效和粒子** | `fx/HitFx`（入口、画质）；水花：`fx/Splash` 和 `fx/SplashForm`（两种样子的数字）；圆波纹：`fx/WaterRings`；LOST 的粒子文件 `assets/ParticleEffects/lost.p`（发射器名称不能改，颜色由代码覆盖）；TIDE 的潮水：`gameplay/TideWash`；水雷冲击环和轨道上的字：`gameplay/FeedbackLayer`；判定线的闪光和涌起：`gameplay/FieldRenderer` | 见 `docs/ASSET_SLOTS.md` §4 |
| **颜色** | `ui/Theme`（全部颜色常量）；深海背景随深度的配色：`ui/DepthPalette` | 地图专用的少量颜色在 `map/MapStyle` |
| **字体和中文字库** | `ui/Fonts`（角色、字高、中文自动切换）；字体文件 `assets/fonts/` | 中文字体 `NotoSansSC-Medium.ttf` 是 Noto Sans SC 的子集（GB2312 加日文常用汉字），加中文前先确认字都在里面（§6.5）。授权见 `docs/ASSET_SLOTS.md` §3 |
| **谱面读取** | `elements/ChartMapper`（入口、清理、加载报告）、`ChartFormatV5`（v5 读写）、`LegacyChartFormat`（旧格式读取和转换）、`SongInfo`（`song.json`）；数据模型 `elements/*`；曲库和文件查找：`library/SongLibrary`；谱面头：`library/ChartHeaderReader` | 格式见 `docs/CHART_FORMAT.md` |
| **地图** | `map/MapScreen`（总控）、`CaveData`（读数据）、`MapWorld`（一层的内容）、`MapRenderer`（绘制）、`MapCamera`（镜头物理）、`MapHud`、`SongCard`、`StoryCard`、`CaveFx`、`MapStyle`（常量） | 每层的布局在 `assets/maps/*.json`，格式见 `docs/MAP_FORMAT.md` |
| **设置** | 数据：`profile/Settings`（跟随玩家）、`profile/DeviceSettings`（本机）；界面：`screens/SettingsScreen`（`buildAudio` … `buildOther`），游戏界面在 `screens/VisualsScreen`；行控件：`screens/shell/ToggleRow`、`ChoiceRow`、`ChipsRow`、`SliderRow`、`ActionRow`、`InfoRow`；生效：`Main.applySettings()` | 步骤见 §6.3 |
| **存档** | `profile/ProfileStore`（位置、保存、恢复、导入导出）；内容：`Profile`、`ChartRecord`、`Settings`、`DeviceSettings`；JSON：`PlainJson`；旧存档导入：`LegacyUserDataImporter` | 在应用自己的存储里（每个玩家一个 `<id>.json`，加上 `index.json`、`device.json`）；导出的备份码复制到剪贴板 |
| 歌曲时钟、音频延迟 | `audio/SongClock`、`audio/AudioService`；校准：`screens/CalibrationPanel`（`CalibrationScreen` 和首次设置共用） | |
| 目押校准 | 设置：`profile/Settings.hitPointPx`；画：`gameplay/NoteRenderer.hitShift`、`TrailGeometry.setShift`；校准：`screens/HitPointPanel`（`HitPointScreen` 和首次设置共用） | 只改画的位置，判定不变；默认 0（2026-10-20 22:37 起，以前 20），存档第 5 版把还在 20 的改成 0。规格见 DESIGN §5.8、§9.7 |
| 结算画面 | `screens/ResultScreen` 和 `screens/result/*`（时间线在 `Choreo`，位置在 `ResultLayout`，数值在 `ResultModel`） | |
| 转场 | `transition/TransitionLayer` | |
| 背景 | `ui/Backdrop`、`ui/DepthPalette` | |
| Android 方向、权限 | `android/` 目录的 `AndroidManifest.xml`；`android/AndroidLauncher` | |

---

## 6. 常见任务

### 6.1 加一首歌

完整步骤见 `docs/ASSET_SLOTS.md` §5。简要：

1. 新建 `assets/Charts/<歌曲 id>/`。文件夹名就是歌曲 id，也是存档的键，之后不要改名。
2. 放入音乐 `<歌曲 id>.ogg`（或 `.mp3` / `.wav`）、至少一个谱面（`Surface.json`、`Shallow.json`、`Deep.json`、`Abyss.json`）、曲绘 `jacket.png`；可选 `bg.png`、`result.ogg`。
3. 在 `assets/Charts/index.json` 某个章节的 `songs` 里加上 id（可以用对象写法加 `previewStartMs` / `previewEndMs`、`resultMusicStartMs`、`map` 微调；试听段也可以写在歌曲文件夹的 `song.json` 里，谱面编辑器写的就是它）。
4. 重新构建（更新 `assets/assets.txt`），在地图上检查。

代码不用改：`library/SongLibrary` 会自动找到文件。没写进 manifest 的文件夹会被追加到最后一个已开放的章节。

### 6.2 加一个章节

在 `assets/Charts/index.json` 的 `chapters` 里按深度顺序加一项（`id`、`title`、`titleZh`、`subtitle`、`depthMeters`、`depthBottom`、`uncharted`、`songs`），见 `docs/ASSET_SLOTS.md` §6。地图上的位置要在 `assets/maps/` 里放（见 `docs/MAP_FORMAT.md` §7）；没放到任何一层的歌会自动出现在“单曲”层（排在离起点最近的空位上，`MapWorld.addUnplaced`）和“所有曲目”里。

### 6.3 加一个设置

1. **数据**：跟随玩家的设置加在 `profile/Settings`，本机设置加在 `profile/DeviceSettings`：加一个带默认值的公共字段，并在 `sanitize()` 里把非法值改回合法范围。`PlainJson` 按字段名自动读写，不用注册；旧存档里没有这个键时用默认值。
2. **界面**：在 `screens/SettingsScreen` 对应页的 `build…` 方法里加一行，用 `ToggleRow`、`ChoiceRow` 或 `SliderRow`。行的 `get` 读字段，`set` 写字段后调用 `diverChanged()`（玩家设置）或 `deviceChanged()`（本机设置），它们会标记存档待保存并调用 `Main.applySettings()`。
3. **生效**：需要通知服务的设置在 `Main.applySettings()` 里处理；游戏中用到的在 `GameplayScreen.begin(...)` 读取（每局开始时）。
4. 新文字要用英文大写的界面风格，并且都要有中文：标题和说明写成 `Lang.of("ENGLISH", "中文")`（见 §6.6），按 §6.5 检查字库。下潜画面和结算画面只写英文（§6.6 第 4 条），选歌地图见 §6.6 第 6 条。
5. 在 DESIGN §9.2 的设置表里补上这一行。

### 6.4 改教程（手写谱面，手排字幕）

教程的谱面 `assets/Introduction/Surface.json` 是 Kelvin 手写的（2026-10-17，102 个音符）；字幕类 `gameplay/IntroductionGuidelines` 里每条字幕的拍点和位置是按这份谱面手工排的。原来的生成脚本已经不用了。

1. **改谱面**：直接改 `Surface.json`（或用谱面编辑器）。改了音符的拍点、轨道或线，就要看一遍附近的字幕：字幕（尤其是带底板的说明）不能挡住还没判定的音符。
2. **改字幕**：在 `IntroductionGuidelines` 里改 `add(...)` 那一行（英文、中文、开始和结束拍、淡入淡出、位置、字号、样式、锚点），演示窗口改类里的常量（如 `MINE_DEMO_START`、`LANE_MINE_BEAT`）。居中的字幕要在它那一课的音符出现以前消失；来不及时放在一侧的底板上，压在这段时间里空着的轨道上。英文说明可以断成两到四行。
3. **检查**：在 16:9、20:9 和 4:3 下各看一遍教程（英文和中文）：每条字幕不能挡住还没判定的音符、不能超出安全区、不能和别的字幕重叠。结尾两句没有底板的对白压在最后的和弦上可以接受（音符从字后面经过）。
4. 演示动画、SKIP 按钮这些代码在 `gameplay/IntroductionScreen`，它读 `IntroductionGuidelines` 里的拍点常量。
5. DESIGN §10.2 的课程表跟着改。

### 6.5 加中文文字（字库子集）

`assets/fonts/NotoSansSC-Medium.ttf` 是 GB2312 子集加日文常用汉字（约 8,750 个字形）：常用简体字、希腊字母、日文假名和 JIS X 0208 第一水准的日文汉字（如 氷、塩）都有，生僻字没有；2026-10-21 补了 GB2312 没有的 ς、长音 ー 等 38 个字，2026-10-30 补了第一水准汉字（1,085 个）和 ❀ ✿。缺的字显示不出来。加新的中文文字前，可以用 fontTools（`pip install fonttools`）检查：

```bash
python3 -c "
from fontTools.ttLib import TTFont
cmap = TTFont('assets/fonts/NotoSansSC-Medium.ttf').getBestCmap()
s = '要检查的中文文字'
print([c for c in s if ord(c) not in cmap] or '都在字库里')
"
```

缺字时换一个说法，或从完整的 Noto Sans SC 重新做子集。做法（原有字形保持不变）：取 Google Fonts 的可变字体 `NotoSansSC[wght].ttf`，用 fontTools 在字重 500 实例化（不设重叠标记），再按现有文件的全部字符加上新字做子集，去掉排版特性和 hinting：

```python
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools import subset
old = TTFont('assets/fonts/NotoSansSC-Medium.ttf').getBestCmap()
f = instancer.instantiateVariableFont(TTFont('NotoSansSC[wght].ttf'), {'wght': 500},
                                      overlap=instancer.OverlapMode.KEEP_AND_DONT_SET_FLAGS)
o = subset.Options(); o.layout_features = []; o.hinting = False; o.notdef_outline = True
s = subset.Subsetter(o); s.populate(unicodes=set(old) | {ord(c) for c in '要加的字'}); s.subset(f)
f.save('assets/fonts/NotoSansSC-Medium.ttf')
```

### 6.6 中英文界面（`ui/Lang`）

设置 → OTHER → LANGUAGE 选「中文」时，除了选歌地图、下潜画面和结算画面，所有界面都显示中文；暂停菜单和结算的按钮、确认框、明细弹窗也跟着语言（Kelvin 2026-10-18）。

1. **固定的文字**（标题、按钮、设置行、标签页）：在建界面时写 `Lang.of("ENGLISH", "中文")`。它是一个 `CharSequence`，每次画的时候读当前语言，所以换语言后不用重建界面。
2. **每帧拼出来的文字**（分数说明、提示、日期）：用 `Lang.t("ENGLISH ", "中文")` 取当前语言的那一句再 append；缓存了文字的地方要记下缓存时的语言（例如 `WelcomeScreen.refreshChip` 的 `chipZh`、`DiveChart` 的 `tickZh`），语言变了重新拼。语言跟着玩家走：换玩家时 `ShellScreen.afterActiveChange` 先按新玩家设好语言，再刷新各页。
3. **第一次显示中文会卡一下**：中文字形是用到时才栅格化的。在画面被转场盖住的时候先量一次宽度（`fonts.width(...)`）把字形准备好，例如 `ResultScreen.warmText`、`GameplayHud` 和 `PauseMenu` 的 prewarm、`IntroductionScreen.begin`。
4. **保持英文的**：歌名和曲师、难度名（SURFACE 等）、DST 10 / DEEPEST 10 / OVERFLOW、许可证原文；游玩时用 glitch 字体画的判定字（RESONANCE / ECHO / LOST）和结束横幅（CLEAR / FULL COMBO / ALL RESONANCE），因为这个字体没有中文字形；整个下潜画面（AUTOPLAY、COMBO、EARLY / LATE、教程的 HOLD TO SKIP）和结算画面，这些地方不用 `Lang`；但结算的 RETRY / CONTINUE、谱面延时确认框、贝壳明细、分数明细、柱状图的 峰值 / 平均、谱面延时提示、特效调低的提示和条上那行小字跟着语言（`ResultScreen` 的 `Lang.of`，`ResultModel` 的 `zh`、`hintText`、`fxNoticeText()`、`stripNote`）。暂停菜单只写当前语言（`PauseMenu`）。教程字幕还是跟语言走。
5. **游戏 HUD 的字**：`GameplayHud.label(...)` 逐个字母画（带字距），只有英文。
6. **选歌地图**（`map` 包）不用 `Lang`，看 `MapScreen.zh` / `MapRenderer.zh`（`refreshProfile` 换语言后调用 `MapRenderer.refreshLabels` 重建标签和路牌）。中文模式英文中文都画（如 `SINGLES` 下面一行 `单曲`）；英文模式一个中文字也不画（Kelvin 2026-10-20：「如果语言选择是英语就不要出现中文」）：数据里的 `…Zh` 字段不画，英文中文混写的字（如 `"SIDE · 支线"`）用 `MapWorld.latin` 去掉带中文的部分，代码里的提示写成 `zh ? "中文 ENGLISH" : "ENGLISH"`。要每帧画的字在 `refreshLabels` 里先拼好（`signText`、`areaSub`）。唯一的例外是设置里语言选项的「中文」，不然切到英文后认不出怎么切回来。

---

## 7. 检查和测试

* **编译**：Gradle 构建（`./gradlew android:assembleDebug`，或在 Android Studio 里运行 `android`）。
* **谱面文档里的 JSON 例子要能被真实的 `ChartMapper` 加载**：改 `CHART_FORMAT.md` 和 `CHART_FORMAT_v4.md` 时保持这些代码块完整、可解析。
* **其他测试**（引擎、计分、残影、TIDE、暂停、存档、截图、性能）不在仓库中。
* **整理代码时**：只删没用到的东西、只改排版和注释的改动，应该不改变游戏的任何行为。可以这样确认：用 `javac -g:none` （不带调试信息，行号变了也不影响）编译改动前后的代码，逐个比较 `.class` 文件；再跑一遍自动测试。截图不适合逐像素比较：粒子、故障字和光圈一直在动，同一个版本连拍两次也不一样，所以截图用眼睛对比。

---

## 8. 本手册的类名检查

每次改完本手册，可以在仓库根目录运行下面的脚本：它列出代码里有、但手册里没提到的类（第一部分），以及手册里写了 `包/类`、但代码里找不到的类（第二部分）。两部分都应该没有输出。

```bash
# 1. 代码里每个类都要在手册里出现
find core/src/main/java android/src/main/java -name '*.java' \
  | sed 's#.*/##; s#[.]java$##' | sort -u \
  | while read -r c; do grep -qE "[\`/]$c([\`.(]|$)" docs/DEVELOPER.md || echo "手册缺少：$c"; done
# 2. 手册里写的“包/类”都要存在
grep -oE '`(audio|elements|fx|gameplay|library|map|play|profile|screens|screens/result|screens/shell|transition|ui|android)/[A-Z][A-Za-z0-9]*' docs/DEVELOPER.md \
  | tr -d '`' | sort -u \
  | while read -r p; do ls core/src/main/java/org/kelvinizer/$p.java \
      android/src/main/java/org/kelvinizer/$p.java 2>/dev/null | grep -q . || echo "代码里没有：$p"; done
```
