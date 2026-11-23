import json,pathlib,re,shlex,time
from ext_run import adb,screen,marker,dump,nodes,tap
dest=pathlib.Path('qa-emby-evidence');pkg='com.yfuse.validation';rows=[]
def record(name,fn):
 try:res=fn();rows.append({'case':name,'passed':True,'detail':res})
 except Exception as e:rows.append({'case':name,'passed':False,'detail':str(e)[:80]})
 (dest/'transport.json').write_text(json.dumps(rows,indent=2))
def open_url(uri):adb('shell','am start -a android.intent.action.VIEW -n '+pkg+'/com.yfuse.feature.player.ExternalPlaybackActivity -t video/mp4 -d '+shlex.quote(uri))
def permit_or_words():
 labels=[]
 for node,b in nodes(dump()):
  words=[node.get(i,'').strip() for i in ['text','content-desc']]
  labels+=words
  if any(s in ['打开','Open','确认打开','仍然打开','允许','Allow'] for s in words) and node.get('enabled')=='true':
   tap((b[0]+b[2])//2,(b[1]+b[3])//2);break
 return labels
good=json.loads(pathlib.Path('qa-emby-local/streams.json').read_text())['ProbeA']['url']
def running_frame():
 end=time.monotonic()+24;first=None
 while time.monotonic()<end:
  b=screen();v=marker(b)
  if v is None:permit_or_words();continue
  if first is None:first=v
  elif v-first>=.5:
   b.save(dest/'recovered-frame.png');return first,v
 raise RuntimeError('no advancing decoded frame')
def fail_recover():
 adb('shell','input','keyevent','4');time.sleep(1.0);pid=adb('shell','pidof',pkg).strip()
 open_url('http://127.0.0.1:18196/qa-missing-video.mp4')
 error=False
 for _ in range(12):
  words=permit_or_words()
  if any(any(k in word for k in ['播放失败','重新播放','重试','无法播放','打开失败','404','无法读取']) for word in words):
   error=True;screen().save(dest/'failed-media.png');break
 if not error:raise RuntimeError('failed media error surface unverified')
 adb('shell','input','keyevent','4');time.sleep(.7);open_url(good);first,next_frame=running_frame()
 assert pid and pid==adb('shell','pidof',pkg).strip(),'process changed'
 return {'sameProcess':True,'firstFrame':first,'laterFrame':next_frame}
def pause_resume():
 # Works through the system transport controls which deliver media keys to this player.
 adb('shell','input','keyevent','127');time.sleep(.9)
 a=marker(screen());time.sleep(1.6);b=marker(screen())
 assert a is not None and b is not None and abs(a-b)<=.125,'pause did not hold a frame'
 adb('shell','input','keyevent','126');v,w=running_frame()
 return {'pausedFrame':a,'resumeFrame':w}
record('failed item followed by a good item in same process',fail_recover)
record('pause hold and resume via Android media keys',pause_resume)
