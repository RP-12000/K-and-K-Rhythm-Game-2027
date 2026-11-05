# Android PCM Music Bugfix Design

## Overview

安卓上游玩的节奏游戏在暂停-继续后音乐位置偏走：每次差得不一样（快约 40ms 或慢 65–100ms）。根因是 Android 用 MediaPlayer 放歌，seek 后它报告的位置和实际声音对不上，而游戏只能相信这个报告。此外，继续后的第一下自动播放打击音比后面的晚 50–90ms，且自动播放打击音没算上用户的音频偏移（audio offset），和判定对不上。

修复方案是在 Android 上不再用 MediaPlayer 放歌，而是先把歌曲解码成 PCM，再通过现有的低延迟通道（AndroidHitOutput 的 AudioTrack）自己按时刻放，让音乐和打击音共用同一条通道、同一个时钟。这样音乐放到哪一刻游戏都确切知道（精确到采样），暂停、继续、跳转都能对到采样。同时把自动播放打击音改成按谱面时间排（算上音频偏移），暂停时还没响的全部取消，继续后从暂停的地方重新排。

核心改动：引入 AudioSource 抽象让 SongClock 既能用旧的 libGDX Music（桌面）也能用新的共享通道（Android）；在 AndroidHitOutput 上增加音乐混音能力；新增 PCM 解码器把 MP3/WAV 解码成 short[]；简化 SongClock 的 Android 分支中为 MediaPlayer 打补丁的复杂逻辑。

## Glossary

- **Bug_Condition (C)**: 触发 bug 的条件——在 Android 上音乐位置来自 MediaPlayer（不精确），或自动播放打击音未包含音频偏移
- **Property (P)**: 修复后的期望行为——音乐位置精确到采样，打击音和判定对齐
- **Preservation**: 必须保持不变的行为——桌面播放、玩家输入打击音、和弦合并、菜单音乐等
- **AudioSource**: 新的音频源抽象接口，封装音乐播放的位置查询、播放/暂停/跳转/音量控制
- **MusicAudioSource**: AudioSource 的桌面实现，包装 libGDX Music（OpenAL），也用于 Android <8.0 的回退
- **ChannelAudioSource**: AudioSource 的 Android 实现，包装共享低延迟通道（AndroidHitOutput）的音乐混音能力
- **AudioSourceFactory**: 平台相关的工厂接口，根据平台创建对应的 AudioSource
- **PcmDecoder**: Android 上的 PCM 解码器，用 MediaExtractor + MediaCodec 把 MP3/WAV 解码成立体声 PCM short[]
- **SongMixer**: AndroidHitOutput 上新增的音乐混音能力——在同一个 AudioTrack 缓冲区里同时混入音乐和打击音
- **SongClock**: 音频锚点时钟（`core/src/main/java/org/kelvinizer/audio/SongClock.java`），负责在音频锚点之间用 nanoTime 插值歌曲时间
- **HitMixer**: 打击音混音器（`core/src/main/java/org/kelvinizer/audio/HitMixer.java`），按设备时间戳把每一下排到准确的一刻响
- **AndroidHitOutput**: 安卓 8.0+ 的低延迟打击音输出（`android/src/main/java/org/kelvinizer/android/AndroidHitOutput.java`），自己开一条 AudioTrack 一直放着
- **GameEngine**: 纯 Java 游戏引擎（`core/src/main/java/org/kelvinizer/gameplay/GameEngine.java`），持有 SongClock，判定用 `clock.nowMs() - audioOffsetMs`
- **GameplayScreen**: 游玩画面（`core/src/main/java/org/kelvinizer/gameplay/GameplayScreen.java`），持有 GameEngine，管理暂停/继续/自动播放
- **audioOffsetMs**: 用户的音频偏移（DeviceSettings），正值表示玩家听到的音频偏晚，判定时间 = `clock.timeAtNanos(event) - audioOffsetMs`
- **frameAt(nanos)**: HitMixer 中根据设备时间戳把纳秒时间换算成流帧号的方法——修复后 SongClock 的 audioMs() 在 Android 上用它读通道位置

## Bug Details

