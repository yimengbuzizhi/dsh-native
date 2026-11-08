# -*- coding: utf-8 -*-
"""把 screens/*.png 拼成一张总览图。"""
from PIL import Image, ImageDraw, ImageFont
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
PNG = os.path.join(ROOT, "png")

W = 1210
MARGIN = 35
GAP = 30
CARD_W = 360
CARD_H = 779

screens = [
    ("01-chat", "聊天 · 深色"),
    ("02-console", "控制台 · 深色"),
    ("03-sessions", "会话 · 浅色"),
    ("04-settings", "设置 · 浅色"),
    ("05-plugins", "插件 · 深色"),
    ("06-theme", "主题 · 浅色"),
]

rows = 2
title_h = 120
row_gap = 72
bottom_h = 190
H = title_h + rows * (CARD_H + row_gap) + bottom_h

canvas = Image.new("RGB", (W, H), "#0B0F17")
d = ImageDraw.Draw(canvas)

font_path = r"C:\Windows\Fonts\msyh.ttc"
font_bold = ImageFont.truetype(font_path, 34)
font_sub = ImageFont.truetype(font_path, 16)
font_label = ImageFont.truetype(font_path, 17)
font_small = ImageFont.truetype(font_path, 13)

d.text((MARGIN, 34), "DSH Native · 界面概念 v0.1", font=font_bold, fill="#E9EEFB")
d.text((MARGIN, 78), "品牌蓝 #2563EB · 默认蓝白 · 深/浅两套 · 7 月工期里的视觉基线", font=font_sub, fill="#8E99AD")


def rounded(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, im.size[0] - 1, im.size[1] - 1], radius=radius, fill=255)
    out = Image.new("RGB", im.size, "#0B0F17")
    out.paste(im, (0, 0), mask)
    return out


for i, (name, label) in enumerate(screens):
    x = MARGIN + (i % 3) * (CARD_W + GAP)
    y = title_h + (i // 3) * (CARD_H + row_gap)
    path = os.path.join(PNG, name + ".png")
    im = Image.open(path).convert("RGB").resize((CARD_W, CARD_H), Image.LANCZOS)
    im = rounded(im, 18)
    canvas.paste(im, (x, y))
    d.rounded_rectangle([x - 1, y - 1, x + CARD_W, y + CARD_H], radius=19, outline="#1E2636", width=1)
    d.text((x, y + CARD_H + 14), label, font=font_label, fill="#B9C3D6")

# 底部：图标
by = H - bottom_h + 14
d.text((MARGIN, by), "图标候选（DSH 元素 + 安卓元素）", font=font_label, fill="#E9EEFB")

icons = [("icon-01", "鲸鱼机器人"), ("icon-02", "D + 天线"), ("icon-03", "轨道鲸尾"), ("icon-04", "DSH 字标")]
ix = MARGIN
for name, label in icons:
    p = os.path.join(PNG, name + ".png")
    im = Image.open(p).convert("RGB").resize((96, 96), Image.LANCZOS)
    im = rounded(im, 22)
    canvas.paste(im, (ix, by + 34))
    d.text((ix + 48, by + 146), label, font=font_small, fill="#8E99AD", anchor="mm")
    ix += 132

d.text((W - MARGIN, by + 60), "单色版 / 自适应前景背景 也在 icons/ 里", font=font_small, fill="#5E6A80", anchor="ra")

out = os.path.join(ROOT, "00-overview.png")
canvas.save(out)
print("saved", out, canvas.size)
