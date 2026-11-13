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
shard=int(sys.argv[1]);rec=[];report={'shard':shard,'planned':512,'files':rec}
def save():(out/'volume.json').write_text(json.dumps(report))

def export_trace():
    raw=adb('shell',"cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl",check=False)
    observed=[]
    for line in raw.splitlines():
        try:x=json.loads(line)
        except (ValueError,TypeError):continue
        event=str(x.get('event',''))
        if not re.fullmatch(r'(native_direct_[a-z0-9_]+|paused_preview_[a-z0-9_]+|native_decoder_[a-z0-9_]+|external_playback_opened|engine_[a-z0-9_]+|playback_[a-z0-9_]+)',event):continue
        keep={}
        for k,v in x.get('attributes',{}).items():
            key=k.lower()
            if key in {'targetus','positionus','positionms','buffered','presentationtimeus','bufferedseek','temporalsamples','playbackrequested','generation','result','timeus','ptsus','videoptsus','rendered'} and re.fullmatch(r'(?:-?[0-9]+|true|false)',str(v).lower()):
                keep[key]=v
        observed.append(dict(event=event,at=x.get('timestamp',x.get('time')),values=keep))
    (out/'numeric-player-events.json').write_text(json.dumps(observed))
    print('SANITIZED_PLAYER_EVENTS',len(observed),flush=True)

for idx,clip in enumerate(clips):
    if idx not in (0,3,7):continue
    detail=dict(clip,rows=[]);rec.append(detail);save()
    if idx:adb('shell','input','keyevent','4');time.sleep(.65)
    url='http://127.0.0.1:18382/'+clip['name']
    cmd='am start -a android.intent.action.VIEW -n com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity -t '+clip['mime']+' -d '+url
    adb('shell',cmd);seen=None;start=time.monotonic()
    while time.monotonic()-start<42:
        v=marker(screen())
        if v is not None and seen is not None and v>seen+.25:break
        if v is not None:seen=v
        else:
            t=snapshot();n=find(t,['打开','Open','仍然打开','知道了','Got it','确定','OK'])
            if n is not None:tap(*coords(n))
            if t.tag=='launcher-dismissed':adb('shell',cmd)
        time.sleep(.22)
    else:detail['open']='no advancing fixture timecode';print('OPEN FAILED',clip['name'],flush=True);save();continue
    detail['open']='passed'
    for batch in range(1):
        try:
            s=adb('shell','am','instrument','-w','-e','mode','volume','-e','variant',pathlib.Path('qa-replay-mode').read_text(),'-e','seed',381710+shard*701+idx*13+batch,'-e','count',16,'-e','duration',clip['duration'],'qa.yfuse.gesture/.Driver',timeout=220)
            m=re.search(r'INSTRUMENTATION_RESULT: rows=([A-Za-z0-9+/=]+)',s)
            if not m:raise ValueError('instrumentation returned no measurements')
            rows=json.loads(base64.b64decode(m[1]));detail['rows'].extend(rows)
            for row in rows:
                if row.get('issued') and not row.get('ok'):print('OBS',clip['name'],json.dumps(row,ensure_ascii=True),flush=True)
            print('BATCH',clip['name'],batch,'issued',sum(bool(x.get('issued')) for x in rows),'verified',sum(bool(x.get('ok')) for x in rows),flush=True)
        except Exception as ex:detail.setdefault('errors',[]).append(type(ex).__name__)
        save()
export_trace()
report['issued']=sum(bool(r.get('issued')) for c in rec for r in c['rows']);report['verified']=sum(bool(r.get('ok')) for c in rec for r in c['rows']);save()
print('TOTAL',report['planned'],report['issued'],report['verified'],flush=True)
assert report['issued']>0
