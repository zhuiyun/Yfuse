"""Test a saved Emby session through Yfuse's own server, search, detail and player UI."""
import base64
import json
import re
import os
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
    old = ui.shell(f'if test -f {prefs}; then cat {prefs}; fi')
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
    tree=ui.snapshot()
    for n in ui.nodes(tree):
        if n.get('package')!=ui.pkg:continue
        values=' '.join(ui.labels(n))+ ' '+n.get('state','')
        if any(flag in values for flag in ('关闭播放','播放遇到问题','纯内核路径无法打开','播放进度','查看原因与导出日志','暂停播放')) or n.get('class','').endswith('SeekBar'):
            return True
    in_app=any(n.get('package')==ui.pkg for n in ui.nodes(tree))
    if in_app and (ui.find(tree,['字幕','音轨']) is not None and ui.find(tree,['倍速','片头片尾']) is not None):
        return True
    state=ui.shell('dumpsys','activity','activities')
    return any('PlayerActivity' in line and 'ExternalPlaybackActivity' not in line and any(x in line for x in ('ResumedActivity','topResumedActivity','mFocusedApp')) for line in state.splitlines())


def press_node(node, tree=None):
    if tree is None:
        ui.tap(node)
        return
    parents={child:parent for parent in tree.iter() for child in parent}
    current=node
    while current is not None:
        if current.get('clickable')=='true' and current.get('enabled')=='true':
            ui.tap(current)
            return
        current=parents.get(current)
    ui.tap(node)


def displays(tree):
    allow=['无法播放','加载超时','播放信息加载超时','无法连接','播放失败','准备播放','正在播放','继续观看','正在加载','加载失败','重试','从头播放','播放来源','选择播放来源','选择版本','详情','服务器','媒体库','搜索','播放','简介','演员','演职人员','收藏','稍后看','已看','下载','图克罗','第 3 集','剧集','暂时无法','网络','读取失败','错误','连接失败','取消','确认','知道了','选择','确认打开','允许','正在缓冲','播放进度','正在连接','其他来源','立即播放','版本','资源','线路','影片信息','搜索结果','搜索记录','回声']
    words=[word for node in ui.nodes(tree) for word in ui.labels(node)]
    return [flag for flag in allow if any(flag in word for word in words)]


def component():
    state=ui.shell('dumpsys','window','windows')
    return [part.rsplit('.',1)[-1] for part in re.findall(r'(?:mCurrentFocus|mFocusedApp)[^\n]*?com\.yfuse/([A-Za-z0-9_.]+)',state)][:2]


def capture(label):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    pixels=ui.adb('exec-out','screencap','-p',output=True)
    nonce=os.urandom(12)
    data=AESGCM(screenshot_key).encrypt(nonce,pixels,label.encode())
    (Path('private-results')/(label+'.bin')).write_bytes(nonce+data)


def collect_failure(prefix, since):
    """Keep new failure diagnostics encrypted with the per-run display key."""
    from datetime import datetime
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    raw=measure.adb('shell','cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl',check=False)
    keep=[]
    for line in raw.splitlines():
        try:
            event=json.loads(line)
            stamp=datetime.fromisoformat(event['timestamp'].replace('Z','+00:00')).timestamp()
        except (ValueError,KeyError,TypeError):
            continue
        if stamp < since or event.get('event') not in ('router_command_failed','native_enhanced_failed','native_direct_failed','video_decoder_candidates','internal_route_unavailable','startup_probe_aborted'):
            continue
        attrs=event.get('attributes') or {}
        # Restrict the export to decoder fields, exception types and JVM frame coordinates.
        fields={k:str(v)[:500] for k,v in attrs.items() if k.lower() in {
            'command','exceptiontype','origin','candidates','mime','requestedmime','decoder','decodername',
            'codecnames','route','failurecategory','inputhdr','dolbyguard','stage','width','height'}}
        exc=event.get('exception') or {}
        stack=str(exc.get('stackTrace') or exc.get('stack') or '')
        frames=re.findall(r'(?:^|\n)\s*at ([a-zA-Z0-9_.$<>]+\([a-zA-Z0-9_. :-]+\))',stack)[:32]
        known=('no decoder','no candidate','configure','insufficient resource','unsupported','bad value','failed to initialize','verification','could not initialize','failed resolution','no implementation found','index out of bounds','null pointer')
        hints=[h for h in known if h in (str(exc.get('message',''))+' '+stack[:650]).lower()]
        keep.append(dict(event=event['event'],fields=fields,exception=exc.get('type'),frames=frames,hints=hints))
    label=prefix+'-causes'
    nonce=os.urandom(12)
    encrypted=AESGCM(screenshot_key).encrypt(nonce,json.dumps(keep).encode(),label.encode())
    (Path('private-results')/(label+'.bin')).write_bytes(nonce+encrypted)


