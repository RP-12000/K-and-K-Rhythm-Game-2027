# 开发人员手册（DEVELOPER）

本手册说明代码在哪里、每个模块做什么，以及常见的改动该改哪个文件。内容以分支 `integ` 的代码为准（本次更新时为 `5040d0b`），手册里的类名都用脚本和代码树对过（见 §8）。

* 画面和交互的规范：`docs/DESIGN.md`（英文）。代码结构和约定：`docs/ARCHITECTURE.md`（英文；§21 是 2026-10-12 第二轮改动在代码里的位置索引）。两份规范对代码结构有分歧时以 ARCHITECTURE 为准，对外观有分歧时以 DESIGN 为准。
* 和原版相比改了什么：`docs/CHANGES.md`。谱面格式：`docs/CHART_FORMAT.md`。资源槽位：`docs/ASSET_SLOTS.md`。
* 怎么打开和运行工程：仓库根目录的 `README.md`。

---

## 1. 工程一览

| 路径 | 内容 |
|---|---|
| `core/src/main/java/org/kelvinizer/` | 全部游戏代码，桌面和 Android 共用（§4 逐个包说明） |
| `lwjgl3/src/main/java/org/kelvinizer/lwjgl3/` | 桌面启动器（`Lwjgl3Launcher`、`StartupHelper`）；窗口图标在 `lwjgl3/src/main/resources/ic_launcher.png` |
| `android/src/main/java/org/kelvinizer/android/` | Android 启动器（`AndroidLauncher`）。清单文件 `AndroidManifest.xml` 和图标目录 `res/` 在 `android/` 目录下 |
| `assets/` | 资源：谱面和曲绘（`Charts/`）、教程（`Introduction/`）、字体（`fonts/`）、粒子（`ParticleEffects/`）、音效和结算音乐槽位（`Sounds/`、`Audio/`）、背景图。桌面运行时它也是工作目录 |
| `docs/` | 文档 |
| `build.gradle`、`settings.gradle`、`gradle.properties`、`core/build.gradle`、`lwjgl3/build.gradle`、`android/build.gradle` | Gradle 构建。版本号都在 `gradle.properties`（libGDX 1.14.1、LWJGL 3.4.1、ShapeDrawer 2.5.0） |

构建要点：

* 代码按 **Java 11** 编译（`sourceCompatibility = 11`）：不能用 record、switch 表达式、text block 等更新的语法。运行 Gradle 和游戏用 JDK 21。
* 依赖：libGDX（含 FreeType）、ShapeDrawer、Jackson **只用流式核心** `jackson-core`（不能用 databind，原因见 `profile/PlainJson`）。
* 构建时 `generateAssetList` 任务把 `assets/` 下的文件列表写进 `assets/assets.txt`（git 忽略）。曲库在不能列目录的场合（jar 里的 classpath、Android）靠它找文件，所以**加了资源要重新构建一次**。
* 仓库里没有单元测试。开发时用的测试、截图和性能工具不在仓库里。

---

## 2. 代码约定（摘要，完整的见 ARCHITECTURE §0）

* **坐标**：世界坐标固定以 1920 × 1080 为最小可见区域（`ExtendViewport`），较长的一边向外延伸。游戏场地另外按 `gameplay/Field` 拉伸到整个可见区域（谱面坐标 → 世界坐标），四角的 HUD 不拉伸。安全边（刘海、圆角、设置里的额外边距）用 `ui/Ui` 的 `safeLeft()` 等取得。界面元素按 `Ui.uiScale()` 缩放。
* **服务**：字体、绘图、背景、转场、音频、曲库、存档都由 `Main` 创建并持有，画面通过 `game.xxx()` 取用。GL 对象（贴图、着色器、帧缓冲）**不能放在 static 字段里**（Android 会丢失 GL 上下文）。
* **不在每帧分配内存**：游戏、渲染和特效代码在加载后不再 `new`，用预先分配的数组和对象池。
* **文字**：所有文字都通过 `ui/Fonts` 绘制；含中文的字符串会自动换到中文字体（`NotoSansSC-Medium.ttf`）。
* **按钮在抬起时生效**（`ui/Pressable`）；判定用触摸事件自己的时间戳。
* **注释**：类注释先写一两句中文，再写英文；方法和行内注释用英文（Kelvin 的规则）。目前大部分类注释还只有英文，清单见改动报告。

---

## 3. 从启动到结算：一局的流程

1. `lwjgl3/Lwjgl3Launcher`（或 `android/AndroidLauncher`）创建 `Main`。`Main.create()` 建好所有服务，读曲库（`SongLibrary.load()`，只读谱面头）和存档（`ProfileStore`）。
2. 欢迎画面 `screens/WelcomeScreen` → 第一次启动走 `screens/FirstRunScreen` 再进教程，之后进地图 `map/MapScreen`。
3. 在地图上点歌曲、选难度、按 DESCEND：`Main.startGameplay(song, diff, autoplay)` 播放 DIVE 转场（`transition/TransitionLayer`）。转场盖住画面的同时，后台线程（`kk-chart-loader`）用 `SongLibrary.loadChart` → `elements/ChartMapper` 读出完整的 `elements/Chart`；两件事都完成后，回到 GL 线程调用 `gameplay/GameplayScreen.begin(...)`。
4. 游戏中：每次触摸和按键先交给 `gameplay/GameEngine`（判定、计分，输出 `GameEvent`），然后才交给 `gameplay/PauseControl`；判定了音符、重新按住长条或按在进行中的 TIDE STREAM 上的按下不算暂停点击（碰水雷的按下也不算）。BACK / Esc 和键盘的 Pause 键直接暂停。每帧 `GameEngine.update()` 之后，`GameplayScreen` 读取事件，驱动 `GameplayHud`、`FeedbackLayer`、`fx/HitFx`、`TideWash`、`FieldRenderer`，再按 DESIGN §5.1 的顺序绘制：背景 → 判定区提示、轨道分隔线、潮水冲刷 → 连击数和判定字 → 判定线、TIDE STREAM 光带、LANE MINE 光柱、残影和音符 → 加法混合的光和打击特效 → 教程说明、开场卡片、倒计时、结束横幅 → 最上层（暂停遮罩、四角 HUD、暂停菜单）。
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
| `Params` | 全局常量：参考尺寸 1920 × 1080，判定窗口和权重（`play/Judgement` 里定义的别名），水雷窗口 `MINE_WINDOW_MS`（±66.67 ms）、轨道水雷提前量 `LANE_MINE_LEAD_MS`（0，到线才封锁），判定区半高 `JUDGE_ZONE_HALF`（108），结束延迟 `FINISH_DELAY_MS` 等。只放常量。 |

### 4.2 `audio`：声音和歌曲时钟

| 类 | 作用 |
|---|---|
| `AudioService` | 菜单这边的声音：菜单音乐（循环、淡入、选歌时压低）、地图试听、结算音乐、打击音效槽位（`hit.*`、`hit_echo.*`、`hold_end.*`、`mine.*`）和校准时的播放。每帧调用 `update`。 |
| `ClickTrack` | 校准用的 120 BPM 节拍声，第一次使用时合成，缓存为 `cache/calib_120.wav`。 |
| `SongClock` | 以音频位置为准的歌曲时钟：在音频锚点之间用 `System.nanoTime()` 插值，处理开场留白、暂停和恢复；MP3 的解码延迟在这里扣掉；测量设备开始出声的延迟（存在 `DeviceSettings.musicStartLatencyMs`；还不知道时在倒计时里静音试播测一次），提前这么多开播，开播后 1 秒内不跟随还在跳的位置。 |
| `WavDurationUtil` | 只读 WAV 文件头得到时长（试听起点的默认值、歌曲长度）。 |
| `Mp3Info` | 只读 MP3 文件头（Info/Xing 头和 LAME 标签）得到时长，以及电脑上解码后开头多出来的延迟；`SongClock` 用它把 MP3 对齐到谱面。 |

### 4.3 `elements`：谱面数据模型（纯数据，加载后不变）

