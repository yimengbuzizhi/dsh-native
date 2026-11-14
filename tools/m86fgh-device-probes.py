"""Run one debug probe at a time; reports never contain device preferences or credentials."""
import argparse
import json
from pathlib import Path
import subprocess
import time

ADB = r'C:\Android\platform-tools\adb.exe'
DEV = '192.0.2.10:39905'
PKG = 'xin.dshaa.nativeapp'
ROOT = Path(r'D:\dsh\native')

def adb(*args, timeout=40):
    p = subprocess.run([ADB, '-s', DEV, *args], capture_output=True, timeout=timeout)
    if p.returncode:
        raise RuntimeError(p.stderr.decode('utf-8', 'replace')[:2000])
    return p.stdout

p = argparse.ArgumentParser()
p.add_argument('probe', choices=['loading', 'queue', 'remote'])
p.add_argument('--suffix', default='')
a = p.parse_args()
key = {'loading': 'dsh_loading_dev_test', 'queue': 'dsh_queue_test', 'remote': 'dsh_remote_test'}[a.probe]
name = 'm86' + {'loading':'f-loading-dev', 'queue':'g-queue-device', 'remote':'h-remote-device'}[a.probe] + a.suffix
path = '/sdcard/Download/' + name + '.json'
out = ROOT / 'docs' / (name + '.json')
assert not out.exists(), f'report already exists: {out}'
adb('shell', 'am', 'start', '-f', '0x20000000', '-n', PKG + '/.MainActivity', '--es', key, path)
report = None
last = None
for _ in range(240):
    raw = subprocess.run([ADB, '-s', DEV, 'shell', 'cat', path], capture_output=True, timeout=30)
    if raw.returncode == 0:
        try:
            report = json.loads(raw.stdout.decode('utf-8'))
            progress = len(report.get('checks', []))
            if progress != last:
                print(f'{a.probe}: {progress} checks observed', flush=True)
                last = progress
            if report.get('running') is not True and ('passed' in report or 'total' in report):
                break
        except json.JSONDecodeError:
            pass
    time.sleep(.5)
else:
    raise RuntimeError(f'{a.probe} timed out; last checks={last}')
out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
passed = report.get('passed')
if isinstance(passed, int) and not isinstance(passed, bool):
    ok = passed == report['total'] and report.get('allCreatedPluginsDisabled', False)
else:
    ok = passed is True
failed = [c.get('id', c.get('name')) for c in report.get('checks', []) if c.get('passed', c.get('ok')) is False]
print(json.dumps({'probe':a.probe, 'ok':ok, 'checks':len(report.get('checks', [])), 'failed':failed, 'report':str(out)}, ensure_ascii=False), flush=True)
if not ok:
    raise SystemExit(1)
