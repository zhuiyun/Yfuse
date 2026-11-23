"""Range and startup timing for a slow, byte-identical public movie."""
import threading
import time


class StartupRangeTrial:
    header_seconds = 1.5
    bytes_per_second = 2 * 1024 * 1024
    block_size = 2 * 1024 * 1024

    def __init__(self):
        self.lock = threading.Lock()
        self.started = None
        self.opened_ms = None
        self.rows = []

    def before_open(self):
        with self.lock:
            self.started = time.monotonic()

    def first_picture(self):
        with self.lock:
            self.opened_ms = round(1000 * (time.monotonic() - self.started))

    def arrival(self, first, last):
        with self.lock:
            if self.started is not None:
                self.rows.append({'ms': round(1000 * (time.monotonic() - self.started)),
                                  'phase': 'startup' if self.opened_ms is None else 'playing',
                                  'first': first, 'last': last})

    def result(self):
        with self.lock:
            rows = self.rows.copy()
            opened = self.opened_ms
        prefixes, full_blocks, paired = set(), set(), set()
        for row in rows:
            first = row['first']
            if row['phase'] != 'startup' or first <= 0 or first % self.block_size:
                continue
            length = row['last'] - first + 1
            if length == 128 * 1024:
                prefixes.add(first)
            elif length == self.block_size:
                full_blocks.add(first)
                if first in prefixes:
                    paired.add(first)
        return {'headerDelayMs': round(self.header_seconds * 1000), 'bytesPerSecond': self.bytes_per_second,
                'strongValidator': False, 'firstPictureMs': opened, 'requests': rows,
                'startupRangesBeyondFirstBlock': sorted(full_blocks),
                'startupPrefixThenFullBeyondFirstBlock': sorted(paired)}
