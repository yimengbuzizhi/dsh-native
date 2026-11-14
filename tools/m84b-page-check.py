#!/usr/bin/env python3
"""M84-B 页面插件化真机验收脚本（仅 debug 包）。

对每个内置页面插件：
  1. 关掉插件 -> dump 页面清单，确认它提供的页面 enabled=false；
  2. 打开设置页，从上滚到底，确认这些页面的入口文字全部消失（Workspace 查聊天页的工作区图标）；
  3. 回到聊天，清 logcat，`dsh_open_screen <页面>` -> 确认没有新的 DshPage 路由日志（被拦回）；
  4. 打开插件 -> 确认页面 enabled=true，逐一打开每个页面，确认路由日志落在正确页面；
  5. reset 全部内置插件。

结果写 docs/m84b-check.json。stdout 只用 ASCII，方便在任意终端看进度。
"""

import json
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = r"C:\Android\platform-tools\adb.exe"
DEV = "192.0.2.10:35809"
PKG = "xin.dshaa.nativeapp"
OUT = r"D:\dsh\native\docs\m84b-check-m86fgh.json"

# owner plugin -> [(page id, level, settings entry text)]
CASES = [
    ("builtin.fs", [("Workspace", "Primary", None)], "chat-workspace-icon"),
    ("builtin.shell", [("Terminal", "Primary", "终端")], None),
    ("builtin.jobs", [("Jobs", "Primary", "后台任务")], None),
    ("builtin.agent", [("Agents", "Primary", "子代理")], None),
    ("builtin.skill", [("Skills", "Secondary", "技能")], None),
    ("builtin.memory", [("Memory", "Primary", "记忆库")], None),
    ("builtin.page.sessions", [("Sessions", "Primary", "对话历史管理")], None),
    ("builtin.page.workspaces", [("Workspaces", "Secondary", "工作区管理")], None),
    ("builtin.page.device", [
        ("VirtualScreen", "Primary", "虚拟屏"),
        ("Accessibility", "Primary", "无障碍"),
        ("Permissions", "Secondary", "权限与能力"),
    ], None),
    ("builtin.page.packages", [("Packages", "Primary", "包管理")], None),
    ("builtin.page.model", [("ModelConfig", "Secondary", "模型与参数配置")], None),
    ("builtin.page.assistant", [
        ("Assistant", "Secondary", "助手配置"),
        ("Behavior", "Secondary", "显示与行为"),
    ], None),
    ("builtin.page.data", [
        ("Backup", "Secondary", "备份与恢复"),
        ("DataMigration", "Secondary", "旧版数据导入"),
        ("Privacy", "Secondary", "隐私与数据清理"),
    ], None),
    ("builtin.page.remote", [("RemoteDsh", "Secondary", "远程 DSH")], None),
    ("builtin.page.about", [("About", "Secondary", "关于 DSH 原生版")], None),
]

results = []


