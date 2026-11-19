import sys,time,json,pathlib,re,threading,subprocess,os
sys.path.insert(0,'qa-arm-matrix')
from engine import engine_evidence
sys.path.insert(0,'qa-functional')
from ext_run import screen,tap,rail,marker,adb
from features import snapshot,find,coords
from fixture_origin import FixtureOrigin
import atexit
from PIL import Image
import numpy as np
root = pathlib.Path('qa-media')
trial = os.environ.get('QA_CACHE_TRIAL', 'held-stalled')
assert trial in {'held-stalled', 'idle-stalled', 'held-paced', 'held-settled'}
out = pathlib.Path('results') if trial == 'held-stalled' else pathlib.Path('results') / trial
out.mkdir(exist_ok=True)
limits = json.loads((root / 'boundary.json').read_text())
srv = FixtureOrigin(root / 'ProbeA.mp4', limits, paced=trial == 'held-paced')
threading.Thread(target=srv.serve_forever, daemon=True).start()
origin_counters = srv.counters
spans = srv.spans
checkpoints = []


def checkpoint(name):
    checkpoints.append({'name': name, 'atMs': srv.now(), 'counters': srv.counters()})


def save_origin():
    audit = {'trial': trial, 'cutoff': limits['before'], 'paceAfter': srv.pace_after,
             'events': srv.events(), 'checkpoints': checkpoints, 'counters': srv.counters(), 'extents': srv.spans()}
    (out / 'origin-timeline.json').write_text(json.dumps(audit, indent=2))


atexit.register(save_origin)


def packetsAvailable(which):
    ranges = srv.spans()
    return all(any(start <= p and q <= end for start, end in ranges) for p, q in limits[which])


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
paused = find(tr, ['播放', 'Play']) is not None
checkpoint('paused-controls' if paused else 'pause-unconfirmed')
ts=marker(screen())
hot=10;cold=143;payload={'rail':r,'startPTS':ts,'hot':hot,'cold':cold,'baseCodes':thumbTimes(baseline),'phases':[]}
payload['trial'] = trial
payload['pauseConfirmed'] = paused

if trial == 'held-settled':
    # Let the existing playback read reach the same timeout as the no-touch control.
    # Keep the finger up throughout. An unexpected request during the next gesture still fails.
    checkpoint('settle-without-touch')
    time.sleep(30)
    quiet = origin_counters()
    time.sleep(10)
    current_rail = rail(snapshot())
    stationary = marker(screen())
    stayed_paused = (paused and ts is not None and stationary is not None and abs(stationary - ts) <= .25)
    payload['settleEvidence'] = {'stationary': stayed_paused, 'startPts': ts, 'endPts': stationary,
                                 'lastTenSecondsHadNoTraffic': origin_counters() == quiet}
    checkpoint('settled-controls')
    (out / 'thumbnail-held.json').write_text(json.dumps(payload))
    if not stayed_paused or origin_counters() != quiet or not current_rail:
        raise AssertionError('Missing stationary, network-quiet playback baseline with a visible seek bar')
    if not any(e['event'] == 'body-stalled' and e['offset'] == limits['before'] for e in srv.events()):
        raise AssertionError('Expected the actual ongoing response to reach the withheld boundary')
    x0, y0, x1, y1 = current_rail
    y = (y0 + y1) // 2


def record(label,ok,observed):
    checkpoint(label)
    payload['phases'].append({'case':label,'ok':bool(ok),'codes':observed,'http':origin_counters()})
    (out/'thumbnail-held.json').write_text(json.dumps(payload));print('THUMB',label,ok,observed,flush=True)
before=origin_counters()
record('hot-packets-delivered',packetsAvailable('hotPackets'),[])
record('cold-packets-withheld',not packetsAvailable('coldPackets'),[])
if trial == 'idle-stalled':
    checkpoint('no-touch-start')
    time.sleep(40)
    checkpoint('no-touch-end')
    idle = screen()
    idle.save(out / 'paused-no-touch.png')
    payload['endingPTS'] = marker(idle)
    payload['stationaryPicture'] = (ts is not None and payload['endingPTS'] is not None and
                                    abs(payload['endingPTS'] - ts) <= .25)
    payload['noTouchSeconds'] = 40
    payload['engine'] = identity
    payload['httpAtEnd'] = origin_counters()
    (out / 'thumbnail-held.json').write_text(json.dumps(payload))
    raise SystemExit(int(not paused or not payload['stationaryPicture'] or
                         not all(p['ok'] for p in payload['phases']) or bool(thumbTimes(idle))))

try:
    checkpoint('finger-down')
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
    checkpoint('finger-up')
    motion('UP',px(hot+2))
time.sleep(.5);ok,v=waitImage('released',hot+2,False);record('released',ok,v)
after=origin_counters()
record('no-thumbnail-http-endpoint',after['image']==0,[])
payload['rangeRequestsDuringGesture']=after['range']-before['range']
payload['engine']=identity
(out/'thumbnail-held.json').write_text(json.dumps(payload))
passed=paused and all(s['ok'] for s in payload['phases']) and payload['originRequestsWhileHeld']==0
print('Origin requests while held',payload['httpMethodsWhileHeld'],flush=True)
raise SystemExit(int(not passed))