| 类 | 作用 |
|---|---|
| `Chart` | 一张可玩的谱面（v2 模型）：轨道、轨道模式（`laneLimit()`，0 = 无限轨）、判定线几何、按轨道和线预先建好的音符索引；内部类 `Chart.LoadReport` 记录加载时修正、丢弃的内容和 LANE MINE 冲突。 |
| `ChartMapper` | 读写谱面：解析 v2，把 v1 精确转换成 v2（20 条原始轨道 → 上下两条线各 ±1..±5），去掉重复音符，生成加载报告（不干净时记一次日志）。 |
| `Events` | 一段带缓动的关键帧：`MovementEvents`、`RotationEvents`、`DisappearEvents`、`HeightEvents`、轨道的宽度和透明度事件、`LineEvents` 都用它。 |
| `JudgementLine` | 两条判定线的高度、全局轨道缩放（`laneScaleAt`）、镜头平移（`cameraFrameAt`，中轴线左右移动），以及让它们动起来的 `LineEvents`。旧的窗口左右边界读取时换算成缩放和平移（CHART_FORMAT §3.5）。 |
| `Lane` | 一条轨道：id 负数在左，0 只能是插入的轨道；宽度和透明度可以随时间变化；`implicit()` 是无限轨加载时自动补上、存档时不写出的轨道；`insert()` 是插入事件（CHART_FORMAT §3.6），插入前宽 0；`deletion()` 是删除事件（§3.7），删完宽 0。 |
| `LaneLayout` | 每帧算出各轨道的 x 位置和宽度，是唯一计算轨道位置的地方；音符、触摸、特效都用它。无限轨（`tiled()`）各轨道保持自己的宽度、从中轴线（`axis()`，x 960 加镜头平移）向两边排，完全在屏幕外的宽度为 0；有插入的轨道时再以画面中心为中心统一缩小（`squeeze()`），删除轨道时统一放大，画面两边的轨道不动；有限轨按宽度比例分满镜头框（CHART_FORMAT §3）。 |
| `Line` | 判定线：`LOWER` 地面线（默认 y 120，音符向下落到线上），`UPPER` 天空线（默认 y 960，音符向上落到线上）。 |
| `Note` | 一个音符：类型、线、轨道、判定拍、时长、运动 / 旋转 / 透明 / 尾长事件、残影；派生出 `isTideStream()`、`isLaneMine()`、`isSync()`、`tailHeightAt(beat)` 等；旋转中心 `pivotX()` / `pivotY()`（2026-10-16，CHART_FORMAT §6.1.1）。LANE MINE 到线后固定在线上。运动事件原样使用，没有事件覆盖的拍子在画面外；只有完全没有运动事件的音符用默认接近（CHART_FORMAT §9）。 |
| `NoteTrail` | 一个音符的一段拖拽残影参数（谱面的 `trail` 键，可以写多段）：长度、密度、透明度、模式 PATH / LEVEL、起止拍。只影响画面。 |
| `NoteGlitch` | 一个音符的一段故障效果（谱面的 `glitch` 键，可以写多段）：起止拍、强度（可渐变）、跳变速度、色散 / 错位 / 闪断的比重。只影响画面。 |
| `NoteType` | 四种 JSON 类型 `tap` / `hold` / `mine` / `universal` 及显示名：DROP 水滴、STREAM 水流、MINE 水雷、TIDE 潮汐，带时长的 LANE MINE 轨道水雷、TIDE STREAM 潮汐水流。 |

### 4.4 `library`：曲库

| 类 | 作用 |
|---|---|
| `ChapterMeta` | 一个章节（地图上的一个深度层）：id、英文名、中文名、副标题、深度范围、是否“未探明”、歌曲列表。来自 `Charts/index.json`。 |
| `ConstantLabel` | 定数怎么显示给玩家：整数加右上角的小号 `-` / `+`（.0–.3 是 `-`，.4–.6 不加，.7–.9 是 `+`；DESIGN §1.3）。排序、筛选、评分仍用精确值。 |
| `ChartHeaderReader` | 只读谱面开头的字段（标题、曲师、画师、谱师、BPM、定数、难度名），让曲库加载很快。 |
| `Difficulty` | 四个难度 SURFACE / SHALLOW / DEEP / ABYSS，对应 `Surface.json` / `Shallow.json` / `Deep.json` / `Abyss.json`。名字也是存档的键，不要改。 |
| `SongLibrary` | 曲库：读 manifest，在每个歌曲文件夹里找音乐、谱面、曲绘、背景图和结算音乐；按需加载完整谱面（`loadChart`）和曲绘；教程也作为一首“伪歌曲”放在这里。 |
| `SongMeta` | 一首歌的头信息：id（= 文件夹名，也是存档的键）、各难度的定数（精确值、谱面里写的文字、显示用的整数和上标）、文件、试听起点、结算音乐起点、地图上的位置微调。 |

### 4.5 `play`：判定和计分

| 类 | 作用 |
|---|---|
| `ShellBreakdown` | 一局获得的贝壳明细（结算画面的贝壳弹窗；2026-10-17 前叫 `ExpBreakdown`）。 |
| `Judgement` | 三级判定 RESONANCE / ECHO / LOST，以及判定窗口和计分常量：`TIER1_MS`（66.67）、`TIER2_MS`（133.33）、`PERFECT_MS`（33.33）、权重、`MAX_SCORE`（100,000,000）。 |
| `PlayResult` | 一局结束时的成绩快照（不可变），交给存档和结算画面。 |
| `RecordOutcome` | 记录一局成绩后改变了什么（最高分、评级、这张谱的深度、玩家深度和贝壳的前后值、新解锁的头像外观），结算画面用它做动画。 |
| `ScoreKeeper` | 一局的分数、连击、共鸣链、PERFECT、EARLY / LATE 和水雷统计：计分公式 `score()` / `baseScore()`、`isFullCombo()`（没有 LOST 且没有碰到水雷）、`isAllResonance()`。纯 Java。 |

### 4.6 `gameplay`：游戏画面、引擎、教程