def adb(*args, timeout=30):
    p = subprocess.run([ADB, "-s", DEV, *args], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", "replace"), p.returncode


def shell(*args):
    return adb("shell", *args)


def start(extra_key=None, extra_value=None, flags="-f 0x20000000"):
    cmd = ["shell", "am", "start"]
    if flags:
        cmd += flags.split()
    cmd += ["-n", f"{PKG}/.MainActivity"]
    if extra_key:
        cmd += ["--es", extra_key, str(extra_value)]
    return adb(*cmd)


def open_screen(page):
    start("dsh_open_screen", page)


def set_builtin(off=None, on=None, reset=False):
    args = ["shell", "am", "start", "-f", "0x20000000", "-n", f"{PKG}/.MainActivity"]
    if off:
        args += ["--es", "dsh_builtin_off", off]
    if on:
        args += ["--es", "dsh_builtin_on", on]
    if reset:
        args += ["--ez", "dsh_builtin_reset", "true"]
    adb(*args)


def dump_pages(tag):
    path = f"/data/user/0/{PKG}/files/pages-{tag}.json"
    start("dsh_dump_pages", path)
    time.sleep(0.8)
    raw, _ = shell("run-as", PKG, "cat", f"files/pages-{tag}.json")
    try:
        return json.loads(raw)
    except Exception as e:
        raise RuntimeError(f"bad pages dump {tag}: {e}: {raw[:200]}")


def dump_xml():
    shell("uiautomator", "dump", "/data/local/tmp/ui-m84b.xml")
    raw, _ = shell("cat", "/data/local/tmp/ui-m84b.xml")
    try:
        root = ET.fromstring(raw)
    except Exception:
        return set(), set()
    texts, descs = set(), set()
    for node in root.iter("node"):
        t = (node.get("text") or "").strip()
        d = (node.get("content-desc") or "").strip()
        if t:
            texts.add(t)
        if d:
            descs.add(d)
    return texts, descs


def collect_settings_texts():
    open_screen("Settings")
    time.sleep(1.2)
    seen, last = set(), None
    for _ in range(9):
        texts, _ = dump_xml()
        seen |= texts
        current = frozenset(texts)
        if current == last:
            break
        last = current
        shell("input", "swipe", "360", "1350", "360", "450", "250")
        time.sleep(0.65)
    return seen


def logcat_routes():
    raw, _ = shell("logcat", "-d", "-s", "DshPage")
    return [line for line in raw.splitlines() if "DshPage" in line]


def expected_route(page, level):
    if level == "Primary":
        return f"page={page} sub=-"
    return f"page=Settings sub={page}"


def record(case, step, ok, detail=""):
    results.append({"plugin": case, "step": step, "ok": bool(ok), "detail": detail})
    print(("PASS " if ok else "FAIL ") + case + " " + step + " " + detail, flush=True)


def main():
    # 冷启一个已知状态
    shell("am", "force-stop", PKG)
    adb("logcat", "-c")
    shell("am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(2.5)
    set_builtin(reset=True)
    time.sleep(0.8)

    base = dump_pages("baseline")
    record("baseline", "pages", len(base["pages"]) == 22, f"pages={len(base['pages'])}")
    record("baseline", "unknown", not base["unknown"], f"unknown={base['unknown']}")
    record("baseline", "duplicates", not base["duplicates"], f"duplicates={base['duplicates']}")
    record("baseline", "catalogMatch", base.get("catalogMatch") is True, f"catalogMatch={base.get('catalogMatch')}")
    record(
        "baseline",
        "unowned",
        base.get("unowned") == ["Plugins", "Settings"],
        f"unowned={base.get('unowned')}",
    )

    for plugin, pages, special in CASES:
        print(f"== {plugin} ==", flush=True)
        set_builtin(off=plugin)
        time.sleep(1.2)
        dump = dump_pages("off-" + plugin.replace(".", "_"))
        rec = {p["id"]: p for p in dump["pages"]}
        record(plugin, "disabled", plugin in dump["disabled"], f"disabled={dump['disabled']}")
        record(
            plugin,
            "pages-off",
            all(rec[pid]["enabled"] is False for pid, _, _ in pages),
            ",".join(f"{pid}={rec[pid]['enabled']}" for pid, _, _ in pages),
        )
        record(plugin, "unknown", not dump["unknown"], f"unknown={dump['unknown']}")
        record(plugin, "duplicates", not dump["duplicates"], f"duplicates={dump['duplicates']}")

        if special == "chat-workspace-icon":
            open_screen("chat")
            time.sleep(1.0)
            _, descs = dump_xml()
            record(plugin, "entry-gone", "工作区" not in descs, "chat content-desc 工作区")
        else:
            seen = collect_settings_texts()
            for pid, level, entry in pages:
                record(plugin, f"entry-gone:{pid}", entry not in seen, f"entry={entry}")

        # 直接路由应被拦回：先回聊天，清 logcat，再尝试
        for pid, level, _ in pages:
            open_screen("chat")
            time.sleep(0.5)
            adb("logcat", "-c")
            open_screen(pid)
            time.sleep(1.4)
            routes = logcat_routes()
            blocked = not any(expected_route(pid, level) in line for line in routes)
            record(plugin, f"blocked:{pid}", blocked, f"routes={[r.split('DshPage : ')[-1] for r in routes]}")

        # 打开插件 -> 页面恢复、路由落对
        set_builtin(on=plugin)
        time.sleep(1.2)
        dump = dump_pages("on-" + plugin.replace(".", "_"))
        rec = {p["id"]: p for p in dump["pages"]}
        record(
            plugin,
            "pages-on",
            all(rec[pid]["enabled"] is True for pid, _, _ in pages),
            ",".join(f"{pid}={rec[pid]['enabled']}" for pid, _, _ in pages),
        )
        for pid, level, _ in pages:
            open_screen("chat")
            time.sleep(0.5)
            adb("logcat", "-c")
            open_screen(pid)
            time.sleep(1.4)
            routes = logcat_routes()
            want = expected_route(pid, level)
            record(plugin, f"route:{pid}", any(want in line for line in routes), f"want={want}")
        open_screen("chat")
        time.sleep(0.4)

    set_builtin(reset=True)
    time.sleep(0.8)
    final = dump_pages("final")
    record("final", "all-enabled", all(p["enabled"] for p in final["pages"]), "")
    record("final", "disabled-empty", not final["disabled"], f"disabled={final['disabled']}")

    fails = [r for r in results if not r["ok"]]
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(
            {
                "device": DEV,
                "time": time.strftime("%Y-%m-%d %H:%M:%S"),
                "total": len(results),
                "passed": len(results) - len(fails),
                "failed": len(fails),
                "results": results,
            },
            f,
            ensure_ascii=False,
            indent=2,
        )
    print(f"RESULT total={len(results)} passed={len(results) - len(fails)} failed={len(fails)}", flush=True)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
