#!/usr/bin/env python3
"""M84-Q：v0.3.2（发布树）↔ master（开发树）能力清单对拍，只读。

输出 JSON：源文件 / 声明名 / 工具名 / 页面 id / 系统提示词中文数 / 内置工具目录数量。
判定「有没有丢」的标准：发布树里的每一项都要能在开发树里找到（允许改名，改名记在 renamed 里）。
"""
import json
import re
from pathlib import Path

PUB = Path(r"D:\dsh\native-publish")
DEV = Path(r"D:\dsh\native")
OUT = DEV / "docs" / "m84q-inventory.json"

DECL = re.compile(
    r"^\s*(?:private |internal |public |protected |open |abstract |sealed |data |enum |value |inline |suspend |override |operator )*"
    r"(?:fun|class|object|interface|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)"
)
TOOL_NAME = re.compile(r'override val name\s*(?::\s*String)?\s*=\s*"([^"]+)"')

# 发布树里存在、开发树里换实现/改名的项：老名字 -> 新名字（人工确认过，见 docs/141）
RENAMES = {
    "DefaultTools": "BuiltinToolCatalog",
    "NativeBridge": "JsNativeBridge",
    "parseEnvelope": "parseJsEnvelope",
    "Screen": "BuiltinPageIds",
    "SubScreen": "BuiltinPageIds",
}

# 0.3.2 的页面枚举（App.kt 里 Screen / SubScreen 的常量）
PUB_PAGES = {
    "Sessions", "Workspace", "Agents", "Jobs", "Terminal", "VirtualScreen", "Accessibility",
    "Plugins", "Packages", "Memory", "Settings",
    "ModelConfig", "Assistant", "Behavior", "Backup", "DataMigration", "Skills", "Workspaces",
    "Permissions", "Privacy",
}


def kt_files(root: Path):
    for path in sorted(root.rglob("*.kt")):
        text = path.as_posix()
        if "/build/" in text or ".thirdparty-src" in text:
            continue
        yield path


def files_of(root: Path):
    return {p.relative_to(root).as_posix() for p in kt_files(root)}


def decls_of(root: Path):
    names = set()
    for path in kt_files(root):
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            m = DECL.match(line)
            if m:
                names.add(m.group(1))
    return names


def tool_names(root: Path):
    names = set()
    for path in kt_files(root):
        for m in TOOL_NAME.finditer(path.read_text(encoding="utf-8", errors="replace")):
            names.add(m.group(1))
    return names


def page_ids(root: Path):
    # master：BuiltinPageIds 的 const val 列表；发布树用人工清单
    ids = set()
    f = root / "app/src/main/java/xin/dshaa/nativeapp/data/BuiltinPageIds.kt"
    if f.is_file():
        for m in re.finditer(r'const val ([A-Za-z0-9_]+)\s*=\s*"([^"]+)"', f.read_text(encoding="utf-8")):
            ids.add(m.group(2))
    return ids


def han_count(path: Path) -> int:
    if not path.is_file():
        return -1
    return len(re.findall(r"[\u4e00-\u9fff]", path.read_text(encoding="utf-8", errors="replace")))


def main() -> int:
    pub_files, dev_files = files_of(PUB), files_of(DEV)
    pub_decls, dev_decls = decls_of(PUB), decls_of(DEV)
    pub_tools, dev_tools = tool_names(PUB), tool_names(DEV)
    dev_pages = page_ids(DEV)
    prompt = DEV / "core/prompt/src/main/resources/prompt/0.2.2-rc.1/system.txt"
    sections = prompt.parent / "sections.json"

    result = {
        "publish": str(PUB),
        "dev": str(DEV),
        "files": {
            "publish": len(pub_files),
            "dev": len(dev_files),
            "missing": sorted(pub_files - dev_files),
        },
        "declarations": {
            "publish": len(pub_decls),
            "dev": len(dev_decls),
            "missing": sorted(pub_decls - dev_decls),
            "renamed": {name: RENAMES.get(name, "") for name in sorted(pub_decls - dev_decls)},
        },
        "tools": {
            "publish_declared": len(pub_tools),
            "dev_declared": len(dev_tools),
            "missing": sorted(pub_tools - dev_tools),
            "extra_in_dev": sorted(dev_tools - pub_tools),
        },
        "pages": {
            "publish": sorted(PUB_PAGES),
            "dev": sorted(dev_pages),
            "missing": sorted(PUB_PAGES - dev_pages),
            "extra_in_dev": sorted(dev_pages - PUB_PAGES),
        },
        "prompt": {
            "system_txt_chars": len(prompt.read_text(encoding="utf-8")) if prompt.is_file() else -1,
            "system_txt_han": han_count(prompt),
            "sections_json_han": han_count(sections),
        },
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {OUT}")
    print(
        "files missing:", result["files"]["missing"],
        "| decl missing:", result["declarations"]["missing"],
        "| tools missing:", result["tools"]["missing"],
        "| pages missing:", result["pages"]["missing"],
        "| han:", result["prompt"]["system_txt_han"],
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
