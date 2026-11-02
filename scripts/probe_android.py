"""Native Android smoke test against MobileProbe only; no live data or model calls."""
import json, pathlib, re, subprocess, time, urllib.request, xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB=pathlib.Path.home()/'AppData/Local/Temp/sbg-android-sdk/platform-tools/adb.exe'
SERIAL='emulator-5570'
fixture=pathlib.Path((ROOT/'.local/mobile-probe/current.txt').read_text())
info=json.loads((fixture/'server.json').read_text())
def adb(*args):
    return subprocess.check_output([str(ADB),'-s',SERIAL,*args],text=True,encoding='utf-8',errors='replace')
def nodes():
    adb('shell','uiautomator','dump','/sdcard/tethium-ui.xml')
    return list(ET.fromstring(adb('shell','cat','/sdcard/tethium-ui.xml')).iter('node'))
def click(text=None,kind=None,contains=False,description=None):
    end=time.monotonic()+12
    while time.monotonic()<end:
        for node in nodes():
            if (text is None or (text in node.get('text','') if contains else text.casefold()==node.get('text','').casefold())) and (description is None or description==node.get('content-desc')) and (kind is None or node.get('class','').endswith(kind)):
                x,y,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
                adb('shell','input','tap',str((x+x2)//2),str((y+y2)//2));return
    raise AssertionError('UI element not found: '+str((text,kind)))
def type_text(value):
    click(kind='EditText');adb('shell','input','text',value);adb('shell','input','keyevent','4')
def state():
    req=urllib.request.Request('http://127.0.0.1:'+str(info['host']['port'])+'/state?session='+info['session'],headers={'Authorization':'Bearer '+info['host']['token']})
    return json.load(urllib.request.urlopen(req))
def command(action,**body):
    req=urllib.request.Request('http://127.0.0.1:'+str(info['host']['port'])+'/sessions/'+info['session']+'/'+action,data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+info['host']['token'],'Content-Type':'application/json'})
    return json.load(urllib.request.urlopen(req))
def until(predicate):
    end=time.monotonic()+25
    while time.monotonic()<end:
        if predicate():return
        time.sleep(.3)
    raise AssertionError('Timed out')
def screenshot(name):
    adb('shell','screencap','-p','/sdcard/tethium.png');adb('pull','/sdcard/tethium.png',str(fixture/name))
if __name__=='__main__':
    assert fixture.is_relative_to(ROOT/'.local/mobile-probe')
    adb('shell','am','force-stop','dev.tethium.mobile');adb('shell','am','start','-n','dev.tethium.mobile/.MainActivity')
    command('stop');until(lambda:not state()['sessions'][0]['running'])
    for job in state()['conversation']['queued']:command('queue-remove',jobId=job['id'])
    command('queue-resume')
    command('run',prompt='long');until(lambda:state()['sessions'][0]['running']);time.sleep(2)
    type_text('old-task');click(description='В очередь');until(lambda:len(state()['conversation']['queued'])==1);assert state()['conversation']['queued'][0]['prompt']=='old-task'
    type_text('append-task');click(description='Дополнить');until(lambda:state()['conversation']['queued'][0]['partsCount']==2);assert state()['conversation']['queued'][0]['prompt']=='old-task\n\n---\n\nappend-task'
    click(description='Стоп: остановить работу и сохранить очередь');until(lambda:not state()['sessions'][0]['running']);assert len(state()['conversation']['queued'])==1
    type_text('fix-now');click('Сейчас');until(lambda:state()['sessions'][0]['status']=='COMPLETED');assert state()['sessions'][0]['queuePaused'];assert state()['conversation']['queued'][0]['partsCount']==2
    time.sleep(2);screenshot('queue-after-interrupt.png')
    click(description='Результаты сессии');click('HTML с JavaScript',contains=True);click('Проверить JavaScript');until(lambda:any(n.get('text')=='Нажато' for n in nodes()));screenshot('html-preview.png');click('Закрыть результат')
    # Reopening retains the pairing and the paused queue.
    adb('shell','am','force-stop','dev.tethium.mobile');adb('shell','am','start','-n','dev.tethium.mobile/.MainActivity');time.sleep(3)
    assert any(n.get('text')=='Проверка Android' for n in nodes());assert state()['sessions'][0]['queuePaused'];assert len(state()['conversation']['queued'])==1
    log=adb('logcat','-d','-b','crash');assert 'Process: dev.tethium.mobile' not in log
    report={'passed':True,'queueAppend':True,'stopPreservesQueue':True,'priorityRunsFromPause':True,'pairingSurvivesRestart':True,'htmlJavaScript':True,'modelCalls':0}
    (fixture/'report.json').write_text(json.dumps(report,indent=2));print(json.dumps(report));print(fixture)
