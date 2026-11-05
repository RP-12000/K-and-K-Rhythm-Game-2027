# K&K 谱面格式 v5（CHART_FORMAT）

v5 从 2026-10-27 起使用（Kelvin 04:12「把整个谱面文件全部重构」，04:40 选了「文件格式」）。和 v4 相比：

* **省掉默认值**：默认值一律不写，没写的键就是默认值。完全没有运动事件的音符用默认下落（判定前 2 拍，1080 → 0，匀速），不再每个音符写一遍。
* **一种写法**：键名全部小写开头、区分大小写，没有别名；事件列表都叫 `<通道>Events`，事件都写成 `{"beats": [开始拍, 结束拍], "values": [开始值, 结束值], "ease": 指数}`；残影、故障、扭曲都写成数组。
* **歌曲信息只写在 `song.json`**：歌名、曲师、画师、BPM、变速、offset 是整首歌的，只写一次；难度文件里只有这个难度自己的东西。
* 画面、判定和计分规则都和 v4 一样。规则的细节（缓动公式、无限轨 / 有限轨、插入删除轨道、TIDE STREAM、LANE MINE、残影、故障、漩涡、扭曲……）见 `CHART_FORMAT_v4.md`，本文只写 v5 的键。
* 旧格式（v1–v4）仍然能读：游戏和谱面编辑器读到旧文件时按 v4 的规则读取（代码在 `elements/LegacyChartFormat`），旧的 v1–v3 照样自动换算。写出一律是 v5（`elements/ChartFormatV5`）。
* 读取在 `elements/ChartMapper`，`song.json` 在 `elements/SongInfo`。代码和本文不一致时以代码为准，并请同时修正本文。

---

## 0. 文件

| 文件 | 内容 |
|---|---|
| `assets/Charts/<歌曲 id>/song.json` | 整首歌：`title`、`artist`、`illustrator`、`bpm`、`bpmList`、`offsetMs`，还有试听段、曲绘取景等（§1） |
| `assets/Charts/<歌曲 id>/Surface.json` 等 | 一个难度一个文件：`Surface.json`、`Shallow.json`、`Deep.json`、`Abyss.json`（屏幕上显示为 SHALLOW / MEDIUM / DEEP / ABYSS）。难度只看文件名 |
| `assets/Introduction/song.json`、`Surface.json` | 教程，写法一样 |

单位和 v4 一样：时间用拍（第 0 拍在音频的 `offsetMs` 毫秒处），位置用 1920 × 1200 的参考画面（x 从左往右，y 从下往上），下判定线默认 y = 120，上判定线默认 y = 1080。

## 1. `song.json`

```json
{
  "title": "Confusion",
  "artist": "Tectogen_海渊",
  "illustrator": "Large Language Model",
  "bpm": 197,
  "offsetMs": 596,
  "previewStartMs": 51154,
  "previewEndMs": 70647,
  "jacket16x9": { "x": 0, "y": 0, "w": 1, "h": 0.5625 }
}
```

| 键 | 默认 | 说明 |
|---|---|---|
| `title` | 文件夹名 | 歌名 |
| `artist`、`illustrator` | `""` | 曲师、画师 |
| `bpm` | **必填** | 大于 0。有 `bpmList` 时写第一段的 BPM |
| `bpmList` | 不变速 | 变速：`[{"beat": 0, "bpm": 170}, {"beat": 64, "bpm": 85}]`，规则同 v4 §2.1 |
| `offsetMs` | 0 | 第 0 拍在音频里的毫秒数 |
| 其他键 | | `previewStartMs`、`previewEndMs`、`resultMusicStartMs`、`jacket16x9`、`jacketCircle`、`map` 同 v4 §12.2；谱面编辑器自己的键（例如 `previewStartBeat`）游戏不读 |

## 2. 难度文件的顶层

