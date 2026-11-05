#!/usr/bin/env python3
"""深度六边形 16 档的图片生成器：每档一张 PNG（六边形、翅膀、头像两边的尖刺、头像上的珍珠），数字由游戏自己写。
Renders the 16 depth-tier crests into assets/ui/depth/: tierNN.png plus tiers.json.

Each PNG holds two layers on one canvas. Rows below ``split`` are the back layer (the hexagon with its fins,
wings, glows, gems, pips and the spikes beside the avatar), drawn under the avatar; rows above it are the top
layer (the pearls or the diamond on the avatar ring), drawn over the avatar. Colours are premultiplied by
alpha so the game can blend with GL_ONE / GL_ONE_MINUS_SRC_ALPHA without dark fringes. Sizes are in world
units at uiScale 1 times K. tiers.json gives the canvas, the hexagon rectangle, the avatar centre and radius,
the split row and, per tier, how far the solid art reaches past the hexagon's left and right tips.

Run from the repository root:  python3 tools/depth-tiers/make_tiers.py   (needs Pillow)
"""
import json
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

S = 4                 # drawing scale: the hexagon is drawn 54·S px tall, then scaled to its final size
K = 2                 # output pixels per world unit at uiScale 1
TW = 270              # hexagon width in drawing units (54 tall)
HEX_W, HEX_H = 220, 44  # hexagon size in world units
R = 54                # avatar radius in world units
LIFT = 8              # the avatar overlaps the hexagon top by this much
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'assets', 'ui', 'depth')

def C(h, a=255): return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def hexpts(x, y, w, h, p): return [(x, y + h / 2), (x + p, y), (x + w - p, y), (x + w, y + h / 2), (x + w - p, y + h), (x + p, y + h)]


def grad(w, h, top, bot):
    g = Image.new('RGBA', (w, h)); d = ImageDraw.Draw(g)
    for i in range(h):
        t = i / max(1, h - 1)
        d.line([(0, i), (w, i)], fill=tuple(int(top[k] + (bot[k] - top[k]) * t) for k in range(3)) + (255,))
    return g


def glow(base, fn, r):
    lay = Image.new('RGBA', base.size, (0, 0, 0, 0)); fn(ImageDraw.Draw(lay))
    base.alpha_composite(lay.filter(ImageFilter.GaussianBlur(r)))


def fade(t, a, e=0.15): return int(a * max(0.0, min(1.0, t / e, (1 - t) / e)))


def streak(dd, w, y, col, a):
    n = 60
    for i in range(n):
        x0, x1 = w * i / n, w * (i + 1) / n
        dd.line([(x0, y), (x1, y)], fill=col[:3] + (fade(i / n, a, 0.4),), width=S)


def wave(dd, w, cy, amp, per, ph, col, a, wd=1):
    pts = [(x, cy + amp * math.sin(2 * math.pi * x / per + ph)) for x in range(0, int(w) + 1, 3)]
    for i in range(len(pts) - 1):
        dd.line([pts[i], pts[i + 1]], fill=col[:3] + (fade(i / len(pts), a),), width=wd * S)


def bubbles(dd, pts, col, a):
    for x, y, r in pts:
        dd.ellipse((x - r, y - r, x + r, y + r), outline=col[:3] + (a,), width=S)


def slashes(dd, x0, n, gap, bw, sk, h, col, a, d=1):
    for i in range(n):
        x = x0 + d * i * gap
        dd.polygon([(x, 0), (x + bw, 0), (x + bw - sk, h), (x - sk, h)], fill=col[:3] + (int(a * (1 - i / (n + 1))),))


def sonar(dd, cx, cy, radii, col, a, span=55):
    for k, r in enumerate(radii):
        dd.arc((cx - r, cy - r, cx + r, cy + r), -span, span, fill=col[:3] + (int(a * (1 - k / (len(radii) + 1))),), width=S)


def sonar_l(dd, cx, cy, radii, col, a, span=55):
    for k, r in enumerate(radii):
        dd.arc((cx - r, cy - r, cx + r, cy + r), 180 - span, 180 + span, fill=col[:3] + (int(a * (1 - k / (len(radii) + 1))),), width=S)