| 类 | 作用 |
|---|---|
| `DeferredBatch` | 包内。先记录、再按贴图分组重放的 Batch：分散绘制的文字合并成每个字体页一次绘制调用。 |
| `GlitchCapture` | 包内。音符故障的录制 Batch：`NoteRenderer` 画正在故障的音符时先录下它的四边形，再切成左右错开的横条、加上红蓝两层、偶尔变暗，画进图集批次（CHART_FORMAT §6.9）。 |
| `EdgeEntry` | 从屏幕边缘入场（DESIGN §5.4）：出生点在 16:9 参考画面之外、出生时本体却会露进可见范围的音符（场地拉伸之后就是任何屏幕上转了角度、会露出一角的音符），在第一段运动之前按它的平均速度从屏幕边缘滑进来。按当前可见范围算出每个音符的入场拍，供 `NoteRenderer`、`TrailRenderer` 和开场留白（`GameplayScreen`）使用。距离用参考单位，可见范围按 `Field` 换回参考单位来算。纯 Java，只影响画面，不影响判定。 |
| `Field` | 场地拉伸（2026-10-12 16:17）：谱面按 1920 × 1080 写，游戏时以屏幕中心为原点，x 乘 `kx = worldW / 1920`、y 乘 `ky = worldH / 1080`（像素上就是屏幕宽 / 1920、屏幕高 / 1080）。引擎拉伸判定线和轨道窗口，渲染把到线的距离和尾巴长度乘 `ky`，判定区是 ±432·`ky`（19:09 起，上下各约半屏；画面上的判定带仍是 ±108·`ky`，见 `Field.bandHalf`）。音符厚度、文字、波纹和四角 HUD 不拉伸。`GameplayScreen` 加载时和每帧按视口设置；默认 1（无头测试不受影响）。 |
| `FeedbackLayer` | 包内。判定线附近的短暂反馈：“轨道上”显示的判定字（含碰到水雷的红色 LOST）、触摸接触环、ECHO 小箭头、水雷冲击环。 |
| `FieldRenderer` | 包内。两条判定线和轨道区：线、刻度、全高的轨道分隔线、轨道发光、TIDE 命中时整条线的涌起（`swell`）、判定区提示（从线往外到 ±0.4 屏高的渐变，线上的不透明度 `ZONE_HINT_A` 0.07，2026-10-18 19:52 前 0.035）。 |
| `GameEngine` | 一局的模型：歌曲时间、触摸和按键输入、判定（TIDE 组、联合判定、hold 和 TIDE STREAM 的按住规则）、水雷和 LANE MINE（和同轨 note 撞在一起时 note 优先）、计分、事件流、暂停和恢复、自动播放。纯 Java，不碰 GL，加载后不分配内存。 |
| `GameEvent` | 引擎事件流里的一条：HIT、HOLD_START、HOLD_END、LOST、MINE、EMPTY，加上标志（如 `FLAG_JOINT` 联合判定、`FLAG_LANE_MINE` 轨道水雷接触）。画面按它播放特效和文字。 |
| `GameKeys` | 桌面键位：两排各 10 个键对应两条线的 ±1..±5 轨道，G/H、T/Y 对应插入的 0 号轨，空格只判定下线的 TIDE。 |
| `GameplayHud` | 包内。游戏 HUD：四角（分数、准确率、暂停按钮、难度和歌名、剩余时间、进度条）、中间的连击和判定字、误差条、实时计数；教程用的演示钩子（`demoPauseDoubleTap`、`demoJudgement`）。开着「FC/AR 指示」（`Settings.fcArIndicator`，2026-10-18 16:17）时分数按 `fcState` 变色：全共鸣淡蓝、FC 紫、断了白色。 |
| `GameplayScreen` | 游戏画面：先把输入交给引擎、再交给暂停控件；绘制场地、潮水、残影、音符、特效和 HUD；开场、倒计时、暂停菜单和结束。教程画面继承它（受保护的演示钩子在这里）。 |
| `GuidingText` | 教程的一条定时字幕：按拍子淡入淡出；样式 `GuidingText.Style`，锚点 `GuidingText.Anchor`（可以挂在 HUD 元素、判定线或叠在一起的 TIDE 上）。 |
| `IntroductionGuidelines` | 教程的全部字幕（英文和中文），按语言和暂停方式构造（`IntroductionGuidelines(zh, pauseMode)`）。拍点和位置按 Kelvin 手写的谱面（2026-10-17）手工排，直接改，改完按 §6.4 检查。 |
| `IntroductionScreen` | 教程画面：在普通游玩之上显示字幕和演示（判定字、EARLY / LATE、暂停双击、水雷），以及 SKIP 按钮；结果标记为教程（不记录谱面成绩，只记下“看过教程”和第一次的经验）。 |
| `NoteGuide` | NOTE GUIDE 音符图鉴面板（2026-10-12；在设置 GAMEPLAY 最后，2026-10-18 03:25 起在 OTHER 最后，**05:14 起哪里都不显示**，代码先留着）：两列六种音符，每种一个静止小图（自己持有一个 `NoteRenderer`，用它的 `paint*` 方法画）、名字和一句玩法（玩法文字在 `RULE` / `RULE_ZH`，名字来自 `NoteType`）。`layout(width, k, zh)` 按宽度、界面缩放和语言排版并缓存，`drawShapes` / `drawText` 两遍绘制。只显示，不接收输入。原来由 `screens/shell/NoteGuideRow` 放进设置列表。 |
| `NoteRenderer` | 包内。画所有音符：DROP、STREAM、MINE、LANE MINE 柱、TIDE（天空线珍珠串、地面线滚浪）、TIDE STREAM 水带、金色同步方块和发光，以及 sync 高亮边框（`setSyncHint`、`syncFrame` / `frameGlow`、`crestGlow`，粗细和强度是 `HL_*` 常量）。转过的 TIDE / TIDE STREAM 绕旋转中心画成无限长（`tideTurn` / `tideTurnEnd`，2026-10-16）。它的 `paint*` 方法也给残影、教程演示和 NOTE GUIDE 用。 |
| `NoteShapes` | 音符外形的纯数字和规则：上下线重叠时的挖空、TIDE 的 T4 外形、sync 珍珠（`showsPearl`）、LANE MINE 柱、按键离线远时按中心定位（`centreShiftX/Y`，天地两排在任何音符大小和屏幕比例下对齐）、旋转中心（`pivotShiftX/Y`，CHART_FORMAT §6.1.1）和转过的 TIDE 能伸多远（`tideTurnReach`）。渲染器和残影按它画，测试直接检查它。 |
| `PauseControl` | 右上角暂停按钮的位置、触摸区和输入规则（双击、按住 0.35 s、单击；哪些按下不算）。纯 Java。 |
| `PauseMenu` | 包内。暂停菜单（方案 B）：暗幕和三个大按钮 RETRY / RESUME / QUIT。 |
| `PreviewRun` | 「游戏界面」中间实时预览的那一局（2026-10-18 19:52）：自己持有一个 `GameEngine`（时钟用模拟的时间源 `SongClock.setTimeSource`）和 `FieldRenderer`、`NoteRenderer`、`FeedbackLayer`、`GameplayHud`（`setScoreOnly`：四角只剩分数和准确率），每局随机生成一张 40 次下落的谱（`DROPS`，120 BPM 每拍一个；双押之间隔 1–6 次下落，车道至少隔一条），自己按每个音符（±50 ms，约六分之一打成回响），连击到这一局的 5–12 时漏一个；打完 0.7 s 换新谱。按当前玩家的设置每帧绘制，用世界坐标画一整个游戏画面，由 `screens/shell/LivePreview` 缩小、裁剪进框里。不接收点击。 |
| `RunOverlay` | 包内。盖在 HUD 之上的时刻：3-2-1 倒计时（开场每步 1 s，在歌曲时钟开始前；恢复每步 0.6 s）、结束横幅（CLEAR / FULL COMBO / ALL RESONANCE）。开场的预备时间常量 `PREROLL_SEC` 也在这里。 |
| `ScaledGlitch` | 包内。连击数、倒计时这类会缩放的数字的故障闪烁，固定格宽，数字变化时不抖动。 |
| `TideWash` | 包内。TIDE 命中的潮水效果（光带浮起、退潮、变暗）和 TIDE STREAM 按住时的细光带。 |
| `TouchMarks` | 包内。「显示触控」（2026-10-18 19:52）：游戏中每个按着的手指下面一个白色的圈（半径 `R` 64）和中间的小点，跟着手指；松开后圈放大三分之一、`FADE_SEC` 0.25 s 淡出。每帧从引擎的指针表读位置，画在音符和特效上面、四角 HUD 下面。以前「显示触控」只在点到两个判定区外时闪淡点（那个淡点现在只有教程有）。 |
| `TrailGeometry` | 残影副本在某一时刻的位置和角度。纯 Java。 |
| `TrailPlan` | 残影的纯规则：哪些副本存在、多亮、超出预算时怎么稀疏。 |
| `TrailRenderer` | 包内。画残影：在活音符之前画每个副本，最远的先画，用和活音符相同的画法。 |

### 4.7 `fx`：打击特效

| 类 | 作用 |
|---|---|
| `FxPool` | 包内。一种打击特效的对象池，限制同时存在的数量。注意 `screens/result` 里有一个同名但无关的类。 |
| `HitFx` | 打击特效的入口：RESONANCE / ECHO 爆发、按住 STREAM 的光点流、LOST ×、水波纹；高 / 低 / 关画质和 100 个四边形的预算（超出时自动降级）。 |
| `ParticleAtlasFactory` | 包内。把 `assets/ParticleEffects/*.p` 加载到 `ui/Draw` 的共享图集上，让粒子和场景在同一张贴图里。 |
| `WaterRings` | 包内。DROP / STREAM 的水波纹圆环（RESONANCE 五个、ECHO 一个、按住时周期性的小圆环）。 |

### 4.8 `profile`：存档和设置

