"""Verify original ranges inside the authorized media server. No listener or cloud upload."""
import os
import ast
import base64
import datetime
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
RUN = 44417724795
PIN = 'f0efe80c70a1d9d0ba4e94a15731292f0f3deaf5'
BASE = ROOT/'audit/legacy-engine-20261113/integrated-20261116'
RECEIPT = Path(__file__).with_name('private-server-ranges-43.json')
REMOTE = '''
def verify():
    global AUTH, TOKEN, USER
    cfg = json.loads(sys.stdin.readline())
    AUTH = AUTH.replace('yfuse-qa-20261116', 'yfuse-source-control-44417724795')
    result = {'run':44417724795,'authenticated':False,'signedOut':False,'samples':[]}
    stage = 'login'
    try:
        username, password = cfg.pop('credentials')
        login = call('/Users/AuthenticateByName', {'Username':username, 'Pw':password})
        TOKEN, USER = login['AccessToken'], login['User']['Id']
        del login, username, password
        result['authenticated'] = True
        stage = 'catalog'
        ref = cfg.pop('reference')
        catalogs = {kind:candidates(kind) for kind in ('Movie','Episode')}
        result['catalogCounts'] = {kind:len(rows) for kind,rows in catalogs.items()}
        identities = {kind:[catalog_identity(item) for item in rows] for kind,rows in catalogs.items()}
        diagnostics = {}
        for label,prior in ref.items():
            current = identities[prior['kind']]
            spec = (prior['bytes'],prior['seconds'],prior['container'])
            same_size = [x for x in current if x[0] == prior['bytes']]
            diagnostics[label] = {'exact':current.count(spec),'sameSize':len(same_size),
                'sameSizeAndDuration':sum(x[1]==spec[1] for x in same_size),'sameSizeAndContainer':sum(x[2]==spec[2] for x in same_size),
                'nearestDurationDifferenceSeconds':min((abs(x[1]-spec[1]) for x in same_size),default=None)}
        result['catalogMismatches'] = {label:x for label,x in diagnostics.items() if x['exact'] != 1}
        unique = {label:prior for label,prior in ref.items() if diagnostics[label]['exact'] == 1}
        if cfg.get('replaceOne'):
            label='e-027'
            if set(result['catalogMismatches'])!={label} or diagnostics[label]['sameSize']!=0:
                raise ValueError('Replacement requires exactly one missing original')
            from collections import Counter
            original=ref[label]
            counts=Counter(identities['Episode'])
            reserved={(v['bytes'],v['seconds'],v['container']) for v in ref.values() if v['kind']=='Episode'}
            ranked=[]
            for item in catalogs['Episode']:
                size,seconds,container=catalog_identity(item)
                identity=(size,seconds,container)
                if identity in reserved or counts[identity]!=1 or size<2048:continue
                src=(item.get('MediaSources') or [{}])[0]
                streams=src.get('MediaStreams') or item.get('MediaStreams') or []
                video=next((t for t in streams if t.get('Type')=='Video'),{})
                if int(video.get('Width') or 0)!=3840:continue
                codec=(video.get('Codec') or '').lower()
                profile=(video.get('Profile') or '').lower()
                height=int(video.get('Height') or 0)
                rank=(codec not in ('hevc','h265'),abs(height-1608), '10' not in profile,
                      abs(size-original['bytes']),container!=original['container'],item['Id'])
                ranked.append((rank,item,identity))
            if not ranked:raise ValueError('No unique 4K episode')
            _,replacement,identity=min(ranked,key=lambda candidate:candidate[0])
            result['replacement']={'label':label,'kind':'Episode','bytes':identity[0],'seconds':identity[1],'container':identity[2]}
            ref[label]=dict(result['replacement'])
            items=[(label,replacement)]
        else:
            resolver = dict(globals(), candidates=catalogs.__getitem__)
            items = select_sources(resolver, set(unique), unique) if unique else []
        stage = 'source-ranges'
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            for label, row, error in pool.map(resolve, items):
                entry = {'label':label,'matchingMetadata':False,'rangeCodes':{},'sha256Prefixes':{}}
                if row is not None:
                    expected = ref[label]
                    entry['matchingMetadata'] = (row['type'],row['size'],row['seconds'],row['container']) == (
                        expected['kind'],expected['bytes'],expected['seconds'],expected['container'])
                    if cfg.get('replaceOne'):
                        result['replacement'].update({k:row['technical'][k] for k in ('videoCodec','width','height')})
                    entry['rangeCodes'] = row['rangeCodes']
                    entry['sha256Prefixes'] = row['rangeHashes']
                if error: entry['resolutionFailed'] = True
                result['samples'].append(entry)
    except Exception as exc:
        result['failedStage'] = stage
        result['errorClass'] = type(exc).__name__
    finally:
        if TOKEN:
            try:
                close_emby_session(globals())
                result['signedOut'] = True
            except Exception as exc:
                result['logoutErrorClass'] = type(exc).__name__
            TOKEN = None
    print(json.dumps(result), flush=True)

verify()
'''


