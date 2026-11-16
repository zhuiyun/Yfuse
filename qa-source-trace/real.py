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



def sample_range(media, position):
    req=urllib.request.Request(media["location"],headers={"Range":f"bytes={position}-{position+1023}","User-Agent":"Yfuse/1.9.4","Accept-Encoding":"identity"})
    try:
        with POLICY.open(req,timeout=10) as data:
            chunk=data.read(1024)
            response=data.headers.get("Content-Range","")
            expected=re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", response)
            correct=bool(expected and int(expected[1])==position and int(expected[2])==position+1023 and int(expected[3])==media["size"])
            return {"code":data.status,"bytesRead":len(chunk),"validRange":correct,"signature":hashlib.sha256(chunk).hexdigest()[:16] if correct else None}
    except urllib.error.HTTPError as exc:
        body=exc.read(8192).decode("utf-8","ignore").lower()
        flags=[tag for tag,terms in {"expired":["expiredtoken","request has expired","url expired","已过期"],"addressDenied":["ip mismatch","ip not allowed","ip does not match","ip不匹配"],"signatureRejected":["signaturedoesnotmatch","signature mismatch","invalid signature"],"accessDenied":["accessdenied","access denied","forbidden"]}.items() if any(t in body for t in terms)]
        return {"code":exc.code,"bytesRead":0,"validRange":False,"providerHint":flags}
    except (TimeoutError,OSError):return {"code":0,"bytesRead":0,"validRange":False}



def app_events():
    raw=adb("shell","cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl",check=False)
    keep=[]
    fields={"failurecategory","category","stage","errorcode","code","status","httpstatus","responsestatus","engine","reason","route","decoder","phase","positionms","durationms","bufferedpositionms","firstframerendered","videoready","outputgate","playbackrequested","domain","index","width","height","mime","selected"}
    knownText={"network","source","decoder","unknown","ready","failed","buffering","nativedirect","ffmpeg","mediacodec","ycore","senplayer","true","false","videodecoderconfigure","sourceopen","datasourceread","transport","engine","http","tcp","timeout","codec","ended","software","hardware","preparing","surface","video/hevc","video/avc","video/av01"}
    for line in raw.splitlines():
        try:x=json.loads(line)
        except ValueError:continue
        event=str(x.get("event",""));category=str(x.get("category",""))
        if not re.fullmatch(r"[a-zA-Z_][a-zA-Z_0-9.]{0,80}",event) or not any(t in category.lower() for t in ["player","playback","transport","core2","media","decoder"]):continue
        attrs={}
        for k,v in x.get("attributes",{}).items():
            k=k.lower();v=str(v).lower()
            if k in fields and (re.fullmatch(r"-?\d{1,15}",v) or v in knownText):attrs[k]=v
        # Extract only allowlisted technical categories, never log a response, stack, filename, URL or trace message.
        combined=" ".join([str(x.get("message","")),str(x.get("exception") or {}),str(x.get("attributes",{}))]).lower()
        labels=[q for q in ["403","401","416","sslhandshakeexception","sockettimeoutexception","unknownhostexception","econnreset","connection reset","invalidresponsecode","unsupported","decoder configure","mediaserver died","codecexception","insufficient resource","permission denied","httpdatasource","connection refused"] if q in combined]
        keep.append({"event":event,"time":x.get("timestamp"),"values":attrs,"hints":labels})
    return keep

def android_network(url):
    import subprocess
    p="/data/local/tmp/yfuse-direct-qa-url"
    # Feed bytes over stdin; neither the launcher command nor Gradle logs contain the signed address.
    subprocess.run(["adb","shell",f"umask 077; cat > {p}"],input=url.encode(),stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
    try:
        out=adb("shell","am","instrument","-w","-e","mode","range","qa.yfuse.gesture/.Driver",timeout=50)
        match=re.search(r'INSTRUMENTATION_RESULT: network=(\{[^\n]+\})',out)
        return json.loads(match[1]) if match else {"driverResult":"missing"}
    finally:adb("shell",f"rm -f {p}",check=False)

if __name__=="__main__":
    OUT.mkdir(exist_ok=True)
    items=load_envelope()
    for label,media in items.items():
        host=sample_range(media,0);host.pop("signature",None)
        measured(label+"-origin-range",codes=media["rangeCodes"])
        measured(label+"-runner-range",**host)
        measured(label+"-android-range",**android_network(media["location"]))
        try:
            q=subprocess.run(["ffprobe","-v","error","-user_agent","Yfuse/1.9.4","-rw_timeout","9000000","-probesize","5242880","-analyzeduration","4000000","-show_entries","stream=codec_name,width,height:format=duration,size","-of","json",media['location']],capture_output=True,timeout=28)
            measured(label+"-runner-decode",success=q.returncode==0,streams=json.loads(q.stdout or b"{}").get("streams",[]))
        except (ValueError,OSError,subprocess.TimeoutExpired):measured(label+"-runner-decode",success=False)
        adb("shell","am","force-stop",PKG);prior={(x['time'],x['event']) for x in app_events()}
        cmd="am start -W -a android.intent.action.VIEW -n com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity -t video/"+("x-matroska" if media['container']=='mkv' else "mp4")+" -d "+shlex.quote(media['location'])
        adb('shell',cmd,check=False)
        try:
            result=decode_playback(label,None,media['seconds'])
            measured(label+"-screen",moving=True,**result)
        except (ValueError,RuntimeError,AssertionError,subprocess.TimeoutExpired):measured(label+"-screen",moving=False)
        events=[e for e in app_events() if (e['time'],e['event']) not in prior]
        measured(label+"-player-diagnostics",total=len(events),events=events[-150:])
        adb("shell","am","force-stop",PKG)