### Bug Condition

Bug 在以下情况出现：

1. **Android 音乐位置不精确**：在 Android 上，游戏的 SongClock 用 MediaPlayer（通过 libGDX Music）放歌，每次 update() 把锚点拉向 `music.getPosition()`。MediaPlayer 在 seek（包括 pause-resume 时的 reseek）后报告的位置和实际放出来的声音对不上，每次差不一样。这导致暂停-继续后时钟偏走 40–100ms，跳转后位置不准。

2. **继续后第一下自动播放打击音偏晚**：Android 上继续后 SongClock 进入 resumeSlew 状态，时钟缓慢追上音乐。第一下自动播放打击音按这个偏走的时钟排，比后面的晚 50–90ms。

3. **自动播放打击音未包含音频偏移**：`hitTimes(chart)` 返回 `note.judgementMs()`（谱面判定时间），没有加上 `audioOffsetMs`。但判定发生在 `clock.nowMs() - audioOffsetMs >= note.judgementMs()` 时，即歌曲时间 `note.judgementMs() + audioOffsetMs`。打击音排在歌曲时间 `note.judgementMs()`，比判定早 `audioOffsetMs`。

**Formal Specification:**
```
FUNCTION isBugCondition(input)
  INPUT: input of type PlaybackContext
  OUTPUT: boolean

  // C1: Android 音乐位置来自 MediaPlayer，不精确
  IF input.platform == ANDROID
     AND input.musicSource == MEDIAPLAYER
     AND input.operation IN {PAUSE_RESUME, SEEK, AUTOPLAY_AFTER_RESUME}
  THEN
    RETURN abs(reportedPositionMs(input) - actualAudioPositionMs(input)) > 0
  END IF

  // C2: 自动播放打击音未包含音频偏移
  IF input.autoplay == true
     AND input.timedHits == true          // Android 8.0+ 才有定时打击音
     AND input.hitTimesIncludeAudioOffset == false
  THEN
    RETURN true
  END IF

  RETURN false
END FUNCTION
```

### Examples

- **暂停-继续偏快**：歌曲播到 30000ms 时暂停，继续后 MediaPlayer 报告 29960ms（快了 40ms），时钟跟着偏，谱面比音乐早 40ms
- **暂停-继续偏慢**：歌曲播到 60000ms 时暂停，继续后 MediaPlayer 报告 60065ms（慢了 65ms），时钟跟着偏，谱面比音乐晚 65ms
- **暂停-继续偏慢更多**：歌曲播到 90000ms 时暂停，继续后 MediaPlayer 报告 90100ms（慢了 100ms），每次差不一样
- **继续后第一下打击音偏晚**：暂停后继续，第一个自动播放打击音比同一段后面的打击音晚 50–90ms，因为时钟在 resumeSlew 中缓慢追上
- **跳转位置不准**：retry 跳到 45000ms，MediaPlayer 有 Xing/Info TOC 时按 1/256 文件长度跳，实际可能差到 ~1s（已有 seekableCopy 缓解但仍不精确）
- **打击音未含偏移**：audioOffsetMs = 50，音符 judgementMs = 10000，打击音排在歌曲时间 10000ms，但判定在歌曲时间 10050ms 发生，打击音比判定早 50ms

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- 桌面端继续用 libGDX Music（OpenAL）放歌，完全不变
- 玩家输入的打击音继续按输入事件时间戳通过低延迟通道排
- 和弦（同一拍多个音符）的打击音继续在 20ms 合并窗口内合成一声
- 菜单音乐、歌曲预览、结算音乐、校准音频继续用 AudioService 的 libGDX Music，不受影响
- 歌曲开始的留白（lead-in）、开场倒计时、音乐启动时序继续正常工作
- 判定逻辑（事件时间戳→歌曲时间、音频偏移用于判定、视觉偏移用于绘制）不变
- 暂停后继续的 3-2-1 倒计时和谱面淡入继续工作
- 没有安装打击音文件时静音不崩溃
- Android <8.0（没有低延迟通道）继续回退到之前的播放方式

