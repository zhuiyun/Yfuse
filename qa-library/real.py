import base64
import contextlib
import hashlib
import http.server
import io
import json
import os
import re
import shlex
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from PIL import Image, ImageChops, ImageStat
import sys
sys.path.insert(0,'qa-functional')
from ext_run import adb, rail, screen, tap
from features import find, coords, snapshot

TEMP = Path(os.environ["RUNNER_TEMP"])
OUT = Path("private-results")
PKG = "com.yfuse"
ROWS = []
METRICS = {}
SOURCES = {}
BODY = {}
POLICY = urllib.request.build_opener(urllib.request.ProxyHandler({}))
ERROR = None


def load_envelope():
    envelope = json.loads((TEMP/"envelope.json").read_text())
    private = serialization.load_pem_private_key((TEMP/"qa-private.pem").read_bytes(),password=None)
    unwrap = private.decrypt(base64.b64decode(envelope["key"]), padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=None))
    data = AESGCM(unwrap).decrypt(base64.b64decode(envelope["nonce"]),base64.b64decode(envelope["ciphertext"]),(os.environ["GITHUB_RUN_ID"]+"-"+os.environ["QA_SHARD"]).encode())
    return json.loads(data)


class MediaProxy(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_HEAD(self):
        self.forward(False)

    def do_GET(self):
        self.forward(True)

    def forward(self, body):
        label=self.path.split("?",1)[0].strip("/").split(".")[0]
        if label not in SOURCES:
            self.send_error(404)
            return
        item=SOURCES[label]
        req_headers={"User-Agent":"Yfuse/1.9.4","Accept-Encoding":"identity"}
        if self.headers.get("Range"):
            req_headers["Range"]=self.headers["Range"]
        req=urllib.request.Request(item["location"],headers=req_headers,method="GET" if body else "HEAD")
        status=0
        try:
            with POLICY.open(req,timeout=18) as upstream:
                status=upstream.status
                METRICS[label]["statuses"].add(status)
                METRICS[label]["requests"]+=1
                self.send_response(status)
                for header in ("Content-Type","Content-Length","Content-Range","Accept-Ranges","ETag","Last-Modified"):
                    value=upstream.getheader(header)
                    if value:
                        self.send_header(header,value)
                self.end_headers()
                if body:
                    while METRICS[label]["bytes"]<350*1024*1024:
                        data=upstream.read(65536)
                        if not data:break
                        self.wfile.write(data)
                        METRICS[label]["bytes"]+=len(data)
        except urllib.error.HTTPError as e:
            METRICS[label]["statuses"].add(e.code)
            with contextlib.suppress(OSError):self.send_error(e.code)
        except (ConnectionResetError,BrokenPipeError,TimeoutError,OSError):
            if not status:
                METRICS[label]["statuses"].add(0)
                with contextlib.suppress(OSError):self.send_error(502)


def measured(label, **detail):
    ROWS.append({"case":label, **detail})
    report={"measurements":ROWS,"network":{k:{"codes":sorted(v["statuses"]),"requests":v["requests"],"bytes":v["bytes"]} for k,v in METRICS.items()}}
    (OUT/"metrics.json").write_text(json.dumps(report,indent=2))
    print("PRIVATE",label,json.dumps(detail),flush=True)


def media_clock(tree):
    def clock_in(s):
        match=re.search(r"(?<!\d)(?:(?:\d{1,2}:)?\d{1,3}:\d\d)(?!\d)",s)
        if not match:return None
        value=0
        for part in match.group().split(":"):value=value*60+int(part)
        return value
    for node in tree.iter("node"):
        state=node.get("state","")
        if "播放进度" in state or "SeekBar" in node.get("class",""):
            here=clock_in(state)
            if here is not None:return here
    times=[clock_in(node.get("text","")) for node in tree.iter("node") if re.fullmatch(r"(?:\d{1,2}:)?\d{1,3}:\d\d",node.get("text","").strip())]
    times=[v for v in times if v is not None]
    return min(times) if times else None


def reveal():
    for _ in range(6):
        tree=snapshot()
        r=rail(tree)
        if r:return r,tree
        button=find(tree,["允许","打开","Open","Got it","确认打开","知道了","OK"])
        if button is not None:tap(*coords(button))
        else:
            im=screen();w,h=im.size;tap(w*.52,h*.36)
        time.sleep(.4)
    raise AssertionError("no progress control")


def patch(im):
    w,h=im.size
    return im.crop((int(.17*w),int(.31*h),int(.83*w),int(.66*h))).resize((96,48)).convert("RGB")


def decode_playback(label,target,duration,max_wait=60):
    begin=time.monotonic()
    prev=None
    first=None
    speech=[]
    laststatus=None
    history=[]
    max_wait=42 if target is None else 32
    while time.monotonic()-begin<max_wait:
        try:r,tree=reveal()
        except AssertionError:
            tree=snapshot()
            flags=sorted({s for node in tree.iter("node") for s in ("播放失败","重新播放","不支持","解码","正在缓冲","重新连接","播放进度","加载超时") if s in (node.get("text","")+" "+node.get("state","")+" "+node.get("content-desc",""))})
            history.append({"flags":flags,"noRail":True,"elapsed":round(time.monotonic()-begin,1)})
            time.sleep(.5);continue
        pos=media_clock(tree)
        pix=patch(screen())
        elapsed=time.monotonic()-begin
        history.append({"s":pos,"elapsed":round(elapsed,1),"contrast":round(sum(ImageStat.Stat(pix).stddev)/3,2)})
        if pos is not None and pos<duration-1 and (target is None or target-12 <= pos <= target+elapsed+12):
            if prev:
                oldpos,oldim=prev
                diff=sum(ImageStat.Stat(ImageChops.difference(pix,oldim)).mean)/3
                if pos>oldpos and diff>.8:
                    speech.append({"s":pos,"pixelChange":round(diff,2)})
                    if first is None:first=(pos,elapsed)
                    if len(speech)>=2 and pos-first[0]>=1:
                        return {"firstPos":first[0],"laterPos":pos,"secToFirst":round(first[1],2),"secToConfirm":round(elapsed,2),"changes":speech[-3:]}
            prev=(pos,pix)
        time.sleep(.8)
    measured(label+"-observation",history=history[-16:])
    raise AssertionError("no moving movie image with advancing player time")


def check_source(label,item):
    global ERROR
    status=None
    try:
        req=urllib.request.Request(item["location"],headers={"Range":"bytes=0-1023","User-Agent":"Yfuse/1.9.4"})
        with POLICY.open(req,timeout=18) as read:
            status=read.status
            ok=status==206 and len(read.read(1024))==1024
    except urllib.error.HTTPError as e:status=e.code;ok=False
    except OSError:status=0;ok=False
    measured(label+"-source-range",ok=ok,status=status,video=item["video"],width=item["width"],itemType=item["type"],container=item["container"],sourceBytes=item["size"],thumbnailSet=item.get("thumb"))
    if not ok:raise AssertionError("runner cannot read issued film location")
    if list(SOURCES)[0]!=label:
        adb("shell","input","keyevent","4");time.sleep(1)
    ext="mp4" if "mp4" in item["container"] or "mov" in item["container"] else "mkv"
    mime="video/mp4" if ext=="mp4" else "video/x-matroska"
    url="http://127.0.0.1:18628/"+label+"."+ext
    adb("shell","am start -a android.intent.action.VIEW -n com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity -t "+mime+" -d "+shlex.quote(url))
    duration=item["seconds"]
    measured(label+"-open",ok=True,**decode_playback(label,None,duration))
    for n,fraction in enumerate((.64,.23),1):
        r,_=reveal()
        x0,y0,x1,y1=r
        y=(y0+y1)//2
        origin=.84 if fraction<.5 else .19
        x=lambda f:round(x0+(x1-x0-1)*f)
        adb("shell","input","swipe",x(origin),y,x(fraction),y,320)
        result=decode_playback(label,duration*fraction,duration)
        measured(label+"-seek-"+str(n),ok=True,target=round(duration*fraction),**result)


if __name__=="__main__":
    OUT.mkdir(exist_ok=True)
    SOURCES=load_envelope()
    METRICS={k:{"bytes":0,"requests":0,"statuses":set()} for k in SOURCES}
    httpd=http.server.ThreadingHTTPServer(("127.0.0.1",18628),MediaProxy)
    httpd.daemon_threads=True
    th=threading.Thread(target=httpd.serve_forever,daemon=True);th.start()
    failed=False
    for label,item in SOURCES.items():
        try:check_source(label,item)
        except Exception as e:
            failed=True
            measured(label+"-incomplete",ok=False,error=str(e) if isinstance(e,AssertionError) else type(e).__name__)
    httpd.shutdown()
    sys_exit=2 if failed else 0
    raise SystemExit(sys_exit)