```json
{
  "format": 5,
  "charter": "Overwhelming Sensations",
  "constant": 14.9,
  "lines": { "upperEvents": [ { "beats": [231.5, 231.58], "values": [1080, 1110], "ease": 0.5 } ] },
  "notes": [
    { "type": "tap", "line": "upper", "lane": -2, "beat": 37 },
    { "type": "hold", "lane": -1, "beat": 9, "duration": 1, "height": 540 },
    { "type": "universal", "beat": 95.5 },
    { "type": "mine", "line": "upper", "lane": 2, "beat": 278.375,
      "positionEvents": [ { "beats": [278.125, 278.375], "values": [1300, 0] } ] }
  ],
  "editor": { "iterations": 84 }
}
```

| 键 | 默认 | 说明 |
|---|---|---|
| `format` | **必填** | 写 `5`。没有这个键（或写的是旧的 `formatVersion`）就按旧格式读 |
| `charter` | `""` | 谱师 |
| `constant` | 没有定数 | 定数，数字（例如 `14.9`），也可以是文字 |
| `laneCount` | 无限轨 | 写了就是有限轨，同 v4 §3.2 |
| `lanes` | 8 条默认轨道 −4..−1、1..4 | 轨道（§3）。全是默认轨道时不写 |
| `lines` | 默认判定线 | 判定线、全局缩放、镜头平移（§4）。全是默认值时不写 |
| `veils` | 没有漩涡 | 漩涡（§6） |
| `notes` | 没有音符 | 音符（§5） |
| `editor` | | 谱面编辑器自己的数据（例如 `iterations` 迭代次数、`flowBpmList`、`fallBy`），游戏不读（§8） |
| `song` | | 只给离开歌曲文件夹单独存放的谱面（备份、测试）：写法同 `song.json` 的 `title` … `offsetMs`。同一文件夹有 `song.json` 时以 `song.json` 为准，歌曲文件夹里的谱面不写 |

其他键一律跳过。

## 3. 事件

```json
{ "beats": [35, 37], "values": [1080, 0], "ease": 0.5 }
```

| 键 | 默认 | 说明 |
|---|---|---|
| `beats` | **必填** | `[开始拍, 结束拍]`。两个一样就是瞬间跳到结束值 |
| `values` | **必填** | `[开始值, 结束值]` |
| `ease` | 1 | 缓动指数（v4 的 `exponent`）：1 匀速，大于 1 先慢后快，小于 1 先快后慢。`value = 开始值 + (结束值 − 开始值) · p^ease` |

* 一个通道在某一拍的值：最后一个已开始的事件还没结束就用它的值，**否则用基础值**（和 v4 一样，不是上一个事件的结束值）。要让值停住，就写一个开始值等于结束值的事件盖住那段时间。
* 事件按开始拍排序，同一通道里不要重叠。
* 谱面编辑器可以在事件里加自己的键（例如 `rel`、`flow`），游戏不读。

## 4. 轨道 `lanes` 和判定线 `lines`

```json
"lanes": [
  { "id": -1, "width": 300, "widthEvents": [ { "beats": [16, 18], "values": [300, 240] } ] },
  { "id": 0, "insert": { "beats": [32, 34], "values": [0, 240] } },
  { "id": 3, "delete": { "beats": [60, 62], "values": [240, 0] } }
]
```

轨道键：`id`（必填）、`width`（默认 240）、`opacity`（默认 1）、`widthEvents`、`opacityEvents`、`insert`、`delete`。`insert` 和 `delete` 是一个事件（§3），意义同 v4 §3.6、§3.7。写了 `lanes` 就只有写出来的这些轨道（无限轨时中间缺的 id 和画面能露出的轨道照样自动补上）。

```json
"lines": { "upper": 1060, "lower": 120, "upperEvents": [], "lowerEvents": [], "scale": 0.8, "scaleEvents": [], "camera": 120, "cameraEvents": [] }
```

| 键 | 默认 | v4 里叫 |
|---|---|---|
| `upper`、`upperEvents` | 1080 | `upperJudgementLine`、`upperEvents` |
| `lower`、`lowerEvents` | 120 | `lowerJudgementLine`、`lowerEvents` |
| `scale`、`scaleEvents` | 1 | `laneScale`、`laneScaleEvents`（全局缩放） |
| `camera`、`cameraEvents` | 0 | `cameraFrame`、`cameraFrameEvents`（镜头平移） |

