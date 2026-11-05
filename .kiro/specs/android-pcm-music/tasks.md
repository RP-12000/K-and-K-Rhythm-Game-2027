# Implementation Plan

本文档把 design.md 的 10 项改动拆成可执行的实现任务，按依赖顺序排列。
任务 1、2 是在未修复代码上先写的探索测试和保留测试（property-based），用于暴露 bug 和锁定基线行为。
任务 3 是完整实现，含 10 项改动的子任务和验证步骤。

## Overview

修复 Android 上暂停-继续后音乐位置偏走（MediaPlayer 位置不精确）和自动播放打击音未含音频偏移的问题。方案是在 Android 上解码歌曲为 PCM，通过共享低延迟通道（AudioTrack）播放，让音乐和打击音共用同一通道和时钟；同时把自动播放打击音改为按谱面时间排（算上音频偏移）。

## Task Dependency Graph

```json
{
  "waves": [
    {
      "name": "测试 + 独立改动",
      "tasks": ["1", "2", "3.1", "3.10"]
    },
    {
      "name": "基础抽象层",
      "tasks": ["3.2", "3.3", "3.4"],
      "depends": ["3.1"]
    },
    {
      "name": "Android 解码和混音",
      "tasks": ["3.5", "3.6"],
      "depends": ["3.1"]
    },
    {
      "name": "Android 输出整合",
      "tasks": ["3.7", "3.8"],
      "depends": ["3.4", "3.5", "3.6"]
    },
    {
      "name": "时钟改造",
      "tasks": ["3.9"],
      "depends": ["3.2", "3.3", "3.4"]
    },
    {
      "name": "管线连接",
      "tasks": ["3.11"],
      "depends": ["3.4", "3.7", "3.8", "3.9", "3.10"]
    },
    {
      "name": "验证",
      "tasks": ["3.12", "3.13"],
      "depends": ["3.11"]
    },
    {
      "name": "检查点",
      "tasks": ["4"],
      "depends": ["3.12", "3.13"]
    }
  ]
}
```

## Tasks

- [ ] 1. 写 bug 条件探索测试（修复前）
  - **Property 1: Bug Condition** - Android 音乐位置精确到采样
  - **CRITICAL**: 此测试在未修复代码上必须失败——失败证明 bug 存在
  - **DO NOT** 修复测试或代码当它失败时
  - **NOTE**: 此测试编码了期望行为——实现修复后通过它即证明 bug 已修复
  - **GOAL**: 暴露反例，证明 MediaPlayer 位置不精确导致暂停-继续后偏走
  - **Scoped PBT Approach**: 对确定性 bug，把 property 缩小到具体失败场景以保证可复现
  - 测试实现细节（来自 design Bug Condition C1）：
    - 创建一个 FakeMusic 模拟 MediaPlayer 的不精确行为：seek 后 getPosition() 返回偏移的位置（如暂停在 30000ms，继续后报 29960ms 或 60065ms，每次随机不同）
    - 用 FakeMusic 构造 SongClock，调用 pause() → resume()，断言恢复后 nowMs() 精确在暂停时的位置
    - 生成多次随机暂停-继续序列，断言每次恢复后位置无偏移
  - 断言内容匹配 design Expected Behavior：位置精确到采样，暂停-继续无偏移，跳转精确
  - 在未修复代码上运行
  - **EXPECTED OUTCOME**: 测试失败（正确——证明 MediaPlayer 位置不精确的 bug 存在）
  - 记录反例（如"暂停在 30000ms，继续后 SongClock 报 29960ms，偏快 40ms"）
  - 完成标志：测试已写、已运行、失败已记录
  - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - **Property 1: Bug Condition** - 自动播放打击音包含音频偏移
  - **CRITICAL**: 此测试在未修复代码上必须失败
  - **Scoped PBT Approach**: 对确定性 bug，把 property 缩小到具体失败场景
  - 测试实现细节（来自 design Bug Condition C2/C3）：
    - C3（hitTimes 未加偏移）：构造一个含音符 judgementMs=10000 的 Chart，调用 hitTimes(chart, audioOffsetMs=50)，断言返回值包含 10050ms 而非 10000ms
    - 在未修复代码上 hitTimes(chart) 不接受 audioOffsetMs 参数，直接返回 judgementMs=10000ms → 测试失败
    - C2（继续后第一下偏晚）：构造 SongClock + FakeMusic 模拟 resumeSlew（时钟以最多 10% 速度差追赶），断言 queueAutoHits 排的第一下打击音时间和后续对齐
  - 在未修复代码上运行
  - **EXPECTED OUTCOME**: 测试失败（证明打击音未含偏移 / 继续后第一下偏晚）
  - 记录反例（如"audioOffsetMs=50，音符 judgementMs=10000，打击音排在 10000ms，比判定时刻 10050ms 早 50ms"）
  - 完成标志：测试已写、已运行、失败已记录
  - _Requirements: 2.5, 2.7_

