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
PLAYBACK_USER_AGENT = "Emby for Android Mobile"


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


def media_duration(tree):
    for node in tree.iter("node"):
        state=node.get("state","")
        if "播放进度" not in state:continue
        match=re.search(r"/\s*((?:\d{1,2}:)?\d{1,3}:\d\d)(?!\d)",state)
        if match:
            value=0
            for part in match[1].split(":"):value=value*60+int(part)
            if value>1:return value
    return None


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
    max_wait=max_wait if target is None else min(max_wait, 40)
    while time.monotonic()-begin<max_wait:
        observation_started=time.monotonic()
        try:r,tree=reveal()
        except AssertionError:
            tree=snapshot()
            flags=sorted({s for node in tree.iter("node") for s in ("播放失败","重新播放","不支持","解码","正在缓冲","重新连接","播放进度","加载超时") if s in (node.get("text","")+" "+node.get("state","")+" "+node.get("content-desc",""))})
            history.append({"flags":flags,"noRail":True,"elapsed":round(time.monotonic()-begin,1)})
            time.sleep(.5);continue
        pos=media_clock(tree)
        duration=media_duration(tree) or duration
        tree_finished=time.monotonic()
        pix=patch(screen())
        elapsed=time.monotonic()-begin
        history.append({"s":pos,"elapsed":round(elapsed,1),"contrast":round(sum(ImageStat.Stat(pix).stddev)/3,2),
                        "treeMs":round((tree_finished-observation_started)*1000),
                        "captureMs":round((time.monotonic()-tree_finished)*1000)})
        if pos is not None and (duration<=1 or pos<duration-1) and (target is None or target-12 <= pos <= target+elapsed+12):
            if prev:
                oldpos,oldim=prev
                diff=sum(ImageStat.Stat(ImageChops.difference(pix,oldim)).mean)/3
                if pos>oldpos and diff>.8:
                    speech.append({"s":pos,"pixelChange":round(diff,2)})
                    if first is None:first=(pos,elapsed)
                    if len(speech)>=2 and pos-first[0]>=1:
                        return {"firstPos":first[0],"laterPos":pos,"durationSeconds":duration,"secToFirst":round(first[1],2),"secToConfirm":round(elapsed,2),"changes":speech[-3:]}
            prev=(pos,pix)
        time.sleep(.8)
    measured(label+"-observation",history=history[-16:])
    raise AssertionError("no moving movie image with advancing player time")



def sample_range(media, position):
    req=urllib.request.Request(media["location"],headers={"Range":f"bytes={position}-{position+1023}","User-Agent":PLAYBACK_USER_AGENT,"Accept-Encoding":"identity"})
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
        detail={"code":exc.code,"bytesRead":0,"validRange":False,"providerHint":flags}
        try:
            failure=json.loads(body)
            if isinstance(failure.get("code"),int):detail["providerCode"]=failure["code"]
            message=str(failure.get("message",""))
            if re.fullmatch(r"download err: [0-9]{1,12}",message):detail["providerError"]=int(message.split(": ")[1])
        except (ValueError,AttributeError):pass
        return detail
    except (TimeoutError,OSError):return {"code":0,"bytesRead":0,"validRange":False}



