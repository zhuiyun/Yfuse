"""Test a saved Emby session through Yfuse's own server, search, detail and player UI."""
import base64
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, 'qa-private-playback')
import pilot
import features as ui

measure = pilot.measure
ui.pkg = 'com.yfuse'
prefs = '/data/user/0/com.yfuse/shared_prefs/yfuse.xml'
server_name = 'Emby QA'
markers = ['服务器','媒体库','我的服务器','搜索','搜索媒体','切换服务器','播放','继续播放','立即播放','选集','演职人员','搜索历史','所有服务器中都没有找到相关内容','无结果','搜索结果','设置','观看','媒体信息','连接到服务器','无法连接','重试','播放失败','正在缓冲','播放进度','关闭',server_name]


def provision(core, account=None):
    ui.shell('am','force-stop',ui.pkg)
    uid = ui.shell('stat','-c','%u','/data/user/0/com.yfuse').strip()
    assert uid.isdecimal()
    old = ui.shell('cat', prefs)
    root = ET.fromstring(old) if '<map' in old else ET.Element('map')
    def put(name, value, boolean=False):
        node = root.find(f"./*[@name='{name}']")
        if node is not None:
            root.remove(node)
        if boolean:
            ET.SubElement(root,'boolean',{'name':name,'value':value})
        else:
            ET.SubElement(root,'string',{'name':name}).text=value
    put('player.engine',core)
    put('player.ycore.engineSelection','Lock'+core)
    put('sync.progress','false',True)
    if account:
        ident=account['baseUrl']+'#'+account['userId']
        saved=dict(account,id=ident,serverName=server_name)
        put('servers.data',json.dumps({'servers':[saved],'defaultServerId':ident}))
    directory = prefs.rsplit('/',1)[0]
    command = f'umask 077 && mkdir -p {directory} && chown {uid}:{uid} {directory} && cat > {prefs} && chown {uid}:{uid} {prefs} && chmod 600 {prefs} && restorecon -R {directory}'
    subprocess.run(['adb','shell',command],input=ET.tostring(root,encoding='utf-8',xml_declaration=True),capture_output=True,check=True)
    check=ET.fromstring(ui.shell('cat',prefs))
    assert check.find("./boolean[@name='sync.progress']").get('value')=='false'
    assert check.find("./string[@name='player.engine']").text==core


def matching(tree, terms, starts=False):
    for term in terms:
        for node in ui.nodes(tree):
            if node.get('package') != ui.pkg or 'EditText' in node.get('class',''): continue
            for label in ui.labels(node):
                if label==term or (starts and label.startswith(term)):
                    return node
    return None


def wait_node(get, seconds=18):
    start=time.monotonic()
    while time.monotonic()-start<seconds:
        result=get(ui.snapshot())
        if result is not None: return result
        time.sleep(.4)
    raise RuntimeError('missing UI control')


def active_player():
    state=ui.shell('dumpsys','activity','activities')
    return any('com.yfuse.feature.player.PlayerActivity' in line and any(x in line for x in ('ResumedActivity','topResumedActivity','mFocusedApp')) for line in state.splitlines())


