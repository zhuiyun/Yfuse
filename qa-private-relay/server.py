"""Bounded, read-only range relay; only an explicit in-memory source allowlist is served."""
import hmac
from collections import deque
import http.server
import re
import socketserver
import threading
import time
import urllib.request
from urllib.parse import urlsplit


def requested_range(value, size):
    if value is None:
        return 0, size - 1, False
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", value)
    if not match or not any(match.groups()):
        raise ValueError("Invalid single byte range")
    start, end = match.groups()
    if not start:
        length = int(end)
        if length <= 0:
            raise ValueError("Invalid suffix")
        return max(0, size - length), size - 1, True
    start = int(start)
    end = min(int(end), size - 1) if end else size - 1
    if start >= size or start > end:
        raise ValueError("Unsatisfiable range")
    return start, end, True


class BoundedHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True

    def __init__(self, address, handler, tls_context=None, allowed_clients=None):
        self.connection_slots = threading.BoundedSemaphore(16)
        self.tls_context = tls_context
        self.allowed_clients = None if allowed_clients is None else frozenset(allowed_clients)
        super().__init__(address, handler)

    def verify_request(self, request, address):
        return self.allowed_clients is None or address[0] in self.allowed_clients

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(12)
        if self.tls_context:
            connection = self.tls_context.wrap_socket(
                connection, server_side=True, do_handshake_on_connect=False,
            )
        return connection, address

    def process_request(self, request, address):
        if not self.connection_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.connection_slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.connection_slots.release()

    def handle_error(self, request, address):
        pass


class RelayState:
    def __init__(self, sources, token, lifetime, byte_budget, opener=None):
        if not sources or len(token) < 40 or not 0 < lifetime <= 4 * 3600:
            raise ValueError("Invalid bounded session")
        for label, media in sources.items():
            parsed = urlsplit(media["location"])
            if not re.fullmatch(r"[me]-[0-9]{3,}", label) or media["size"] <= 0:
                raise ValueError("Invalid media allowlist")
            if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
                raise ValueError("Only pre-resolved HTTPS media targets are accepted")
        if not 0 < byte_budget <= 32 * 1024 ** 3:
            raise ValueError("Invalid transfer budget")
        self.sources = sources
        self.token = token
        self.deadline = time.monotonic() + lifetime
        self.remaining = byte_budget
        self.bytes_sent = 0
        self.request_count = 0
        self.request_history = deque(maxlen=4096)
        self.lock = threading.Lock()
        self.slots = threading.BoundedSemaphore(8)
        self.stopped = threading.Event()
        self.opener = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def alive(self):
        return not self.stopped.is_set() and time.monotonic() < self.deadline

    def reserve(self, amount):
        with self.lock:
            if self.remaining < amount:
                return False
            self.remaining -= amount
            return True

    def sent(self, amount):
        with self.lock:
            self.bytes_sent += amount

    def record_request(self, record):
        with self.lock:
            self.request_count += 1
            self.request_history.append(record)

    def request_summary(self):
        with self.lock:
            return {"total": self.request_count, "retained": list(self.request_history)}


def handler_for(state):
    class Handler(http.server.BaseHTTPRequestHandler):
        # Neither the capability, source address nor upstream error body is logged.
        def log_message(self, *args):
            pass

        def fail(self, status, size=None):
            self.response_status = status
            self.send_response(status)
            self.send_header("Content-Length", "0")
            self.send_header("Cache-Control", "no-store")
            if size is not None:
                self.send_header("Content-Range", "bytes */" + str(size))
            self.end_headers()

        def authenticated_path(self):
            parts = self.path.split("/")
            if (len(parts) != 3 or not re.fullmatch(r"[A-Za-z0-9_-]{40,100}", parts[1]) or
                    not hmac.compare_digest(parts[1], state.token)):
                self.fail(404)
                return None
            return parts[2]

        def do_POST(self):
            target = self.authenticated_path()
            if target is None:
                return
            if target != "stop":
                self.fail(405)
                return
            state.stopped.set()
            self.fail(204)

        def do_HEAD(self):
            self.serve(False)

        def do_GET(self):
            self.serve(True)

        def serve(self, body):
            label = self.authenticated_path()
            if label is None:
                return
            if label not in state.sources:
                self.fail(404)
                return
            if not state.alive():
                self.fail(410)
                return
            media = state.sources[label]
            size = media["size"]
            try:
                lo, hi, partial = requested_range(self.headers.get("Range"), size)
            except ValueError:
                self.fail(416, size)
                return
            if not body:
                self.headers_for(lo, hi, partial, size)
                return
            began = time.monotonic()
            metric = {"label": label, "start": lo, "end": hi, "bytes": 0,
                      "startedEpochMs": round(time.time() * 1000)}
            if not state.slots.acquire(timeout=8):
                state.record_request(dict(metric, status=503, errorType="SlotTimeout",
                                          elapsedMs=round(1000 * (time.monotonic() - began))))
                self.fail(503)
                return
            started = False
            self.response_status = 0
            try:
                if not state.alive():
                    self.fail(410)
                    return
                with state.lock:
                    exhausted = state.remaining <= 0
                if exhausted:
                    self.fail(429)
                    return
                request = urllib.request.Request(media["location"], headers={
                    "Range": "bytes=%d-%d" % (lo, hi),
                    "User-Agent": "Emby for Android Mobile", "Accept-Encoding": "identity",
                })
                with state.opener.open(request, timeout=12) as response:
                    metric["upstreamStatus"] = response.status
                    expected = "bytes %d-%d/%d" % (lo, hi, size)
                    if response.status != 206 or response.headers.get("Content-Range") != expected:
                        self.fail(502)
                        return
                    self.headers_for(lo, hi, partial, size)
                    started = True
                    left = hi - lo + 1
                    while left and state.alive():
                        amount = min(65536, left)
                        if not state.reserve(amount):
                            break
                        data = response.read(amount)
                        if not data or not state.alive():
                            break
                        if "firstByteMs" not in metric:
                            metric["firstByteMs"] = round((time.monotonic() - began) * 1000)
                        self.wfile.write(data)
                        state.sent(len(data))
                        metric["bytes"] += len(data)
                        left -= len(data)
            except Exception as error:
                metric["errorType"] = type(error).__name__
                if isinstance(getattr(error, "code", None), int):
                    metric["upstreamStatus"] = error.code
                if not started:
                    self.fail(502)
            finally:
                self.close_connection = True
                metric.update(status=self.response_status, elapsedMs=round((time.monotonic() - began) * 1000))
                state.record_request(metric)
                state.slots.release()

        def headers_for(self, lo, hi, partial, size):
            self.response_status = 206 if partial else 200
            self.send_response(206 if partial else 200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(hi - lo + 1))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Cache-Control", "no-store")
            if partial:
                self.send_header("Content-Range", "bytes %d-%d/%d" % (lo, hi, size))
            self.end_headers()

    return Handler
