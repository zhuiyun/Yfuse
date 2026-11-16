"""Deterministic local HTTP Range source and request ledger for cloud power comparisons."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import threading
import time


class MediaServer:
    def __init__(self, path, output, port=18389):
        self.path = Path(path)
        self.output = Path(output)
        self.lock = threading.Lock()
        self.requests = self.bytes = 0
        self.failure = False
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.serve(True)

            def do_HEAD(self):
                self.serve(False)

            def serve(self, body):
                began = time.monotonic()
                size = owner.path.stat().st_size
                match = re.fullmatch(r'bytes=(\d+)-(\d*)', self.headers.get('Range', ''))
                lo = int(match[1]) if match else 0
                hi = min(int(match[2]), size - 1) if match and match[2] else size - 1
                status = 503 if owner.failure else 416 if lo > hi else 206 if match else 200
                sent = 0
                with owner.lock:
                    owner.requests += 1
                try:
                    if status >= 400:
                        self.send_error(status)
                        return
                    self.send_response(status)
                    self.send_header('Content-Type', 'video/mp4')
                    self.send_header('Accept-Ranges', 'bytes')
                    self.send_header('Content-Length', str(hi - lo + 1))
                    if match:
                        self.send_header('Content-Range', f'bytes {lo}-{hi}/{size}')
                    self.end_headers()
                    if body:
                        with owner.path.open('rb') as source:
                            source.seek(lo)
                            while sent < hi - lo + 1:
                                if owner.failure:
                                    break
                                chunk = source.read(min(65536, hi - lo + 1 - sent))
                                if not chunk:
                                    break
                                self.wfile.write(chunk)
                                sent += len(chunk)
                                with owner.lock:
                                    owner.bytes += len(chunk)
                except (BrokenPipeError, ConnectionResetError, TimeoutError):
                    pass
                finally:
                    row = {'time': time.time(), 'method': self.command, 'status': status,
                           'rangeStart': lo, 'bytes': sent, 'seconds': round(time.monotonic() - began, 3)}
                    with owner.lock, owner.output.open('a', encoding='utf-8') as stream:
                        stream.write(json.dumps(row) + '\n')

        self.server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def counters(self):
        with self.lock:
            return {'requests': self.requests, 'bytes': self.bytes}

    def close(self):
        self.server.shutdown()
        self.server.server_close()