def honeycomb(dd, w, h, s, col, a):
    for row in range(-1, int(h / (s * 1.5)) + 2):
        for c in range(-1, int(w / (s * 1.732)) + 2):
            cx = c * s * 1.732 + (row % 2) * s * 0.866
            cy = row * s * 1.5
            pts = [(cx + s * math.cos(math.radians(60 * k + 30)), cy + s * math.sin(math.radians(60 * k + 30))) for k in range(6)]
            dd.polygon(pts, outline=col[:3] + (fade(cx / w, a, 0.3),))


def rays(dd, cx, cy, n, R, col, a, half=2.2):
    for i in range(n):
        th = 2 * math.pi * i / n
        dlt = math.radians(half)
        dd.polygon([(cx, cy), (cx + R * math.cos(th - dlt), cy + R * math.sin(th - dlt)),
                    (cx + R * math.cos(th + dlt), cy + R * math.sin(th + dlt))], fill=col[:3] + (a,))


def filigree(dd, w, h, p, col, a):
    for y in (6 * S, h - 6 * S):
        dd.line([(p + 4 * S, y), (w - p - 4 * S, y)], fill=col[:3] + (a,), width=S)
        for i in range(1, 8):
            x = p + 4 * S + (w - 2 * p - 8 * S) * i / 8
            r = 3 * S if i % 2 else 2 * S
            dd.polygon([(x - r, y), (x, y - r), (x + r, y), (x, y + r)], fill=col[:3] + (a,))
    for side in (0, 1):  # chevron ticks along the slanted ends
        bx = p * 0.55 if side == 0 else w - p * 0.55
        for k in range(3):
            o = (k - 1) * 9 * S
            sx = -1 if side == 0 else 1
            dd.line([(bx - sx * 4 * S + o * 0, h / 2 - 8 * S + o), (bx + sx * 3 * S, h / 2 + o)], fill=col[:3] + (a,), width=S)


def cracks(dd, w, h, col, a, seed):
    rnd = random.Random(seed)
    for _ in range(6):
        x, y = rnd.uniform(0.05, 0.95) * w, rnd.uniform(0.2, 0.8) * h
        pts = [(x, y)]
        ang = rnd.uniform(0, 2 * math.pi)
        for _ in range(rnd.randint(4, 7)):
            ang += rnd.uniform(-0.9, 0.9)
            x += math.cos(ang) * 9 * S; y += math.sin(ang) * 5 * S
            pts.append((x, y))
        dd.line(pts, fill=col[:3] + (a,), width=S)


def sparkles(dd, pts, col, a):
    for x, y, r in pts:
        dd.polygon([(x - r, y), (x - r * 0.18, y - r * 0.18), (x, y - r), (x + r * 0.18, y - r * 0.18), (x + r, y),
                    (x + r * 0.18, y + r * 0.18), (x, y + r), (x - r * 0.18, y + r * 0.18)], fill=col[:3] + (a,))


def wing(d, tipx, cy, side, n, col, size=1.0):
    for k in range(n):
        o = (k + 1) * 9 * S * size
        x0 = tipx + side * (6 * S + o)
        hh = (14 - k * 2.5) * S * size
        d.polygon([(x0, cy - hh), (x0 + side * 10 * S * size, cy), (x0, cy + hh), (x0 + side * 4 * S * size, cy)],
                  fill=col[k] if isinstance(col, list) else col)


def gem(d, cx, cy, r, col, edge):
    d.polygon([(cx - r, cy), (cx, cy - r), (cx + r, cy), (cx, cy + r)], fill=col, outline=edge, width=2 * S)
    d.polygon([(cx - r * 0.45, cy - r * 0.1), (cx, cy - r * 0.55), (cx + r * 0.2, cy - r * 0.35)], fill=(255, 255, 255, 200))


def pips(d, cx, y, n, col, edge=(255, 255, 255, 230)):
    for i in range(n):
        x = cx + (i - (n - 1) / 2) * 16 * S
        r = 5 * S
        d.polygon([(x - r, y), (x, y - r), (x + r, y), (x, y + r)], fill=col, outline=edge)


