import os,time,json,subprocess,re
from pathlib import Path
from xml.etree import ElementTree as ET
import uiautomator2 as u2
temp=Path(os.environ['RUNNER_TEMP']);cfg=json.loads((temp/'real-config.json').read_text());out=temp/'private-review';out.mkdir(exist_ok=True);records={}
def sh(*args,raw=False,t=30):
    p=subprocess.run(['adb']+list(map(str,args)),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=t)
    if p.returncode:raise RuntimeError('device command')
    return p.stdout if raw else p.stdout.decode(errors='ignore')
def a(*args):return sh('shell',*args)
def record(k,res):records[k]=res;(out/'steps.json').write_text(json.dumps(records,ensure_ascii=False))
def tree():
    for attempt in range(2):
        try:return ET.fromstring(ui.dump_hierarchy(compressed=False,max_depth=60))
        except Exception:time.sleep(.3)
    return ET.Element('empty')
def texts(x):
    z=[]
    for n in x.iter('node'):
        v=n.get('text') or n.get('content-desc') or ''
        coords=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
        if n.get('package')==pkg and len(coords)==4 and v:z.append((v,coords))
    return z
def tp(x,y):a('input','tap',int(x),int(y))
def find(s,scroll=0):
    labels=s.split('|')
    for i in range(scroll+1):
        nodes=texts(tree())
        for label in labels:
            for v,b in nodes:
                if v==label or v.split('，')[0]==label or v.startswith(label+' ·'):
                    return ((b[0]+b[2])//2,(b[1]+b[3])//2)
        if i<scroll: a('input','swipe',350,1160,350,510,420);time.sleep(.45)
def tap(s,scroll=0):
    pt=find(s,scroll)
    if pt:tp(*pt);time.sleep(.5)
    return bool(pt)
def back():a('input','keyevent',4);time.sleep(.55)
def edit(s,val):
    p=find(s,3)
    if not p:raise ValueError('field missing')
    tp(*p);time.sleep(.3);a('input','keycombination',113,29);a('input','text',val);back()
def clean(v):
    for s in [cfg['password'],cfg['user'],cfg['url'],cfg['url'].split('/')[2].split(':')[0]]:
        if s:v=v.replace(s,'•••')
    v=re.sub(r'https?://[^\s]+','[链接]',v,flags=re.I);v=re.sub(r'[A-Fa-f0-9]{24,}','[ID]',v);return v
def snap(k):
    im=sh('exec-out','screencap','-p',raw=True)
    if im[:4]!=b'\x89PNG':return
    x=tree();ls=texts(x)
    if any(v in ['登录账号','请输入账号名','输入用户名','连接到服务器'] for v,b in ls):record(k,'still in form');return
    (out/(k+'.png')).write_bytes(im)
    (out/(k+'.json')).write_text(json.dumps([(clean(v),b) for v,b in ls],ensure_ascii=False));record(k,'captured')
def cold():
    a('am','force-stop',pkg);a('am','start','-W','-n',activity);time.sleep(1.1)
def login(tv=False):
    cold();tap('服务器');time.sleep(.6)
    if not tap('添加服务器|添加'):raise ValueError('server entry')
    if tv:
        tap('HTTPS');edit('主机或完整地址','127.0.0.1');edit('用户名','Audit')
        if not find('8096'):edit('80|443','8096')
        tap('连接',2)
    else:
        tap('HTTP');edit('media.example.com','127.0.0.1')
        if not find('8096'):edit('80|443','8096')
        edit('输入用户名','Audit');tap('连接到服务器',3)
    time.sleep(2.8)
    for _ in range(2):
        if not tap('仍然连接|继续连接|允许连接|允许'):break
    time.sleep(.9)
def launch_search(name):
    cold();time.sleep(1.6)
    for _ in range(4):
        e=ui(className='android.widget.EditText')
        if e.exists(timeout=.25):break
        if not tap('搜索'):tp(648,1276)
        time.sleep(.8)
    e=ui(className='android.widget.EditText')
    if not e.exists(timeout=1):return False
    e.set_text(name);a('input','keyevent',66);time.sleep(2.8);return True
def inspect():
    global ui,pkg,activity
    sh('reverse','tcp:8096','tcp:8096')
    a('settings','put','secure','immersive_mode_confirmations','confirmed');a('input','keyevent',82)
    if json.loads((out/'connectivity.json').read_text()).get('auth')!=200:
        record('real-server','Authentication or reachability incomplete');return
    a('wm','size','720x1440');a('wm','density',292)
    sh('install','-r','-g','apk/phone.apk',t=150);pkg=a('pm','list','packages','yfuse').strip().split(':')[-1];activity=pkg+'/com.yfuse.MainActivity'
    ui=u2.connect();ui.settings['wait_timeout']=2
    login();time.sleep(1.7);snap('live-phone-home')
    if any(v=='连接到服务器' for v,b in texts(tree())):
        record('first-form-diagnostic',[clean(v) for v,b in texts(tree()) if any(t in v for t in ['连接','失败','错误','有效','无效','允许','密码','认证','请输入','HTTP'])][:18])
        tap('连接到服务器',3);time.sleep(1.4)
    time.sleep(2);tp(355,375);time.sleep(3);snap('live-feature-detail')
    if any('剧情简介' in v or '资源比较' in v for v,b in texts(tree())):
        a('input','swipe',340,1120,340,480,500);time.sleep(1.4);snap('live-feature-lower')
        if tap('查看全部剧集|选集|第 1 季',1):time.sleep(2);snap('live-feature-episodes')
    cold();time.sleep(2);tap('媒体库');time.sleep(5);snap('live-phone-library')
    a('input','swipe',350,1120,350,500,520);time.sleep(1.3);snap('live-library-rows')
    if tap('全部',1):
        time.sleep(1.4);snap('live-library-grid');tp(115,350);time.sleep(2.2);snap('live-grid-detail')
    candidates=json.loads((out/'candidates.json').read_text())
    for kind,k in [('Series','series'),('Movie','movie')]:
        try:
            if not candidates.get(kind):continue
            options=[v for v in candidates[kind] if len(v['name'].strip())>=2] or candidates[kind]
            name=options[0]['name']
            if not launch_search(name):continue
            snap('live-search-'+k)
            if not tap(name,1):continue
            snap('live-'+k+'-detail')
            if kind=='Series':
                a('input','swipe',360,1140,360,490,490);time.sleep(.75);snap('live-series-lower')
                if tap('查看全部剧集|选集|第 1 季',2):snap('live-episodes')
            else:
                if tap('播放|继续播放|从头播放|立即播放',1):
                    time.sleep(2);snap('live-play-intent')
                    # Some releases expose a source or explicit "play" confirmation.
                    nodes=texts(tree())
                    if any(v in ['选择播放源','播放来源','选择播放版本'] for v,b in nodes):
                        if not tap('立即播放|播放|继续观看',1):
                            for v,b in nodes:
                                if '1080' in v or '2160' in v:
                                    tp((b[0]+b[2])/2,(b[1]+b[3])/2);break
                    time.sleep(5);snap('live-playing-a');time.sleep(2);snap('live-playing-b')
        except Exception as e:record(k,type(e).__name__)
    try:
        a('am','force-stop',pkg)
        sh('shell','am','start','-W','-a','android.intent.action.VIEW','-d','http://127.0.0.1:8096/review/video.mp4','-t','video/mp4','-n',pkg+'/com.yfuse.feature.player.ExternalPlaybackActivity')
        time.sleep(.65);tap('打开');time.sleep(3);snap('live-source-a')
        time.sleep(12);snap('live-source-b');time.sleep(12);snap('live-source-c');time.sleep(12);snap('live-source-d');tp(650,310);time.sleep(.3);snap('live-source-time')
    except Exception as e:record('real-source',type(e).__name__)
    try:
        a('am','force-stop',pkg)
        apk=next(Path('tvapk').rglob('*.apk'));sh('uninstall',pkg,t=40);sh('install','-r','-g',str(apk),t=150)
        packages=a('pm','list','packages','yfuse').strip().splitlines();pkg=next(x.split(':')[-1] for x in packages if x.startswith('package:'));activity=pkg+'/com.yfuse.tv.TvMainActivity'
        a('wm','size','1080x1920');a('wm','density',240);a('settings','put','system','accelerometer_rotation',0);a('settings','put','system','user_rotation',1)
        login(True);snap('live-tv-home');tap('媒体库');time.sleep(1);snap('live-tv-library')
        if tap('详情',1):
            time.sleep(3);snap('live-tv-detail');time.sleep(4);snap('live-tv-detail-settled')
    except Exception as e:record('television',type(e).__name__)
try:inspect()
except Exception as e:record('capture',type(e).__name__)
print('Real-media UI review finished.')
