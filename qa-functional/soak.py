import json,os,pathlib,subprocess,sys,time
from ext_run import adb
state=pathlib.Path('qa-emby-local/streams.json');streams=json.loads(state.read_text())
out=pathlib.Path('qa-emby-evidence');out.mkdir(exist_ok=True)
apk=next(pathlib.Path('app-apk').rglob('*.apk'))
pkg='com.yfuse.validation';adb('install','-r','-g',str(apk));adb('shell','settings','put','secure','immersive_mode_confirmations','confirmed');adb('shell','wm','dismiss-keyguard');adb('reverse','tcp:18196','tcp:18196')
cases=[('ProbeA','hls',150,'.2,.7,.3')]
failed=False
for i,(clip,key,seconds,targets) in enumerate(cases):
 if i:
  adb('shell','input','keyevent','4');time.sleep(2)
 uri=streams[clip][key]
 os.environ["QA_ACTIVE_MEDIA"]=uri
 os.environ["QA_ACTIVE_MIME"]="video/"+("mp4" if key=="url" else "mpegurl")
 # A single remote shell argument must keep URL query separators literal.
 import shlex
 adb('shell','am start -a android.intent.action.VIEW -n '+pkg+'/com.yfuse.feature.player.ExternalPlaybackActivity -t video/'+('mp4' if key=='url' else 'mpegurl')+' -d '+shlex.quote(uri))
 time.sleep(2)
 evidence=out/(clip+'-'+key)
 result=subprocess.run([sys.executable,str(pathlib.Path(__file__).with_name('soak_run.py')),'--out',str(evidence),'--duration',str(seconds),'--targets',targets])
 failed|=result.returncode!=0
# The synthetic credentials are only in the disposable data folder. Retain frame evidence only.
for f in out.rglob('*.xml'):f.unlink()
print('Emby UI scenarios executed:',len(cases),flush=True)
sys.exit(int(failed))
