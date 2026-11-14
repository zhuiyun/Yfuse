"""Full APK decoder controls; generated fixtures are never counted as Emby coverage."""
import functools
import http.server
import json
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, 'qa-full-library')
import run

ui, pilot, measure = run.ui, run.pilot, run.measure
out = Path('private-results')
out.mkdir(exist_ok=True)
root = Path(os.environ['RUNNER_TEMP']) / 'decoder-controls'
root.mkdir(exist_ok=True)


class RangeServer(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        name = self.path.split('?')[0].rsplit('/', 1)[-1]
        name = 'control.mkv' if name == 'disguised.m3u8' else name
        if name not in ('control.mp4', 'control.mkv'):
            self.send_error(404)
            return
        path = root / name
        size = path.stat().st_size
        start, end = 0, size - 1
        match = re.fullmatch(r'bytes=(\d+)-(\d*)', self.headers.get('Range', ''))
        if match:
            start = int(match[1])
            end = min(int(match[2]) if match[2] else end, end)
        if start >= size or end < start:
            self.send_response(416)
            self.send_header('Content-Range', f'bytes */{size}')
            self.end_headers()
            return
        self.send_response(206 if match else 200)
        self.send_header('Content-Type', 'video/mp4' if name.endswith('mp4') else 'video/x-matroska')
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(end - start + 1))
        if match:
            self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        self.end_headers()
        if self.command == 'HEAD':
            return
        try:
            with path.open('rb') as f:
                f.seek(start)
                remaining = end - start + 1
                while remaining:
                    data = f.read(min(65536, remaining))
                    if not data:
                        break
                    self.wfile.write(data)
                    remaining -= len(data)
        except (BrokenPipeError, ConnectionResetError):
            pass


original_snapshot = ui.snapshot
instrument_timeouts = []


def resilient_snapshot():
    for attempt in range(2):
        try:
            return original_snapshot()
        except subprocess.TimeoutExpired:
            instrument_timeouts.append({'at': round(time.monotonic(), 2), 'retry': attempt})
            # Restart only the observer. Never restart the player to hide a playback failure.
            subprocess.run(['adb', 'shell', 'am', 'force-stop', 'qa.yfuse.gesture'], capture_output=True, timeout=10)
            if attempt:
                raise


def fixture_controls():
    tree = ui.snapshot()
    if ui.find(tree, ['打开本机或局域网中的视频？']) is not None:
        button = ui.find(tree, ['打开'])
        if button is None:
            raise AssertionError('fixture confirmation missing')
        ui.tap(button)
    return run.playback_controls()


def main():
    pilot.engine.guard()
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i',
                    'testsrc2=size=640x360:rate=24', '-f', 'lavfi', '-i', 'sine=frequency=880:sample_rate=48000',
                    '-t', '600', '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '28', '-g', '48',
                    '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '96k', '-movflags', '+faststart',
                    str(root / 'control.mp4')], check=True)
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', str(root / 'control.mp4'),
                    '-c', 'copy', str(root / 'control.mkv')], check=True)
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 18764), RangeServer)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    ui.adb('reverse', 'tcp:18764', 'tcp:18764')
    ui.snapshot = resilient_snapshot
    measure.reveal = fixture_controls
    failures = 0
    try:
        for core in ('YCore', 'SenPlayer'):
            run.provision(core)
            for name in ('control.mp4', 'control.mkv', 'disguised.m3u8'):
                label = name.replace('.', '-')
                media = {'location': 'http://127.0.0.1:18764/' + name, 'container': name.rsplit('.', 1)[-1],
                         'seconds': 600, 'targets': [.15, .80, .35, .45]}
                start = time.time()
                try:
                    pilot.one(core, label, media)
                    result = 'passed'
                except Exception as error:
                    failures += 1
                    result = 'failed'
                    measure.measured(core + '-' + label + '-failed', error=type(error).__name__,
                                     diagnostic=measure.app_events()[-18:], fixture=True)
                pixels = ui.adb('exec-out', 'screencap', '-p', output=True)
                (out / (core + '-' + label + '-' + result + '.png')).write_bytes(pixels)
                measure.measured(core + '-' + label + '-duration', seconds=round(time.time() - start, 2), fixture=True)
    finally:
        server.shutdown()
        (out / 'observer-timeouts.json').write_text(json.dumps(instrument_timeouts))
        ui.shell('am', 'force-stop', ui.pkg)
        ui.shell('pm', 'clear', ui.pkg)
    if failures:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
