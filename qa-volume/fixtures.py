import sys,subprocess,pathlib,json
sys.path.insert(0,'qa-scrub')
from fixture import create
from os import environ
group=int(sys.argv[1])
root=pathlib.Path('qa-media');root.mkdir(exist_ok=True)
out=[]
spec=[('h264','mp4','video/mp4'),('h264','mkv','video/x-matroska'),('h264','mov','video/quicktime'),('hevc','mp4','video/mp4'),('hevc','mkv','video/x-matroska'),('vp9','webm','video/webm'),('mpeg4','mp4','video/mp4'),('h264','ts','video/mp2t')]
for j,(codec,ext,mime) in enumerate(spec):
    secs=79+(group*11+j*7)%34
    base=root/'input.mp4';create(str(base),secs,'A' if (group+j)%2==0 else 'B')
    resolution=[320,480,640,960,1280][(group+j)%5];name=f'clip-{group:02}-{j:02}.{ext}';dst=root/name
    encoder={'h264':['-c:v','libx264','-preset','ultrafast'],'hevc':['-c:v','libx265','-preset','ultrafast','-x265-params','pools=2:frame-threads=1:log-level=error'],'vp9':['-c:v','libvpx-vp9','-deadline','realtime','-cpu-used','8'],'mpeg4':['-c:v','mpeg4','-q:v','4']}[codec]
    opts=['-c:a','libopus','-b:a','48k'] if ext=='webm' else ['-c:a','copy']
    subprocess.run(['ffmpeg','-nostdin','-loglevel','error','-y','-i',str(base),'-vf',f'scale={resolution}:-2','-threads','2','-g','12']+encoder+opts+(['-movflags','+faststart'] if ext in ('mov','mp4') else [])+(['-muxdelay','0','-muxpreload','0'] if ext=='ts' else [])+[str(dst)],check=True)
    out.append(dict(name=name,duration=secs,codec=codec,mime=mime,width=resolution,size=dst.stat().st_size))
base.unlink();(root/'fixtures.json').write_text(json.dumps(out))
