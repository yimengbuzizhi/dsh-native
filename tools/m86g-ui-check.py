"""M86-G real Android UI checks. Authoring/syntax checking this file never invokes adb.

Run explicitly after installing the debug APK and ensuring no user turn is active:
  python tools/m86g-ui-check.py --device 192.0.2.10:39905

Creates one timestamped, permission-free JS LLM fixture (90s on HOLD inputs), and only
new fixture chats. Keeps every plugin/session/artifact. Finally stops only a confirmed
fixture foreground, restores the original queue/steer preference, selects native UI /
model-follow-protocol / debug-provider-off, and disables its fixture plugin. It never
reads preferences, API keys, registry files, existing chat bodies, or session files.
"""
import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

PKG = 'xin.dshaa.nativeapp'
ROOT = Path(__file__).resolve().parents[1]
ADB_DEFAULT = r'C:\Android\platform-tools\adb.exe'


class UiFailure(RuntimeError):
    pass


class Probe:
    def __init__(self, args):
        self.args = args
        self.stamp = datetime.now().strftime('%Y%m%d-%H%M%S-%f')
        self.plugin = 'm86g.ui.fixture.' + self.stamp.replace('-', '')
        self.provider = self.plugin + '.llm'
        self.out = args.out or ROOT / 'docs' / ('m86g-ui-' + self.stamp)
        self.out.mkdir(parents=True, exist_ok=False)
        self.remote = '/sdcard/Download/m86g-ui-' + self.stamp
        self.checks = []
        self.screenshots = []
        self.session_ids = []
        self.original_mode = None
        self.fixture_foreground = False
        self.marker = None
        self.import_attempted = False
        self.previous_whale_enabled = None
        self.cached = []
        self.failures = []
        self.serial = 0
        self.current_input = None
        self.queued_id = None
        self.observations = []
        self.mutated = False

    def adb(self, *parts, timeout=40, allow_failure=False):
        result = subprocess.run([self.args.adb, '-s', self.args.device, *map(str, parts)],
                                capture_output=True, timeout=timeout)
        if result.returncode and not allow_failure:
            # Commands do not contain secrets; avoid dumping arbitrary device stderr/content.
            raise UiFailure('adb_command_failed:' + str(parts[0]) + ':' + str(result.returncode))
        return result.stdout if not allow_failure else (result.returncode, result.stdout)

    def shell(self, *parts, **kwargs):
        # adb shell combines argv into one remote shell command; quote each token explicitly.
        return self.adb('shell', ' '.join(shlex.quote(str(p)) for p in parts), **kwargs)

    def start(self, settle=.22, **extras):
        command = ['am', 'start', '-f', '0x20000000', '-n', PKG + '/.MainActivity']
        for key, value in extras.items():
            command += ['--es', key, str(value)]
        self.shell(*command)
        if settle:
            time.sleep(settle)
        self.cached = []

    def check(self, name, ok):
        self.checks.append({'id': name, 'passed': bool(ok)})
        print(('PASS ' if ok else 'FAIL ') + name, flush=True)
        if not ok:
            raise UiFailure(name)

    def nodes(self):
        xml = self.remote + '.xml'
        self.shell('uiautomator', 'dump', '--compressed', xml, timeout=20)
        raw = self.shell('cat', xml)
        try:
            self.cached = list(ET.fromstring(raw).iter('node'))
        except ET.ParseError as exc:
            raise UiFailure('invalid_ui_hierarchy') from exc
        return self.cached

    @staticmethod
    def bounds(node):
        numbers = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
        if len(numbers) != 4:
            raise UiFailure('ui_node_has_no_bounds')
        return numbers

    @staticmethod
    def matches(node, value, desc=False, contains=False):
        actual = node.get('content-desc' if desc else 'text', '')
        return value in actual if contains else value == actual

    def find(self, value, desc=False, contains=False, current=None):
        candidates = self.nodes() if current is None else current
        return next((n for n in candidates if self.matches(n, value, desc, contains)), None)

    def tap_node(self, node, settle=.12):
        if node is None:
            raise UiFailure('missing_ui_node')
        x1, y1, x2, y2 = self.bounds(node)
        self.shell('input', 'tap', (x1 + x2) // 2, (y1 + y2) // 2)
        if settle:
            time.sleep(settle)
        self.cached = []

    def tap(self, value, desc=False, contains=False, current=None, settle=.12):
        node = self.find(value, desc, contains, current)
        if node is None:
            raise UiFailure('missing_control:' + value)
        self.tap_node(node, settle)

    def back(self):
        self.shell('input', 'keyevent', '4')
        time.sleep(.18)
        self.cached = []

    def wait_nodes(self, name, predicate, timeout=22):
        deadline = time.monotonic() + timeout
        while True:
            current = self.nodes()
            if predicate(current):
                self.check(name, True)
                return current
            if time.monotonic() >= deadline:
                self.check(name, False)
            time.sleep(.18)

    def shot(self, name):
        path = self.out / (name + '.png')
        path.write_bytes(self.adb('exec-out', 'screencap', '-p'))
        self.screenshots.append(path.name)

    def text_exists(self, current, value, contains=False):
        return self.find(value, contains=contains, current=current) is not None

    def has_stop(self, current):
        return any(self.matches(n, '停止', desc=True) or self.matches(n, '停止') for n in current)

    def type_draft(self, text, current=None):
        if not re.fullmatch('[A-Za-z0-9_]+', text):
            raise UiFailure('fixture_input_must_be_ascii')
        candidates = self.nodes() if current is None else current
        edits = [n for n in candidates if n.get('class') == 'android.widget.EditText' and n.get('enabled') == 'true']
        self.check('fixture_composer_editable', len(edits) == 1)
        self.tap_node(edits[0], settle=.05)
        self.shell('input', 'text', text)
        self.back()  # Hide keyboard, keep the fixture draft.

    def dump(self, key, **extras):
        self.serial += 1
        path = self.remote + '-observation-' + str(self.serial) + '.json'
        self.start(**{key: path}, **extras, settle=.05)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            rc, raw = self.shell('cat', path, allow_failure=True)
            if rc == 0:
                try:
                    result = json.loads(raw)
                except (ValueError, UnicodeError):
                    time.sleep(.1)  # File may exist while its one bounded write is in progress.
                    continue
                (self.out / ('observation-' + str(self.serial) + '.json')).write_text(
                    json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
                return result
            time.sleep(.15)
        raise UiFailure('missing_fresh_debug_observation')

    def state(self):
        value = self.dump('dsh_ui_probe')
        self.observations.append(value)
        return value

    def capture_session_id(self):
        result = self.state()
        session_id = result.get('sessionId')
        self.check('new_fixture_session_has_id', result.get('fixtureSession') and isinstance(session_id, str) and bool(session_id))
        if session_id not in self.session_ids:
            self.session_ids.append(session_id)
        return session_id

    def preflight(self):
        state = self.state()
        self.check('all_local_turns_and_writers_idle_before_model_switch', all(state.get(k) == 0 for k in
                   ('activeTurnCount', 'writerCount', 'streamingCount')))
        self.check('no_unsaved_draft_or_attachments_before_fixture', not state.get('hasDraft') and
                   state.get('pendingImageCount') == 0 and state.get('pendingFileCount') == 0)
        self.check('native_shell_selected_before_fixture', state.get('shell') == 'native')
        plugins = self.dump('dsh_dump_plugins')
        self.previous_whale_enabled = 'builtin.shell.whale' in plugins.get('enabled', [])

    def assert_delivery(self, name, delivered, running=False):
        state = self.state()
        if delivered:
            deadline = time.monotonic() + 20
            while state.get('writerCount') != 0 or state.get('activeTurnCount') != 0:
                if time.monotonic() >= deadline:
                    raise UiFailure(name + '_writer_completion_timeout')
                time.sleep(.15)
                state = self.state()
        messages = state.get('messages', [])
        count = sum(m.get('role') == 'user' and m.get('text') == self.current_input for m in messages)
        self.check(name + '_exact_input_delivery_count', count == (1 if delivered else 0) and
                   state.get('delivered', []).count(self.queued_id) == (1 if delivered else 0))
        if running:
            self.check(name + '_hold_still_running', state.get('streaming') and state.get('writerCount') == 1)
        if delivered:
            self.check(name + '_successful_reply_and_completed_turn', not state.get('streaming') and
                       not state.get('errorPresent') and state.get('writerCount') == 0 and
                       bool(state.get('turnEnds')) and state['turnEnds'][-1] == 'completed' and
                       any(m.get('role') == 'assistant' and m.get('text') == 'M86GUI_REPLY_' + self.current_input for m in messages))
        return state

    def make_fixture(self):
        # No network or file access and no credentials: Android has no "llm" manifest permission.
        manifest = {
            'id': self.plugin, 'name': 'M86-G UI slow fixture ' + self.stamp,
            'version': '1.0.0', 'description': 'Local UI acceptance fixture; no network, tools or credentials.',
            'main': 'index.js', 'permissions': [],
        }
        script = '''export function apply(ctx) {
  ctx.llm.register({
    id: PROVIDER_ID,
    label: 'M86-G UI 90s fixture',
    stream: function(request, emit) {
      var texts = [];
      (request.messages || []).forEach(function(message) {
        if (message.role !== 'user') return;
        (message.content || []).forEach(function(block) {
          if (block.type === 'text' && /^M86GUI_[A-Z0-9_]+$/.test(block.text || '')) texts.push(block.text);
        });
      });
      var latest = texts.length ? texts[texts.length - 1] : '';
      if (!latest) throw new Error('M86-G fixture input required');
      emit(/_HOLD$/.test(latest) ? 'M86GUI_PARTIAL' : 'M86GUI_REPLY_' + latest);
      if (/_HOLD$/.test(latest)) {
        return new Promise(function(resolve) {
          setTimeout(function() { resolve({stopReason: 'end_turn', usage: {input: 0, output: 2}}); }, 90000);
        });
      }
      return {stopReason: 'end_turn', usage: {input: 0, output: 2}};
    }
  });
}
'''.replace('PROVIDER_ID', json.dumps(self.provider))
        icon = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="14" fill="#175c7b"/><path d="M15 21h34M15 32h25M15 43h16" stroke="#fff" stroke-width="5" stroke-linecap="round"/></svg>'
        archive = self.out / (self.plugin + '.zip')
        with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED) as package:
            package.writestr('package.json', json.dumps(manifest, ensure_ascii=False))
            package.writestr('index.js', script)
            package.writestr('icon.svg', icon)
            package.writestr('README.md', 'M86-G disposable acceptance fixture. Disable after use; retain data.\n')
        self.check('fixture_requests_no_permissions', manifest['permissions'] == [])
        remote_zip = self.remote + '.zip'
        self.adb('push', str(archive), remote_zip)
        self.import_attempted = True
        self.mutated = True
        self.start(dsh_shell='native', dsh_llm_provider='off', dsh_model='off', dsh_plugin_zip=remote_zip)
        deadline = time.monotonic() + 35
        while True:
            status = self.dump('dsh_dump_plugins')
            if self.plugin in status.get('enabled', []) and self.provider in status.get('llmProviders', []):
                self.check('fixture_installed_and_provider_registered', True)
                break
            if time.monotonic() >= deadline:
                self.check('fixture_installed_and_provider_registered', False)
            time.sleep(.35)
        self.start(dsh_model=self.provider, dsh_llm_provider='off', dsh_open_screen='Behavior', settle=.5)

    def close_drawer(self):
        for _ in range(3):
            current = self.nodes()
            if not any(n.get('content-desc') == '切换本机或远程' for n in current):
                return current
            width = max(self.bounds(n)[2] for n in current)
            height = max(self.bounds(n)[3] for n in current)
            self.shell('input', 'tap', width - 16, height // 2)  # Drawer scrim, outside its 304dp sheet.
            time.sleep(.5)
        raise UiFailure('drawer_scrim_did_not_close')

    def behavior(self, mode, capture_original=False):
        self.start(dsh_shell='native', dsh_open_screen='Behavior', settle=.35)
        current = self.close_drawer()
        if not all(self.text_exists(current, text, contains=True) for text in ('排队发送', '插话发送')):
            # This group follows just two toggles; a short upward swipe handles compact displays.
            size = self.bounds(current[0])
            self.shell('input', 'swipe', size[2] // 2, size[3] * 3 // 4,
                       size[2] // 2, size[3] // 3, 250)
            current = self.nodes()
        self.check('behavior_exposes_both_delivery_modes', all(self.text_exists(current, t, contains=True) for t in ('排队发送', '插话发送')))
        if capture_original:
            self.original_mode = self.selected_behavior(current)
        self.tap('插话发送' if mode == 'steer' else '排队发送', contains=True, current=current)
        current = self.nodes()
        self.check('behavior_selection_indicator_matches_' + mode, self.selected_behavior(current) == mode)
        self.shot('behavior-' + mode)

    def selected_behavior(self, current):
        chosen = self.find('已选择', contains=True, current=current)
        self.check('behavior_has_selected_delivery_mode', chosen is not None)
        # Compose may expose one merged clickable row or separate title/value descendants.
        selected_text = chosen.get('text', '')
        if '插话发送' in selected_text:
            return 'steer'
        if '排队发送' in selected_text:
            return 'queue'
        cy = sum(self.bounds(chosen)[1::2]) / 2
        labels = [(abs(sum(self.bounds(n)[1::2]) / 2 - cy),
                   'steer' if '插话发送' in n.get('text', '') else 'queue')
                  for n in current if any(t in n.get('text', '') for t in ('排队发送', '插话发送'))]
        self.check('behavior_selected_row_resolvable', bool(labels))
        return min(labels)[1]

    def hold(self, case, shell='native'):
        # dsh_send always creates a new chat when no dsh_open_session is supplied.
        marker = 'M86GUI_' + self.stamp.replace('-', '') + '_' + case.upper() + '_HOLD'
        self.marker = marker
        self.close_drawer()
        self.start(dsh_shell=shell, dsh_open_screen='chat', dsh_send=marker, settle=.25)
        self.fixture_foreground = True  # Cleanup still requires this exact marker before stopping.
        current = self.wait_nodes(case + '_real_partial_and_stop',
                                  lambda ns: self.text_exists(ns, 'M86GUI_PARTIAL', contains=True) and
                                  self.text_exists(ns, marker, contains=True) and self.has_stop(ns))
        self.fixture_foreground = True
        return current

    def stop_fixture(self, current=None):
        if not self.fixture_foreground:
            return
        current = self.nodes() if current is None else current
        if self.marker is not None and not self.text_exists(current, self.marker, contains=True):
            raise UiFailure('refusing_stop_without_fixture_marker')
        node = self.find('停止', desc=True, current=current)
        if node is None:
            node = self.find('停止', current=current)
        if node is not None:
            self.tap_node(node)
            self.wait_nodes('fixture_stopped', lambda ns: not self.has_stop(ns), timeout=22)
        self.capture_session_id()
        self.fixture_foreground = False
        deadline = time.monotonic() + 20
        while True:
            state = self.state()
            if state.get('writerCount') == 0 and state.get('activeTurnCount') == 0:
                break
            if time.monotonic() > deadline:
                raise UiFailure('fixture_writer_did_not_finish')
            time.sleep(.2)
        # JS promises share one resident executor; Flow cancellation alone can
        # leave its timed callback running. Recreate ONLY our isolated runtime.
        self.start(dsh_plugin_off=self.plugin)
        self.check('fixture_disabled_before_runtime_reset', self.plugin in self.dump('dsh_dump_plugins').get('disabled', []))
        self.start(dsh_plugin_on=self.plugin)
        deadline = time.monotonic() + 20
        while True:
            status = self.dump('dsh_dump_plugins')
            if self.plugin in status.get('enabled', []) and self.provider in status.get('llmProviders', []):
                break
            if time.monotonic() > deadline:
                raise UiFailure('fixture_runtime_reset_registration_timeout')
            time.sleep(.25)

    def queued(self, name, mode, current=None):
        expected = '等待插话' if mode == 'steer' else '排队中'
        current = self.wait_nodes(name + '_queue_visible', lambda ns:
                                  self.text_exists(ns, '待发送 1 条') and self.text_exists(ns, expected, contains=True), timeout=8)
        self.check(name + '_queue_cancel_enabled', any(n.get('enabled') == 'true' and n.get('text') == '取消' for n in current))
        self.shot(name + '-queued')
        state = self.state()
        queue = state.get('queued', [])
        self.check(name + '_exact_pending_entry_and_running_writer', state.get('streaming') and len(queue) == 1 and
                   queue[0].get('text') == self.current_input and queue[0].get('status') == 'pending')
        self.queued_id = queue[0]['id']
        return current

    def draft_and_send(self, name, mode, current):
        self.current_input = 'M86GUI_' + name.upper() + '_INPUT'
        self.type_draft(self.current_input, current)
        current = self.nodes()
        label = '插话发送' if mode == 'steer' else '排队发送'
        self.check(name + '_draft_send_and_stop_coexist', self.text_exists(current, label) and self.has_stop(current))
        self.check(name + '_draft_mode_switch_visible', self.text_exists(current, '切换发送方式'))
        self.shot(name + '-draft-and-stop')
        self.tap(label, current=current)
        return self.queued(name, mode)

    def native_cancel(self):
        current = self.hold('native_cancel')
        current = self.draft_and_send('native_cancel', 'queue', current)
        self.tap('取消', current=current)
        current = self.nodes()
        self.check('native_cancel_removes_queued_item', not self.text_exists(current, '待发送 1 条'))
        self.assert_delivery('native_cancel', False, running=True)
        self.stop_fixture(current)
        self.assert_delivery('native_cancel_after_stop', False)

    def attachments(self):
        current = self.hold('native_attachment')
        self.tap('附件', desc=True, current=current)
        current = self.nodes()
        self.check('attachments_entry_opens_while_generating', self.text_exists(current, '生成中也能添加附件，发送时按当前方式排队或插话。'))
        self.check('attachments_actions_visible_and_enabled', all(any(
            n.get('text') == title and n.get('enabled') == 'true' for n in current)
            for title in ('照片', '拍照', '文件', '屏幕内容')))
        self.shot('native-attachment-menu-running')
        self.back()
        self.stop_fixture()

    def native_pause_resume(self):
        current = self.hold('native_pause')
        current = self.draft_and_send('native_pause', 'queue', current)
        self.stop_fixture(current)
        current = self.nodes()
        self.check('native_stop_pauses_saved_queue', self.text_exists(current, '继续发送') and
                   self.text_exists(current, '已暂停：', contains=True) and self.text_exists(current, '待发送 1 条'))
        self.shot('native-stopped-paused-queue')
        self.tap('继续发送', current=current)
        current = self.wait_nodes('native_manual_resume_drains_queue', lambda ns:
                                  not self.text_exists(ns, '待发送 1 条') and not self.has_stop(ns))
        self.assert_delivery('native_resume', True)
        self.shot('native-resumed-queue')

    def steer_to_surface(self):
        self.behavior('steer')
        current = self.hold('native_steer')
        current = self.draft_and_send('native_steer', 'steer', current)
        self.stop_fixture(current)
        current = self.nodes()
        self.check('native_steer_stop_pauses_queue', self.text_exists(current, '继续发送') and
                   self.text_exists(current, '待发送 1 条'))
        native_id = self.capture_session_id()
        self.start(dsh_plugin_on='builtin.shell.whale', settle=.2)
        self.start(dsh_shell='builtin.shell.whale', settle=.6)
        current = self.wait_nodes('surface_observes_same_paused_queue', lambda ns:
                                  self.text_exists(ns, '待发送 1 条') and self.text_exists(ns, '继续发送'), timeout=25)
        self.check('surface_same_appvm_session', self.capture_session_id() == native_id and
                   self.state().get('shell') == 'builtin.shell.whale#builtin.shell.whale')
        self.shot('surface-same-paused-queue')
        self.tap('继续发送', current=current)
        self.wait_nodes('surface_resume_command_drains_same_queue', lambda ns:
                        not self.text_exists(ns, '待发送 1 条') and not self.has_stop(ns), timeout=25)
        self.assert_delivery('surface_resume', True)
        self.shot('surface-resumed-queue')

    def surface_cancel(self):
        current = self.hold('surface_cancel', shell='builtin.shell.whale')
        # The Surface chip changes the same SettingsStore through surface.command.
        self.tap('排队', current=current)
        current = self.nodes()
        self.current_input = 'M86GUI_SURFACE_CANCEL_INPUT'
        self.type_draft(self.current_input, current)
        current = self.nodes()
        self.check('surface_running_send_and_stop_coexist', self.text_exists(current, '排队发送') and self.has_stop(current))
        self.tap('排队发送', current=current)
        current = self.queued('surface_cancel', 'queue')
        self.tap('取消', current=current)
        current = self.nodes()
        self.check('surface_cancel_command_removes_item', not self.text_exists(current, '待发送 1 条'))
        self.assert_delivery('surface_cancel', False, running=True)
        self.shot('surface-cancelled-queue')
        self.stop_fixture(current)

    def cleanup(self):
        if not self.mutated:
            return
        tasks = [('stop_fixture', self.stop_fixture)] if self.fixture_foreground else []
        if self.original_mode is not None:
            tasks.append(('restore_delivery_preference', lambda: self.behavior(self.original_mode)))
        tasks += [('restore_native_and_model_follow', lambda: self.start(
            dsh_shell='native', dsh_model='off', dsh_llm_provider='off', dsh_open_screen='chat'))]
        if self.import_attempted:
            tasks.append(('disable_fixture_keep_data', lambda: self.start(dsh_plugin_off=self.plugin)))
        if self.previous_whale_enabled is False:
            tasks.append(('restore_whale_disabled', lambda: self.start(dsh_plugin_off='builtin.shell.whale')))
        for label, task in tasks:
            try:
                task()
                self.checks.append({'id': 'cleanup_' + label, 'passed': True})
            except Exception:
                self.checks.append({'id': 'cleanup_' + label, 'passed': False})
                self.failures.append('cleanup_' + label)
        try:
            state = self.state()
            plugins = self.dump('dsh_dump_plugins')
            providers = self.dump('dsh_dump_providers')
            roles = self.dump('dsh_roles_list')
            self.check('cleanup_verified_native_and_no_active_writers', state.get('shell') == 'native' and
                       all(state.get(k) == 0 for k in ('activeTurnCount', 'writerCount', 'streamingCount')))
            self.check('cleanup_verified_original_send_mode', self.original_mode is None or state.get('sendMode') == self.original_mode)
            self.check('cleanup_verified_fixture_disabled', self.plugin in plugins.get('disabled', []))
            self.check('cleanup_verified_original_whale_switch', ('builtin.shell.whale' in plugins.get('enabled', [])) == self.previous_whale_enabled)
            self.check('cleanup_verified_debug_provider_off', not providers.get('llmActive'))
            self.check('cleanup_verified_model_follows_protocol', not roles.get('modelPref'))
        except Exception as error:
            self.failures.append('cleanup_final_observation_' + (str(error) if isinstance(error, UiFailure) else type(error).__name__))

    def run(self):
        try:
            self.preflight()
            self.make_fixture()
            self.behavior('queue', capture_original=True)
            self.native_cancel()
            self.attachments()
            self.native_pause_resume()
            self.steer_to_surface()
            self.surface_cancel()
        except Exception as exc:
            # Only controlled failure identifiers are persisted; no device XML or user content.
            identifier = str(exc) if isinstance(exc, UiFailure) else type(exc).__name__
            self.failures.append(identifier)
            if self.fixture_foreground:
                try:
                    self.shot('failure-fixture')
                except Exception:
                    pass
        finally:
            self.cleanup()
            report = {'fixture': 'M86-G-UI', 'pluginId': self.plugin, 'providerId': self.provider,
                      'passed': not self.failures and all(c['passed'] for c in self.checks),
                      'createdSessionIds': self.session_ids, 'checks': self.checks,
                      'screenshots': self.screenshots, 'failures': self.failures}
            (self.out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'passed': report['passed'], 'checks': len(self.checks), 'failures': self.failures,
                          'report': str(self.out / 'report.json')}, ensure_ascii=False), flush=True)
        return 0 if report['passed'] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=ADB_DEFAULT)
    parser.add_argument('--device', default='192.0.2.10:39905')
    parser.add_argument('--out', type=Path, help='new output directory (must not exist)')
    args = parser.parse_args()
    return Probe(args).run()


if __name__ == '__main__':
    sys.exit(main())
