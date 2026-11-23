import collections,contextlib,io,json,math,os,pathlib,re,shlex,subprocess,sys,time
from PIL import Image,ImageChops,ImageStat,ImageOps
sys.path.insert(0,'qa-functional')
from ext_run import adb,screen,tap,rail
from features import snapshot,find,coords
OUT=pathlib.Path('results');OUT.mkdir(exist_ok=True);results=[]
def runproc(args,timeout):return subprocess.run(args,capture_output=True,timeout=timeout,check=True).stdout
def value(pos):
    n=0
    for v in pos.split(':'):n=n*60+int(v)
    return n
def clock(tree):
    for n in tree.iter('node'):
        match=re.search(r'播放进度\s+((?:\d+:)?\d+:\d\d)\s*/\s*((?:\d+:)?\d+:\d\d)',n.get('state',''))
        if match:return value(match[1]),value(match[2])
    return None
def reveal():
    for _ in range(5):
        t=snapshot();r=rail(t);v=clock(t)
        if v and r:return v[0],v[1],r
        button=find(t,['打开','Open','知道了','Got it','确定','OK'])
        if button is not None:tap(*coords(button))
        else:
            im=screen();w,h=im.size;tap(w*.51,h*.36)
        time.sleep(.38)
    raise ValueError('progress control unavailable')
def fingerprint(im):
    return im.resize((128,72)).convert('L').crop((7,12,121,55)).resize((32,14),Image.Resampling.BILINEAR).tobytes()
def correlation(a,b):
    m=sum(a)/len(a);n=sum(b)/len(b);x=[t-m for t in a];y=[t-n for t in b]
    d=math.sqrt(sum(t*t for t in x)*sum(t*t for t in y))
    return sum(s*t for s,t in zip(x,y))/d if d>30 else -1
def reference(item):
    f=runproc(['ffmpeg','-v','error','-nostdin','-rw_timeout','20000000','-threads','2','-i',item['url'],'-map','0:v:0','-an','-sn','-filter_threads','1','-vf','fps=2,scale=128:72:force_original_aspect_ratio=decrease,pad=128:72:(ow-iw)/2:(oh-ih)/2,setsar=1','-f','rawvideo','-threads','1','-pix_fmt','gray','pipe:1'],260)
    return [fingerprint(Image.frombytes('L',(128,72),f[k:k+9216])) for k in range(0,len(f)-9215,9216)]
def window(fp,refs,start,end):
    candidates=[(correlation(fp,refs[i]),i/2.0) for i in range(max(0,round(start*2)),min(len(refs),round(end*2)+1))]
    return max(candidates,default=(-1,-1))
def observe(refs,target):
    start=time.monotonic();accepted=[];samples=[];trial=0
    while time.monotonic()-start<65:
        trial+=1
        try:pos,duration,r=reveal()
        except ValueError:continue
        im=screen()
        w,h=im.size
        view=im.crop((0,max(0,round((h-w*9/16)/2)),w,min(h,round((h+w*9/16)/2)))) if h>w*9/16 else im.crop((max(0,round((w-h*16/9)/2)),0,min(w,round((w+h*16/9)/2)),h))
        fp=fingerprint(view);elapsed=time.monotonic()-start
        score,pt=window(fp,refs,pos-2.5,pos+3.0)
        samples.append(dict(player=pos,matched=pt,correlation=round(score,3),elapsed=round(elapsed,2)))
        near=target-3<=pt<=target+elapsed+3
        if score>=.72 and near:
            for old,oldfp in accepted:
                dt=pt-old['matched']
                if dt>=.5 and pos>=old['player']+1 and dt<=elapsed-old['elapsed']+3 and correlation(fp,oldfp)<.99 and correlation(refs[round(pt*2)],refs[round(old['matched']*2)])<.99:
                    return dict(ok=True,first=old,second=samples[-1])
            accepted.append((samples[-1],fp))
        time.sleep(.75)
    return dict(ok=False,samples=samples[-15:])
def one(item):
    refs=reference(item);report=dict(id=item['id'],kind=item['kind'],referenceFrames=len(refs),cases=[]);results.append(report)
    if len(refs)<24:return
    adb('shell','am','force-stop','com.yfuse')
    cmd='am start -W -a android.intent.action.VIEW -n com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity -t video/mp4 -d '+shlex.quote(item['url'])
    adb('shell',cmd);t=0
    for target in (0,.63,.21):
        if target:
            _,duration,r=reveal();t=target*duration;x,y,right,bottom=r
            adb('shell','input','swipe',round(x+(right-x)*(.18 if target>.5 else .82)),(y+bottom)//2,round(x+(right-x)*target),(y+bottom)//2,330)
        case=observe(refs,t)
        report['cases'].append(dict(fraction=target,**case))
        print('PICTURE_CHECK',item['id'],target,case.get('ok'),flush=True)
        (OUT/'match.json').write_text(json.dumps(results))
for i,t in enumerate(json.loads(pathlib.Path('qa-fingerprint/originals.json').read_text())):
    if i%4!=int(sys.argv[1]):continue
    try:one(t)
    except (OSError,KeyError,ValueError,RuntimeError,subprocess.SubprocessError) as e:
        results.append(dict(id=t['id'],error=type(e).__name__))
    (OUT/'match.json').write_text(json.dumps(results))
