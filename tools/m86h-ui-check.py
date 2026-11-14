#!/usr/bin/env python3
"""M86-H normal-UI check. WRITE-ONLY delivery: the author did not execute this script/adb.

Run only after the main agent has finished other device interaction, installed the intended APK,
started its ONE managed m86h-remote-fixture.py process and reversed tcp:18786.
  python tools/m86h-ui-check.py --device 192.0.2.10:39905

Uses UIAutomator/input to add two uniquely named m86h-ui-<time>-A/B profiles, click Use,
submit exactly one fixture prompt on each shared ChatScreen, expand tool cards, capture
remote/drawer/source-selector evidence, switch to local, and delete ONLY those two profiles
through their title-checked confirmation dialogs. Existing profiles are never edited.
Does not read prefs, app files, user's tokens, runtime dumps or H probe reports.
Only the isolated helper is accessed via HTTP; m86h-a/b are public fixture constants.
No debug Intent extras, remote prompt RPCs, apk installation, server start or adb reverse.

Finally attempts local selection and exact-title cleanup even after a failed check. On an
ambiguous row/dialog it refuses deletion and reports the remaining fixture title for manual UI
cleanup. Screenshots are captured while the remote is still selected; the parent must inspect
these PNGs with read_image. Canvas ring absence needs that visual inspection in addition to
UIAutomator's composer-label check. The JSON report never claims screenshots were inspected.
"""
from __future__ import annotations

import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET

PKG = "xin.dshaa.nativeapp"
FIXTURE_ROOT = "http://127.0.0.1:18786"
PUBLIC_TOKENS = {"a": "m86h-a", "b": "m86h-b"}
MODEL = {"a": "fixture-A", "b": "fixture-B"}
REMOTE_FORBIDDEN = ("输入设置", "附件", "模型设置", "工作区")
DRAWER_FORBIDDEN = ("切换工作区", "切换助手配置", "全部会话")
EDIT_LABELS = ("名称", "地址", "令牌")


class CheckFailure(RuntimeError):
    pass


def bounds(node):
    values = re.findall(r"-?\d+", node.get("bounds", ""))
    return tuple(map(int, values)) if len(values) == 4 else (0, 0, 0, 0)


def labels(node):
    return [node.get(key, "") for key in ("text", "content-desc", "hint") if node.get(key)]


def matches(node, target):
    # Compose can merge a Text's semantics with sibling labels using newlines.
    return any(value == target or target in value.splitlines() for value in labels(node))


class Tree:
    def __init__(self, raw):
        self.root = ET.fromstring(raw)
        self.parent = {child: parent for parent in self.root.iter() for child in parent}
        self.nodes = [node for node in self.root.iter("node") if node.get("package") == PKG]
        self.width = max((bounds(n)[2] for n in self.root.iter("node")), default=0)
        self.height = max((bounds(n)[3] for n in self.root.iter("node")), default=0)

    def visible(self, node):
        x1, y1, x2, y2 = bounds(node)
        return x2 - x1 >= 4 and y2 - y1 >= 4 and x2 > 0 and y2 > 0 and x1 < self.width and y1 < self.height

    def find(self, target, scope=None, contains=False):
        candidates = self.nodes if scope is None else list(scope.iter("node"))
        return [n for n in candidates if self.visible(n) and
                (any(target in value for value in labels(n)) if contains else matches(n, target))]

    def has(self, target, scope=None, contains=False):
        return bool(self.find(target, scope, contains))

    def ancestors(self, node):
        current = node
        while current is not None:
            yield current
            current = self.parent.get(current)

    def editable(self, scope=None):
        candidates = self.nodes if scope is None else list(scope.iter("node"))
        return [n for n in candidates if self.visible(n) and
                (n.get("class") == "android.widget.EditText" or n.get("editable") == "true")]

    def unique(self, target, scope=None):
        found = self.find(target, scope)
        if len(found) != 1:
            raise CheckFailure(f"expected_unique_ui_label:{target}")
        return found[0]

    def field(self, label):
        fields = self.editable()
        direct = [n for n in fields if self.has(label, n)]
        if len(direct) == 1:
            return direct[0]
        for label_node in self.find(label):
            for parent in self.ancestors(label_node):
                candidates = self.editable(parent)
                if len(candidates) == 1:
                    return candidates[0]
                if len(candidates) > 1:
                    break
        return None

    def profile_card(self, title):
        cards = []
        for node in self.find(title):
            for parent in self.ancestors(node):
                if all(self.has(action, parent) for action in ("使用", "编辑", "删除")):
                    # A whole list is not a card: exactly one of each contextual action is required.
                    if all(len(self.find(action, parent)) == 1 for action in ("使用", "编辑", "删除")):
                        cards.append(parent)
                    break
        return cards[0] if len({id(card) for card in cards}) == 1 else None

    def source_scope(self):
        # The smallest title/close ancestor is the dialog, even when 本机鲸歌
        # has scrolled out of view. Never use the background drawer as the list.
        for node in self.find("选择对话来源"):
            for parent in self.ancestors(node):
                if len(self.find("选择对话来源", parent)) == 1 and len(self.find("关闭", parent)) == 1:
                    return parent
        return None

    def source_list_scope(self):
        dialog = self.source_scope()
        if dialog is None:
            return None
        candidates = [n for n in dialog.iter("node") if self.visible(n) and n.get("scrollable") == "true"]
        if len(candidates) == 1:
            return candidates[0]
        if candidates:
            return None
        # Some accessibility trees omit scroll actions when this short list
        # already fits. Its body still contains both endpoint rows and Manage.
        for node in self.find("本机鲸歌", dialog):
            for parent in self.ancestors(node):
                if parent is dialog:
                    break
                if self.has("管理远程电脑", parent) and not self.has("选择对话来源", parent) and not self.has("关闭", parent):
                    return parent
        return None

    def source_row(self, title, url=None):
        scope = self.source_list_scope()
        if scope is None:
            return None
        rows = []
        for node in self.find(title, scope):
            for parent in self.ancestors(node):
                if parent is scope:
                    break
                if parent.get("clickable") == "true":
                    if url is None or self.has(url, parent):
                        rows.append(parent)
                    break
        return rows[0] if len({id(row) for row in rows}) == 1 else None

    def source_selected(self, title, url=None):
        row = self.source_row(title, url)
        return row is not None and self.has("使用中", row)

    def selected_source_row(self):
        scope = self.source_list_scope()
        if scope is None:
            return None
        rows = []
        for node in self.find("使用中", scope):
            for parent in self.ancestors(node):
                if parent is scope:
                    break
                if parent.get("clickable") == "true":
                    rows.append(parent)
                    break
        return rows[0] if len({id(row) for row in rows}) == 1 else None

    def drawer_panel(self):
        for node in self.find("切换本机或远程"):
            for parent in self.ancestors(node):
                if all(self.has(label, parent) for label in ("新对话", "设置", "插件")):
                    x1, _, x2, _ = bounds(parent)
                    if x1 >= 0 and x2 < self.width-24:
                        return parent
                    break
        return None

    def chat_history_scope(self):
        fields = self.editable()
        if len(fields) != 1 or self.source_scope() is not None:
            return None
        composer_top = bounds(fields[0])[1]
        candidates = [n for n in self.nodes if self.visible(n) and n.get("scrollable") == "true"
                      and bounds(n)[3] <= composer_top and not self.editable(n)]
        if candidates:
            return max(candidates, key=lambda n: (bounds(n)[2]-bounds(n)[0])*(bounds(n)[3]-bounds(n)[1]))
        # A short LazyColumn has no scroll action. Its history container still
        # lies above the composer and contains the known fixture message text.
        anchors = [n for n in self.nodes if self.visible(n) and bounds(n)[3] <= composer_top and
                   any(value.startswith("M86H_UI_") or re.fullmatch(r"[AB] 已处理第 \d+ 条输入", value)
                       for value in labels(n))]
        regions = []
        for anchor in anchors:
            for parent in self.ancestors(anchor):
                if bounds(parent)[3] <= composer_top and not self.editable(parent) and self.visible(parent):
                    regions.append(parent)
        return max(regions, key=lambda n: (bounds(n)[2]-bounds(n)[0])*(bounds(n)[3]-bounds(n)[1]), default=None)

    def dialog(self, title, body_fragment=None):
        for node in self.find(title):
            for parent in self.ancestors(node):
                if self.has("取消", parent) and self.has("删除", parent) and (
                    body_fragment is None or self.has(body_fragment, parent, contains=True)
                ):
                    return parent
        return None


