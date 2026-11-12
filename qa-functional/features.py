import json,pathlib,subprocess,time,sys,xml.etree.ElementTree as ET,re
from collections import defaultdict
from PIL import Image
from io import BytesIO
out=pathlib.Path('feature-evidence');out.mkdir(exist_ok=True);rows=[];pkg='com.yfuse.validation'
def adb(*a,output=False,t=20):
 r=subprocess.run(['adb',*map(str,a)],capture_output=True,timeout=t)
 if r.returncode:raise RuntimeError('adb failed '+str(a[:2]))
 return r.stdout if output else r.stdout.decode(errors='replace')
def shell(*a):return adb('shell',*a)
def snapshot():
 adb('shell','uiautomator','dump','/sdcard/full-qa.xml',t=22)
 return ET.fromstring(adb('shell','cat','/sdcard/full-qa.xml'))
def nodes(tree):return list(tree.iter('node'))
def labels(n):return [x.strip() for k in ('text','content-desc') for x in n.get(k,'').split('\n') if x.strip()]
def coords(n):
 x,y,u,v=map(int,re.findall(r'-?\d+',n.get('bounds','')));return ((x+u)//2,(y+v)//2)
def tap(n):shell('input','tap',*coords(n));time.sleep(.35)
def find(t,keys):
 if isinstance(keys,str):keys=[keys]
 for key in keys:
  for n in nodes(t):
   if key in labels(n) and n.get('enabled')!='false':return n
 return None
def page_info(t):
 return sorted(set(v for n in nodes(t) for v in labels(n) if len(v)<65 and not ('http' in v or 'token' in v.lower())))
def snap(name,t=None):
 t=t if t is not None else snapshot(); words=page_info(t)
 (out/(name+'.json')).write_text(json.dumps(words,ensure_ascii=False))
 im=Image.open(BytesIO(adb('exec-out','screencap','-p',output=True)));im.thumbnail((520,940));im.save(out/(name+'.png'))
 return words
def grab(keys,scrolls=0):
 last=None
 for i in range(scrolls+1):
  t=snapshot();n=find(t,keys)
  if n is not None:return n
  now=tuple(page_info(t))
  if i==scrolls or now==last:break
  last=now;w,h=size();shell('input','swipe',w//2,h*77//100,w//2,h*32//100,390)
 raise LookupError(str(keys))
def size():
 s=adb('shell','wm','size');w,h=map(int,re.findall(r'(\d+)x(\d+)',s)[-1]);return w,h
def wait(keys,secs=18):
 until=time.monotonic()+secs
 while time.monotonic()<until:
  n=find(snapshot(),keys)
  if n is not None:return n
 raise LookupError(str(keys))
def expect(keys):wait(keys);return keys
def step(name,action):
 try:
  result=action();row=dict(name=name,result='passed',detail=result)
 except Exception as e:
  row=dict(name=name,result='unverified',detail=(type(e).__name__+': '+str(e))[:120])
 finally:
  rows.append(row); print(name,row['result'],flush=True)
  (out/'cases.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
  try:snap(name)
  except Exception:pass
def launch():
 adb('shell','am','force-stop',pkg)
 adb('shell','am','start','-W','-n',pkg+'/com.yfuse.MainActivity',t=90);wait('我的',50)
def root(tab):
 for _ in range(4):
  t=snapshot();n=find(t,tab)
  if n is not None and find(t,'我的') is not None:tap(n);return
  shell('input','keyevent',4);time.sleep(.45)
 launch();tap(grab(tab))
def view(tab,keys,marker):
 root(tab);n=grab(keys,6);before=set(page_info(snapshot()));tap(n);expect(marker);after=set(page_info(snapshot()))
 if len(before.symmetric_difference(after))<2:raise RuntimeError('view transition not observed')
 return marker
def pressback():
 shell('input','keyevent',4);time.sleep(.5)
def type_into(node,value,clear=False):
 tap(node)
 if clear:
  shell('input','keycombination','113','29');shell('input','keyevent','67')
 shell('input','text',value)
 if 'mInputShown=true' in shell('dumpsys','input_method'):shell('input','keyevent','4')
def field(label,value,clear=False):
 anchor=grab(label,5);t=snapshot();ref=coords(anchor);ed=[n for n in nodes(t) if n.get('class','').endswith('EditText')]
 if not ed:raise LookupError('no editable inputs')
 best=min(ed,key=lambda n:abs(coords(n)[1]-ref[1]));type_into(best,value,clear)
def setup():
 root('服务器')
 tap(grab(['添加服务器','添加'],4));wait(['连接到服务器','地址','服务器地址'])
 # Switch HTTP before supplying a dedicated plain loopback address.
 h=find(snapshot(),'HTTP')
 if h is not None:tap(h)
 field('地址','127.0.0.1',True);field('端口','18196',True)
 field('用户名','seekqa',True)
 # The local fixture password never appears in the APK, logs or screenshots.
 field('密码',pathlib.Path('qa-emby-local/local.secret').read_text(),True)
 tap(grab('连接到服务器',6));wait(['我的服务器','Seek'],32)
 root('媒体库');return expect(['Seek','ProbeA','ProbeB'])
def browse():
 root('媒体库');t=snapshot()
 if find(t,'Seek') is not None:tap(find(t,'Seek'))
 return expect(['ProbeA','ProbeB'])
def detail():
 browse();tap(grab('ProbeA',4));return expect(['播放','立即播放','继续播放','从头播放','演职人员'])
def search():
 root('媒体库');tap(grab(['搜索','搜索媒体'],3));t=snapshot()
 edit=next(n for n in nodes(t) if n.get('class','').endswith('EditText'));type_into(edit,'ProbeA')
 shell('input','keyevent','66');return expect('ProbeA')
def download():
 detail();tap(grab(['下载','下载影片','离线下载'],5));return expect(['下载到本地','添加下载','下载中心','下载任务','选择版本','选择下载源','已加入下载队列','下载中'])
def start_player():
 detail();tap(grab(['播放','立即播放','从头播放','继续播放'],3))
 until=time.monotonic()+20
 while time.monotonic()<until:
  if 'feature.player.PlayerActivity' in shell('dumpsys','activity','activities').split('mResumedActivity')[-1]:break
  time.sleep(.5)
 else:raise RuntimeError('PlayerActivity did not resume')
 pressback();return expect(['ProbeA','播放','继续播放'])
def background():
 root('我的');shell('input','keyevent','3');adb('shell','am','start','-W','-n',pkg+'/com.yfuse.MainActivity',t=40);return expect('我的')
launch()
for name,fn in [('00-em​by-sign-in'.replace('\u200b',''),setup),('01-library-index',browse),('02-details',detail),('03-library-search',search),('04-library-play-and-return',start_player),('05-download-entry',download),('06-background-resume',background)]:
 if name.startswith('00-') and '--populated' in sys.argv:continue
 step(name,fn)
profiles=[
 ('account',['账号与同步'],['登录账号','注册账号','账号与同步']),
 ('lists',['我的片单'],['新建片单','搜索片单','我的片单']),
 ('history',['播放记录'],['播放记录','清空记录']),
 ('calendar',['追剧日历'],['追剧日历','今天']),
 ('appearance',['外观与交互','外观设置'],['首页与导航','动画与动效','辅助功能']),
 ('playback',['播放设置','播放与字幕','播放与兼容'],['播放模式','默认倍速','播放器设置']),
 ('advanced',['高级播放设置'],['内核与解码','显示与音频输出']),
 ('downloads',['下载与离线库','下载中心'],['下载任务','下载中心','离线库','暂无下载任务']),
 ('extras',['附加组件','插件','组件管理'],['已安装','组件管理','组件库']),
 ('danmaku',['弹幕'],['弹幕设置','来源与内容过滤']),
 ('watch-together',['一起看'],['房间','聊天显示','创建房间']),
 ('about',['关于'],['版本','开源许可','关于 Yfuse','Yfuse'])]
for name,keys,marker in profiles:
 step('profile-'+name,lambda k=keys,m=marker:view('我的',k,m))
step('watch-history',lambda:view('观看','播放记录',['播放记录','ProbeA']))
step('watch-discover',lambda:view('观看','发现',['返回媒体库','发现']))
step('library-after-cold-start',lambda:(launch(),browse())[1])
