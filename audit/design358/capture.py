"""Exercise public Yfuse controls on a clean emulator. No real accounts are used."""
import hashlib, json, os, re, subprocess, sys, time
from pathlib import Path
from xml.etree import ElementTree as ET

AREA=sys.argv[1]; OUT=Path('evidence'); OUT.mkdir(exist_ok=True)
def run(*args, binary=False, timeout=45):
    p=subprocess.run(list(map(str,args)),capture_output=True,timeout=timeout)
    if p.returncode: raise RuntimeError(p.stderr.decode(errors='replace')[-350:])
    return p.stdout if binary else p.stdout.decode(errors='replace').strip()
def adb(*args,**kw): return run('adb',*args,**kw)
def shell(*args): return adb('shell',*args)
assert shell('getprop','ro.kernel.qemu')=='1'
apks=list(Path('apk').rglob('*.apk')); a=next((a for a in apks if 'debug' in a.name),apks[0])
if AREA!='tv': assert hashlib.sha256(a.read_bytes()).hexdigest()=='9e4d22d18b6cb9ad93a0968de8dd17a07caa5dc18f7c3fcab5161c9404b46652'
adb('install','-r','-g',a,timeout=150)
PKG=shell('pm','list','packages','yfuse').splitlines()[0].split(':')[-1]
activity=PKG+('/com.yfuse.tv.TvMainActivity' if AREA=='tv' else '/com.yfuse.MainActivity')
shell('settings','put','global','hide_error_dialogs','1')
shell('input','keyevent','82')
shell('wm','size','1080x2160' if AREA!='tv' else '1080x1920')
shell('wm','density','440' if AREA!='tv' else '240')
if AREA=='tv':shell('settings','put','system','accelerometer_rotation','0');shell('settings','put','system','user_rotation','1')
if AREA=='media':adb('reverse','tcp:8096','tcp:8096')
try:
    import uiautomator2 as u2
    ui=u2.connect(); ui.settings['wait_timeout']=3
except Exception:ui=None
def launch():
    shell('am','start','-W','-n',activity);time.sleep(2.0)
def tree():
    for _ in range(3):
        try:
            if ui is not None: s=ui.dump_hierarchy(compressed=False)
            else: shell('uiautomator','dump','--compressed','/sdcard/audit.xml'); s=shell('cat','/sdcard/audit.xml')
            x=ET.fromstring(s)
            if len(list(x.iter('node')))>2:return x
        except Exception:pass
        try:
            shell('uiautomator','dump','--compressed','/sdcard/tree.xml')
            x=ET.fromstring(shell('cat','/sdcard/tree.xml'))
            if len(list(x.iter('node')))>2:return x
        except Exception:pass
        time.sleep(.5)
    raise ValueError('UI tree unavailable')
