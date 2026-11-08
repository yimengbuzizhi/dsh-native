# -*- coding: utf-8 -*-
"""C 方案定稿：导出多尺寸 + 预览板。"""
from PIL import Image, ImageDraw, ImageFont
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
PNG = os.path.join(ROOT, "png")
OUT = os.path.join(ROOT, "exports")
os.makedirs(OUT, exist_ok=True)

master = Image.open(os.path.join(PNG, "20-icon-c-final.png")).convert("RGB")
sizes = [512, 192, 144, 96, 72, 48]
for s in sizes:
    master.resize((s, s), Image.LANCZOS).save(os.path.join(OUT, "ic_launcher_%d.png" % s))

# 预览板
W, H = 1180, 660
canvas = Image.new("RGB", (W, H), "#F2F1ED")
d = ImageDraw.Draw(canvas)

fp = r"C:\Windows\Fonts\msyh.ttc"
f_title = ImageFont.truetype(fp, 30)
f_sub = ImageFont.truetype(fp, 15)
f_label = ImageFont.truetype(fp, 14)
f_small = ImageFont.truetype(fp, 12)


def rounded(im, size, r):
    im = im.convert("RGB").resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    out = Image.new("RGB", (size, size), "#F2F1ED")
    out.paste(im, (0, 0), mask)
    return out


d.text((44, 34), "图标 C · 定稿", font=f_title, fill="#17181A")
d.text((44, 74), "黑底 + 白色字母 D + 两根安卓天线 + 品牌蓝窗口；Rikka 式极简，48px 仍可辨认。", font=f_sub, fill="#6B7280")

big = rounded(master, 360, 84)
canvas.paste(big, (44, 130))

# 尺寸行
x = 460
d.text((x, 130), "多尺寸", font=f_label, fill="#8A877E")
sx = x
for s in [192, 144, 96, 72, 48]:
    im = rounded(master, s, max(8, s // 5))
    canvas.paste(im, (sx, 160))
    d.text((sx, 160 + s + 8), "%d" % s, font=f_small, fill="#8A877E")
    sx += s + 26

# 自适应 / 单色
d.text((460, 400), "自适应图层 / 单色", font=f_label, fill="#8A877E")
fg = Image.open(os.path.join(PNG, "20-icon-fg.png")).convert("RGBA").resize((120, 120), Image.LANCZOS)
bg = Image.new("RGB", (120, 120), "#14161A")
bg.paste(fg, (0, 0), fg)
canvas.paste(bg, (460, 430))
d.text((460, 560), "前景（66dp 安全区）", font=f_small, fill="#8A877E")

mono = Image.open(os.path.join(PNG, "20-icon-mono.png")).convert("RGBA").resize((120, 120), Image.LANCZOS)
mbg = Image.new("RGB", (120, 120), "#FFFFFF")
mbg.paste(mono, (0, 0), mono)
canvas.paste(mbg, (610, 430))
d.text((610, 560), "单色（主题图标）", font=f_small, fill="#8A877E")

out = os.path.join(ROOT, "05-icon-final.png")
canvas.save(out)
print("saved", out, "exports:", os.listdir(OUT))