| 类 | 作用 |
|---|---|
| `AvatarSpec` | 程序生成头像“声呐印记”的参数（形状、花纹、配色、符号、边框）和各选项要潜到多深才解锁（`*_UNLOCK_METERS`）；`relock(depthM)` 把还没解锁的选项换回默认（重置记录用）。 |
| `ChartRecord` | 一个潜水员在一张谱面上的记录：最高分、准确率、最大连击、FULL COMBO / ALL RESONANCE、评级、次数、最近成绩、最好成绩的深度 `bestDepth`。 |
| `DeviceSettings` | 本机设置（`device.json`）：音量、各音频输出的延迟、画质、界面缩放、额外边距等，所有潜水员共用。 |
| `LegacyUserDataImporter` | 把原版的 `KandKGame/user.json`（按歌名记录的旧成绩）导入潜水员存档。 |
| `Depth` | 深度：每张谱的深度（40 ×（定数 + 加成）²，加成在 9600 万分以上每 200 万加 1）、玩家深度（最深 10 张平均）、卡片上的 NEXT 小目标、深度分档（`tierMeters`）和按档解锁的头像外观。这里都是正数，界面显示时加负号（`ShellFormat.appendDepth`）。 |
| `Grades` | 15 档评级（R+ … F）的分数门槛、颜色和比较。 |
| `PlainJson` | 包内。存档的 JSON 读写：只用 Jackson 流式核心，按公共字段自动绑定（兼容 Android 5–7）。 |
| `Profile` | 一个玩家的存档：名字、头像、贝壳、深度、买过的歌、成绩记录、个人设置、地图位置等；旧存档的转换（`migrateToSchema2`）。 |
| `ProfileStore` | 多账号存档：读写、原子保存、备份和损坏恢复、导入导出、记录成绩（`recordPlay`）、买单曲（`buySong`）、重置记录（`resetRecords`，2026-10-18 20:16 起清空全部进度：成绩、深度、记录、统计、贝壳、买下的曲目、剧情和隐藏曲目标记、教程、章节模式和要靠深度解锁的头像样式；名字、设置和校准保留）。 |
| `Shells` | 每局贝壳（原来的经验公式和奖励）和单曲默认价格。 |
| `Settings` | 跟随潜水员的设置：暂停方式 `pauseMode`、判定字位置、EARLY / LATE、语言、显示触控 `strayTouches`（2026-10-18 起只有开 / 关，旧的 AUTO 按关算；19:52 起是游戏中每个手指下的圈，见 `gameplay/TouchMarks`）、实时计数（设置里已没有这一行）、触感、sync 提示 `syncHint` 等。 |

### 4.9 `map`：潜水地图（选歌）

除 `MapScreen` 外都是包内类。

| 类 | 作用 |
|---|---|
| `AutoChip` | 歌曲卡片上的 AUTO（自动播放）开关。 |
| `CardChip` | 歌曲卡片上的一个难度按钮（难度名和定数）。 |
| `CardRecord` | 歌曲卡片上一个难度的成绩区：最高分、评级、通关标记、准确率、这张谱的深度（当前 / 满分）、排名、次数、最近走势、小目标。 |
| `DescendButton` | DESCEND 按钮（带下沉的双箭头和光晕）；没买的单曲变成「HOLD TO BUY」购买模式（按住 0.6 秒）。 |
| `SortPanel` | 所有曲目的排序面板：从 SORT 按钮升起，选分组（曲包 / 全部）、顺序（默认、歌名、定数、最高分、最近）和筛选（NEW / FULL COMBO / ALL RESONANCE，一次一个，再点取消）。 |
| `TextChip` | 排序面板里的文字小按钮（选中时填满青色，放不下时折成两行）。 |
| `CaveData` | 读 `assets/maps/` 的地图数据（每层一个 JSON）和剧情，格式见 `docs/MAP_FORMAT.md`。 |
| `CaveFx` | 层与层之间的转场：旋涡进入（X1）、下坠（X3）、上浮（X4）。 |
| `MapBubbles` | 在地图上拖动、点击时冒出的气泡。 |
| `MapCamera` | 地图镜头的物理：跟手拖动、惯性、越界回弹、双指缩放、飞行到节点。 |
| `MapFilter` | 筛选条件（NEW / FULL COMBO / ALL RESONANCE 之一或不筛），按镜头难度判断每首歌；`MapWorld` 不把不符合的歌排进网格。 |
| `MapGestures` | 地图的手势识别（拖动、双指、点击、长按、双击）。 |
| `MapHud` | 地图四周的 HUD：潜水员徽章（贝壳数、深度、离下一个头像外观的进度）、设置按钮、镜头难度切换、全图提示（捏合到全图时亮 1 秒），所有曲目的 SORT 按钮（右下角，底栏放不下时在难度切换上方），主界面右下角的「所有曲目」按钮和设置左边的故事模式 / 章节模式切换按钮（主线章节全部解锁后出现）。2026-10-16 起没有上浮牌、回到当前位置和 DEMO 标记。 |
| `MapNode` | 地图上的一个点（歌曲洞、剧情、竖井、航线洞口、上浮洞、未知信号）：位置、成绩、上锁和已读状态、动画状态。 |
| `MapRenderer` | 画地图世界：光斑、区域名、路、解锁虚线、各种点、锁、层名牌、路牌和提示，按贴图排序控制绘制次数。 |
| `MapScreen` | 公开。洞穴地图：把上面这些组合起来，处理层的切换、上浮（只在子层：缩到全图后 1 秒内连捏两次，或返回键 / Esc / PageUp；航线页和开着歌曲卡片时不上浮）、选歌（没有镜头难度谱面的歌点了只晃一下）、剧情、DESCEND（X2c）和从游戏返回的解锁动画（X5）。 |
| `MapStyle` | 地图专用常量：世界尺寸、节点样式、镜头物理、HUD 尺寸和少量地图专用颜色。 |
| `MapUi` | 地图上 HUD、卡片和面板的界面缩放。 |
| `MapWorld` | 一层地图的内容：从数据建点和路、算上锁和通关、所有曲目的网格排列（含筛选）。`rise` 点不建节点，只用于起始镜头和镜头范围。 |
| `PeekTip` | 长按节点时显示各难度最高分的小浮窗。 |
| `SongCard` | 歌曲卡片（宽屏在右侧、窄屏从底部弹出）：曲绘、成绩、信息、难度、AUTO 和 DESCEND。 |
| `StoryCard` | 剧情卡（X6）：墨水晕开、青色细线、打字机文字、翻页。 |
| `ThumbAtlas` | 节点上的圆形缩略图：在后台线程裁剪缩放曲绘，缓存到 `cache/thumbs/`。 |
| `VelocityTracker` | 拖动松手时的速度估计（卡片滑动、列表滚动）。 |

### 4.10 `screens`：其他画面

| 类 | 作用 |
|---|---|
| `CalibrationScreen` | 校准：音频延迟（跟着 120 BPM 节拍点击，取中位数），可以试打验证。画面延迟 2026-10-18 起取消，这里只剩这一页。 |
| `FirstRunScreen` | 首次设置三步：WHO'S DIVING?（头像和名字）、COMFORT（故障强度、减少动态 / 闪烁）、TUNE YOUR SONAR（音频输出和校准）。 |
| `ProfileScreen` | 玩家档案：潜水员信息，以及 STATS / DST 10 / RECENT / PLAYER（玩家，原来叫 DIVERS）四个标签页。 |
| `ResultScreen` | 结算画面 DIVE LOG：用 `screens/result` 的组件按时间线播放成绩。 |
| `VisualsScreen` | 游戏界面（GAMEPLAY VISUALS；19:52 前中文叫游玩界面视觉参数；2026-10-18 15:17 起的独立画面，像校准画面；设置 → 视觉 → 调整… 打开，返回回到视觉页）：中间是 `shell/LivePreview` 大预览（19:52 起由 `gameplay/PreviewRun` 自己打一局），右列第二行是多押辅助（19:52 从游玩页挪来），下面「判定字样」一条（ECHO、RESONANCE、LOST 按故障强度轮流闪），左右两列竖排的设置行（`SettingRow.setStacked`），两列用 `SettingsPage.setSpread` 排到底、放不下时缩小而不滚动；列宽、留白等数字在类开头的常量里。 |
| `SettingsScreen` | 设置：AUDIO、VISUAL、GAMEPLAY、OTHER 四页，每页的行在 `buildAudio` … `buildOther` 里建立（2026-10-18 起没有 TIMING：音频延迟和音频输出是 AUDIO 最上面的 TIMING 一节，校准按钮挂在音频延迟那行的微调按钮左边（`SliderRow.setLead`）；画面延迟取消；02:27 起 ACCOUNT 和 ABOUT 合并成 OTHER；03:25 起 OTHER 不分节；04:06 起 VISUAL 第一行的「调整…」打开实时预览页 `PAGE_PREVIEW`（行在 `buildPreview`，`railTab` 让它亮着 VISUAL），早 / 晚提示等挪到 GAMEPLAY 最前，实时计数没有行了，K & K 在开源许可证下面；05:14 起去掉音符图鉴；05:58 起 OTHER 最后一行左边 K & K 大字、右边版本号（`InfoRow.brand`，占满剩下的高度，05:28–05:58 是居中上下排）；06:03 起 GAMEPLAY 的两个早 / 晚提示合成一行 EARLY / LATE ON（`ChipsRow`，RESONANCE、ECHO 两个可多选的按钮）；14:29 起名字按 Kelvin 定的中文（谱面延时、音频预设、游玩界面视觉参数、多押辅助、过早 / 过晚提示等），英文从中文重译，谱面延时的两行小字是 `OFFSET_EARLY` / `OFFSET_LATE`，背景插画、打击音效时机、曲目试听、菜单音乐四行去掉，`Settings.sanitize` 把它们固定在默认值；15:17 起音频页多一行界面音效音量（`UI_NONE`，一直灰着），视觉页的「调整…」打开独立的 `VisualsScreen`，`PAGE_PREVIEW` 和 `buildPreview` 去掉；19:52 起谱面延时的说明三行（`OFFSET_DESC` 加两行提示，`SettingRow.setDescription3`），游玩页多了显示触控（暂停方式和震动之间），多押辅助挪进 `VisualsScreen`；20:16 起重置记录按住 2 秒后弹出 `shell/ResetDialog`，确认并输入玩家名后才调用 `resetRecords`）。OTHER 的 VIEW 打开开源许可证页（`buildLicenses`，数据在 `screens/shell/Licenses`），带 VIEW 的条目用 `showLicense` 显示全文。 |
| `WelcomeScreen` | 欢迎画面：开场动画、K & K 标志、TAP TO DESCEND、潜水员切换和设置入口。 |