**Scope:**
所有不涉及 Android 音乐播放位置和自动播放打击音定时的输入都完全不受影响：
- 桌面端的所有播放（音乐、打击音、菜单音乐等）
- Android 上的玩家输入打击音（已经通过低延迟通道，不受音乐改动影响）
- Android <8.0 的回退路径（继续用 MediaPlayer + SoundPool）
- AudioService 的菜单/预览/结算/校准音乐（独立的 libGDX Music 通道）
- 谱面加载、视觉渲染、输入处理、判定逻辑

## Hypothesized Root Cause

根据代码分析，最可能的问题：

1. **MediaPlayer 位置不精确（C1 主因）**：Android 的 MediaPlayer 在 seek 后报告的位置和实际音频输出不一致
   - pause-resume 时 SongClock.reseekOnResume() 先 seek 到冻结时间，但 MediaPlayer 的 seek 本身就不精确
   - Xing/Info TOC 导致粗粒度跳转（已有 seekableCopy 缓解，但仍有残留误差）
   - MediaPlayer 的内部时钟在 resume 时可能继续走，导致报告位置和实际声音脱节
   - SongClock 每次 update() 用 NUDGE_GAIN 把锚点拉向 music.getPosition()，HARD_RESYNC_MS(60ms) 时硬重锚，但拉向的本身就是错的

2. **音乐和打击音用不同时钟（C1 次因）**：音乐走 MediaPlayer 的时钟，打击音走 AudioTrack 的 getTimestamp()。这两个时钟之间没有同步关系，相对漂移不可控

3. **resumeSlew 导致第一下打击音偏晚（C2）**：继续后 SongClock 进入 resumeSlew，时钟以最多 10% 的速度差缓慢追上音乐。queueAutoHits() 用这个偏走的 clock.timeAtNanos() 排打击音，第一下排在错误的时间

4. **hitTimes() 未加音频偏移（C3）**：`hitTimes(chart)` 直接返回 `note.judgementMs()`（谱面时间），而判定发生在 `clock.nowMs() - audioOffsetMs >= note.judgementMs()`，即歌曲时间 `note.judgementMs() + audioOffsetMs`。打击音排在 `note.judgementMs()`，比判定时刻早 `audioOffsetMs`

## Correctness Properties

Property 1: Bug Condition - Android 音乐位置精确到采样

_For any_ Android playback input where the bug condition holds (music position from MediaPlayer after pause-resume or seek), the fixed system SHALL decode the song to PCM and play it through the shared low-latency channel (AudioTrack), reporting the music position sample-accurately from the channel's frame count and timestamp, so that pause-resume resumes at the exact sample with no drift, seek jumps to the exact sample, and the first autoplay hit after resume aligns with subsequent hits.

**Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.6, 2.7, 2.8**

Property 2: Bug Condition - 自动播放打击音包含音频偏移

_For any_ autoplay input where hit sounds are timed (Android 8.0+ with low-latency output), the fixed system SHALL schedule each hit sound at the chart judgement time plus the user's audio offset (judgement ms + audio offset ms), so the hit sound aligns with when the note is actually judged.

**Validates: Requirements 2.5**

Property 3: Preservation - 非 bug 输入行为不变

_For any_ input where the bug condition does NOT hold (desktop playback, player input hit sounds, menu music, Android below 8.0, chord merging, no hit sound file), the fixed system SHALL produce exactly the same behavior as the original system, preserving all existing functionality for non-buggy inputs.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9**

## Fix Implementation

### Changes Required

假设根因分析正确，需要以下改动：

**1. AudioSource 接口（新增，core）**

**文件**: `core/src/main/java/org/kelvinizer/audio/AudioSource.java`（新建）

抽象音乐播放源，让 SongClock 不直接依赖 libGDX Music：
```
interface AudioSource:
  float getPositionMs()     // 当前播放位置（ms）
  void play()               // 开始/继续播放
  void pause()              // 暂停
  void setPositionMs(float) // 跳转到指定位置
  void setVolume(float)     // 设置音量
  boolean isPlaying()       // 是否正在播放
  void dispose()             // 释放资源
```
- SongClock 用 AudioSource 替代直接持有 Music 对象
- `audioMs()` 改为 `source.getPositionMs() - audioDelayMs`（Android 上 audioDelayMs=0，因为自解码没有 MP3 解码延迟）