def main():
    replace_one = sys.argv[1:] == ['--replace-one']
    assert replace_one or not sys.argv[1:]
    cfg = json.load(sys.stdin)
    if int(cfg['run']) != RUN or cfg['referenceRound'] != 28:
        raise ValueError('Wrong source control run')
    old = json.loads((ROOT/'audit/power-ui-20261116/private-originals-28-by-file.json').read_text('utf8'))
    reference = {row['label']:{key:row[key] for key in ('kind','bytes','seconds','container')}
                 for row in old['media'] if row['label'] in cfg['labels']}
    assert old['run'] == cfg['referenceRun'] and set(reference) == set(cfg['labels']) and len(reference) == 60
    assert sum(v['kind']=='Movie' for v in reference.values()) == 32
    original = (BASE/'source_bridge_entry.py').read_bytes()
    assert hashlib.sha256(original).hexdigest() == '49b1406ee77d34931393b2099e06b6b10822423d0f70f2f03d52c57791b30f24'
    probe = original.decode('utf8').split("\nstage = 'config'", 1)[0]
    checked = subprocess.check_output(['git','-c','safe.directory=' + ROOT.as_posix(),'show',
                                       PIN+':qa-private-relay/serve_session.py'],cwd=ROOT).decode('utf8')
    names = {'catalog_identity','select_sources','close_emby_session'}
    helpers = [ast.get_source_segment(checked, n) for n in ast.parse(checked).body
               if isinstance(n,ast.FunctionDef) and n.name in names]
    assert len(helpers) == len(names)
    program = 'import http.client\n' + probe + '\n' + '\n\n'.join(helpers) + '\n' + REMOTE
    remote_command = 'exec(__import__("base64").b64decode('+repr(base64.b64encode(program.encode()).decode())+'))'
    payload = json.dumps({'credentials':cfg.pop('credentials'),'reference':reference,'replaceOne':replace_one})+'\n'
    proc = subprocess.run(['ssh','-T','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes',
                           '-o','ConnectTimeout=8','-o','ServerAliveInterval=30','-o','ServerAliveCountMax=4',
                           '--', os.environ['YFUSE_QA_SSH_TARGET'],'python3','-c',shlex.quote(remote_command)],
                          input=payload,text=True,encoding='utf8',capture_output=True)
    del payload
    if proc.returncode or not proc.stdout.strip():
        raise SystemExit('Source-control connection ended without a receipt. SSH code '+str(proc.returncode))
    data = json.loads(proc.stdout)
    permitted = {'authenticated','signedOut','run','samples','failedStage','errorClass','logoutErrorClass','catalogCounts','catalogMismatches','replacement'}
    assert not (data.keys()-permitted) and data['run']==RUN
    for key in ('errorClass','logoutErrorClass','failedStage'):
        assert key not in data or re.fullmatch(r'[A-Za-z-]{1,50}',data[key])
    if 'replacement' in data:
        v=data['replacement']
        assert replace_one and v['label']=='e-027' and v['kind']=='Episode'
        assert set(v)<={'label','kind','bytes','seconds','container','videoCodec','width','height'}
        assert type(v['bytes']) is int and v['bytes']>2048 and v['seconds']>=0 and re.fullmatch(r'[a-zA-Z0-9_,-]{1,25}',v['container'])
        if 'width' in v:assert v['width']==3840 and type(v['height']) is int and re.fullmatch('[a-z0-9]{1,12}',v['videoCodec'])
    counts = data.get('catalogCounts', {})
    assert set(counts) <= {'Movie','Episode'} and all(type(c) is int and c>=0 for c in counts.values())
    mismatches = data.get('catalogMismatches', {})
    for label,check in mismatches.items():
        assert label in reference and set(check)=={'exact','sameSize','sameSizeAndDuration','sameSizeAndContainer','nearestDurationDifferenceSeconds'}
        assert all(v is None or (type(v) is int and v>=0) for v in check.values())
    samples = data['samples']
    assert len({r['label'] for r in samples})==len(samples)
    for row in samples:
        assert set(row) <= {'label','matchingMetadata','rangeCodes','sha256Prefixes','resolutionFailed'}
        assert row['label'] in reference and type(row['matchingMetadata']) is bool
        assert set(row['rangeCodes']) <= {'start','middle','end'} and all(type(c) is int for c in row['rangeCodes'].values())
        assert set(row['sha256Prefixes']) <= {'start','middle','end'}
        assert all(re.fullmatch(r'[0-9a-f]{16}',v) for v in row['sha256Prefixes'].values())
    good = [r for r in samples if r['matchingMetadata'] and set(r['sha256Prefixes'])=={'start','middle','end'}]
    data.update(timeUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),verifiedFiles=len(good),
                verifiedRanges=sum(len(r['sha256Prefixes']) for r in samples),bytesPerRange=1024,
                movieCount=sum(reference[r['label']]['kind']=='Movie' for r in good),
                episodeCount=sum(reference[r['label']]['kind']=='Episode' for r in good),
                expectedFiles=(1 if replace_one else len(reference)),transport='Emby loopback login; read-only ranged original files on the server')
    receipt = RECEIPT.with_name('private-server-replacement-43.json') if replace_one else RECEIPT
    receipt.write_text(json.dumps(data,indent=2)+'\n','utf8')
    print(json.dumps({k:v for k,v in data.items() if k not in ('samples','transport','timeUtc','catalogMismatches')}))
    return int(not (data['authenticated'] and data['signedOut'] and len(good)==data['expectedFiles']))


if __name__ == '__main__':
    sys.exit(main())