- [ ] 2. 写保留性 property 测试（修复前）
  - **Property 2: Preservation** - 非 bug 输入行为不变
  - **IMPORTANT**: 遵循观察优先方法论
  - **IMPORTANT**: 在实现修复之前，先在未修复代码上观察以下行为，再写测试
  - 观察并写 property-based 测试捕捉以下行为模式（来自 design Preservation Requirements）：
    - **桌面播放保留**（3.1）：观察 MusicAudioSource 包装 Music 的行为——getPositionMs() = music.getPosition()*1000，setPositionMs(ms) 调用 setPosition(ms/1000)。写 property：对所有 ms 值，MusicAudioSource.setPositionMs(ms) 后 getPositionMs() 近似 ms
    - **玩家输入打击音保留**（3.2）：观察 HitMixer.play(id, volume, atNanos) 把声音排在 frameAt(atNanos)。写 property：对所有有效 atNanos，打击音排在正确的帧
    - **和弦合并保留**（3.3）：观察 HitMixer 对 MERGE_MS(20ms) 内的同 sound 请求只响一次。写 property：对所有同 sound 且 start 间隔 < 20ms 的请求对，只创建一个 voice
    - **hitTimes 去重保留**：观察 hitTimes 把同拍多音符去重到 1ms 间隔。写 property：对所有含重复音符的 Chart，hitTimes 返回值间隔 >= 1ms
    - **HitMixer 重采样保留**：观察 load() 的线性重采样保持音高。写 property：对所有 srcRate/outRate 组合，重采样后帧数 = inFrames * rate / srcRate
    - **SongClock 无音乐运行保留**：观察 SongClock 无 Music 时纯 nanoTime 运行。写 property：对所有时间点，nowMs() = anchorSongMs + (now - anchorNanos)/1e6
  - Property-based testing 自动生成大量测试用例，提供更强保证
  - 在未修复代码上运行
  - **EXPECTED OUTCOME**: 测试通过（确认要保留的基线行为）
  - 完成标志：测试已写、已运行、在未修复代码上通过
  - _Requirements: 3.1, 3.2, 3.3, 3.5, 3.6_

