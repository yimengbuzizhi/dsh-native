# -*- coding: utf-8 -*-
"""总览图 v2：RikkaHub 风格版（4 屏）。"""
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
    ("11-chat-light", "聊天 · 浅色（主推）"),
    ("12-chat-dark", "聊天 · 深色"),
    ("13-settings-light", "设置 · 浅色"),
    ("14-console-light", "控制台 · 浅色"),
]

title_h = 120
row_gap = 72
H = title_h + 2 * (CARD_H + row_gap) + 30

canvas = Image.new("RGB", (W, H), "#F7F5F0")
d = ImageDraw.Draw(canvas)

font_path = r"C:\Windows\Fonts\msyh.ttc"
font_bold = ImageFont.truetype(font_path, 34)
font_sub = ImageFont.truetype(font_path, 16)
font_label = ImageFont.truetype(font_path, 17)

d.text((MARGIN, 34), "DSH Native · Rikka 风格概念 v0.2", font=font_bold, fill="#1A1A17")
d.text((MARGIN, 78), "暖白底 · 少颜色 · 大字排版 · 圆角卡片 · 品牌蓝只做强调", font=font_sub, fill="#8A877E")


def rounded(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, im.size[0] - 1, im.size[1] - 1], radius=radius, fill=255)
    out = Image.new("RGB", im.size, "#F7F5F0")
    out.paste(im, (0, 0), mask)
    return out


for i, (name, label) in enumerate(screens):
    x = MARGIN + (i % 2) * (CARD_W + GAP)
    y = title_h + (i // 2) * (CARD_H + row_gap)
    im = Image.open(os.path.join(PNG, name + ".png")).convert("RGB").resize((CARD_W, CARD_H), Image.LANCZOS)
    im = rounded(im, 18)
    canvas.paste(im, (x, y))
    d.rounded_rectangle([x - 1, y - 1, x + CARD_W, y + CARD_H], radius=19, outline="#E3E0D8", width=1)
    d.text((x, y + CARD_H + 14), label, font=font_label, fill="#4A4842")

out = os.path.join(ROOT, "01-overview-v2.png")
canvas.save(out)
print("saved", out, canvas.size)
