"""Cloud comparison of repeated public original opens/seeks over origin and loopback HTTP."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

if os.environ.get('GITHUB_ACTIONS') != 'true':
    raise SystemExit('Cloud runner required')

import direct

catalog = json.loads(Path('qa-format-originals/sources.json').read_text())
catalog += [dict(x, mime='video/mp4') for x in json.loads(Path('qa-titles/catalog.json').read_text())]
requested_id = os.environ.get('QA_ORIGINAL_ID', 'sintel-blender-1080p-mkv')
item = next(x for x in catalog if x['id'] == requested_id)
mode = os.environ['QA_ORIGINAL_PATH']
if mode not in ('origin', 'loopback'):
    raise ValueError('Unknown control route')
os.environ['QA_CAPTURE_FAILURE'] = 'true'
server = None

if os.environ.get('QA_RANGE_CONTROL') == 'true':
    from range_control import record
    record(item['url'], direct.OUT / 'independent-origin-ranges.json')

if mode == 'loopback':
    target = Path(os.environ['RUNNER_TEMP']) / 'public-original.media'
    subprocess.run(['curl', '--fail', '--location', '--retry', '3', '--max-time', '600',
                    '--output', str(target), item['url']], check=True)
    digest = hashlib.sha256()
    with target.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    direct.measured('original-control-file', bytes=target.stat().st_size, sha256=digest.hexdigest())

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):pass
        def do_GET(self):self.serve(True)
        def do_HEAD(self):self.serve(False)
        def serve(self, body):
            if self.path != '/original.media':
                self.send_error(404);return
            size = target.stat().st_size
            import re
            match = re.fullmatch(r'bytes=(\d+)-(\d*)', self.headers.get('Range', ''))
            start = int(match[1]) if match else 0
            end = min(int(match[2]), size-1) if match and match[2] else size-1
            if start > end:
                self.send_error(416);return
            self.send_response(206 if match else 200)
            self.send_header('Content-Length', str(end-start+1))
            self.send_header('Content-Type', item['mime'])
            self.send_header('Accept-Ranges', 'bytes')
            if match:self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
            self.end_headers()
            with (direct.OUT/'loopback-arrivals.jsonl').open('a') as log:
                log.write(json.dumps({'timeMs':round(direct.time.time()*1000),'start':start,'end':end,'body':body})+'\n')
            if not body:return
            try:
                with target.open('rb') as source:
                    source.seek(start)
                    while start<=end:
                        data=source.read(min(65536,end-start+1))
                        if not data:break
                        self.wfile.write(data);start+=len(data)
            except (BrokenPipeError,ConnectionResetError):pass

    server = ThreadingHTTPServer(('127.0.0.1', 18389), Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    direct.adb('reverse', 'tcp:18389', 'tcp:18389')
    item = dict(item, testUrl='http://127.0.0.1:18389/original.media')

try:
    for index in range(12):
        sample = dict(item, id=item['id']+'-'+mode+'-'+str(index))
        try:
            direct.run(sample)
        except Exception as error:
            direct.measured(sample['id']+'-incomplete', error=type(error).__name__)
            direct.capture_failure(sample['id'])
            break
        if not direct.ROWS[-1].get('ok'):
            break
    passed = sum(row['case'].endswith('-complete') and row.get('ok',False) for row in direct.ROWS)
    direct.measured('repeat-summary', sourceId=requested_id, mode=mode, plannedOpens=12, passedOpens=passed)
    raise SystemExit(int(passed != 12))
finally:
    if server:
        server.shutdown();server.server_close()