- [ ] 3. 修复 Android PCM 音乐播放和自动播放打击音定时

  - [ ] 3.1 搭建测试框架
    - 在 core/build.gradle 中添加 testImplementation 依赖：JUnit 5（org.junit.jupiter:junit-jupiter:5.11.4）和 jqwik（net.jqwik:jqwik:1.9.2）用于 property-based testing
    - 在 build.gradle 的 `configure(subprojects - project(':android'))` 块中确保 test 任务可用
    - 创建 core/src/test/java/org/kelvinizer/audio/ 目录结构
    - 验收：`./gradlew core:test` 能运行空的测试套件不报错
    - _Requirements: 2.1–2.8, 3.1–3.9（测试基础设施）_
    - 依赖：无

  - [ ] 3.2 实现 AudioSource 接口（design 第 1 项）
    - 新建 `core/src/main/java/org/kelvinizer/audio/AudioSource.java`
    - 定义接口方法：`float getPositionMs()`、`void play()`、`void pause()`、`void setPositionMs(float ms)`、`void setVolume(float v)`、`boolean isPlaying()`、`void dispose()`
    - SongClock 将用 AudioSource 替代直接持有 Music 对象
    - `audioMs()` 将改为 `source.getPositionMs() - audioDelayMs`（Android 上 audioDelayMs=0，因为自解码没有 MP3 解码延迟）
    - 验收：接口编译通过；写单元测试验证接口定义完整
    - _Bug_Condition: isBugCondition(input) where input.platform == ANDROID AND input.musicSource == MEDIAPLAYER_
    - _Expected_Behavior: expectedBehavior(result) — 音乐位置精确到采样 (design Property 1)_
    - _Preservation: 桌面端继续用 libGDX Music (design Preservation 3.1)_
    - _Requirements: 2.1, 2.2_
    - 依赖：3.1（测试框架）

  - [ ] 3.3 实现 MusicAudioSource（design 第 2 项）
    - 新建 `core/src/main/java/org/kelvinizer/audio/MusicAudioSource.java`
    - 实现 AudioSource，包装 libGDX Music（用于桌面和 Android <8.0 回退）
    - 所有方法委托给 Music 对象：
      - `getPositionMs()` 返回 `music.getPosition() * 1000f`
      - `setPositionMs(ms)` 调用 `music.setPosition(ms / 1000f)`
      - `play()` / `pause()` / `isPlaying()` / `setVolume()` / `dispose()` 委托给 Music
    - 验收：写单元测试（用假 Music 验证委托正确）；`./gradlew core:test` 通过
    - _Bug_Condition: 无（MusicAudioSource 用于非 bug 路径：桌面和 Android <8.0）_
    - _Preservation: 桌面端继续用 libGDX Music 播放，完全不变 (design Preservation 3.1, 3.9)_
    - _Requirements: 3.1, 3.9_
    - 依赖：3.2

  - [ ] 3.4 实现 AudioSourceFactory 接口（design 第 3 项）
    - 新建 `core/src/main/java/org/kelvinizer/audio/AudioSourceFactory.java`
    - 定义接口：`AudioSource create(FileHandle file, float durationMs)`
    - 桌面：不设工厂（null），SongClock.load() 内部创建 MusicAudioSource
    - Android 8.0+：AndroidHitOutput 实现此接口，解码 PCM 并创建 ChannelAudioSource
    - Android <8.0：工厂返回 null，回退到 MusicAudioSource
    - 验收：接口编译通过
    - _Bug_Condition: 无（工厂是平台分发机制）_
    - _Preservation: 桌面端不设工厂，走 MusicAudioSource 路径 (design Preservation 3.1)_
    - _Requirements: 2.1, 3.1, 3.9_
    - 依赖：3.2

  - [ ] 3.5 重构 HitMixer 混音逻辑（design 第 6 项）
    - 修改 `core/src/main/java/org/kelvinizer/audio/HitMixer.java`
    - 把 `frameAt(long nanos)` 从包级可见改为 `protected`（AndroidHitOutput 在不同包需要访问它来计算音乐位置）
    - 新增 `protected void mixVoices(float[] acc, int frames, long first)`：提取现有的 voice 混音逻辑（遍历 vSound/vVolume/vStart，把声音累加到 acc）
    - 新增 `protected void mixMusic(float[] acc, int frames, long first)`：空默认实现（AndroidHitOutput 重写以混入音乐）
    - 新增 `protected void clipTo(short[] out, float[] acc, int n)`：float 到 short 的 clip 转换（提取现有 mix() 末尾的 clip 循环）
    - 重构 `mix(short[] out, int frames, long first)`：cancel 处理 + addVoices → 填零 acc → `mixMusic(acc, frames, first)` → `mixVoices(acc, frames, first)` → `clipTo(out, acc, n)`
    - 保持现有行为完全不变（mix 的输入输出不变，只是内部拆分）
    - 验收：重跑 HitMixer 现有保留测试（Task 2），确认 3.5 改动后打击音混音行为不变；`./gradlew core:test` 通过
    - _Bug_Condition: 无（纯重构，不改变行为）_
    - _Expected_Behavior: 无_
    - _Preservation: 玩家输入打击音继续按事件时间戳排 (3.2)；和弦合并 20ms 窗口不变 (3.3)_
    - _Requirements: 3.2, 3.3_
    - 依赖：3.1

  - [ ] 3.6 实现 PcmDecoder（design 第 4 项）
    - 新建 `android/src/main/java/org/kelvinizer/android/PcmDecoder.java`
    - `static short[] decode(FileHandle file, int outRate)`：用 MediaExtractor 读取音频轨，MediaCodec 解码为 PCM
    - 线性重采样到 outRate（复用 HitMixer.load 的重采样逻辑思路）
    - 单声道复制为立体声（交错 short[]）
    - 返回立体声交错 short[]
    - 在后台线程调用（chart 加载线程），避免阻塞 GL 线程
    - 内存考量：3 分钟 48kHz 立体声 ≈ 35MB，可接受
    - 验收：Android 仪器测试解码一首测试 MP3/WAV，验证采样率、声道数、数据非空且非全零；无法在桌面 JVM 测试（需要 Android API），需在 Android 设备或 Robolectric 环境验证
    - _Bug_Condition: isBugCondition(input) where input.platform == ANDROID AND input.musicSource == MEDIAPLAYER_
    - _Expected_Behavior: Android 上解码歌曲为 PCM，通过共享低延迟通道播放 (design Property 1)_
    - _Preservation: 无（新增功能，不影响现有路径）_
    - _Requirements: 2.1, 2.2_
    - 依赖：3.1

  - [ ] 3.7 AndroidHitOutput 增加音乐混音并实现 AudioSourceFactory（design 第 5 项）
    - 修改 `android/src/main/java/org/kelvinizer/android/AndroidHitOutput.java`
    - 新增字段：`short[] songPcm`（解码后的歌曲 PCM）、`long songFrame`（当前播放帧）、`float songVolume`、`boolean songPlaying`、`int songFrames`、`long songStartFrame`
    - 新增方法：
      - `loadSong(short[] pcm)`：存储歌曲 PCM，设 songFrames
      - `playSong()`：设 songPlaying=true，记录 songStartFrame
      - `pauseSong()`：设 songPlaying=false
      - `seekSong(long frame)`：设 songFrame=frame
      - `songPositionMs()`：用 `frameAt(System.nanoTime())` 减去 songStartFrame，换算成 ms（精确到采样）
      - `setSongVolume(float v)`：设 songVolume
      - `songIsPlaying()`：返回 songPlaying
      - `unloadSong()`：清空 songPcm 等字段
    - 实现 AudioSourceFactory：`create(FileHandle, float)` 调用 PcmDecoder.decode() 解码 PCM，存储到 songPcm，返回 new ChannelAudioSource(this)
    - 重写 `mixMusic(float[] acc, int frames, long first)`：如果 songPlaying，从 songFrame 开始读 PCM 累加到 acc（乘以 songVolume），推进 songFrame
    - 重写 `mix(short[], int, long)` 无需重写——父类 HitMixer.mix 已调用 mixMusic 钩子
    - 线程安全：play/pause/seek/volume 从 GL 线程调用，用 synchronized 保护；songFrame 由音频线程推进
    - pause 时停止推进 songFrame（AudioTrack 继续输出，静音或打击音）
    - 验收：Android 仪器测试验证音乐 + 打击音同时混入同一缓冲区，音量正确，位置正确，clip 不爆音；需 Android 设备或 Robolectric
    - _Bug_Condition: isBugCondition(input) where input.platform == ANDROID AND input.musicSource == MEDIAPLAYER AND operation IN {PAUSE_RESUME, SEEK}_
    - _Expected_Behavior: expectedBehavior(result) — 音乐位置精确到采样，暂停-继续无偏移 (design Property 1)_
    - _Preservation: Android <8.0 继续回退到 MediaPlayer + SoundPool (3.9)；玩家输入打击音不受影响 (3.2)_
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.6, 2.8, 3.2, 3.9_
    - 依赖：3.5（HitMixer 重构提供 mixMusic 钩子）、3.6（PcmDecoder）、3.4（AudioSourceFactory 接口）

  - [ ] 3.8 实现 ChannelAudioSource（design 第 7 项）
    - 新建 `android/src/main/java/org/kelvinizer/android/ChannelAudioSource.java`
    - 实现 AudioSource，包装 AndroidHitOutput 的音乐能力：
      - `getPositionMs()`：返回 `channel.songPositionMs()`
      - `play()`：调用 `channel.playSong()`
      - `pause()`：调用 `channel.pauseSong()`
      - `setPositionMs(ms)`：调用 `channel.seekSong((long)(ms * rate / 1000))`
      - `setVolume(v)`：调用 `channel.setSongVolume(v)`
      - `isPlaying()`：返回 `channel.songIsPlaying()`
      - `dispose()`：调用 `channel.unloadSong()`
    - 验收：Android 仪器测试验证 ChannelAudioSource 委托调用正确；需 Android 设备或 Robolectric
    - _Bug_Condition: 无（ChannelAudioSource 是修复后的正确路径）_
    - _Expected_Behavior: Android 上音乐位置精确到采样 (design Property 1)_
    - _Preservation: 无（新增）_
    - _Requirements: 2.2, 2.3_
    - 依赖：3.2（AudioSource 接口）、3.7（AndroidHitOutput 音乐方法）

  - [ ] 3.9 改造 SongClock（design 第 8 项）
    - 修改 `core/src/main/java/org/kelvinizer/audio/SongClock.java`
    - 将 `private Music music` 字段替换为 `private AudioSource source`；`private boolean ownsMusic` 改为 `private boolean ownsSource`
    - 新增 `load(FileHandle file, float knownDurationMs, AudioSourceFactory factory)` 重载：如果 factory != null 用 factory.create() 创建 AudioSource，否则创建 MusicAudioSource（内部 newMusic + 包装）
    - 保留现有 `load(FileHandle, float)` 和 `load(FileHandle)` 重载：内部调用 `load(file, dur, null)`（无工厂 = MusicAudioSource，用于桌面和校准）
    - `attach(Music m, ...)` 改为内部创建 MusicAudioSource 包装；或新增 `attachSource(AudioSource, float, boolean)`
    - `audioMs()` 改为 `source.getPositionMs() - audioDelayMs`
    - 所有 `music.X()` 调用改为 `source.X()`（play/pause/setPosition/isPlaying/setVolume/dispose）
    - `music()` 访问器：保留返回 null 或改为返回底层（测试可能用到）；或新增 `source()` 访问器
    - 简化 Android 特有逻辑（自解码后位置精确，不再需要为 MediaPlayer 打补丁）：
      - `load()` 中 `seekableCopy()`：不再需要（自解码按帧跳转精确）——仅在 factory==null 时保留（Android <8.0 回退仍用 Music）
      - `reseekOnResume()`：当 source 是 ChannelAudioSource 时返回 false（位置精确，不需要 reseek）；当 source 是 MusicAudioSource 时保留原逻辑
      - `startCountIn()` 的 Android 分支：ChannelAudioSource 位置精确，不需要 muted run-in 对齐；保留淡入避免突然出声
      - `resumeSlew`：ChannelAudioSource 位置精确，不需要缓慢追赶——简化或去掉
      - `startLeadMs/preroll`：保留（AudioTrack 也有 buffer 填充延迟，但比 MediaPlayer 小且恒定）
      - `primeStream()`：仅在 MusicAudioSource + MP3 桌面时需要
    - 关键约束：改动后桌面端（MusicAudioSource）行为必须完全不变；只有 Android 8.0+（ChannelAudioSource）走简化路径
    - 验收：写单元测试用 FakeAudioSource（精确位置）测试暂停/继续/跳转后位置精确；用 FakeMusic（不精确位置）验证桌面路径保留原有行为；`./gradlew core:test` 通过
    - _Bug_Condition: isBugCondition(input) where input.platform == ANDROID AND input.musicSource == MEDIAPLAYER AND operation IN {PAUSE_RESUME, SEEK, AUTOPLAY_AFTER_RESUME}_
    - _Expected_Behavior: expectedBehavior(result) — 位置精确到采样，暂停-继续无偏移，跳转精确 (design Property 1)_
    - _Preservation: 桌面端继续用 libGDX Music，完全不变 (3.1)；Android <8.0 回退保留 (3.9)；判定逻辑不变 (3.6)；倒计时和淡入保留 (3.7)_
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.6, 2.8, 3.1, 3.5, 3.6, 3.7, 3.9_
    - 依赖：3.2（AudioSource）、3.3（MusicAudioSource）、3.4（AudioSourceFactory）

  - [ ] 3.10 hitTimes() 加音频偏移（design 第 9 项）
    - 修改 `core/src/main/java/org/kelvinizer/gameplay/GameplayScreen.java`
    - `hitTimes(Chart chart)` 改为 `hitTimes(Chart chart, float audioOffsetMs)`
    - 返回值从 `note.judgementMs()` 改为 `note.judgementMs() + audioOffsetMs`
    - `begin()` 中调用改为 `hitTimes(chart, engine.audioOffsetMs())`
    - 注意：判定发生在 `clock.timeAtNanos(event) - audioOffsetMs >= note.judgementMs()`，即歌曲时间 `note.judgementMs() + audioOffsetMs`。打击音应排在同一时刻
    - 保持 1ms 去重逻辑不变（只改变基准值，不改变去重阈值）
    - 验收：写单元测试验证 hitTimes(chart, 50) 对 judgementMs=10000 的音符返回 10050；hitTimes(chart, 0) 行为和原来一致；`./gradlew core:test` 通过
    - _Bug_Condition: isBugCondition(input) where input.autoplay == true AND input.timedHits == true AND input.hitTimesIncludeAudioOffset == false_
    - _Expected_Behavior: expectedBehavior(result) — 打击音排在 judgementMs + audioOffsetMs (design Property 2)_
    - _Preservation: 玩家输入打击音不受影响 (3.2)；和弦合并不变 (3.3)_
    - _Requirements: 2.5_
    - 依赖：3.1

  - [ ] 3.11 管线连接（design 第 10 项）
    - 修改 `core/src/main/java/org/kelvinizer/audio/AudioService.java`：
      - 新增 `private AudioSourceFactory audioSourceFactory` 字段
      - 新增 `public void setAudioSourceFactory(AudioSourceFactory f)` 和 `public AudioSourceFactory audioSourceFactory()`
    - 修改 `core/src/main/java/org/kelvinizer/Main.java`：
      - `create()` 中 `audio.setHitOutput(hitOutput)` 后，如果 hitOutput 实现了 AudioSourceFactory，调用 `audio.setAudioSourceFactory((AudioSourceFactory) hitOutput)`
      - 注意：AndroidHitOutput 现在同时 implements HitOutput（通过继承 HitMixer）和 AudioSourceFactory
    - 修改 `core/src/main/java/org/kelvinizer/gameplay/GameplayScreen.java`：
      - `begin()` 中 `engine.clock().load(music, dur)` 改为 `engine.clock().load(music, dur, game.audio().audioSourceFactory())`
      - `engine.clock().load(music)` 改为 `engine.clock().load(music, 0f, game.audio().audioSourceFactory())`
    - AndroidLauncher 不变（已创建 AndroidHitOutput 并传给 Main）
    - AudioService.playFile()（校准）不传 factory → SongClock.load(file) 走 MusicAudioSource 路径，校准音乐不受影响
    - 验收：桌面端完整游玩流程验证（`./gradlew lwjgl3:run` 手动测试）；Android 端需设备测试验证音乐通过低延迟通道播放；编译通过
    - _Bug_Condition: isBugCondition(input) where input.platform == ANDROID AND input.musicSource == MEDIAPLAYER_
    - _Expected_Behavior: expectedBehavior(result) — Android 上通过 AudioSourceFactory 创建 ChannelAudioSource，音乐走共享低延迟通道 (design Property 1)_
    - _Preservation: 校准音乐继续用 MusicAudioSource (3.4)；桌面端不设 factory 走 MusicAudioSource (3.1)_
    - _Requirements: 2.1, 2.2, 2.8, 3.1, 3.4, 3.9_
    - 依赖：3.4（AudioSourceFactory）、3.7（AndroidHitOutput 实现工厂）、3.8（ChannelAudioSource）、3.9（SongClock.load 新签名）、3.10

  - [ ] 3.12 验证 bug 条件探索测试通过
    - **Property 1: Expected Behavior** - Android 音乐位置精确到采样 + 打击音含音频偏移
    - **IMPORTANT**: 重跑 Task 1 的同一批测试——不要写新测试
    - Task 1 的两个 Property 1 测试编码了期望行为
    - 当这些测试通过时，确认期望行为已满足
    - 运行 bug 条件探索测试（Task 1）
    - **EXPECTED OUTCOME**: 测试通过（确认 bug 已修复）
    - 如果测试仍失败，检查根因（可能 3.7–3.11 某项实现有误）
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_

  - [ ] 3.13 验证保留性测试仍通过
    - **Property 2: Preservation** - 非 bug 输入行为不变
    - **IMPORTANT**: 重跑 Task 2 的同一批测试——不要写新测试
    - 运行保留性 property 测试（Task 2）
    - **EXPECTED OUTCOME**: 测试通过（确认无回归）
    - 确认所有测试仍通过（无回归）
    - 如果测试失败，检查 3.5（HitMixer 重构）或 3.9（SongClock 改造）是否改变了非 bug 路径的行为
    - _Requirements: 3.1, 3.2, 3.3, 3.5, 3.6_

