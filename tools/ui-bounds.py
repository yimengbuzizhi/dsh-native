#!/usr/bin/env python3
"""从 uiautomator dump 里按 text 精确匹配一个节点，打印 bounds（M84-B 验收辅助）。"""
import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = r"C:\Android\platform-tools\adb.exe"
DEV = "192.0.2.10:45111"
PKG = "xin.dshaa.nativeapp"

text = sys.argv[1]
subprocess.run([ADB, "-s", DEV, "shell", "uiautomator", "dump", "/data/local/tmp/ui-b.xml"], capture_output=True)
raw = subprocess.run([ADB, "-s", DEV, "shell", "cat", "/data/local/tmp/ui-b.xml"], capture_output=True).stdout
root = ET.fromstring(raw.decode("utf-8", "replace"))
for node in root.iter("node"):
    if (node.get("text") or "") == text or (node.get("content-desc") or "") == text:
        print(node.get("text") or node.get("content-desc"), node.get("bounds"), node.get("class"))
