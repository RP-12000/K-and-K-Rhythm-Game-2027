# K & K（Kelvinizer and Kathlina）

一款以“下潜”为主题的双判定线节奏游戏，用 [libGDX](https://libgdx.com/) 写成，支持桌面（Windows / macOS / Linux）和 Android。

> 这是一个男孩和一个女孩的故事，他们被困在一个黑暗的世界里。
> 唯一的出路，是向未知的更深处下潜。
> 并揭开他们最深的秘密，和那个悲剧的真相。
>
> ——教程开场

---

## 1. 游戏是什么

* Kelvinizer 和 Kathlina 两个人一路向深处下潜。选歌界面是一张**潜水地图**：每个章节是一层更深的海（SHORELINE 浅滩、TWILIGHT 暮光层、MIDNIGHT 午夜层，再往下还有两层未探明），每首歌是潜水路线上的一个浮标。最上面的 LAUNCH POINT 浮标是教程。
* 游戏画面有**两条判定线**：音符**落到下面的地面线**，或者**升到上面的天空线**。在线附近点击才算，离线太远的点击不算。
* 六种音符：

| 音符 | 谱面里的类型 | 怎么打 |
|---|---|---|
| DROP 水滴 | `tap` | 落到线上时点一下 |
| STREAM 水流 | `hold`（有时长） | 按住，同一根手指按到结尾 |
| MINE 水雷 | `mine` | 别碰；碰到会断连击 |
| TIDE 潮汐 | `universal` | 整条线：线上任意位置点一下 |
| TIDE STREAM 潮汐水流 | `universal`（有时长） | 任意手指按在线上就算 |
| LANE MINE 轨道水雷 | `mine`（有时长） | 变红的时候别碰这条轨道 |

* 带**金色珍珠**的音符和别的音符在同一时刻，要一起打（水雷上是红色小珍珠，那是提醒别碰）。有的音符后面拖着一串**残影**：残影只是光，不用打。
* **判定**：RESONANCE（±66.67 ms）、ECHO（±133.33 ms）、LOST；RESONANCE 里 ±33.33 ms 以内的另计为 PERFECT。偏早或偏晚会显示 EARLY / LATE。
* **结果**：打完就是 CLEAR；没有 LOST、也没有碰到任何水雷是 FULL COMBO；FULL COMBO 并且全部 RESONANCE 是 ALL RESONANCE。全部 RESONANCE 是 100,000,000 分，每个 PERFECT 再加 1 分。详细规则见 `docs/CHANGES.md` §3。

---

## 2. 需要什么

| | 版本 | 说明 |
|---|---|---|
| JDK | **21** | 运行 Gradle 和游戏都用它 |
| Java 语言级别 | **11** | 由 Gradle 设定（`sourceCompatibility = 11`），不能用更新的语法 |
| Gradle | **9.5.1**（wrapper） | 用仓库里的 `gradlew` / `gradlew.bat`，不用另装 Gradle；第一次运行要联网下载 Gradle 和依赖 |
| libGDX | **1.14.1** | 另有 LWJGL 3.4.1、ShapeDrawer 2.5.0、jackson-core 2.17.0；版本号都在 `gradle.properties` |
| Android（可选） | compileSdk 36，minSdk 21 | 需要 Android SDK；Android Gradle Plugin 9.2.1 |
| 音乐文件 | 10 个 WAV | **不在 git 仓库里**，要另外放进 `assets/`（见 §5） |

---

## 3. 用 IntelliJ IDEA 打开和运行（桌面版）

1. **打开工程**：File → Open，选仓库根目录（有 `settings.gradle` 的文件夹），作为 Gradle 工程打开并信任它。
2. **选 JDK 21**：File → Project Structure → Project → SDK 选 JDK 21；Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM 也选这个 JDK 21。语言级别不用改，Gradle 会按 Java 11 编译。
3. **等 Gradle 同步完成**。
   * 如果因为 `android` 模块同步失败（例如提示找不到 Android SDK）：安装 Android SDK，然后在仓库根目录建 `local.properties`，写一行 `sdk.dir=<Android SDK 的路径>`（这个文件不进 git），或者设置环境变量 `ANDROID_HOME`。
   * 只想跑桌面版的话，也可以临时把 `settings.gradle` 最后一行里的 `'android'` 删掉再同步。**这个改动不要提交。**
4. **运行**：打开右侧的 Gradle 工具窗口，双击 `lwjgl3` → Tasks → application → `run`。
   * 命令行也一样：`./gradlew lwjgl3:run`（Windows：`gradlew.bat lwjgl3:run`）。
   * 这个任务会把工作目录设成 `assets/`，加上需要的 JVM 参数，并且每次运行前更新资源清单 `assets/assets.txt`。
5. **另一种运行方式**：新建一个 Application 运行配置：
   * 模块（classpath）：`lwjgl3` 的 main（名字形如 `<文件夹名>.lwjgl3.main`）。
   * 主类：`org.kelvinizer.lwjgl3.Lwjgl3Launcher`。
   * 工作目录：`<仓库>/assets`。
   * macOS 不用手动加 `-XstartOnFirstThread`：启动器会自己用这个参数重新启动 JVM（`lwjgl3/src/main/java/org/kelvinizer/lwjgl3/StartupHelper.java`）。

窗口默认 1280 × 720，可以拉伸（最小 960 × 540）。

**打包成 jar**：`./gradlew lwjgl3:jar`，生成 `lwjgl3/build/libs/KandK-1.0.0.jar`，资源都打在 jar 里，用 `java -jar` 运行。另外还有 `lwjgl3:jarMac`、`lwjgl3:jarLinux`、`lwjgl3:jarWin`，只包含对应系统的本地库。打包前先把 WAV 放好，否则 jar 里没有音乐。

### Android

* Android 部分是 `android` 模块（启动器 `android/src/main/java/org/kelvinizer/android/AndroidLauncher.java`，资源直接用根目录的 `assets/`）。在 IntelliJ IDEA（需要 Android 插件）或 Android Studio 里打开同一个工程，选 `android` 模块的运行配置，连上手机或模拟器运行。
* 命令行：`./gradlew android:installDebug` 安装到连着的设备；`./gradlew android:run` 在设备上启动已安装的游戏（它按 `local.properties` 的 `sdk.dir` 或环境变量 `ANDROID_SDK_ROOT` 找 `adb`）。
* 游戏固定横屏（可以 180° 翻转），全屏沉浸模式；振动反馈需要的 `VIBRATE` 权限已经在 `android/AndroidManifest.xml` 里声明。

---

## 4. 键盘操作（桌面版）

鼠标左键相当于一根手指的触摸。键盘按键对应轨道（轨道编号见 `docs/CHART_FORMAT.md` §3）：

| 判定线 | 左边轨道 −5 −4 −3 −2 −1 | 右边轨道 1 2 3 4 5 |
|---|---|---|
| 天空线（上） | Tab　Q　W　E　R | U　I　O　P　[ |
| 地面线（下） | CapsLock　A　S　D　F | J　K　L　;　' |

* **空格**：只判定地面线的 TIDE。另外，每一行的任何一个键也能判定**本条线**的 TIDE。
* v2 谱面默认 8 条轨道（±1 到 ±4），用不到 CapsLock、'、Tab、[。原版歌曲的谱面（v1）有 ±5 两条外侧轨道，平时在画面外，只在部分段落窗口收窄时进入画面。
* **暂停**：Esc 或键盘上的 Pause 键，立即暂停。暂停时按 Esc 或 Enter 继续（3-2-1 倒计时）。**P 是轨道键，不是暂停。**
* 触摸 / 鼠标暂停：默认是双击右上角的暂停按钮（设置 → GAMEPLAY → PAUSE INPUT 可以改成按住或单击）。

---

## 5. 歌曲和音乐文件放在哪里

* 曲库在 `assets/Charts/`：每首歌一个文件夹，**文件夹名就是歌曲 id**（也是存档里的键，不要改名）。里面是谱面（`Surface.json`、`Shallow.json`、`Deep.json`、`Abyss.json`，有几个放几个）、曲绘图片和音乐。
* 章节、歌曲顺序和教程文件夹写在 `assets/Charts/index.json`。
* 教程在 `assets/Introduction/`（谱面 `Surface.json` 和音乐 `Introduction.wav`）。
* **WAV 文件不在 git 仓库里**，拿到后按下面的路径放好：

| 音乐 | 放在 |
|---|---|
| Song 1 … Song 6 | `assets/Charts/Song 1/Song 1.wav` … `assets/Charts/Song 6/Song 6.wav` |
| ICS4U | `assets/Charts/ICS4U/ICS4U.wav` |
| marsh mally | `assets/Charts/marsh mally/marsh mally.wav` |
| 教程 | `assets/Introduction/Introduction.wav` |
| 菜单音乐 | `assets/background.wav` |

* 歌曲音乐的文件名要和文件夹名一样（`<歌曲 id>.wav`，也可以是 `.ogg` 或 `.mp3`）。缺少某个文件时，游戏照样能运行，只是对应的歌曲、教程或菜单没有声音。
* 加一首新歌、加章节的完整步骤见 `docs/ASSET_SLOTS.md` §5–§6 和 `docs/DEVELOPER.md` §6。
* 存档：桌面版在用户主目录的 `KandKGame/profiles/`（导出的备份在 `KandKGame/export/`）；Android 在应用自己的存储里。

---

## 6. 文档

| 文件 | 内容 | 语言 |
|---|---|---|
| `docs/CHANGES.md` | 和原版相比改了什么，以及 2026-10-12 第二轮的改动（§7） | 中文 |
| `docs/DEVELOPER.md` | 开发人员手册：每个包和类做什么、要改什么去哪里改、常见任务 | 中文 |
| `docs/CHART_FORMAT.md` | 谱面格式（v2，以及 v1 的自动转换） | 中文 |
| `docs/ASSET_SLOTS.md` | 资源槽位：音效、结算音乐、背景图、字体、粒子、加歌、加章节、教程 | 中文 |
| `docs/DESIGN.md` | 画面和交互的设计规范（§22 是第二轮决定的索引） | 英文 |
| `docs/ARCHITECTURE.md` | 代码结构和约定（§21 是第二轮改动在代码里的位置） | 英文 |

第一次看代码，从 `docs/DEVELOPER.md` 开始。

---

## 7. 第一次测试清单

### 7.1 教程（Introduction）

- [ ] 启动后在欢迎画面点 **TAP TO DESCEND**。第一次启动会先走首次设置（WHO'S DIVING? → COMFORT → TUNE YOUR SONAR），最后一步完成后自动下潜进入教程。
- [ ] 已经有存档时，可以从地图右上角的教程按钮、地图最上面的 LAUNCH POINT 浮标（DIVE IN），或 设置 → OTHER → TUTORIAL → PLAY 重玩教程。想从头体验第一次启动，可以先把 `KandKGame/profiles/` 改个名字（改回原名就能恢复存档）。
- [ ] 开场：`WELCOME TO`、`Kelvinizer and Kathlina` 和三行故事。
- [ ] HUD 介绍：四个角的说明都贴在对应的角上。暂停那一行要和 设置 → GAMEPLAY → PAUSE INPUT 一致：双击（默认）是 `DOUBLE-TAP HERE TO PAUSE` / 双击这里暂停，按住是 `HOLD HERE TO PAUSE` / 按住这里暂停，单击是 `TAP HERE TO PAUSE` / 点击这里暂停。只有双击模式下暂停按钮会播放双击演示，演示不会真的暂停。（旧存档的暂停方式可能还是“按住”。）
- [ ] 判定介绍：RESONANCE / ECHO / LOST 和 EARLY / LATE，旁边有演示的打击效果。
- [ ] 每一课都能打（2026-10-17 按 Kelvin 手写的谱面重排）：地面 DROP、天空 DROP、TIDE、STREAM、TIDE STREAM、MINE（演示水雷被碰到时，红色 LOST 出现在演示水雷上方）、LANE MINE（变红时别碰那条轨道）、潮汐也能当判定线、MORE TO DISCOVER，最后是两人的对白。字幕都放在当时没有音符经过的地方；金色珍珠没有单独的字幕。
- [ ] 暂停：双击右上角的暂停按钮（或按 Esc）→ 暂停菜单（RESUME / RETRY / QUIT；中文模式下是 继续 / 重开 / 退出，两种语言不会同时出现）→ RESUME（或 Esc / Enter）→ 3-2-1 倒计时后继续。倒计时期间音符不动，也不判定。
- [ ] 开场：3 秒倒计时时只有判定线、轨道和四角，接着 0.3 秒空白，然后音符从屏幕边缘落进来；不会有音符在屏幕里突然出现。
- [ ] 屏幕比例：宽屏手机和平板上，轨道和判定线撑满整个屏幕；四角的分数、暂停、难度和时间贴着角，大小不变。
- [ ] SKIP：右侧暂停按钮下方的 `HOLD TO SKIP`（中文模式下也是英文），按住 0.6 s 跳过。第一次玩时 10 s 后才出现，重玩时一开始就有。在 3-2-1 倒计时中跳过，也应该直接进入结算画面，歌不会在后面继续放。
- [ ] 中文：设置 → OTHER → LANGUAGE 选 中文 后重玩，字幕是中文；COMBO、EARLY / LATE、HOLD TO SKIP 和判定字 RESONANCE / ECHO / LOST 仍是英文（见 7.4）。
- [ ] 结束后出现结算画面，CONTINUE 回到地图。

### 7.2 一张基本谱面

- [ ] 地图上 SHORELINE（浅滩）章节 → Song 1 → 难度 SURFACE（定数 1.4）→ DESCEND。
- [ ] 键盘：主要用 A S D F / J K L ;（地面线）和 Q W E R / U I O P（天空线）；原版谱面窗口收窄时，外侧的 CapsLock、'、Tab、[ 也会用到。
- [ ] 音乐和音符对得上。如果整体偏早或偏晚，先做 设置 → AUDIO → CALIBRATE。
- [ ] 判定字、连击、分数和四个角的 HUD 正常；暂停、继续、RETRY 都正常。
- [ ] 打完后结算画面的分数、评级和 FULL COMBO 等标记合理；CONTINUE 回到地图，歌曲卡片上记下了成绩。
- [ ] 歌曲卡片上的 AUTO 打开后，按钮变成 WATCH：自动演奏，用来看谱面，不记录成绩。

### 7.3 贝壳、深度和评级（2026-10-17）

- [ ] 第一次用新版本打开时，旧存档自动升级：原来的经验变成同样多的贝壳，玩过的单曲算已经买过，旧的 PURE 变成 R；`KandKGame/profiles/` 里会多一份 `<id>.json.schema1.bak` 备份。
- [ ] 地图左上角的徽章：名字、贝壳数和你的深度（带负号，比如 -2,412 m），没有进度条。
- [ ] 单曲层里没买的歌（现在是 Aleph-0）有金色虚线圈和 🐚 300；卡片上按住 HOLD TO BUY 约 0.6 秒买下。贝壳不够时按钮变灰，写 NEED 🐚 还差多少 MORE。
- [ ] 打完一首：结算画面下方显示贝壳从多少变成多少和 +N；深度到了解锁点时出现 UNLOCKED 和解锁的头像样式。评级按分数分 15 档：F、D、D+ … SS+、R（1 亿分及以上），全部 PERFECT 是 R+。
- [ ] 个人页左栏：头像、名字、贝壳数和深度，下面是 PLAYS 和 PLAY TIME 两条（平板上在顶上那一块的右边）。STATS：第一排四个难度开关（只写难度名），进来时全亮，点亮几个就看几个的合计，只剩一个亮着时点它只会晃；第二排第一格是「通过几张/一共几张」，比如 13/31 CHARTS CLEARED；下面的分数直方图一直到底，9600 万分起是青色。TOP 10（2026-10-18 起叫 DST 10，见 7.4）：最上面是 DIVE PROFILE（更新后第一次打开是空的，打一局才开始画），往下滚时它不动，只有下面最深的 10 张谱在滚。LOG 改名 RECENT。
- [ ] 改名：在个人页点名字。电脑上名字原地变成输入框，回车确定；手机上只弹出键盘，打完按完成。
- [ ] 歌曲卡片：深度写成「-4,000 / -5,760 m」。NEXT 后面是打完这首之后你的总深度和要打到的分数：先凑下一个整百，比如你在 -2,580 m 时写「-2,600 m AT 94,990,000」；单推这首不够就凑 50，再不够凑整 10；还不够就写 MAX 和这首最多能到的深度；再怎么打都不会更深时写 MAXED。9600 万分以下也会扣定数，所以低分的谱深度很浅甚至是 0。
- [ ] 评级颜色：S、SS 金色；S 以下从 F 的灰色一个字母一档变亮，B 是浅灰，A 是纯白；带 + 的和不带的同色。R 和 R+ 是浅蓝色，一道白光一直从左往右扫过（R+ 扫得更勤）：结算印章、歌曲卡片的评级和地图拉近后洞下面的评级都这样。选歌界面和 RECENT 里 SS+、R+ 这些的 + 是右上角的小上标（RECENT 里不扫光）。FULL COMBO 是紫色，ALL RESONANCE 是浅蓝（卡片、结算、RECENT、打完一首时的横幅）。
- [ ] 全图提示和上浮：在单曲里捏合缩到全图，顶上的「全图 OVERVIEW · 再捏合 ↑ 上浮」松手 1 秒后淡出；淡出后再捏合，地图缩不动、回弹，提示重新亮起，不上浮；1 秒内连捏两次才上浮到上一层。开着歌曲卡片时只能缩到全图，不弹提示也不上浮。电脑上用滚轮：一次连续滚动算一次捏合，到全图后停一下再滚会回弹一下、提示重新亮起，1 秒内滚两次上浮。
- [ ] 进单曲：(0,0) 在屏幕正中，SINGLES 字在中间，六首歌围成一圈，marsh mally 在右上、ICS4U 在右下，没放进地图的歌（Aleph-0）自动排在左上。每个洞外面是四圈完整的分数环，最里圈 SURFACE、最外圈 ABYSS（黑色），6000 万分以下是空的，满 1 亿是一整圈。拉近后，当前难度没打过的谱面在分数那一行显示青色的 NEW。分数环（包括很短的 ABYSS 弧）和封面那圈边都是圆的，边缘没有锯齿和小黑点。
- [ ] 进单曲时的闪光：从主界面点单曲，转场之后闪一下的那个圆圈在屏幕正中间（SINGLES 字上）展开，周围六首歌一起亮起，不再在左上角。

### 7.4 设置、中文和个人页（2026-10-18）

- [ ] 设置左边只剩四项：AUDIO、VISUAL、GAMEPLAY、OTHER（中文是 音频、视觉、游玩、其他；OTHER 的图标是三个点），没有 TIMING，也没有单独的 ACCOUNT 和 ABOUT。
- [ ] AUDIO 一打开先是 TIMING 一节：第一行是 CHART OFFSET（谱面延时，滑条和 −10 / −1 / +1 / +10），CALIBRATE… 按钮在 −10 那排按钮的左边，不再单占一行；下面是 AUDIO PRESET（音频预设），再往下才是 SOUND 一节。VISUAL 第一行是 GAMEPLAY VISUALS（游戏界面，19:52 前叫游玩界面视觉参数），右边蓝色的 ADJUST…（调整…）按钮打开游戏界面画面（见下面 15:17 那条）。VISUAL 里剩下的是 减少动态、背景细节、背景亮度、界面缩放、额外边距、显示帧率。GAMEPLAY 先是 EARLY / LATE HINT（过早 / 过晚提示）一行（后面两个可以多选的按钮 RESONANCE、ECHO，中文 共鸣、回响，样子和个人页 STATS 的难度按钮一样：亮着的有底色、边框和小灯；两个都不选也可以，原来是两行开关）、SHOW MS、HIT ERROR METER，再是 PAUSE INPUT、SHOW TOUCHES（显示触控，19:52 从游戏界面挪过来）、HAPTICS，一页放得下不用滚动；MULTI-PRESS ASSIST（多押辅助，OFF / ON / ENHANCED，中文 关 / 开 / 增强；开 = 光晕，增强 = 光晕加珍珠）19:52 挪进了游戏界面；没有 LIVE COUNTERS 了，LANGUAGE 在 OTHER 最上面。存在本机、所有玩家共用的设置（谱面延时、音频预设、音量、背景细节、界面缩放、额外边距、显示帧率、粒子质量），标题后面的小标签是 SHARED（公用），不再是 DEVICE（本机）。VISUAL OFFSET 没有了；校准画面只剩音频延迟一页。以前存的画面延迟不再起作用。
- [ ] OTHER 一页不分节：最上面是 LANGUAGE（EN / 中文），然后 SAVE BACKUP（备份存档，EXPORT 把当前玩家复制成 KK1: 备份码）、RESET RECORDS（按住 2 秒，再确认、输入玩家名，见下面 19:52–20:56 那条）、TUTORIAL（PLAY 重玩教程）、OPEN-SOURCE LICENSES（VIEW），最下面一行左边是大字 K & K（和开始画面一样的 glitch 字体），右边是版本号；这一行占满上面几行剩下的高度，字跟着自动放大缩小，所以 OTHER 一页在电脑、手机和平板上都不用滚动（05:58）；没有 TWO SIGNALS · ONE DESCENT（05:28）；NOTE GUIDE 音符图鉴已经去掉（05:14）；没有 STORED ON THIS DEVICE ONLY 一行。左下角标签栏下面还是只有版本号。VIEW 打开开源许可证页（左边 OTHER 一直亮着，返回回到 OTHER）：K & K 自己的 MIT 许可证、每首歌的曲师 / 谱师 / 曲绘、三种字体和用到的开源软件，带 VIEW 的点开能看全文，Esc 或左上角返回。
- [ ] 中文：设置 → OTHER → LANGUAGE 选 中文 后，欢迎画面、首次设置、设置、校准、个人页、教程字幕、转场的 音乐 / 谱师 / 曲绘（20:45 前是 谱面）和各种提示条都是中文。保持英文的：选歌地图、歌名和难度名（SURFACE 等）、DST 10 / DEEPEST 10 / OVERFLOW、许可证原文；下潜画面和 EN 完全一样（AUTOPLAY、COMBO、EARLY / LATE、HOLD TO SKIP、判定字 RESONANCE / ECHO / LOST 和结束横幅 CLEAR / FULL COMBO / ALL RESONANCE，16:41）；暂停菜单只写当前语言（17:22）；结算画面是英文（16:45），只有 RETRY / CONTINUE、谱面延时的确认框和点贝壳弹出的明细跟着语言（17:22），点分数弹出的明细和点开柱状图的 峰值 / 平均 也跟着语言（20:34），谱面延时提示、特效调低的提示和教程结算那行小字也跟着语言（21:37）。
- [ ] 14:29 改名和删项（设置分别用 中文 和 EN 看一遍）：设置里的名字是 谱面延时 CHART OFFSET、音频预设 AUDIO PRESET、音乐音量 MUSIC VOLUME、打击音效音量 NOTE VOLUME（19:52 前 HIT SOUND VOLUME）、视觉 VISUAL、游戏界面 GAMEPLAY VISUALS（19:52 前叫游玩界面视觉参数）、按键缩放 NOTE SCALE、判定位置 JUDGEMENT POSITION、多押辅助 MULTI-PRESS ASSIST、过早 / 过晚提示 EARLY / LATE HINT（19:52 前 TOO EARLY / TOO LATE HINT）、备份存档 SAVE BACKUP，视觉页和游戏界面页各有一个 背景亮度 BACKGROUND BRIGHTNESS（后者 100 % 是不压暗）。谱面延时下面三行小字「调整音乐的同步性」（19:52 加的第一行）「EARLY 较多 → 点击减少（–）」「LATE 较多 → 点击增加（+）」；音频预设「点击切换预设项目」；打击音效音量「调整音符的打击音效音量」；视觉页的背景亮度「菜单和曲目背景插画的亮度」；语言、新手教程、开源许可证没有小字。背景插画开关、打击音效时机、曲目试听、菜单音乐都不见了，但功能一直开着：插画照常显示，选歌时有试听，菜单有音乐，打击音效只在判定时响（以前关掉过的也会恢复）。个人页中文标题是「账号与统计」（英文还是 PROFILE），标签不管语言都是 STATS / DST 10 / RECENT / PLAYER，STATS 第二排是 CHARTS CLEARED / FULL COMBOS / RESONATED；RECENT 每局后面是 FC 或 AR。选歌卡片和筛选里的 ALL RESONANCE 变成 RESONATED。
- [ ] 15:17–16:19 游戏界面（设置 → 视觉 → 调整…；19:52 前叫游玩界面视觉参数）是一个单独的画面，像校准画面一样，左上角返回回到设置的视觉页。中间是大的实时预览（19:52 重写）：一整局游戏画面按屏幕比例缩小放进来，自己在打一张随机谱，音符随机落在上下两条线上，双押之后隔 1–6 次下落才再出双押，连击到 5–12 时断一次；COMBO 和判定字和游戏里一样，判定区提示从判定线往外到 0.4 屏高；左上角是分数（开着 FC/AR 指示时淡蓝，回响之后变紫，迷失之后变白），右下角是「实时预览」；点预览没有反应。预览下面一条「判定字样 / JUDGEMENT WORDS」，标题居中，依次是 ECHO、RESONANCE、LOST，按故障强度轮流闪；最下面一行小字只有「改动马上生效」。左列「画面」：背景亮度、故障效果强度（下面小字「判定字和标题的故障特效」）、减少闪烁、粒子质量；右列「音符与判定」：按键缩放、多押辅助、判定位置、FC/AR 指示、判定区提示（显示触控挪到了游玩页）；两列都排到底，电脑、平板、手机都不用滚动。「接近光晕」没有了（光晕还在）。改设置时预览马上跟着变：按键缩放、多押辅助（双押的白框和珍珠）、判定位置（中央 / 轨道上 / 关）、粒子质量、背景亮度、故障强度、判定区提示。
- [ ] 17:22–17:26 结算画面（中文和 EN 各看一遍）：左上角没有 DIVE LOG · 01 SHORELINE 了（教程打完的 TUTORIAL COMPLETE 也没有了），右上角的谱面深度那行还在。RETRY / CONTINUE 在中文下是 重开 / 继续（教程是 再玩一次）；点 ADJUST 弹出的框中文是「谱面延时」「把扬声器的谱面延时从 … 改成 … ms？」和 取消 / 校准 / 应用。点贝壳 +N 弹出的明细没有标题，各行和最下面的 TOTAL 跟着语言（中文是 基础、首次下潜、新纪录…合计）。点分数弹出的明细也没有标题，三行是 ACCURACY SCORE、RESONANCE CHAIN、PERFECT RESONANCE，最下面 TOTAL；中文是 准度分 / 共鸣链 / 完美共鸣 / 总计（20:34）。暂停菜单英文模式只写 RETRY / RESUME / QUIT，中文模式只写 重开 / 继续 / 退出。
- [ ] FC/AR 指示（默认开）：下潜时左上角的分数，全是共鸣时淡蓝，出了回响但没断连是紫色，断了（迷失或碰了水雷）变白；关掉就一直是白色。
- [ ] 音频页「打击音效音量」下面多了「界面音效音量」，现在是灰的（小字「调整菜单和按钮的音效音量 · 还没有界面音效」）。
- [ ] 个人页第四个标签叫 PLAYER（中文 玩家，原来叫 DIVERS / 潜水员）：卡片最后一行是「LAST ACTIVITY: OCT 18 · 02:49」（中文「上次活动：10月18日 02:49」），也就是最后一次下潜的日期和时间，没下潜过写「—」；点玩家卡片只会切换到这位玩家，不再弹出改名、导出、删除的菜单（删除玩家暂时没有入口）。
- [ ] 个人页 DST 10（原 TOP 10）：DIVE PROFILE 下面先是 DEEPEST 10，没有那句「YOUR DEPTH IS THE AVERAGE OF THESE TEN」；不满 10 张时用空位（—）占着；再下面是 OVERFLOW，第 11 到 15 深的 5 张（不算进深度），不满也用空位。结算画面的「TOP 10 #3」也改成「DST 10 #3」。
- [ ] DIVE PROFILE：横轴是时间，刻度下面写日期（10月17日 / OCT 17）；手机上双指捏合、电脑上鼠标放在曲线上滚滚轮，可以放大到几分钟，刻度会细到小时和分钟；拖动左右平移，双击回到全部。点一个圆点，弹出那次下潜的日期、时间和之后的深度，再点一次收起。右边的两个深度刻度单独占一栏，最新的点不会压在字上。
- [ ] 19:52–21:56（中文和 EN 各看一遍）：设置 → 游玩 打开 SHOW TOUCHES（显示触控），进一首歌，每个按着的手指下面有一个白色的圈和小点，跟着手指走，松开后放大一点淡出（电脑上鼠标按下也有）。游戏里判定线往外到 0.4 屏高有一层淡淡的颜色（判定区提示开着时；以前几乎看不出来）。设置 → 其他 → 重置记录：按住 2 秒弹出确认框（头像、名字、贝壳和深度，红字「清空后无法恢复。」），点「确认」后要把玩家名一字不差地输入一遍，「重置」才能点；点框外、返回或「取消」什么都不改。重置以后这位玩家的贝壳、深度、成绩、买下的曲目、看过的剧情和新手教程记录都清空，头像里要靠深度解锁的样式换回默认。**建议先新建一个测试玩家来试**，或者先用「备份存档」导出。主界面：主线下面没有「演示 · 7 章」，联动是一个罩着铁栅栏的空洞，下面还是「敬请期待」。进歌时转场的署名中文是「谱师」。中文里所有「潜水员」都变成了「玩家」（玩家页、新建玩家、导入、改名、切换玩家、第一次进游戏）；英文还是 DIVER。结算（中文）：重开 / 继续 的字更大；打得偏晚或偏早时下面是「你打得偏晚（+31 ms） · 调整」，特效被自动调低时是「为了更流畅，粒子质量已自动调低」，打完教程是「下一步 · 在地图上选一首歌」；这两行以柱状图 0 ms 那条线为中心居中。长条：离结尾 0.25 秒以内松手算打完（一直都是这样，Kelvin 选了不改）。

## 8. 许可证 License

* 源代码（`core/`、`lwjgl3/`、`android/` 和 Gradle 脚本）和文档按 [MIT 许可证](LICENSE) 开源。
* `assets/` 里的素材不在 MIT 范围内：音乐、音效、曲绘、图片和谱面的版权归各自的作者所有，保留所有权利，未经许可不能使用。
* 字体按各自的许可证：Chakra Petch、Noto Sans SC 和 Rubik Glitch 都是 SIL Open Font License 1.1（见 `assets/fonts/OFL-*.txt`）。
* 游戏里用到的开源软件（libGDX、FreeType、LWJGL 等）的许可证全文在 `assets/licenses/`，游戏里在 设置 → OTHER → OPEN-SOURCE LICENSES 可以看到。

The source code (`core/`, `lwjgl3/`, `android/` and the Gradle scripts) and the documentation are released under the [MIT License](LICENSE). The assets in `assets/` are not: music, sound effects, cover art, images and charts belong to their respective owners, all rights reserved. Fonts keep their own licenses: Chakra Petch, Noto Sans SC and Rubik Glitch are under the SIL Open Font License 1.1 (`assets/fonts/OFL-*.txt`).
