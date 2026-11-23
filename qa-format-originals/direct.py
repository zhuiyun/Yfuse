import contextlib,hashlib,json,os,re,shlex,subprocess,sys,time,urllib.parse,urllib.request,urllib.error
from pathlib import Path
from PIL import Image,ImageChops,ImageStat
sys.path.insert(0,'qa-functional')
from ext_run import adb,rail,screen,tap
from features import find,coords,snapshot
OUT=Path('results');OUT.mkdir(exist_ok=True)
ROWS=[];METRICS={};POLICY=urllib.request.build_opener()
def measured(label, **detail):
    ROWS.append({"case":label, **detail})
    report={"measurements":ROWS,"network":{k:{"codes":sorted(v["statuses"]),"requests":v["requests"],"bytes":v["bytes"]} for k,v in METRICS.items()}}
    (OUT/"metrics.json").write_text(json.dumps(report,indent=2))
    print("PRIVATE",label,json.dumps(detail),flush=True)


def media_clock(tree):
    def clock_in(s):
        match=re.search(r"(?<!\d)(?:(?:\d{1,2}:)?\d{1,3}:\d\d)(?!\d)",s)
        if not match:return None
        value=0
        for part in match.group().split(":"):value=value*60+int(part)
        return value
    for node in tree.iter("node"):
        state=node.get("state","")
        if "播放进度" in state or "SeekBar" in node.get("class",""):
            here=clock_in(state)
            if here is not None:return here
    times=[clock_in(node.get("text","")) for node in tree.iter("node") if re.fullmatch(r"(?:\d{1,2}:)?\d{1,3}:\d\d",node.get("text","").strip())]
    times=[v for v in times if v is not None]
    return min(times) if times else None


def reveal():
    for _ in range(6):
        tree=snapshot()
        r=rail(tree)
        if r:return r,tree
        button=find(tree,["允许","打开","Open","Got it","确认打开","知道了","OK"])
        if button is not None:tap(*coords(button))
        else:
            im=screen();w,h=im.size;tap(w*.52,h*.36)
        time.sleep(.4)
    raise AssertionError("no progress control")


def patch(im):
    w,h=im.size
    return im.crop((int(.17*w),int(.31*h),int(.83*w),int(.66*h))).resize((96,48)).convert("RGB")


def decode_playback(label,target,duration,max_wait=60):
    begin=time.monotonic()
    prev=None
    first=None
    speech=[]
    laststatus=None
    history=[]
    max_wait=50 if target is None else 38
    while time.monotonic()-begin<max_wait:
        try:r,tree=reveal()
        except AssertionError:
            tree=snapshot()
            flags=sorted({s for node in tree.iter("node") for s in ("播放失败","重新播放","不支持","解码","正在缓冲","重新连接","播放进度","加载超时") if s in (node.get("text","")+" "+node.get("state","")+" "+node.get("content-desc",""))})
            history.append({"flags":flags,"noRail":True,"elapsed":round(time.monotonic()-begin,1)})
            time.sleep(.5);continue
        pos=media_clock(tree)
        pix=patch(screen())
        elapsed=time.monotonic()-begin
        history.append({"s":pos,"elapsed":round(elapsed,1),"contrast":round(sum(ImageStat.Stat(pix).stddev)/3,2)})
        if pos is not None and pos<duration-1 and (target is None or target-12 <= pos <= target+elapsed+12):
            if prev:
                oldpos,oldim=prev
                diff=sum(ImageStat.Stat(ImageChops.difference(pix,oldim)).mean)/3
                if pos>oldpos and diff>.8:
                    speech.append({"s":pos,"pixelChange":round(diff,2)})
                    if first is None:first=(pos,elapsed)
                    if len(speech)>=2 and pos-first[0]>=1:
                        return {"firstPos":first[0],"laterPos":pos,"secToFirst":round(first[1],2),"secToConfirm":round(elapsed,2),"changes":speech[-3:]}
            prev=(pos,pix)
        time.sleep(.8)
    measured(label+"-observation",history=history[-16:])
    raise AssertionError("no moving movie image with advancing player time")



def probe_file(url):
    q=subprocess.run(["ffprobe","-v","error","-rw_timeout","15000000","-probesize","8388608","-analyzeduration","5000000","-show_entries","format=size,duration,format_name:stream=codec_type,codec_name,width,height,channels","-of","json",url],capture_output=True,timeout=42)
    if q.returncode:raise ValueError("ffprobe rejected this source")
    data=json.loads(q.stdout)
    return dict(bytes=int(data['format'].get('size',0)),seconds=round(float(data['format']['duration'])),container=data['format']['format_name'],tracks=data['streams'])

def run(item):
    url=item['url'];label=item['id'];kind=item['kind']
    if urllib.parse.urlsplit(url).hostname not in ('download.blender.org','media.xiph.org'):raise ValueError('unlisted source host')
    observed=probe_file(url)
    measured(label+'-format',kind=kind,**observed)
    if observed['bytes']>1024:
        for key,pos in [('start',0),('middle',observed['bytes']//2),('end',observed['bytes']-1024)]:
            try:
                with urllib.request.urlopen(urllib.request.Request(url,headers={'Range':f'bytes={pos}-{pos+1023}','Accept-Encoding':'identity'}),timeout=12) as f:
                    count=len(f.read(1024));status=f.status
                    valid=f.headers.get('Content-Range','')==f"bytes {pos}-{pos+1023}/{observed['bytes']}"
                measured(label+'-'+key,status=status,returned=count,exactRange=valid)
            except (OSError,ValueError) as e:measured(label+'-'+key,status=getattr(e,'code',0),returned=0,exactRange=False)
    adb('shell','am','force-stop','com.yfuse')
    seturl='am start -W -a android.intent.action.VIEW -n com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity -t '+item['mime']+' -d '+shlex.quote(url)
    status=adb('shell',seturl,check=False)
    if 'Exception' in status or 'Error' in status:raise RuntimeError('intent rejected')
    result=decode_playback(label,None,observed['seconds'])
    measured(label+'-opened',kind=kind,**result)
    for fraction in (.63,.21):
        r,_=reveal();left,top,right,bottom=r;y=(top+bottom)//2
        adb('shell','input','swipe',round(left+(right-left)*(.83 if fraction<.5 else .17)),y,round(left+(right-left)*fraction),y,330)
        measured(label+'-seek',fraction=fraction,**decode_playback(label,observed['seconds']*fraction,observed['seconds']))
    measured(label+'-complete',ok=True)

if __name__=='__main__':
    rows=json.loads(Path('qa-format-originals/sources.json').read_text())
    shard,n=map(int,sys.argv[1:3])
    for item in rows[shard::n]:
        try:run(item)
        except (AssertionError,OSError,TimeoutError,subprocess.TimeoutExpired,ValueError,KeyError,RuntimeError) as e:measured(item['id']+'-incomplete',kind=item['kind'],error=type(e).__name__)
    print('DIRECT_COUNT',sum(r['case'].endswith('-opened') for r in ROWS),len(rows[shard::n]),flush=True)
