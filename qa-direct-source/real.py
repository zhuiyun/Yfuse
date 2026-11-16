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


if __name__=="__main__":
    OUT.mkdir(exist_ok=True)
    SOURCES=load_envelope()
    denied_android=set()
    for label,media in SOURCES.items():
        safe={k:v for k,v in media.items() if k in ("type","container","video","audio","width","size","seconds")}
        steps={key:sample_range(media,offset) for key,offset in (("start",0),("middle",media["size"]//2),("end",max(0,media["size"]-1024)))}
        for key,x in steps.items():
            sig=x.pop("signature",None)
            x["matchesOrigin"]=bool(sig and sig==media["rangeHashes"].get(key))
        measured(label+"-ranges",**safe,originCodes=media["rangeCodes"],checks=steps)
        accessible=any(x["validRange"] for x in steps.values())
        if not accessible and media["type"] in denied_android:continue
        if not accessible:denied_android.add(media["type"])
        adb("shell","am","force-stop",PKG)
        mime={"mkv":"video/x-matroska","mov":"video/quicktime","ts":"video/mp2t","webm":"video/webm"}.get(media["container"],"video/mp4")
        cmd="am start -W -a android.intent.action.VIEW -n com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity -t "+mime+" -d "+shlex.quote(media["location"])
        opened=adb("shell",cmd,check=False)
        if "Error" in opened or "Exception" in opened:measured(label+"-android",moving=False,launchError=True);continue
        try:
            result=decode_playback(label,None,media["seconds"])
            measured(label+"-android",moving=True,**result)
            for target in (.67,.27):
                r,_=reveal();x0,y0,x1,y1=r;y=(y0+y1)//2
                adb("shell","input","swipe",round(x0+(x1-x0)*(.19 if target>.5 else .82)),y,round(x0+(x1-x0)*target),y,340)
                confirmed=decode_playback(label,target*media["seconds"],media["seconds"])
                measured(label+"-seek",fraction=target,moving=True,**confirmed)
        except (AssertionError,RuntimeError,TimeoutError,subprocess.TimeoutExpired):measured(label+"-android-incomplete",moving=False)
        finally:adb("shell","am","force-stop",PKG,check=False)
    print("COMPLETE",len(SOURCES),"direct media",flush=True)
