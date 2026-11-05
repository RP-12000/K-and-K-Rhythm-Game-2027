# Bugfix Requirements Document

## Introduction

游玩节奏游戏时，音乐会在暂停后继续时偏走：每次暂停-继续后，音乐接上的位置都和暂停前对不上，偏多少每次不一样（快约 40 毫秒，或慢 65 到 100 毫秒）。根因是安卓自带的播放器（MediaPlayer）跳到某个位置后，它报给游戏的位置和实际放出来的声音对不上，而且每次差得不一样；游戏只能相信它报的位置，所以每次暂停后都偏得不同。此外，继续后的第一下自动播放打击音也比后面几下晚了 50 到 90 毫秒；自动播放的打击音也没有算上用户设的音频偏移（audio offset），和判定对不上。

修复方案是在安卓上不再用 MediaPlayer 放歌，而是先把歌解码成 PCM 再自己按时刻放，方法和打击音一样——通过现有的低延迟通道（AudioTrack），让音乐和打击音共用同一条通道、同一个时钟。这样音乐放到哪一刻游戏都确切知道（精确到采样），暂停、继续、跳转都能对到采样。同时把自动播放的打击音改成按谱面时间排（算上音频偏移），暂停时还没响的全部取消，继续后从暂停的地方重新排。

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN the game pauses and resumes on Android THEN the music's reported playback position (from MediaPlayer) does not match the actual audio output, causing the song clock to drift by a varying amount each time (one time about 40 ms early, four times 65–100 ms late)

1.2 WHEN the game resumes after a pause on Android THEN the first autoplay hit sound after resume plays 50–90 ms late compared to the subsequent hits in the same segment

1.3 WHEN autoplay hit sounds are scheduled THEN they are placed at the chart judgement time directly (note judgement beat converted to ms) without the user's audio offset, so they do not align with when the note is actually judged (judging uses clock time minus audio offset)

1.4 WHEN the game needs the music's playback position on Android THEN it must trust MediaPlayer's getPosition() which is inaccurate after a seek, so pause, resume and seek cannot be sample-accurate

### Expected Behavior (Correct)

2.1 WHEN the game plays music on Android THEN the system SHALL decode the song to PCM and play it through the same low-latency audio channel (AudioTrack) used by hit sounds, not through MediaPlayer

2.2 WHEN the game needs the music's playback position on Android THEN the system SHALL know it exactly (sample-accurate) from the shared low-latency channel's frame count and timestamp

2.3 WHEN the game pauses and resumes on Android THEN the music SHALL resume at the exact sample where it was paused, with no drift (the clock reads the channel's exact position, not MediaPlayer's report)

2.4 WHEN the game seeks on Android (retry, jump) THEN the music SHALL seek to the exact sample

2.5 WHEN autoplay hit sounds are scheduled THEN they SHALL be placed at the chart judgement time adjusted for the user's audio offset (judgement ms plus audio offset ms), so they align with when the note is judged

2.6 WHEN the game pauses THEN all pending (not yet started) hit sounds and music that have not begun playing SHALL be cancelled

2.7 WHEN the game resumes after a pause THEN pending autoplay hit sounds SHALL be re-scheduled from the resume point

2.8 WHEN music and hit sounds play on Android THEN they SHALL share the same low-latency channel and the same clock

### Unchanged Behavior (Regression Prevention)

3.1 WHEN the game plays on desktop THEN it SHALL CONTINUE TO use the existing libGDX Music (OpenAL) playback for songs without changes

3.2 WHEN the game plays hit sounds for player input (not autoplay) THEN it SHALL CONTINUE TO schedule each hit at the input event timestamp through the low-latency channel

3.3 WHEN a chord (multiple notes at the same beat) is played THEN the hit sounds SHALL CONTINUE TO merge into a single sound for hits of the same type within the merge window (20 ms)

3.4 WHEN the game plays menu music, song previews, result music or calibration audio THEN those SHALL CONTINUE TO use the existing AudioService with libGDX Music, unaffected by the gameplay music change

3.5 WHEN the game starts a song THEN the lead-in, start countdown and music start timing SHALL CONTINUE TO work smoothly

3.6 WHEN the game records input for judging THEN the judging logic (event timestamp to song time via the clock, audio offset applied to judge time, visual offset for drawing) SHALL CONTINUE TO work as before

3.7 WHEN the game resumes after a pause with the 3-2-1 countdown THEN the countdown and chart fade-in SHALL CONTINUE TO work

3.8 WHEN no hit sound file is installed THEN hit sounds SHALL CONTINUE TO be silent without crashing

3.9 WHEN the game runs on Android below 8.0 (no low-latency channel) THEN it SHALL CONTINUE TO fall back to the previous audio playback method for both music and hit sounds
