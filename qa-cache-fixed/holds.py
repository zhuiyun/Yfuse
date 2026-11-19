import sys,time,json,pathlib,re,threading,subprocess,os
sys.path.insert(0,'qa-arm-matrix')
from engine import engine_evidence
sys.path.insert(0,'qa-functional')
from ext_run import screen,tap,rail,marker,adb
from features import snapshot,find,coords
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from PIL import Image
import numpy as np
root=pathlib.Path('qa-media');file=root/'ProbeA.mp4';out=pathlib.Path('results');out.mkdir(exist_ok=True);stats={'range':0,'image':0,'bytes':0,'GET':0,'HEAD':0}
origin_lock=threading.Lock()
received=[]

def origin_counters():
    with origin_lock:
        return dict(stats)
limits=json.loads((root/'boundary.json').read_text())
def spans():
    merged=[]
    with origin_lock:
        extents=sorted(received)
    for a,b in extents:
        if not merged or a>merged[-1][1]+1:merged.append([a,b])
        else:merged[-1][1]=max(merged[-1][1],b)
    return merged
def packetsAvailable(which):
    have=spans()
    return all(any(a<=p and q<=b for a,b in have) for p,q in limits[which])
class Origin(BaseHTTPRequestHandler):
    def log_message(self,*a):pass
    def do_HEAD(self):self.serve(False)
    def do_GET(self):self.serve(True)
    def serve(self,body):
        with origin_lock:
            stats[self.command]+=1
            if self.path!='/film.mp4':
                stats['image']+=1
        if self.path!='/film.mp4':
            self.send_error(404);return
        size=file.stat().st_size;match=re.fullmatch(r'bytes=(\d+)-(\d*)',self.headers.get('Range',''))
        start=int(match[1]) if match else 0;end=min(int(match[2]),size-1) if match and match[2] else size-1
        if start>end:self.send_error(416);return
        # Keep the remaining suffix unavailable until the measured gesture finishes.
        if start>=limits['before']: self.send_error(503);return
        if match:
            with origin_lock:
                stats['range']+=1
        self.send_response(206 if match else 200);self.send_header('Content-Length',str(end-start+1));self.send_header('Content-Type','video/mp4');self.send_header('ETag','"qa-rail-preview-v1"');self.send_header('Accept-Ranges','bytes')
        if match:self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
        self.end_headers()
        if body:
            with file.open('rb') as f:
                f.seek(start)
                try:
                    while start<=end:
                        if start>=limits['before']:time.sleep(25);return
                        chunk=f.read(min(16384,end-start+1,limits['before']-start))
                        self.wfile.write(chunk)
                        with origin_lock:
                            received.append((start,start+len(chunk)-1))
                            stats['bytes']+=len(chunk)
                        start+=len(chunk)
                except OSError:pass
srv=ThreadingHTTPServer(('127.0.0.1',18382),Origin);srv.daemon_threads=True;threading.Thread(target=srv.serve_forever,daemon=True).start()
adb('shell','am start -a android.intent.action.VIEW -n com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity -t video/mp4 -d http://127.0.0.1:18382/film.mp4')
def show():
    for i in range(6):
        t=snapshot();r=rail(t)
        if r:return r,t
        n=find(t,['打开','Open','确定','知道了'])
        if n is not None:tap(*coords(n))
        else:
            im=screen();tap(im.width*.52,im.height*.36)
        time.sleep(.35)
    raise RuntimeError('no rail')
for _ in range(28):
    im=screen();v=marker(im)
    if v is not None and v>1:break
    t=snapshot();n=find(t,['打开','Open','确定','知道了'])
    if n is not None:tap(*coords(n))
    time.sleep(.45)
else:raise RuntimeError('no timecoded moving source')
identity=engine_evidence(os.environ['QA_ENGINE'])
identity_deadline=time.monotonic()+12
while not identity['active'] and time.monotonic()<identity_deadline:
    time.sleep(.5)
    identity=engine_evidence(os.environ['QA_ENGINE'])
(out/'engine.json').write_text(json.dumps(identity))
assert identity['active'], 'active engine not verified'
r,tr=show();baseline=screen();baseline.save(out/'base.png')
width,height=baseline.size
# The narrow cache lane is painted in the centre of the real track. Preserve the UI evidence as well.
r=[int(x) for x in r];x0,y0,x1,y1=r;y=(y0+y1)//2
def px(sec):return round(x0+(x1-x0-1)*sec/150)
def motion(a,x):
    adb('shell','input','motionevent',a,int(x),y)