**2. MusicAudioSource（新增，core）**

**文件**: `core/src/main/java/org/kelvinizer/audio/MusicAudioSource.java`（新建）

包装 libGDX Music，用于桌面和 Android <8.0 回退：
- 所有方法委托给 Music 对象
- `getPositionMs()` 返回 `music.getPosition() * 1000f`
- `setPositionMs(ms)` 调用 `music.setPosition(ms / 1000f)`

**3. AudioSourceFactory 接口（新增，core）**

**文件**: `core/src/main/java/org/kelvinizer/audio/AudioSourceFactory.java`（新建）

平台相关工厂，创建 AudioSource：
```
interface AudioSourceFactory:
  AudioSource create(FileHandle file, float durationMs)
```
- 桌面：不设工厂（null），SongClock.load() 内部创建 MusicAudioSource
- Android 8.0+：AndroidHitOutput 实现 AudioSourceFactory，解码 PCM 并创建 ChannelAudioSource
- Android <8.0：AndroidHitOutput.create() 返回 null，工厂也为 null，回退到 MusicAudioSource

**4. PcmDecoder（新增，android）**

**文件**: `android/src/main/java/org/kelvinizer/android/PcmDecoder.java`（新建）

用 MediaExtractor + MediaCodec 把 MP3/WAV 解码成立体声 PCM short[]：
- `static short[] decode(FileHandle file, int outRate)`: 解码并重采样到输出速率，返回立体声交错 short[]
- 用 MediaExtractor 读取音频轨，MediaCodec 解码为 PCM
- 线性重采样到输出速率（同 HitMixer.load 的重采样逻辑）
- 单声道复制为立体声
- 在后台线程调用（chart 加载线程），避免阻塞 GL 线程
- 内存：3 分钟 48kHz 立体声 ≈ 35MB，可接受

**5. AndroidHitOutput 增加音乐混音（修改，android）**

**文件**: `android/src/main/java/org/kelvinizer/android/AndroidHitOutput.java`

在现有 HitMixer 基础上增加音乐混音能力：
- 新增字段：`short[] songPcm`（解码后的歌曲 PCM）、`long songFrame`（当前播放帧）、`float songVolume`、`boolean songPlaying`、`int songFrames`
- 新增方法：`loadSong(short[] pcm)`（存储歌曲 PCM）、`playSong()`、`pauseSong()`、`seekSong(long frame)`、`songPositionMs()`、`setSongVolume(float)`、`unloadSong()`
- 实现 AudioSourceFactory：`create(FileHandle, float)` 解码 PCM 并返回 ChannelAudioSource
- 重写 `mix(short[], int, long)`：先混入音乐（从 songFrame 读 PCM 累加到 float 缓冲区），再混入打击音（调用重构后的 hit-voice 混音），最后 clip 到 short[]
- `songPositionMs()`：用 `frameAt(nanoTime())` 减去音乐开始帧，换算成 ms——精确到采样
- 线程安全：play/pause/seek/volume 从 GL 线程调用，用 synchronized 保护；songFrame 由音频线程推进
- pause 时停止推进 songFrame（但不停止 AudioTrack，通道继续输出静音或打击音）

**6. HitMixer 混音重构（修改，core）**

**文件**: `core/src/main/java/org/kelvinizer/audio/HitMixer.java`

提取 hit-voice 混音逻辑为可被子类调用的方法：
- 新增 `protected void mixVoices(float[] acc, int frames, long first)`：把打击音声部累加到 acc（现有的 voice 混音逻辑）
- `mix(short[], int, long)` 改为：填零 acc → 调用 `mixMusic(acc, frames, first)`（空钩子）→ 调用 `mixVoices(acc, frames, first)` → clip 到 out
- 新增 `protected void mixMusic(float[] acc, int frames, long first)`：空默认实现，AndroidHitOutput 重写
- 新增 `protected void clipTo(short[] out, float[] acc, int n)`：float 到 short 的 clip 转换

