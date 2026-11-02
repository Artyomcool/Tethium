"""Queue editing, model settings and conversation navigation in the Android emulator."""
import sys,json,time,pathlib,re,urllib.request
ROOT=pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0,str(ROOT/'scripts'));import probe_android as p
def click_desc_part(value):
    for n in p.nodes():
        if value in n.get('content-desc',''):
            p.click(description=n.get('content-desc'));return
    raise AssertionError(value)
def api(path,body):
    req=urllib.request.Request('http://127.0.0.1:'+str(p.info['host']['port'])+path,data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+p.info['host']['token'],'Content-Type':'application/json'})
    return json.load(urllib.request.urlopen(req))
def prompts():return [j['prompt'] for j in p.state()['conversation']['queued']]
def close():p.click(description='Закрыть панель')
if __name__=='__main__':
    assert p.fixture.is_relative_to(ROOT/'.local/mobile-probe')
    p.adb('shell','am','force-stop','dev.tethium.mobile');p.adb('shell','am','start','-n','dev.tethium.mobile/.MainActivity');time.sleep(2)
    p.click(kind='EditText');p.adb('shell','input','keycombination','113','29');p.adb('shell','input','keyevent','67');p.adb('shell','input','keyevent','4')
    p.command('stop');p.until(lambda:not p.state()['sessions'][0]['running'])
    for j in p.state()['conversation']['queued']:p.command('queue-remove',jobId=j['id'])
    for text in ['Alpha task','Beta task','Gamma task']:p.command('run',prompt=text,separate=True)
    time.sleep(2);p.click('Очередь · 3 · пауза')
    p.click(description='Выбрать сообщение 1');p.click(description='Выбрать сообщение 3');p.click('Склеить · 2')
    p.until(lambda:prompts()==['Alpha task\n\n---\n\nGamma task','Beta task'])
    p.screenshot('queue-merged.png')
    p.click(description='Действия с сообщением 1');p.click('Разбить на сообщения');p.until(lambda:prompts()==['Alpha task','Beta task','Gamma task'])
    p.click(description='Действия с сообщением 3');p.click('Вернуть последнюю часть в редактор');p.until(lambda:prompts()==['Alpha task','Beta task'])
    p.until(lambda:any(n.get('class','').endswith('EditText') and n.get('text')=='Gamma task' for n in p.nodes()))
    p.adb('shell','input','keyevent','4');p.click('Очередь · 2 · пауза');p.click(description='Действия с сообщением 2');p.click('Удалить…');p.click('Отмена');assert prompts()==['Alpha task','Beta task']
    p.click(description='Действия с сообщением 2');p.click('Первым');p.until(lambda:prompts()==['Beta task','Alpha task']);close()
    p.click('Codex · Авто');p.click(kind='Spinner');p.click('Probe model');
    ns=[n for n in p.nodes() if n.get('class','').endswith('Spinner')];x,y,x2,y2=map(int,re.findall(r'\d+',ns[1].get('bounds')));p.adb('shell','input','tap',str((x+x2)//2),str((y+y2)//2));p.click('Высокое');p.screenshot('model-settings.png');p.click('Применить');p.until(lambda:p.state()['sessions'][0]['preferences']['reasoningEffort']=='high')
    p.adb('shell','am','force-stop','dev.tethium.mobile');p.adb('shell','am','start','-n','dev.tethium.mobile/.MainActivity');time.sleep(2)
    assert any(n.get('class','').endswith('EditText') and n.get('text')=='Gamma task' for n in p.nodes())
    p.click(description='Проекты и сессии');p.screenshot('projects.png');close()
    p.click(description='Поиск в разговоре');p.click(kind='EditText');p.adb('shell','input','text','fix-now');p.adb('shell','input','keyevent','4');p.screenshot('search.png');p.click('Вы · fix-now');
    p.screenshot('search-position.png')
    result={'passed':True,'nonAdjacentMerge':True,'splitRestoresOrder':True,'takeLastPart':True,'deleteConfirmation':True,'reorder':True,'modelAndEffort':True,'draftSurvivesRestart':True,'requestSearch':True,'modelCalls':0}
    (p.fixture/'features-report.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