RAINBOW = [(79, 216, 255), (180, 156, 255), (255, 111, 216), (255, 210, 122), (140, 255, 217), (79, 216, 255)]


GLOWLINE = [(79, 216, 255), (180, 156, 255), (255, 111, 216), (180, 156, 255), (79, 216, 255)]


def hgrad(w, h, stops):
    g = Image.new('RGBA', (w, h)); d = ImageDraw.Draw(g)
    n = len(stops) - 1
    for x in range(w):
        t = x / max(1, w - 1) * n; i = min(int(t), n - 1); f = t - i; a, b = stops[i], stops[i + 1]
        d.line([(x, 0), (x, h)], fill=tuple(int(a[k] + (b[k] - a[k]) * f) for k in range(3)) + (255,))
    return g


def paint(img, x0, y0, w, h, stops, maskfn, blur=0, gain=1.0):
    # push a left-to-right colour gradient through a mask drawn in local coordinates; (x0, y0) is the sheet offset
    m = Image.new('L', (int(w), int(h)), 0); maskfn(ImageDraw.Draw(m))
    if blur: m = m.filter(ImageFilter.GaussianBlur(blur))
    if gain != 1.0: m = m.point(lambda v: min(255, int(v * gain)))
    g = hgrad(int(w), int(h), stops); g.putalpha(m)
    img.alpha_composite(g, (int(x0), int(y0)))


def dots(dd, w, h, n, cols, seed, rmax=1.5):
    rnd = random.Random(seed)
    for i in range(n):
        x, y, r = rnd.uniform(0, w), rnd.uniform(0.08, 0.92) * h, rnd.uniform(0.5, rmax) * S
        dd.ellipse((x - r, y - r, x + r, y + r), fill=cols[i % len(cols)][:3] + (rnd.randint(110, 230),))


def ribbon(dd, w, cy, amp, per, ph, thick, col, a):
    # a filled band around one sine, for the blurred layer
    top = [(x, cy + amp * math.sin(2 * math.pi * x / per + ph) - thick) for x in range(0, int(w) + 1, 4)]
    dd.polygon(top + [(x, y + 2 * thick) for x, y in reversed(top)], fill=col[:3] + (a,))


def pearl(img, cx, cy, r):
    glow(img, lambda dd: dd.ellipse((cx - r * 1.8, cy - r * 1.8, cx + r * 1.8, cy + r * 1.8), fill=(255, 236, 250, 170)), r)
    d = ImageDraw.Draw(img)
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(205, 196, 236, 255), outline=(255, 210, 122, 255), width=S)
    d.ellipse((cx - r * 0.85, cy - r * 0.9, cx + r * 0.6, cy + r * 0.55), fill=(240, 238, 255, 255))
    d.ellipse((cx - r * 0.55, cy - r * 0.6, cx - r * 0.1, cy - r * 0.2), fill=(255, 255, 255, 255))


def feathers(d, tipx, cy, side, cols):
    # five full feathers for the rainbow tier; three short white and lavender ones for 心海 (three colours)
    small = len(cols) == 3
    n = 3 if small else 5
    for i in reversed(range(n)):
        if small:
            th, ln, wd = math.radians(-50 + 30 * i), (34 - 6 * i) * S, 4 * S
        else:
            th, ln, wd = math.radians(-58 + 80 * i / (n - 1)), (58 - 6 * i) * S, 5 * S
        bx, by = tipx + side * 3 * S, cy
        dx, dy = side * math.cos(th), math.sin(th)
        ex, ey = bx + dx * ln, by + dy * ln
        mx, my = bx + dx * ln * 0.55, by + dy * ln * 0.55
        px, py = -dy * wd, dx * wd
        d.polygon([(bx, by), (mx + px, my + py), (ex, ey), (mx - px, my - py)], fill=cols[i % len(cols)])
        d.line([(bx, by), (ex, ey)], fill=(255, 255, 255, 200), width=S)


