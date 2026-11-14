#!/usr/bin/env python3
"""M86-F WebView cold check; main-agent review before explicit execution.
  python tools/m86f-webview-check.py --output docs/m86f-webview-NEW
One UUID ui-only static shell; native/default and global-idle/empty-draft preflight.
Unique reports retry partial JSON; finally native/default and only own fixture off.
No prefs/key/private reads, build, service changes, F14 rerun or send. PNG needs read_image.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile
ROOT = Path(__file__).resolve().parents[1]
PKG = "xin.dshaa.nativeapp"
COMPONENT = PKG + "/.MainActivity"
MARKER = "M86_WEB_COLD_READY"
BUILTIN_LOADING = "builtin.loading.native"
class CheckFailure(RuntimeError):
    pass
def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
def probe_empty(value, shell):
    return (value.get("shell") == shell and value.get("sessionId") == "" and
            all(value.get(key) == 0 for key in ("activeTurnCount", "writerCount", "streamingCount",
                                               "pendingImageCount", "pendingFileCount")) and
            all(value.get(key) is False for key in ("streaming", "stopping", "hasDraft", "errorPresent", "fixtureSession")))
class WebViewCheck:
    def __init__(self, args):
        self.args = args
        self.run_id = uuid.uuid4().hex
        self.plugin_id = "m86.selftest.webcold." + self.run_id
        self.shell_id = "m86.webcold." + self.run_id
        self.selection = self.plugin_id + "#" + self.shell_id
        self.out = (args.output or ROOT / "docs" / ("m86f-webview-" + self.run_id)).resolve()
        if not re.fullmatch(r"m86f-webview-[A-Za-z0-9-]+", self.out.name):
            raise CheckFailure("output_name_must_start_m86f_webview")
        self.out.mkdir(parents=True, exist_ok=False)
        self.remote = "/sdcard/Download/m86f-webview-" + self.run_id
        self.serial = 0
        self.install_attempted = False
        self.baseline = {}
        self.report = dict(fixture="M86-F-WebView-cold", version=1, runId=self.run_id,
                           pluginId=self.plugin_id, shellId=self.shell_id, selection=self.selection,
                           evidenceDirectory=str(self.out), remoteDirectory=self.remote,
                           status="preparing", completed=False, checks=[],
                           pixelEvidence=dict(status="pending-main-agent-read_image", marker=MARKER),
                           limitations=["VisualStateCallback and first-draw logs prove renderer order, not pixel presentation time.",
                                        "The main agent must read the retained PNG before declaring visual acceptance.",
                                        "Force-stop can affect accessibility binding; verify Bound separately after this run."])
        self.save()
    def save(self):
        write_json(self.out / "report.json", self.report)
    def require(self, name, ok, **evidence):
        self.report["checks"].append(dict(name=name, ok=bool(ok), **evidence))
        print(("PASS " if ok else "FAIL ") + name, flush=True)
        self.save()
        if not ok:
            raise CheckFailure(name)
    def adb(self, *parts, check=True, timeout=35):
        result = subprocess.run([str(self.args.adb), "-s", self.args.device, *map(str, parts)],
                                capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise CheckFailure("adb_failed:" + str(parts[0] if parts else "unknown"))
        return result
    def shell(self, *parts, **options):
        return self.adb("shell", shlex.join(list(map(str, parts))), **options)
    def start(self, wait=False, **extras):
        parts = ["am", "start"] + (["-W"] if wait else []) + ["-f", "0x20000000", "-n", COMPONENT]
        for key, value in extras.items():
            parts += ["--es", key, str(value)]
        return self.shell(*parts).stdout.decode("utf-8", "replace")
    def wait(self, name, predicate, timeout=35):
        deadline = time.monotonic() + timeout
        while True:
            value = predicate()
            if value:
                return value
            if time.monotonic() >= deadline:
                raise CheckFailure("timeout:" + name)
            time.sleep(0.25)
    def dump(self, key, name):
        self.serial += 1
        identity = f"{self.serial:03d}-{name}-{uuid.uuid4().hex}"
        target = self.remote + "/dump-" + identity + ".json"
        self.start(**{key: target})  # Once only; asynchronous/partial JSON is polled below.
        def read_complete():
            result = self.shell("cat", target, check=False)
            if result.returncode:
                return None
            try:
                value = json.loads(result.stdout)
                if not isinstance(value, dict):
                    return None
            except (ValueError, UnicodeError):
                return None
            write_json(self.out / "dumps" / (identity + ".json"), value)
            return value
        return self.wait("debug_report:" + name, read_complete)
    def wait_dump(self, key, name, predicate, timeout=45):
        # A valid report can still precede asynchronous install/grant/mount.
        # Each new observation gets another unique target; mutations never repeat.
        deadline = time.monotonic() + timeout
        attempt = 0
        while True:
            attempt += 1
            value = self.dump(key, f"{name}-{attempt:02d}")
            if predicate(value):
                return value
            if time.monotonic() >= deadline:
                raise CheckFailure("timeout_state:" + name)
            time.sleep(0.25)
    def own_shell(self, value):
        rows = [row for row in value.get("shells", []) if row.get("plugin") == self.plugin_id and
                row.get("id") == self.shell_id and row.get("backend") == "webview"]
        return rows[0] if len(rows) == 1 else None
    def fixture_archive(self):
        directory = self.out / "fixture"
        directory.mkdir()
        manifest = dict(name=self.plugin_id, version="1.0.0", main="index.js", permissions=["ui"],
                        description="Isolated retained M86 WebView cold-start fixture")
        html = ("<main style='min-height:75vh;display:flex;align-items:center;justify-content:center;"
                "background:#071b29;color:#ffffff;padding:24px;font:700 24px sans-serif;word-break:break-all'>"
                "<div role='heading' aria-level='1'>" + MARKER + "</div></main>")
        source = ("const html = " + json.dumps(html) + ";\n"
                  "function render() { return {title:'M86 cold WebView',html:html}; }\n"
                  "export function apply(ctx) { ctx.ui.registerShell({id:" + json.dumps(self.shell_id) +
                  ",label:'M86 cold WebView',backend:'webview',mount:render,onState:render}); }\n")
        write_json(directory / "package.json", manifest)
        (directory / "index.js").write_text(source, encoding="utf-8")
        archive = self.out / "webview-fixture.zip"
        with zipfile.ZipFile(archive, "x", zipfile.ZIP_DEFLATED) as packed:
            for name in ("package.json", "index.js"):
                packed.write(directory / name, name)
        self.report["fixtureArchive"] = dict(path=archive.name, permissions=manifest["permissions"],
                                              sha256=hashlib.sha256(archive.read_bytes()).hexdigest())
        self.save()
        return archive
    def preflight(self):
        # New remote directory, unique even when a caller reuses an output suffix.
        self.shell("mkdir", self.remote)
        self.start()
        ui = self.dump("dsh_ui_probe", "baseline-ui")
        shells = self.dump("dsh_shell_list", "baseline-shells")
        loading = self.dump("dsh_loading_list", "baseline-loading")
        plugins = self.dump("dsh_dump_plugins", "baseline-plugins")
        self.baseline = dict(ui=ui, shells=shells, loading=loading, plugins=plugins)
        self.require("baseline_global_idle_empty_local_draft", probe_empty(ui, "native"))
        self.require("baseline_native_ui", not shells.get("active") and shells.get("native", {}).get("enabled") is True and
                     shells.get("native", {}).get("safeMode") is False)
        self.require("baseline_default_loading", loading.get("selected") == BUILTIN_LOADING and
                     loading.get("snapshotOwner") == BUILTIN_LOADING)
        existing = set(plugins.get("enabled", [])) | set(plugins.get("disabled", []))
        self.require("fresh_uuid_not_installed", self.plugin_id not in existing)
    def install_select(self):
        archive = self.fixture_archive()
        target = self.remote + "/webview-fixture.zip"
        self.adb("push", str(archive), target)
        self.install_attempted = True
        self.start(dsh_plugin_zip=target)
        installed = self.wait_dump("dsh_shell_list", "installed-own-shell", lambda value: self.own_shell(value) is not None)
        row = self.own_shell(installed)
        self.require("normal_install_webview_metadata", row.get("enabled") is True and row.get("hasState") is True)
        self.start(dsh_plugin_grant=self.plugin_id)  # Exact UUID only; manifest requests ui and nothing else.
        granted = self.wait_dump("dsh_shell_list", "granted-own-shell", lambda value:
                                 self.own_shell(value) is not None and self.own_shell(value).get("uiGranted") is True)
        self.require("own_fixture_ui_granted", self.own_shell(granted).get("uiGranted") is True)
        self.start(dsh_shell=self.selection, dsh_open_screen="chat")
        selected = self.wait_dump("dsh_shell_list", "selected-own-shell", lambda value: value.get("active") == self.selection)
        self.require("own_fixture_selected", selected.get("active") == self.selection and not selected.get("treeError"))
        self.require("before_cold_global_idle_empty", probe_empty(self.dump("dsh_ui_probe", "before-cold-ui"), self.selection))
    def cold(self):
        self.shell("am", "force-stop", PKG)
        # A one-shot logcat dump later is restricted to this process. No global
        # clear/capture, and no hot debug report is requested until it is saved.
        previous = self.shell("pidof", PKG, check=False).stdout.decode().strip()
        self.require("force_stop_process_absent", not previous)
        command = self.start(wait=True)
        (self.out / "cold-am-start.txt").write_text(command, encoding="utf-8")
        pid = self.wait("cold_pid", lambda: self.shell("pidof", PKG, check=False).stdout.decode().strip())
        self.require("one_cold_process", bool(re.fullmatch(r"\d+", pid)), pid=pid)
        def capture_ready():
            result = self.adb("logcat", "-d", "--pid=" + pid, "-v", "threadtime", "-b", "main", "-b", "crash",
                              "DshBoot:I", "DshWebShell:I", "AndroidRuntime:E", "*:S")
            text = result.stdout.decode("utf-8", "replace")
            (self.out / "cold-boot.log").write_text(text, encoding="utf-8")
            return text if "reportFullyDrawn elapsedMs=" in text or "coreFailed elapsedMs=" in text or "FATAL EXCEPTION" in text else None
        text = self.wait("cold_report_fully_drawn", capture_ready, timeout=35)
        lines = text.splitlines()
        starts = [i for i, line in enumerate(lines) if "activityStart elapsedRealtimeMs=" in line]
        self.require("cold_exactly_one_activity_start", len(starts) == 1, count=len(starts))
        lines = lines[starts[0]:]
        names = ("loadingFirstDraw", "coreReady", "shellFirstDraw", "reportFullyDrawn")
        events = {}
        for name in names:
            matches = [(i, re.search(r"\b" + name + r" elapsedMs=(\d+)", line)) for i, line in enumerate(lines)]
            events[name] = [(i, int(match[1])) for i, match in matches if match]
        visual = [i for i, line in enumerate(lines) if re.search(r"contentVisualReady revision=\d+ presentationConfirmed=false", line)]
        attached = [i for i, line in enumerate(lines) if "attached target=" + self.shell_id in line]
        self.require("cold_webview_target_and_renderer_ready", bool(attached) and bool(visual), shellId=self.shell_id)
        self.require("cold_each_boot_stage_once", all(len(events[name]) == 1 for name in names))
        positions = {name: events[name][0][0] for name in names}
        times = {name: events[name][0][1] for name in names}
        self.require("cold_visual_ready_precedes_shell_and_fully_drawn",
                     positions["loadingFirstDraw"] < positions["coreReady"] < min(visual) < positions["shellFirstDraw"] < positions["reportFullyDrawn"] and
                     min(attached) < min(visual) and times["loadingFirstDraw"] < times["coreReady"] <= times["shellFirstDraw"] <= times["reportFullyDrawn"],
                     stageElapsedMs=times, contentVisualReadyLine=min(visual)+1, shellFirstDrawLine=positions["shellFirstDraw"]+1,
                     reportFullyDrawnLine=positions["reportFullyDrawn"]+1, presentationConfirmed=False)
        self.require("cold_no_failure_or_fatal", "coreFailed elapsedMs=" not in text and "FATAL EXCEPTION" not in text)
        self.marker_and_png()
        after = self.dump("dsh_shell_list", "cold-selected-shell")
        self.require("cold_still_exact_fixture", after.get("active") == self.selection and self.own_shell(after) is not None)
        self.require("cold_no_local_send_or_draft", probe_empty(self.dump("dsh_ui_probe", "cold-ui"), self.selection))
        self.report["coldObservation"] = dict(pid=pid, log="cold-boot.log", stageElapsedMs=times,
                                               rendererOrderVerified=True, presentationConfirmed=False)
        self.save()
    def marker_and_png(self):
        self.serial += 1
        target = self.remote + f"/cold-ui-{self.serial:03d}.xml"
        def read_marker():
            self.shell("uiautomator", "dump", target, check=False)
            result = self.shell("cat", target, check=False)
            try:
                found = False
                for node in ET.fromstring(result.stdout).iter("node"):
                    area = list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))
                    if (node.get("package") == PKG and any(MARKER in node.get(key, "") for key in ("text", "content-desc")) and
                            len(area) == 4 and area[2] > max(area[0], 0) and area[3] > max(area[1], 0)):
                        found = True
                        break
            except (ET.ParseError, ValueError):
                return None
            if found:
                (self.out / "cold-ui.xml").write_bytes(result.stdout)
                return True
            return None
        self.wait("actual_webview_marker_semantics", read_marker)
        self.require("cold_marker_in_actual_webview_ui", True, marker=MARKER, xml="cold-ui.xml")
        image = self.adb("exec-out", "screencap", "-p").stdout
        self.require("cold_screenshot_is_png", image.startswith(b"\x89PNG\r\n\x1a\n"))
        (self.out / "cold-webview.png").write_bytes(image)
        self.report["pixelEvidence"].update(path="cold-webview.png", sha256=hashlib.sha256(image).hexdigest(),
                                             uiSemanticsMarkerFound=True, screenshotsVerified=False)
        self.save()
    def cleanup(self):
        errors, original_verified = [], False
        if self.install_attempted:
            for name, action in (
                ("restore-native", lambda: self.start(dsh_shell="native", dsh_open_screen="chat")),
                ("default-loading", lambda: self.start(dsh_loading="default")),
                ("disable-own-fixture", lambda: self.start(dsh_plugin_off=self.plugin_id)),
            ):
                try:
                    action()
                except Exception as error:
                    errors.append(dict(stage=name, reason=str(error) if isinstance(error, CheckFailure) else type(error).__name__))
            try:
                shells = self.wait_dump("dsh_shell_list", "cleanup-native", lambda value:
                                        value.get("active") == "" and value.get("native", {}).get("selected") is True)
                loading = self.dump("dsh_loading_list", "cleanup-loading")
                plugins = self.dump("dsh_dump_plugins", "cleanup-plugins")
                ui = self.dump("dsh_ui_probe", "cleanup-ui")
                before = self.baseline["plugins"]
                self.require("cleanup_native_default_loading", not shells.get("active") and
                             loading.get("selected") == BUILTIN_LOADING and loading.get("snapshotOwner") == BUILTIN_LOADING)
                original_verified = (set(plugins.get("enabled", [])) == set(before.get("enabled", [])) and
                                     set(plugins.get("disabled", [])) == set(before.get("disabled", [])) | {self.plugin_id})
                self.require("cleanup_only_own_fixture_disabled_original_plugins_preserved", original_verified)
                self.require("cleanup_idle_empty_no_local_send", probe_empty(ui, "native"))
            except Exception as error:
                errors.append(dict(stage="verify-cleanup", reason=str(error) if isinstance(error, CheckFailure) else type(error).__name__))
        self.report["cleanup"] = dict(ok=not errors, errors=errors, fixtureRetained=self.install_attempted,
                                       deletedSessions=False, originalPluginStatesVerified=original_verified, privateFilesRead=False,
                                       evidenceRetained=True, accessibilityBoundVerified=False)
        self.save()
    def run(self):
        try:
            self.preflight()
            self.install_select()
            self.cold()
            self.report["completed"] = True
        except BaseException as error:
            self.report["failure"] = dict(reason=str(error) if isinstance(error, CheckFailure) else type(error).__name__)
        finally:
            self.cleanup()
        passed = self.report["completed"] and self.report["cleanup"]["ok"] and all(item["ok"] for item in self.report["checks"])
        self.report["automatedPassed"] = passed
        self.report["status"] = "automated-pass-pixel-review-pending" if passed else "failed"
        self.save()
        return passed
def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--adb", type=Path, default=Path(r"C:\Android\platform-tools\adb.exe"))
    parser.add_argument("--device", default="192.0.2.10:39905")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    run = WebViewCheck(args)
    passed = run.run()
    print("Evidence: " + str(run.out), flush=True)
    print("Read cold-webview.png before visual acceptance; verify accessibility Bound separately.", flush=True)
    return 0 if passed else 1
if __name__ == "__main__":
    raise SystemExit(main())
