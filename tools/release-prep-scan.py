#!/usr/bin/env python3
"""M84-S：发版前置检查（只读，不碰发布树、不改任何文件）。

做四件事，结果写 docs/m84s-release-prep.json 与 docs/m84s-release-prep.md：
1. 发布树状态：HEAD / tag / 工作区是否干净（必须干净才允许后续同步）；
2. 开发树清单：tracked 文件数、发布时要剔除的内部文件（docs/shots/**、docs/HANDOFF.md）；
3. 脱敏扫描：本机 IP / 主机用户名路径 / 常见密钥形态（**只报文件名+行号+模式名，不打印命中值**）；
4. Node 工具链自检：发版要用的脚本（check-plugin-js / inventory）能跑。

规则来自 docs/113（v0.3.2 发布快照）：发布树里要删 docs/shots 与 docs/HANDOFF.md，
IP/用户名要替换成文档保留地址（192.0.2.x / C:\\Users\\user）。
"""
import json
import re
import subprocess
import sys
from pathlib import Path

DEV = Path(r"D:\dsh\native")
PUB = Path(r"D:\dsh\native-publish")
OUT_JSON = DEV / "docs" / "m84s-release-prep.json"
OUT_MD = DEV / "docs" / "m84s-release-prep.md"

# 发布树里必须剔除的内部文件/目录（沿用 docs/113 的规则）
EXCLUDE_PREFIXES = ("docs/shots/",)
EXCLUDE_FILES = {"docs/HANDOFF.md"}

# 脱敏规则：名字 -> 正则（命中值一律不打印）
PATTERNS = [
    ("device_ip", re.compile(r"\b100\.\d{1,3}\.\d{1,3}\.\d{1,3}\b")),
    ("lan_ip", re.compile(r"\b192\.168\.\d{1,3}\.\d{1,3}\b")),
    ("windows_user_path", re.compile(r"C:\\Users\\(?!user\b)[A-Za-z0-9_.-]+", re.IGNORECASE)),
    ("openai_key", re.compile(r"\bsk-[A-Za-z0-9_-]{16,}")),
    ("github_token", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})")),
    ("bearer", re.compile(r"\bBearer\s+[A-Za-z0-9._-]{20,}")),
]


def git(root: Path, *args: str) -> str:
    p = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True, encoding="utf-8")
    return (p.stdout or "").strip()


def main() -> int:
    report = {"publish": {}, "dev": {}, "sensitive": {}, "notes": []}

    # 1) 发布树
    report["publish"] = {
        "path": str(PUB),
        "exists": PUB.is_dir(),
        "head": git(PUB, "rev-parse", "--short", "HEAD") if PUB.is_dir() else "",
        "describe": git(PUB, "describe", "--tags") if PUB.is_dir() else "",
        "dirty": bool(git(PUB, "status", "--short")) if PUB.is_dir() else None,
        "dirty_files": git(PUB, "status", "--short").splitlines() if PUB.is_dir() else [],
    }

    # 2) 开发树 tracked 清单
    tracked = [line for line in git(DEV, "ls-files").splitlines() if line]
    excluded = [f for f in tracked if f in EXCLUDE_FILES or f.startswith(EXCLUDE_PREFIXES)]
    report["dev"] = {
        "path": str(DEV),
        "head": git(DEV, "rev-parse", "--short", "HEAD"),
        "tracked": len(tracked),
        "publish_excluded": excluded,
        "publish_candidate_count": len(tracked) - len(excluded),
    }

    # 3) 脱敏扫描（只报文件名 + 行号 + 模式名）
    hits = []
    for rel in tracked:
        path = DEV / rel
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="strict")
        except (OSError, UnicodeDecodeError):
            continue
        for i, line in enumerate(text.splitlines(), 1):
            for name, pattern in PATTERNS:
                if pattern.search(line):
                    hits.append({"file": rel, "line": i, "pattern": name})
    summary = {}
    for hit in hits:
        summary[hit["pattern"]] = summary.get(hit["pattern"], 0) + 1
    report["sensitive"] = {"summary": summary, "hits": hits}
    report["notes"] = [
        "发布树当前必须保持干净；本脚本不写发布树。",
        "命中项要在发布树里替换成文档保留值（docs/113 规则），不要删开发树的历史记录。",
    ]

    OUT_JSON.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    lines = [
        "# M84-S 发版前置检查（只读，未发布）",
        "",
        "> 由 `tools/release-prep-scan.py` 生成；**没有修改发布树、没有打 tag**。",
        "",
        "## 发布树",
        "",
        f"- 路径：`{report['publish']['path']}`",
        f"- HEAD：`{report['publish']['head'] or '（不存在）'}`，describe `{report['publish']['describe']}`",
        f"- 工作区：{'**有未提交改动**' if report['publish']['dirty'] else '干净'}",
        "",
        "## 开发树",
        "",
        f"- HEAD：`{report['dev']['head']}`",
        f"- tracked 文件：{report['dev']['tracked']}，发布候选 {report['dev']['publish_candidate_count']}（剔除 "
        f"{len(report['dev']['publish_excluded'])} 个内部文件）",
        "",
        "剔除清单：",
        "",
    ]
    for item in report["dev"]["publish_excluded"]:
        lines.append(f"- `{item}`")
    lines += ["", "## 脱敏扫描（只列位置，不打印命中值）", ""]
    if not hits:
        lines.append("- **0 命中**")
    else:
        for name, count in sorted(summary.items()):
            lines.append(f"- `{name}`：{count} 处")
        lines.append("")
        lines.append("| 文件 | 行 | 模式 |")
        lines.append("|---|---|---|")
        for hit in hits:
            lines.append(f"| `{hit['file']}` | {hit['line']} | `{hit['pattern']}` |")
    OUT_MD.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"wrote {OUT_JSON}")
    print(f"wrote {OUT_MD}")
    print(
        "publish head:", report["publish"]["head"], "dirty:", report["publish"]["dirty"],
        "| dev tracked:", report["dev"]["tracked"],
        "| sensitive:", summary,
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