# ---------------------------------------------------------------- tiers


def base_tiers():
    T = []
    T.append(dict(rng='0 – 1,999 m', num='-1,520 m', fill=((14, 22, 52), (8, 14, 38)), edge='4FD8FF', ew=2,
                  deco=lambda dd, w, h, p: (streak(dd, w, h * 0.30, C('4FD8FF'), 110),
                                            bubbles(dd, [(w - p - 14 * S, h * 0.62, 4 * S), (w - p - 26 * S, h * 0.42, 2.5 * S), (w - p - 6 * S, h * 0.35, 2 * S)], C('9FE8FF'), 150))))
    T.append(dict(rng='2,000 – 3,999 m', num='-2,860 m', fill=((16, 34, 74), (8, 18, 46)), edge='4FD8FF', ew=2, inner='4FD8FF',
                  deco=lambda dd, w, h, p: (streak(dd, w, h * 0.28, C('4FD8FF'), 120), wave(dd, w, h * 0.72, 3 * S, 70 * S, 0.0, C('4FD8FF'), 110),
                                            bubbles(dd, [(p + 12 * S, h * 0.40, 3 * S), (p + 22 * S, h * 0.60, 2 * S), (w - p - 14 * S, h * 0.40, 3.5 * S), (w - p - 24 * S, h * 0.62, 2 * S)], C('9FE8FF'), 160))))
    T.append(dict(rng='4,000 – 5,999 m', num='-4,730 m', fill=((18, 60, 110), (10, 26, 64)), edge='8CFFD9', ew=3, inner='8CFFD9', fins=1,
                  deco=lambda dd, w, h, p: (wave(dd, w, h * 0.5, 9 * S, 120 * S, 0.0, C('8CFFD9'), 120), wave(dd, w, h * 0.5, -9 * S, 120 * S, 0.0, C('8CFFD9'), 120),
                                            slashes(dd, p + 4 * S, 4, 11 * S, 4 * S, 10 * S, h, C('8CFFD9'), 70), slashes(dd, w - p - 8 * S, 4, 11 * S, 4 * S, 10 * S, h, C('8CFFD9'), 70, -1))))
    T.append(dict(rng='6,000 – 6,999 m', num='-6,410 m', fill=((30, 80, 170), (12, 30, 90)), edge='E6F5FF', ew=3, inner='78D2FF', fins=2, notch=True,
                  deco=lambda dd, w, h, p: (sonar(dd, p * 0.2, h / 2, [16 * S, 28 * S, 40 * S, 52 * S], C('FFFFFF'), 120),
                                            sonar_l(dd, w - p * 0.2, h / 2, [16 * S, 28 * S, 40 * S, 52 * S], C('FFFFFF'), 120),
                                            streak(dd, w, h * 0.25, C('FFFFFF'), 120))))
    T.append(dict(rng='7,000 – 7,999 m', num='-7,390 m', fill=((90, 60, 170), (30, 16, 80)), edge='C8B4FF', ew=3, inner='B49CFF', fins=2, gem='B49CFF',
                  deco=lambda dd, w, h, p: (honeycomb(dd, w, h, 8 * S, C('E6DCFF'), 70), rays(dd, w / 2, h / 2, 24, w * 0.6, C('E6DCFF'), 26),
                                            streak(dd, w, h * 0.24, C('FFFFFF'), 120))))
    T.append(dict(rng='8,000 – 8,999 m', num='-8,620 m', fill=((120, 20, 70), (40, 6, 34)), edge='FFD27A', ew=3, inner='FF2E88', fins=3, gem='FF2E88', crown=3,
                  deco=lambda dd, w, h, p: (rays(dd, w / 2, h / 2, 28, w * 0.6, C('FF6FB0'), 34), filigree(dd, w, h, p, C('FFD27A'), 170),
                                            slashes(dd, p + 10 * S, 3, 10 * S, 3 * S, 9 * S, h, C('FFD27A'), 70), slashes(dd, w - p - 13 * S, 3, 10 * S, 3 * S, 9 * S, h, C('FFD27A'), 70, -1))))
    return T


