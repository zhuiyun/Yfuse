"""Observe two previously selected Emby originals on the authorized server without opening a port."""
import os
import base64
import datetime as dt
import getpass
import hashlib
import inspect
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def classify_refusal(body, headers):
    text = body.decode('utf-8', errors='replace')
    try:
        packet = json.loads(text)
    except ValueError:
        packet = {}
    message = packet.get('message', packet.get('msg','')) if isinstance(packet, dict) else ''
    if not isinstance(message,str):message=''
    # Only fixed categories and explicitly numeric provider codes leave the server.
    clues = {
        'expired': r'expir(?:ed|ation)|requesttimetooskewed|过期|失效时间|链接已失效',
        'timestamp': r'timestamp|time.?out|时间戳',
        'signature': r'signature|sign error|invalid sign|签名|鉴权失败|鉴权不通过',
        'account-quota': r'quota|余额|套餐|流量包|流量不足|流量已|超出流量|流量超|直链流量',
        'request-rate': r'too many requests|rate.?limit|请求过于频繁|限流|请求频率',
        'address-policy': r'ip (?:address|mismatch|denied)|禁止海外|境外|白名单|区域限制|地区限制',
        'source-denied': r'access.?denied|forbidden|无权限|拒绝访问|访问被拒绝',
        'reference-policy': r'referer|防盗链|hotlink',
        'sharing-policy': r'分享已|分享不存在|已取消分享|文件已删除|链接不存在',
    }
    reason = text + ' ' + message + ' ' + ' '.join(headers.get(k, '') for k in ('x-ca-error-code','x-ca-error-message','x-error-code'))
    codes = sorted(set(re.findall(r'["\x27](?:code|errorCode|errno|status)["\x27]\s*:\s*["\x27]?([0-9]{3,8})\b', text, re.I)))
    # Include an exact generic error only if every word belongs to a fixed diagnostic vocabulary.
    words=set('access account arg auth authentication bad balance bandwidth blocked bucket cache cdn check client code current daily date deadline denied download empty end err error exceed exceeded expired expires fail failed file flow forbidden frequency host invalid ip is key limit limited link missing mismatch no not now of out over parameter permission quota rate request requests risk server sign signature source space speed stale steal stop time timed timeout timestamp token traffic unavailable unknown url user valid verify vip wrong the a too many for found in r realc'.split())
    generic=message if message and all(w.lower() in words for w in re.split('[ _:-]+',message.strip())) else None
    download_error = re.fullmatch(r'download err:\s*([0-9]{3,6})',message,re.I)
    if download_error:
        generic='download err: '+download_error[1]
    return {'categories': [k for k, pattern in clues.items() if re.search(pattern, reason, re.I)],
            'providerCodes': codes, 'diagnosticMessage':generic,
            'diagnosticWords':[w for w in re.findall('[a-z]+',message.lower()) if w in words][:16],
            'bytesExamined': len(body)}


def expiry_hints(url, now):
    fields = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)
    result = {}
    for name, vs in fields.items():
        key = name.lower()
        if key not in {'t','e','exp','expires','expiry','deadline','auth_key','authkey','timestamp'}:
            continue
        for v in vs:
            part = v.split('-', 1)[0] if key in ('auth_key','authkey') else v
            if re.fullmatch(r'[0-9]{10}', part) and 1_500_000_000 < int(part) < 2_500_000_000:
                result[key] = int(part) - int(now)
    return result