### 4.11 `screens/result`：结算画面的组件

| 类 | 作用 |
|---|---|
| `Choreo` | 结算的时间线：固定节拍、可变阶段（深度、升阶、经验、升级）、跳过和停顿点。 |
| `EchoTrace` | 判定时间分布图（ECHO TRACE）：−135..+135 ms 的直方图画成对称波形。 |
| `FxPool` | 结算画面的装饰粒子（印章火花、升级光点、气泡）。和 `fx/FxPool` 无关。 |
| `GlyphQuads` | 字体之外的逐字特效：分数格子的错位故障、扫过文字的光带（结算印章；地图和歌曲卡片上 R / R+ 的白光也借它画，见 `map/MapRenderer.grade()`）。 |
| `GradeStamp` | 评级印章：砸下、冲击、抖动、PURE / S+ 的光泽。 |
| `JacketBlock` | 标题行和左栏：曲绘的入场、声呐边框、AUTO 水印。 |
| `ProfileStrip` | 潜水员条：头像、贝壳数的变化、+N 贝壳（点开明细弹窗）、深度的变化和离下一个头像外观的进度。 |
| `ResultKit` | 结算组件共用的绘制环境（服务、临时颜色、文字队列、变换辅助）。 |
| `ResultLayout` | 当前屏幕比例下结算画面每个元素的位置。 |
| `ResultModel` | 结算要显示的全部数值和字符串，在转场遮住画面时一次算好。 |
| `RoundedImage` | 不用着色器画圆角图片（曲绘）。 |
| `ScorePanel` | 分数的解码动画和与最高分的对比行。 |
| `StatsBlock` | 判定数量各行（含 MINES、PERFECT、RESONANCE CHAIN）、EARLY / LATE、准确率和最大连击、通关标记。 |
| `TextQueue` | 结算画面的文字按字体页分组绘制，控制绘制次数。 |

### 4.12 `screens/shell`：菜单画面共用的部件

欢迎、首次设置、玩家档案、设置和校准这些“外壳”画面共用的基类、行和对话框。全部公开。

| 类 | 作用 |
|---|---|
| `ActionRow` | 设置行：右侧一到两个按钮（如 CALIBRATE…、REPLAY）。 |
| `AvatarEditorSheet` | 头像编辑器：大预览、随机、逐项切换形状 / 花纹 / 配色 / 符号 / 边框（未解锁的可以预览）。 |
| `AvatarView` | 会动的头像：翻转切换、随机时的动画。 |
| `ChartRows` | DST 10 和 RECENT 列表行共用的缩略图和难度标签。 |
| `ChipsRow` | 设置行：右侧几个可以多选的开关按钮，样子和个人页 STATS 的难度按钮一样，全都不选也可以（2026-10-18 06:03，GAMEPLAY 的 EARLY / LATE ON：RESONANCE、ECHO）。 |
| `ChoiceRow` | 设置行：分段选择（如 OFF \| LOW \| HIGH）。 |
| `CreateDiverSheet` | 新建潜水员的弹出卡片。 |
| `DiverActionsDialog` | 潜水员的改名、导出、删除菜单。2026-10-18 起没有入口（DIVERS 卡片只切换），代码保留，以后要恢复删除时再接上。 |
| `DiverCardRow` | 当前潜水员和切换 / 管理按钮的设置行。2026-10-18 起设置里只剩 BACKUP CODE 和 RESET RECORDS（在 OTHER 页），这一行不再使用。 |
| `DiverForm` | “WHO'S DIVING?” 的内容：头像预览、随机、配色、名字输入（首次设置和新建潜水员共用）。 |
| `DiverList` | 所有本地潜水员的显示缓存（名字、等级、深度、段位、最后一次下潜）。 |
| `DiverStats` | 玩家档案页的统计数字：贝壳、深度、游玩次数和时长，每个难度玩过 / 通过 / FULL COMBO / ALL RESONANCE 的谱面数，最高分按每 100 万分一格的分布（STATS 直方图）和平均分，下潜曲线（每个点的深度 `history` 和时间 `historyT`）。 |
| `DiveChart` | DST 10 顶上的下潜曲线 DIVE PROFILE（2026-10-18）：横轴是时间，刻度从一分钟到一年自动选，刻度下面写日期；双指捏合 / 滚轮放大缩小（最窄 10 分钟），拖动平移，双击回到全部；点圆点弹出那次下潜的日期、时间和深度。右边两个深度刻度单独占一栏。 |
| `DiversTab` | PLAYER 标签页（原来叫 DIVERS）：潜水员卡片（2026-10-18 起点卡片只切换，最后一行是 `ShellFormat.lastActivity` 的「LAST ACTIVITY: 日期 时间」）、新建、导入。 |
| `ImportDialog` | 导入备份码（剪贴板，桌面还列出导出文件夹里的文件）。 |
| `InfoRow` | 设置列表里的只读行：分节标题或信息行；`brand` 是 OTHER 最后的品牌行（左边用开始画面的 glitch 字写 K & K，右边版本号，2026-10-18 05:58）：它占满列表剩下的高度（`fills`），在 `BRAND_MIN_H` 到 `BRAND_HEIGHT` 之间；K & K 字高最多 `BRAND_CAP`，行矮时是行高的一半，版本号按 `BRAND_VALUE_CAP` / `BRAND_CAP` 的比例跟着。 |
| `Licenses` | 开源许可证页的数据（2026-10-18）：K & K 自己（MIT）、三种字体（OFL）和随游戏打包的软件库，每条有版权行、许可证名和全文文件（`assets/licenses/`，字体的在 `assets/fonts/OFL-*.txt`）。`DESKTOP` / `ANDROID` 标记只在电脑版或手机版里有的库。改 Gradle 依赖时跟着改。 |
| `LivePreview` | `VisualsScreen` 中间的预览框（2026-10-18 19:52 重写）：按屏幕比例放一整个缩小的游戏画面，画背景渐变和背景亮度的暗幕，用画笔的变换矩阵和一次裁剪把 `gameplay/PreviewRun` 那一局画进框里，再画边框和右下角的「实时预览」。不接收点击。重写前是自己画的小场地（固定六个音符循环、点一下出特效、右上角小分数），判定区提示还是 ±0.1 的旧尺寸。 |
| `LogTab` | RECENT 标签页（2026-10-17 前叫 LOG）：最近 20 局。 |
| `Modal` | 弹出卡片和对话框的基类（暗幕、滑入、返回键关闭）。 |
| `NoteGuideRow` | 把 `gameplay/NoteGuide` 音符图鉴面板放进设置列表的行（原来在 GAMEPLAY 的 LANGUAGE 下面，2026-10-18 03:25 起在 OTHER 最后；**05:14 起没有页面用它**，代码先留着）。高度随列表宽度、界面缩放和当前语言变化；只显示，在上面拖动会滚动整页。 |
| `ProfileHeader` | 玩家档案左栏：头像、名字（点名字原地改名，手机上只弹键盘）、贝壳、深度，下面 PLAYS 和 PLAY TIME 两条（平板在顶部横条右边）。 |
| `ProfileTab` | 玩家档案右栏一个标签页的基类（可滚动区域）。 |
| `RenameDialog` | 改名对话框（`DiverActionsDialog` 用；2026-10-18 起没有入口，个人页点名字原地改名）。 |
| `ResetDialog` | 重置记录的确认框（2026-10-18 20:16）：设置 → 其他 → 重置记录按住 2 秒后弹出；第一步显示头像、玩家名、贝壳和深度和会清空的内容，点「确认」进第二步；第二步把玩家名完整输入一遍，对上了「重置」才能点（首尾空格不算）。「重置」调用设置画面传进来的动作（`ProfileStore.resetRecords`）；取消、返回或点框外关闭。 |
| `ScrollArea` | 菜单列表的滚动物理和裁剪（拖动、惯性、回弹）。 |
| `SettingRow` | 设置列表一行的基类：左侧标题、说明（`setDescription2` 可加第二行，2026-10-18 14:29；`setDescription3` 加第三行，这一行随之高一行，谱面延时用，19:52）和本机设置的 SHARED / 公用 标记（2026-10-18 04:48 前叫 DEVICE / 本机），右侧控件。`fills` / `fillHeight` 让一行占满列表剩下的高度（每页最多一行）。`setStacked` 竖排：标题一行（数值或开关在右边）、说明，控件在下面占满整行（`VisualsScreen` 用，15:17）。 |
| `SettingsPage` | 设置的一页：可滚动的设置行列表。各行放得下时不会滚动（底部 40 的留白只在放不下时才算，2026-10-18 06:03）；`fills` 的行拿到其他行剩下的高度；`setSpread` 把剩下的高度平分给各行，让一列排到底（`VisualsScreen`）。 |
| `ShellFormat` | 外壳画面共用的文字格式化（不分配内存）。 |
| `ShellScreen` | 外壳画面的基类：背景、标题栏、返回、转场和弹出卡片的管理。 |
| `SliderRow` | 设置行：滑块和右侧的数值。 |
| `StatsTab` | STATS 标签页：四个难度开关（只写难度名，可点亮组合，至少亮一个），亮着的难度合计的 CHARTS CLEARED（通过/总数）/ FULL COMBOS / ALL RESONANCE，和一直到底的最高分直方图（画法同结算界面的 `EchoTrace`）。 |
| `SwitcherSheet` | 欢迎画面的玩家切换卡片。 |
| `TabRail` | 设置的分类导航（宽屏在左侧，4:3 在顶部）。 |
| `TapStats` | 校准点击的统计（中位数和离散度）。 |
| `TapStrip` | 校准时的点击散点条（EARLY 在左、LATE 在右）。 |
| `TextRow` | 设置列表里的一大段纯文字（许可证全文）：按原文换行，只画看得见的行，不接收输入。 |
| `Tappable` | 由所有者自己绘制外观的可按控件（卡片、行、自定义芯片）。 |
| `ThumbCache` | 玩家档案列表用的小缩略图（后台解码，合进一张贴图）。 |
| `ToggleRow` | 设置行：右侧开关，点整行也能切换。 |
| `TopTab` | DST 10 标签页（2026-10-18 前叫 TOP 10）：上面固定的下潜曲线（`DiveChart`），下面滚动的是 DEEPEST 10（决定你深度的最深 10 张，不满用空位）和 OVERFLOW（第 11 到 15 深的 5 张，不算进深度）。 |
| `WidgetSet` | 一组可按控件的输入分发、更新和绘制。 |


