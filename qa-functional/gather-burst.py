import io,tarfile,json,pathlib,subprocess,re
p=pathlib.Path('qa-emby-evidence');p.mkdir(exist_ok=True)
pkg='com.yfuse.validation'
def adb(*args):return subprocess.run(['adb',*args],capture_output=True,timeout=35).stdout
raw=adb('exec-out','run-as',pkg,'tar','-cf','-','no_backup/diagnostics')
def safe(s):
 s=re.sub(r'(?i)(?:https?|rtsp)://[^\s,)\]"<>]+','[url]',str(s))
 s=re.sub(r'(?i)(token|api_key|password|authorization|secret|signature)(?:=|:|%3D)[^\s&,}\]"]+',r'\1=[redacted]',s)
 return re.sub(r'(?i)(?<![a-z0-9])[a-f0-9]{24,}(?![a-z0-9])','[id]',s)[:1250]
def trim(o):
 if isinstance(o,dict):return {k:trim(v) for k,v in o.items() if not re.search(r'(?i)token|url|uri|payload|header|user|password|host|ipaddress|credential|endpoint',k)}
 if isinstance(o,list):return [trim(v) for v in o[:16]]
 return safe(o) if isinstance(o,str) else o
entries=[]
if raw:
 try:
  with tarfile.open(fileobj=io.BytesIO(raw)) as t:
   for f in t:
    if not f.name.endswith('.jsonl'):continue
    for l in t.extractfile(f):
     try:o=json.loads(l)
     except:continue
     if re.search(r'seek|failed|error|retry|route|buffering|demux|recover|decoder|retir|session_open|prepare',str(o.get('event',''))+' '+str(o.get('category','')),re.I):
      entries.append(trim(o))
 except Exception:pass
(p/'app-events.jsonl').write_text('\n'.join(json.dumps(i,ensure_ascii=False) for i in entries))
apppid=adb('shell','pidof',pkg).decode().strip().split()
if apppid:
 rawlog=adb('logcat','-d','--pid',apppid[0]).decode(errors='replace').splitlines()
 keep=[safe(l) for l in rawlog if re.search(r'ffmpeg|avformat|demux|seek|http error|playback_error|recovery|decoder.*err|failed.*source|invalid data',l,re.I)]
 (p/'player-errors.txt').write_text('\n'.join(keep[-800:]))
serv=[]
for f in pathlib.Path('qa-emby-local').rglob('*.txt'):
 if 'log' in f.name.lower() or 'ffmpeg' in f.name.lower():
  try:serv.extend(safe(l) for l in f.read_text(errors='replace').splitlines() if re.search(r'failed|error|invalid|cannot|broken pipe|restarting',l,re.I))
  except Exception:pass
(p/'server-errors.txt').write_text('\n'.join(serv[-100:]))
