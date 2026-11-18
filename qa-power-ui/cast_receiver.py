"""Exercise the casting controls with an independently decoding DLNA renderer on the cloud bridge."""
from contextlib import suppress
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import subprocess
import time

from PIL import Image

from capture import capture, failure_diagnostics
from media import MediaServer
from navigation import click
from player_ui import pause_visible
from power import adb, direct, engine, playing, ui
from prepare_buffer_clock import create
from telemetry import Recorder


RECEIVER = 'Yfuse QA Renderer'
OUT = Path('results/cast')


def docker_value(*args):
    return subprocess.check_output(['docker', *args], text=True, timeout=15).strip()


def clock_second(pixels):
    def digit(x, y):
        rgb = pixels.getpixel((x, y))
        if min(rgb) >= 180:
            return 1
        if max(rgb) <= 65:
            return 0
        return None

    def row(y):
        if digit(4, y) != 1 or digit(12, y) != 0:
            return None
        bits = [digit(20 + 8 * bit, y) for bit in range(7)]
        return None if None in bits else sum(value << bit for bit, value in enumerate(bits))

    second = row(8)
    return second if second is not None and second < 96 and row(10) == second else None


def latest_picture(frames):
    for path in sorted(frames.glob('frame-*.rgb'), reverse=True):
        try:
            data = path.read_bytes()
        except FileNotFoundError:
            continue
        if len(data) != 160 * 90 * 3:
            continue
        picture = Image.frombytes('RGB', (160, 90), data)
        second = clock_second(picture)
        if second is not None:
            return int(path.stem.split('-')[-1]), second, picture
    return None


def moving(frames, label, *, target=None, after=-1):
    started = time.monotonic()
    deadline = started + 35
    first = None
    while time.monotonic() < deadline:
        result = latest_picture(frames)
        if result is not None:
            index, second, picture = result
            if index > after and (target is None or target - 4 <= second <= target + 9):
                if first is None:
                    first = index, second, time.monotonic() - started
                elif index > first[0] and second > first[1]:
                    picture.save(OUT / (label + '-receiver.png'))
                    return {'firstFrame': first[0], 'firstSecond': first[1], 'frame': index, 'second': second,
                            'firstObservedAfterSeconds': round(first[2], 3)}
        time.sleep(.15)
    raise AssertionError('No advancing decoded receiver timecode for ' + label)


def stable_picture(frames):
    time.sleep(1.5)
    previous = latest_picture(frames)
    time.sleep(3)
    current = latest_picture(frames)
    if previous is None or current is None or current[1] != previous[1]:
        raise AssertionError('Receiver timecode did not stay still')
    return {'firstFrame': previous[0], 'frame': current[0], 'second': current[1]}


def page_contains(label):
    return any(label in word for word in ui.page_info(ui.snapshot()))


def cast_panel():
    if not page_contains('局域网投屏设备'):
        if not page_contains('投屏'):
            direct.reveal()
        click(['投屏中', '投屏'], 0)


def require_screen(name, expected):
    shot = capture(OUT, name, expected=expected, timeout=18)
    if 'com.yfuse' not in shot['packages'] or not any(expected in s for s in shot['labels']):
        raise AssertionError('Expected visible casting control ' + expected)
    return {'name': name, 'sha256': hashlib.sha256((OUT / (name + '.png')).read_bytes()).hexdigest()}


def seek(frames, fraction, previous, name):
    (left, top, right, bottom), _ = direct.reveal()
    y = (top + bottom) // 2
    start = .83 if fraction < .5 else .17
    adb('shell', 'input', 'swipe', round(left + (right - left) * start), y,
        round(left + (right - left) * fraction), y, 330)
    return moving(frames, name, target=96 * fraction, after=previous)


def observe(recorder, phase, server, duration):
    deadline = time.monotonic() + duration
    while True:
        recorder.capture(phase, server.counters())
        if time.monotonic() >= deadline:
            break
        time.sleep(2)


def continuity(evidence):
    paused = evidence['receiverPause']['second']
    resumed = evidence['resumed']
    first = resumed['firstSecond']
    return {
        'pausedClockAgreesWithPicture': abs(evidence['phonePause']['positionAfter'] - paused) <= 2,
        'resumedAtPausedPicture': paused - 1 <= first <= paused + resumed['firstObservedAfterSeconds'] + 2,
        'resumedFramesAdvanceInOrder': 0 < resumed['second'] - first <= resumed['frame'] - resumed['firstFrame'] + 1,
    }


def save_cast_diagnostics():
    raw = adb('shell', 'cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl', check=False)
    events = []
    for line in raw.splitlines():
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if row.get('category') != 'cast':
            continue
        attributes = row.get('attributes') or {}
        events.append({'event': row.get('event'), 'timestamp': row.get('timestamp'), 'attributes':
                       {k: v for k, v in attributes.items() if k in ('state', 'status', 'positionMs', 'startPositionMs',
                         'targetMs', 'fromMs', 'attempt', 'seekAttempts', 'seekedBeforePlay', 'failure')}})
    (OUT / 'cast-events.json').write_text(json.dumps(events, indent=2))