### 4.13 `transition`

| 类 | 作用 |
|---|---|
| `TransitionLayer` | 全局转场，由 `Main` 持有、画在画面之上：CURTAIN、DIVE（下潜进游戏：水墙合上后曲绘左移，右边依次弹出曲作者、歌名和难度、谱师、画师）、ASCENT（和 DIVE 一样的水帘，到结算）、IRIS（光圈回地图）。画面被完全盖住时执行加载回调。有曲绘时 DIVE 至少盖住 `DIVE_ART_COVER` 4.5 s，没有曲绘（教程、重来）是 `DIVE_MIN_COVER` 1.5 s。 |

### 4.14 `ui`：共用视觉服务和控件

| 类 | 作用 |
|---|---|
| `Anim` | 动画用语：时长常量、缓动函数、可复用的 Tween。 |
| `ArtCache` | 全屏背景图（`welcome.png`、`Blue.png`、`ResultPage.png`、`ChartVisualizer.png`）的引用计数缓存。 |
| `Avatar` | 程序生成的“声呐印记”头像：渲染到缓存的帧缓冲，边框实时绘制。 |
| `Backdrop` | 共用的程序海水背景（着色器、光柱、焦散、雪花、气泡、声呐），颜色随深度变化。 |
| `Button` | 按钮（PRIMARY、SECONDARY、DESTRUCTIVE 等样式，可设按住时长）。 |
| `Chip` | 小标签 / 芯片控件，可选中。 |
| `DepthPalette` | 六个海洋深度层的配色表，按深度（米）插值。 |
| `DepthParams` | 深度配色算出的一组参数（由 `DepthPalette` 填写）。 |
| `Draw` | 共用绘图服务：唯一的 SpriteBatch、程序生成的 1024² 图集（所有界面、背景、粒子的小图，含 `ring_water` 水波纹圆环）、ShapeDrawer。`ringArc()` 画抗锯齿的粗圆环（地图的分数环和封面边）。 |
| `Fonts` | 字体服务：按“角色”（DISPLAY、NUMBER、UI、UI_BOLD 等）和字高取字体，按屏幕像素栅格化并缓存，中文自动换到中文字体。 |
| `FrameProbe` | 帧时间探测：背景太慢时自动切到低画质。 |
| `GlitchState` | 单个元素的故障状态（静止等级、突发、随机种子）。 |
| `GlitchText` | 故障风文字：G0–G3 各级效果，所有故障字都经过它。 |
| `Haptics` | 触感反馈（振动），由设置开启。 |
| `Icons` | 程序绘制的图标（暂停、设置、返回等）。 |
| `KkScreen` | 所有画面的基类：世界视口 1920 × 1080、共用的 Batch、把触摸换算成世界坐标、返回键（BACK / Esc → `onBack`）、转场期间锁定输入。 |
| `KkServices` | 接口：`KkScreen` 依赖的服务（由 `Main` 实现）。 |
| `Lang` | 界面语言（2026-10-18）：`Lang.of(英文, 中文)` 是跟着语言走的文字（建一次，换语言后自动换字），`Lang.t(英文, 中文)` 取当前语言的一句，`Lang.zh()` 判断当前是不是中文。`Main.applySettings()` 按当前潜水员的 LANGUAGE 设置。选歌地图（`map` 包）不用它，保持英文。 |
| `Panel` | 卡片、暗幕、分隔线、分节标题的绘制辅助。 |
| `Pressable` | 所有可按控件的基类：按下缩放、变亮、波纹、触感，抬起时生效。 |
| `Ripple` | 按下时的波纹。 |
| `Segmented` | 分段选择控件。 |
| `Slider` | 滑块控件。 |
| `SonarField` | 共用的声呐波纹（背景和画面里的扩散圆环）。 |
| `Spring` | 阻尼弹簧（按钮缩放等动画）。 |
| `TextField` | 文字输入框（手机用系统输入对话框，桌面直接输入；`Mode.KEYBOARD` 在手机上只弹出键盘）。 |
| `Theme` | 所有颜色常量（DESIGN §1.2），以及取派生颜色的辅助方法；定数上标的大小和位置（`CONST_MARK_*`）。 |
| `Toast` | 提示条队列（顶部中间弹出）。 |
| `Toggle` | 开关控件。 |
| `Ui` | 视口计算：可见区域、安全边、界面缩放、触摸目标大小。 |
| `UiKit` | 把所有视觉服务打包，统一创建、调整大小、恢复和释放。 |

### 4.15 启动器

| 类 | 作用 |
|---|---|
| `lwjgl3/Lwjgl3Launcher` | 桌面入口（`main`）：窗口 1280 × 720，可调整大小（最小 960 × 540），垂直同步，设置窗口图标，创建 `Main`。 |
| `lwjgl3/StartupHelper` | 启动辅助：macOS 上自动用 `-XstartOnFirstThread` 重启 JVM，Windows 上避免 LWJGL 解压原生库时的常见崩溃，Linux NVIDIA 显卡上的兼容处理。 |
| `android/AndroidLauncher` | Android 入口：全屏沉浸、可画到刘海区域、保持屏幕常亮，创建 `Main`。横屏（可翻转 180°）和振动权限写在 `android/` 目录的 `AndroidManifest.xml` 里。 |

