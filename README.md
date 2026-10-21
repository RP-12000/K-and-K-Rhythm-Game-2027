# K & K（Kelvinizer and Kathlina）

一款以“下潜”为主题的双判定线节奏游戏，用 [libGDX](https://libgdx.com/) 写成，支持桌面（Windows / macOS / Linux）和 Android。

当前版本 **0.1.0-beta.1**（内测版，2026-10-21）。每一次改了什么见 `docs/CHANGES.md`（开发日志）。

> 这是一个男孩和一个女孩的故事，他们被困在一个黑暗的世界里。
> 唯一的出路，是向未知的更深处下潜。
> 并揭开他们最深的秘密，和那个悲剧的真相。
>
> ——教程开场

---

## 1. 游戏是什么

* Kelvinizer 和 Kathlina 两个人一路向深处下潜。选歌界面是一张**洞穴地图**：开始画面之后先到洞口（CAVE MOUTH），从这里进 SINGLES 单曲、MAIN 主线和 COLLAB 联动（敬请期待）；洞口右下角是 ALL SONGS 所有曲目。主线一章是一层：现在有 01 SHORELINE 浅滩和 02 TWILIGHT 暮光层，03–07 章还封着。每首歌是地图上的一个洞；双指按住不动 1.5 秒上浮回上一层。
* 第一次启动先做三步首次设置，然后自动进新手教程；以后在 设置 → 其他 → 新手教程 重玩。
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

* 同一时刻要一起打的音符默认带一圈光晕（设置 → 视觉 → 游戏界面 → 多押辅助：可以关掉，或者再加上金色珍珠；水雷上是红色珍珠，提醒别碰）。有的音符后面拖着一串**残影**：残影只是光，不用打。
* **判定**：RESONANCE（±66.67 ms）、ECHO（±133.33 ms）、LOST；RESONANCE 里 ±33.33 ms 以内的另计为 PERFECT。偏早或偏晚会显示 EARLY / LATE。
* **分数**：准度分满分 100,000,000（RESONANCE 算 1 个，ECHO 算 0.55 个），加上共鸣链（最长的一段连续 RESONANCE，满了是 10,000）和每个 PERFECT 1 分，所以全部 PERFECT 是 100,010,000 加音符数。详细规则见 `docs/CHANGES.md` §3。
* **结果和评级**：打完就是 CLEAR；没有 LOST、也没有碰到任何水雷是 FULL COMBO；FULL COMBO 并且全部 RESONANCE 是 ALL RESONANCE（卡片上写 RESONATED）。评级按分数分 15 档：F、D、D+ … SS+、R（1 亿分及以上），全部 PERFECT 是 R+。
* **贝壳和深度**：每局得贝壳，贝壳可以买单曲。每张谱面按定数和分数算出一个深度，最深的 10 张平均就是玩家的深度（显示带负号，比如 -2,412 m）；深度到了解锁点会解锁新的头像样式。

---

## 2. 需要什么

| | 版本 | 说明 |
|---|---|---|
| JDK | **21** | 运行 Gradle 和游戏都用它 |
| Java 语言级别 | **11** | 由 Gradle 设定（`sourceCompatibility = 11`），不能用更新的语法 |
| Gradle | **9.5.1**（wrapper） | 用仓库里的 `gradlew` / `gradlew.bat`，不用另装 Gradle；第一次运行要联网下载 Gradle 和依赖 |
| libGDX | **1.14.1** | 另有 LWJGL 3.4.1、ShapeDrawer 2.5.0、jackson-core 2.17.0；版本号都在 `gradle.properties` |
| Android（可选） | compileSdk 36，minSdk 21 | 需要 Android SDK；Android Gradle Plugin 9.2.1 |
| 音乐文件 | MP3 / OGG / WAV | **不在 git 仓库里**，要另外放进 `assets/`（见 §5） |

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

**打包成 jar**：`./gradlew lwjgl3:jar`，生成 `lwjgl3/build/libs/KandK-0.1.0-beta.1.jar`，资源都打在 jar 里，用 `java -jar` 运行。另外还有 `lwjgl3:jarMac`、`lwjgl3:jarLinux`、`lwjgl3:jarWin`，只包含对应系统的本地库。打包前先把音乐文件放好，否则 jar 里没有音乐。

**版本号**：三处保持一致，改版本时一起改：`gradle.properties` 的 `projectVersion`、`android/build.gradle` 的 `versionName`（`versionCode` 每次发布加 1）、`core/.../Params.java` 的 `VERSION`（游戏里 设置 → 其他 最下面和开始画面显示的就是它）。

### Android

* Android 部分是 `android` 模块（启动器 `android/src/main/java/org/kelvinizer/android/AndroidLauncher.java`，资源直接用根目录的 `assets/`）。在 IntelliJ IDEA（需要 Android 插件）或 Android Studio 里打开同一个工程，选 `android` 模块的运行配置，连上手机或模拟器运行。
* 命令行：`./gradlew android:installDebug` 安装到连着的设备；`./gradlew android:run` 在设备上启动已安装的游戏（它按 `local.properties` 的 `sdk.dir` 或环境变量 `ANDROID_SDK_ROOT` 找 `adb`）。
* **更新时直接 `installDebug` 覆盖安装，不要先卸载**：卸载会把手机上的存档一起删掉。
* 游戏固定横屏（可以 180° 翻转），全屏沉浸模式；振动反馈需要的 `VIBRATE` 权限已经在 `android/AndroidManifest.xml` 里声明。

---

## 4. 键盘操作（桌面版）

鼠标左键相当于一根手指的触摸。键盘按键对应轨道（轨道编号见 `docs/CHART_FORMAT.md` §3）：

| 判定线 | 左边轨道 −5 −4 −3 −2 −1 | 0 号轨道 | 右边轨道 1 2 3 4 5 |
|---|---|---|---|
| 天空线（上） | Tab　Q　W　E　R | T 或 Y | U　I　O　P　[ |
| 地面线（下） | CapsLock　A　S　D　F | G 或 H | J　K　L　;　' |

* **空格**：只判定地面线的 TIDE。另外，每一行的任何一个键也能判定**本条线**的 TIDE。
* v2–v4 谱面默认 8 条轨道（±1 到 ±4），用不到 CapsLock、'、Tab、[。0 号轨道只在谱面插入它时才有；没有 0 号轨道时 G / H / T / Y 只判定 TIDE。原版歌曲的谱面（v1）有 ±5 两条外侧轨道，平时在画面外，只在部分段落窗口收窄时进入画面。
* **暂停**：Esc 或键盘上的 Pause 键，立即暂停。暂停时按 Esc 或 Enter 继续（3-2-1 倒计时）。**P 是轨道键，不是暂停。**
* 触摸 / 鼠标暂停：默认是双击右上角的暂停键（设置 → 游玩 → 暂停方式 可以改成按住或单击）。
* 选歌地图：Esc 或 PageUp 上浮回上一层；滚轮缩放（所有曲目页是上下滚动）。

---

## 5. 歌曲和音乐文件放在哪里

* 曲库在 `assets/Charts/`：每首歌一个文件夹，**文件夹名就是歌曲 id**（也是存档里的键，不要改名）。里面是谱面（`Surface.json`、`Shallow.json`、`Deep.json`、`Abyss.json`，有几个放几个）、曲绘图片和音乐。
* 章节、歌曲顺序和教程文件夹写在 `assets/Charts/index.json`；每张地图上哪首歌在哪里写在 `assets/maps/*.json`（见 `docs/MAP_FORMAT.md`）。没放进任何地图的歌会自动排进单曲层的空位。
* 教程在 `assets/Introduction/`（谱面 `Surface.json` 和音乐 `Introduction.wav`）。
* **音乐文件不在 git 仓库里**，拿到后按下面的路径放好：

| 音乐 | 放在 |
|---|---|
| 每首歌 | `assets/Charts/<歌曲 id>/<歌曲 id>.mp3`，比如 `assets/Charts/Song 1/Song 1.mp3` |
| 教程 | `assets/Introduction/Introduction.wav` |
| 菜单音乐 | `assets/background.wav` |

* 文件名要和文件夹名一样；扩展名 `.mp3`、`.ogg`、`.wav` 都可以（菜单音乐也一样）。缺少某个文件时，游戏照样能运行，只是对应的歌曲、教程或菜单没有声音。
* 加一首新歌、加章节的完整步骤见 `docs/ASSET_SLOTS.md` §5–§6 和 `docs/DEVELOPER.md` §6。
* 存档：桌面版在用户主目录的 `KandKGame/profiles/`（导出的备份在 `KandKGame/export/`）；Android 在应用自己的存储里。

---

## 6. 文档

| 文件 | 内容 | 语言 |
|---|---|---|
| `docs/DEVELOPER.md` | 开发人员手册：每个包和类做什么、要改什么去哪里改、代码风格、常见任务 | 中文 |
| `docs/CHANGES.md` | 开发日志：和原版相比改了什么（§1–§6），以及 2026-10-12 起每一次修改（§7） | 中文 |
| `docs/CHART_FORMAT.md` | 谱面格式（v4，以及 v1、v2、v3 的自动转换） | 中文 |
| `docs/MAP_FORMAT.md` | 地图格式（`assets/maps/*.json`：歌在哪、路怎么连、区域名和文案） | 中文 |
| `docs/ASSET_SLOTS.md` | 资源槽位：音效、结算音乐、背景图、字体、粒子、加歌、加章节、教程 | 中文 |
| `docs/DESIGN.md` | 画面和交互的设计规范（§22 是 2026-10-12 那一轮决定的索引，§24 是之后各轮的修改） | 英文 |
| `docs/ARCHITECTURE.md` | 代码结构和约定（§21 是 2026-10-12 那一轮改动在代码里的位置） | 英文 |

第一次看代码，从 `docs/DEVELOPER.md` 开始。

---

## 7. 测试清单（0.1.0-beta.1）

按画面排，写的是 0.1.0-beta.1 应有的样子。每次更新后挑改到的部分测；中文和 EN 最好各看一遍（设置 → 其他 → 语言）。手机更新用 `installDebug` 覆盖安装，不要先卸载。

### 7.1 开始画面和首次设置

- [ ] 开始画面：大字 K & K，下面 `TWO SIGNALS · ONE DESCENT`（中文 两道信号 · 一同下潜），最底下 `TAP ANYWHERE TO START` / `点击任意位置开始`；点屏幕任意位置（K 字母和左下角玩家标签除外）开始。右下角没有设置齿轮。
- [ ] 左下角玩家标签：列出各个玩家和 `+ 新建玩家`（满 12 个时没有），玩家多时可以滚动，点一行切换玩家。
- [ ] 第一次启动（想重来可以先把 `KandKGame/profiles/` 改个名字，改回原名就能恢复存档）：首次设置三步，右上角写「第 1 步，共 3 步」，卡片在屏幕正中，没有返回键（手机的返回键能回上一步）。
  1. 舒适度（COMFORT），示例字 `SIGNAL`。
  2. 延迟校准：4 下预备拍再响 12 下，跟着点；结果「+40 ms」数字和单位一起居中；散点条两头是 −300 和 +300 ms，±150 以内占大部分宽度，外面两段的点是灰色空心圈。「应用」或「跳过」都进下一步。
  3. 目押校准：按键在卡片里落到线上，按平时的习惯点；结果是「-32 px」这样的 px 值，散点图写「线下方」「线上方」，没有 ms。「应用」或「跳过」后进教程。
- [ ] 新玩家的名字是 `DIVER-XXXX`，头像随机，都可以在个人页改。
- [ ] 手机上三步的卡片都完整放得下，不和底下的按钮重叠。

### 7.2 新手教程

- [ ] 首次设置做完自动开始；以后从 设置 → 其他 → 新手教程 重玩，结算画面的「再玩一次」也行。地图上没有教程的洞。
- [ ] 开场 `WELCOME TO`、`Kelvinizer and Kathlina` 和三行故事；然后介绍四个角，暂停那句跟着 设置 → 游玩 → 暂停方式（双击是默认，只有双击模式会播放双击演示，演示不会真的暂停）；再介绍 RESONANCE / ECHO / LOST 和 EARLY / LATE。
- [ ] 每一课都能打：地面 DROP、天空 DROP、TIDE、STREAM、TIDE STREAM、MINE（被碰到时红色 LOST 出现在水雷上方）、LANE MINE、潮汐当判定线。字幕放在当时没有音符经过的地方。
- [ ] 潮汐当判定线那一课以后的对白现在是空的占位（等新文案），也不画空底板；结尾是 K & K，中间的 & 看得到，连击数在它出来时让开。
- [ ] `HOLD TO SKIP`（中文模式也是英文）在下判定线下面那条的正中，按住 0.6 秒跳过；第一次玩 10 秒后才出现，重玩时一开始就有；在 3-2-1 倒计时中跳过也直接进结算，歌不会在后面接着放。

### 7.3 选歌地图

- [ ] 洞口（CAVE MOUTH）：SINGLES、MAIN 和罩着铁栅栏的 COLLAB（敬请期待）；右下角 ALL SONGS。
- [ ] 左上角玩家块：白圈圆头像、名字（底下一条往右淡出的深蓝光带）、两头尖的深度标签（只有数字，比如 -2,211 m；长度固定，数字居中）和紧挨着的贝壳数。只有点头像进个人页，点名字、深度和贝壳都没有反应。右上角是设置键。
- [ ] 进每一层都是最近的缩放，层名在最上面，字小、半透明。单曲的歌排成两行；主线 01、02 章有歌和剧情（书本），03–07 章封着。
- [ ] 两根手指按住不动：0.3 秒后两指中间出现一个圈，1.5 秒转满就上浮一层（圈里写 RISE / 上浮）；手指一动就取消，照常缩放。缩到最小不会上浮；电脑上 Esc / PageUp 上浮。
- [ ] 每个洞外面是四圈分数环（最里圈 SURFACE，最外圈 ABYSS）；拉近后，当前难度没打过的谱面显示青色 NEW。全连的评级是紫色，R / R+ 浅蓝带一道白光。
- [ ] 点歌打开右边的歌曲卡片：‹ › 换歌，深度写成「-4,000 / -5,760 m」，DESCEND 上面那一行是 NEXT 推分建议、MAX 或 MAXED；点地图空白处、往右划或按返回键（电脑上 Esc）关上。AUTO 打开后按钮变成 WATCH（自动演奏，不记成绩）。
- [ ] 没买的单曲有金色虚线圈和价格（默认 300 贝壳）；卡片上按住 HOLD TO BUY 买下，贝壳不够时按钮变灰。
- [ ] 所有曲目：一排两首、相邻两行错开半格，连成网（两头都打过是实线，否则虚线）；不缩放，上下拖动；右下 SORT 排序；右边的卡片一直开着；往下拖时歌从玩家块和设置键底下过去。
- [ ] 英文模式下地图上一个中文字都没有；中文模式下层名、区域名英文中文都有。
- [ ] 选一首歌下潜：转场中间的曲绘圆正好是地图上的洞放大而成，没有跳一下；署名是 音乐 / 谱师 / 曲绘（英文 MUSIC / CHART / ILLUSTRATION）。

### 7.4 下潜（游戏画面）

- [ ] 开场：3 秒倒计时时只有判定线、轨道和四角，接着 0.3 秒空白，然后音符从屏幕边缘落进来。
- [ ] 两条判定线在 1920 × 1200 画面的 y 1080 和 120（16:10 时离上下边 120）；判定区从线往外 0.4 屏高，开着判定区提示时有一层淡淡的颜色。
- [ ] 左上角只有分数：在屏幕顶边和上判定线之间按 0.3 / 0.4 / 0.3 放（上空 0.3、字高 0.4、下空 0.3），离左边的距离在电脑、手机和平板上都一样；FC/AR 指示开着时全共鸣淡蓝、出过回响紫色、断了连击白色。
- [ ] 右上角暂停键在同一段的中间，默认双击暂停；左下角难度标签和歌名、右下角剩余时间在同一行；顶上的进度条约屏幕高度的 1%。
- [ ] 屏幕中间是连击数；自动播放时连击数下面写淡绿色的 AUTOPLAY。
- [ ] 暂停：双击暂停键或按 Esc → 暂停菜单（RETRY / RESUME / QUIT，中文 重开 / 继续 / 退出，只显示当前语言）→ 继续时 3-2-1 倒计时，倒计时期间音符不动，也不判定。
- [ ] 手机上连续暂停、继续几次：音乐和音符一直对得上，不会越来越晚，也不会忽早忽晚；倒计时一结束音符马上接着走，不会先停一下，之后也不会突然跳一下（第一次继续时如果有偏差，谱面会在一两秒内稍快或稍慢地追上，之后的继续一开始就对得上）。
- [ ] 判定字 RESONANCE / ECHO / LOST、COMBO、EARLY / LATE 和结束横幅 CLEAR / FULL COMBO / ALL RESONANCE 在中文模式下也是英文。
- [ ] 宽屏手机和平板：轨道和判定线撑满整个屏幕，四角贴着角，大小不变。
- [ ] 开着 设置 → 游玩 → 显示触控：每个按着的手指下面有白圈和小点，松开后放大淡出。

### 7.5 结算

- [ ] 左上角是和地图一样的玩家块。深度加深的一局：先显示旧深度，深度标签放大发光，右下角浮出一个小六边形框写着 ▼ 加深的米数，数字滚到新深度，约 2.5 秒；然后右上角贝壳左边浮出 +N 的贝壳泡，被吸进贝壳里，数字再加上去。动画中途点一下屏幕会快进到最后。
- [ ] 评级印章（这一局全连是紫色）、NEW BEST 和分数在曲绘左下；点分数弹出明细（准度分 / 共鸣链 / 完美共鸣 / 总计），点贝壳弹出这次的贝壳细则。
- [ ] 偏早或偏晚时下面写「你打得偏晚（+31 ms） · 调整」，点了弹出谱面延时的确认框（取消 / 校准 / 应用）；教程打完写「下一步 · 在地图上选一首歌」。
- [ ] 按钮 重开 / 继续（教程是 再玩一次 / 继续），继续回到地图，卡片上记下了成绩。
- [ ] 手机（比 16:8.5 宽的屏幕）分三栏：左边曲绘和分数，中间连击、早晚和判定数，右边留给人物，按钮在右下角。

### 7.6 设置

- [ ] 左边四项：音频、视觉、游玩、其他（AUDIO / VISUAL / GAMEPLAY / OTHER）。电脑和平板上每页都不用滚动。
- [ ] 音频：谱面延时（滑条、–10 / –1 / +1 / +10 和左边的「校准…」）、音频预设、音乐音量、打击音效音量、界面音效音量（还没有界面音效，所以是灰的）；几行平分整页的高度。
- [ ] 视觉：目押校准（默认 0 px：判定线横穿按键中间）、游戏界面（调整…）、减少动态、背景细节、背景亮度、界面缩放、额外边距、显示帧率。
- [ ] 游戏界面（单独一个画面，左上角返回）：中间是实时预览，一局 12 组（单、单、双），分数开局淡蓝、第 5 组变紫、第 9 组变白；下面是判定字样；左列 背景亮度、故障效果强度、减少闪烁、粒子质量，右列 按键缩放、多押辅助、判定位置、FC/AR 指示、判定区提示。改了马上在预览里看到。
- [ ] 游玩：过早 / 过晚提示（共鸣、回响两个按钮可以多选）、显示毫秒、偏差条、暂停方式、显示触控、震动。
- [ ] 其他：语言、备份存档、重置记录、新手教程、开源许可证；最下面一行左边大字 K & K，右边 `版本 0.1.0-beta.1` / `VERSION 0.1.0-beta.1`。
- [ ] 重置记录：按住 2 秒 → 确认框 → 一字不差地输入玩家名，才能点「重置」；点框外、返回或取消什么都不改。**建议先新建一个测试玩家来试**，或者先用备份存档导出。
- [ ] 开源许可证：K & K 自己的 MIT 许可证、每首歌的曲师 / 谱师 / 曲绘、字体和开源软件；点 查看 看全文，全文左对齐。
- [ ] 延迟校准（音频 → 校准…）和目押校准（视觉 → 校准…）和首次设置里的一样，结果按钮写「应用」；延迟校准测完点「验证」，判定字是 RESONANCE / ECHO / LOST。

### 7.7 个人页和玩家

- [ ] 点地图左上角的头像进个人页（中文标题「账号与统计」，英文 PROFILE）：左栏头像、名字、贝壳和深度，下面 PLAYS 和 PLAY TIME。点名字改名。
- [ ] STATS：四个难度开关（点亮几个就看几个的合计），CHARTS CLEARED / FULL COMBOS / RESONATED，下面是分数直方图。
- [ ] DST 10：最上面 DIVE PROFILE 深度曲线（捏合或滚轮放大，拖动平移，双击回到全部，点圆点看那一次的详情）；下面 DEEPEST 10 和 OVERFLOW（第 11–15 深，不算进深度），不满的用「—」占位。
- [ ] RECENT：最近的成绩，全连的后面标 FC 或 AR。
- [ ] PLAYER：一行三张卡片，上面写「N / 12 位玩家」；不到 12 个时有「新建玩家」卡片和「导入」按钮，满 12 个时都不显示；点卡片只切换玩家（删除玩家暂时没有入口）。英文模式写 PLAYER、`NEW PLAYER`、`UP TO 12 ON THIS DEVICE`。

### 7.8 存档

- [ ] 打几局后关掉游戏再打开：成绩、贝壳、深度和设置都还在（桌面版在 `KandKGame/profiles/`）。
- [ ] 设置 → 其他 → 备份存档 → 导出：得到一串 `KK1:` 开头的备份码；在 个人页 → PLAYER → 导入 能用它导回来。
- [ ] 手机：`installDebug` 覆盖安装以后，成绩还在。

## 8. 许可证 License

* 源代码（`core/`、`lwjgl3/`、`android/` 和 Gradle 脚本）和文档按 [MIT 许可证](LICENSE) 开源。
* `assets/` 里的素材不在 MIT 范围内：音乐、音效、曲绘、图片和谱面的版权归各自的作者所有，保留所有权利，未经许可不能使用。
* 字体按各自的许可证：Chakra Petch、Noto Sans SC 和 Rubik Glitch 都是 SIL Open Font License 1.1（见 `assets/fonts/OFL-*.txt`）。
* 游戏里用到的开源软件（libGDX、FreeType、LWJGL 等）的许可证全文在 `assets/licenses/`，游戏里在 设置 → OTHER → OPEN-SOURCE LICENSES 可以看到。

The source code (`core/`, `lwjgl3/`, `android/` and the Gradle scripts) and the documentation are released under the [MIT License](LICENSE). The assets in `assets/` are not: music, sound effects, cover art, images and charts belong to their respective owners, all rights reserved. Fonts keep their own licenses: Chakra Petch, Noto Sans SC and Rubik Glitch are under the SIL Open Font License 1.1 (`assets/fonts/OFL-*.txt`).
