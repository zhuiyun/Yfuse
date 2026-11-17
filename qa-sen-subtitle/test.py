"""Cloud-only native subtitle on/off evidence, using an embedded generated SRT track."""
import functools
import http.server
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time

if os.environ.get('GITHUB_ACTIONS') != 'true':
    raise SystemExit('Cloud runner required')
sys.path.insert(0, 'qa-arm-matrix')
import engine
sys.path.insert(0, 'qa-functional')
import features as ui
from ext_run import adb, screen, tap
sys.path.insert(0, 'qa-arm-original')
import direct
sys.path.insert(0, 'qa-power-ui')
import background_audio as lifecycle
from navigation import click, bounds, vertical_pane

out = Path('results'); out.mkdir(exist_ok=True)
root = Path(os.environ['RUNNER_TEMP']) / 'subtitle-factory'
root.mkdir(exist_ok=True)
DURATION = 600
SECONDARY = ('SECONDARY', 'CLOUD', 'PASS')
(root/'caption.srt').write_text('1\n00:00:01,000 --> 00:09:59,000\nSUBTITLE FACTORY PASS\n', encoding='utf-8')
(root/'secondary.srt').write_text('1\n00:00:01,000 --> 00:09:59,000\nSECONDARY CLOUD PASS\n', encoding='utf-8')
subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=24',
                '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-i', str(root/'caption.srt'), '-i', str(root/'secondary.srt'),
                '-map', '0:v', '-map', '1:a', '-map', '2:s', '-map', '3:s', '-t', str(DURATION), '-c:v', 'libx264',
                '-preset', 'ultrafast', '-crf', '28', '-c:a', 'aac', '-c:s', 'srt',
                '-metadata:s:s:0', 'language=eng', '-metadata:s:s:0', 'title=English',
                '-disposition:s:0', 'default', '-metadata:s:s:1', 'language=zho',
                '-metadata:s:s:1', 'title=Secondary QA', '-disposition:s:1', '0', str(root/'fixture.mkv')], check=True)

subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(root/'fixture.mkv'),
                '-map', '0:v', '-map', '0:a', '-c', 'copy', str(root/'no-subtitles.mkv')], check=True)

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self): self.serve(True)
    def do_HEAD(self): self.serve(False)
    def serve(self, body):
        name = self.path.lstrip('/')
        if name not in ('fixture.mkv', 'no-subtitles.mkv'):
            self.send_error(404); return
        path = root/name; size = path.stat().st_size
        match = re.fullmatch(r'bytes=(\d+)-(\d*)', self.headers.get('Range', ''))
        lo = int(match[1]) if match else 0
        hi = min(int(match[2]), size-1) if match and match[2] else size-1
        if lo > hi: self.send_error(416); return
        self.send_response(206 if match else 200)
        self.send_header('Content-Type', 'video/x-matroska')
        self.send_header('Content-Length', str(hi-lo+1))
        self.send_header('Accept-Ranges', 'bytes')
        if match: self.send_header('Content-Range', f'bytes {lo}-{hi}/{size}')
        self.end_headers()
        if body:
            try:
                with path.open('rb') as stream:
                    stream.seek(lo)
                    while lo <= hi:
                        data = stream.read(min(65536, hi-lo+1))
                        if not data: break
                        self.wfile.write(data); lo += len(data)
            except (BrokenPipeError, ConnectionResetError): pass

