"""Exercise ordinary playback controls on the original media sources returned by Emby."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, 'qa-arm-matrix')
import engine
spec = importlib.util.spec_from_file_location('measure', 'qa-private-arm/test.py')
measure = importlib.util.module_from_spec(spec)
spec.loader.exec_module(measure)
from ext_run import adb, screen, tap
from features import find, coords

class MediaRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parts = urllib.parse.urlsplit(newurl)
        host = parts.hostname or ''
        if (parts.scheme != 'https' or parts.username or parts.password or parts.port not in (443, None)
                or not (host == 'vip-download-cdn.123295.com' or host.endswith('.pd1.cjjd19.com'))):
            return None
        path = urllib.parse.quote(parts.path, safe="/%:@!$&()*+,;=-._~")
        query = urllib.parse.quote(parts.query, safe="&=/:;@!$()*+,-._~%?")
        target = urllib.parse.urlunsplit((parts.scheme, parts.netloc, path, query, ''))
        return super().redirect_request(req, fp, code, msg, headers, target)

def verify_range(media, position):
    opener = urllib.request.build_opener(MediaRedirect())
    request = urllib.request.Request(media['location'], headers={
        'Range': f'bytes={position}-{position+1023}', 'Accept-Encoding': 'identity',
        'User-Agent': 'Yfuse/1.9.4',
    })
    try:
        with opener.open(request, timeout=12) as response:
            chunk = response.read(1024)
            correct = response.headers.get('Content-Range') == f'bytes {position}-{position+1023}/{media["size"]}'
            return {'status': response.status, 'valid': response.status == 206 and correct and len(chunk) == 1024,
                    'fingerprint': hashlib.sha256(chunk).hexdigest()[:16]}
    except urllib.error.HTTPError as failure:
        return {'status': failure.code, 'valid': False}
    except (OSError, ValueError) as failure:
        return {'status': 0, 'valid': False, 'reason': type(failure).__name__}

def launch(media):
    mime = {'mkv':'x-matroska','webm':'webm','mpegts':'mp2t','ts':'mp2t','mov':'quicktime'}.get(media['container'], 'mp4')
    intent = 'am start -W -a android.intent.action.VIEW -n com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity -t video/'+mime+' -d '+shlex.quote(media['location'])
    adb('shell',intent,check=False)

def pause_resume(label, media):
    _, tree = measure.reveal()
    key = find(tree, ['暂停','Pause'])
    if key is None: raise AssertionError('pause control unavailable')
    tap(*coords(key))
    time.sleep(.9)
    _,tree = measure.reveal()
    start = measure.media_clock(tree)
    time.sleep(1.4)
    _,tree = measure.reveal()
    stopped = measure.media_clock(tree)
    resume = find(tree,['继续播放','播放','Play','Resume'])
    if start is None or stopped != start or resume is None: raise AssertionError('pause not verified')
    tap(*coords(resume))
    return measure.decode_playback(label,stopped,media['seconds'])

def one(core, label, media):
    prefix = core+'-'+label
    adb('shell','am','force-stop','com.yfuse')
    before = time.time()
    launch(media)
    measure.measured(prefix+'-open',requested=True)
    opened = measure.decode_playback(prefix,None,media['seconds'])
    proof = engine.engine_evidence(core,before)
    if not proof['active']: raise AssertionError('engine not verified')
    measure.measured(prefix+'-load',seconds=round(time.time()-before,2),verified=True,**opened)
    for ordinal,frac in enumerate(media['targets'],1):
        bounds,_ = measure.reveal()
        left,top,right,bottom = bounds
        y = (top+bottom)//2
        target = round(left+(right-left)*frac)
        origin = round(left+(right-left)*(.80 if frac<.5 else .15))
        adb('shell','input','swipe',origin,y,target,y,320)
        measure.measured(prefix+'-drag',issued=True,ordinal=ordinal,fraction=frac)
        response = measure.decode_playback(prefix,round(media['seconds']*frac),media['seconds'])
        measure.measured(prefix+'-landing',verified=True,ordinal=ordinal,**response)
    _,tree=measure.reveal()
    old=measure.media_clock(tree)
    if old is None:raise AssertionError('position unavailable')
    im=screen()
    x,y=round(im.width*.82),round(im.height*.38)
    adb('shell',f'input tap {x} {y}; sleep .09; input tap {x} {y}')
    advanced=measure.decode_playback(prefix,old+10,media['seconds'])
    if advanced['laterPos']-old < advanced['secToConfirm']+4:raise AssertionError('fast forward not confirmed')
    measure.measured(prefix+'-fast-forward',verified=True,**advanced)
    resumed=pause_resume(prefix,media)
    measure.measured(prefix+'-resume',verified=True,**resumed)

def main():
    import concurrent.futures
    items=measure.load_envelope()
    ready=[]
    def admit(pair):
        label,media=pair
        results=[verify_range(media,p) for p in (0,media['size']//2,media['size']-1024)]
        return label,media,results
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for label,media,ranges in pool.map(admit,items.items()):
            measure.measured(label+'-admission',kind=media['type'],container=media['container'],size=media['size'],ranges=ranges)
            if all(r['valid'] for r in ranges): ready.append((label,media))
    failures=0
    # Exercise each player's normal URL entry even when the host-side Range probe fails.
    # Keep these opens and their failures separate from successful playback and gestures.
    for core in ('YCore','SenPlayer'):
        engine.configure(core)
        for label,media in items.items():
            baseline=len(measure.app_events())
            try:
                one(core,label,media)
            except Exception as failure:
                failures+=1
                events=measure.app_events()[baseline:]
                measure.measured(core+'-'+label+'-failed',error=type(failure).__name__,
                                 hints=sorted({hint for event in events for hint in event['hints']}),
                                 events=[event['event'] for event in events[-16:]])
    if failures or len(ready)!=len(items): raise SystemExit('Playback or source checks incomplete')

if __name__ == '__main__': main()
