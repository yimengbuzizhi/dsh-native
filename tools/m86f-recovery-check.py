"""M86-F required-agent boot recovery probe. Run explicitly; never runs on import.

Creates ONE fresh agent/effect fixture ZIP (permissions exactly ['agent']), installs
and grants it through existing debug APIs, verifies its real resident registration,
backs up its exact private index.js, then replaces only that file with effect-only
code. Cold boot must draw loading and fail after the 10s exact-agent gate despite
an existing resident. Before repairing the agent, clicks '使用默认加载页' and proves
it performs another actual draw/retry and fails again. Then uses the boot recovery
plugin manager, searches 'agent', restores built-in agent, returns/retries, verifies
coreReady/shellFirstDraw, disables its fixture and restores the original index.js.

No adb/Gradle/service work occurs until main() runs. No secrets/preferences reads,
network/model send, permission/notification/audio/alarm changes or session deletes.
Every private write is restricted to the fresh /data/data/<pkg>/files/plugins/
<generated m86.selftest.recovery.* id>/index.js, checked with run-as pwd/readlink.
Fixture source, original index backup, ZIP, screenshots, UI XML and logs are kept.

  python tools/m86f-recovery-check.py --output docs/m86f-recovery-core
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shlex
import subprocess
import time
import traceback
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PKG = 'xin.dshaa.nativeapp'
COMPONENT = PKG + '/.MainActivity'
AGENT_ID = 'm86.recovery.agent'


def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def stages(text):
    found = {}
    for match in re.finditer(r'\b(loadingFirstDraw|coreReady|coreFailed|shellFirstDraw|reportFullyDrawn) elapsedMs=(\d+)', text):
        found.setdefault(match[1], []).append(int(match[2]))
    return found


class Recovery:
    def __init__(self, args):
        self.args = args
        self.id = 'm86.selftest.recovery.' + uuid.uuid4().hex
        self.out = args.output or ROOT / 'docs' / ('m86f-recovery-' + time.strftime('%Y%m%d-%H%M%S'))
        if not re.fullmatch(r'm86f-recovery-[A-Za-z0-9-]+', self.out.name):
            raise ValueError('output name must be m86f-recovery-<letters/digits/hyphens>')
        self.out.mkdir(parents=True, exist_ok=False)
        self.remote = '/sdcard/Download/' + self.out.name
        self.private = PurePosixPath('/data/data/' + PKG + '/files/plugins/' + self.id + '/index.js')
        self.relative = 'files/plugins/' + self.id + '/index.js'
        self.parent = str(self.private.parent)
        self.original = None
        self.canonical = None
        self.installed = False
        self.log_process = None
        self.log_handle = None
        self.serial = 0
        self.baseline = {}
        self.report = {'device': args.device, 'pluginId': self.id, 'agentId': AGENT_ID,
                       'privateMutationTarget': str(self.private), 'evidenceDirectory': str(self.out),
                       'remoteDirectory': self.remote, 'checks': [], 'status': 'preparing'}
        self.persist()
        self.assert_target()

    def persist(self):
        save_json(self.out / 'report.json', self.report)

    def check(self, name, ok, **details):
        self.report['checks'].append({'name': name, 'ok': bool(ok), **details})
        print(('PASS ' if ok else 'FAIL ') + name, flush=True)
        self.persist()
        return bool(ok)

    def assert_target(self):
        assert re.fullmatch(r'm86\.selftest\.recovery\.[0-9a-f]{32}', self.id)
        assert str(self.private) == '/data/data/' + PKG + '/files/plugins/' + self.id + '/index.js'
        assert self.relative == 'files/plugins/' + self.id + '/index.js'
        assert len(self.id) < 80 and self.private.name == 'index.js'
        assert '..' not in self.private.parts
        assert self.private.parent.name == self.id
        assert re.fullmatch(r'/sdcard/Download/m86f-recovery-[A-Za-z0-9-]+', self.remote)

    def adb(self, *parts, check=True, timeout=30):
        result = subprocess.run([self.args.adb, '-s', self.args.device, *map(str, parts)], capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError('adb failed ' + ' '.join(map(str, parts[:4])) + ': ' + result.stderr.decode('utf-8', 'replace'))
        return result

    def shell(self, *parts, **options):
        return self.adb('shell', shlex.join(list(map(str, parts))), **options)

    def start(self, wait=False, **extras):
        command = ['am', 'start'] + (['-W'] if wait else []) + ['-f', '0x20000000', '-n', COMPONENT]
        for key, value in extras.items():
            command += ['--es', key, str(value)]
        return self.shell(*command).stdout.decode('utf-8', 'replace')

    def dump(self, key, name, **extras):
        self.serial += 1
        remote = self.remote + f'/dump-{self.serial:03d}-{name}.json'
        self.start(**{key: remote}, **extras)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            value = self.shell('cat', remote, check=False)
            if not value.returncode:
                try:
                    result = json.loads(value.stdout)
                    save_json(self.out / 'dumps' / (name + '.json'), result)
                    return result
                except (ValueError, UnicodeError):
                    pass
            time.sleep(.2)
        raise RuntimeError('missing debug report ' + name)

    def nodes(self, name):
        remote = self.remote + '/ui-' + name + '.xml'
        self.shell('uiautomator', 'dump', remote)
        data = self.shell('cat', remote).stdout
        (self.out / (name + '.xml')).write_bytes(data)
        return list(ET.fromstring(data).iter('node'))

    def tap(self, text, *, exact=True):
        for node in self.nodes('tap-' + str(time.time_ns())):
            if (node.get('text', '') == text if exact else text in node.get('text', '')):
                return self.tap_node(node)
        raise RuntimeError('missing UI label ' + text)

    def tap_scroll(self, text):
        for index in range(7):
            for node in self.nodes('find-' + str(time.time_ns())):
                bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
                if (node.get('text', '') == text and len(bounds) == 4 and
                        bounds[2] > bounds[0] and bounds[3] > bounds[1]):
                    return self.tap_node(node)
            dimensions = self.shell('wm', 'size').stdout.decode('utf-8', 'replace')
            values = re.findall(r'(\d+)x(\d+)', dimensions)
            width, height = map(int, values[-1]) if values else (1080, 2400)
            self.shell('input', 'swipe', width//2, int(height*.79), width//2, int(height*.38), 300)
            time.sleep(.25)
        raise RuntimeError('missing scrollable UI label ' + text)

    def tap_node(self, node):
        bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
        if len(bounds) != 4:
            raise RuntimeError('invalid UI node bounds')
        x1, y1, x2, y2 = bounds
        self.shell('input', 'tap', (x1+x2)//2, (y1+y2)//2)
        time.sleep(.45)

    def shot(self, name):
        data = self.adb('exec-out', 'screencap', '-p').stdout
        if not data.startswith(b'\x89PNG\r\n\x1a\n'):
            raise RuntimeError('screenshot is not PNG')
        (self.out / name).write_bytes(data)

    def begin_log(self):
        self.log_handle = (self.out / 'recovery-logcat.txt').open('wb')
        self.log_process = subprocess.Popen([self.args.adb, '-s', self.args.device, 'logcat', '-v', 'threadtime', '-T', '1',
                                             'DshBoot:I', 'DshProjection:I', 'DshAgent:I', 'DshPlugin:I', 'AndroidRuntime:E', '*:S'],
                                            stdout=self.log_handle, stderr=subprocess.STDOUT)
        time.sleep(.25)

    def log_text(self):
        return (self.out / 'recovery-logcat.txt').read_text(encoding='utf-8', errors='replace')

    def wait_log(self, pattern, offset=0, timeout=18):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            text = self.log_text()[offset:]
            match = re.search(pattern, text)
            if match:
                return text, match
            time.sleep(.2)
        raise RuntimeError('log condition did not arrive: ' + pattern)

    def verify_private(self):
        self.assert_target()
        pwd = self.shell('run-as', PKG, 'pwd').stdout.decode().strip()
        assert pwd in ('/data/data/' + PKG, '/data/user/0/' + PKG), 'unexpected app-private root'
        # Android may preserve /data/data while run-as pwd uses /data/user/0.
        # Both exact roots must resolve to the SAME file, still inside this UUID.
        expected = {root + '/files/plugins/' + self.id + '/index.js'
                    for root in ('/data/data/' + PKG, '/data/user/0/' + PKG)}
        canonical = self.shell('run-as', PKG, 'readlink', '-f', str(self.private)).stdout.decode().strip()
        relative_canonical = self.shell('run-as', PKG, 'readlink', '-f', self.relative).stdout.decode().strip()
        absolute_stat = self.shell('run-as', PKG, 'stat', '-c', '%d:%i', str(self.private)).stdout.decode().strip()
        relative_stat = self.shell('run-as', PKG, 'stat', '-c', '%d:%i', self.relative).stdout.decode().strip()
        assert (canonical in expected and relative_canonical in expected and
                re.fullmatch(r'\d+:\d+', absolute_stat) and absolute_stat == relative_stat), 'private target escaped exact generated plugin directory'
        self.canonical = canonical
        self.report['targetDeviceInode'] = absolute_stat
        self.shell('run-as', PKG, 'test', '-f', str(self.private))
        self.report['canonicalMutationTarget'] = canonical
        self.persist()

    def read_index(self):
        self.verify_private()
        data = self.adb('exec-out', 'run-as', PKG, 'cat', str(self.private)).stdout
        assert 0 < len(data) <= 65536, 'fixture index size outside expected limit'
        return data

    def replace_index(self, host_source):
        self.assert_target()
        self.verify_private()
        host_source = host_source.resolve()
        assert host_source.is_relative_to(self.out.resolve()) and host_source.is_file()
        assert host_source.name in ('effect-only.js', 'index.original.js')
        remote_source = self.remote + '/' + host_source.name
        assert remote_source in (self.remote + '/effect-only.js', self.remote + '/index.original.js')
        self.adb('push', str(host_source), remote_source)
        # Android shell owns the external read, run-as owns the private write. No
        # exec-out stdin: adb shell's explicit pipeline transports the fixed file.
        write_command = 'cat > ' + shlex.quote(str(self.private))
        command = 'cat ' + shlex.quote(remote_source) + ' | ' + shlex.join(['run-as', PKG, 'sh', '-c', write_command])
        self.adb('shell', command)
        actual = self.read_index()
        if actual != host_source.read_bytes():
            raise RuntimeError('private fixture index write/readback mismatch')

    def fixture(self):
        directory = self.out / 'fixture'
        directory.mkdir()
        manifest = {'id': self.id, 'name': 'M86 F recovery agent fixture', 'version': '1.0.0',
                    'description': 'Dedicated retained boot recovery probe; no model or tool execution',
                    'main': 'index.js', 'format': 'dsh', 'permissions': ['agent']}
        source = ("export function apply(ctx) {\n"
                  "  ctx.effect(function () { return function () {}; });\n"
                  "  ctx.agent.register({id:'" + AGENT_ID + "',label:'M86 recovery agent',\n"
                  "    run:function () { return {stopReason:'completed',text:'M86_RECOVERY_UNUSED'}; }});\n"
                  "}\n")
        effect_only = "export function apply(ctx) { ctx.effect(function () { return function () {}; }); }\n"
        save_json(directory / 'package.json', manifest)
        (directory / 'index.js').write_text(source, encoding='utf-8')
        (self.out / 'effect-only.js').write_text(effect_only, encoding='utf-8')
        archive = self.out / 'recovery-fixture.zip'
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as zipped:
            zipped.write(directory / 'package.json', 'package.json')
            zipped.write(directory / 'index.js', 'index.js')
        return archive

    def run(self):
        self.shell('mkdir', '-p', self.remote)
        self.baseline['agents'] = self.dump('dsh_agent_list', 'baseline-agents')
        self.baseline['loading'] = self.dump('dsh_loading_list', 'baseline-loading')
        self.baseline['shells'] = self.dump('dsh_shell_list', 'baseline-shells')
        self.baseline['sessions'] = self.dump('dsh_dump_sessions', 'baseline-sessions')
        if self.baseline['agents'].get('pref') not in ('', 'native', 'builtin.agent.dsh'):
            raise RuntimeError('preflight requires built-in agent selected')
        if not self.baseline['shells'].get('native', {}).get('enabled') or self.baseline['shells'].get('active'):
            raise RuntimeError('preflight requires enabled native UI selected')
        if any(n.get('content-desc') in ('停止', '停止中…') for n in self.nodes('baseline')):
            raise RuntimeError('preflight refuses to force-stop an active visible turn')
        archive = self.fixture()
        remote_archive = self.remote + '/recovery-fixture.zip'
        self.adb('push', str(archive), remote_archive)
        try:
            # Normal install first; never patch registry metadata or grant storage.
            self.installed = True  # An attempted install must also clean up its own id if validation/dump fails.
            self.start(dsh_plugin_zip=remote_archive)
            time.sleep(1.5)
            agents = self.dump('dsh_agent_list', 'installed-agents')
            candidate = next((item for item in agents.get('candidates', []) if item.get('plugin') == self.id), None)
            if not self.check('normal-install-agent-metadata', candidate is not None):
                raise RuntimeError('normal fixture installation did not register candidate')
            self.installed = True
            self.start(dsh_plugin_grant=self.id)  # Exactly one grant call; manifest requests only agent.
            self.start(dsh_agent=self.id + '#' + AGENT_ID)
            self.start(dsh_loading='default')
            default = self.dump('dsh_loading_list', 'default-before-failure')
            self.check('default-loading-before-same-revision-retry', default.get('snapshotOwner') == 'builtin.loading.native',
                       revision=default.get('revision'))
            selected = self.dump('dsh_agent_list', 'normal-selected-agent')
            projections = self.dump('dsh_dump_projections', 'normal-resident')
            self.check('normal-selected-agent', selected.get('active', {}).get('plugin') == self.id and
                       selected.get('active', {}).get('agent') == AGENT_ID and not selected.get('safety') and
                       any(c.get('plugin') == self.id and c.get('permitted') is True for c in selected.get('candidates', [])))
            resident = next((p for p in projections.get('plugins', []) if p.get('id') == self.id), {})
            if not self.check('normal-runtime-exact-agent-and-effect', AGENT_ID in resident.get('agents', []) and resident.get('cleanups', 0) == 1):
                raise RuntimeError('normal resident registration missing')
            self.shell('am', 'force-stop', PKG)
            self.original = self.read_index()
            if self.original != (self.out / 'fixture' / 'index.js').read_bytes():
                raise RuntimeError('installed source differs from dedicated fixture source')
            (self.out / 'index.original.js').write_bytes(self.original)
            self.report['originalSha256'] = hashlib.sha256(self.original).hexdigest()
            self.persist()
            self.replace_index(self.out / 'effect-only.js')
            self.begin_log()
            (self.out / 'cold-am-start.txt').write_text(self.start(wait=True), encoding='utf-8')
            first_text, failure = self.wait_log(r'coreFailed elapsedMs=(\d+)', timeout=22)
            first_stages = stages(first_text)
            self.check('effect-only-resident-live', bool(re.search(r'resident started plugin=' + re.escape(self.id) +
                       r' .*cleanups=1 .*agents=\s+storages=', first_text)), log='recovery-logcat.txt')
            self.check('required-agent-fails-after-gate', int(failure[1]) >= 10_000 and self.id + '#' + AGENT_ID in first_text and
                       'coreReady' not in first_stages and 'shellFirstDraw' not in first_stages,
                       timings=first_stages)
            self.shot('01-required-agent-failed.png')
            failed_nodes = self.nodes('failed-before-default')
            self.check('failed-ui-controls', all(any(n.get('text') == label for n in failed_nodes)
                       for label in ('启动未完成', '重试', '使用默认加载页', '管理插件 / 修复角色')))

            # Same builtin snapshot revision must still actually draw and restart.
            # This is intentionally separate from (and before) the role repair.
            retry_offset = len(self.log_text())
            self.tap('使用默认加载页')
            retry_text, retry_failure = self.wait_log(r'coreFailed elapsedMs=(\d+)', offset=retry_offset, timeout=23)
            retry_stages = stages(retry_text)
            self.check('default-button-same-revision-retries', bool(retry_stages.get('loadingFirstDraw')) and
                       int(retry_failure[1]) - int(failure[1]) >= 9_500 and
                       'coreReady' not in retry_stages and 'shellFirstDraw' not in retry_stages,
                       timings=retry_stages, note='Failed role remains selected; retry/default cannot bypass exact registration.')
            self.shot('02-default-retry-still-failed.png')

            self.tap('管理插件 / 修复角色')
            self.shot('03-recovery-plugin-manager.png')
            self.tap('角色')
            search = next((n for n in self.nodes('recovery-roles-before-search') if n.get('class') == 'android.widget.EditText'), None)
            if search is None:
                raise RuntimeError('role manager search field missing')
            self.tap_node(search)
            self.shell('input', 'text', 'builtin.agent.dsh')
            self.shell('input', 'keyevent', '4')
            time.sleep(.4)
            self.shot('04-recovery-agent-search.png')
            self.tap_scroll('恢复内置回合')
            repair_offset = len(self.log_text())
            self.tap('返回并重试启动')
            repaired_text, _ = self.wait_log(r'shellFirstDraw elapsedMs=(\d+)', offset=repair_offset, timeout=22)
            repaired_stages = stages(repaired_text)
            self.check('repaired-role-reaches-shell', bool(repaired_stages.get('coreReady')) and
                       bool(repaired_stages.get('shellFirstDraw')) and not repaired_stages.get('coreFailed'), timings=repaired_stages)
            self.shot('05-repaired-native-shell.png')
            active = self.dump('dsh_agent_list', 'repaired-agent')
            self.check('built-in-agent-selected-by-ui', active.get('active', {}).get('builtin') is True and not active.get('safety'))
            after = self.dump('dsh_dump_sessions', 'after-sessions')
            self.check('no-session-set-changes', set(self.baseline['sessions'].get('active', [])) == set(after.get('active', [])),
                       beforeCount=self.baseline['sessions'].get('activeCount'), afterCount=after.get('activeCount'))
        finally:
            self.cleanup()
        self.report['status'] = 'passed' if all(c['ok'] for c in self.report['checks']) and self.report.get('cleanup', {}).get('ok') else 'failed'
        self.persist()

    def cleanup(self):
        errors = []
        # Restore bytes BEFORE trying to recover from any failed UI test, so even
        # an interrupted run can boot its fresh fixture again. All data retained.
        if self.original is not None:
            try:
                self.shell('am', 'force-stop', PKG)
                self.replace_index(self.out / 'index.original.js')
                self.check('cleanup-original-index-restored', self.read_index() == self.original,
                           sha256=hashlib.sha256(self.original).hexdigest())
            except Exception as error:
                errors.append('restore index: ' + str(error))
        if self.installed:
            try:
                # Cold launch sets agent pref BEFORE initialization; disable only own fixture.
                self.start(wait=True, dsh_agent=self.baseline['agents'].get('pref') or 'native')
                time.sleep(.6)
                self.start(dsh_plugin_off=self.id)
                if self.baseline['loading'].get('selected'):
                    self.start(dsh_loading=self.baseline['loading']['selected'])
                after = self.dump('dsh_agent_list', 'cleanup-agent')
                plugins = self.dump('dsh_dump_plugins', 'cleanup-plugins')
                self.check('cleanup-fixture-disabled', self.id in plugins.get('disabled', []))
                self.check('cleanup-agent-baseline', after.get('active', {}).get('builtin') is True)
            except Exception as error:
                errors.append('disable/selection: ' + str(error))
        if self.log_process is not None:
            if self.log_process.poll() is None:
                self.log_process.terminate()
                try:
                    self.log_process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.log_process.kill()
                    self.log_process.wait(timeout=5)
            self.log_handle.close()
        self.report['cleanup'] = {'ok': not errors, 'errors': errors, 'fixtureRetained': self.installed,
                                  'sourceAndBackupRetained': self.original is not None}
        self.persist()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--adb', default=r'C:\Android\platform-tools\adb.exe')
    parser.add_argument('--device', default='192.0.2.10:39905')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    run = Recovery(args)
    try:
        run.run()
    except BaseException as error:
        run.report['status'] = 'failed'
        run.report['error'] = str(error)
        (run.out / 'exception.txt').write_text(traceback.format_exc(), encoding='utf-8')
        run.persist()
        print('FAILED ' + str(error), flush=True)
        return 1
    print('Evidence: ' + str(run.out), flush=True)
    return 0 if run.report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
