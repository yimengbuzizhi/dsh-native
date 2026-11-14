"""M86-A 真机交互：三 Tab / 搜索 / 插件详情 / 选择壳 / 安全模式插件管理。"""
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = r'C:\Android\platform-tools\adb.exe'
DEV = '192.0.2.10:39905'
ROOT = Path(r'D:\dsh\native')
PKG = 'xin.dshaa.nativeapp'
results = []

def adb(*args):
    p = subprocess.run([ADB, '-s', DEV, *args], capture_output=True, timeout=30)
    if p.returncode: raise RuntimeError(p.stderr.decode('utf-8', 'replace'))
    return p.stdout

def start(**extras):
    args = ['shell', 'am', 'start', '-f', '0x20000000', '-n', PKG+'/.MainActivity']
    for k, v in extras.items(): args += ['--es', k, v]
    adb(*args)
    time.sleep(0.7)

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/Download/m86a-ui-check.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/Download/m86a-ui-check.xml')).iter('node'))

def texts(): return [n.get('text', '') for n in nodes()]

def tap(text, exact=True):
    for n in nodes():
        value = n.get('text', '')
        if (value == text if exact else text in value):
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            time.sleep(0.6)
            return
    raise RuntimeError('missing text: '+text)

def shot(name):
    (ROOT/'docs'/'shots'/name).write_bytes(adb('exec-out', 'screencap', '-p'))

def selected_shell():
    path = '/sdcard/Download/m86a-selected-shell.json'
    start(dsh_shell_list=path)
    time.sleep(3.6)
    value = json.loads(adb('shell', 'cat', path))
    return value.get('active') == 'm85f.webview-shell#m85f.webview'

def check(name, ok):
    results.append({'name':name, 'ok':bool(ok)})
    print(('PASS ' if ok else 'FAIL ')+name, flush=True)

try:
    start(dsh_shell='native', dsh_open_screen='Plugins')
    tap('导入')
    check('three-tabs', all(t in texts() for t in ['导入','内置','角色']))
    shot('m86a-04-import-final.png')
    tap('内置')
    check('builtin-enabled-state', any('已启用' in t for t in texts()))
    shot('m86a-05-builtin.png')
    tap('界面：原生 UI')
    check('builtin-detail', 'builtin.shell.native' in texts())
    shot('m86a-06-builtin-detail.png')
    tap('关闭')
    tap('导入')
    # 搜索停用的第三方壳，名称/角色匹配同一入口。
    for n in nodes():
        if n.get('class') == 'android.widget.EditText':
            x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)); break
    adb('shell','input','text','webview')
    adb('shell','input','keyevent','4')
    time.sleep(0.5)
    check('search-disabled-shell', any('M85-F WebView' in t for t in texts()))
    shot('m86a-07-search-disabled.png')
    tap('清空')
    start(dsh_plugin_on='m85f.webview-shell')
    tap('角色')
    check('native-using-before-switch', '使用中' in texts())
    shot('m86a-08-roles-final.png')
    tap('M85-F WebView 壳 · webview')
    time.sleep(1.5)
    shot('m86a-09-webview-selected.png')
    check('shell-selected-via-ui', selected_shell())
    start(dsh_shell='native')
    start(dsh_builtin_off='builtin.shell.native')
    check('safe-mode', '界面插件已停用' in texts())
    shot('m86a-10-safe-mode.png')
    tap('管理插件 / 选择界面壳')
    check('safe-plugin-manager', all(t in texts() for t in ['导入','内置','角色']))
    tap('角色')
    shot('m86a-11-safe-manager.png')
    tap('M85-F WebView 壳 · webview')
    time.sleep(1.5)
    shot('m86a-12-safe-select-shell.png')
    check('safe-select-alternative', selected_shell())
finally:
    start(dsh_builtin_on='builtin.shell.native', dsh_shell='native')
    start(dsh_plugin_off='m85f.webview-shell')
    (ROOT/'docs'/'m86a-ui-check.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')

if not all(r['ok'] for r in results): raise SystemExit(1)