FAM = [
    # 深渊 exactly as approved in round 2 (crack seeds 11-14 kept so the four tags look the same)
    dict(name='深渊', fill=((30, 8, 26), (6, 2, 10)), edge='FFD27A', ew=4, inner='FF3A50', fins=4, wingcol='FFD27A', gem='FF3A50', crown=5, red=True,
         deco=lambda dd, w, h, p, k: (cracks(dd, w, h, C('FF3A50'), 220, 11 + k), sonar(dd, w / 2, h / 2, [26 * S, 40 * S, 54 * S], C('FF6070'), 110, 40),
                                      sonar_l(dd, w / 2, h / 2, [26 * S, 40 * S, 54 * S], C('FF6070'), 110, 40), filigree(dd, w, h, p, C('FFD27A'), 160))),
    dict(name='幽光', fill=((8, 40, 62), (4, 10, 28)), edge='E8F6FF', ew=4, inner='7CF5FF', fins=5,
         wingcol=['7CF5FF', '9FD0FF', 'B49CFF', 'E08BFF', 'FF8BE0'], crowncol='E8F6FF', gem='7CF5FF', crown=5, orb='7CF5FF',
         prism=GLOWLINE, pip='BFF6FF',
         soft=lambda dd, w, h, p, k: (ribbon(dd, w, h * 0.40, 7 * S, 150 * S, 0.0, 4 * S, C('4FD8FF'), 160),
                                      ribbon(dd, w, h * 0.55, 6 * S, 120 * S, 1.8, 3 * S, C('B49CFF'), 150),
                                      ribbon(dd, w, h * 0.68, 5 * S, 100 * S, 3.4, 3 * S, C('FF6FD8'), 130)),
         deco=lambda dd, w, h, p, k: (wave(dd, w, h * 0.40, 7 * S, 150 * S, 0.0, C('BFF6FF'), 170), wave(dd, w, h * 0.55, 6 * S, 120 * S, 1.8, C('E6DCFF'), 150),
                                      wave(dd, w, h * 0.68, 5 * S, 100 * S, 3.4, C('FFC2EE'), 140),
                                      dots(dd, w, h, 34, [C('BFF6FF'), C('FFFFFF'), C('FFC2EE')], 30 + k), streak(dd, w, h * 0.2, C('FFFFFF'), 110))),
    dict(name='心海', fill=((44, 16, 72), (8, 4, 26)), edge='FFFFFF', ew=5, rainbow=RAINBOW, inner='FFE9B0',
         feathers=[C('FFFFFF', 240), C('FFF3C8', 240), C('FFE296', 240), C('FFC2EE', 240), C('BFF6FF', 240)], crowncol='FFF3C8', gem='FFE9F6', crown=7, pearl=True,
         prism=RAINBOW, halo=True, pip='FFFFFF', pipedge='FFD27A',
         soft=lambda dd, w, h, p, k: (dd.ellipse((w * 0.08, h * 0.1, w * 0.45, h * 1.0), fill=(255, 111, 216, 120)),
                                      dd.ellipse((w * 0.55, -h * 0.1, w * 0.95, h * 0.8), fill=(120, 140, 255, 120)),
                                      dd.ellipse((w * 0.35, h * 0.3, w * 0.65, h * 1.1), fill=(79, 216, 255, 70))),
         deco=lambda dd, w, h, p, k: (rays(dd, w / 2, h / 2, 40, w * 0.6, C('FFFFFF'), 26),
                                      dots(dd, w, h, 60, [C('FFFFFF'), C('FFE9B0'), C('BFF6FF')], 50 + k, 1.3), filigree(dd, w, h, p, C('FFE9B0'), 150),
                                      sparkles(dd, [(p + 20 * S, h * 0.34, 5 * S), (w - p - 22 * S, h * 0.64, 6 * S), (w * 0.27, h * 0.74, 3 * S), (w * 0.75, h * 0.28, 3.5 * S)], C('FFFFFF'), 235))),
]


