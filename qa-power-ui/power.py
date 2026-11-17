"""Four-hour per-core activity baseline on a disposable cloud ARM64 Android guest."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from media import MediaServer
from library import LibraryServer
from library_ui import connect
from telemetry import Recorder, shell

sys.path.insert(0, 'qa-arm-matrix')
import engine
sys.path.insert(0, 'qa-functional')
import features as ui
from ext_run import adb, rail, screen, tap
sys.path.insert(0, 'qa-arm-original')
import direct

PHASES = [('stopped-control', 5), ('servers-idle', 15), ('library-idle', 15), ('settings-idle', 15),
          ('settings-interaction', 10), ('playing', 35), ('paused', 20), ('seeking', 15),
          ('after-player-idle', 15), ('background-idle', 30), ('screen-off', 35),
          ('network-failure', 20), ('recovered-playback', 10)]


def home(tab):
    adb('shell', 'input', 'keyevent', '224')
    adb('shell', 'wm', 'dismiss-keyguard')
    adb('shell', 'am', 'start', '-W', '-n', 'com.yfuse/com.yfuse.MainActivity')
    ui.root(tab)


def open_video(url='http://127.0.0.1:18389/fixture.mp4'):
    adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-n',
        'com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity', '-t', 'video/mp4',
        '-d', url)


def playing(core, url='http://127.0.0.1:18389/fixture.mp4'):
    opened = time.time()
    open_video(url)
    result = direct.decode_playback('power-' + core, None, 3600)
    identity = engine.engine_evidence(core, opened)
    identities = [identity]
    deadline = time.monotonic() + 10
    while not identity['active'] and time.monotonic() < deadline:
        time.sleep(1)
        identity = engine.engine_evidence(core, opened)
        identities.append(identity)
    evidence = Path('results/engine-entry-' + str(time.time_ns()) + '.json')
    evidence.parent.mkdir(exist_ok=True)
    evidence.write_text(json.dumps({'movingPicture': result, 'identityChecks': identities}), encoding='utf-8')
    if not identity['active']:
        raise AssertionError('Requested native engine not active: ' + json.dumps(identity))
    if rail(ui.snapshot()):
        image = screen()
        tap(image.width * .5, image.height * .35)
    return {'movingPicture': result, 'identity': identity}


def pause():
    _, tree = direct.reveal()
    # Reuse the revealed tree: a second instrumentation attachment may outlive
    # the player's auto-hide interval before the tap reaches the control.
    node = ui.find(tree, ['暂停', 'Pause'])
    if node is None:
        raise AssertionError('Pause control unavailable')
    ui.tap(node)
    _, tree = direct.reveal()
    first = direct.media_clock(tree)
    time.sleep(2)
    _, tree = direct.reveal()
    second = direct.media_clock(tree)
    if first is None or second is None or abs(second - first) > 1:
        raise AssertionError('Playback did not pause')
    return {'positionBefore': first, 'positionAfter': second}


def seek(index):
    bounds, _ = direct.reveal()
    fraction = .18 if index % 2 else .62
    x = int(bounds[0] + (bounds[2] - bounds[0] - 1) * fraction)
    y = (bounds[1] + bounds[3]) // 2
    adb('shell', 'input', 'swipe', x - 25, y, x, y, 350)
    return direct.decode_playback('power-seek', 3600 * fraction, 3600)


def enter(name, server, core):
    if name == 'stopped-control':
        adb('shell', 'am', 'force-stop', 'com.yfuse')
    elif name == 'servers-idle':
        home('服务器')
    elif name == 'library-idle':
        home('媒体库')
    elif name in ('settings-idle', 'settings-interaction'):
        home('我的')
    elif name in ('playing', 'seeking', 'recovered-playback'):
        server.failure = False
        return playing(core, 'http://127.0.0.1:18389/outage.mp4') if name == 'recovered-playback' else playing(core)
    elif name == 'paused':
        return pause()
    elif name == 'after-player-idle':
        adb('shell', 'input', 'keyevent', '4')
        home('我的')
    elif name == 'background-idle':
        adb('shell', 'input', 'keyevent', '3')
    elif name == 'screen-off':
        adb('shell', 'input', 'keyevent', '223')
    elif name == 'network-failure':
        home('我的')
        server.failure = True
        previous = server.counters()['unavailableResponses']
        open_video('http://127.0.0.1:18389/outage.mp4')
        # This disposable loopback fixture is explicitly authorized by the test.
        # Playback helpers acknowledge the local-link prompt, but this failure
        # phase deliberately cannot wait for a decoded frame.
        tree = ui.snapshot()
        if ui.find(tree, ['打开本机或局域网中的视频？']) is not None:
            button = ui.find(tree, ['打开', 'Open'])
            if button is None:
                raise AssertionError('Authorized fixture confirmation has no Open control')
            ui.tap(button)
        deadline = time.monotonic() + 15
        while server.counters()['unavailableResponses'] <= previous and time.monotonic() < deadline:
            time.sleep(.5)
        if server.counters()['unavailableResponses'] <= previous:
            raise AssertionError('No actual HTTP503 observed; cached playback is not a network-failure test')
    return {'entered': name}


def main():
    engine.guard()
    ui.pkg = 'com.yfuse'
    core = os.environ['QA_ENGINE']
    pilot = os.environ.get('QA_POWER_PILOT') == 'true'
    out = Path('results/power')
    out.mkdir(parents=True, exist_ok=True)
    fixture = Path(os.environ['RUNNER_TEMP']) / 'power-fixture.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i',
        'testsrc2=size=640x360:rate=24', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
        '-t', '3600', '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '35',
        '-c:a', 'aac', '-b:a', '64k', '-movflags', '+faststart', str(fixture)], check=True)
    server = MediaServer(fixture, out / 'http.jsonl')
    library = LibraryServer(out / 'library-http.jsonl')
    adb('reverse', 'tcp:18389', 'tcp:18389')
    adb('reverse', 'tcp:18196', 'tcp:18196')
    engine.configure(core)
    try:
        connect()
    except Exception as error:
        screen().save(out / 'library-setup-failure.png')
        (out / 'library-setup-failure.xml').write_bytes(ET.tostring(ui.snapshot(), encoding='utf-8'))
        (out / 'setup-failure.json').write_text(json.dumps({'error': str(error),
            'labels': ui.page_info(ui.snapshot()), 'status': 'measurement-not-started'}), encoding='utf-8')
        server.close()
        library.close()
        raise
    recorder = Recorder(out)
    (out / 'conditions.json').write_text(json.dumps({
        'protocol': 2, 'batteryLevelSetpoint': 80, 'batteryIsSimulated': True,
        'chargingSetpoint': False, 'seekPattern': 'alternating18and62percent',
        'networkFailure': 'uncached outage URL must receive HTTP503 before the phase starts',
        'engineIdentity': 'same source requires native mapping/output evidence within10seconds; all attempts retained',
        'comparisonLimit': 'Earlier protocol used simulated level0, repeated18percent seeks and did not prove uncached503.',
    }, indent=2), encoding='utf-8')
    phases = []
    status = 'incomplete'
    # This changes only the guest's charging state so Batterystats accounts for activity.
    # It does NOT manufacture a battery discharge or create a physical power sensor.
    adb('shell', 'dumpsys', 'battery', 'unplug')
    adb('shell', 'dumpsys', 'battery', 'set', 'level', '80')
    adb('shell', 'dumpsys', 'battery', 'set', 'status', '3')
    adb('shell', 'dumpsys', 'batterystats', '--reset')
    adb('shell', 'dumpsys', 'batterystats', '--enable', 'full-wake-history')
    try:
        for name, minutes in PHASES:
            duration = 45 if pilot else minutes * 60
            row = {'phase': name, 'plannedSeconds': duration, 'status': 'incomplete'}
            phases.append(row)
            print('POWER_PHASE', core, name, duration, flush=True)
            try:
                row['entryEvidence'] = enter(name, server, core)
                adb('shell', 'dumpsys', 'gfxinfo', 'com.yfuse', 'reset')
                screen().save(out / (name + '-entry.png'))
                began = time.monotonic()
                next_action = began + 30
                number = 0
                action_number = 0
                while time.monotonic() - began < duration:
                    action = False
                    if name in ('seeking', 'settings-interaction') and time.monotonic() >= next_action:
                        if name == 'seeking':
                            row.setdefault('seeks', []).append(seek(action_number))
                        else:
                            width, height = ui.size()
                            start, end = (.75, .30) if action_number % 2 else (.30, .75)
                            adb('shell', 'input', 'swipe', width // 2, int(height * start),
                                width // 2, int(height * end), 400)
                        action_number += 1
                        next_action = time.monotonic() + 30
                        action = True
                    recorder.capture(name, {**server.counters(), 'libraryRequests': library.requests}, heavy=number % 4 == 0,
                                     intervention=action or time.monotonic() - began < 60)
                    number += 1
                    time.sleep(min(30, max(0, duration - (time.monotonic() - began))))
                row['measuredSeconds'] = round(time.monotonic() - began, 2)
                row['status'] = 'recorded'
                if name in ('playing', 'recovered-playback'):
                    row['endPicture'] = direct.decode_playback('power-end', None, 3600)
                if os.environ.get('QA_EXPECT_NO_SUBTITLE_CONFIGURATION_ERRORS') == 'true':
                    raw = adb('shell', 'cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl', check=False)
                    (out / (name + '-subtitle-check.jsonl')).write_text(raw, encoding='utf-8')
                    events = [json.loads(line) for line in raw.splitlines() if line.startswith('{')]
                    if not events or any(event.get('event') == 'subtitle_operation_failed' for event in events):
                        raise AssertionError('Audio/video-only fixture must not fail subtitle configuration')
            except Exception as error:
                row['status'] = 'failed'
                row['error'] = type(error).__name__ + ': ' + str(error)[:200]
                screen().save(out / (name + '-failure.png'))
                from capture import failure_diagnostics
                failure_diagnostics(out, name)
            finally:
                recorder.finish(phases, 'incomplete')
        status = 'recorded' if all(r['status'] == 'recorded' for r in phases) else 'failed'
    finally:
        recorder.capture('final', server.counters(), heavy=True, intervention=True)
        recorder.finish(phases, status)
        adb('shell', 'dumpsys', 'battery', 'reset', check=False)
        adb('shell', 'input', 'keyevent', '224', check=False)
        server.close()
        library.close()
    # Recording success is not a claim that the app meets a physical energy budget.
    if status != 'recorded':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
