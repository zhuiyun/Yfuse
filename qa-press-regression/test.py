import os
from engine import engine_evidence
import base64,json,random,re,shlex,sys,time,subprocess,pathlib
sys.path.insert(0,'qa-functional')
from ext_run import adb,marker,screen,tap,rail
from features import find,coords,snapshot
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from threading import Thread
root=pathlib.Path('qa-media');out=pathlib.Path('results');out.mkdir(exist_ok=True)
clips=json.loads((root/'fixtures.json').read_text()); allow={r['name'] for r in clips}
class HTTP(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_HEAD(self):self.serve(False)
    def do_GET(self):self.serve(True)
    def serve(self,body):
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
server=ThreadingHTTPServer(('127.0.0.1',18382),HTTP);server.daemon_threads=True;Thread(target=server.serve_forever,daemon=True).start()
shard=int(sys.argv[1])
rec=[];report={'shard':shard,'planned':len(clips)*48,'files':rec}
def save():
    report['issued']=sum(bool(r.get('issued')) for c in rec for r in c['rows'])
    report['verified']=sum(bool(r.get('ok')) for c in rec for r in c['rows'])
    (out/'volume.json').write_text(json.dumps(report))
def open_media(clip):
    adb('shell','am','force-stop','com.yfuse')
    url='http://127.0.0.1:18382/'+clip['name']
    cmd='am start -W -a android.intent.action.VIEW -n com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity -t '+clip['mime']+' -d '+url
    adb('shell',cmd);seen=None;started=time.monotonic()
    while time.monotonic()-started<50:
        current=marker(screen())
        if current is not None and seen is not None and current>seen+.25 and current-seen<6:return True
        if current is not None:seen=current
        else:
            tree=snapshot();b=find(tree,['打开','Open','仍然打开','知道了','Got it','确定','OK'])
            if b is not None:tap(*coords(b))
            if tree.tag=='launcher-dismissed':adb('shell',cmd)
        time.sleep(.28)
    return False
for idx,clip in enumerate(clips):
    detail=dict(clip,rows=[],sessions=[]);rec.append(detail);save()
    for session in range(3):
        since=time.time()
        ok=open_media(clip)
        proof=engine_evidence(os.environ['QA_ENGINE'],since)
        if ok and not proof['active']:
            time.sleep(1.4);proof=engine_evidence(os.environ['QA_ENGINE'],since)
        detail['sessions'].append({'session':session,'opened':ok,'identity':proof})
        print('IDENTITY',clip['name'],session,ok,proof,flush=True)
        ok=ok and proof['active']
        detail['open']='passed' if ok or detail.get('open')=='passed' else 'no advancing picture'
        if not ok:save();continue
        attempted=0
        for batch in range(1):
            seed=(51037111 if os.environ['QA_ENGINE']=='YCore' else 61037111)+shard*2017+idx*137+session*19+batch
            try:
                answer=adb('shell','am','instrument','-w','-e','mode','volume','-e','variant','original','-e','seed',seed,'-e','count',16,'-e','duration',clip['duration'],'qa.yfuse.gesture/.Driver',timeout=350)
                match=re.search(r'INSTRUMENTATION_RESULT: rows=([A-Za-z0-9+/=]+)',answer)
                if not match:raise ValueError('missing instrumentation response')
                rows=json.loads(base64.b64decode(match[1]))
                for row in rows:row['seed']=seed
                detail['rows'].extend(rows)
                n=sum(bool(r.get('issued')) for r in rows);good=sum(bool(r.get('ok')) for r in rows);attempted+=n
                print('BATCH',clip['name'],session,batch,'issued',n,'verified',good,flush=True)
                save()
                if n<8:break
            except (ValueError,subprocess.SubprocessError,RuntimeError) as e:
                detail.setdefault('errors',[]).append(type(e).__name__);break
        detail['sessions'][-1]['issued']=attempted;save()
save()
print('TOTAL',report['planned'],report['issued'],report['verified'],flush=True)
raise SystemExit(int(report['issued']!=report['planned'] or report['verified']!=report['issued']))