def exercise(core, url, frames, server, recorder, evidence):
    evidence['localPlayback'] = playing(core, url)
    pause_visible(OUT / 'local-pause.txt')
    require_screen('before-discovery', '投屏')
    cast_panel()
    capture(OUT, 'opened-cast-panel', expected='局域网投屏设备', timeout=3)
    evidence['discovery'] = require_screen('discovered', RECEIVER)
    click(RECEIVER)
    started = moving(frames, 'start')
    evidence['remoteStart'] = started
    cast_panel()
    evidence['connected'] = require_screen('connected', '停止投屏')
    adb('shell', 'input', 'keyevent', '4')
    observe(recorder, 'receiver-playing', server, 8)
    forward = seek(frames, .63, started['frame'], 'forward')
    evidence['forwardSeek'] = forward
    back = seek(frames, .21, forward['frame'], 'backward')
    evidence['backwardSeek'] = back
    evidence['phonePause'] = pause_visible(OUT / 'remote-pause.txt')
    evidence['receiverPause'] = stable_picture(frames)
    observe(recorder, 'receiver-paused', server, 8)
    _, tree = direct.reveal()
    control = ui.find(tree, ['播放', '继续播放', 'Play', 'Resume'])
    if control is None:
        raise AssertionError('Resume control missing on phone')
    ui.tap(control)
    evidence['resumed'] = moving(frames, 'resumed', after=evidence['receiverPause']['frame'])
    cast_panel()
    click('停止投屏')
    cast_panel()
    evidence['stopped'] = require_screen('after-stop', '重新扫描')
    if page_contains('停止投屏'):
        raise AssertionError('The stopped renderer still has an active session')
    evidence['receiverStopped'] = stable_picture(frames)
    adb('shell', 'input', 'keyevent', '4')
    evidence['localReturn'] = direct.decode_playback('returned-' + core, None, 96)
    observe(recorder, 'after-cast', server, 8)


def main():
    engine.guard()
    bridge = docker_value('network', 'inspect', 'bridge', '--format', '{{(index .IPAM.Config 0).Gateway}}')
    peer = docker_value('inspect', 'qa-receiver', '--format', '{{.NetworkSettings.Networks.bridge.IPAddress}}')
    subnet = ipaddress.ip_network(docker_value('network', 'inspect', 'bridge', '--format', '{{(index .IPAM.Config 0).Subnet}}'))
    if not subnet.is_private or any(ipaddress.ip_address(v) not in subnet for v in (bridge, peer)) or peer == bridge:
        raise RuntimeError('Test requires two distinct addresses on the private cloud Docker bridge')
    core = os.environ['QA_ENGINE']
    engine.configure(core)
    OUT.mkdir(parents=True, exist_ok=True)
    temporary = Path(os.environ['RUNNER_TEMP'])
    frames = temporary / 'cast-frames'
    fixture = temporary / 'cast-clock.mp4'
    create(fixture)
    server = MediaServer(fixture, OUT / 'media.jsonl', bridge_address=bridge)
    recorder = Recorder(OUT / 'activity')
    evidence = {'core': core, 'fixtureSha256': hashlib.sha256(fixture.read_bytes()).hexdigest(),
                'receiver': RECEIVER, 'receiverSoftware': 'GMediaRender/GStreamer',
                'audioClock': os.environ['QA_RECEIVER_CLOCK'], 'passed': False}
    try:
        exercise(core, f'http://{bridge}:18389/clock.mp4', frames, server, recorder, evidence)
        arrivals = [json.loads(line) for line in (OUT / 'media.jsonl').read_text().splitlines()]
        received = [row for row in arrivals if row['peer'] == peer and row['method'] == 'GET' and row['status'] in (200, 206)]
        evidence['bytesDeliveredToReceiver'] = sum(row['bytes'] for row in received)
        if evidence['bytesDeliveredToReceiver'] <= 0:
            raise AssertionError('No original media bytes recorded at the receiver')
        evidence['continuity'] = continuity(evidence)
        if not all(evidence['continuity'].values()):
            raise AssertionError('Receiver continuity failed: ' + str(evidence['continuity']))
        evidence['passed'] = True
    finally:
        save_cast_diagnostics()
        if not evidence['passed']:
            failure_diagnostics(OUT, 'failure')
            with suppress(Exception):
                capture(OUT, 'failure')
            with suppress(OSError):
                last = latest_picture(frames)
                if last is not None:
                    last[2].save(OUT / 'last-receiver.png')
        (OUT / 'outcome.json').write_text(json.dumps(evidence, indent=2))
        recorder.finish(['receiver-playing', 'receiver-paused', 'after-cast'], 'passed' if evidence['passed'] else 'incomplete')
        server.close()


if __name__ == '__main__':
    main()
