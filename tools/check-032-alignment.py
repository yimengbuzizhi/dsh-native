#!/usr/bin/env python3
"""M84-Q：0.3.2 发布说明里的用户可见功能点 → 当前仓库代码位置（只读，首条命中）。

粗查「代码还在不在」，不是行为验证；行为验证看 docs/109~112、docs/114、docs/m84b-check-m84n.json。
输出 Markdown 表格到 stdout。
"""
import re
import sys
from pathlib import Path

ROOT = Path(r"D:\dsh\native")

APP = "app/src/main/java/xin/dshaa/nativeapp"
CORE = "core"

ITEMS = [
    ("长任务无步数上限", r"单轮最多 12 步", f"{CORE}/engine/src/main/java/xin/dshaa/core/engine/TurnEngine.kt"),
    ("LLM 读取超时 180s", r"readTimeout\(180", f"{CORE}/llm/src/main/java/xin/dshaa/core/llm/LlmClient.kt"),
    ("工具执行中点停止", r"deps\.stop\.requested", f"{CORE}/engine/src/main/java/xin/dshaa/core/engine/TurnEngine.kt"),
    ("包统一开关", r"setPluginEnabled|Switch\(", f"{APP}/ui/plugins/PluginsScreen.kt"),
    ("技能开关/删除", r"setSkillEnabled|skillsDisabled", f"{APP}/chat/AppViewModel.kt"),
    ("聊天「选择包」三段", r"选择包|MCP", f"{APP}/ui/chat/ChatComposer.kt"),
    ("会话彻底删除/回收站", r"回收站|彻底删除", f"{APP}/chat/AppViewModel.kt"),
    ("页面进入/退出转场", r"AnimatedContent", f"{APP}/ui/App.kt"),
    ("从聊天进的页面返回聊天", r"goFromChat", f"{APP}/ui/App.kt"),
    ("助手人设内联/导入/全屏", r"导入文件|全屏", f"{APP}/ui/settings/AssistantScreen.kt"),
    ("上下文自动压缩可设置", r"autoCompact|compactionRatio", f"{APP}/chat/AppViewModel.kt"),
    ("悬浮窗只留最终回复", r"只留最后那条纯文字回复", f"{APP}/device/floating/ui/window/FloatingWindowScreen.kt"),
    ("悬浮窗遮罩 0.69", r"0\.69", f"{APP}/device/floating/ui/fullscreen/FloatingFullscreenScreen.kt"),
    ("系统提示词全英文", r"[\\u4e00-\\u9fff]", f"{CORE}/prompt/src/main/resources/prompt/0.2.2-rc.1/system.txt"),
]


def find(path: Path, pattern: str):
    if not path.is_file():
        return None
    text = path.read_text(encoding="utf-8", errors="replace")
    for i, line in enumerate(text.splitlines(), 1):
        if re.search(pattern, line):
            return i, line.strip()[:100]
    return None


def main() -> int:
    lines = ["| 0.3.2 功能点 | 代码位置（首条命中） |", "|---|---|"]
    missing = 0
    for name, pattern, rel in ITEMS:
        hit = find(ROOT / rel, pattern)
        if hit:
            lines.append(f"| {name} | `{rel}:{hit[0]}` |")
        else:
            missing += 1
            lines.append(f"| {name} | **MISSING** |")
    table = "\n".join(lines) + "\n"
    out = ROOT / "docs" / "m84q-alignment-table.md"
    out.write_text(table, encoding="utf-8")
    print(f"wrote {out} missing={missing}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