def remote():
    cfg = json.loads(sys.stdin.readline())
    scope = {}
    exec(cfg.pop('resolver'), scope)
    select = {}
    exec(cfg.pop('selection'), select)
    scope['AUTH'] = scope['AUTH'].replace('yfuse-qa-20261116', 'yfuse-origin-observation-51')
    selected, sources = cfg['sources'], {}
    def emit(kind, **values):
        print(json.dumps(dict(type=kind, utc=dt.datetime.now(dt.timezone.utc).isoformat(), **values)), flush=True)
    def probe(url, ref):
        request = urllib.request.Request(url, headers={'Range':'bytes=0-1023', 'Accept-Encoding':'identity',
                                                      'User-Agent':'Emby for Android Mobile'})
        try:
            with scope['HTTP'].open(request, timeout=16) as response:
                data = response.read(1024)
                target = response.geturl()
                parsed = urllib.parse.urlsplit(target)
                valid = (parsed.scheme == 'https' and parsed.hostname not in ('127.0.0.1','localhost','47.112.219.60')
                         and not parsed.username and not parsed.password and
                         (not scope['TOKEN'] or scope['TOKEN'] not in urllib.parse.unquote(target)))
                matches = (response.status == 206 and response.headers.get('Content-Range') == 'bytes 0-1023/' + str(ref['bytes'])
                           and hashlib.sha256(data).hexdigest()[:16] == ref['start'] and len(data) == 1024)
                return {'http':response.status,'contentHashAndRangeMatch':matches,
                        'timestampHintsSeconds':expiry_hints(target,time.time()) if valid else {}}, target if valid and matches else None
        except urllib.error.HTTPError as e:
            try:
                endpoint=e.geturl()
                external=urllib.parse.urlsplit(endpoint).hostname not in ('127.0.0.1','localhost','47.112.219.60')
                return {'http':e.code,'refusal':classify_refusal(e.read(4096), e.headers),
                        'externalResponse':external,'timestampHintsSeconds':expiry_hints(endpoint,time.time()) if external else {}}, None
            finally:
                e.close()
        except (OSError, ValueError) as e:
            return {'http':0,'transportError':type(e).__name__},None
    stage = 'login'
    try:
        login = scope['call']('/Users/AuthenticateByName', {'Username':cfg.pop('username'), 'Pw':cfg.pop('password')})
        scope['TOKEN'],scope['USER'] = login['AccessToken'],login['User']['Id']
        del login
        stage = 'catalog'
        items = select['select_sources'](scope, set(selected), selected)
        for label, item in items:
            stage = 'source'
            media = scope['call']('/Items/'+item['Id']+'/PlaybackInfo',{'UserId':scope['USER'],'IsPlayback':False,
                    'AutoOpenLiveStream':False,'EnableDirectPlay':True,'EnableDirectStream':True,'EnableTranscoding':False})['MediaSources'][0]
            query = urllib.parse.urlencode({'Static':'true','MediaSourceId':media['Id'],'api_key':scope['TOKEN'],'UserId':scope['USER']})
            url = scope['ROOT']+'/Videos/'+urllib.parse.quote(item['Id'])+'/stream.'+urllib.parse.quote(media.get('Container') or 'mkv')+'?'+query
            result, terminal = probe(url, selected[label])
            emit('initial',label=label,**result)
            if terminal:
                sources[label]=terminal
    finally:
        if scope.get('TOKEN'):
            select['close_emby_session'](scope)
            scope['TOKEN']=None
            emit('logout', confirmed=True)
    # Recheck the same issued URLs at clock boundaries. A refused URL is immediately retired.
    started=time.time()
    hour=(int(started)//3600+1)*3600
    schedule=sorted(set(t for t in (started+120, hour-15,hour+15,hour+90,hour+3585,hour+3615)
                        if started+5<t<=started+7200))
    if cfg['once']:schedule=[]
    for target in schedule:
        if not sources:break
        time.sleep(max(0,target-time.time()))
        for label,url in list(sources.items()):
            outcome,_=probe(url,selected[label])
            emit('same-link',label=label,ageSeconds=round(time.time()-started),**outcome)
            if outcome['http'] != 206 or not outcome['contentHashAndRangeMatch']:
                del sources[label]
    emit('complete', stillReadable=len(sources))


def main():
    here=Path(__file__).resolve().parent
    common=here.parent/'legacy-engine-20261113/integrated-20261116'
    resolver=(common/'source_bridge_entry.py').read_text('utf8').split("\nstage = 'config'")[0]
    project=here.parent.parent
    selection=(project/'qa-private-relay/serve_session.py').read_text('utf8')
    selection='import urllib.parse, http.client\n'+selection[selection.index('def catalog_identity('):selection.index('def run(config):')]
    samples=json.loads((here/'private-originals-50-by-file.json').read_text('utf8'))['media']
    hashes=json.loads((here/'private-source-control-43.json').read_text('utf8'))['rangeSha256Prefixes']
    once=sys.argv[1:]==['--refusal-details']
    assert not sys.argv[1:] or once
    wanted={'m-012'} if once else {'m-012','e-001'}
    refs={x['label']:dict(kind=x['kind'],bytes=x['bytes'],seconds=x['seconds'],container=x['container'],start=hashes[x['label']]['start'])
          for x in samples if x['label'] in wanted}
    assert set(refs)==wanted
    imports='import datetime as dt, hashlib, json, re, sys, time, urllib.request, urllib.error, urllib.parse\n'
    code=imports+'\n'.join(map(inspect.getsource,(classify_refusal,expiry_hints,remote)))+'\nremote()'
    program="exec(__import__('base64').b64decode("+repr(base64.b64encode(code.encode()).decode())+"))"
    if not sys.stdin.isatty():
        raise ValueError('A non-echoing terminal is required')
    receipt=here/('origin-refusal-details-51.jsonl' if once else 'origin-lifetime-51.jsonl')
    if receipt.exists():raise FileExistsError('The named observation already exists')
    cfg=dict(sources=refs,selection=selection,resolver=resolver,once=once,username=getpass.getpass('Emby username (hidden): ').strip(),password=getpass.getpass('Emby-only password (hidden): '))
    proc=subprocess.Popen(['ssh','-T','-o','BatchMode=yes','-o','ConnectTimeout=8','-o','ServerAliveInterval=30',
                           '-o','ServerAliveCountMax=4','--', os.environ['YFUSE_QA_SSH_TARGET'],'python3','-c',shlex.quote(program)],
                          stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,encoding='utf8',text=True)
    proc.stdin.write(json.dumps(cfg)+'\n');proc.stdin.close();cfg.clear()
    with receipt.open('x',encoding='utf8') as f:
        for line in proc.stdout:
            event=json.loads(line)
            assert event.get('type') in ('initial','same-link','logout','complete')
            if 'label' in event:assert event['label'] in wanted
            f.write(json.dumps(event)+'\n');f.flush()
            print(json.dumps(event),flush=True)
    code=proc.wait()
    if code:
        # No SSH/provider traceback is printed; it can contain media URLs.
        raise SystemExit('Origin observation ended with SSH code '+str(code))


if __name__=='__main__':
    main()