def app_events():
    raw=adb("shell","cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl",check=False)
    keep=[]
    fields={"failurecategory","category","stage","errorcode","code","status","httpstatus","responsestatus","engine","reason","route","decoder","phase","positionms","durationms","bufferedpositionms","firstframerendered","videoready","outputgate","playbackrequested","domain","index","width","height","mime","selected"}
    knownText={"network","source","decoder","unknown","ready","failed","buffering","nativedirect","ffmpeg","mediacodec","ycore","senplayer","true","false","videodecoderconfigure","sourceopen","datasourceread","transport","engine","http","tcp","timeout","codec","ended","software","hardware","preparing","surface","video/hevc","video/avc","video/av01"}
    fields.update({"failurestage","codecmime","codecprofile","outputallowed","bufferedms","requiredms","targetms","waitms","queuebytes","memorybudgetbytes","queuebudgetbytes","plannedbudgetbytes","queuesamples","fillscheduled","readelapsedms","lastpacketagems","packetsread","demuxthroughputbps","pendingtrackcount","endofinput","atcapacity","generation","audiorendering","videorendering","renderedframecount","decodedframecount","droppedframecount","framesrendered","framesdropped","outputgeneration","videooutputverified","audiooutputverified","deterministic"})
    fields.update({"renderedframes","droppedframes","lastvideoms","softwarevideo","pendingvideoframe"})
    fields.update({"elapsedms","remainingms","attempt","itemindex"})
    fields.update({"blockindex","blocksize","blocksizebytes","offset","start","end","size","requestedbytes",
                   "allowancems","startupreadserved","prioritizestartupreads","prefetchdepthblocks","cachebytes",
                   "expectedrangestart","acceptedrangestart","attemptcount","redirectcount","contentlengthknown",
                   "negotiatedprotocol","finalprotocol","cleartextredirect"})
    knownText.update({"http/1.1","h2","http_2","http_1_1","https","unavailable"})
    fields.update({"nativeerrorcode","failurekind","sourcekept","candidatelimit","candidatecount","limitms","reservedms","outcome"})
    knownText.update({"authorization","drm","container","renderer","audiosink","license","network","format","playback","completed","deadline","busy","yielded","cancelled_or_deadline"})
    knownText.update({"nativenhanced","nativeenhanced","softwarefallback","softwarevideodecoderconfigure","softwarevideodecode","audiorender","videorender","audiodecoderconfigure","audiodecoderqueue","videodecoderqueue","demux","seek","prepare","audio/flac","audio/aac","audio/opus","video/dolby-vision","video/x-vnd.on2.vp9","video/mpeg2","video/mp4v-es"})

    for line in raw.splitlines():
        try:x=json.loads(line)
        except ValueError:continue
        event=str(x.get("event",""));category=str(x.get("category",""))
        if not re.fullmatch(r"[a-zA-Z_][a-zA-Z_0-9.]{0,80}",event) or not any(t in category.lower() for t in ["player","playback","transport","core2","media","decoder"]):continue
        attrs={}
        for k,v in x.get("attributes",{}).items():
            k=k.lower();raw_value=str(v);v=raw_value.lower()
            if k in fields and (re.fullmatch(r"-?\d{1,15}",v) or v in knownText):attrs[k]=v
            if (k in {"exceptiontype","origin"} or re.fullmatch(r"frame[0-7]",k)) and re.fullmatch(r"[a-z0-9_$.:() -]{1,200}",v):
                attrs[k]=raw_value
            if k in {"decoder","decoderattempts"} and re.fullmatch(r"(?:(?:c2|omx)\.[a-z0-9._-]+)(?:,(?:c2|omx)\.[a-z0-9._-]+)*",v):
                attrs[k]=v
        # Extract only allowlisted technical categories, never log a response, stack, filename, URL or trace message.
        combined=" ".join([str(x.get("message","")),str(x.get("exception") or {}),str(x.get("attributes",{}))]).lower()
        labels=[q for q in ["sslhandshakeexception","sockettimeoutexception","unknownhostexception","econnreset","connection reset","invalidresponsecode","unsupported","decoder configure","mediaserver died","codecexception","insufficient resource","permission denied","httpdatasource","connection refused"] if q in combined]
        labels.extend("http"+code for code in ("401","403","416") if re.search(r"(?:http(?: status)?|response code|statuscode)\s*[:=]?\s*"+code+r"(?!\d)",combined))
        exception=x.get('exception') or {}
        stack=str(exception.get('stackTrace','')) if isinstance(exception,dict) else ''
        # Only Java exception class names survive; message text and source locations do not.
        chain=re.findall(r'(?m)^(?:Caused by: )?([a-zA-Z_$][a-zA-Z0-9_$.]*(?:Exception|Error))(?::|$)',stack)
        keep.append({"event":event,"time":x.get("timestamp"),"values":attrs,"hints":labels,"exceptionChain":chain[:12]})
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


def record_android_range(label, url):
    started = time.monotonic()
    try:
        reply = android_network(url)
    except subprocess.TimeoutExpired:
        reply = {"driverResult": "timeout"}
    code = reply.get("status", 0)
    error_type = str(reply.get("exception", ""))
    driver_result = reply.get("driverResult")
    measured(label, code=code if type(code) is int else 0,
             bytesRead=reply.get("bytes") if type(reply.get("bytes")) is int else None,
             elapsedMs=round(1000 * (time.monotonic() - started)),
             errorType=error_type if re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]{0,64}(?:Exception|Error)", error_type) else None,
             driverResult=driver_result if driver_result in ("missing", "timeout") else "received")
    return code


