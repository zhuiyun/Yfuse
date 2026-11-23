"""Bounded original-byte checks after full-app playback; no watch-state reports."""
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlsplit, urljoin, urlencode
from urllib.request import Request, HTTPRedirectHandler, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def inspect(account, samples):
    base = account['baseUrl'].rstrip('/')
    origin = urlsplit(base)
    token = account['accessToken']
    uid = account['userId']
    identity = {'X-Emby-Token': token, 'Content-Type': 'application/json'}
    result = []

    def kind(url):
        p = urlsplit(url)
        if p.username or p.password:
            return None
        if (p.scheme, p.netloc) == (origin.scheme, origin.netloc):
            return 'emby'
        if p.scheme == 'https' and p.port in (None, 443) and (
            p.hostname == 'vip-download-cdn.123295.com' or
            (p.hostname or '').endswith('.pd1.cjjd19.com')
        ):
            return 'cdn'
        return None

    def send(url, headers, body=None):
        request = Request(url, headers=headers, data=json.dumps(body).encode() if body is not None else None)
        try:
            return build_opener(NoRedirect()).open(request, timeout=15)
        except HTTPError as e:
            return e

    def read(url, start=0):
        hops = []
        for _ in range(5):
            route = kind(url)
            if route is None or (route != 'emby' and token in url):
                return hops + [{'outOfScope': True}]
            headers = {'User-Agent': 'Yfuse/1.9.6', 'Accept-Encoding': 'identity', 'Range': f'bytes={start}-{start+4095}'}
            if route == 'emby':
                headers.update(identity)
            with send(url, headers) as r:
                record = {'hop': route, 'http': r.status, 'contentType': r.headers.get_content_type()}
                hops.append(record)
                if r.status in (301, 302, 303, 307, 308):
                    url = urljoin(url, r.headers['Location'])
                    continue
                data = r.read(4096)
                record['receivedBytes'] = len(data)
                record['requestedOffset'] = start
                record['contentRange'] = r.headers.get('Content-Range')
                record['byteReadVerified'] = r.status == 206 and len(data) == 4096 and (r.headers.get('Content-Range') or '').startswith(f'bytes {start}-{start+4095}/')
                if r.status >= 400:
                    try:
                        error = json.loads(data)
                        record['providerCode'] = error.get('code') if isinstance(error.get('code'), int) else None
                        message = str(error.get('message', ''))
                        if message.startswith('download err: ') and message[14:].isdigit():
                            record['providerError'] = message[14:]
                        record['missingUserId'] = '缺少 Emby User ID' in str(error.get('error', ''))
                    except (ValueError, AttributeError):
                        pass
                return hops
        return hops

    for sample in samples:
        record = {'sample': sample['label']}
        result.append(record)
        try:
            body = {'Id': sample['id'], 'UserId': uid, 'IsPlayback': True, 'AutoOpenLiveStream': False, 'EnableDirectPlay': True, 'EnableDirectStream': True, 'EnableTranscoding': False}
            with send(base + '/Items/' + sample['id'] + '/PlaybackInfo?' + urlencode({'UserId': uid}), identity, body) as r:
                record['infoHTTP'] = r.status
                sources = json.load(r).get('MediaSources', []) if r.status == 200 else []
            record['sourceCount'] = len(sources)
            if len(sources) != 1:
                continue
            source = sources[0]
            query = urlencode({'Static': 'true', 'UserId': uid, 'api_key': token, 'MediaSourceId': source['Id']})
            record['serverOriginal'] = read(base + '/Videos/' + sample['id'] + '/stream?' + query)
            direct = source.get('DirectStreamUrl')
            if direct:
                direct = urljoin(base + '/', direct)
                record['negotiatedOriginal'] = read(direct)
                if record['negotiatedOriginal'][-1].get('byteReadVerified') and source.get('Size', 0) > 8192:
                    record['middle'] = read(direct, source['Size'] // 2)
        except Exception as e:
            record['errorType'] = type(e).__name__
        finally:
            Path('private-results/transport-audit.json').write_text(json.dumps(result, indent=2))
            print('ORIGINAL_TRANSPORT', json.dumps(record), flush=True)
    return result