**7. ChannelAudioSource（新增，android）**

**文件**: `android/src/main/java/org/kelvinizer/android/ChannelAudioSource.java`（新建）

实现 AudioSource，包装 AndroidHitOutput 的音乐能力：
- `getPositionMs()`：返回 `channel.songPositionMs()`
- `play()`：调用 `channel.playSong()`
- `pause()`：调用 `channel.pauseSong()`
- `setPositionMs(ms)`：调用 `channel.seekSong((long)(ms * rate / 1000))`
- `setVolume(v)`：调用 `channel.setSongVolume(v)`
- `isPlaying()`：返回 `channel.songIsPlaying()`
- `dispose()`：调用 `channel.unloadSong()`

**8. SongClock 改造（修改，core）**

**文件**: `core/src/main/java/org/kelvinizer/audio/SongClock.java`

- 将 `Music music` 字段替换为 `AudioSource source`
- 新增 `setAudioSourceFactory(AudioSourceFactory)`：设置平台工厂（启动时设一次，null 表示桌面）
- `load(FileHandle, float)`：如果有工厂用工厂创建 AudioSource，否则创建 MusicAudioSource
- `audioMs()` 改为 `source.getPositionMs() - audioDelayMs`
- 所有 `music.X()` 调用改为 `source.X()`
- 简化 Android 特有逻辑（大部分是为 MediaPlayer 不精确打补丁，自解码后位置精确）：
  - `seekableCopy()`：不再需要（自解码，按帧跳转精确）
  - `reseekOnResume()`：不再需要（通道位置精确，pause/resume 位置不偏）
  - `countIn` run-in 机制：简化（不需要静音跑进来对齐；保留淡入避免突然出声）
  - `resumeSlew`：简化或去掉（位置精确，不需要缓慢追赶）
  - `startLeadMs/preroll`：保留（AudioTrack 也有 buffer 填充延迟，但比 MediaPlayer 小且恒定）

**9. hitTimes() 加音频偏移（修改，core）**

**文件**: `core/src/main/java/org/kelvinizer/gameplay/GameplayScreen.java`

- `hitTimes(Chart chart)` 改为 `hitTimes(Chart chart, float audioOffsetMs)`
- 返回值从 `note.judgementMs()` 改为 `note.judgementMs() + audioOffsetMs`
- `begin()` 中调用改为 `hitTimes(chart, engine.audioOffsetMs())`

**10. 管线连接（修改）**

**文件**: `android/src/main/java/org/kelvinizer/android/AndroidLauncher.java` 和 `core/src/main/java/org/kelvinizer/Main.java`

- AndroidLauncher 不变（已创建 AndroidHitOutput 并传给 Main）
- Main.create() 中将 AndroidHitOutput 同时设为 AudioSourceFactory 传给 AudioService（或直接传给 SongClock）
- AudioService 新增 `setAudioSourceFactory(AudioSourceFactory)` 和 `audioSourceFactory()`
- GameplayScreen.begin() 通过 `game.audio().audioSourceFactory()` 获取工厂，传给 `engine.clock().load(file, dur, factory)`
- SongClock.load() 有工厂参数时用工厂创建 AudioSource，否则创建 MusicAudioSource

## Testing Strategy

### Validation Approach

测试策略分两阶段：先在未修复的代码上暴露反例（证明 bug 存在并确认根因），再验证修复正确且不破坏现有行为。

### Exploratory Bug Condition Checking

**Goal**: 在实现修复之前暴露反例，确认或推翻根因分析。如果推翻，需要重新假设。

**Test Plan**: 写测试模拟 Android 上的暂停-继续和跳转场景，断言音乐位置精确。在未修复代码上运行以观察失败。

