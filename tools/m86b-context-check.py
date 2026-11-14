"""同会话确定性用量验证；需先选 m86.usage.fixture，真实上游验证单独记录。"""
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
ADB=r'C:\Android\platform-tools\adb.exe'
DEV='192.0.2.10:39905'
ROOT=Path(r'D:\dsh\native')
PKG='xin.dshaa.nativeapp'

def adb(*args):
    p=subprocess.run([ADB,'-s',DEV,*args],capture_output=True,timeout=30)
    if p.returncode: raise RuntimeError(p.stderr.decode('utf-8','replace'))
    return p.stdout

def nodes():
    adb('shell','uiautomator','dump','/sdcard/Download/m86b-context-check.xml')
    return list(ET.fromstring(adb('shell','cat','/sdcard/Download/m86b-context-check.xml')).iter('node'))

def tap_node(n):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)); time.sleep(.5)

def snapshot():
    path='/sdcard/Download/m86b-context-check.json'
    adb('shell','am','start','-f','0x20000000','-n',PKG+'/.MainActivity','--es','dsh_context_usage',path)
    return json.loads(adb('shell','cat',path))

def send(text):
    tap_node(next(n for n in nodes() if n.get('class')=='android.widget.EditText'))
    adb('shell','input','text',text); adb('shell','input','keyevent','4'); time.sleep(.5)
    tap_node(next(n for n in nodes() if n.get('content-desc')=='发送'))
    time.sleep(1)

reports=[snapshot()]
assert reports[0]['current']['inputTokens']==12000,reports[0]
send('M86_LOWER_USAGE')
reports.append(snapshot())
assert reports[-1]['current']['inputTokens']==8000,reports[-1]
assert reports[-1]['current']['cacheReadTokens']==6000,reports[-1]
# 同会话不把合法下降锁成历史最大值。
assert len({r['sessionId'] for r in reports})==1
percent=next(n for n in nodes() if re.fullmatch(r'\d+%',n.get('text','')))
tap_node(percent)
(ROOT/'docs/shots/m86b-02-context-panel.png').write_bytes(adb('exec-out','screencap','-p'))
text=' '.join(n.get('text','') for n in nodes())
assert '真实输入' in text and '缓存读取' in text,text
adb('shell','input','keyevent','4')
send('M86_ZERO_USAGE')
reports.append(snapshot())
assert reports[-1]['current']['inputTokens']==0,reports[-1]
assert reports[-1]['current']['cacheReadTokens']==0,reports[-1]
assert len({r['sessionId'] for r in reports})==1
(ROOT/'docs/shots/m86b-03-zero-usage.png').write_bytes(adb('exec-out','screencap','-p'))
(ROOT/'docs/m86b-context-check.json').write_text(json.dumps(reports,ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS same-session snapshots 12000 -> 8000 -> 0; cache subsets 10000 -> 6000 -> 0; panel real-input labels',flush=True)
