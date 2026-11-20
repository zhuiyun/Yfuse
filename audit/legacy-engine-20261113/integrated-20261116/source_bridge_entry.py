"""Read-only source probe. Run on the authorized server; emit encrypted URLs only."""
import base64
import concurrent.futures
import hashlib
import json
import os
import random
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = 'http://127.0.0.1:19001/emby'
AUTH = 'MediaBrowser Client="Yfuse QA", Device="Private source check", DeviceId="yfuse-qa-20261116", Version="1.9.6"'
TRACE = threading.local()
class EntryTrace(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if req.data is not None or any(name.lower() in ('x-emby-token', 'x-emby-authorization', 'authorization')
                                       for name, _ in req.header_items()):
            return None
        parsed = urllib.parse.urlsplit(newurl)
        hostname = parsed.hostname or ""
        local = (parsed.scheme, hostname, parsed.port) == ("http", "127.0.0.1", 19001)
        original = urllib.parse.urlsplit(req.full_url)
        if local and (original.scheme, original.hostname, original.port) != ("http", "127.0.0.1", 19001):
            return None
        if not local and (parsed.scheme != 'https' or not hostname or
                          hostname in ("127.0.0.1", "localhost", "47.112.219.60") or
                          parsed.username is not None or parsed.password is not None or
                          (TOKEN and TOKEN in urllib.parse.unquote(newurl))):
            return None
        if not local and not getattr(TRACE, "entry", None):
            TRACE.entry = newurl
        return super().redirect_request(req, fp, code, msg, headers, newurl)
class NoApiRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None
API_HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoApiRedirect())
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), EntryTrace())
TOKEN = None


def call(path, body=None, query=None):
    url = ROOT + path + ('?' + urllib.parse.urlencode(query) if query else '')
    headers = {'X-Emby-Authorization': AUTH, 'Content-Type': 'application/json'}
    if TOKEN:
        headers['X-Emby-Token'] = TOKEN
    request = urllib.request.Request(url, headers=headers, data=None if body is None else json.dumps(body).encode())
    with API_HTTP.open(request, timeout=35) as response:
        return json.load(response)


def candidates(kind):
    result = []
    offset = 0
    while True:
        page = call('/Users/' + USER + '/Items', query={
            'Recursive': 'true', 'IncludeItemTypes': kind, 'Fields': 'MediaSources,MediaStreams',
            'StartIndex': offset, 'Limit': 500, 'EnableImages': 'false', 'EnableUserData': 'false',
        })
        rows = page.get('Items', [])
        result.extend(rows)
        offset += len(rows)
        if not rows or offset >= page.get('TotalRecordCount', offset):
            return result


def choose(rows, count):
    groups = {}
    for item in rows:
        source = (item.get('MediaSources') or [{}])[0]
        if source.get('Size', 0) < 2048:
            continue
        video = next((s for s in source.get('MediaStreams', item.get('MediaStreams', [])) if s.get('Type') == 'Video'), {})
        key = (source.get('Container'), video.get('Codec'), video.get('Width'))
        groups.setdefault(key, []).append(item)
    selected = {}
    for key in sorted(groups, key=str):
        group = sorted(groups[key], key=lambda i: (i.get('MediaSources') or [{}])[0].get('Size', 0))
        for item in (group[0], group[-1]):
            selected[item['Id']] = item
    remainder = [r for r in rows if r['Id'] not in selected]
    random.Random(20261116).shuffle(remainder)
    for item in remainder:
        if len(selected) >= count:
            break
        if (item.get('MediaSources') or [{}])[0].get('Size', 0) >= 2048:
            selected[item['Id']] = item
    # Every format/size extreme stays selected; count is a floor, not a library cap.
    return list(selected.values())