def rect(n):return tuple(map(int,re.findall(r'\d+',n.get('bounds',''))))
def mid(n):
    l,t,r,b=rect(n);return ((l+r)//2,(t+b)//2)
def visible(x):
    return [(n,n.get('text') or n.get('content-desc') or '') for n in x.iter('node') if n.get('package')==PKG and len(rect(n))==4 and rect(n)[2]>rect(n)[0] and rect(n)[3]>rect(n)[1]]
def labels(x):return [s for _,s in visible(x) if s]
def tap(n):shell('input','tap',*mid(n));time.sleep(.55)
def back():shell('input','keyevent','4');time.sleep(.65)
def swipe(d,horiz=False):
    size=shell('wm','size');a,b=map(int,re.findall(r'(\d+)x(\d+)',size)[-1])
    if AREA=='tv':a,b=max(a,b),min(a,b)
    if horiz:shell('input','swipe',int(a*.83),int(b*.49),int(a*.22),int(b*.49),320)
    else:shell('input','swipe',a//2,int(b*(.77 if d>0 else .30)),a//2,int(b*(.29 if d>0 else .77)),380)
    time.sleep(.32)
def opt(s): return s.split('|')
def find(label,scroll=0,choice='middle'):
    opts=opt(label)
    def look(x):
        items=visible(x)
        for name in opts:
            hits=[n for n,s in items if (s==name or (s.split('\n')[0]==name))]
            if not hits: hits=[n for n,s in items if s.startswith(name+'，') or s.startswith(name+' ·')]
            if hits:
                return max(hits,key=lambda n:mid(n)[1]) if (choice=='bottom' or name=='外观与交互') else hits[0]
    prev=None
    for direction in ([1,-1] if scroll else [1]):
        for _ in range(scroll+1):
            x=tree();n=look(x)
            if n is not None:return n
            text=labels(x)
            if not scroll or text==prev:break
            prev=text;swipe(direction)
        prev=None
    return None
records=[]
def note(key,status,detail):
    records.append(dict(key=key,status=status,detail=detail))
    (OUT/'index.json').write_text(json.dumps(records,ensure_ascii=False,indent=2));print(AREA,key,status,flush=True)
def capture(key):
    x=tree()
    if not visible(x):raise ValueError('other window')
    png=adb('exec-out','screencap','-p',binary=True)
    assert png.startswith(b'\x89PNG')
    (OUT/(key+'.png')).write_bytes(png); (OUT/(key+'.xml')).write_bytes(ET.tostring(x,encoding='utf-8'))
    note(key,'captured',labels(x)[:90]);return x
def root(tab):
    shell('am','force-stop',PKG);launch()
    for i in range(10):
        n=find(tab,choice='bottom')
        if n is not None:tap(n);return
        time.sleep(.6)
    raise ValueError('root tab '+tab)
def click(label,scroll=3):
    n=find(label,scroll)
    if n is None:raise ValueError('absent: '+label)
    tap(n)
def path(key,tab,steps):
    try:
        root(tab)
        for label in steps:click(label)
        return capture(key)
    except Exception as e:note(key,'blocked',str(e)[:200])
def paths(tab,entries):
    for key,*steps in entries:path(key,tab,steps)
def field(placeholder,value):
    click(placeholder);shell('input','text',value);time.sleep(.35)
    if 'mInputShown=true' in shell('dumpsys','input_method') or 'mIsInputViewShown=true' in shell('dumpsys','input_method'):back()
def media():
    root('服务器');click('添加服务器|添加');capture('media-form')
    click('HTTP');field('media.example.com','127.0.0.1')
    x=tree()
    if '8096' not in labels(x):
        n=find('80|443|8096',2)
        if n is not None:
            tap(n);shell('input','keycombination','113','29');shell('input','text','8096');back()
    field('输入用户名','Audit')
    for label in ['我了解风险|仍然使用 HTTP|仍要使用 HTTP|允许 HTTP 连接','连接到服务器']:
        n=find(label,2)
        if n is not None:tap(n)
    time.sleep(3);capture('media-after-connect')
    paths('媒体库',[('media-library',),('media-collection','Audit Library'),('media-detail','Audit Library','Audit Film'),('media-series','Audit Library','Audit Series')])
    root('媒体库');click('Audit Library');click('Audit Film')
    capture('media-film');click('继续播放|播放|立即播放|从头播放');time.sleep(5)
    def controls():
        t=tree()
        if not any(any(w in st for w in ('音轨','字幕','倍速','播放设置')) for st in labels(t)):
            size=shell('wm','size');width,height=map(int,re.findall(r'(\d+)x(\d+)',size)[-1]);shell('input','tap',int(height*.47),int(width*.47));time.sleep(.38)
    controls();capture('media-player')
    for key,label in [('subtitles','字幕|字幕轨道'),('audio','音轨|音频轨道'),('speed','倍速|播放速度'),('engine','设置|播放设置')]:
        try:controls();click(label,0);capture('player-'+key);back()
        except Exception as e:note('player-'+key,'blocked',str(e))
def appearance():
    basic='外观与交互'
    paths('我的',[(k,basic,*st) for k,st in [('appearance',[]),('glass',['玻璃材质']),('splash',['Logo 与开屏动画']),('home-layout',['首页与导航']),('widget-manager',['首页与导航','首页与组件管理']),('motion',['动画与动效']),('motion-theme',['动画与动效','动效主题']),('dialog',['动画与动效','弹窗动画']),('loading',['动画与动效','加载动画']),('transition',['动画与动效','播放器进出场']),('accessibility',['辅助功能'])]])
    if path('dialog-start','我的',[basic,'动画与动效','弹窗动画']) is not None:
        for i in range(23):
            x=tree();n=find('液态成形')
            if n is not None:tap(n);capture('dialog-liquid');break
            swipe(1,True)
        else:note('dialog-liquid','blocked',labels(tree())[:20])
def navigation():
    for tab,key in [('媒体库','library-empty'),('服务器','servers'),('自定义首页','custom-home'),('观看','watching'),('搜索','search'),('我的','profile-root')]:path(key,tab,[])
    paths('观看',[('discovery','发现'),('calendar','追剧日历')]);paths('服务器',[('add-server','添加服务器|添加'),('iptv','IPTV · 电视直播'),('wifi','Wi-Fi 导入视频'),('file-source','添加文件来源')])
    path('large-font-base','观看',['发现']);shell('settings','put','system','font_scale','1.3');time.sleep(3);capture('large-font')
    shell('settings','put','system','accelerometer_rotation','0');shell('settings','put','system','user_rotation','1');time.sleep(3);capture('landscape')
def profile():
    paths('我的',[(k,*st) for k,st in [('account',['账号与同步']),('login',['账号与同步','登录账号']),('watchlater',['我的片单']),('favorites',['我的片单','收藏']),('history',['我的片单','观看历史']),('list-search',['我的片单','搜索片单']),('statistics',['观影统计']),('family',['家庭资料']),('sync',['同步状态与恢复']),('handoff',['设备接力']),('trakt',['Trakt']),('stremio',['Stremio 插件']),('room',['一起看']),('downloads',['下载|下载与离线库']),('diagnostics',['数据与诊断'])]])
def playback_settings():
    paths('我的',[(k,*st) for k,st in [('playback',['播放']),('advanced',['播放','高级播放设置|高级播放']),('kernel',['播放','播放内核|默认播放内核']),('danmaku',['弹幕']),('storage',['下载|下载与离线库','离线媒体库|离线库']),('server-backup',['数据与诊断','服务器备份与迁移|服务器备份']),('diagnostics-settings',['数据与诊断']),('setting-search',['搜索设置']),('sync-account',['账号与同步','同步状态与恢复']),('devices',['账号与同步','登录设备|设备与会话|会话管理'])]])
def tv():
    launch();time.sleep(4);capture('tv-start')
    for i in range(9):
        shell('input','keyevent','21');time.sleep(.12)
    capture('tv-rail-focus')
    for label,key in [('首页','tv-home'),('媒体库','tv-library'),('搜索','tv-search'),('追剧日历','tv-calendar'),('服务器','tv-servers'),('设置','tv-settings')]:
        try:click(label,0);capture(key)
        except Exception as e:note(key,'blocked',str(e))
    for k,label in [('tv-appearance','外观与辅助'),('tv-account','账号与同步'),('tv-playback','播放'),('tv-downloads','下载与离线库'),('tv-permission','权限检查'),('tv-diagnostics','数据与诊断')]:
        try:click('设置',0);click(label);capture(k);back()
        except Exception as e:note(k,'blocked',str(e))
    for k in [20,20,22,19]:
        shell('input','keyevent',str(k));time.sleep(.38)
    capture('tv-dpad')
def snap(k):
    try:return capture(k)
    except Exception as e:
        (OUT/(k+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True));note(k,'pixels-only',str(e))
def safe(k,fn):
    try:fn();time.sleep(.55);return snap(k)
    except Exception as e:note(k,'blocked',str(e)[:180])
def tvextra():
    def start(k,*steps):
        def visit():
            shell('am','force-stop',PKG);launch();time.sleep(.6)
            for i in steps:click(i,5)
        return safe(k,visit)
    start('tv-settings','我的与设置');start('tv-calendar','首页','追剧日历')
    start('tv-add-server','服务器','添加服务器')
    entries={'appearance':'外观与辅助','account':'账号与同步','personal':'个人中心','stats':'观影统计','family':'家庭资料','sync':'同步状态','handoff':'设备接力','phone-remote':'手机遥控','trakt':'Trakt','stremio':'Stremio 插件','engine':'高级播放','playback':'播放','comments':'弹幕','party':'一起看','download':'下载与离线库','backup':'服务器备份与迁移','permissions':'权限检查','diagnostics':'数据与诊断'}
    for k,v in entries.items():start('tv-'+k,'我的与设置',v)
def external():
    adb('reverse','tcp:8096','tcp:8096')
    adb('push','/tmp/yfuse-design-media/clip.mp4','/sdcard/Movies/audit.mp4')
    shell('am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d','file:///sdcard/Movies/audit.mp4')
    def openclip():
        shell('am','start','-W','-a','android.intent.action.VIEW','-d','file:///sdcard/Movies/audit.mp4','-t','video/mp4','-n',PKG+'/com.yfuse.feature.player.ExternalPlaybackActivity')
        time.sleep(3)
    openclip();snap('clip-open')
    def ctr():
        shell('input','tap',1030,450);time.sleep(.35)
    ctr();snap('clip-controls')
    for k,names in [('tracks','字幕|字幕设置|字幕选择'),('audio','音轨|音频|音轨选择'),('speed','倍速|1.0x|1.0×|播放速度'),('settings','设置|播放设置|更多|更多操作'),('playlist','播放列表|选集')]:
        try:
            n=find(names,0)
            if n is None:ctr();n=find(names,0)
            if n is None:raise ValueError('absent '+names)
            tap(n);snap('clip-'+k);back()
        except Exception as e:note('clip-'+k,'blocked',str(e))
    ctr();shell('input','swipe','480','480','1160','480','420');snap('clip-after-seek')
def gallery():
    root('我的');click('外观与交互');click('动画与动效');click('弹窗动画');snap('gallery-first')
    for _ in range(26):
        x=tree();matches=[(n,s) for n,s in visible(x) if '液态成形' in s or '液态成型' in s]
        if matches:
            tap(matches[0][0]);snap('gallery-liquid-picked')
            time.sleep(.5);snap('gallery-liquid-settled');return
        cards=[n for n in x.iter('node') if n.get('scrollable')=='true' and rect(n)[2]-rect(n)[0]>500]
        items=[n for n,s in visible(x) if s in ['柔和浮起','底部升起','磁悬停泊','光栅百叶']];y=mid(items[0])[1] if items else (mid(min(cards,key=lambda n:rect(n)[3]-rect(n)[1]))[1] if cards else 1140)
        shell('input','swipe','935',y,'164',y,'440');time.sleep(.35)
    snap('gallery-last')
def widgets():
    for k,tab,seq in [('widgets-home','自定义首页',[]),('widgets-manage','自定义首页',['组件管理|管理组件|管理']),('widgets-add','自定义首页',['组件管理|管理组件|管理','添加组件|添加']),('iptv-home','服务器',['IPTV · 电视直播']),('iptv-add','服务器',['IPTV · 电视直播','添加订阅|添加|新增订阅']),('search-text','搜索',[])]:
        path(k,tab,seq)
    if ui is not None:
        try:ui(className='android.widget.EditText').set_text('Audit');shell('input','keyevent','66');time.sleep(1);snap('search-results')
        except Exception as e:note('search-results','blocked',str(e))
    root('观看');shell('cmd','uimode','night','yes');time.sleep(1);snap('night-watch')
def form2():
    adb('reverse','tcp:8096','tcp:8096');root('服务器');click('添加服务器');click('HTTP');time.sleep(1)
    def edit(placeholder,text):
        n=find(placeholder,5)
        if n is None:raise ValueError(placeholder)
        tap(n);shell('input','keycombination','113','29');time.sleep(.15);shell('input','text',text);time.sleep(.4);back();time.sleep(.4)
    snap('form-http');edit('media.example.com','127.0.0.1');snap('form-host')
    if '8096' not in labels(tree()): edit('80|443','8096')
    edit('输入用户名','Audit');snap('form-ready');click('连接到服务器');time.sleep(2);snap('server-submitted')
    for i in range(2):
        for k in ['仍然连接|继续连接|确认连接|继续|允许|连接']:
            n=find(k,0)
            if n is not None:tap(n);time.sleep(1)
    snap('server-final')
    for tab,name in [('媒体库','filled-library'),('观看','filled-watch'),('搜索','filled-search'),('服务器','filled-servers')]:path(name,tab,[])
    root('媒体库')
    for k,l in [('collection','Audit Library|电影'),('film','Audit Film'),('actor','Audit Actor')]:
        if safe('filled-'+k,lambda l=l:click(l,4)) is None:break
    root('搜索')
    if ui is not None:
        ui(className='android.widget.EditText').set_text('Audit');shell('input','keyevent','66');time.sleep(1);snap('filled-results')
    for k,l in [('series','Audit Series'),('seasons','第 1 季|Season 1|选集')]:
        if safe('filled-'+k,lambda l=l:click(l,3)) is None:break
def pausecapture(k):
    time.sleep(1.1);return snap(k)
def profilelink(section):
    root('我的');time.sleep(1);swipe(1);time.sleep(.4);click(section,5);time.sleep(1.15)
def appearance3():
    profilelink('外观与交互');pausecapture('style-overview')
    for k,l in [('glass','玻璃材质'),('splash','Logo 与开屏动画'),('layout','首页与导航'),('access','辅助功能'),('motion','动画与动效')]:
        try:
            click(l,5);pausecapture('style-'+k)
            if k=='motion':
                for key,lab in [('theme','动效主题'),('loading','加载动画'),('enter','播放器进出场'),('dialog','弹窗动画')]:
                    try:click(lab,5);pausecapture('style-'+key);back();time.sleep(1)
                    except Exception as e:note('style-'+key,'blocked',str(e))
            back();time.sleep(.8)
        except Exception as e:note('style-'+k,'blocked',str(e));profilelink('外观与交互')
def gallery3():
    profilelink('外观与交互');click('动画与动效',5);time.sleep(.9);click('弹窗动画',5);time.sleep(1);snap('picker-first')
    for i in range(35):
        x=tree()
        opts=[(n,s) for n,s in visible(x) if s=='液态成形' or s=='液态成型']
        if opts:
            tap(opts[0][0]);time.sleep(1.05);snap('picker-selected')
            prev=next((n for n,s in visible(tree()) if '预览' in s and (s.startswith('预览') or s.startswith('播放预览') or s.startswith('查看预览'))),None)
            if prev is not None:
                rec=subprocess.Popen(['adb','shell','screenrecord','--size','544x1088','--bit-rate','2000000','--time-limit','4','/sdcard/audit-motion.mp4'])
                time.sleep(.85);tap(prev);time.sleep(1);snap('picker-preview');rec.wait(timeout=15);adb('pull','/sdcard/audit-motion.mp4',str(OUT/'liquid-preview.mp4'))
            return
        cards=[n for n in x.iter('node') if n.get('scrollable')=='true' and rect(n)[2]-rect(n)[0]>450]
        if not cards: y=1080
        else:y=mid(min(cards,key=lambda n:rect(n)[3]-rect(n)[1]))[1]
        if i in (3,7,13):snap('picker-swipe-'+str(i))
        shell('input','swipe','919',y,'170',y,'630');time.sleep(.55)
    snap('picker-last')
def play3():
    for k,section,later in [('play','播放设置',[]),('advanced','播放设置',['高级播放设置']),('danmaku','弹幕设置',[]),('downloads','下载与离线库',['下载设置']),('links','打开链接',[]),('diagnostics','数据与诊断',[]),('family-add','家庭资料',['新建家庭资料']),('stremio-install','Stremio 插件',['安装插件']),('register','账号与同步',['没有账号？创建一个'])]:
        try:
            profilelink(section)
            for l in later:click(l,6);time.sleep(1)
            pausecapture('setting-'+k)
        except Exception as e:note('setting-'+k,'blocked',str(e))
def editor3():
    root('自定义首页');time.sleep(2);snap('custom-home-empty');click('开始布置首页|编辑首页|编辑');time.sleep(1);snap('custom-manager')
    for k,l in [('install','安装组件|添加组件'),('backup','备份与恢复'),('newpage','新建页面'),('builtin','恢复TMDB|恢复影视推荐')]:
        try:
            if k=='newpage':click('页面 0|页面 1|页面 2');time.sleep(.7)
            click(l,4);pausecapture('custom-'+k)
            if k!='builtin':back();time.sleep(.8)
        except Exception as e:note('custom-'+k,'blocked',str(e))
def discover3():
    root('观看');click('发现');time.sleep(8);snap('discover-loaded')
    try:
        ts=labels(tree());n=next((n for n,s in visible(tree()) if s in ['查看详情','详情','海报'] or s.startswith('查看详情')),None)
        if n:tap(n)
        else:shell('input','tap','520','580')
        time.sleep(4);snap('discover-tap');back();time.sleep(2)
    except Exception as e:note('discover-tap','blocked',str(e))
    root('观看');click('发现');time.sleep(4);swipe(1);time.sleep(1);snap('discover-rows')
    try:click('查看全部|全部|更多',3);time.sleep(2);snap('discover-full-grid')
    except Exception as e:note('discover-grid','blocked',str(e))
    root('观看');click('追剧日历');time.sleep(4);snap('calendar-settled')
    for k,l in [('tracked','追剧'),('resources','资源'),('options','设置')]:
        safe('calendar-'+k,lambda l=l:click(l,0))
def overlays3():
    for k,lab in [('iptv','IPTV · 电视直播'),('wifi','Wi-Fi 导入视频')]:
        root('服务器');click(lab);time.sleep(2);snap(k+'-content')
        if k=='iptv':
            try:click('添加订阅|添加|新增订阅|添加 M3U 订阅',1);pausecapture('iptv-form')
            except Exception as e:note('iptv-form','blocked',str(e))
    root('服务器');time.sleep(1);shell('input','swipe','580','1700','580','680','520');pausecapture('servers-bottom')
def choose(label,wait=1):
    n=find(label,4)
    if n is None:raise ValueError(label)
    tap(n);time.sleep(wait)
def settings_route():
    for attempt in range(3):
        root('我的');time.sleep(1.8)
        for step in range(6):
            x=tree()
            if labels(x)[:2]==['返回','外观与交互']:return
            targets=[n for n,s in visible(x) if s.startswith('深色 · ') or s.startswith('跟随系统 · ') or s.startswith('浅色 · ')]
            if targets and 200<mid(targets[-1])[1]<1780:
                tap(targets[-1]);time.sleep(1.4)
                if '背景图' in labels(tree()) or '视觉效果' in labels(tree()):return
                break
            shell('input','swipe','530','1570','530','1050','470');time.sleep(1)
    raise ValueError('appearance destination')
def pick():
    settings_route();choose('动画与动效');choose('弹窗动画');snap('liquid-first')
    for i in range(22):
        n=find('液态成形')
        if n is not None and 155<mid(n)[0]<914:
            tap(n);time.sleep(1.1);snap('liquid-chosen');break
        shell('input','swipe','885','965','260','965','560');time.sleep(.75)
    else:snap('liquid-last');return
    n=find('预览：液态成形')
    if n is None:return
    rec=subprocess.Popen(['adb','shell','screenrecord','--size','544x1088','--bit-rate','2200000','--time-limit','5','/sdcard/liquid.mp4'])
    time.sleep(1);tap(n);time.sleep(1.3);snap('liquid-visible');rec.wait(timeout=24);adb('pull','/sdcard/liquid.mp4',str(OUT/'liquid-preview.mp4'))
def sampleplayer():
    adb('reverse','tcp:8096','tcp:8096')
    shell('am','start','-W','-a','android.intent.action.VIEW','-d','http://127.0.0.1:8096/Videos/m01/stream.mp4','-t','video/mp4','-n',PKG+'/com.yfuse.feature.player.ExternalPlaybackActivity')
    time.sleep(.7);snap('net-confirm')
    n=find('打开')
    if n is not None:tap(n)
    time.sleep(6);snap('net-playing')
    def ensure():
        x=tree()
        if any(any(k in s for k in ['倍速','音轨','字幕','更多设置','播放器设置','播放设置','暂停播放','播放速度']) for s in labels(x)):return
        shell('input','tap','1000','460');time.sleep(.22)
    ensure();snap('net-controls')
    for k,ss in [('options',['更多','播放器设置','播放设置','设置']),('subtitle',['字幕','字幕轨道','字幕设置']),('audio',['音轨','音频','音轨选择']),('speed',['倍速','播放速度','1.0','1.0x','1x','1×','1.0×']),('queue',['播放列表','选集'])]:
        ensure();x=tree()
        hits=[n for n,s in visible(x) if s and any(s==t or s.startswith(t+'，') or s.startswith(t+' ') or s.startswith(t+'：') for t in ss)]
        if hits:
            tap(hits[0]);time.sleep(.8);snap('net-'+k);back();time.sleep(.5)
        else:note('net-'+k,'blocked','no visible control')
    shell('input','swipe','470','450','1150','450','600');time.sleep(.35);snap('net-seek')
def server():
    try:form2()
    except Exception as e:
        note('server-attempt','blocked',str(e));snap('server-window')
    if any('连接到服务器'==s for s in labels(tree())):
        for i in range(2):
            n=find('连接到服务器',0)
            if n is not None:tap(n);time.sleep(1.0)
            snap('server-retry-'+str(i))
    root('观看');time.sleep(3);snap('online-history')
    root('媒体库');time.sleep(3);snap('online-library')
    root('观看');time.sleep(1);choose('发现');time.sleep(5);snap('online-discovery')
def public():
    root('观看');time.sleep(1.8);choose('发现');time.sleep(6);snap('tmdb-cover')
    shell('input','swipe','550','1780','550','830','650');time.sleep(1);snap('tmdb-rows')
    items=[(n,s) for n,s in visible(tree()) if len(s)>1 and s not in ['影视推荐','返回媒体库','调整推荐栏目','正在加载','发现','播放','收藏'] and ('，满分' not in s and '今日精选' not in s)]
    if items:
        opts=[n for n,s in items if mid(n)[1]>1000 and mid(n)[1]<1750]
        if opts:tap(opts[0]);time.sleep(2);snap('tmdb-info')
    root('观看');time.sleep(1.5);choose('追剧日历');time.sleep(3);snap('calendar-landing')
    for k,label in [('follow','追剧'),('resource','资源'),('setting','设置')]:
        try:choose(label);snap('calendar-'+k)
        except Exception as e:note('calendar-'+k,'blocked',str(e))
try:
    launch();time.sleep(1.8);{'liquid':pick,'player':sampleplayer,'media':server,'discovery':public}[AREA]()
except Exception as e:note('audit-stop','blocked',str(e)[:180]);snap('last-window')