server = http.server.ThreadingHTTPServer(('127.0.0.1', 18388), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
adb('reverse', 'tcp:18388', 'tcp:18388')
engine.configure('SenPlayer')
adb('shell', 'cmd', 'appops', 'set', 'com.yfuse', 'PICTURE_IN_PICTURE', 'deny')
since = time.time()
checks = []

def open_panel():
    for _ in range(6):
        tree = ui.snapshot()
        node = ui.find(tree, '字幕')
        if node is not None:
            ui.tap(node); break
        im = screen(); tap(im.width*.5, im.height*.35)
    ui.wait('主字幕', 12)


def select(enabled):
    open_panel()
    tree = ui.snapshot()
    choices = []
    for node in ui.nodes(tree):
        words = ui.labels(node)
        matches = any(re.search(r'english|\beng\b|srt|英文|英语', word, re.I) for word in words) if enabled else '关闭' in words
        if matches and node.get('enabled') != 'false':choices.append(node)
    if not choices:
        (out/'subtitle-options.json').write_text(json.dumps(ui.page_info(tree), ensure_ascii=False))
        raise AssertionError('Required native subtitle option missing')
    ui.tap(min(choices, key=lambda n: ui.coords(n)[1]))
    if ui.find(ui.snapshot(), '主字幕') is not None:adb('shell', 'input', 'keyevent', '4')
    tree = ui.snapshot()
    if ui.find(tree, '字幕') is not None:
        im = screen();tap(im.width*.5, im.height*.35)

def verify(enabled, label, words=('SUBTITLE', 'FACTORY', 'PASS')):
    observations = []
    for index in range(8):
        im = screen(); name = 'on' if enabled else 'off'
        path = out/f'subtitle-{label}-{name}-{index}.png';im.save(path)
        text = subprocess.run(['tesseract', str(path), 'stdout', '--psm', '11', '-l', 'eng'],
                              capture_output=True, check=True).stdout.decode().upper()
        found = all(word in text for word in words)
        observations.append({'phase': label, 'enabled': enabled, 'visible': found, 'words': words})
        if found == enabled and (enabled or len(observations) >= 3 and all(not x['visible'] for x in observations[-3:])):
            checks.extend(observations);return
        time.sleep(.5)
    checks.extend(observations)
    raise AssertionError('Subtitle image did not match selected state')

def close_panel():
    adb('shell', 'input', 'keyevent', '4')
    if ui.find(ui.snapshot(), '字幕') is not None:
        im = screen(); tap(im.width*.5, im.height*.35)


def preset(label):
    open_panel(); click(label); close_panel()


def secondary_off():
    open_panel()
    for _ in range(10):
        tree = ui.snapshot()
        panes = [n for n in ui.nodes(tree) if vertical_pane(n)]
        if not panes: break
        pane = max(panes, key=lambda n: (bounds(n)[2]-bounds(n)[0])*(bounds(n)[3]-bounds(n)[1]))
        left, top, right, bottom = bounds(pane)
        group = ui.find(tree, '副字幕')
        if group is not None:
            group_y = ui.coords(group)[1]
            candidates = [n for n in ui.nodes(tree) if n.get('text') == '关闭'
                          and n.get('enabled') != 'false' and n.get('visible') != 'false'
                          and group_y < ui.coords(n)[1]
                          and top <= bounds(n)[1] < bounds(n)[3] <= bottom]
            if candidates:
                ui.tap(min(candidates, key=lambda n: ui.coords(n)[1])); close_panel(); return
        ui.shell('input', 'swipe', (left+right)//2, int(top+(bottom-top)*.8),
                 (left+right)//2, int(top+(bottom-top)*.3), 400)
        time.sleep(.5)
    raise AssertionError('Secondary off action was not visible in its own section')


def seek_with_subtitles(fraction):
    (left, top, right, bottom), _ = direct.reveal()
    y = (top+bottom)//2
    adb('shell', 'input', 'swipe', round(left+(right-left)*(.8 if fraction < .5 else .2)), y,
        round(left+(right-left)*fraction), y, 330)
    proof = direct.decode_playback('dual-seek', DURATION*fraction, DURATION)
    checks.append({'case': 'dual-subtitle-actual-seek', 'fraction': fraction, 'proof': proof})
    if ui.find(ui.snapshot(), '字幕') is not None:
        im = screen(); tap(im.width*.5, im.height*.35)
    verify(True, 'seek-primary-'+str(fraction))
    verify(True, 'seek-secondary-'+str(fraction), SECONDARY)


def assert_no_subtitle_errors(label):
    raw = adb('shell', 'cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl', check=False)
    (out/(label+'-diagnostics.txt')).write_text(raw, encoding='utf-8')
    rows = [json.loads(line) for line in raw.splitlines() if line.startswith('{')]
    if any(row.get('event') == 'subtitle_operation_failed' for row in rows):
        raise AssertionError('Unexpected subtitle configuration failure in '+label)
    return rows


def background_and_return(label):
    evidence = out/label
    evidence.mkdir(exist_ok=True)
    lifecycle.lifecycle_evidence(evidence, 'after-launch')
    adb('shell', 'input', 'keyevent', '3')
    time.sleep(3)
    lifecycle.require_state(evidence, 'background-paused', 2, False)
    lifecycle.return_to_player(evidence, before_play=wait_for_focused_player)
    return assert_no_subtitle_errors(label)


def wait_for_focused_player(evidence):
    # ActivityManager's resumed record precedes the app's onStart/window rotation.
    # Keep the single media-play command, but record that its recipient is ready.
    deadline = time.monotonic() + 20
    stable = None
    while time.monotonic() < deadline:
        # On Android 12 the focus summary is outside the `windows` subsection.
        window = adb('shell', 'dumpsys', 'window')
        (evidence/'focus-wait-window.txt').write_text(window, encoding='utf-8')
        focused = re.search(r'mCurrentFocus[^\n]*com\.yfuse[^\n]*PlayerActivity', window)
        size = screen().size
        marker = (focused.group(0), size) if focused else None
        if marker is not None and marker == stable:
            (evidence/'before-play-window.txt').write_text(window, encoding='utf-8')
            lifecycle.session(evidence, 'before-play')
            screen().save(evidence/'before-play.png')
            return
        stable = marker
        time.sleep(1)
    raise AssertionError('Player window never gained stable focus before media play')


passed = False
try:
    adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-n',
        'com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity', '-t', 'video/x-matroska',
        '-d', 'http://127.0.0.1:18388/no-subtitles.mkv')
    direct.decode_playback('no-subtitle-fixture', None, DURATION)
    no_sub = assert_no_subtitle_errors('no-subtitles')
    if any(row.get('event') == 'subtitle_renderer_ready' for row in no_sub):
        raise AssertionError('Audio/video-only file unnecessarily configured subtitle output')
    background_and_return('no-subtitles-return')
    checks.append({'case': 'no-subtitles-and-return', 'passed': True})
    adb('shell', 'am', 'force-stop', 'com.yfuse')
    adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-n',
        'com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity', '-t', 'video/x-matroska',
        '-d', 'http://127.0.0.1:18388/fixture.mkv')
    moving = direct.decode_playback('subtitle-fixture', None, DURATION)
    identity = engine.engine_evidence('SenPlayer', since)
    if not identity['active']:raise AssertionError('SenPlayer native output identity missing')
    select(True);verify(True, 'selected')
    select(False);verify(False, 'disabled')
    select(True);verify(True, 'reselected')
    background_and_return('subtitles-return')
    verify(True, 'foreground-return')
    checks.append({'case': 'selected-subtitles-after-return', 'passed': True})
    preset('英文 / 中文')
    verify(True, 'dual-primary'); verify(True, 'dual-secondary', SECONDARY)
    select(False)
    verify(False, 'primary-off-dual'); verify(True, 'secondary-only', SECONDARY)
    select(True)
    seek_with_subtitles(.65); seek_with_subtitles(.25)
    preset('大字幕')
    verify(True, 'custom-style-primary'); verify(True, 'custom-style-secondary', SECONDARY)
    background_and_return('dual-subtitles-return')
    verify(True, 'dual-return-primary'); verify(True, 'dual-return-secondary', SECONDARY)
    secondary_off()
    verify(True, 'primary-after-secondary-off'); verify(False, 'secondary-off', SECONDARY)
    select(False)
    verify(False, 'both-off-primary'); verify(False, 'both-off-secondary', SECONDARY)
    checks.append({'case': 'dual-subtitles-seek-style-lifecycle-and-off', 'passed': True})
    raw = adb('shell', 'cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl', check=False)
    (out/'subtitle-diagnostics.txt').write_text(raw)
    events = [json.loads(line).get('event') for line in raw.splitlines() if line.startswith('{')]
    if 'subtitle_renderer_ready' not in events or 'subtitle_operation_failed' in events:
        raise AssertionError('Original subtitle configuration did not succeed')
    passed = True
finally:
    (out/'subtitle-results.json').write_text(json.dumps({'passed': passed, 'checks': checks,
        'fixture': 'generated two-track English and Chinese-labelled SRT, not Emby coverage'}))
    if not passed:direct.capture_failure('subtitle-factory')
    server.shutdown();server.server_close()
