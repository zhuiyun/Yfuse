"""One delayed response from the cloud emulator's loopback copy of the public movie."""
import select
import socket
import threading
import time


class BodyTimeoutTrial:
    prefix_bytes = 64 * 1024
    delay_seconds = 24

    def __init__(self, size, etag):
        self.size = size
        self.etag = etag
        self.lock = threading.Lock()
        self.finished = threading.Event()
        self.armed = False
        self.window = None
        self.prefix_sent_at = None
        self.peer_close_after = None
        self.conditional_requests = []

    def before_seek(self, fraction):
        if fraction == .63:
            with self.lock:
                self.armed = True

    def observe_request(self, start, end, if_range):
        with self.lock:
            if if_range is not None and self.prefix_sent_at is not None:
                self.conditional_requests.append({
                    'start': start, 'end': end, 'tagMatched': if_range == self.etag,
                    'secondsAfterPrefix': round(time.monotonic() - self.prefix_sent_at, 3),
                })
            if (self.armed and self.window is None and if_range is None and
                    self.size * .4 <= start <= self.size * .85 and end - start >= 2 * self.prefix_bytes):
                self.window = (start, end)
                return True
            return False

    def pause_after_prefix(self, connection):
        with self.lock:
            self.prefix_sent_at = time.monotonic()
        try:
            readable, _, _ = select.select([connection], [], [], self.delay_seconds)
            if readable:
                try:
                    closed = connection.recv(1, socket.MSG_PEEK) == b''
                except ConnectionResetError:
                    closed = True
                if closed:
                    with self.lock:
                        self.peer_close_after = round(time.monotonic() - self.prefix_sent_at, 3)
        finally:
            self.finished.set()

    def result(self):
        with self.lock:
            attempted = self.window is not None
        if attempted:
            self.finished.wait(self.delay_seconds + 2)
        with self.lock:
            start, end = self.window if self.window else (None, None)
            next_byte = start + self.prefix_bytes if self.prefix_sent_at is not None else None
            exact = [r for r in self.conditional_requests
                     if r['tagMatched'] and r['start'] == next_byte and r['end'] == end and
                     17 <= r['secondsAfterPrefix'] <= self.delay_seconds + 8]
            return {
                'rangeStart': start, 'rangeEnd': end, 'firstMissingByte': next_byte,
                'peerClosedAfterSeconds': self.peer_close_after,
                'observedTimeout': self.peer_close_after is not None and 17 <= self.peer_close_after <= self.delay_seconds,
                'conditionalRequests': list(self.conditional_requests),
                'exactSuffixRequests': len(exact), 'requestedDelaySeconds': self.delay_seconds,
            }
