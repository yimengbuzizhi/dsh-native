# -*- coding: utf-8 -*-
"""图标方向对比板：Rikka 参考 + 我们 4 个候选。"""
from PIL import Image, ImageDraw, ImageFont
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
PNG = os.path.join(ROOT, "png")
REF = os.path.join(ROOT, "ref")

items = [
    (os.path.join(REF, "rikka-icon-crop.png"), "RikkaHub（参考）", "黑底 + 单色粗形"),
    (os.path.join(PNG, "10-whale-rikka-dark.png"), "A · 鲸鱼机器人（暗）", "推荐，最像 Rikka"),
    (os.path.join(PNG, "11-whale-rikka-blue.png"), "B · 鲸鱼机器人（蓝）", "品牌蓝底"),
    (os.path.join(PNG, "12-d-rikka.png"), "C · D + 天线", "字母标，商务感"),
    (os.path.join(PNG, "13-whale-light.png"), "D · 浅色反相", "适配浅色/主题图标"),
]

TILE = 176
GAP = 34
MARGIN = 44
W = MARGIN * 2 + len(items) * TILE + (len(items) - 1) * GAP
H = 430

canvas = Image.new("RGB", (W, H), "#F2F1ED")
d = ImageDraw.Draw(canvas)

fp = r"C:\Windows\Fonts\msyh.ttc"
f_title = ImageFont.truetype(fp, 30)
f_sub = ImageFont.truetype(fp, 15)
f_label = ImageFont.truetype(fp, 15)
f_note = ImageFont.truetype(fp, 12)

d.text((MARGIN, 36), "图标方向 · Rikka 风格", font=f_title, fill="#17181A")
d.text((MARGIN, 78), "暗底 + 单色粗形 + 两个圆眼；RikkaHub 的做法：极简、强对比、小尺寸也认得出。", font=f_sub, fill="#6B7280")


def rounded(im, size, r):
    im = im.convert("RGB").resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    out = Image.new("RGB", (size, size), "#F2F1ED")
    out.paste(im, (0, 0), mask)
    return out


for i, (path, label, note) in enumerate(items):
    x = MARGIN + i * (TILE + GAP)
    y = 132
    im = rounded(Image.open(path), TILE, 36)
    canvas.paste(im, (x, y))
    d.text((x, y + TILE + 18), label, font=f_label, fill="#17181A")
    d.text((x, y + TILE + 42), note, font=f_note, fill="#6B7280")

out = os.path.join(ROOT, "04-icons.png")
canvas.save(out)
print("saved", out, canvas.size)
