"""Targeted warm recreation/drawer evidence; no model sends or data deletion."""
import argparse
import importlib.util
import json
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--session-id', default='5dc68817-1c11-42ef-89f9-37f477b49692', help='Retained M86_F_HOT_ONCE probe only')
    args = parser.parse_args()
    allowed = json.loads((ROOT / 'docs/m86f-boot-remaining1/report.json').read_text(encoding='utf-8'))
    ids = next(c['added'] for c in allowed['checks'] if c['name'] == 'hot:exactly-one-retained-session')
    if args.session_id not in ids:
        raise ValueError('session must be the recorded M86 hot-intent fixture')
    spec = importlib.util.spec_from_file_location('m86g_ui', ROOT / 'tools/m86g-ui-check.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    probe = module.Probe(argparse.Namespace(adb=r'C:\Android\platform-tools\adb.exe', device='192.0.2.10:39905', out=args.output))
    try:
        probe.preflight()
        for index in range(2):
            probe.start(dsh_open_session=args.session_id, settle=1.2)
            probe.start(dsh_open_screen='Behavior', settle=.5)
            current = probe.nodes()
            probe.check(f'warm_{index}_behavior_visible_without_drawer',
                        probe.text_exists(current, '显示与行为') and not any(n.get('content-desc') == '切换本机或远程' and
                        probe.bounds(n)[2] > probe.bounds(n)[0] for n in current))
            probe.shot(f'warm-{index}-behavior')
        probe.start(dsh_open_screen='chat', settle=.5)
        probe.tap('打开侧边栏', desc=True)
        current = probe.nodes()
        probe.check('drawer_opens_by_menu', any(n.get('content-desc') == '切换本机或远程' for n in current))
        probe.shot('menu-open')
        probe.back()
        current = probe.nodes()
        probe.check('drawer_closes_by_back', not any(n.get('content-desc') == '切换本机或远程' for n in current))
        probe.tap('打开侧边栏', desc=True)
        current = probe.nodes()
        probe.check('drawer_opens_again', any(n.get('content-desc') == '切换本机或远程' for n in current))
        probe.close_drawer()
        probe.check('drawer_closes_by_scrim', not any(n.get('content-desc') == '切换本机或远程' for n in probe.nodes()))
        probe.shot('closed')
        result = {'passed': True, 'checks': probe.checks, 'screenshots': probe.screenshots}
    except Exception as error:
        probe.shot('failure')
        result = {'passed': False, 'checks': probe.checks, 'error': str(error)}
    (args.output / 'report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    probe.adb('logcat', '-d', '-s', 'DshDrawerProbe:I', 'DshBoot:I')
    (args.output / 'drawer.log').write_bytes(probe.adb('logcat', '-d', '-s', 'DshDrawerProbe:I', 'DshBoot:I'))
    print(json.dumps(result, ensure_ascii=False), flush=True)
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