---

## 5. 要改……在哪里改

| 想改的东西 | 在哪里 | 说明 |
|---|---|---|
| **判定窗口** | `play/Judgement`（`TIER1_MS`、`TIER2_MS`、`PERFECT_MS`）；`Params.MINE_WINDOW_MS`（水雷 ±66.67 ms）、`Params.LANE_MINE_LEAD_MS`（轨道水雷到线才封锁）；`GameEngine`（`HOLD_LEAVE_MS`、`NEIGHBOUR_TOLERANCE`、`MINE_BOX`、`LANE_MINE_BOX`）；判定区高度 `Params.JUDGE_ZONE_HALF` | 规则见 ARCHITECTURE §5、§10、§13、§20，谱面作者看 `docs/CHART_FORMAT.md` §6 |
| **计分公式** | `play/ScoreKeeper.score()` / `baseScore()`；权重和满分在 `play/Judgement`；评级阈值在 `profile/Grades`，深度在 `profile/Depth`，贝壳在 `profile/Shells` | FULL COMBO / ALL RESONANCE：`ScoreKeeper.isFullCombo()` / `isAllResonance()`。旧存档的分数换算在 `ProfileStore` |
| **音符的样子** | 数字和规则：`gameplay/NoteShapes`；绘制：`gameplay/NoteRenderer`；残影：`gameplay/TrailRenderer`；故障：`gameplay/GlitchCapture`（图样的数字在它的常量和 `emit` 里）；颜色：`ui/Theme`；小图：`ui/Draw` 的图集 | sync 的规则（金珍珠和高亮边框共用）在 `NoteShapes.showsPearl`；sync 高亮边框的粗细、强度在 `NoteRenderer` 的 `HL_*` 常量，颜色是 `Theme.SYNC_HIGHLIGHT`、`SYNC_HIGHLIGHT_SKY`、`MINE_HIGHLIGHT`；透明度大于 1 的闪烁在 `Note.shownOpacity`；上下线重叠的两个常量 `SKY_HOLE_PAD`、`SKY_TIDE_STREAM_INSET`（改成 `*_FIRST` 就是第一版方案） |
| **HUD 的位置和大小** | `gameplay/GameplayHud`（四角、连击、判定字、误差条、实时计数）；暂停按钮的位置和触摸区：`gameplay/PauseControl`；安全边：`ui/Ui` | 布局数字见 DESIGN §5.2–§5.4 |
| **暂停规则** | `gameplay/PauseControl`（双击 300 ms、按住 0.35 s、单击；`judges` 决定哪些按下不算）；输入顺序和按键（返回键、Esc、Pause）：`gameplay/GameplayScreen`；暂停菜单：`gameplay/PauseMenu`；默认方式：`profile/Settings.pauseMode`；引擎的暂停和恢复：`GameEngine.pause()` / `resume()` | 规则见 DESIGN §5.12 |
| **教程的课程和字幕** | 谱面 `assets/Introduction/Surface.json` 是 Kelvin 手写的（2026-10-17）；字幕、拍点常量和暂停那一行的三种说法（`PAUSE_LABEL_EN` / `PAUSE_LABEL_ZH`）在 `gameplay/IntroductionGuidelines`，按谱面手工排，直接改。演示动画和 SKIP：`gameplay/IntroductionScreen`；字幕的样式和锚点：`gameplay/GuidingText`。改完检查字幕有没有挡住音符 | 课程表见 DESIGN §10.2，步骤见 §6.4 |
| **打击特效和粒子** | `fx/HitFx`（入口、画质、预算）；粒子文件 `assets/ParticleEffects/*.p`（发射器名称不能改，颜色由代码覆盖）；水波纹：`fx/WaterRings`；TIDE 的潮水：`gameplay/TideWash`；水雷冲击环和轨道上的字：`gameplay/FeedbackLayer`；判定线的闪光和涌起：`gameplay/FieldRenderer` | 见 `docs/ASSET_SLOTS.md` §4 |
| **颜色** | `ui/Theme`（全部颜色常量）；深海背景随深度的配色：`ui/DepthPalette` | 地图专用的少量颜色在 `map/MapStyle` |
| **字体和中文字库** | `ui/Fonts`（角色、字高、中文自动切换）；字体文件 `assets/fonts/` | 中文字体 `NotoSansSC-Medium.ttf` 是 Noto Sans SC 的 GB2312 子集，加中文前先确认字都在里面（§6.5）。授权见 `docs/ASSET_SLOTS.md` §3 |
| **谱面读取** | `elements/ChartMapper`（解析、v1 转换、去重、加载报告）；数据模型 `elements/*`；曲库和文件查找：`library/SongLibrary`；谱面头：`library/ChartHeaderReader` | 格式见 `docs/CHART_FORMAT.md` |
| **地图** | `map/MapScreen`（总控）、`CaveData`（读数据）、`MapWorld`（一层的内容）、`MapRenderer`（绘制）、`MapCamera`（镜头物理）、`MapHud`、`SongCard`、`StoryCard`、`CaveFx`、`MapStyle`（常量） | 每层的布局在 `assets/maps/*.json`，格式见 `docs/MAP_FORMAT.md` |
| **设置** | 数据：`profile/Settings`（跟随潜水员）、`profile/DeviceSettings`（本机）；界面：`screens/SettingsScreen`（`buildAudio` … `buildOther`），游戏界面（游玩界面视觉参数）在 `screens/VisualsScreen`；行控件：`screens/shell/ToggleRow`、`ChoiceRow`、`ChipsRow`、`SliderRow`、`ActionRow`、`InfoRow`；生效：`Main.applySettings()` | 步骤见 §6.3 |
| **音符图鉴（NOTE GUIDE，2026-10-18 05:14 起不显示）** | 面板：`gameplay/NoteGuide`（六句玩法 `RULE` / `RULE_ZH`、排版尺寸、小图的长度）；音符名字：`elements/NoteType`；设置列表里的行：`screens/shell/NoteGuideRow`。05:14 以前在 `SettingsScreen.buildOther` 里加在最后（03:25 前在 GAMEPLAY 的 LANGUAGE 后面）；要放回来，在某一页的 build 方法里 `p.add(new NoteGuideRow(...))` | 小图直接用 `NoteRenderer` 的画法画，音符外观改了会自动跟着变。改中文玩法前先按 §6.5 检查字库。规格见 DESIGN §9.2 |
| **存档** | `profile/ProfileStore`（位置、保存、恢复、导入导出）；内容：`Profile`、`ChartRecord`、`Settings`、`DeviceSettings`；JSON：`PlainJson`；旧存档导入：`LegacyUserDataImporter` | 桌面存档在用户主目录的 `KandKGame/profiles/`（每个潜水员一个 `<id>.json`，加上 `index.json`、`device.json`），导出在 `KandKGame/export/`；Android 在应用自己的存储里 |
| 歌曲时钟、音频延迟 | `audio/SongClock`、`audio/AudioService`；校准：`screens/CalibrationScreen` | |
| 键盘键位 | `gameplay/GameKeys` | |
| 结算画面 | `screens/ResultScreen` 和 `screens/result/*`（时间线在 `Choreo`，位置在 `ResultLayout`，数值在 `ResultModel`） | |
| 转场 | `transition/TransitionLayer` | |
| 背景 | `ui/Backdrop`、`ui/DepthPalette` | |
| 桌面窗口 | `lwjgl3/Lwjgl3Launcher` | |
| Android 方向、权限 | `android/` 目录的 `AndroidManifest.xml`；`android/AndroidLauncher` | |

---

## 6. 常见任务

### 6.1 加一首歌

完整步骤见 `docs/ASSET_SLOTS.md` §5。简要：

1. 新建 `assets/Charts/<歌曲 id>/`。文件夹名就是歌曲 id，也是存档的键，之后不要改名。
2. 放入音乐 `<歌曲 id>.ogg`（或 `.mp3` / `.wav`）、至少一个谱面（`Surface.json`、`Shallow.json`、`Deep.json`、`Abyss.json`）、曲绘 `jacket.png`；可选 `bg.png`、`result.ogg`。
3. 在 `assets/Charts/index.json` 某个章节的 `songs` 里加上 id（可以用对象写法加 `previewStartMs`、`resultMusicStartMs`、`map` 微调）。
4. 重新构建（更新 `assets/assets.txt`），在地图上检查。