def route(core, media):
    prefix=core+'-'+media['label']
    def stage(name,**details):
        measure.measured(prefix+'-'+name,**details)
    ui.shell('am','force-stop',ui.pkg)
    ui.adb('shell','am','start','-W','-n','com.yfuse/com.yfuse.MainActivity',t=80)
    source=wait_node(lambda t:ui.dock(t,'服务器'),42)
    ui.tap(source)
    server_card=wait_node(lambda t:matching(t,[server_name],True),22)
    registry=ET.fromstring(ui.shell('cat',prefs))
    native=json.loads(registry.find("./string[@name='servers.data']").text)
    if native.get('v') != 2 or not any(s.get('n')==server_name for s in native.get('s',[])):
        raise RuntimeError('session migration')
    stage('server',restored=True,progressSyncOff=registry.find("./boolean[@name='sync.progress']").get('value')=='false')
    ui.tap(server_card)
    def search_link(t):
        dock=ui.dock(t,'搜索')
        return dock if dock is not None else matching(t,['搜索','搜索媒体'],True)
    search=wait_node(search_link)
    stage('library',opened=True)
    ui.tap(search)
    edit=wait_node(lambda t:next((n for n in ui.nodes(t) if 'EditText' in n.get('class','')),None))
    ui.hide_keyboard()
    term=media['series'] or media['name']
    value=base64.b64encode(term.encode()).decode()
    for attempt in range(4):
        res=ui.shell('am','instrument','-w','-e','mode','searchtext','-e','query',value,'qa.yfuse.gesture/.Driver')
        if 'changed=true' in res:break
        edit=wait_node(lambda t:next((n for n in ui.nodes(t) if 'EditText' in n.get('class','')),None))
        ui.tap(edit)
        ui.hide_keyboard()
    else: raise RuntimeError('search input')
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
    found=wait_node(result,35)
    ui.tap(found)
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
        keys=['立即播放','继续播放','从头播放','播放','Play','Resume']
        wait_node(lambda t:matching(t,keys),28)
        tree=ui.snapshot()
        play=matching(tree,keys)
        if play is None:raise RuntimeError('missing UI control')
        stage('detail',playVisible=True,labels=displays(tree),screen=component(),tap=ui.coords(play))
        capture(prefix+'-detail')
        press_node(play,tree)
    deadline=time.monotonic()+50
    seen=set()
    attempt=0
    while not active_player() and 'PlayerActivity' not in component() and time.monotonic()<deadline:
        tree=ui.snapshot()
        seen.update(displays(tree))
        if attempt==1:capture(prefix+'-screen')
        if attempt in (3,9):
            control=matching(tree,['立即播放','继续播放','从头播放','播放','Play','Resume'])
            if control is not None:press_node(control,tree)
        attempt+=1
        time.sleep(.4)
    stage('after-play-tap',seen=sorted(seen),screen=component())
    if not active_player() and 'PlayerActivity' not in component():raise RuntimeError('internal player not opened')
    capture(prefix+'-player')
    stage('open',internalPlayer=True)
    return prefix


def widget_route(core,media,account):
    prefix=core+'-'+media['label']
    ui.shell('am','force-stop',ui.pkg)
    ui.adb('shell','am','start','-W','-n','com.yfuse/com.yfuse.MainActivity',t=80)
    tab=wait_node(lambda t:ui.dock(t,'服务器'),42)
    ui.tap(tab)
    server=wait_node(lambda t:matching(t,[server_name],True),22)
    ui.tap(server)
    time.sleep(.8)
    ident=account['baseUrl']+'#'+account['userId']
    if not re.fullmatch(r'[a-zA-Z0-9:/#.\-]+',ident) or not str(media['id']).isdigit():
        raise RuntimeError('invalid target')
    ui.adb('shell','am','start','-W','-n','com.yfuse/com.yfuse.MainActivity','--es','widget_action','resume','--es','widget_server',ident,'--es','widget_item',media['id'],'--el','widget_position','0',t=65)
    measure.measured(prefix+'-native-widget',fullApp=True,issued=True)
    until=time.monotonic()+45
    while time.monotonic()<until:
        if active_player() or 'PlayerActivity' in component():
            capture(prefix+'-player')
            measure.measured(prefix+'-open',internalPlayer=True)
            return
        time.sleep(.7)
    raise RuntimeError('internal player not opened')