def thumbTimes(im):
    # Search only for miniature synthetic video markers. The full-screen video's bit spacing is far larger.
    arr=np.asarray(im.convert('L')).astype(np.int16);ans={}
    for gap in np.arange(7.75,10.75,.25):
        delta=np.rint(np.arange(18)*gap).astype(int)
        n=arr.shape[1]-delta[-1]
        if n<=0:continue
        for yy in range(max(15,int(height*.1)),min(y-3,height),4):
            row=arr[yy];seed=(row[:n]>175)&(row[delta[1]:delta[1]+n]<70)&(row[delta[2]:delta[2]+n]>175)&(row[delta[15]:delta[15]+n]<70)&(row[delta[16]:delta[16]+n]>175)&(row[delta[17]:delta[17]+n]<70)
            for xx in np.flatnonzero(seed)[::2]:
                vals=row[xx+delta]
                if np.any((vals>70)&(vals<175)):continue
                bits=vals>140
                if int(bits[3:14].sum())%2!=int(bits[14]):continue
                val=sum(int(b)*(2**(10-k)) for k,b in enumerate(bits[3:14]))/8
                if val<0 or val>=150:continue
                # Both sides of the strip are the known blue fixture background (reject buttons/text).
                xa=int(xx-1.3*gap);xb=int(xx+18.4*gap)
                if xa<0 or xb>=im.width:continue
                rgb=[im.getpixel((i,yy)) for i in (xa,xb)]
                if not all(b-r>12 and g-r>7 for r,g,b in rgb):continue
                key=(val,round(yy/15),round(xx/25));ans[key]=ans.get(key,0)+1
    return sorted({k[0] for k,c in ans.items() if c>=6})
def waitImage(name,sec,want=True):
    seen=[]
    for i in range(5):
        im=screen();vals=thumbTimes(im)
        seen.append(vals)
        if any(abs(v-sec)<=2.6 for v in vals) if want else not vals:
            im.save(out/(name+'.png'));return True,seen
        time.sleep(.2)
    im.save(out/(name+'.png'));return False,seen
# Actually pause the picture to keep the active forward range stationary while testing.
pause=find(tr,['暂停','Pause'])
if pause is not None:tap(*coords(pause));time.sleep(.4)
r,tr=show();x0,y0,x1,y1=r;y=(y0+y1)//2
ts=marker(screen()) or 4
hot=10;cold=143;payload={'rail':r,'startPTS':ts,'hot':hot,'cold':cold,'baseCodes':thumbTimes(baseline),'phases':[]}
def record(label,ok,observed):
    payload['phases'].append({'case':label,'ok':bool(ok),'codes':observed,'http':origin_counters()})
    (out/'thumbnail-held.json').write_text(json.dumps(payload));print('THUMB',label,ok,observed,flush=True)
before=origin_counters()
record('hot-packets-delivered',packetsAvailable('hotPackets'),[])
record('cold-packets-withheld',not packetsAvailable('coldPackets'),[])
try:
    motion('DOWN',px(max(1,hot-6)))
    for z in range(1,7):motion('MOVE',px(hot-6+z*6/6))
    time.sleep(.75)
    ok,v=waitImage('held-hot',hot);record('held-buffered',ok,v)
    motion('MOVE',px(cold));time.sleep(.65)
    ok,v=waitImage('held-cold',cold,False);record('held-unbuffered',ok,v)
    motion('MOVE',px(hot+2));time.sleep(.7)
    ok,v=waitImage('held-back',hot+2);record('returned-to-buffer',ok,v)
finally:
    held_end=origin_counters()
    payload['requestsWhileHeld']=held_end['range']-before['range']
    payload['httpMethodsWhileHeld']={method:held_end[method]-before[method] for method in ('GET','HEAD')}
    payload['originRequestsWhileHeld']=sum(payload['httpMethodsWhileHeld'].values())
    payload['deliveredExtentsBeforeRelease']=spans()
    motion('UP',px(hot+2))
time.sleep(.5);ok,v=waitImage('released',hot+2,False);record('released',ok,v)
after=origin_counters()
record('no-thumbnail-http-endpoint',after['image']==0,[])
payload['rangeRequestsDuringGesture']=after['range']-before['range']
payload['engine']=identity
(out/'thumbnail-held.json').write_text(json.dumps(payload))
passed=all(s['ok'] for s in payload['phases']) and payload['originRequestsWhileHeld']==0
print('Origin requests while held',payload['httpMethodsWhileHeld'],flush=True)
raise SystemExit(int(not passed))
