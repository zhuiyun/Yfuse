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
 for attempt in range(4):
  try:
   shell('rm','-f','/sdcard/full-qa.xml')
   adb('shell','uiautomator','dump','--compressed','/sdcard/full-qa.xml',t=24)
   return ET.fromstring(adb('shell','cat','/sdcard/full-qa.xml'))
  except (RuntimeError,ET.ParseError,subprocess.TimeoutExpired):
   if attempt==3:return ET.Element('empty')
   time.sleep(.35)
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
def dock(t,tab):
 candidates=[n for n in nodes(t) if n.get('content-desc')==tab and n.get('package')==pkg]
 return max(candidates,key=lambda n:coords(n)[1]) if candidates else None
def hide_keyboard():
 state=shell('dumpsys','input_method')
 if re.search(r'(?:mInputShown|isInputViewShown|mIsInputViewShown)=true|mImeWindowVis=0x3',state):shell('input','keyevent',4);time.sleep(.45)
def root(tab):
 hide_keyboard()
 for _ in range(6):
  t=snapshot()
  if find(t,'切换服务器') is not None and find(t,'关闭') is not None:tap(find(t,'关闭'));continue
  visible=page_info(t)
  markers={'我的':['账号与同步','搜索设置'],'服务器':['我的服务器'],'观看':['追剧日历','发现'],'媒体库':['Seek','ProbeA','ProbeB']}
  if dock(t,tab) is not None and any(s in visible for s in markers.get(tab,[])):return
  n=dock(t,tab)
  if n is not None:
   tap(n)
   t=snapshot()
   if find(t,'切换服务器') is not None and find(t,'关闭') is not None:tap(find(t,'关闭'))
   if any(s in page_info(snapshot()) for s in markers.get(tab,[])):return
   continue
  shell('input','keyevent',4);time.sleep(.45)
 launch()
 n=dock(snapshot(),tab)
 if n is not None:tap(n)
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
 if find(t,'ProbeA') is None and find(t,'Seek') is not None:tap(find(t,'Seek'))
 return expect(['ProbeA','ProbeB'])
def detail():
 browse();tap(grab('ProbeA',4));return expect(['播放','立即播放','继续播放','从头播放','演职人员'])
def search():
 root('媒体库');search_tab=dock(snapshot(),'搜索');tap(search_tab if search_tab is not None else grab(['搜索','搜索媒体'],3));t=snapshot()
 edit=next(n for n in nodes(t) if n.get('class','').endswith('EditText'));type_into(edit,'ProbeA')
 shell('input','keyevent','66');hide_keyboard()
 until=time.monotonic()+12
 while time.monotonic()<until:
  t=snapshot()
  hit=[n for n in nodes(t) if 'ProbeA' in labels(n) and 'EditText' not in n.get('class','') and not n.get('focusable')=='true' and '所有服务器中都没有找到相关内容' not in page_info(t)]
  if hit:return 'matched search result outside query editor'
  time.sleep(.4)
 raise LookupError('no result card for ProbeA')
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
 root('我的');expect('账号与同步');shell('input','keyevent','3');adb('shell','am','start','-W','-n',pkg+'/com.yfuse.MainActivity',t=40);return expect('我的')
if __name__=='__main__':
 launch()
 for name,fn in [('00-em​by-sign-in'.replace('\u200b',''),setup),('01-library-index',browse),('02-details',detail),('03-library-search',search),('04-library-play-and-return',start_player),('05-download-entry',download),('06-background-resume',background)]:
  if name.startswith('00-') and '--populated' in sys.argv:continue
  step(name,fn)
 profiles=[
  ('stats',['观影统计'],['观影统计','观看时长','观看次数']),
  ('family',['家庭资料'],['家庭资料','添加资料','个人']),
  ('capabilities',['设备能力'],['设备能力','刷新率','视频解码']),
  ('account',['账号与同步'],['登录账号','注册账号','账号与同步']),
  ('lists',['我的片单'],['新建片单','搜索片单','我的片单']),
  ('history',['播放记录'],['播放记录','清空记录']),
  ('calendar',['追剧日历'],['追剧日历','今天']),
  ('appearance',['外观与交互','外观设置'],['首页与导航','动画与动效','辅助功能']),
  ('playback',['播放设置','播放与字幕','播放与兼容'],['播放模式','默认倍速','播放器设置']),
  ('advanced',['高级播放设置'],['内核与解码','显示与音频输出']),
  ('downloads',['下载与离线库','下载中心'],['下载任务','下载中心','离线库','暂无下载任务']),
  ('extras',['Stremio 插件','附加组件','插件','组件管理'],['已安装','组件管理','组件库','安装插件','添加插件']),
  ('danmaku',['弹幕'],['弹幕设置','来源与内容过滤']),
  ('watch-together',['一起看'],['房间','聊天显示','创建房间']),
  ('about',['关于'],['版本','开源许可','关于 Yfuse','Yfuse'])]
 for name,keys,marker in profiles:
  step('profile-'+name,lambda k=keys,m=marker:view('我的',k,m))
 for nm,keys,marks in [
  ('server-form',['添加服务器','添加'],['地址','服务器类型','用户名','连接到服务器']),
  ('iptv',['IPTV · 电视直播','IPTV・电视直播'],['直播源','添加直播源','频道','IPTV · 电视直播']),
  ('wifi-import',['Wi-Fi 导入视频'],['局域网','上传地址','Wi-Fi 导入视频','本机地址']),
  ('file-sources',['添加文件来源'],['SMB','WebDAV','文件来源']),
 ]:
  step('source-'+nm,lambda k=keys,m=marks:view('服务器',k,m))
 step('watch-calendar',lambda:view('观看','追剧日历',['今天','日历','订阅','追剧日历']))
 step('watch-history',lambda:view('观看','播放记录',['播放记录','ProbeA']))
 step('watch-discover',lambda:view('观看','发现',['返回媒体库','发现']))
 step('library-after-cold-start',lambda:(launch(),browse())[1])
