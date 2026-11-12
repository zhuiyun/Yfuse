import array,io,json,pathlib,subprocess,time
from PIL import Image
from run import marker
def verify(f,secs,root):
 records=json.loads(f.read_text());out=[]
 for title,resource in sorted(records.items()):
  for mode in ('url','hls'):
   if not resource.get(mode):continue
   src=resource[mode].replace(':18196/',':8096/')
   for sec in secs:
    common=['ffmpeg','-v','error','-nostdin','-threads','1','-filter_threads','1','-rw_timeout','6000000','-ss',str(sec),'-i',src]
    row={'item':title,'mode':mode,'target':sec};start=time.monotonic()
    try:
     a=subprocess.run(common+['-an','-frames:v','1','-c:v','png','-threads','1','-f','image2pipe','pipe:1'],capture_output=True,timeout=18)
     row['frame']=marker(Image.open(io.BytesIO(a.stdout)).convert('RGB')) if a.returncode==0 and a.stdout else None
     b=subprocess.run(common+['-vn','-t','0.25','-ac','1','-ar','16000','-f','s16le','pipe:1'],capture_output=True,timeout=18)
     pcm=array.array('h');pcm.frombytes(b.stdout)
     row['tone_hz']=round(sum(v<=0<w for v,w in zip(pcm,pcm[1:]))*16000/len(pcm)) if pcm else None
     hz=523 if title=='ProbeA' else 880
     row['ok']=row['frame'] is not None and abs(row['frame']-sec)<=1 and row['tone_hz'] is not None and abs(row['tone_hz']-hz)<18
    except Exception as e:row['error']=type(e).__name__;row['ok']=False
    row['test_seconds']=round(time.monotonic()-start,2);out.append(row)
    (root/'server-seek-check.json').write_text(json.dumps(out,indent=2));print('read',title,mode,sec,row.get('frame'),row.get('tone_hz'),row['ok'],flush=True)