def tag(img, x, y, st):
    w, h, p = TW * S, 54 * S, 22 * S
    d = ImageDraw.Draw(img)
    pts = hexpts(x, y, w, h, p)
    cy = y + h / 2
    ec = C(st['edge'])
    wc = st.get('wingcol', st['edge'])
    wc = [C(c) for c in wc] if isinstance(wc, list) else C(wc)
    if st.get('halo'):
        glow(img, lambda dd: dd.ellipse((x - 30 * S, y - 30 * S, x + w + 30 * S, y + h + 30 * S), fill=(255, 236, 180, 90)), 18 * S)
    if st.get('red'):
        glow(img, lambda dd: dd.polygon(hexpts(x - 8 * S, y - 6 * S, w + 16 * S, h + 12 * S, p + 4 * S), fill=(255, 40, 70, 120)), 10 * S)
    if st.get('prism'):
        m = 34 * S
        paint(img, x - m, y - m, w + 2 * m, h + 2 * m, st['prism'],
              lambda dd: dd.polygon(hexpts(m, m, w, h, p), outline=255, width=st['ew'] * S * 3), 10 * S, 2.2)
    if st.get('feathers'):
        glow(img, lambda dd: (feathers(dd, x, cy, -1, st['feathers']), feathers(dd, x + w, cy, 1, st['feathers'])), 5 * S)
        feathers(d, x, cy, -1, st['feathers']); feathers(d, x + w, cy, 1, st['feathers'])
    if st.get('fins'):
        glow(img, lambda dd: (wing(dd, x, cy, -1, st['fins'], wc), wing(dd, x + w, cy, 1, st['fins'], wc)), 5 * S)
        wing(d, x, cy, -1, st['fins'], wc); wing(d, x + w, cy, 1, st['fins'], wc)
    if not st.get('prism'):
        glow(img, lambda dd: dd.polygon(pts, outline=ec[:3] + (200,), width=st['ew'] * S * 2), 6 * S)
    W, H = int(w), int(h)
    tile = grad(W, H, *st['fill'])
    if st.get('soft'):
        sl = Image.new('RGBA', (W, H), (0, 0, 0, 0)); st['soft'](ImageDraw.Draw(sl), w, h, p)
        tile.alpha_composite(sl.filter(ImageFilter.GaussianBlur(5 * S)))
    deco = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    st['deco'](ImageDraw.Draw(deco), w, h, p)
    tile.alpha_composite(deco.filter(ImageFilter.GaussianBlur(2.5 * S)))
    tile.alpha_composite(deco)
    blob = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(blob).ellipse((w * 0.28, h * 0.18, w * 0.72, h * 0.82), fill=(0, 0, 0, 110))
    tile.alpha_composite(blob.filter(ImageFilter.GaussianBlur(9 * S)))
    m = Image.new('L', (W, H), 0); ImageDraw.Draw(m).polygon([(a - x, b - y) for a, b in pts], fill=255)
    tile.putalpha(m)
    img.alpha_composite(tile, (int(x), int(y)))
    d = ImageDraw.Draw(img)
    if st.get('inner'):
        d.polygon(hexpts(x + 7 * S, y + 6 * S, w - 14 * S, h - 12 * S, p - 6 * S), outline=C(st['inner'], 170), width=S)
    if st.get('notch'):
        xx = x + w * 0.5
        d.polygon([(xx - 10 * S, y), (xx + 10 * S, y), (xx, y + 6 * S)], fill=ec)
        d.polygon([(xx - 10 * S, y + h), (xx + 10 * S, y + h), (xx, y + h - 6 * S)], fill=ec)
    d.polygon(pts, outline=ec, width=st['ew'] * S)
    if st.get('rainbow'):
        m = 4 * S
        paint(img, x - m, y - m, w + 2 * m, h + 2 * m, st['rainbow'], lambda dd: dd.polygon(hexpts(m, m, w, h, p), outline=255, width=st['ew'] * S))
        d = ImageDraw.Draw(img)
    if st.get('gem'):
        gem(d, x, cy, 11 * S, C(st['gem']), (255, 255, 255, 255))
        gem(d, x + w, cy, 11 * S, C(st['gem']), (255, 255, 255, 255))
    if st.get('pips'):
        pips(d, x + w / 2, y + h + 9 * S, st['pips'], C(st.get('pip', 'FFD27A')), C(st.get('pipedge', 'FFFFFF'), 230))


