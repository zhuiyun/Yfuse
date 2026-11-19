"""Cloud comparison of repeated public original opens/seeks over origin and loopback HTTP."""
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

if os.environ.get('GITHUB_ACTIONS') != 'true':
    raise SystemExit('Cloud runner required')

import direct
from engine import configure

catalog = json.loads(Path('qa-format-originals/sources.json').read_text())
catalog += [dict(x, mime='video/mp4') for x in json.loads(Path('qa-titles/catalog.json').read_text())]
requested_id = os.environ.get('QA_ORIGINAL_ID', 'sintel-blender-1080p-mkv')
item = next(x for x in catalog if x['id'] == requested_id)
mode = os.environ['QA_ORIGINAL_PATH']
if mode not in ('origin', 'loopback', 'loopback-timeout'):
    raise ValueError('Unknown control route')
os.environ['QA_CAPTURE_FAILURE'] = 'true'
server = None
fault_mode = mode == 'loopback-timeout'
trial = None

if os.environ.get('QA_RANGE_CONTROL') == 'true':
    from range_control import record
    record(item['url'], direct.OUT / 'independent-origin-ranges.json')

if mode in ('loopback', 'loopback-timeout'):
    target = Path(os.environ['RUNNER_TEMP']) / 'public-original.media'
    subprocess.run(['curl', '--fail', '--location', '--retry', '3', '--max-time', '600',
                    '--output', str(target), item['url']], check=True)
    digest = hashlib.sha256()
    with target.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    original_sha = digest.hexdigest()
    expected_sha = os.environ.get('QA_EXPECTED_FILE_SHA256')
    if expected_sha and original_sha != expected_sha:
        raise ValueError('The published bytes differ from the original movie used for comparison')
    direct.measured('original-control-file', bytes=target.stat().st_size, sha256=original_sha)
    etag = '"' + original_sha + '"'
    if fault_mode:
        from body_timeout_control import BodyTimeoutTrial
        if not expected_sha or os.environ.get('QA_TIMEOUT_VERSION') not in ('previous', 'candidate'):
            raise ValueError('The timeout comparison requires a pinned movie and a named build')

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):pass
        def do_GET(self):self.serve(True)
        def do_HEAD(self):self.serve(False)
        def serve(self, body):
            if self.path != '/original.media':
                self.send_error(404);return
            size = target.stat().st_size
            match = re.fullmatch(r'bytes=(\d+)-(\d*)', self.headers.get('Range', ''))
            if fault_mode and self.headers.get('If-Range') not in (None, etag):
                match = None
            start = int(match[1]) if match else 0
            end = min(int(match[2]), size-1) if match and match[2] else size-1
            if start > end:
                self.send_error(416);return
            self.send_response(206 if match else 200)
            self.send_header('Content-Length', str(end-start+1))
            self.send_header('Content-Type', item['mime'])
            self.send_header('Accept-Ranges', 'bytes')
            if fault_mode:
                self.send_header('ETag', etag)
            if match:self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
            self.end_headers()
            with (direct.OUT/'loopback-arrivals.jsonl').open('a') as log:
                log.write(json.dumps({'timeMs':round(direct.time.time()*1000),'start':start,'end':end,'body':body})+'\n')
            if not body:return
            request_trial = trial
            should_pause = bool(request_trial and request_trial.observe_request(start, end, self.headers.get('If-Range')))
            pace_open = fault_mode and start < size * .05
            try:
                with target.open('rb') as source:
                    source.seek(start)
                    while start<=end:
                        chunk = request_trial.prefix_bytes if should_pause else 65536
                        data=source.read(min(chunk,end-start+1))
                        if not data:break
                        self.wfile.write(data);start+=len(data)
                        if should_pause:
                            self.wfile.flush()
                            request_trial.pause_after_prefix(self.connection)
                            should_pause = False
                        if pace_open:
                            # Keep the first real seek outside the bytes already read during startup.
                            direct.time.sleep(len(data) / (2 * 1024 * 1024))
            except (BrokenPipeError,ConnectionResetError):pass

    server = ThreadingHTTPServer(('127.0.0.1', 18389), Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    direct.adb('reverse', 'tcp:18389', 'tcp:18389')
    item = dict(item, testUrl='http://127.0.0.1:18389/original.media')


def cache_state():
    directory = '/data/user/0/com.yfuse/cache/video_cache_v2'
    command = rf'if [ -d {directory} ]; then find {directory} -type f -exec stat -c "%s %n" {{}} \; ; fi'
    listing = direct.adb('shell', command)
    sizes = []
    for row in listing.splitlines():
        count, filename = row.split(' ', 1)
        if re.match(r'^\d+\.\d+\.\d+\.', Path(filename).name):
            sizes.append(int(count))
    return {'ranges': len(sizes), 'mediaBytes': sum(sizes)}


def prepare_cache_pair(index, always_fresh=False):
    if direct.adb('get-serialno').strip() != '127.0.0.1:15555':
        raise RuntimeError('Cache comparisons require the local disposable Android guest')
    fresh = always_fresh or index % 2 == 0
    direct.adb('shell', 'am', 'force-stop', 'com.yfuse')
    if fresh:
        if direct.adb('shell', 'pm', 'clear', 'com.yfuse').strip() != 'Success':
            raise RuntimeError('Could not reset the disposable test installation')
        configure(os.environ['QA_ENGINE'])
    state = cache_state()
    if fresh and state['mediaBytes'] != 0:
        raise RuntimeError('New comparison retained media data')
    return {'attempt': index + 1, 'pair': index + 1 if always_fresh else index // 2 + 1,
            'cleared': fresh, 'before': state}


cache_pairs = []
compare_cache = fault_mode or os.environ.get('QA_CACHE_PAIRS') == 'true'
planned = 2 if fault_mode else 12
timeouts = []
try:
    for index in range(planned):
        sample = dict(item, id=item['id']+'-'+mode+'-'+str(index))
        cache_case = prepare_cache_pair(index, always_fresh=fault_mode) if compare_cache else None
        trial = BodyTimeoutTrial(target.stat().st_size, etag) if fault_mode else None
        try:
            direct.run(sample, before_seek=trial.before_seek if trial else None)
        except Exception as error:
            direct.measured(sample['id']+'-incomplete', error=type(error).__name__)
            direct.capture_failure(sample['id'])
            if not fault_mode:
                break
        finally:
            if cache_case is not None:
                cache_case['after'] = cache_state()
                cache_case['confirmed'] = any(r['case'] == sample['id'] + '-complete' and r.get('ok') for r in direct.ROWS)
                cache_pairs.append(cache_case)
                (direct.OUT / 'cache-open-pairs.json').write_text(json.dumps(cache_pairs, indent=2))
            if trial:
                result = trial.result()
                result.update(attempt=index + 1, sourceSha256=original_sha,
                              build=os.environ['QA_TIMEOUT_VERSION'], pictureConfirmed=cache_case['confirmed'])
                timeouts.append(result)
                (direct.OUT / 'body-timeout-trials.json').write_text(json.dumps(timeouts, indent=2))
            if os.environ.get('QA_DNS_CONTROL') == 'true':
                # Observe after the attempt: a DNS probe before playback would warm the cache
                # and could hide the first-lookup failure this comparison is investigating.
                snapshot = {
                    'attempt': index, 'path': mode,
                    'privateDnsMode': direct.adb('shell', 'settings', 'get', 'global', 'private_dns_mode', check=False),
                    'privateDnsSpecifier': direct.adb('shell', 'settings', 'get', 'global', 'private_dns_specifier', check=False),
                    'resolver': direct.adb('shell', 'dumpsys', 'dnsresolver', check=False)[:65536],
                }
                (direct.OUT / ('resolver-after-' + str(index) + '.json')).write_text(json.dumps(snapshot, indent=2))
        if not fault_mode and not direct.ROWS[-1].get('ok'):
            break
    passed = sum(row['case'].endswith('-complete') and row.get('ok',False) for row in direct.ROWS)
    direct.measured('repeat-summary', sourceId=requested_id, mode=mode, plannedOpens=planned, passedOpens=passed)
    if fault_mode:
        exposed = len(timeouts) == planned and all(t['observedTimeout'] for t in timeouts)
        recovered = all(t['exactSuffixRequests'] == 1 and t['pictureConfirmed'] for t in timeouts)
        required = exposed and (os.environ['QA_TIMEOUT_VERSION'] == 'previous' or recovered)
        raise SystemExit(int(not required))
    raise SystemExit(int(passed != planned))
finally:
    if server:
        server.shutdown();server.server_close()
