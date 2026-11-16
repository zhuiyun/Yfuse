import json,pathlib,subprocess,time
from features import adb,launch,setup,detail,grab,tap,wait,find,snapshot,labels,nodes,coords,size,shell,snap,pressback,out,page_info
from ext_run import screen,marker
def live():
 for _ in range(12):
  im=screen();v=marker(im)
  if v is not None:return v
  n=find(snapshot(),['打开','Open','确定','OK'])
  if n is not None:tap(n)
  time.sleep(.45)
 raise RuntimeError('cannot read source timecode')
def panel():
 for _ in range(5):
  t=snapshot()
  n=find(t,'字幕')
  if n is not None:tap(n);break
  w,h=screen().size;shell('input','tap',w//2,h*35//100);time.sleep(.4)
 wait('主字幕',10)
def select(english):
 t=snapshot();items=[]
 for n in nodes(t):
  if any(x=='关闭' or any(k in x.casefold() for k in ('english','英语','英文','srt','eng','probea.en')) for x in labels(n)):
   items.append((coords(n)[1],n))
 items.sort(key=lambda v:v[0]);n=next((n for _,n in items if ('关闭' not in labels(n))==english),None)
 if n is None:
  snap('subtitle-options',t);raise RuntimeError('target track absent in visible subtitle options')
 tap(n);time.sleep(.4)
 if find(snapshot(),'主字幕') is not None:pressback()
 for _ in range(3):
  t=snapshot();n=find(t,'知道了')
  if n is not None:tap(n);continue
  if find(t,'字幕') is not None:
   w,h=screen().size;shell('input','tap',w//2,h*35//100)
  break
def visible(name,enabled):
 samples=[]
 for i in range(5):
  im=screen();h=im.height;w=im.width
  at=marker(im);p=out/(name+'-'+str(i)+'.png')
  box=im.crop((w//8,int(h*.54),w*7//8,int(h*.975)));box.save(p)
  text=subprocess.run(['tesseract',str(p),'stdout','--psm','11','-l','eng'],capture_output=True).stdout.decode().strip()
  match='subtitle' in text.casefold() and 'fixture' in text.casefold()
  samples.append({'videoSeconds':at,'text':text,'visible':match})
  if at is not None and match==enabled and (enabled or len([x for x in samples if x['videoSeconds'] is not None and not x['visible']])>=3):
   im.thumbnail((900,520));im.save(out/(name+'.png'));return samples
  time.sleep(.4)
 raise RuntimeError(name+': '+str(samples)[:500])
r=[];ok=False
try:
 launch();setup();detail();tap(grab(['播放','立即播放','从头播放','继续播放'],3));live()
 panel();snap('subtitle-options');select(True);r.append({'enabled':visible('subtitle-on',True)})
 panel();select(False);r.append({'disabled':visible('subtitle-off',False)})
 ok=True
finally:
 (out/'subtitle-results.json').write_text(json.dumps({'checks':r,'passed':ok},ensure_ascii=False))
 if not ok:snap('subtitle-failure')
