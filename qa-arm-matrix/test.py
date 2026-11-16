import os
import ipaddress
from engine import engine_evidence
import base64,json,random,re,shlex,sys,time,subprocess,pathlib
sys.path.insert(0,'qa-functional')
from ext_run import adb,marker,screen,tap,rail
from features import find,coords,snapshot
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from threading import Thread
root=pathlib.Path('qa-media');out=pathlib.Path('results');out.mkdir(exist_ok=True)
media_host=os.environ.get('QA_MEDIA_HOST','127.0.0.1')
if media_host!='127.0.0.1' and ipaddress.ip_address(media_host) not in ipaddress.ip_network('172.16.0.0/12'):
    raise ValueError('The fixture listener must stay on loopback or the isolated Docker bridge')
clips=json.loads((root/'fixtures.json').read_text()); allow={r['name'] for r in clips}
continuous=os.environ.get('QA_CONTINUOUS_PROCESS')=='true'
cycles=int(os.environ.get('QA_CYCLES','1'))
assert cycles>0 and (continuous or cycles==1)
clips=clips*cycles
process_pid=None
process_samples=[]
class HTTP(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_HEAD(self):self.serve(False)
    def do_GET(self):self.serve(True)
    def serve(self,body):
        if os.environ.get('QA_STOP_ON_FAILURE')=='true':
            with (out/'public-range-arrivals.jsonl').open('a') as log:
                log.write(json.dumps({'timeMs':round(time.time()*1000),'method':self.command,'range':self.headers.get('Range','')})+'\n')
        name=self.path.lstrip('/')
        if name not in allow:self.send_error(404);return
        path=root/name;size=path.stat().st_size
        m=re.fullmatch(r'bytes=(\d+)-(\d*)',self.headers.get('Range',''));lo=int(m[1]) if m else 0;hi=min(int(m[2]),size-1) if m and m[2] else size-1
        if lo>hi:self.send_error(416);return
        self.send_response(206 if m else 200);self.send_header('Content-Type',next(i['mime'] for i in clips if i['name']==name));self.send_header('Content-Length',str(hi-lo+1));self.send_header('Accept-Ranges','bytes')
        if m:self.send_header('Content-Range',f'bytes {lo}-{hi}/{size}')
        self.end_headers()
        if body:
            with path.open('rb') as f:
                f.seek(lo)
                try:
                    while lo<=hi:
                        chunk=f.read(min(65536,hi-lo+1));self.wfile.write(chunk);lo+=len(chunk)
                except (BrokenPipeError,ConnectionResetError):pass
server=ThreadingHTTPServer((media_host,18382),HTTP);server.daemon_threads=True;Thread(target=server.serve_forever,daemon=True).start()
shard=int(sys.argv[1])
sessions=int(os.environ.get('QA_SESSIONS','5'))
batches=int(os.environ.get('QA_BATCHES','2'))
batch_count=int(os.environ.get('QA_BATCH_COUNT','16'))
assert sessions>0 and batches>0 and batch_count>0
rec=[];report={'continuousProcess':continuous,'cycles':cycles,'processSamples':process_samples,'shard':shard,'mediaPath':'adb-reverse' if media_host=='127.0.0.1' else 'docker-bridge','planned':len(clips)*sessions*batches*batch_count,'files':rec}

def capture_open_failure(clip, session):
    # This lane only serves the allowlisted public fixtures, never private media locations.
    prefix=out/(clip['name'].replace('.','-')+'-session-'+str(session))
    screen().save(str(prefix)+'-screen.png')
    import xml.etree.ElementTree as ET
    pathlib.Path(str(prefix)+'-tree.txt').write_text(ET.tostring(snapshot(),encoding='unicode'))
    pathlib.Path(str(prefix)+'-logcat.txt').write_text(adb('logcat','-d','-v','threadtime',check=False))
    pathlib.Path(str(prefix)+'-diagnostics.txt').write_text(adb('shell','cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl',check=False))
    # Capture a live blocked seek before the next session force-stops it. These are public
    # generated fixtures; private-source jobs never execute this recorder.
    pathlib.Path(str(prefix)+'-host-sockets.txt').write_text(subprocess.run(['ss','-ntp'],capture_output=True,text=True).stdout)
    pathlib.Path(str(prefix)+'-android-sockets.txt').write_text(adb('shell','cat /proc/net/tcp /proc/net/tcp6',check=False))
    pid=adb('shell','pidof com.yfuse',check=False).strip()
    if pid.isdigit():
        try:
            adb('shell','kill -3 '+pid,check=False,timeout=10)
            time.sleep(1)
            pathlib.Path(str(prefix)+'-native-stacks.txt').write_text(adb('shell','debuggerd -b '+pid,check=False,timeout=20))
            pathlib.Path(str(prefix)+'-java-stacks.txt').write_text(adb('shell','cat /data/anr/*',check=False,timeout=10))
        except subprocess.SubprocessError:
            pathlib.Path(str(prefix)+'-stack-capture.txt').write_text('Timed out capturing cloud emulator stacks')
    time.sleep(8)
    screen().save(str(prefix)+'-later-screen.png')
    pathlib.Path(str(prefix)+'-later-diagnostics.txt').write_text(adb('shell','cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl',check=False))
    pathlib.Path(str(prefix)+'-later-logcat.txt').write_text(adb('logcat','-d','-v','threadtime',check=False))
def save():
    report['issued']=sum(bool(r.get('issued')) for c in rec for r in c['rows'])
    report['verified']=sum(bool(r.get('ok')) for c in rec for r in c['rows'])
    (out/'volume.json').write_text(json.dumps(report))
def sample_process():
    global process_pid
    pid=adb('shell','pidof com.yfuse',check=False).strip()
    if not pid.isdigit():raise AssertionError('The continuous player process disappeared')
    if process_pid is None:process_pid=pid
    if pid!=process_pid:raise AssertionError('The continuous player process restarted')
    raw=adb('shell','cat /proc/'+pid+'/status',check=False)
    def number(key):
        match=re.search(r'^'+key+r':\s+(\d+)',raw,re.M)
        return int(match[1]) if match else None
    files=adb('shell','ls -1 /proc/'+pid+'/fd',check=False).splitlines()
    process_samples.append({'pid':int(pid),'rssKiB':number('VmRSS'),'threads':number('Threads'),'fileDescriptors':len(files)})
    save()

def open_media(clip):
    if not continuous:adb('shell','am','force-stop','com.yfuse')
    url='http://'+media_host+':18382/'+clip['name']
    cmd='am start -W -a android.intent.action.VIEW -n com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity -t '+clip['mime']+' -d '+url
    expected_title='qa-continuous-'+str(len(rec))+'-'+clip['name']
    if continuous:cmd+=' --es title '+expected_title
    adb('shell',cmd);seen=None;started=time.monotonic()
    title_seen=not continuous
    while time.monotonic()-started<50:
        im=screen();current=marker(im)
        if continuous and not title_seen:
            tree=snapshot()
            title_seen=any(expected_title in (node.get('text',''),node.get('content-desc','')) for node in tree.iter('node'))
            if not title_seen:tap(im.width*.5,im.height*.32)
        if title_seen and current is not None and seen is not None and current>seen+.25 and current-seen<6:
            if continuous:sample_process()
            return True
        if current is not None:seen=current
        else:
            tree=snapshot();b=find(tree,['打开','Open','仍然打开','知道了','Got it','确定','OK'])
            if b is not None:tap(*coords(b))
            if tree.tag=='launcher-dismissed':adb('shell',cmd)
        time.sleep(.28)
    return False
for idx,clip in enumerate(clips):
    detail=dict(clip,rows=[],sessions=[]);rec.append(detail);save()
    for session in range(sessions):
        since=time.time()
        ok=open_media(clip)
        proof=engine_evidence(os.environ['QA_ENGINE'],since)
        identity_deadline=time.monotonic()+12
        while ok and not proof['active'] and time.monotonic()<identity_deadline:
            time.sleep(.5);proof=engine_evidence(os.environ['QA_ENGINE'],since)
        detail['sessions'].append({'session':session,'opened':ok,'identity':proof})
        print('IDENTITY',clip['name'],session,ok,proof,flush=True)
        ok=ok and proof['active']
        detail['open']='passed' if ok or detail.get('open')=='passed' else 'no advancing picture'
        if not ok:
            capture_open_failure(clip,session)
            save();continue
        attempted=0
        for batch in range(batches):
            base_seed=int(os.environ.get('QA_SEED_BASE',str((11987311 if os.environ['QA_ENGINE']=='YCore' else 22987311)+shard*2017+idx*137)))
            seed=base_seed+batch if os.environ.get('QA_REPEAT_SESSION')=='true' else base_seed if os.environ.get('QA_REPEAT_SEED')=='true' else base_seed+session*19+batch
            try:
                answer=adb('shell','am','instrument','-w','-e','mode','volume','-e','variant','original','-e','seed',seed,'-e','count',batch_count,'-e','duration',clip['duration'],'-e','stopOnFailure',os.environ.get('QA_STOP_ON_FAILURE','false'),'qa.yfuse.gesture/.Driver',timeout=350)
                match=re.search(r'INSTRUMENTATION_RESULT: rows=([A-Za-z0-9+/=]+)',answer)
                if not match:raise ValueError('missing instrumentation response')
                rows=json.loads(base64.b64decode(match[1]))
                for row in rows:row['seed']=seed
                detail['rows'].extend(rows)
                n=sum(bool(r.get('issued')) for r in rows);good=sum(bool(r.get('ok')) for r in rows);attempted+=n
                print('BATCH',clip['name'],session,batch,'issued',n,'verified',good,flush=True)
                save()
                if good!=len(rows):
                    capture_open_failure(clip,str(session)+'-batch-'+str(batch))
                if n<8:break
            except (ValueError,subprocess.SubprocessError,RuntimeError) as e:
                detail.setdefault('errors',[]).append(type(e).__name__);break
        detail['sessions'][-1]['issued']=attempted;save()
        if continuous:sample_process()
save()
print('TOTAL',report['planned'],report['issued'],report['verified'],flush=True)
raise SystemExit(int(report['issued']!=report['planned'] or report['verified']!=report['issued']))
