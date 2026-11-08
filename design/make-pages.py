# -*- coding: utf-8 -*-
"""Operit 风「其他页面」三屏展示。"""
from PIL import Image, ImageDraw, ImageFont
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
PNG = os.path.join(ROOT, "png")

W = 1280
MARGIN = 40
GAP = 30
CARD_W = 380
CARD_H = 822

items = [
    ("17-packages", "插件 / 工具管理", "标签页 + 搜索 + 开关行 + 悬浮按钮"),
    ("18-workspace", "工作区 / 环境", "设置卡 + 文件列表 + 环境状态"),
    ("19-terminal", "终端", "标签页 + 等宽 + 快捷按键条"),
]

title_h = 150
H = title_h + CARD_H + 80

canvas = Image.new("RGB", (W, H), "#F2F1ED")
d = ImageDraw.Draw(canvas)

fp = r"C:\Windows\Fonts\msyh.ttc"
f_title = ImageFont.truetype(fp, 34)
f_sub = ImageFont.truetype(fp, 16)
f_head = ImageFont.truetype(fp, 18)
f_small = ImageFont.truetype(fp, 13)

d.text((MARGIN, 34), "DSH Native · 其他页面（Operit 风）", font=f_title, fill="#17181A")
d.text((MARGIN, 82), "同一套卡片语言，换到管理类、环境类、终端类页面。", font=f_sub, fill="#6B7280")


def rounded(im, r):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, im.size[0] - 1, im.size[1] - 1], radius=r, fill=255)
    out = Image.new("RGB", im.size, "#F2F1ED")
    out.paste(im, (0, 0), mask)
    return out


for i, (name, head, note) in enumerate(items):
    x = MARGIN + i * (CARD_W + GAP)
    y = title_h
    im = Image.open(os.path.join(PNG, name + ".png")).convert("RGB").resize((CARD_W, CARD_H), Image.LANCZOS)
    im = rounded(im, 20)
    canvas.paste(im, (x, y))
    d.rounded_rectangle([x - 1, y - 1, x + CARD_W, y + CARD_H], radius=21, outline="#DAD8D0", width=1)
    d.text((x, y + CARD_H + 16), head, font=f_head, fill="#17181A")
    d.text((x, y + CARD_H + 40), note, font=f_small, fill="#6B7280")

out = os.path.join(ROOT, "03-pages.png")
canvas.save(out)
print("saved", out, canvas.size)