def resolve(pair):
    label, item = pair
    TRACE.entry = None
    try:
        plan = call('/Items/' + item['Id'] + '/PlaybackInfo', {
            'UserId': USER, 'IsPlayback': False, 'AutoOpenLiveStream': False,
            'EnableDirectPlay': True, 'EnableDirectStream': True, 'EnableTranscoding': False,
        })
        source = plan['MediaSources'][0]
        container = source.get('Container') or 'mkv'
        streams = source.get('MediaStreams') or (item.get('MediaSources') or [{}])[0].get('MediaStreams') or item.get('MediaStreams', [])
        video = next((v for v in streams if v.get('Type') == 'Video'), {})
        audio = [v for v in streams if v.get('Type') == 'Audio']
        video_codecs = {'h264','hevc','vp9','vp8','mpeg4','mpeg2video','av1','prores','vc1','wmv3','rawvideo'}
        audio_codecs = {'aac','ac3','eac3','dts','flac','opus','vorbis','truehd','mp3','pcm_s16le','pcm_s24le','pcm_f32le'}
        technical = {'videoCodec': video.get('Codec') if video.get('Codec') in video_codecs else 'other',
                     'width': int(video.get('Width') or 0), 'height': int(video.get('Height') or 0),
                     'bitrate': int(video.get('BitRate') or source.get('Bitrate') or 0),
                     'audioCodecs': [a.get('Codec') if a.get('Codec') in audio_codecs else 'other' for a in audio]}
        size = int(source.get('Size') or (item.get('MediaSources') or [{}])[0].get('Size') or 0)
        ticks = source.get('RunTimeTicks') or item.get('RunTimeTicks') or 0
        query = {'Static': 'true', 'MediaSourceId': source['Id'], 'api_key': TOKEN, 'UserId': USER}
        url = ROOT + '/Videos/' + urllib.parse.quote(item['Id']) + '/stream.' + urllib.parse.quote(container) + '?' + urllib.parse.urlencode(query)
        direct = None
        codes, signatures = {}, {}
        for name, offset in [('start', 0), ('middle', size // 2), ('end', max(0, size - 1024))]:
            request = urllib.request.Request(direct or url, headers={
                'Range': f'bytes={offset}-{offset + 1023}', 'User-Agent': 'Emby for Android Mobile', 'Accept-Encoding': 'identity',
            })
            try:
                with HTTP.open(request, timeout=18) as response:
                    data = response.read(1024)
                    candidate = response.geturl()
                    if urllib.parse.urlsplit(candidate).hostname in ('127.0.0.1', 'localhost', '47.112.219.60'):
                        raise ValueError('not a temporary external URL')
                    if TOKEN in candidate:
                        raise ValueError('token-bearing URL rejected')
                    direct = candidate
                    codes[name] = response.status
                    bounds = re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)', response.headers.get('Content-Range', ''))
                    actual = tuple(map(int, bounds.groups())) if bounds else None
                    if response.status == 206 and len(data) == 1024 and actual == (offset, offset + 1023, size):
                        signatures[name] = hashlib.sha256(data).hexdigest()[:16]
            except urllib.error.HTTPError as error:
                codes[name] = error.code
            except (OSError, ValueError):
                codes[name] = 0
        if not direct:
            return label, None, 'no-source-bytes'
        return label, {'location': direct, 'providerEntry': getattr(TRACE, 'entry', None), 'type': item['Type'], 'container': container,
                       'size': size, 'seconds': int(ticks / 10000000),
                       'rangeCodes': codes, 'rangeHashes': signatures, 'technical': technical,
                       'expiryHintsSeconds': [int(v)-int(time.time()) for k,values in urllib.parse.parse_qs(urllib.parse.urlsplit(direct).query).items()
                                              for v in values if k.lower() in {'e','expires','expiry','exp','t','deadline'} and v.isdigit() and 1000000000<int(v)<5000000000]}, None
    except Exception as error:
        return label, None, type(error).__name__


stage = 'config'
try:
    config = json.loads(sys.stdin.readline())
    stage = 'login'
    login = call('/Users/AuthenticateByName', {'Username': config['credentials'][0], 'Pw': config['credentials'][1]})
    TOKEN = login['AccessToken']
    USER = login['User']['Id']
    inventory = {}
    for kind in ('Movie', 'Episode'):
        stage = 'inventory-' + kind
        inventory[kind] = candidates(kind)
    selected = [(('m' if kind == 'Movie' else 'e') + '-' + str(i).zfill(3), item)
                for kind, rows in inventory.items() for i, item in enumerate(choose(rows, 20))]
    if config.get('labels'):
        wanted = set(config['labels'])
        if not wanted.issubset({label for label,_ in selected}):
            raise ValueError('requested diagnostic label missing')
        selected = [pair for pair in selected if pair[0] in wanted]
    stage = 'resolve'
    media, failures = {}, Counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for label, row, error in pool.map(resolve, selected):
            if row:
                if config.get('useProviderEntry'):
                    entry = row.pop('providerEntry', None)
                    if not entry:
                        failures['no-provider-entry'] += 1
                        continue
                    row['location'] = entry
                media[label] = row
            else: failures[error] += 1
    envelopes, shard_summaries = {}, {}
    for shard, pub in config['keys'].items():
        shard_rows = {label:media[label] for index,(label,_) in enumerate(selected) if index % len(config['keys']) == int(shard) and label in media}
        public = serialization.load_pem_public_key(pub.encode())
        key, nonce = os.urandom(32), os.urandom(12)
        ciphertext = AESGCM(key).encrypt(nonce, json.dumps(shard_rows).encode(), (config['run']+'-'+shard).encode())
        wrapped = public.encrypt(key, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=None))
        envelopes[shard] = {k:base64.b64encode(v).decode() for k,v in [('key',wrapped),('nonce',nonce),('ciphertext',ciphertext)]}
        shard_summaries[shard] = {'count':len(shard_rows),'types':dict(Counter(m['type'] for m in shard_rows.values()))}
    summary = {'inventory': {k:len(v) for k,v in inventory.items()},'selected':len(selected),'encrypted':len(media),
               'sourceHTTP':dict(Counter(str(code) for m in media.values() for code in m['rangeCodes'].values())),
               'failedSourceLookups':dict(failures),'shards':shard_summaries,
               'mediaFacts':{label:{k:v for k,v in row.items() if k in ('type','container','size','seconds','technical','expiryHintsSeconds')} for label,row in media.items()}}
    print(json.dumps({'envelopes':envelopes,'summary':summary}))
except Exception as error:
    print(json.dumps({'errorType':type(error).__name__,'stage':stage,'http':getattr(error,'code',None)}))
finally:
    if TOKEN:
        try:call('/Sessions/Logout',{})
        except Exception:pass