def playback_controls():
    # The player presents its own failure details. Tapping around this overlay can hide
    # the cause or accidentally pause playback; return the real player result immediately.
    toggled = False
    for _ in range(5):
        tree = ui.snapshot()
        if matching(tree, ['播放遇到问题', '播放状态']) is not None:
            raise RuntimeError('player error screen')
        tutorial = matching(tree, ['知道了', 'Got it'])
        if tutorial is not None:
            press_node(tutorial, tree)
            continue
        bounds = measure.rail(tree)
        if bounds:
            return bounds, tree
        if not toggled:
            im = pilot.screen()
            # A single tap on the left side reveals the chrome, outside the central play button.
            pilot.tap(im.width * .28, im.height * .39)
            toggled = True
        time.sleep(.45)
    raise AssertionError('no progress control')


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
    fast_forward(prefix, media)
    measure.measured(prefix+'-resume',verified=True,**pilot.pause_resume(prefix,media))


def fast_forward(prefix, media):
    _, tree = measure.reveal()
    old = measure.media_clock(tree)
    if old is None:
        raise RuntimeError('position unavailable')
    im = pilot.screen()
    x, y = round(im.width * .82), round(im.height * .38)
    ui.shell(f'input tap {x} {y}; sleep .09; input tap {x} {y}')
    measure.measured(prefix+'-fast-forward-input', issued=True, before=old)
    proof = measure.decode_playback(prefix, old + 10, media['seconds'])
    if proof['firstPos'] - old < proof['secToFirst'] + 4:
        raise RuntimeError('fast forward')
    measure.measured(prefix+'-fast-forward', verified=True, **proof)


def main():
    global screenshot_key
    payload=measure.load_envelope()
    screenshot_key=base64.b64decode(payload['screenKey'])
    pilot.engine.guard()
    measure.reveal = playback_controls
    failures=0
    try:
        cores=['YCore'] if payload.get('startupAudit') else ['YCore','SenPlayer']
        for i,core in enumerate(cores):
            provision(core,payload['account'] if i==0 else None)
            for media in payload['samples']:
                prefix=core+'-'+media['label']
                try:
                    start=time.time()
                    if payload.get('entry')=='widget':
                        widget_route(core,media,payload['account'])
                    else:
                        route(core,media)
                    time.sleep(2)
                    capture(prefix+'-screen')
                    if any('播放遇到问题' in v for n in ui.nodes(ui.snapshot()) for v in ui.labels(n)):
                        raise RuntimeError('player error screen')
                    proof=measure.decode_playback(prefix,None,media['seconds'])
                    selection=pilot.engine.engine_evidence(core,start)
                    if not selection['active']:
                        _,tree=measure.reveal()
                        legend=' '.join(val for n in ui.nodes(tree) for val in ui.labels(n))
                        expected=('SCore','SenPlayer') if core=='SenPlayer' else ('YCore',)
                        if not any(name in legend for name in expected):raise RuntimeError('unconfirmed engine')
                    capture(prefix+'-playing')
                    measure.measured(prefix+'-load',verified=True,**proof)
                    if payload.get('singleControl') == 'resume':
                        measure.measured(prefix+'-resume', verified=True, **pilot.pause_resume(prefix, media))
                    elif payload.get('singleControl') == 'fast-forward':
                        fast_forward(prefix, media)
                    elif not payload.get('startupAudit'):
                        controls(prefix,media)
                except Exception as error:
                    failures+=1
                    collect_failure(prefix,start)
                    capture(prefix+'-failed')
                    screen=ui.snapshot()
                    visible=[m for m in markers if matching(screen,[m],True) is not None]
                    events=[v for v in measure.app_events() if v.get('time')]
                    recent=events[-22:] if active_player() else []
                    measure.measured(prefix+'-failed',signals=[(v['event'],v['values'],v['hints']) for v in recent],
                                     error=type(error).__name__,reason=str(error) if isinstance(error,RuntimeError) and str(error) in ('missing UI control','session migration','search input','episode selection','internal player not opened','unconfirmed engine','player error screen','position unavailable','fast forward') else 'unverified',visible=visible,labels=displays(screen),screen=component(),internalPlayer=active_player())
    finally:
        ui.shell('am','force-stop',ui.pkg)
        ui.shell('pm','clear',ui.pkg)
    if failures:raise SystemExit(1)

if __name__=='__main__':main()