## 5. 音符 `notes`

| 键 | 默认 | 说明（v4 里叫） |
|---|---|---|
| `type` | **必填** | `tap` DROP 水滴、`hold` STREAM 水流、`mine` MINE 水雷、`universal` TIDE 浪花、`tideMine` TIDE MINE 浪花水雷（§5.1） |
| `line` | `lower` | `lower` 地面线、`upper` 天空线 |
| `lane` | **轨道音符必填** | 轨道 id。`universal`、`tideMine` 不写 |
| `beat` | **必填** | 判定拍（`judgement`） |
| `duration` | 0 | 持续拍数。`hold` 必写；`universal` 写了是 TIDE STREAM，`mine` 写了是 LANE MINE，`tideMine` 写了是 TIDE STREAM MINE |
| `height` | 0 | 尾巴长度（hold、TIDE STREAM、LANE MINE） |
| `waveSpeed` | 0 | 只给 TIDE、TIDE STREAM（2026-10-28）：浪花每拍移动几个波，正数向右、负数向左，0 不动。地面线是波往旁边流，天上线是珍珠串往旁边移（一个波 = 两颗珍珠）。其他音符写了也忽略，不写回 |
| `fake` | 没写 | 假键（2026-11-02，Kelvin，给 `tap`、`hold`、`mine` 和 `universal` 浪花、潮汐（05:28 起），`tideMine` 写了忽略）：`true` 时只是装饰，画法和真的一样（移动、旋转、公转、横移、透明度、残影、故障、扭曲都照常），到判定拍（长条到结束拍）就不再画。不判定，不算音符数和水雷数，不计分、不算连击，没有打击效果和打击音，判定线上不发光提示，也不算多押（同一拍的真键不会因为它出多押提示）。假的浪花不和同一拍的浪花组成一组，每个都单独画（同一拍的真浪花样子一样时只画一个，位置、角度等不同时各画各的）。曲子结束的时间仍会等它画完 |
| `positionEvents` | 默认下落 | 离判定线的距离（`MovementEvents`）。不写就用默认下落；不在任何事件里的拍子音符在画面外 |
| `appear` | | 只给“没有接近”的音符：不写 `positionEvents`、`appear` 不早于 `beat` 时，音符不下落，判定时直接出现（v4 §9 的例外，`appearance`） |
| `rotationEvents` | 不转 | 角度（`RotationEvents`） |
| `opacityEvents` | 1 | 透明度（`DisappearEvents`） |
| `heightEvents` | 默认缩短 | 尾巴长度（`HeightEvents`），只给 hold、TIDE STREAM、LANE MINE |
| `pivot` | `[0, 0]` | 自转中心 `[x, y]`，从音符自己的中心量起（v4 §6.1.1）。谱面编辑器里自转固定在键中心（长条是键头的中心，2026-10-29 20:55），不再填；游戏照旧读旧谱里写了的 `pivot` |
| `offsetX` | 0 | 只给轨道上的音符（2026-10-28）：画在轨道中心右边多少参考单位（负数往左；天地两条线都是屏幕向右，按 kx 拉伸）。先横移再按 `pivot` 旋转，`pivot` 从横移后的音符量起。只改画面，判定和打击效果仍在原轨道 |
| `offsetXEvents` | 用 `offsetX` | 横移随拍子变（2026-10-28，只给轨道上的音符，写法同 `positionEvents`）：事件里的值是那一拍的横移。有事件时，不在事件里的拍子横移是 0（回到轨道中心），常量 `offsetX` 只在没有事件时用（2026-10-29）。`pivot` 从横移后的音符量起 |
| `orbit` | 没有 | 公转中心（2026-10-29，只给轨道上的音符）：参考画面里的绝对坐标 `[x, y]`（1920 × 1200，y 从下往上，`[960, 600]` 是画面中心）。只写 `orbit` 时（2026-10-29 19:04 的写法），按音符自己的角度绕这一点转，不用 `pivot`；几个键写同一个点就整体转；角度 0、中心没动时，键就在没写 `orbit` 时的位置。再写了半径或公转角度（下面几行）就按半径和角度摆。在参考画面里算，再按场地拉伸（16:10 屏幕上形状不变，别的比例跟轨道一样横向拉伸）。只改画面 |
| `orbitXEvents`、`orbitYEvents` | 不动 | 公转中心随拍子移动（2026-10-29，写法同 `positionEvents`，值是绝对坐标），键跟着平移。和别的通道不同：事件结束后中心停在事件的终点，直到下一个事件；第一个事件之前在 `orbit`。没写 `orbit` 时忽略 |
| `orbitRadius`、`orbitRadiusEvents` | 没有 | 公转半径（2026-10-29 20:55，参考单位，事件写法同 `positionEvents`，像中心一样事件结束后停在终点；没写 `orbitRadius` 时第一个事件之前用它的起始值）。写了半径或公转角度，公转时段里键头的中心就在「公转中心 + 半径 × 公转角度的方向」，半径为负就伸到另一边；键自己的旋转只让它绕键头中心自转。公转时段从公转中心、半径、角度事件里最早的开始到最晚的结束（都没有事件就一直起作用），时段外键在自己的轨道上按「移动」落下。键什么时候显示仍看「移动」和透明度 |
| `orbitAngle`、`orbitAngleEvents` | 0 | 公转角度（2026-10-29 21:13，度，0° 朝右、逆时针为正，事件写法和停在终点同半径）。和键自己的「旋转」分开：公转角度在转、旋转不动，键就一直平行于判定线绕着转；两个写同样的转动就像辐条一样整体转 |
| `orbitConflict` | 没写 | 公转时段里「移动」给的键中心高度和公转不同（谱面编辑器的「物件坐标冲突」）时以谁为准：`"orbit"` 公转，`"move"` 高度用「移动」、横坐标仍用公转。没写时游戏按公转画，谱面编辑器提醒去选 |
| `orbitRigid` | 没写 | 刚性公转（2026-11-02，Kelvin，只对写了 `orbitRadius` 或 `orbitAngle` 的公转）：`true` 时半径横竖按同一个比例缩放，不是 16 : 10 的屏幕上公转出来的形状不会被压扁，每条「臂」的方向和键自己的旋转对得上。缩放比例取场地的竖向比例 `ky`（2026-11-04 起；以前取横向、纵向里小的那个 `min(kx, ky)`）：16 : 10 及更宽的屏幕上和以前完全一样，只有比 16 : 10 更窄的屏幕（4 : 3 平板）不再按横向缩小。公转中心照旧按位置拉伸，键本身的大小和旋转不变。没写时和以前一样（横按 kx、竖按 ky） |
| `trails` | 没有 | 残影，数组：`{"length", "density", "alpha", "fade", "mode", "from", "to"}`（v4 §6.8 的 `trail`） |
| `glitches` | 没有 | 故障，数组：`{"from", "to", "intensity", "intensityEnd", "ease", "rate", "split", "slice", "flicker"}`（v4 §6.9 的 `glitch`；`exponent` 改叫 `ease`） |
| `distortions` | 没有 | 扭曲，数组：`{"from", "to", "intensity", "intensityEnd", "ease"}`（v4 §6.11 的 `distortion`） |