**Test Cases**:
1. **暂停-继续偏移测试**：在 Android 上模拟暂停在 30000ms 后继续，断言恢复后位置精确在 30000ms（未修复代码会偏 40–100ms）
2. **多次暂停-继续测试**：连续暂停-继续 5 次，断言每次恢复后位置精确（未修复代码每次偏得不同）
3. **跳转精度测试**：在 Android 上模拟跳转到 45000ms，断言位置精确在 45000ms（未修复代码可能偏到 44xxx 或 45xxx）
4. **继续后第一下打击音测试**：暂停后继续，断言第一个自动播放打击音和后面的对齐（未修复代码第一下晚 50–90ms）
5. **打击音偏移测试**：audioOffsetMs=50，音符 judgementMs=10000，断言打击音排在歌曲时间 10050ms（未修复代码排在 10000ms）

**Expected Counterexamples**:
- 暂停-继续后位置偏移 40–100ms，每次不同
- 第一下打击音比后面的晚 50–90ms
- 打击音比判定早 audioOffsetMs
- 可能原因：MediaPlayer seek 不精确、resumeSlew 时钟追赶、hitTimes 未加偏移

### Fix Checking

**Goal**: 验证所有 bug 条件成立的输入，修复后产生期望行为。

**Pseudocode:**
```
FOR ALL input WHERE isBugCondition(input) DO
  result := fixedSystem(input)
  ASSERT expectedBehavior(result)
  // C1: Android 位置精确到采样
  // C2: 打击音在 judgementMs + audioOffsetMs 时刻响
END FOR
```

### Preservation Checking

**Goal**: 验证所有 bug 条件不成立的输入，修复后行为和原来完全一样。

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT originalSystem(input) == fixedSystem(input)
END FOR
```

**Testing Approach**: 推荐用 property-based testing 做保留性检查，因为：
- 自动生成大量测试用例覆盖输入域
- 能抓到手写单元测试可能漏掉的边界情况
- 对所有非 bug 输入提供强保证

**Test Plan**: 先在未修复代码上观察桌面播放、玩家输入打击音、菜单音乐等行为，再写 property-based 测试捕捉这些行为。

**Test Cases**:
1. **桌面播放保留**：观察桌面用 libGDX Music 播放的位置和时序，写测试验证修复后不变
2. **玩家输入打击音保留**：观察玩家输入打击音按事件时间戳排，写测试验证修复后不变
3. **和弦合并保留**：观察同拍多个音符的打击音在 20ms 内合并，写测试验证修复后不变
4. **菜单音乐保留**：观察 AudioService 的菜单/预览/结算/校准音乐不受影响
5. **判定逻辑保留**：观察事件时间戳→歌曲时间、音频偏移用于判定不变
6. **Android <8.0 回退保留**：观察无低延迟通道时回退到 MediaPlayer + SoundPool

### Unit Tests

- AudioSource 接口测试：MusicAudioSource 和 ChannelAudioSource 各自的位置查询、播放/暂停/跳转
- PcmDecoder 测试：解码 MP3 和 WAV，验证采样率、声道数、数据正确性
- SongMixer 混音测试：音乐 + 打击音同时混入同一缓冲区，验证音量、位置、clip
- SongClock Android 分支测试：用模拟 AudioSource 测试暂停/继续/跳转后位置精确
- hitTimes 测试：验证返回值包含 audioOffsetMs

### Property-Based Tests

- 生成随机暂停-继续序列，验证 Android 上修复后位置精确（每次恢复后位置 = 暂停时位置）
- 生成随机跳转位置，验证 Android 上修复后跳转精确（位置 = 目标位置）
- 生成随机游戏状态和 audioOffsetMs，验证自动播放打击音排在 judgementMs + audioOffsetMs
- 生成随机桌面播放场景，验证桌面行为完全不变（位置、打击音、音量等）
- 生成随机玩家输入，验证打击音按事件时间戳排不变
- 生成随机和弦（同拍多音符），验证 20ms 合并窗口不变

### Integration Tests

- 完整 Android 游玩流程：加载歌曲→解码 PCM→开始播放→暂停→继续→验证位置精确
- Android 上切换上下文（暂停-倒计时-继续）后用键盘/触摸打击音验证
- Android 上打击音和音乐共用同一通道，验证视觉反馈在正确时刻出现
- 桌面端完整游玩流程：验证和修复前完全一致
- Android <8.0 回退：验证继续用 MediaPlayer + SoundPool，不崩溃
