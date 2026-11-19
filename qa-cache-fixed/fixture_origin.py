"""Serve the scrub fixture and record all media requests, including rejected ranges."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import re
import threading
import time


class FixtureOrigin(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, media, limits, paced=False, port=18382):
        super().__init__(('127.0.0.1', port), MediaHandler)
        self.media = media
        self.cutoff = limits['before']
        self.pace_after = limits['paceAfter'] if paced else None
        self._start = time.monotonic()
        self._lock = threading.Lock()
        self._stats = {'range': 0, 'image': 0, 'bytes': 0, 'GET': 0, 'HEAD': 0}
        self._events = []
        self._received = []

    def now(self):
        return round((time.monotonic() - self._start) * 1000)

    def request(self, handler, start, end, range_type, status):
        with self._lock:
            request_id = self._stats['GET'] + self._stats['HEAD'] + 1
            self._stats[handler.command] += 1
            self._stats['image'] += int(handler.path != '/film.mp4')
            self._stats['range'] += int(status == 206)
            validator = handler.headers.get('If-Range')
            self._events.append({'event': 'request', 'id': request_id, 'atMs': self.now(), 'method': handler.command,
                                 'mediaPath': handler.path == '/film.mp4', 'rangeType': range_type,
                                 'requestedStart': start, 'responseEnd': end, 'status': status,
                                 'ifRange': 'fixture' if validator == '"qa-rail-preview-v1"' else 'other' if validator else 'absent'})
            return request_id

    def event(self, name, request_id, offset):
        with self._lock:
            self._events.append({'event': name, 'id': request_id, 'atMs': self.now(), 'offset': offset})

    def delivered(self, start, count):
        with self._lock:
            self._received.append((start, start + count - 1))
            self._stats['bytes'] += count

    def counters(self):
        with self._lock:
            return self._stats.copy()

    def events(self):
        with self._lock:
            return list(self._events)

    def spans(self):
        with self._lock:
            ranges = sorted(self._received)
        merged = []
        for start, end in ranges:
            if not merged or start > merged[-1][1] + 1:
                merged.append([start, end])
            else:
                merged[-1][1] = max(merged[-1][1], end)
        return merged


class MediaHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_HEAD(self):
        self.serve(False)

    def do_GET(self):
        self.serve(True)

    def serve(self, body):
        origin = self.server
        size = origin.media.stat().st_size
        raw_range = self.headers.get('Range', '')
        match = re.fullmatch(r'bytes=(\d+)-(\d*)', raw_range)
        start = int(match[1]) if match else 0
        end = min(int(match[2]), size - 1) if match and match[2] else size - 1
        request_type = 'bytes' if match else 'unsupported' if raw_range else 'absent'
        if self.path != '/film.mp4':
            status = 404
        elif start > end:
            status = 416
        elif start >= origin.cutoff:
            status = 503
        else:
            status = 206 if match else 200
        request_id = origin.request(self, start, end, request_type, status)
        if status >= 400:
            self.send_error(status)
            return
        self.send_response(status)
        self.send_header('Content-Length', str(end - start + 1))
        self.send_header('Content-Type', 'video/mp4')
        self.send_header('ETag', '"qa-rail-preview-v1"')
        self.send_header('Accept-Ranges', 'bytes')
        if match:
            self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        self.end_headers()
        if not body:
            return
        stalled = False
        with origin.media.open('rb') as stream:
            first_offset = start
            stream.seek(start)
            try:
                while start <= end:
                    if start >= origin.cutoff:
                        origin.event('body-stalled', request_id, start)
                        stalled = True
                        break
                    chunk_size = 16384
                    if origin.pace_after is not None and start >= origin.pace_after:
                        time.sleep(.2)
                        chunk_size = 2048
                    chunk = stream.read(min(chunk_size, end - start + 1, origin.cutoff - start))
                    self.wfile.write(chunk)
                    origin.delivered(start, len(chunk))
                    if start == first_offset:
                        origin.event('first-write', request_id, start)
                    start += len(chunk)
            except OSError:
                origin.event('write-failed', request_id, start)
        if stalled:
            time.sleep(25)
