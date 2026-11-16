import json,pathlib,shlex,time
from features import adb,shell,snapshot,find,grab,tap,wait,expect,field,step,launch,root,labels,nodes,page_info,pkg,pressback
from ext_run import marker,screen,dump,nodes as xml_nodes,tap as point
out=pathlib.Path('feature-evidence');out.mkdir(exist_ok=True)
def profile(key):
 launch();root('我的');tap(grab(key,8))
def playback():profile('播放设置');wait('默认倍速')
def change_speed():
 playback();tap(grab('默认倍速'));tap(wait(['1.5x','1.5×']));wait('1.5x')
 playback();wait('1.5x');tap(grab('默认倍速'));tap(wait('1x'));return expect('1x')
def family():
 profile('家庭资料');tap(grab('新建家庭资料'));field('资料名称','DeviceQA',True);tap(grab('保存',4));wait('DeviceQA')
 profile('家庭资料');return expect('DeviceQA')
def danmaku():
 profile('弹幕设置')
 t=page_info(snapshot());assert any(any(k in w for k in ['过滤','屏蔽','弹幕源','弹幕来源','添加来源']) for w in t),str(t)[:80]
 return 'settings controls observed'
def adv():
 playback();tap(grab('高级播放设置',10));return expect('播放内核')
def score():
 adv();tap(grab('播放内核',3));wait('高级内核选择')
 choices=[n for n in nodes(snapshot()) if any('SCore' in w and len(w)<28 for w in labels(n))]
 if not choices:raise RuntimeError('experimental engine not offered on this build')
 tap(choices[0]);wait('播放内核');t=snapshot()
 assert any('SCore' in w for w in page_info(t)),'selected setting not shown'
 url=json.loads(pathlib.Path('qa-emby-local/streams.json').read_text())['ProbeA']['url']
 adb('shell','am start -a android.intent.action.VIEW -n '+pkg+'/com.yfuse.feature.player.ExternalPlaybackActivity -t video/mp4 -d '+shlex.quote(url))
 until=time.monotonic()+38;first=None
 while time.monotonic()<until:
  b=screen();v=marker(b)
  if v is not None:
   if first is None:first=v
   elif v-first>=.5:
    b.thumbnail((640,400));b.save(out/'experimental-playing.png')
    return {'selected':'SCore','first':first,'later':v}
  else:
   for node,box in xml_nodes(dump()):
    if any(node.get(k,'') in ['打开','确定','Allow','Open'] for k in ['text','content-desc']):
     point((box[0]+box[2])//2,(box[1]+box[3])//2);break
  time.sleep(.2)
 raise RuntimeError('selected engine produced no advancing marked video')
def history():
 profile('我的片单');tap(grab('观看历史'));return expect(['观看历史','还没有观看历史','暂无观看历史'])
def account_gate():
 profile('一起看');return expect(['登录账号','创建房间','加入房间'])
for name,fn in [('speed-persists-after-restart-and-reset',change_speed),('create-family-member-and-reread',family),('danmaku-provider-and-filter-controls',danmaku),('watch-together-account-entry',account_gate),('personal-history',history),('advanced-decoder-options',adv),('experimental-engine-selected-and-frames-advance',score)]:step(name,fn)