# 深渊 keeps its look with a crimson frame (gold fins, spikes, pips and pearls stay); 心海 1-3 have no rainbow edge
# and a small three-feather wing; the rainbow family is kept for tier 16 only.
AB = dict(FAM[0], edge='FF4D5E', inner='7A1424')
AB['deco'] = lambda dd, w, h, p, k: (cracks(dd, w, h, C('FF3A50'), 220, 11 + k), sonar(dd, w / 2, h / 2, [26 * S, 40 * S, 54 * S], C('FF6070'), 110, 40),
                                     sonar_l(dd, w / 2, h / 2, [26 * S, 40 * S, 54 * S], C('FF6070'), 110, 40), filigree(dd, w, h, p, C('FF8090'), 150))
SEA = dict(FAM[2], prism=[(255, 243, 200), (200, 170, 255), (255, 194, 238), (200, 170, 255), (255, 243, 200)],
           feathers=[C('FFFFFF', 240), C('E6DCFF', 240), C('C8B4FF', 240)])
SEA.pop('rainbow')
FAMILIES = [(AB, 'abyss'), (FAM[1], 'glow'), (SEA, 'sea')]


def tiers():
    """The 16 tier styles in order. Tiers 7-16 carry their level (1-3 pearls and pips) and pearl style."""
    out = [dict(t) for t in base_tiers()]
    for k in range(10):
        base, style, n = FAMILIES[k // 3] + (k % 3 + 1,) if k < 9 else (FAM[2], 'rainbow', 3)
        f = dict(base, pips=n, npearls=n, pstyle=style)
        for key in ('deco', 'soft'):
            if key in f:
                f[key] = lambda dd, w, h, p, fn=f[key], k=k: fn(dd, w, h, p, k)
        out.append(f)
    return out


def spike_colour(st):
    if 'crowncol' in st:
        return C(st['crowncol'])
    return C(st['wingcol'] if isinstance(st.get('wingcol'), str) else st['edge'])


def spikes(img, st, cx, htop):
    # the crown split in two: spikes on the hexagon top on both sides of the avatar
    hs = {3: [12], 5: [16, 10], 7: [20, 14, 9]}[st['crown']]
    col = spike_colour(st)

    def draw(dd):
        for side in (-1, 1):
            for i, hh in enumerate(hs):
                sx = cx + side * (R + 8 + i * 14) * K
                dd.polygon([(sx - 6 * K, htop + K), (sx, htop - hh * K), (sx + 6 * K, htop + K)], fill=col)
    glow(img, draw, 3 * K)
    draw(ImageDraw.Draw(img))


def pearl2(img, x, y, r, style, i):
    if style == 'sea':
        return pearl(img, x, y, r)
    halo = {'abyss': (255, 58, 80, 170), 'glow': (124, 245, 255, 210)}.get(style) or (RAINBOW[[0, 2, 3][i]] + (230,))
    body, shine, rim = {'abyss': ((214, 160, 70), (255, 226, 150), (255, 244, 210)),
                        'glow': ((196, 246, 255), (240, 255, 255), (232, 246, 255))}.get(style, ((236, 228, 255), (255, 255, 255), (255, 210, 122)))
    glow(img, lambda dd: dd.ellipse((x - r * 1.9, y - r * 1.9, x + r * 1.9, y + r * 1.9), fill=halo), r)
    d = ImageDraw.Draw(img)
    d.ellipse((x - r, y - r, x + r, y + r), fill=body + (255,), outline=rim + (255,), width=K)
    d.ellipse((x - r * 0.8, y - r * 0.85, x + r * 0.55, y + r * 0.5), fill=shine + (255,))
    d.ellipse((x - r * 0.55, y - r * 0.6, x - r * 0.1, y - r * 0.2), fill=(255, 255, 255, 255))


def jewels(img, st, cx, cy):
    # 1-3 pearls on the avatar ring's top arc (tiers 7-16), or the diamond of tier 6
    rr = R * K
    if st.get('npearls'):
        r = (8.5 if st['pstyle'] == 'rainbow' else 7.5) * K
        for i, a in enumerate({1: [0], 2: [-14, 14], 3: [-25, 0, 25]}[st['npearls']]):
            t = math.radians(a)
            pearl2(img, cx + rr * math.sin(t), cy - rr * math.cos(t), r, st['pstyle'], i)
    elif st.get('crown'):
        r, top = 6 * K, cy - rr
        ImageDraw.Draw(img).polygon([(cx - r, top), (cx, top - r), (cx + r, top), (cx, top + r)],
                                    fill=spike_colour(st), outline=(255, 255, 255, 255), width=K)


def solid_reach(img, hx, hw):
    # how far the solid art (alpha > 150, glows left out) reaches past the hexagon's tips, in world units
    a = img.getchannel('A').point(lambda v: 255 if v > 150 else 0).getbbox()
    return round((hx - a[0]) / K, 1), round((a[2] - (hx + hw)) / K, 1)


def keep_rows(img, y0, y1, tier):
    # the top layer's glow fades out above the split; cut its last faint tail so the two layers never overlap
    a = img.getchannel('A')
    tail = max(a.crop((0, y1, img.width, img.height)).getextrema()[1], 0)
    assert tail < 40, (tier, tail)
    return img.crop((0, y0, img.width, y1))


def premultiply(img):
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            pr, pg, pb, pa = px[x, y]
            if pa < 255:
                px[x, y] = (pr * pa // 255, pg * pa // 255, pb * pa // 255, pa)
    return img


def main():
    f = HEX_H * K / (54 * S)                      # drawing px -> output px (exactly 11/27 at S 4, K 2)
    mx, my = 324, 243                             # tag margins in drawing px, chosen so mx·f and my·f are whole
    top = 140 * K                                 # room above the hexagon for the avatar and its pearls
    hx, hy = round(mx * f), top                   # hexagon top-left on the output canvas
    hw, hh = HEX_W * K, HEX_H * K
    cx, cy = hx + hw / 2, hy - (R - LIFT) * K     # avatar centre
    split = hy - round(my * f)                    # the back layer starts where the drawn tag starts
    cw, ch = round((TW * S + 2 * mx) * f), hy - round(my * f) + round((54 * S + 2 * my) * f)
    os.makedirs(OUT, exist_ok=True)
    meta = dict(scale=K, canvas=[cw, ch], hexagon=[hx, hy, hw, hh], avatar=[cx, cy, R * K], split=split, tiers=[])
    for i, st in enumerate(tiers(), 1):
        c = Image.new('RGBA', (TW * S + 2 * mx, 54 * S + 2 * my), (0, 0, 0, 0))
        tag(c, mx, my, st)
        c = c.resize((round(c.width * f), round(c.height * f)), Image.LANCZOS)
        back = Image.new('RGBA', (cw, ch), (0, 0, 0, 0))
        back.alpha_composite(c, (0, hy - round(my * f)))
        solid = Image.new('RGBA', (TW * S + 2 * mx, 54 * S + 2 * my), (0, 0, 0, 0))
        tag(solid, mx, my, {a: b for a, b in st.items() if a not in ('prism', 'halo', 'red')})
        reach = solid_reach(solid.resize(c.size, Image.LANCZOS), hx, hw)
        if st.get('crown'):
            spikes(back, st, cx, hy)
        front = Image.new('RGBA', (cw, ch), (0, 0, 0, 0))
        jewels(front, st, cx, cy)
        back.paste(keep_rows(front, 0, split, i), (0, 0))
        premultiply(back).save(os.path.join(OUT, 'tier%02d.png' % i), optimize=True)
        meta['tiers'].append(dict(reach=list(reach)))
        print('tier', i, reach)
    with open(os.path.join(OUT, 'tiers.json'), 'w') as fp:
        json.dump(meta, fp, indent=1)


if __name__ == '__main__':
    main()
