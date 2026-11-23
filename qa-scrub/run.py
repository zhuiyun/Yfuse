import argparse, io, json, pathlib, re, statistics, subprocess, time
import xml.etree.ElementTree as ET
from PIL import Image

def adb(*args, raw=False, timeout=35, check=True):
    r=subprocess.run(['adb',*map(str,args)],capture_output=True,timeout=timeout)
    if check and r.returncode:raise RuntimeError(r.stderr.decode(errors='replace')[:400])
    return r.stdout if raw else r.stdout.decode(errors='replace')

def screen():
    data=adb('exec-out','screencap','-p',raw=True)
    return Image.open(io.BytesIO(data)).convert('RGB')

def marker(im):
    w,h=im.size; scale=min(w/320,h/180); x0=(w-320*scale)/2; y0=(h-180*scale)/2
    for y in (86,92,77,99):
        values=[sum(im.getpixel((int(x0+(58+12*i)*scale),int(y0+y*scale))))/3 for i in range(18)]
        black=statistics.median(values[i] for i in (1,15,17)); white=statistics.median(values[i] for i in (0,2,16))
        if white-black<45:continue
        bits=[int(v>(white+black)/2) for v in values]
        if bits[:3]!=[1,0,1] or bits[15:]!=[0,1,0] or sum(bits[3:14])%2!=bits[14]:continue
        return int(''.join(map(str,bits[3:14])),2)/8
    return None

def dump():
    try:
        adb('shell','uiautomator','dump','/sdcard/qa-window.xml',timeout=12)
        return ET.fromstring(adb('shell','cat','/sdcard/qa-window.xml'))
    except Exception:return ET.Element('empty')

def nodes(xml):
    for n in xml.iter('node'):
        r=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
        if len(r)==4:yield n,r

def rail(xml):
    for n,r in nodes(xml):
        attrs=' '.join(n.attrib.values())
        if ('SeekBar' in attrs or '播放进度' in attrs) and r[2]-r[0]>150:return r

def tap(x,y):adb('shell','input','tap',int(x),int(y))

def findrail(out):
    for n in range(8):
        xml=dump(); result=rail(xml)
        (out/f'ui-{n}.xml').write_bytes(ET.tostring(xml,encoding='utf-8'))
        if result:return result
        im=screen(); im.save(out/f'calibrate-{n}.png');w,h=im.size
        handled=False
        for node,box in nodes(xml):
            if (node.get('text','').lower() in ['打开','open','got it','知道了','确定','ok'] and node.get('clickable')=='true'):
                tap((box[0]+box[2])/2,(box[1]+box[3])/2);handled=True;break
        if not handled:tap(w*.52,h*.36)
        time.sleep(.22)
    raise RuntimeError('No actual progress bar located')

def run(opt):
    out=opt.out;out.mkdir(exist_ok=True,parents=True)
    results=[];r=findrail(out);print('rail',r,flush=True)
    x0,y0,x1,y1=r;y=(y0+y1)//2
    width,height=screen().size
    def x(frac):return round(x0+(x1-x0-1)*frac)
    def swipe(start,end):
        adb('shell','input','swipe',x(start),y,x(end),y,320)
    def verify(target,i):
        t=time.monotonic(); first=None;times=[];good=False;im=None
        while time.monotonic()-t<opt.timeout:
            im=screen(); pts=marker(im);elapsed=time.monotonic()-t
            if pts is not None:
                times.append([round(elapsed,2),pts])
                if target-3.0<=pts<=target+elapsed+3.0:
                    if first is None:first=(elapsed,pts);im.save(out/f'{i:02}-land.png')
                    elif pts>=first[1]+.375 and pts-first[1]<=elapsed-first[0]+2.5:good=True;break
            time.sleep(.23)
        if im:im.save(out/f'{i:02}-end.png')
        return dict(target=round(target,2),confirmed=good,firstSampleSec=(round(first[0],2) if first else None),observations=times)
    for i,f in enumerate(opt.targets,1):
        # Reveal the controls before the swipe when the previous evidence series let them close.
        r_now=rail(dump())
        if not r_now:tap(width*.52,height*.36);time.sleep(.32)
        start=.18 if f>.5 else .81
        swipe(start,f)
        result=verify(opt.duration*f,i);results.append(result)
        print('swipe',i,json.dumps(result),flush=True)
        (out/'results.json').write_text(json.dumps({'rail':r,'duration':opt.duration,'results':results},indent=2))
    adb('shell','dumpsys','media_session',check=False)
    return all(v['confirmed'] for v in results)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--out',type=pathlib.Path,required=True);p.add_argument('--duration',type=float,required=True);p.add_argument('--timeout',type=float,default=14);p.add_argument('--targets',type=lambda s:list(map(float,s.split(','))),default=[.72,.14,.84,.27,.64,.1,.77,.33,.91,.19,.59,.39]); raise SystemExit(0 if run(p.parse_args()) else 2)