代码不用改：`library/SongLibrary` 会自动找到文件。没写进 manifest 的文件夹会被追加到最后一个已开放的章节。

### 6.2 加一个章节

在 `assets/Charts/index.json` 的 `chapters` 里按深度顺序加一项（`id`、`title`、`titleZh`、`subtitle`、`depthMeters`、`depthBottom`、`uncharted`、`songs`），见 `docs/ASSET_SLOTS.md` §6。地图上的位置要在 `assets/maps/` 里放（见 `docs/MAP_FORMAT.md` §7）；没放到任何一层的歌会自动出现在“单曲”层最右边和“所有曲目”里。

### 6.3 加一个设置

1. **数据**：跟随潜水员的设置加在 `profile/Settings`，本机设置加在 `profile/DeviceSettings`：加一个带默认值的公共字段，并在 `sanitize()` 里把非法值改回合法范围。`PlainJson` 按字段名自动读写，不用注册；旧存档里没有这个键时用默认值。
2. **界面**：在 `screens/SettingsScreen` 对应页的 `build…` 方法里加一行，用 `ToggleRow`、`ChoiceRow` 或 `SliderRow`。行的 `get` 读字段，`set` 写字段后调用 `diverChanged()`（潜水员设置）或 `deviceChanged()`（本机设置），它们会标记存档待保存并调用 `Main.applySettings()`。
3. **生效**：需要通知服务的设置在 `Main.applySettings()` 里处理；游戏中用到的在 `GameplayScreen.begin(...)` 读取（每局开始时）。
4. 新文字要用英文大写的界面风格，并且都要有中文：标题和说明写成 `Lang.of("ENGLISH", "中文")`（见 §6.6），按 §6.5 检查字库。下潜画面、结算画面和选歌地图只写英文（§6.6 第 4 条）。
5. 在 DESIGN §9.2 的设置表里补上这一行。

### 6.4 改教程（手写谱面，手排字幕）

教程的谱面 `assets/Introduction/Surface.json` 是 Kelvin 手写的（2026-10-17，102 个音符）；字幕类 `gameplay/IntroductionGuidelines` 里每条字幕的拍点和位置是按这份谱面手工排的。原来的生成脚本已经不用了。

1. **改谱面**：直接改 `Surface.json`（或用谱面编辑器）。改了音符的拍点、轨道或线，就要看一遍附近的字幕：字幕（尤其是带底板的说明）不能挡住还没判定的音符。
2. **改字幕**：在 `IntroductionGuidelines` 里改 `add(...)` 那一行（英文、中文、开始和结束拍、淡入淡出、位置、字号、样式、锚点），演示窗口改类里的常量（如 `MINE_DEMO_START`、`LANE_MINE_BEAT`）。居中的字幕要在它那一课的音符出现以前消失；来不及时放在一侧的底板上，压在这段时间里空着的轨道上。英文说明可以断成两到四行。
3. **检查**：在 16:9、20:9 和 4:3 下各看一遍教程（英文和中文）：每条字幕不能挡住还没判定的音符、不能超出安全区、不能和别的字幕重叠。结尾两句没有底板的对白压在最后的和弦上可以接受（音符从字后面经过）。
4. 演示动画、SKIP 按钮这些代码在 `gameplay/IntroductionScreen`，它读 `IntroductionGuidelines` 里的拍点常量。
5. DESIGN §10.2 的课程表跟着改。

### 6.5 加中文文字（字库子集）

`assets/fonts/NotoSansSC-Medium.ttf` 只包含 GB2312 里的字（约 7,600 个字形），常用简体字都有，生僻字没有；缺的字显示不出来。加新的中文文字前，可以用 fontTools（`pip install fonttools`）检查：

```bash
python3 -c "
from fontTools.ttLib import TTFont
cmap = TTFont('assets/fonts/NotoSansSC-Medium.ttf').getBestCmap()
s = '要检查的中文文字'
print([c for c in s if ord(c) not in cmap] or '都在字库里')
"
```

缺字时换一个说法，或从完整的 Noto Sans SC 重新做子集。

### 6.6 中英文界面（`ui/Lang`）

2026-10-18 起，设置 → OTHER → LANGUAGE（04:34 以前在 GAMEPLAY）选「中文」时，除了选歌地图、下潜画面和结算画面，所有界面都显示中文（Kelvin 2026-10-18 00:24；下潜和结算 16:41–16:45 改回和 EN 一样，17:22 起暂停菜单和结算的按钮、确认框、贝壳明细又跟着语言）。

1. **固定的文字**（标题、按钮、设置行、标签页）：在建界面时写 `Lang.of("ENGLISH", "中文")`。它是一个 `CharSequence`，每次画的时候读当前语言，所以换语言后不用重建界面。
2. **每帧拼出来的文字**（分数说明、提示、日期）：用 `Lang.t("ENGLISH ", "中文")` 取当前语言的那一句再 append；缓存了文字的地方要记下缓存时的语言（例如 `WelcomeScreen.refreshChip` 的 `chipZh`、`DiveChart` 的 `tickZh`），语言变了重新拼。语言跟着潜水员走：换潜水员时 `ShellScreen.afterActiveChange` 先按新潜水员设好语言，再刷新各页。
3. **第一次显示中文会卡一下**：中文字形是用到时才栅格化的。在画面被转场盖住的时候先量一次宽度（`fonts.width(...)`）把字形准备好，例如 `ResultScreen.warmText`、`GameplayHud` 和 `PauseMenu` 的 prewarm、`IntroductionScreen.begin`。
4. **保持英文的**：选歌地图（`map` 包）、歌名和曲师、难度名（SURFACE 等）、DST 10 / DEEPEST 10 / OVERFLOW、许可证原文；游玩时用 glitch 字体画的判定字（RESONANCE / ECHO / LOST）和结束横幅（CLEAR / FULL COMBO / ALL RESONANCE），因为这个字体没有中文字形；整个下潜画面（AUTOPLAY、COMBO、EARLY / LATE、教程的 HOLD TO SKIP，Kelvin 16:41）和结算画面（16:45），这些地方不用 `Lang`；但结算的 RETRY / CONTINUE、谱面延时确认框和贝壳明细跟着语言（`ResultScreen` 的 `Lang.of`、`ResultModel.zh`，17:22），分数明细和柱状图的 峰值 / 平均 也是（20:34），还有谱面延时提示、特效调低的提示和条上那行小字（`ResultModel.hintText`、`fxNoticeText()`、`stripNote`，21:37）。暂停菜单只写当前语言（`PauseMenu`，17:22）。教程字幕还是跟语言走。
5. **游戏 HUD 的字**：`GameplayHud.label(...)` 逐个字母画（带字距），只有英文（03:1x 到 16:41 之间中文模式用过 `COMBO_ZH` 等整串画的中文，已经去掉）。

---

## 7. 检查和测试

* **编译**：Gradle 构建（`./gradlew lwjgl3:jar`，或在 IntelliJ 里运行 `lwjgl3`）。
* **谱面文档里的 JSON 例子要能被真实的 `ChartMapper` 加载**：改 `CHART_FORMAT.md` 时保持这些代码块完整、可解析。
* **其他测试**（引擎、计分、残影、TIDE、暂停、存档、截图、性能）不在仓库中。

---

## 8. 本手册的类名检查

每次改完本手册，可以在仓库根目录运行下面的脚本：它列出代码里有、但手册里没提到的类（第一部分），以及手册里写了 `包/类`、但代码里找不到的类（第二部分）。两部分都应该没有输出。

```bash
# 1. 代码里每个类都要在手册里出现
find core/src/main/java lwjgl3/src/main/java android/src/main/java -name '*.java' \
  | sed 's#.*/##; s#[.]java$##' | sort -u \
  | while read -r c; do grep -qE "[\`/]$c([\`.(]|$)" docs/DEVELOPER.md || echo "手册缺少：$c"; done
# 2. 手册里写的“包/类”都要存在
grep -oE '`(audio|elements|fx|gameplay|library|map|play|profile|screens|screens/result|screens/shell|transition|ui|lwjgl3|android)/[A-Z][A-Za-z0-9]*' docs/DEVELOPER.md \
  | tr -d '`' | sort -u \
  | while read -r p; do ls core/src/main/java/org/kelvinizer/$p.java lwjgl3/src/main/java/org/kelvinizer/$p.java \
      android/src/main/java/org/kelvinizer/$p.java 2>/dev/null | grep -q . || echo "代码里没有：$p"; done
```
