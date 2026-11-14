#!/usr/bin/env python3
"""把 PluginModule.kt 里的 JS 大字符串抽出来做 node --check（M84-P 用）。

只做语法检查：PRELUDE / HOST_BRIDGE / RESIDENT_DISPATCH 拼一起交给 node --check。
Kotlin raw string 里的 `${'$'}` 还原成 `$`；其他 ${...} 插值如果出现会直接报出来（需要人工看）。
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

KT = Path(r"D:\dsh\native\core\plugins\src\main\java\xin\dshaa\core\plugins\PluginModule.kt")


def extract(name: str) -> str:
    lines = KT.read_text(encoding="utf-8").splitlines()
    start = None
    for i, line in enumerate(lines):
        if f"private val {name} = \"\"\"" in line:
            start = i + 1
            break
    if start is None:
        raise SystemExit(f"找不到 {name}")
    out = []
    for line in lines[start:]:
        if line.strip() == '"""':
            break
        out.append(line)
    else:
        raise SystemExit(f"{name} 没有结束标记")
    return "\n".join(out)


def main() -> int:
    parts = []
    for name in ("PRELUDE", "HOST_BRIDGE", "RESIDENT_DISPATCH"):
        js = extract(name)
        js = js.replace("${'$'}", "$")
        leftover = re.findall(r"\$\{[^}]*\}", js)
        if leftover:
            print(f"{name}: 还有 Kotlin 插值 {leftover}", file=sys.stderr)
        parts.append(f"// ---- {name} ----\n{js}")
    code = "\n".join(parts)
    path = Path(tempfile.gettempdir()) / "dsh-plugin-module-check.mjs"
    path.write_text(code, encoding="utf-8")
    result = subprocess.run(["node", "--check", str(path)], capture_output=True, text=True)
    print(result.stdout.strip())
    print(result.stderr.strip(), file=sys.stderr)
    print("OK" if result.returncode == 0 else "FAIL", flush=True)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
