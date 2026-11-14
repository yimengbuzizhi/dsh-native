"""M86-F device boot/UI evidence. Preparation only; run explicitly after reviewing.

Core run (uses the already-installed loading selftest fixture):
  python tools/m86f-boot-check.py
Optional hot-intent/recreate echo check (one retained probe session, no network):
  python tools/m86f-boot-check.py --hot-intent
A successful log check is NOT proof of visible pixels. Inspect startup/burst PNGs
or startup/screenrecord.mp4 and record the actual marker frame afterwards:
  python tools/m86f-boot-check.py --confirm-visible <run-dir> \
      --frame startup/burst/frame-003.png
The confirmation command is host-only and never invokes adb.

No settings/preferences dumps, credentials, permission toggles, session deletion,
logcat clearing, service starts or Gradle. The only private file read/written is
files/loading-snapshot.json; its original bytes are backed up before mutation.
Only explicit debug APIs, UI taps, force-stop/start, and dedicated evidence files
under /sdcard/Download/m86f-boot-<run-id> are used. Run against an idle debug build.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import threading
import time
import traceback
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PKG = "xin.dshaa.nativeapp"
COMPONENT = PKG + "/.MainActivity"
BUILTIN_LOADING = "builtin.loading.native"
SNAPSHOT = "files/loading-snapshot.json"
MARKER = "M86_LOADING_FIRST"


def json_write(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_boot_log(text: str):
    # A capture belongs to one force-stopped launch; retain exact lines as evidence.
    result = {"owner": None, "selection": None, "times": {}, "lines": []}
    for line in text.splitlines():
        owner = re.search(r"loadingSnapshot owner=(\S+) selection=(\S+)", line)
        if owner:
            result["owner"], result["selection"] = owner.groups()
        event = re.search(r"\b(loadingFirstDraw|coreReady|shellFirstDraw|reportFullyDrawn) elapsedMs=(\d+)", line)
        if event:
            result["times"].setdefault(event[1], int(event[2]))
        if "DshBoot" in line or "contentVisualReady" in line or "FATAL EXCEPTION" in line:
            result["lines"].append(line)
    times = result["times"]
    result["ordered"] = all(k in times for k in ("loadingFirstDraw", "coreReady", "shellFirstDraw")) and (
        times["loadingFirstDraw"] < times["coreReady"] < times["shellFirstDraw"])
    result["crash"] = "FATAL EXCEPTION" in text
    return result


class Probe:
    def __init__(self, args, fixture):
        self.args, self.fixture = args, fixture
        self.plugin_id = fixture["pluginId"]
        if not re.fullmatch(r"m86\.selftest\.loading\.[A-Za-z0-9.-]+", self.plugin_id):
            raise ValueError("fixture pluginId is not an M86 loading selftest id")
        stamp = time.strftime("%Y%m%d-%H%M%S") + "-" + str(time.time_ns() % 1_000_000)
        self.out = args.output or ROOT / "docs" / ("m86f-boot-" + stamp)
        self.out.mkdir(parents=True, exist_ok=False)
        self.remote = "/sdcard/Download/" + self.out.name
        if not re.fullmatch(r"/sdcard/Download/m86f-boot-[A-Za-z0-9-]+", self.remote):
            raise ValueError("output directory name must start m86f-boot- and contain only letters, digits, hyphens")
        self.report = {"device": args.device, "fixture": str(args.fixture), "pluginId": self.plugin_id,
                       "evidenceDirectory": str(self.out), "remoteDirectory": self.remote,
                       "checks": [], "pixelEvidence": {"status": "pending", "marker": MARKER,
                       "reason": "Inspect actual burst PNG/video frames; logs alone do not prove visible text."},
                       "forbiddenActions": "no secrets/prefs reads, no session deletion, no permissions/audio/alarm changes"}
        self.backup = None
        self.baseline = {}
        self.command_serial = 0
        self.changed = False
        self.save()

    def save(self):
        json_write(self.out / "report.json", self.report)

    def check(self, name, ok, **evidence):
        self.report["checks"].append({"name": name, "ok": bool(ok), **evidence})
        print(("PASS " if ok else "FAIL ") + name, flush=True)
        self.save()
        return bool(ok)

    def adb(self, *parts, timeout=30, input_bytes=None, check=True):
        command = [self.args.adb, "-s", self.args.device, *map(str, parts)]
        p = subprocess.run(command, input=input_bytes, capture_output=True, timeout=timeout)
        if check and p.returncode:
            raise RuntimeError("adb failed: " + " ".join(map(str, parts[:4])) + ": " + p.stderr.decode("utf-8", "replace"))
        return p

    def shell(self, *parts, **kwargs):
        return self.adb("shell", shlex.join(list(map(str, parts))), **kwargs)

    def start(self, wait=False, **extras):
        parts = ["am", "start"] + (["-W"] if wait else []) + ["-f", "0x20000000", "-n", COMPONENT]
        for key, value in extras.items():
            parts += (["--ez", key, str(value).lower()] if isinstance(value, bool) else ["--es", key, str(value)])
        return self.shell(*parts).stdout.decode("utf-8", "replace")

    def dump(self, key, name, **extras):
        self.command_serial += 1
        target = self.remote + f"/dump-{self.command_serial:03d}-{name}.json"
        self.start(**extras, **{key: target})
        deadline = time.monotonic() + 18
        while time.monotonic() < deadline:
            value = self.shell("cat", target, check=False)
            if value.returncode == 0:
                try:
                    data = json.loads(value.stdout)
                    json_write(self.out / "dumps" / (name + ".json"), data)
                    return data
                except (ValueError, UnicodeError):
                    pass
            time.sleep(.2)
        raise RuntimeError("debug dump did not arrive: " + name)

    def ui(self, name):
        target = self.remote + "/ui-" + name + ".xml"
        for attempt in range(3):
            self.shell("uiautomator", "dump", target, check=False)
            loaded = self.shell("cat", target, check=False)
            if loaded.returncode == 0:
                try:
                    root = ET.fromstring(loaded.stdout)
                    (self.out / (name + ".xml")).write_bytes(loaded.stdout)
                    return list(root.iter("node"))
                except ET.ParseError:
                    pass
            time.sleep(.5)
        raise RuntimeError("UI dump unavailable: " + name)

    def tap(self, text, exact=True, optional=False):
        for node in self.ui("tap-" + str(time.time_ns())):
            value = node.get("text", "")
            if (value == text if exact else text in value):
                bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
                if len(bounds) != 4:
                    continue
                x1, y1, x2, y2 = bounds
                self.shell("input", "tap", (x1 + x2) // 2, (y1 + y2) // 2)
                time.sleep(.5)
                return True
        if optional:
            return False
        raise RuntimeError("missing UI label: " + text)

    def shot(self, name):
        data = self.adb("exec-out", "screencap", "-p").stdout
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise RuntimeError("screencap did not return PNG")
        path = self.out / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def cold(self, name, owner, **extras):
        directory = self.out / name
        directory.mkdir()
        self.shell("am", "force-stop", PKG)
        log_file = (directory / "boot.log").open("wb")
        log = subprocess.Popen([self.args.adb, "-s", self.args.device, "logcat", "-v", "threadtime", "-T", "1",
                                "DshBoot:I", "DshWebShell:I", "AndroidRuntime:E", "*:S"], stdout=log_file, stderr=subprocess.STDOUT)
        record_file = (directory / "screenrecord-command.log").open("wb")
        video_remote = self.remote + "/" + name + ".mp4"
        recorder = subprocess.Popen([self.args.adb, "-s", self.args.device, "shell",
                                     shlex.join(["screenrecord", "--time-limit", "7", "--bit-rate", "2000000", video_remote])],
                                    stdout=record_file, stderr=subprocess.STDOUT)
        stop = threading.Event()
        frame_records = []
        began = time.monotonic()

        def burst():
            for index in range(14):
                if stop.is_set() or time.monotonic() - began > 7:
                    break
                try:
                    before = round((time.monotonic() - began) * 1000)
                    frame = self.shot(f"{name}/burst/frame-{index:03d}.png")
                    frame_records.append({"path": str(frame.relative_to(self.out)), "hostStartMs": before,
                                          "hostEndMs": round((time.monotonic() - began) * 1000)})
                except Exception as error:
                    frame_records.append({"error": str(error)})
                    break
                stop.wait(.10)

        worker = threading.Thread(target=burst, daemon=True)
        worker.start()
        try:
            (directory / "am-start.txt").write_text(self.start(wait=True, **extras), encoding="utf-8")
            worker.join(9)
            stop.set()
            worker.join(30)
            try:
                recorder.wait(timeout=10)
            except subprocess.TimeoutExpired:
                recorder.terminate()
                recorder.wait(timeout=5)
            time.sleep(.5)
        finally:
            stop.set()
            worker.join(30)
            for process in (recorder, log):
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
            record_file.close()
            log_file.close()
            json_write(directory / "frames.json", frame_records)
        pulled = self.adb("pull", video_remote, str(directory / "screenrecord.mp4"), check=False)
        (directory / "video-pull.log").write_bytes(pulled.stdout + pulled.stderr)
        text = (directory / "boot.log").read_text(encoding="utf-8", errors="replace")
        # Ignore the one tail line -T 1 may have replayed before this launch.
        if "activityStart elapsedRealtimeMs=" in text:
            text = text[text.index("activityStart elapsedRealtimeMs="):]
        observation = parse_boot_log(text)
        observation["frames"] = frame_records
        observation["videoAvailable"] = pulled.returncode == 0
        json_write(directory / "boot-observation.json", observation)
        self.check(name + ":snapshot-owner", observation["owner"] == owner, expected=owner, actual=observation["owner"])
        self.check(name + ":draw-order", observation["ordered"] and not observation["crash"], times=observation["times"],
                   log=str((directory / "boot.log").relative_to(self.out)))
        self.shot(name + "/settled.png")
        return observation

    def backup_snapshot(self):
        # Read EXACTLY this allowlisted private file; never inspect shared_prefs or registry.
        test = self.shell("run-as", PKG, "test", "-f", SNAPSHOT, check=False)
        if test.returncode != 0:
            raise RuntimeError("loading snapshot missing; baseline app must have finished one boot")
        data = self.adb("exec-out", "run-as", PKG, "cat", SNAPSHOT).stdout
        if len(data) > 65536:
            raise RuntimeError("unexpectedly large snapshot; refusing mutation")
        json.loads(data)
        backup = self.out / "loading-snapshot.original.json"
        backup.write_bytes(data)
        self.backup = data
        self.report["snapshotBackup"] = {"path": backup.name, "sha256": hashlib.sha256(data).hexdigest()}
        self.save()

    def write_snapshot(self, data):
        self.shell("am", "force-stop", PKG)
        # The literal target is fixed. No other private path can be selected by arguments.
        staging = self.out / "snapshot-injection.json"
        staging.write_bytes(data)
        remote_staging = "/data/local/tmp/" + self.out.name + "-snapshot-injection.json"
        if not re.fullmatch(r"/data/local/tmp/m86f-boot-[A-Za-z0-9-]+-snapshot-injection\.json", remote_staging):
            raise ValueError("snapshot staging path is not allowlisted")
        self.adb("push", str(staging), remote_staging)
        self.shell("run-as", PKG, "cp", remote_staging, SNAPSHOT)
        actual = self.adb("exec-out", "run-as", PKG, "cat", SNAPSHOT).stdout
        if actual != data:
            raise RuntimeError("snapshot write/readback mismatch")

    def run(self):
        self.shell("mkdir", "-p", self.remote)
        loading = self.dump("dsh_loading_list", "baseline-loading")
        shells = self.dump("dsh_shell_list", "baseline-shells")
        providers = self.dump("dsh_dump_providers", "baseline-providers")
        agents = self.dump("dsh_agent_list", "baseline-agents")
        plugins = self.dump("dsh_dump_plugins", "baseline-plugins")
        self.baseline = {"loading": loading, "shells": shells, "providers": providers, "agents": agents, "plugins": plugins}
        if not shells.get("native", {}).get("enabled") or shells.get("active"):
            raise RuntimeError("preflight requires native UI enabled/selected; baseline retained, no mutation performed")
        if agents.get("pref") not in (None, "", "native", "builtin.agent.dsh"):
            raise RuntimeError("preflight requires built-in agent selected")
        current_ui = self.ui("baseline")
        if any(n.get("text") == "下一步" for n in current_ui):
            raise RuntimeError("preflight requires onboarding already completed")
        if any(n.get("content-desc") in ("停止", "停止中…") for n in current_ui):
            raise RuntimeError("preflight requires all visible turns idle; refusing force-stop during generation")
        self.backup_snapshot()
        self.changed = True
        try:
            self.start(dsh_plugin_on=self.plugin_id)
            time.sleep(.8)
            self.start(dsh_loading=self.plugin_id + "#startup", dsh_shell="native")
            chosen = self.dump("dsh_loading_list", "startup-selected")
            self.check("startup:selection", chosen.get("snapshotSelection") == self.plugin_id + "#startup")
            self.cold("startup", self.plugin_id)
            self.report["pixelEvidence"].update({"burst": "startup/burst", "video": "startup/screenrecord.mp4",
                                                "frameManifest": "startup/frames.json"})
            self.save()

            self.start(dsh_loading=self.plugin_id + "#second")
            second = self.dump("dsh_loading_list", "second-selected")
            self.check("second:explicit-declaration", second.get("snapshotSelection") == self.plugin_id + "#second" and
                       second.get("revision") != chosen.get("revision"), selection=second.get("snapshotSelection"))

            self.start(dsh_loading=self.plugin_id + "#startup")
            self.start(dsh_plugin_off=self.plugin_id)
            disabled = self.dump("dsh_loading_list", "disabled-fallback")
            self.check("disabled:snapshot-default", disabled.get("snapshotOwner") == BUILTIN_LOADING,
                       notice=disabled.get("notice"))
            self.cold("disabled", BUILTIN_LOADING)

            self.start(dsh_plugin_on=self.plugin_id)
            self.start(dsh_loading=self.plugin_id + "#startup")
            self.dump("dsh_loading_list", "before-corrupt")
            self.write_snapshot(b'{"protocol":"loading/v1","corrupt":true}\n')
            self.cold("corrupt", BUILTIN_LOADING)
            self.start(dsh_loading="default")
            default = self.dump("dsh_loading_list", "default-selected")
            self.check("default:explicit-selection", default.get("selected") == BUILTIN_LOADING and
                       default.get("snapshotOwner") == BUILTIN_LOADING)

            self.cold("onboarding", BUILTIN_LOADING, dsh_reset_onboarding=True)
            welcome = self.ui("onboarding-welcome")
            self.check("onboarding:welcome", any(n.get("text") == "下一步" for n in welcome))
            self.tap("下一步")
            self.shot("onboarding/permissions.png")
            permission_ui = self.ui("onboarding-permissions")
            self.check("onboarding:permissions-page", any(n.get("text") == "先把能力打开" for n in permission_ui))
            # Only advance pages; never touch a permission, notification or sound control.
            self.tap("下一步")
            self.shot("onboarding/ready.png")
            self.tap("开始使用")
            self.shot("onboarding/completed.png")
            self.check("onboarding:completed-by-ui", not any(n.get("text") == "开始使用" for n in self.ui("onboarding-completed")))

            self.safe_mode()
            if self.args.hot_intent:
                self.hot_intent()
        finally:
            self.restore()
        self.report["automatedPassed"] = all(item["ok"] for item in self.report["checks"]) and self.report.get("cleanup", {}).get("ok", False)
        self.report["status"] = "automated-pass-pixel-review-pending" if self.report["automatedPassed"] else "failed"
        self.save()

    def safe_mode(self):
        self.start(dsh_shell="native", dsh_builtin_off="builtin.shell.native")
        time.sleep(.5)
        self.shot("safe-mode.png")
        self.check("safe-mode:visible", any(n.get("text") == "界面插件已停用" for n in self.ui("safe-mode")))
        self.tap("管理插件 / 选择界面壳")
        manager = self.ui("safe-plugin-manager")
        self.shot("safe-plugin-manager.png")
        self.check("safe-mode:manager", all(any(n.get("text") == text for n in manager) for text in ("导入", "内置", "角色")))
        self.tap("角色")
        # The seven-shell section is taller than a screen; a long fling skips its
        # trailing restore button. Narrow with the real search UI, preserving it.
        search = next((n for n in self.ui("safe-roles-before-search") if n.get("class") == "android.widget.EditText"), None)
        if search is None:
            raise RuntimeError("role manager search field missing")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", search.get("bounds", "")))
        self.shell("input", "tap", (x1 + x2) // 2, (y1 + y2) // 2)
        self.shell("input", "text", "builtin.shell.native")
        self.shell("input", "keyevent", "4")
        time.sleep(.3)
        self.shot("safe-roles.png")
        for attempt in range(8):
            if self.tap("恢复原生界面", optional=True):
                break
            self.shell("input", "swipe", 360, 1180, 360, 920, 450)
            time.sleep(.3)
        else:
            raise RuntimeError("restore native button unavailable after filtered short scrolling")
        time.sleep(.8)
        restored = self.dump("dsh_shell_list", "safe-restored")
        self.check("safe-mode:restored-via-manager", restored.get("native", {}).get("enabled") and
                   not restored.get("native", {}).get("safeMode"))
        self.shot("safe-restored.png")

    def run_remaining(self):
        """Only the previously unverified safe-mode and optional hot-intent paths."""
        self.shell("mkdir", "-p", self.remote)
        self.baseline = {
            "loading": self.dump("dsh_loading_list", "baseline-loading"),
            "shells": self.dump("dsh_shell_list", "baseline-shells"),
            "providers": self.dump("dsh_dump_providers", "baseline-providers"),
            "agents": self.dump("dsh_agent_list", "baseline-agents"),
            "plugins": self.dump("dsh_dump_plugins", "baseline-plugins"),
        }
        if not self.baseline["shells"].get("native", {}).get("enabled") or self.baseline["shells"].get("active"):
            raise RuntimeError("preflight requires enabled native UI selected")
        if any(n.get("content-desc") in ("停止", "停止中…") for n in self.ui("baseline")):
            raise RuntimeError("preflight requires visible turn idle")
        self.report["scope"] = "safe-mode-and-hot-intent-only; earlier boot-core3 evidence retained"
        self.changed = True
        try:
            self.safe_mode()
            if self.args.hot_intent:
                self.hot_intent()
        finally:
            self.restore()
        self.report["automatedPassed"] = all(c["ok"] for c in self.report["checks"]) and self.report.get("cleanup", {}).get("ok", False)
        self.report["status"] = "passed" if self.report["automatedPassed"] else "failed"
        self.report["pixelEvidence"] = {"status": "not-in-scope", "original": "docs/m86f-boot-core3/report.json"}
        self.save()

    def hot_intent(self):
        before = self.dump("dsh_dump_sessions", "hot-before-sessions")
        provider = self.dump("dsh_dump_providers", "hot-echo-provider", dsh_llm_provider="debug.echo")
        if not self.check("hot:offline-provider", provider.get("llmActive") == "debug.echo"):
            raise RuntimeError("debug.echo not active; refusing send")
        marker = "M86_F_HOT_ONCE_" + str(time.time_ns())
        output = self.start(dsh_send=marker)  # Exactly one send intent; hot onNewIntent triggers recreate.
        (self.out / "hot-am-start.txt").write_text(output, encoding="utf-8")
        time.sleep(2)
        first = self.dump("dsh_context_usage", "hot-usage-first")
        time.sleep(1)
        second = self.dump("dsh_context_usage", "hot-usage-second")
        after = self.dump("dsh_dump_sessions", "hot-after-sessions")
        old_ids, new_ids = set(before.get("active", [])), set(after.get("active", []))
        self.check("hot:exactly-one-retained-session", len(new_ids - old_ids) == 1 and not old_ids - new_ids,
                   added=sorted(new_ids - old_ids))
        self.check("hot:single-request-stable", first.get("sessionId") == second.get("sessionId") and
                   first.get("sessionId") in new_ids - old_ids and first.get("current") == second.get("current") and
                   first.get("current", {}).get("status") == "completed" and
                   first.get("current", {}).get("requestNo") == 1 and not first.get("history") and not second.get("history"))
        self.shot("hot-echo.png")

    def restore(self):
        if not self.changed:
            return
        failures = []
        for label, action in (
            ("native-ui", lambda: self.start(dsh_builtin_on="builtin.shell.native", dsh_shell="native")),
            ("onboarding", lambda: self.tap("跳过", optional=True)),
            ("fixture-state", lambda: self.start(**{("dsh_plugin_on" if self.plugin_id in self.baseline["plugins"].get("enabled", [])
                                                      else "dsh_plugin_off"): self.plugin_id})),
            ("provider", lambda: self.start(dsh_llm_provider=self.baseline["providers"].get("llmActive") or "off")),
            ("loading", lambda: self.start(dsh_loading=self.baseline["loading"].get("selected") or "default")),
        ):
            try:
                action()
            except Exception as error:
                failures.append(label + ": " + str(error))
        if self.backup is not None:
            try:
                time.sleep(.7)
                self.write_snapshot(self.backup)
                self.check("cleanup:original-snapshot-bytes-restored", True, sha256=hashlib.sha256(self.backup).hexdigest())
                # Leave app stopped so initialization cannot immediately rewrite the restored file.
                # The next ordinary launch uses the restored snapshot and loading selection.
            except Exception as error:
                failures.append("snapshot: " + str(error))
        self.report["cleanup"] = {"ok": not failures, "failures": failures, "appLeftStopped": self.backup is not None,
                                  "probeSessionsRetained": self.args.hot_intent}
        self.save()


def confirm_visible(args):
    directory = args.confirm_visible.resolve()
    report_path = directory / "report.json"
    report = json.loads(report_path.read_text(encoding="utf-8"))
    frame = (directory / args.frame).resolve()
    if not frame.is_relative_to(directory) or not frame.is_file() or frame.suffix.lower() != ".png":
        raise ValueError("--frame must be an existing PNG inside the evidence directory")
    report["pixelEvidence"] = {**report.get("pixelEvidence", {}), "status": "human-confirmed-visible",
                               "frame": str(frame.relative_to(directory)), "marker": MARKER,
                               "sha256": hashlib.sha256(frame.read_bytes()).hexdigest(),
                               "note": "Operator explicitly confirmed M86_LOADING_FIRST in this actual screenshot; log timing remains renderer evidence."}
    report["status"] = "passed" if report.get("automatedPassed") and report.get("cleanup", {}).get("ok") else "failed"
    json_write(report_path, report)
    print(report_path)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--adb", default=r"C:\Android\platform-tools\adb.exe")
    parser.add_argument("--device", default="192.0.2.10:39905")
    parser.add_argument("--fixture", type=Path, default=ROOT / "docs" / "m86f-loading-dev.json")
    parser.add_argument("--output", type=Path, help="New host directory named m86f-boot-<suffix>; default docs/m86f-boot-<timestamp>")
    parser.add_argument("--hot-intent", action="store_true", help="Also send exactly one offline echo; retain its probe session")
    parser.add_argument("--remaining-only", action="store_true", help="Run only safe-mode recovery and optional hot-intent; preserve earlier boot evidence")
    parser.add_argument("--confirm-visible", type=Path, help="Host-only: record review of existing first-frame PNG")
    parser.add_argument("--frame", help="PNG path relative to --confirm-visible evidence directory")
    args = parser.parse_args()
    if args.confirm_visible:
        if not args.frame:
            parser.error("--confirm-visible requires --frame after reviewing its visible marker")
        confirm_visible(args)
        return 0
    fixture = json.loads(args.fixture.read_text(encoding="utf-8"))
    if fixture.get("running") or fixture.get("passed") != fixture.get("total") or not fixture.get("allCreatedPluginsDisabled"):
        parser.error("loading fixture must be complete/passing with all created plugins disabled")
    probe = Probe(args, fixture)
    try:
        probe.run_remaining() if args.remaining_only else probe.run()
    except BaseException as error:
        probe.report["status"] = "failed"
        probe.report["error"] = str(error)
        (probe.out / "exception.txt").write_text(traceback.format_exc(), encoding="utf-8")
        probe.save()
        print("FAILED " + str(error), flush=True)
        return 1
    print("Evidence: " + str(probe.out), flush=True)
    print("Pixel marker review is required; use --confirm-visible only after inspecting an actual frame.", flush=True)
    return 0 if probe.report.get("automatedPassed") and probe.report.get("cleanup", {}).get("ok") else 1


if __name__ == "__main__":
    raise SystemExit(main())