class UiCheck:
    def __init__(self, args):
        self.args = args
        stamp = datetime.now().astimezone()
        self.run_id = stamp.strftime("%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:4]
        self.started_at = stamp.isoformat()
        self.names = {backend: f"m86h-ui-{self.run_id}-{backend.upper()}" for backend in ("a", "b")}
        self.output = args.output or args.root / "docs" / f"m86h-ui-check-{self.run_id}.json"
        self.shots_dir = args.shots_dir or args.root / "docs" / "shots"
        self.device_xml = f"/sdcard/Download/m86h-ui-{self.run_id}.xml"
        self.checks = []
        self.shots = []
        self.saved_attempts = set()
        self.removed = set()
        self.sent_attempts = set()
        self.sent_markers = {}
        self.sent_evidence = {}
        self.original_fixture_config = {}
        self.original_source = dict(captured=False, selectedKind="unknown")
        self.original_active_restoration = "not_attempted_no_fixture_profile_selected"
        self.fixture_selection_attempted = False
        self.profile_metadata_corrupt = False
        self.xml_dump_attempted = False
        self.xml_cleanup_removed = False
        self.last_selected = None
        self.last_remote_confirmed = None
        self.stage = "initializing"
        self.completed = False
        self.cleanup_errors = []
        self.device_touched = False
        self.safe_to_navigate = False

    def adb(self, *args):
        result = subprocess.run([str(self.args.adb), "-s", self.args.device, *map(str, args)],
                                capture_output=True, timeout=35)
        if result.returncode:
            # stderr can contain unrelated device data; only the operation is reported.
            raise CheckFailure("adb_failed:" + str(args[0] if args else "unknown"))
        return result.stdout

    def tree(self):
        self.xml_dump_attempted = True
        self.adb("shell", "uiautomator", "dump", self.device_xml)
        return Tree(self.adb("shell", "cat", self.device_xml))

    def record(self, identity, passed, **evidence):
        self.checks.append(dict(id=identity, passed=bool(passed), **evidence))
        print(("PASS " if passed else "FAIL ") + identity, flush=True)
        self.publish()

    def require(self, identity, ok, **evidence):
        self.record(identity, ok, **evidence)
        if not ok:
            raise CheckFailure(identity)

    def wait(self, identity, predicate, timeout=25):
        deadline = time.monotonic() + timeout
        while True:
            value = predicate()
            if value:
                return value
            if time.monotonic() >= deadline:
                raise CheckFailure("timeout:" + identity)
            time.sleep(0.25)

    def tap_node(self, tree, node, require_enabled=False):
        target = node
        for parent in tree.ancestors(node):
            if parent.get("enabled") == "false":
                raise CheckFailure("ui_target_disabled")
            if parent.get("clickable") == "true":
                target = parent
                break
        else:
            if require_enabled:
                raise CheckFailure("ui_target_not_actionable")
        x1, y1, x2, y2 = bounds(target)
        if not tree.visible(target):
            raise CheckFailure("ui_target_offscreen")
        self.device_touched = True
        self.adb("shell", "input", "tap", (x1+x2)//2, (y1+y2)//2)
        time.sleep(0.35)

    def tap(self, label, scope=None, require_enabled=False):
        tree = self.tree()
        container = scope(tree) if scope else None
        self.tap_node(tree, tree.unique(label, container), require_enabled)

    def back(self):
        self.device_touched = True
        self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.35)

    def scroll(self, direction, tree=None, scope=None):
        tree = tree or self.tree()
        candidates = [node for node in (tree.nodes if scope is None else scope.iter("node"))
                      if tree.visible(node) and node.get("scrollable") == "true"]
        if candidates:
            region = max(candidates, key=lambda n: (bounds(n)[2]-bounds(n)[0])*(bounds(n)[3]-bounds(n)[1]))
            x1, y1, x2, y2 = bounds(region)
        elif scope is not None:
            x1, y1, x2, y2 = bounds(scope)
        else:
            x1, y1, x2, y2 = 0, int(tree.height*0.16), tree.width, int(tree.height*0.91)
        x = (x1+x2)//2
        upper = int(y1+(y2-y1)*0.23)
        lower = int(y1+(y2-y1)*0.80)
        self.adb("shell", "input", "swipe", x, lower if direction == "down" else upper,
                 x, upper if direction == "down" else lower, 420)
        time.sleep(0.3)

    def scroll_find(self, getter, identity, max_swipes=18, directions=("down", "up"), scope_getter=None):
        # Scope is resolved from every fresh tree; never reuse a stale dialog node.
        for direction in directions:
            previous = None
            repeats = 0
            for _ in range(max_swipes):
                tree = self.tree()
                scope = scope_getter(tree) if scope_getter else None
                if scope_getter and scope is None:
                    raise CheckFailure("missing_scroll_scope:" + identity)
                found = getter(tree)
                if found is not None:
                    return tree, found
                nodes = tree.nodes if scope is None else scope.iter("node")
                signature = tuple((n.get("text", ""), n.get("content-desc", ""), n.get("bounds", "")) for n in nodes)
                repeats = repeats + 1 if signature == previous else 0
                if repeats >= 2:
                    break
                previous = signature
                self.scroll(direction, tree, scope)
        raise CheckFailure("missing_ui:" + identity)

    def source_row(self, title, url=None):
        return self.scroll_find(lambda t: t.source_row(title, url), "source_row:" + title,
                                max_swipes=40, scope_getter=lambda t: t.source_list_scope())

    def capture_original_source(self):
        self.source_dialog()
        tree, row = self.scroll_find(lambda t: t.selected_source_row(), "original_selected_source",
                                     max_swipes=40, scope_getter=lambda t: t.source_list_scope())
        values = list(dict.fromkeys(value.strip() for node in row.iter("node") for value in labels(node)
                                    for value in value.splitlines() if value.strip() and value.strip() != "使用中"))
        if tree.has("本机鲸歌", row) and not any(re.match(r"https?://", value) for value in values):
            self.original_source = dict(captured=True, selectedKind="local")
        else:
            urls = [value for value in values if re.match(r"https?://", value)]
            names = [value for value in values if value not in urls]
            if len(urls) != 1 or len(names) != 1:
                raise CheckFailure("ambiguous_original_selected_source_row")
            self.original_source = dict(captured=True, selectedKind="remote", name=names[0], baseUrl=urls[0])
        self.record("original_source_captured_from_selected_ui_row", True,
                    selectedKind=self.original_source["selectedKind"])
        self.tap_node(tree, tree.unique("关闭", tree.source_scope()))

    def note_fixture_selection_attempt(self):
        self.fixture_selection_attempted = True
        self.original_active_restoration = "not_restored_normal_ui_selection_connects_user_endpoint"

    def guard_existing_modal(self, tree):
        # Back on a real user's ask dialog would submit skipped answers. Refuse that route.
        forbidden = ("允许一次", "总是允许", "其他回答（可留空）", "停止本轮")
        if any(tree.has(label) for label in forbidden):
            raise CheckFailure("existing_human_question_or_approval_blocks_ui")

    def ensure_drawer(self):
        for _ in range(8):
            tree = self.tree()
            self.guard_existing_modal(tree)
            if tree.has("选择对话来源"):
                return tree
            if tree.has("切换本机或远程"):
                return tree
            for label in ("打开侧边栏", "菜单"):
                if tree.has(label):
                    self.tap_node(tree, tree.unique(label))
                    break
            else:
                # Only traverse recognizable normal back navigation. Never dismiss unknown dialogs.
                if tree.has("返回"):
                    self.tap_node(tree, tree.unique("返回"))
                elif tree.has("设置") and not tree.has("添加远程电脑") and not tree.has("编辑远程电脑"):
                    self.back()
                else:
                    raise CheckFailure("cannot_reach_normal_drawer_safely")
                continue
            time.sleep(0.3)
        raise CheckFailure("drawer_not_open")

    def source_dialog(self):
        tree = self.ensure_drawer()
        if not tree.has("选择对话来源"):
            self.tap_node(tree, tree.unique("切换本机或远程"))
        return self.wait("source_dialog", lambda: (t if (t := self.tree()).source_list_scope() is not None else None))

    def close_drawer_to_chat(self):
        for attempt in range(5):
            tree = self.tree()
            self.guard_existing_modal(tree)
            if tree.has("选择对话来源"):
                scope = tree.source_scope()
                if scope is None:
                    raise CheckFailure("ambiguous_source_dialog_close")
                self.tap_node(tree, tree.unique("关闭", scope))
                continue
            if tree.has("搜索会话") and not tree.has("切换本机或远程"):
                return
            # A wide permanent drawer is deliberately open. In the compact
            # layout require its identity to disappear, not just a background toolbar.
            panel = tree.drawer_panel()
            if tree.has("搜索会话") and not tree.has("打开侧边栏") and panel is not None and bounds(panel)[2] <= tree.width*0.65:
                return
            if not tree.has("切换本机或远程"):
                raise CheckFailure("cannot_identify_drawer_to_close")
            if attempt % 2 == 0:
                self.back()
                continue
            panel = tree.drawer_panel()
            if panel is None:
                raise CheckFailure("cannot_identify_drawer_scrim")
            x1, y1, x2, y2 = bounds(panel)
            # One bounded fallback tap in the app's measured scrim, never a
            # guessed location in the drawer's actions or an unknown dialog.
            self.device_touched = True
            self.adb("shell", "input", "tap", (x2+tree.width)//2, (y1+y2)//2)
            time.sleep(0.35)
        raise CheckFailure("drawer_did_not_close_after_bounded_retry")

    def select_local(self):
        self.source_dialog()
        tree, row = self.source_row("本机鲸歌")
        self.tap_node(tree, row, require_enabled=True)
        self.last_selected = None
        self.last_remote_confirmed = None
        self.wait("local_chat_toolbar", lambda: self.tree().has("搜索会话"))
        self.source_dialog()
        tree, _ = self.source_row("本机鲸歌")
        self.require("local_source_selected_exact_row_" + str(len(self.checks)), tree.source_selected("本机鲸歌"))
        self.tap_node(tree, tree.unique("关闭", tree.source_scope()))
        tree = self.ensure_drawer()
        self.require("local_drawer_identity_" + str(len(self.checks)), tree.has("本机鲸歌") and
                     not any(tree.has("远程 · " + title) for title in self.names.values()))
        self.close_drawer_to_chat()

    def manager(self):
        tree = self.tree()
        if tree.has("远程电脑") and tree.has("添加") and not tree.has("选择对话来源"):
            return tree
        self.source_dialog()
        tree, row = self.source_row("管理远程电脑")
        self.tap_node(tree, row, require_enabled=True)
        return self.wait("remote_manager", lambda: (t if (t := self.tree()).has("远程电脑") and t.has("添加") else None))

    def guard_profile_metadata(self):
        # Once seen, corruption stays a save blocker for this entire run. A later
        # UI redraw must not turn the test's Add/Save into metadata recovery.
        if self.profile_metadata_corrupt:
            raise CheckFailure("refusing_fixture_save_with_corrupt_profile_metadata")
        for direction in ("down", "up"):
            previous = None
            reached_end = False
            for _ in range(40):
                tree = self.tree()
                if tree.has("远程配置清单损坏，原数据已保留", contains=True):
                    self.profile_metadata_corrupt = True
                    raise CheckFailure("refusing_fixture_save_with_corrupt_profile_metadata")
                signature = tuple((n.get("text", ""), n.get("bounds", "")) for n in tree.nodes)
                if signature == previous:
                    reached_end = True
                    break
                previous = signature
                self.scroll(direction, tree)
            if not reached_end:
                raise CheckFailure("profile_metadata_guard_scan_incomplete")

    def add_profile(self, backend):
        title = self.names[backend]
        self.stage = "add_" + backend
        self.manager()
        self.guard_profile_metadata()
        self.tap("添加")
        self.scroll_find(lambda t: t.find("添加远程电脑")[0] if t.has("添加远程电脑") else None,
                         "add_remote_editor")
        for label, value in (("名称", title), ("地址", f"{FIXTURE_ROOT}/{backend}"), ("令牌", PUBLIC_TOKENS[backend])):
            tree, field = self.scroll_find(lambda t, label=label: t.field(label), "new_profile_" + label)
            # New form only. No existing profile's Edit action is ever used.
            self.tap_node(tree, field)
            if not re.fullmatch(r"[A-Za-z0-9_:/.-]+", value):
                raise CheckFailure("unexpected_non_ascii_fixture_input")
            self.adb("shell", "input", "text", value)
            time.sleep(0.2)
            self.back()  # This field was just focused; close its keyboard before scrolling.
        self.guard_profile_metadata()
        tree, save = self.scroll_find(lambda t: t.find("保存")[0] if t.has("保存") else None, "new_profile_save")
        if self.profile_metadata_corrupt or tree.has("远程配置清单损坏，原数据已保留", contains=True):
            self.profile_metadata_corrupt = True
            raise CheckFailure("refusing_fixture_save_with_corrupt_profile_metadata")
        self.saved_attempts.add(backend)  # Track BEFORE tapping, even if adb acknowledgement is lost.
        self.tap_node(tree, save, require_enabled=True)
        self.wait("editor_closed", lambda: not self.tree().has("添加远程电脑"))
        tree, card = self.scroll_find(lambda t: t.profile_card(title), "saved_fixture_profile_" + backend)
        self.require("ui_profile_" + backend + "_saved", tree.has(title, card) and
                     tree.has(f"{FIXTURE_ROOT}/{backend}", card) and tree.has("已保存令牌", card))

    def use_profile_button(self, backend):
        self.stage = "use_" + backend
        self.manager()
        tree, card = self.scroll_find(lambda t: t.profile_card(self.names[backend]), "use_fixture_profile_" + backend)
        self.note_fixture_selection_attempt()
        self.tap_node(tree, tree.unique("使用", card), require_enabled=True)
        self.last_selected = backend
        self.wait_remote_chat(backend)
        self.require("ui_use_" + backend + "_returns_shared_chat", True)

    def is_remote_chat(self, tree, backend):
        return (tree.has(self.names[backend], contains=True) and tree.has(MODEL[backend], contains=True)
                and tree.has("搜索会话") and tree.has("新对话") and tree.has("全屏编辑")
                and len(tree.editable()) == 1 and not tree.has("选择对话来源")
                and not (tree.has("远程电脑") and tree.has("添加")))

    def wait_remote_chat(self, backend):
        return self.wait("shared_remote_chat_" + backend,
                         lambda: (t if self.is_remote_chat(t := self.tree(), backend) else None), timeout=35)

    def assert_remote_drawer(self, backend, screenshot=None):
        tree = self.ensure_drawer()
        self.require("remote_" + backend + "_drawer_identity_" + str(len(self.checks)),
                     tree.has("远程 · " + self.names[backend]) and tree.has("当前远程") and
                     all(not tree.has(label) for label in DRAWER_FORBIDDEN))
        self.last_remote_confirmed = backend
        if screenshot:
            self.shot(screenshot, backend, "drawer")
        self.close_drawer_to_chat()
        return self.wait_remote_chat(backend)

    def assert_remote_controls(self, backend):
        tree = self.wait_remote_chat(backend)
        self.require("remote_" + backend + "_hides_local_controls",
                     all(not tree.has(label) for label in REMOTE_FORBIDDEN))
        fields = tree.editable()
        composer_top = bounds(fields[0])[1]
        composer_labels = [value.strip() for node in tree.nodes if tree.visible(node) and bounds(node)[1] >= composer_top
                           for value in labels(node)]
        self.require("remote_" + backend + "_context_meter_label_hidden",
                     not any(re.fullmatch(r"(?:\d{1,3}%|—)", value) for value in composer_labels),
                     canvasVisualRequiresReadImage=True)
        # The model remains visible as read-only metadata; tapping it must not open local settings.
        model_nodes = tree.find(MODEL[backend])
        self.require("remote_" + backend + "_readonly_model_label_present", bool(model_nodes))
        model = max(model_nodes, key=lambda node: bounds(node)[1])
        x1, y1, x2, y2 = bounds(model)
        self.adb("shell", "input", "tap", (x1+x2)//2, (y1+y2)//2)
        time.sleep(0.35)
        tree = self.tree()
        self.require("remote_" + backend + "_model_does_not_open_local_settings",
                     self.is_remote_chat(tree, backend) and not any(tree.has(label) for label in
                     ("本会话使用的模型", "打开模型配置", "模型支持识图")))

    def fixture(self, backend=None, path="/api/debug/state", body=None):
        url = FIXTURE_ROOT + ("/" + backend if backend else "") + path
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = {} if backend is None else {"Authorization": "Bearer " + PUBLIC_TOKENS[backend]}
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, headers=headers, method="GET" if data is None else "POST")
        with urllib.request.urlopen(request, timeout=6) as answer:
            return json.load(answer)

    def fixture_state(self, backend):
        return self.fixture()[backend]

    def prepare_fixture(self):
        for backend in ("a", "b"):
            state = self.fixture_state(backend)
            self.require("fixture_" + backend + "_idle_before_ui", all(not s["running"] for s in state["sessions"].values()))
            self.original_fixture_config[backend] = state["config"]
            self.fixture(backend, "/api/debug/control", dict(listDelayMs=0, createDelayMs=0, snapshotDelayMs=0,
                         promptAckDelayMs=0, eventDelayMs=50, toolDelayMs=500, scenario="simple", promptMode="normal"))

    def settled_fixture_state(self, backend, before):
        state = self.fixture_state(backend)
        requests = state["requests"][len(before["requests"]):]
        if not requests or any(item["running"] for item in state["sessions"].values()):
            return None
        request = requests[-1]
        session = state["sessions"].get(request["sessionId"])
        if session is None:
            return None
        events = [record["event"] for record in session["records"] if record["type"] == "event"]
        if not events or events[-1]["type"] != "turn/end" or not any(
            event["type"] == "user/message" and event["data"].get("source", {}).get("rpcId") == request["requestId"]
            for event in events
        ):
            return None
        return state

    def prompt_evidence(self, backend, before, marker, state):
        requests = state["requests"][len(before["requests"]):]
        self.require("remote_" + backend + "_exactly_one_ui_prompt", state["promptCalls"] == before["promptCalls"]+1 and
                     len(requests) == 1 and requests[0]["content"] == [dict(type="text", text=marker)] and
                     requests[0]["mode"] == "queue")
        request = requests[0]
        session_id, request_id = request["sessionId"], request["requestId"]
        self.require("remote_" + backend + "_fixture_request_identity_present",
                     bool(session_id) and bool(request_id) and session_id in state["sessions"])
        events = [record["event"] for record in state["sessions"][session_id]["records"] if record["type"] == "event"]
        users = [i for i, event in enumerate(events) if event["type"] == "user/message" and
                 event["data"].get("source", {}).get("rpcId") == request_id]
        self.require("remote_" + backend + "_durable_user_matches_request", len(users) == 1 and
                     events[users[0]]["data"]["content"] == request["content"])
        user_index = users[0]
        starts = [i for i in range(user_index) if events[i]["type"] == "turn/start"]
        self.require("remote_" + backend + "_durable_turn_start_present", bool(starts))
        start = starts[-1]
        ends = [i for i in range(user_index+1, len(events)) if events[i]["type"] == "turn/end"]
        self.require("remote_" + backend + "_durable_turn_end_present", bool(ends))
        end = ends[0]
        turn = events[start]["data"]["turn"]
        window = events[start+1:end]
        replies = [event for event in window if event["type"] == "assistant/message"]
        tools = [event for event in window if event["type"] == "tool/call" and event["data"].get("name") == "pwsh"]
        self.require("remote_" + backend + "_same_turn_reply_and_tool_present", len(replies) == 1 and len(tools) == 1 and
                     events[end]["data"]["turn"] == turn and
                     not any(event["type"] == "turn/start" for event in window))
        reply = replies[0]["data"]["message"]
        reply_text = "".join(part["text"] for part in reply["content"] if part["type"] == "text")
        call_id = tools[0]["data"]["callId"]
        results = [event["data"]["message"] for event in window if event["type"] == "tool/result" and
                   event["data"]["message"].get("toolCallId") == call_id]
        expected_reply = f"{backend.upper()} 已处理第 {turn} 条输入"
        expected_tool = f"{backend.upper()} tool done"
        self.require("remote_" + backend + "_exact_fixture_turn_content", reply_text == expected_reply and len(results) == 1 and
                     results[0]["content"] == [dict(type="text", text=expected_tool)] and not results[0].get("isError", False))
        evidence = dict(requestId=request_id, sessionId=session_id, turn=turn, userSeq=events[user_index]["seq"],
                        replyId=reply["id"], replySeq=replies[0]["seq"], replyText=reply_text,
                        callId=call_id, toolResult=expected_tool, marker=marker)
        self.sent_evidence[backend] = evidence
        self.record("remote_" + backend + "_request_correlated_to_durable_turn", True,
                    **{key: evidence[key] for key in ("requestId", "sessionId", "turn", "userSeq", "replySeq", "callId")})
        return evidence

    def assert_other_fixture_hidden(self, backend):
        forbidden = [value for other, marker in self.sent_markers.items() if other != backend
                     for value in (marker, self.sent_evidence[other]["replyText"], self.sent_evidence[other]["toolResult"])]
        if not forbidden:
            return
        # LazyColumn only composes visible rows. Scan its full history in both
        # directions; a single bottom-of-chat absence check would miss stale A.
        for direction in ("down", "up"):
            previous = None
            repeats = 0
            for _ in range(40):
                tree = self.tree()
                scope = tree.chat_history_scope()
                if not self.is_remote_chat(tree, backend) or scope is None:
                    raise CheckFailure("missing_remote_history_scope:" + backend)
                if any(tree.has(value, scope, contains=True) for value in forbidden):
                    raise CheckFailure("other_fixture_content_visible_in_" + backend)
                signature = tuple((n.get("text", ""), n.get("content-desc", ""), n.get("bounds", "")) for n in scope.iter("node"))
                repeats = repeats+1 if signature == previous else 0
                if repeats >= 2:
                    break
                previous = signature
                self.scroll(direction, tree, scope)
            else:
                raise CheckFailure("remote_history_scan_incomplete:" + backend)
        self.require("remote_" + backend + "_other_fixture_history_hidden", True,
                     checkedSources=[other for other in self.sent_markers if other != backend])

    def find_chat_text(self, backend, text, identity):
        return self.scroll_find(lambda t: t.find(text, t.chat_history_scope())[0]
                                if self.is_remote_chat(t, backend) and t.chat_history_scope() is not None and
                                t.has(text, t.chat_history_scope()) else None,
                                identity, max_swipes=40, scope_getter=lambda t: t.chat_history_scope())

    def submit_ui(self, backend):
        self.stage = "send_" + backend
        self.assert_remote_drawer(backend)
        self.assert_remote_controls(backend)
        tree = self.wait_remote_chat(backend)
        fields = tree.editable()
        self.require("remote_" + backend + "_composer_empty_before_input", len(fields) == 1 and not fields[0].get("text", ""))
        marker = f"M86H_UI_{backend.upper()}_{self.run_id}"
        before = self.fixture_state(backend)
        self.tap_node(tree, fields[0])
        self.adb("shell", "input", "text", marker)
        self.back()
        tree = self.wait_remote_chat(backend)
        self.require("remote_" + backend + "_typed_fixture_input", tree.editable()[0].get("text") == marker)
        if backend in self.sent_attempts or self.last_remote_confirmed != backend:
            raise CheckFailure("refusing_duplicate_or_unconfirmed_submission")
        self.sent_attempts.add(backend)  # Never retry a send, including an adb acknowledgement failure.
        self.sent_markers[backend] = marker
        self.tap_node(tree, tree.unique("发送"), require_enabled=True)
        self.wait("fixture_" + backend + "_one_prompt", lambda: self.fixture_state(backend)["promptCalls"] >= before["promptCalls"]+1)
        state = self.wait("fixture_" + backend + "_idle_after_prompt", lambda: self.settled_fixture_state(backend, before))
        evidence = self.prompt_evidence(backend, before, marker, state)
        self.wait("ui_" + backend + "_exact_reply", lambda: (t if self.is_remote_chat(t := self.tree(), backend)
                  and t.has(evidence["replyText"]) and not t.has("停止") and not t.editable()[0].get("text", "") else None))
        tree, _ = self.find_chat_text(backend, marker, "durable_user_echo_" + backend)
        self.require("remote_" + backend + "_durable_ui_marker_visible", not tree.editable()[0].get("text", "") and
                     tree.has(marker, tree.chat_history_scope()), requestId=evidence["requestId"], userSeq=evidence["userSeq"])
        self.assert_other_fixture_hidden(backend)
        tree, _ = self.find_chat_text(backend, evidence["replyText"], "exact_reply_for_screenshot_" + backend)
        self.require("remote_" + backend + "_exact_durable_ui_reply_visible", tree.has(evidence["replyText"], tree.chat_history_scope()),
                     replySeq=evidence["replySeq"])
        self.shot(backend + "-shared-chat", backend, "chat", required=evidence["replyText"])
        return marker

    def expand_tool(self, backend):
        self.stage = "tool_" + backend
        self.wait_remote_chat(backend)
        tree = self.tree()
        if not tree.has("pwsh"):
            tree, node = self.scroll_find(lambda t: t.find("展开工作步骤")[0] if t.has("展开工作步骤") else None,
                                          "expand_fixture_process_" + backend, max_swipes=5)
            self.tap_node(tree, node)
        tree, node = self.scroll_find(lambda t: t.find("pwsh")[0] if t.has("pwsh") else None,
                                      "fixture_tool_card_" + backend, max_swipes=5)
        if not tree.has("结果"):
            self.tap_node(tree, node)
        expected_tool = self.sent_evidence[backend]["toolResult"]
        self.find_chat_text(backend, expected_tool, "fixture_tool_result_" + backend)
        self.require("remote_" + backend + "_tool_card_and_result_visible", self.tree().has(expected_tool),
                     requestId=self.sent_evidence[backend]["requestId"], callId=self.sent_evidence[backend]["callId"])
        self.assert_other_fixture_hidden(backend)
        self.find_chat_text(backend, expected_tool, "fixture_tool_result_after_isolation_scan_" + backend)
        # Reconfirm source after expanding the tool, then return to the still-expanded card.
        self.assert_remote_drawer(backend)
        self.shot(backend + "-tool-card", backend, "chat", required=expected_tool)

    def switch_source(self, backend):
        self.stage = "source_" + (backend or "local")
        selected = self.last_selected
        self.source_dialog()
        if selected:
            # Each row can require a different list position; never demand all
            # user and fixture profiles fit in one accessibility snapshot.
            for candidate in (None, "a", "b"):
                title = self.names[candidate] if candidate else "本机鲸歌"
                self.source_row(title, f"{FIXTURE_ROOT}/{candidate}" if candidate else None)
            tree, _ = self.source_row(self.names[selected], f"{FIXTURE_ROOT}/{selected}")
            self.require("source_picker_from_" + selected + "_selected_exact_row",
                         tree.source_selected(self.names[selected], f"{FIXTURE_ROOT}/{selected}"))
            self.require("source_picker_from_" + selected + "_has_both_fixture_profiles", True)
            self.shot(selected + "-source-picker", selected, "source")
        title = self.names[backend] if backend else "本机鲸歌"
        tree, row = self.source_row(title, f"{FIXTURE_ROOT}/{backend}" if backend else None)
        if backend:
            self.note_fixture_selection_attempt()
        self.tap_node(tree, row, require_enabled=True)
        self.last_selected = backend
        if backend:
            self.wait_remote_chat(backend)
            self.assert_remote_drawer(backend, backend + "-drawer")
        else:
            self.last_remote_confirmed = None
            self.wait("local_after_source_selection", lambda: self.tree().has("搜索会话"))
            self.select_local()

    def shot(self, suffix, backend=None, location="chat", required=None):
        tree = self.tree()
        if backend:
            if location == "chat":
                ok = self.is_remote_chat(tree, backend) and self.last_remote_confirmed == backend
            elif location == "drawer":
                ok = tree.has("远程 · " + self.names[backend]) and self.last_remote_confirmed == backend
            else:
                ok = tree.source_selected(self.names[backend], f"{FIXTURE_ROOT}/{backend}") and self.last_remote_confirmed == backend
            if not ok or (required is not None and not tree.has(required)):
                raise CheckFailure("refusing_unconfirmed_remote_screenshot:" + suffix)
        path = self.shots_dir / f"m86h-ui-{self.run_id}-{suffix}.png"
        self.shots_dir.mkdir(parents=True, exist_ok=True)
        path.write_bytes(self.adb("exec-out", "screencap", "-p"))
        self.shots.append(dict(path=str(path), selectedFixture=backend, location=location, requiresReadImage=True))
        self.publish()

    def delete_own_profile(self, backend):
        if backend not in self.saved_attempts:
            return
        title = self.names[backend]
        if not title.startswith("m86h-ui-" + self.run_id + "-"):
            raise CheckFailure("refusing_non_owned_profile_deletion")
        self.stage = "cleanup_" + backend
        self.manager()
        tree, card = self.scroll_find(lambda t: t.profile_card(title), "cleanup_exact_fixture_card_" + backend)
        if not tree.has(title, card) or not tree.has(f"{FIXTURE_ROOT}/{backend}", card):
            raise CheckFailure("refusing_ambiguous_profile_card_deletion")
        self.tap_node(tree, tree.unique("删除", card), require_enabled=True)
        fragment = f"删除「{title}」的手机连接配置和令牌。"
        tree = self.wait("exact_fixture_delete_confirmation", lambda: (t if (t := self.tree()).dialog(
            "删除这台远程电脑？", fragment) is not None else None))
        dialog = tree.dialog("删除这台远程电脑？", fragment)
        if dialog is None or not tree.has(fragment, dialog, contains=True):
            raise CheckFailure("refusing_non_owned_confirmation")
        self.tap_node(tree, tree.unique("删除", dialog), require_enabled=True)
        self.wait("fixture_profile_deleted_" + backend, lambda: not self.tree().has("删除这台远程电脑？"))
        for direction in ("down", "up"):
            previous = None
            repeats = 0
            for _ in range(40):
                tree = self.tree()
                if tree.has(title):
                    raise CheckFailure("deleted_fixture_title_still_present:" + backend)
                signature = tuple((n.get("text", ""), n.get("bounds", "")) for n in tree.nodes)
                repeats = repeats+1 if signature == previous else 0
                if repeats >= 2:
                    break
                previous = signature
                self.scroll(direction, tree)
            else:
                raise CheckFailure("deleted_fixture_title_scan_incomplete:" + backend)
        self.require("ui_fixture_" + backend + "_profile_deleted_by_exact_title", True)
        self.removed.add(backend)

    def run(self):
        self.stage = "fixture_preflight"
        self.prepare_fixture()
        self.stage = "normal_ui_preflight"
        self.adb("shell", "am", "start", "-f", "0x20000000", "-n", PKG + "/.MainActivity")
        time.sleep(0.7)
        tree = self.tree()
        self.guard_existing_modal(tree)
        if tree.has("编辑远程电脑") or tree.has("添加远程电脑"):
            raise CheckFailure("existing_profile_editor_blocks_ui")
        self.safe_to_navigate = True
        self.capture_original_source()
        self.select_local()
        self.add_profile("a")
        self.add_profile("b")
        self.use_profile_button("a")
        self.assert_remote_drawer("a", "a-drawer")
        self.submit_ui("a")
        self.expand_tool("a")
        self.switch_source("b")
        self.submit_ui("b")
        self.expand_tool("b")
        self.switch_source(None)
        self.shot("local-before-cleanup")
        self.completed = True

    def cleanup_error(self, stage, error, **evidence):
        self.cleanup_errors.append(dict(stage=stage, error=type(error).__name__,
                                        reason=str(error) if isinstance(error, CheckFailure) else "host_or_ui_operation_failed",
                                        **evidence))

    def remove_own_device_xml(self):
        if not self.xml_dump_attempted:
            return
        expected = f"/sdcard/Download/m86h-ui-{self.run_id}.xml"
        if not re.fullmatch(r"\d{4}-\d{6}-[0-9a-f]{4}", self.run_id) or self.device_xml != expected:
            raise CheckFailure("refusing_non_owned_device_xml_deletion")
        # Verify the exact absolute target before deletion; never glob Download
        # or follow a path supplied by UI text, a report, or an exception.
        resolved = self.adb("shell", "readlink", "-f", "--", expected).decode("utf-8").strip()
        allowed = {expected, expected.replace("/sdcard/", "/storage/emulated/0/", 1)}
        if resolved not in allowed:
            raise CheckFailure("refusing_unexpected_resolved_device_xml_path")
        self.record("device_xml_exact_owned_path_verified", True, path=expected, resolvedPath=resolved)
        self.adb("shell", "rm", "-f", "--", expected)
        exists = self.adb("shell", f"if [ -e '{expected}' ]; then printf present; else printf absent; fi")
        self.xml_cleanup_removed = exists.strip() == b"absent"
        self.require("device_xml_exact_owned_path_removed", self.xml_cleanup_removed)

    def cleanup(self):
        # Independent best-effort stages: a failure never authorizes a broader deletion.
        if self.safe_to_navigate:
            try:
                tree = self.tree()
                if tree.has("添加远程电脑"):
                    self.tap("取消")  # Only this script opened an editor after preflight.
                elif tree.has("删除这台远程电脑？"):
                    self.tap("取消")  # Reopen and verify the exact fixture title afresh below.
                self.select_local()
            except Exception as error:
                self.cleanup_error("switch_local_before_cleanup", error)
            for backend in ("a", "b"):
                if backend not in self.saved_attempts:
                    continue
                try:
                    self.delete_own_profile(backend)
                except Exception as error:
                    self.cleanup_error("delete_" + backend, error, title=self.names[backend])
            try:
                self.select_local()
                self.shot("local-after-cleanup")
            except Exception as error:
                self.cleanup_error("switch_local_after_cleanup", error)
        for backend, config in self.original_fixture_config.items():
            try:
                self.fixture(backend, "/api/debug/control", config)
            except Exception as error:
                self.cleanup_error("restore_fixture_config_" + backend, error)
        try:
            self.remove_own_device_xml()
        except Exception as error:
            self.cleanup_error("remove_exact_owned_device_xml", error)
        if self.saved_attempts:
            self.record("only_two_owned_fixture_profiles_cleaned", self.saved_attempts == self.removed,
                        remainingTitles=[self.names[b] for b in sorted(self.saved_attempts-self.removed)])
        self.publish()

    def publish(self):
        self.output.parent.mkdir(parents=True, exist_ok=True)
        report = dict(fixture="M86-H-normal-UI", version=2, runId=self.run_id, startedAt=self.started_at,
                      completed=self.completed, stage=self.stage,
                      passed=self.completed and not self.cleanup_errors and all(c["passed"] for c in self.checks),
                      checks=self.checks, passedCount=sum(c["passed"] for c in self.checks), total=len(self.checks),
                      fixtureProfileNames=self.names, promptSendAttempts=sorted(self.sent_attempts),
                      promptEvidence=self.sent_evidence,
                      profileCleanup=dict(attempted=sorted(self.saved_attempts), removed=sorted(self.removed), errors=self.cleanup_errors,
                                          corruptMetadataSeen=self.profile_metadata_corrupt),
                      originalSource=self.original_source,
                      originalActiveProfile=dict(fixtureSelectionAttempted=self.fixture_selection_attempted,
                                                 restoration=self.original_active_restoration, restored=False,
                                                 verified=False, userEndpointConnectedForRestore=False),
                      deviceXmlCleanup=dict(attempted=self.xml_dump_attempted, removed=self.xml_cleanup_removed),
                      screenshots=self.shots, screenshotsVerified=False, realDesktopVerified=False,
                      limitations=["Parent must read_image screenshots, including Canvas context-ring absence.",
                                   "Normal UI send covers one fresh remote session per profile; no local prompt is sent.",
                                   "The source checkmark identifies the displayed conversation target, not the persisted activeId when local is selected.",
                                   "Restoring an existing profile through normal UI immediately connects its user endpoint; this script does not do that. Persisted activeId is not restored or verified after fixture selection.",
                                   "Original source name/URL, when captured, come only from its selected UI row; no token or preferences are read."])
        self.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--adb", type=Path, default=Path(r"C:\Android\platform-tools\adb.exe"))
    parser.add_argument("--device", default="192.0.2.10:39905")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path)
    parser.add_argument("--shots-dir", type=Path)
    args = parser.parse_args()
    runner = UiCheck(args)
    try:
        runner.run()
    except Exception as error:
        # No traceback / raw UI dump / adb stderr: those could include unrelated user UI data.
        runner.record("script_failure", False, stage=runner.stage, error=type(error).__name__,
                      reason=str(error) if isinstance(error, CheckFailure) else "host_or_ui_operation_failed")
    finally:
        runner.cleanup()
    print("Report: " + str(runner.output), flush=True)
    if not runner.completed or runner.cleanup_errors or any(not check["passed"] for check in runner.checks):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