def route(core, media):
    prefix=core+'-'+media['label']
    def stage(name,**details):
        measure.measured(prefix+'-'+name,**details)
    ui.shell('am','force-stop',ui.pkg)
    ui.adb('shell','am','start','-W','-n','com.yfuse/com.yfuse.MainActivity',t=80)
    source=wait_node(lambda t:ui.dock(t,'服务器'),42)
    ui.tap(source)
    wait_node(lambda t:matching(t,[server_name],True),22)
    registry=ET.fromstring(ui.shell('cat',prefs))
    native=json.loads(registry.find("./string[@name='servers.data']").text)
    if native.get('v') != 2 or not any(s.get('n')==server_name for s in native.get('s',[])):
        raise RuntimeError('session migration')
    stage('server',restored=True,progressSyncOff=registry.find("./boolean[@name='sync.progress']").get('value')=='false')
    ui.tap(wait_node(lambda t:ui.dock(t,'媒体库')))
    def search_link(t):
        dock=ui.dock(t,'搜索')
        return dock if dock is not None else matching(t,['搜索','搜索媒体'],True)
    search=wait_node(search_link)
    stage('library',opened=True)
    ui.tap(search)
    edit=wait_node(lambda t:next((n for n in ui.nodes(t) if 'EditText' in n.get('class','')),None))
    ui.tap(edit)
    term=media['series'] or media['name']
    value=base64.b64encode(term.encode()).decode()
    res=ui.shell('am','instrument','-w','-e','mode','searchtext','-e','query',value,'qa.yfuse.gesture/.Driver')
    if 'changed=true' not in res: raise RuntimeError('search input')
    ui.shell('input','keyevent','66')
    ui.hide_keyboard()
    def result(t):
        parents={c:n for n in t.iter() for c in n}
        for n in ui.nodes(t):
            if n.get('package')!=ui.pkg or term not in ui.labels(n): continue
            ancestor=n
            while ancestor is not None:
                if 'EditText' in ancestor.get('class',''): break
                ancestor=parents.get(ancestor)
            else:
                if ui.coords(n)[1] > ui.coords(edit)[1]+28:
                    return n
        return None
    ui.tap(wait_node(result,32))
    stage('search-result',matched=True)
    if media['series']:
        ep=media['episode']
        target=None
        for _ in range(6):
            target=matching(ui.snapshot(),[media['name'],f'第 {ep} 集',f'第{ep}集'],True)
            if target is not None:break
            w,h=ui.size()
            ui.shell('input','swipe',w//2,int(h*.79),w//2,int(h*.43),325)
        if target is None:raise RuntimeError('episode selection')
        ui.tap(target)
        stage('episode',selected=True)
    if not active_player():
        play=wait_node(lambda t:matching(t,['立即播放','继续播放','从头播放','播放','Play','Resume'],True),22)
        stage('detail',playVisible=True)
        ui.tap(play)
    deadline=time.monotonic()+20
    while not active_player() and time.monotonic()<deadline:time.sleep(.55)
    if not active_player():raise RuntimeError('internal player not opened')
    stage('open',internalPlayer=True)
    return prefix


def controls(prefix, media):
    for ordinal,fraction in enumerate(media['targets'],1):
        (left,top,right,bottom),_=measure.reveal()
        y=(top+bottom)//2
        origin=round(left+(right-left)*(.85 if fraction<.5 else .15))
        target=round(left+(right-left)*fraction)
        ui.shell('input','swipe',origin,y,target,y,320)
        measure.measured(prefix+'-drag',issued=True,ordinal=ordinal)
        proof=measure.decode_playback(prefix,round(media['seconds']*fraction),media['seconds'])
        measure.measured(prefix+'-landing',ordinal=ordinal,verified=True,**proof)
    _,tree=measure.reveal()
    old=measure.media_clock(tree)
    if old is None: raise RuntimeError('position unavailable')
    im=pilot.screen()
    x,y=round(im.width*.82),round(im.height*.38)
    ui.shell(f'input tap {x} {y}; sleep .09; input tap {x} {y}')
    forward=measure.decode_playback(prefix,old+10,media['seconds'])
    if forward['laterPos']-old < forward['secToConfirm']+4:raise RuntimeError('fast forward')
    measure.measured(prefix+'-fast-forward',verified=True,**forward)
    measure.measured(prefix+'-resume',verified=True,**pilot.pause_resume(prefix,media))


def main():
    payload=measure.load_envelope()
    pilot.engine.guard()
    failures=0
    try:
        for i,core in enumerate(('YCore','SenPlayer')):
            provision(core,payload['account'] if i==0 else None)
            for media in payload['samples']:
                prefix=core+'-'+media['label']
                try:
                    start=time.time()
                    route(core,media)
                    proof=measure.decode_playback(prefix,None,media['seconds'])
                    if not pilot.engine.engine_evidence(core,start)['active']:raise RuntimeError('unconfirmed engine')
                    measure.measured(prefix+'-load',verified=True,**proof)
                    controls(prefix,media)
                except Exception as error:
                    failures+=1
                    screen=ui.snapshot()
                    visible=[m for m in markers if matching(screen,[m],True) is not None]
                    measure.measured(prefix+'-failed',error=type(error).__name__,reason=str(error) if isinstance(error,RuntimeError) and str(error) in ('missing UI control','session migration','search input','episode selection','internal player not opened','unconfirmed engine','position unavailable','fast forward') else 'unverified',visible=visible,internalPlayer=active_player())
    finally:
        ui.shell('am','force-stop',ui.pkg)
        ui.shell('pm','clear',ui.pkg)
    if failures:raise SystemExit(1)

if __name__=='__main__':main()
