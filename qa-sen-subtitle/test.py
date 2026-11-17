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

out = Path('results'); out.mkdir(exist_ok=True)
root = Path(os.environ['RUNNER_TEMP']) / 'subtitle-factory'
root.mkdir(exist_ok=True)
(root/'caption.srt').write_text('1\n00:00:01,000 --> 00:02:59,000\nSUBTITLE FACTORY PASS\n', encoding='utf-8')
subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=24',
                '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-i', str(root/'caption.srt'),
                '-map', '0:v', '-map', '1:a', '-map', '2:s', '-t', '180', '-c:v', 'libx264',
                '-preset', 'ultrafast', '-crf', '28', '-c:a', 'aac', '-c:s', 'srt',
                '-metadata:s:s:0', 'language=eng', '-metadata:s:s:0', 'title=English',
                '-disposition:s:0', 'default', str(root/'fixture.mkv')], check=True)

subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(root/'fixture.mkv'),
                '-map', '0:v', '-map', '0:a', '-c', 'copy', str(root/'no-subtitles.mkv')], check=True)

class Handler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args): pass

server = http.server.ThreadingHTTPServer(('127.0.0.1', 18388), functools.partial(Handler, directory=str(root)))
threading.Thread(target=server.serve_forever, daemon=True).start()
adb('reverse', 'tcp:18388', 'tcp:18388')
engine.configure('SenPlayer')
adb('shell', 'cmd', 'appops', 'set', 'com.yfuse', 'PICTURE_IN_PICTURE', 'deny')
since = time.time()
checks = []

def select(enabled):
    for _ in range(6):
        tree = ui.snapshot()
        node = ui.find(tree, '字幕')
        if node is not None:
            ui.tap(node); break
        im = screen(); tap(im.width*.5, im.height*.35)
    ui.wait('主字幕', 12)
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

def verify(enabled, label):
    observations = []
    for index in range(8):
        im = screen(); name = 'on' if enabled else 'off'
        path = out/f'subtitle-{label}-{name}-{index}.png';im.save(path)
        text = subprocess.run(['tesseract', str(path), 'stdout', '--psm', '11', '-l', 'eng'],
                              capture_output=True, check=True).stdout.decode().upper()
        found = all(word in text for word in ('SUBTITLE', 'FACTORY', 'PASS'))
        observations.append({'phase': label, 'enabled': enabled, 'visible': found})
        if found == enabled and (enabled or len(observations) >= 3 and all(not x['visible'] for x in observations[-3:])):
            checks.extend(observations);return
        time.sleep(.5)
    checks.extend(observations)
    raise AssertionError('Subtitle image did not match selected state')

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
        window = adb('shell', 'dumpsys', 'window', 'windows')
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
    direct.decode_playback('no-subtitle-fixture', None, 180)
    no_sub = assert_no_subtitle_errors('no-subtitles')
    if any(row.get('event') == 'subtitle_renderer_ready' for row in no_sub):
        raise AssertionError('Audio/video-only file unnecessarily configured subtitle output')
    background_and_return('no-subtitles-return')
    checks.append({'case': 'no-subtitles-and-return', 'passed': True})
    adb('shell', 'am', 'force-stop', 'com.yfuse')
    adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-n',
        'com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity', '-t', 'video/x-matroska',
        '-d', 'http://127.0.0.1:18388/fixture.mkv')
    moving = direct.decode_playback('subtitle-fixture', None, 180)
    identity = engine.engine_evidence('SenPlayer', since)
    if not identity['active']:raise AssertionError('SenPlayer native output identity missing')
    select(True);verify(True, 'selected')
    select(False);verify(False, 'disabled')
    select(True);verify(True, 'reselected')
    background_and_return('subtitles-return')
    verify(True, 'foreground-return')
    checks.append({'case': 'selected-subtitles-after-return', 'passed': True})
    raw = adb('shell', 'cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl', check=False)
    (out/'subtitle-diagnostics.txt').write_text(raw)
    events = [json.loads(line).get('event') for line in raw.splitlines() if line.startswith('{')]
    if 'subtitle_renderer_ready' not in events or 'subtitle_operation_failed' in events:
        raise AssertionError('Original subtitle configuration did not succeed')
    passed = True
finally:
    (out/'subtitle-results.json').write_text(json.dumps({'passed': passed, 'checks': checks,
        'fixture': 'generated embedded English SRT, not Emby coverage'}))
    if not passed:direct.capture_failure('subtitle-factory')
    server.shutdown();server.server_close()
