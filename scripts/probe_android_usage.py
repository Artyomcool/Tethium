"""Quota settings, usage chart, Markdown preview and selected-text quoting in the isolated emulator."""
import sys,time,re,json
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent));import probe_android as p
assert p.fixture.is_relative_to(p.ROOT/'.local/mobile-probe')
p.click(description='Меню сессии');p.click('Токены и прошлые периоды')
p.until(lambda:any('122' in n.get('text','') and 'токенов' in n.get('text','') for n in p.nodes()));p.screenshot('usage-history.png');p.click(description='Закрыть панель')
p.click(description='Меню сессии');p.click('Продолжать после появления квоты',contains=True);p.until(lambda:p.state()['sessions'][0]['quotaWait']['autoResume'])
p.click(description='Меню сессии');p.click('Продолжать после появления квоты',contains=True);p.until(lambda:not p.state()['sessions'][0]['quotaWait']['autoResume'])
p.click(description='Результаты сессии');p.click('Markdown-документ',contains=True);p.until(lambda:any('Markdown внутри Tethium' in n.get('text','') for n in p.nodes()));p.screenshot('markdown-preview.png');p.click('Закрыть результат')
p.click(description='Меню сессии');p.click('В конец разговора');time.sleep(1)
node=next(n for n in p.nodes() if n.get('class','').endswith('TextView') and 'Готово:' in n.get('text',''))
x,y,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')));x=x+60;y=y+25;p.adb('shell','input','swipe',str(x),str(y),str(x),str(y),'1000')
# The API 36 emulator omits FloatingToolbar from the accessibility tree; verified screenshot coordinates at 1080px.
p.adb('shell','input','tap','330',str(y-105));p.until(lambda:any(n.get('class','').endswith('EditText') and '> ' in n.get('text','') for n in p.nodes()));p.adb('shell','input','keyevent','4');p.screenshot('quote-in-composer.png')
result={'passed':True,'usageChart':True,'autoResumeOptIn':True,'markdownPreview':True,'selectedTextQuote':True,'modelCalls':0};(p.fixture/'usage-report.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