- [ ] 4. 检查点——确保所有测试通过
  - 运行 `./gradlew core:test` 确保所有核心模块测试通过
  - 运行 `./gradlew build` 确保整体编译通过（含 android 模块）
  - 桌面端手动验证（`./gradlew lwjgl3:run`）：播放一首歌，暂停-继续，确认音乐位置准确
  - Android 端手动验证（需设备）：播放一首歌，暂停-继续多次，确认每次恢复后音乐位置精确；自动播放打击音与判定对齐
  - 确认 Android <8.0 回退仍正常（不崩溃）
  - 确认校准音频不受影响（走 MusicAudioSource）
  - 如有问题，向用户提问

## Notes

- **测试框架**：项目目前没有测试框架，需先在 core/build.gradle 添加 JUnit 5 和 jqwik（property-based testing 库）。Android 模块的测试需要 Robolectric 或真机仪器测试，因为 PcmDecoder 和 AndroidHitOutput 依赖 Android API（MediaExtractor、MediaCodec、AudioTrack）。
- **Android 测试策略**：核心逻辑（AudioSource、MusicAudioSource、HitMixer、SongClock、hitTimes）可用纯 Java 单元测试。PcmDecoder、AndroidHitOutput 音乐混音、ChannelAudioSource 需要 Android 运行时，建议用 Robolectric 或在 Android 设备上做仪器测试。
- **桌面端无回归**：SongClock 改造后，桌面端走 MusicAudioSource 路径（factory=null），所有 Android 特有的补丁逻辑（reseekOnResume、resumeSlew、seekableCopy、countIn run-in）仅在 MusicAudioSource 时保留原有行为，ChannelAudioSource 时走简化路径。
- **校准音频保留**：AudioService.playFile() 用于校准，不传 AudioSourceFactory，SongClock.load() 走 MusicAudioSource 路径，校准音乐完全不受影响。
- **Android <8.0 回退**：AndroidHitOutput.create() 返回 null → Main 不设 AudioSourceFactory → SongClock.load() 走 MusicAudioSource → 继续用 MediaPlayer 放歌，行为完全不变。