残影、故障、扭曲里各个键的默认值和取值范围同 v4。`from` / `to` 不写就是不限。

同一条线、同一轨道、同一拍可以放几个 `tap` / `hold`（2026-11-02 起，Kelvin），读取时都保留，每个都是单独的音符：一次按下只判一个，叠了几个就要几根手指。以前读取时只留一个。

### 5.1 浪花水雷 `tideMine`（2026-10-31）

浪花和潮汐的水雷版本：画成那条线的浪花（地面是波浪、天上是珍珠串，带 `duration` 时后面跟潮汐的长条），颜色是水雷红。键同 `universal`（没有轨道、横移、公转）。不算进音符总数，算进水雷数；碰到时断连击，分数加 10,000（2026-11-01 起，见 CHART_FORMAT_v4 §6.4），自动播放从不碰。

* **浪花水雷**（不写 `duration`）：判定区里任何位置按下就算碰到（键盘上这条线的任何键，空格也算），±133.33 ms，只算按下的那一下。同一条线上它的判定窗和别的音符（所有轨道的 DROP、STREAM 头，还有浪花）重叠时，按普通水雷的规则互扣（v4 §6.4）：水雷让出那个音符的共鸣窗，那个音符的回响窗扣掉水雷窗。
* **潮汐水雷**（写了 `duration`）：像轨道水雷，只是封住整条线的判定区：到线后到结束 `[T, end)`，手指在判定区里（按下、停着、滑进来）或按着这条线的键，每碰一次断一次连击，离开后才会再算。同一条线上共鸣窗碰到它的音符先判，那个音符没有回响；打出共鸣后这条线的潮汐水雷睡 150 ms（v4 §6.4.1 的让位和保护，只是按整条线算）。

