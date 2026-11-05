#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json
import math

# 读取 JSON 文件
chart_path = r"c:\Users\kelvi\IdeaProjects\K and K Rhythm Game\assets\Charts\𝕄𝕖𝕞𝕠𝕣𝕚𝕒 ℙ𝕝ū𝕧𝕚æ short ver\Deep.json"

print("正在读取谱面文件...")
with open(chart_path, 'r', encoding='utf-8') as f:
    chart = json.load(f)

print(f"已读取 {len(chart['notes'])} 个笔记")

# 1. 修改 beat 73-77 的旋转事件为匀速转动
# 每8拍转一圈 = 360度/8拍 = 45度/拍
# 从 beat 73 到 beat 77 的 rotationEvents 应该每 8 拍转 360 度
print("\n修改旋转事件为匀速转动...")

def create_linear_rotation_events(start_beat, end_beat, start_angle=90):
    """创建线性旋转事件，每8拍转一圈"""
    events = []
    # 每拍转 45 度
    rotation_per_beat = 45.0
    
    # 从 start_beat 到 end_beat，每 0.5 拍添加一个关键帧
    current_beat = start_beat
    step = 0.06  # 使用相同的时间步长作为原始数据
    
    while current_beat < end_beat:
        next_beat = min(current_beat + step, end_beat)
        current_angle = start_angle + (current_beat - start_beat) * rotation_per_beat
        next_angle = start_angle + (next_beat - start_beat) * rotation_per_beat
        
        # 确保角度循环在 0-360
        current_angle = current_angle % 360
        next_angle = next_angle % 360
        
        event = {
            "beats": [current_beat, next_beat],
            "values": [current_angle, next_angle]
        }
        events.append(event)
        
        current_beat = next_beat
    
    return events

# 找到 beat 73-77 的音符，修改其旋转事件
found_notes = []
for i, note in enumerate(chart['notes']):
    beat = note.get('beat', 0)
    if beat >= 73 and beat <= 85:
        if 'rotationEvents' in note:
            print(f"笔记 {i} (beat {beat}) 有旋转事件")
            found_notes.append(i)

# 对于所有在 beat 73-77 范围内且有 rotationEvents 的笔记，更新旋转事件
print(f"\n找到 {len(found_notes)} 个在 beat 73-77 范围内的音符")

# 重新创建匀速旋转事件（从 73 到 77 的 4 拍内）
linear_rotation_events = create_linear_rotation_events(73, 77)

for idx in found_notes:
    note = chart['notes'][idx]
    beat = note['beat']
    
    # 只有在 beat 在 73-77 范围内时才修改
    if beat >= 73 and beat < 77:
        note['rotationEvents'] = linear_rotation_events
        print(f"  笔记 {idx} (beat {beat}) 的旋转事件已更新为匀速")

# 2. 修正 beat 85 的音符的横移坐标
# 这个音符同时有 positionEvents 和 rotationEvents
# 需要找到并修正坐标

print("\n寻找需要修正坐标的笔记...")
for i, note in enumerate(chart['notes']):
    beat = note.get('beat', 0)
    if beat == 85 and 'positionEvents' in note:
        print(f"笔记 {i} (beat {beat}) 有坐标事件")
        
        # 假设横移坐标的最后一个事件是 beat 83-85，需要从 1080 改为 0
        pos_events = note['positionEvents']
        
        # 检查最后一个相对位置事件
        for j, event in enumerate(pos_events):
            if event.get('beats', [None, None])[0] == 83:
                print(f"  找到 beat 83-85 的相对横移事件")
                # 原始：从 1080 移动到 0
                # 现在应该保持这个逻辑，但确保坐标正确
                if event.get('rel', True):
                    print(f"    这是相对事件，初值 {event['values'][0]}, 终值 {event['values'][1]}")

print("\n处理前面蓝键螺旋的坐标...")
# 需要找到所有 lane -1 的音符在 beat 73-85 范围内的
for i, note in enumerate(chart['notes']):
    beat = note.get('beat', 0)
    lane = note.get('lane', 0)
    
    if lane == -1 and beat >= 73 and beat <= 85:
        if 'positionEvents' in note:
            print(f"笔记 {i} (lane {lane}, beat {beat}) 有位置事件")
            pos_events = note['positionEvents']
            
            # 检查事件中是否有绝对位置（rel: false）
            for j, event in enumerate(pos_events):
                if not event.get('rel', True):
                    # 这是绝对位置
                    start_beat = event['beats'][0]
                    if start_beat >= 73 and start_beat <= 77:
                        print(f"    在 beat {start_beat}-{event['beats'][1]} 有绝对位置事件")
                        # 检查是否需要调整
                        # 当前实现：暂时不修改，等待用户提供具体偏移值

# 3. 保存修改
print("\n保存修改的谱面...")
with open(chart_path, 'w', encoding='utf-8') as f:
    json.dump(chart, f, ensure_ascii=False, indent=1)

print("完成！")