def source_range_benchmark(media):
    # Bounded transport measurements only; no bytes, titles, URLs or response headers leave the runner.
    size=int(media.get("size",0))
    if size<8388608:return []
    amount=8388608
    measurements=[]
    for position in (0,(size-amount)//2,size-amount):
        begin=time.monotonic();received=0;first=None;code=0
        req=urllib.request.Request(media["location"],headers={"Range":f"bytes={position}-{position+amount-1}","User-Agent":PLAYBACK_USER_AGENT,"Accept-Encoding":"identity"})
        try:
            with POLICY.open(req,timeout=15) as response:
                code=response.status
                if code!=206:raise ValueError("Range benchmark requires partial response")
                while received<amount and time.monotonic()-begin<25:
                    data=response.read(min(262144,amount-received))
                    if not data:break
                    if first is None:first=time.monotonic()-begin
                    received+=len(data)
            elapsed=time.monotonic()-begin
            measurements.append({"status":code,"bytes":received,"elapsedMs":round(elapsed*1000),"firstByteMs":None if first is None else round(first*1000),"bitsPerSecond":round(received*8/max(elapsed,.001)),"complete":received==amount})
        except Exception as failure:
            measurements.append({"status":getattr(failure,"code",code),"bytes":received,"elapsedMs":round((time.monotonic()-begin)*1000),"errorType":type(failure).__name__})
    return measurements


def observed_seek_diagnostic(label, fraction, duration):
    """Keep a failed raw swipe failed; record a separate refreshed input receipt and retry."""
    reveal()
    response = adb('shell', 'am', 'instrument', '-w', '-e', 'mode', 'observed-seek',
                   '-e', 'fraction', fraction, 'qa.yfuse.gesture/.Driver', timeout=40)
    match = re.search(r'INSTRUMENTATION_RESULT: observedSeek=(\{[^\n]+\})', response)
    if not match:
        raise AssertionError('Observed seek receipt missing')
    receipt = json.loads(match[1])
    # The held image stays only in the disposable emulator. Public evidence contains numbers.
    safe = {k: receipt[k] for k in ('railLeft', 'railRight', 'endX', 'y', 'requestedFraction',
                                   'shownFraction', 'rangeMin', 'rangeMax')}
    if not all(isinstance(value, (int, float)) for value in safe.values()):
        raise ValueError('Non-numeric seek receipt')
    measured(label + '-input-diagnostic', fraction=fraction, receipt=safe)
    try:
        output = decode_playback(label + '-input-diagnostic', duration * fraction, duration)
        measured(label + '-diagnostic-retry', fraction=fraction, moving=True, **output)
    except (ValueError, RuntimeError, AssertionError, subprocess.TimeoutExpired):
        measured(label + '-diagnostic-retry', fraction=fraction, moving=False)


def source_probe(media):
    # Never retain stderr or the command arguments: both may repeat the signed address.
    try:
        proc=subprocess.run(['ffprobe','-v','quiet','-rw_timeout','15000000','-probesize','5242880','-analyzeduration','3000000','-user_agent',PLAYBACK_USER_AGENT,'-show_entries','format=duration,size,format_name:stream=codec_type,codec_name,profile,width,height,pix_fmt,color_space,color_transfer,color_primaries,r_frame_rate,avg_frame_rate,bits_per_raw_sample:stream_side_data','-of','json',media['location']],capture_output=True,timeout=45)
        data=json.loads(proc.stdout)
        streams=[]
        for stream in data.get('streams',[]):
            clean={k:v for k,v in stream.items() if k!='side_data_list' and isinstance(v,(str,int,float))}
            clean['sideData']=[{k:v for k,v in side.items() if isinstance(v,(int,float)) or k=='side_data_type'} for side in stream.get('side_data_list',[])]
            streams.append(clean)
        return {'exitCode':proc.returncode,'format':data.get('format',{}),'streams':streams}
    except (OSError,ValueError,subprocess.SubprocessError) as exc:
        return {'errorType':type(exc).__name__}

if __name__=='__main__':
    sys.path.insert(0,'qa-arm-matrix')
    import engine
    import concurrent.futures, collections
    OUT.mkdir(exist_ok=True)
    items=load_envelope()
    def ranges(pair):
        label,media=pair;size=media['size']
        positions={'start':0,'middle':size//2,'end':size-1024}
        answers={}
        for where,pos in positions.items():
            result=sample_range(media,pos)
            answers[where]={'code':result['code'],'correct':bool(result.get('validRange') and result.get('signature')==media.get('rangeHashes',{}).get(where))}
            answers[where].update({k:result[k] for k in ('providerHint','providerCode','providerError') if k in result})
        return label,answers
    groups={}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for label,answers in pool.map(ranges,items.items()):
            groups[label]=answers
            media=items[label]
            measured(label+'-admission',kind=media['type'],container=media['container'],size=media['size'],durationSeconds=media['seconds'],technical=media.get('technical',{}),source=media['rangeCodes'],runner=answers)
    candidates=[k for k,positions in groups.items() if any(r['correct'] for r in positions.values())]
    controls=[next((k for k in groups if k.startswith(prefix) and k not in candidates),None) for prefix in ('m-','e-')]
    launched=candidates+[k for k in controls if k]
    if os.environ.get('QA_VERIFIED_PRIVATE_RELAY') == 'true':
        # Every original admitted to this pinned test session has source-side byte hashes.
        # Exercise tracks with a failed cloud probe too, retaining the original failing gate.
        # Refusals and identity mismatches must be investigated before those tracks are opened.
        def can_open(label):
            return all(row['code'] not in (401,403,404,410,412,429) and
                       (row['code'] != 206 or row['correct']) for row in groups[label].values())
        launched=[label for label in items if can_open(label)]
    probe_labels=set(os.environ.get('QA_PROBE_LABELS','').split(','))
    benchmark_labels=set(os.environ.get('QA_BENCHMARK_LABELS','').split(','))
    late_open_labels=set(os.environ.get('QA_LATE_OPEN_LABELS','').split(','))
    for label in launched:
        if label in probe_labels:measured(label+'-format-probe',**source_probe(items[label]))
        if label in benchmark_labels:measured(label+'-range-benchmark',samples=source_range_benchmark(items[label]))
    cores=os.environ.get('QA_ENGINES','YCore,SenPlayer').split(',')
    if not cores or len(set(cores))!=len(cores) or any(core not in ('YCore','SenPlayer') for core in cores):raise ValueError('Invalid cloud engine selection')
    pinned_relay = os.environ.get('QA_VERIFIED_PRIVATE_RELAY') == 'true'
    configured_core = None
    for index, k in enumerate(launched):
        # Give both engines the same source lifetime. Alternate which one sees each title first.
        for core in (cores if index % 2 == 0 else reversed(cores)):
            media=items[k];label=core+'-'+k
            range_status = record_android_range(label+'-android-network', media['location'])
            if pinned_relay and range_status != 206:
                if range_status not in (401, 403, 404, 410, 412, 429):
                    range_status = record_android_range(label+'-android-network-recheck', media['location'])
                if range_status != 206:
                    measured(label+'-input-unavailable', code=range_status)
                    continue
            if core != configured_core:
                engine.configure(core)
                configured_core = core
            adb('shell','am','force-stop',PKG);since=time.time();prior={(x['time'],x['event']) for x in app_events()}
            mime={'mkv':'x-matroska','ts':'mp2t','mpegts':'mp2t','webm':'webm','mov':'quicktime','mp4':'mp4'}.get(media['container'],'*')
            cmd='am start -W -a android.intent.action.VIEW -n com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity -t video/'+mime+' -d '+shlex.quote(media['location'])
            try:
                adb('shell',cmd,check=False)
                result=decode_playback(label,None,media['seconds'],max_wait=int(os.environ.get('QA_OPEN_DIAGNOSTIC_SECONDS','60')));proof=engine.engine_evidence(core,since)
                identity_deadline=time.monotonic()+12
                while not proof['active'] and time.monotonic()<identity_deadline:
                    time.sleep(1)
                    proof=engine.engine_evidence(core,since)
                measured(label+'-opened',moving=True,identity=proof,**result)
                if proof['active']:
                    for fraction in (.63,.21):
                        try:
                            duration=result['durationSeconds']
                            if duration<=1:raise AssertionError('No measured media duration for a seek')
                            r,_=reveal();left,top,right,bottom=r;y=(top+bottom)//2
                            adb('shell','input','swipe',round(left+(right-left)*(.83 if fraction<.5 else .17)),y,round(left+(right-left)*fraction),y,330)
                            measured(label+'-issued',fraction=fraction)
                            h=decode_playback(label,duration*fraction,duration)
                            measured(label+'-confirmed',fraction=fraction,**h)
                        except (ValueError,RuntimeError,AssertionError,subprocess.TimeoutExpired):
                            measured(label+'-unconfirmed',fraction=fraction)
                            if os.environ.get('QA_OBSERVED_FAILED_SEEKS') == 'true':
                                try: observed_seek_diagnostic(label, fraction, duration)
                                except Exception as error:
                                    measured(label+'-input-diagnostic-error',fraction=fraction,errorType=type(error).__name__)
            except (ValueError,RuntimeError,AssertionError,subprocess.TimeoutExpired):
                measured(label+'-opened',moving=False)
                if k in late_open_labels:
                    try:
                        late=decode_playback(label+'-late',None,media['seconds'],max_wait=40)
                        proof=engine.engine_evidence(core,since)
                        measured(label+'-late-open',moving=True,identity=proof,**late)
                    except (ValueError,RuntimeError,AssertionError,subprocess.TimeoutExpired):
                        measured(label+'-late-open',moving=False)
            events=[e for e in app_events() if (e['time'],e['event']) not in prior]
            measured(label+'-diagnostics',total=len(events),events=events[-600:])
            adb('shell','am','force-stop',PKG)
            if range_status != 206:
                record_android_range(label+'-android-network-recheck', media['location'])