## 6. 漩涡 `veils`

```json
"veils": [
  { "shape": "circle", "radius": 210, "x": 1480, "y": 322, "from": 112, "to": 130,
    "opacityEvents": [ { "beats": [112, 113], "values": [0, 1] } ] }
]
```

键：`shape`（`circle` / `polygon`）、`radius` 或 `points`（`[[x, y], …]`）、`x`（默认 960）、`y`（默认 600）、`rotation`（0）、`opacity`（1）、`from` / `to`（显示的拍数范围，v4 的 `startTime` / `endTime`，不写就是不限）、`xEvents`、`yEvents`、`rotationEvents`、`opacityEvents`、`glitches`、`distortions`。规则同 v4 §6.10。

### 6.1 水帘 `curtains`

```json
"curtains": [
  { "shape": "polygon", "points": [[-300, -560], [300, -560], [300, 560], [-300, 560]], "x": 1260,
    "from": 64, "to": 96, "blur": 24, "blurEvents": [ { "beats": [64, 66], "values": [0, 24] } ] }
]
```

纯演出：把盖住的画面整个模糊（轨道、音符、漩涡、判定线、打击特效、连击数、判定字、四角的分数和暂停键都一样），只有暂停菜单不受影响；从不判定，点在上面照常判定。键同 `veils`（`distortions` 让边缘摆动，`glitches` 不画），另加 `blur`（模糊半径，参考坐标单位，默认 16，0–64，超出范围读入时修正）和 `blurEvents`（按拍变化，同其他事件）。边缘柔和，里面有细细的水痕往下流，颜色偏冷一点；中心和形状都跟漩涡一样随屏幕比例拉伸（横向乘 屏幕宽 / 1920、纵向乘 屏幕高 / 1200）。

## 7. 从旧格式转换

* 游戏和谱面编辑器都能读旧文件；写出时一律写 v5。
* 一次性转换工具：`ChartUpgrade`（把一个文件夹里所有旧谱面转成 v5，同时把歌曲信息写进 `song.json`，保留 `song.json` 原有的键）。转换前后逐个音符比对，一致才写入。
* 旧文件里谱面编辑器的键（`iterations`、`flowBpmList`、`fallBy`）放进 `editor`，事件上的 `rel`、`flow` 原样保留。

## 8. 谱面编辑器写出的内容

* 歌曲文件夹里的谱面文件只写本难度的东西（§2），不写 `song`；歌名、曲师、画师、BPM、变速、offset 写进 `song.json`，保留 `song.json` 原有的键（`SongInfo.mergeInto`）。
* 编辑器自己的数据放在顶层 `editor` 里（`iterations`、`flowBpmList`、`fallBy` 等），事件上的编辑器键（`rel`、`flow`）直接写在那个事件里。游戏都不读。
* 不写 `positionEvents` 的音符是默认下落，编辑器按“跟着判定拍走”（`rel` 为 true）处理。改成绝对拍数的默认下落要照常写出 `positionEvents`，并在事件里写 `"rel": false`。
* 默认值不写；写了默认值也能读，只是文件大一些。
