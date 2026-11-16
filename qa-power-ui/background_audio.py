"""Cloud-only audio lifecycle activity; actual media-session positions and tagged locks."""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

from capture import capture, failure_diagnostics
from media import MediaServer
from navigation import click
from power import adb, direct, engine, playing
from telemetry import Recorder


def enable_audio():
    # Fresh disposable app only; setup is not counted as a UI-switch test.
    adb('shell', 'am', 'force-stop', 'com.yfuse')
    target = '/data/user/0/com.yfuse/shared_prefs/yfuse.xml'
    root = ET.fromstring(adb('shell', 'cat', target))
    node = root.find("./*[@name='player.backgroundAudio']")
    if node is None:
        node = ET.SubElement(root, 'boolean', {'name': 'player.backgroundAudio'})
    node.tag = 'boolean'
    node.set('value', 'true')
    uid = adb('shell', 'stat', '-c', '%u', '/data/user/0/com.yfuse').strip()
    if not uid.isdecimal():
        raise AssertionError('Disposable app UID unavailable')
    local = Path('results/background-audio-setup.xml')
    local.write_bytes(ET.tostring(root, encoding='utf-8', xml_declaration=True))
    adb('push', str(local), '/data/local/tmp/qa-background-audio.xml')
    adb('shell', 'cp', '/data/local/tmp/qa-background-audio.xml', target)
    adb('shell', 'chown', uid + ':' + uid, target)
    adb('shell', 'chmod', '660', target)
    adb('shell', 'restorecon', target)
    actual = ET.fromstring(adb('shell', 'cat', target))
    if actual.find("./boolean[@name='player.backgroundAudio']").get('value') != 'true':
        raise AssertionError('Audio preference not enabled')
    local.unlink()
    adb('shell', 'cmd', 'appops', 'set', 'com.yfuse', 'PICTURE_IN_PICTURE', 'deny')


def session(out, label):
    media = adb('shell', 'dumpsys', 'media_session')
    power = adb('shell', 'dumpsys', 'power')
    (out / (label + '-media-session.txt')).write_text(media, encoding='utf-8')
    (out / (label + '-power.txt')).write_text(power, encoding='utf-8')
    if 'Wake Locks: size=' not in power:
        raise AssertionError('Current system wake-lock inventory unavailable')
    match = re.search(r'package=com\.yfuse\b(.*?)(?=Session #|\Z)', media, re.S)
    state = re.search(r'PlaybackState[^\n]*?state=(\d+)[^\n]*?position=(\d+)', match[1]) if match else None
    if state is None:
        raise AssertionError('Actual app media-session state and position unavailable')
    return {'state': int(state[1]), 'positionMs': int(state[2]),
            'audioWakeLockHeld': bool(re.search(r'PARTIAL_WAKE_LOCK[^\n]*Yfuse:background-audio', power))}


def lifecycle_evidence(out, label):
    raw = adb('shell', 'cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl', check=False)
    (out / (label + '-diagnostics.jsonl')).write_text(raw, encoding='utf-8')
    root = ET.fromstring(adb('shell', 'cat', '/data/user/0/com.yfuse/shared_prefs/yfuse.xml'))
    preference = root.find("./boolean[@name='player.backgroundAudio']")
    # Record only this synthetic fixture's setting, never the entire preference file.
    value = preference.get('value') if preference is not None else None
    (out / (label + '-preference.json')).write_text(json.dumps({'backgroundAudio': value}), encoding='utf-8')
    for kind, command in [('appops', ['cmd', 'appops', 'get', 'com.yfuse', 'PICTURE_IN_PICTURE']),
                          ('activity', ['dumpsys', 'activity', 'activities'])]:
        (out / (label + '-' + kind + '.txt')).write_text(adb('shell', *command, check=False), encoding='utf-8')
    return value


def require_state(out, label, state, held):
    deadline = time.monotonic() + 15
    while True:
        result = session(out, label)
        if result['state'] == state and result['audioWakeLockHeld'] == held:
            return result
        if time.monotonic() >= deadline:
            lifecycle_evidence(out, label)
            raise AssertionError('Audio state/lock mismatch: ' + repr(result))
        time.sleep(.5)


def return_to_player(out):
    original = (out / 'after-launch-activity.txt').read_text(encoding='utf-8')
    tasks = re.findall(r'com\.yfuse/(?:com\.yfuse)?\.feature\.player\.PlayerActivity\s+t(\d+)', original)
    if not tasks:
        raise AssertionError('Original player task unavailable for foreground recovery')
    adb('shell', 'input', 'keyevent', '224')
    adb('shell', 'wm', 'dismiss-keyguard')
    adb('shell', 'am', 'task', 'focus', tasks[0])
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        activities = adb('shell', 'dumpsys', 'activity', 'activities')
        if re.search(r'mResumedActivity[^\n]*PlayerActivity', activities):
            break
        time.sleep(.5)
    else:
        raise AssertionError('Original player did not return to the foreground')
    adb('shell', 'input', 'keyevent', '126')
    proof = direct.decode_playback('audio-foreground-return', None, 3600)
    (out / 'foreground-return-moving-picture.json').write_text(json.dumps(proof), encoding='utf-8')


def configure_audio_delay(out):
    direct.reveal()
    click('音轨', 3)
    capture(out, 'audio-delay-before', expected='音频同步')
    click('+200', 3)
    click('+200', 3)
    capture(out, 'audio-delay-selected', expected='延后 400 毫秒')
    adb('shell', 'input', 'keyevent', '4')


def main():
    engine.guard()
    core = os.environ['QA_ENGINE']
    delay_restore = core == 'SenPlayer' and os.environ.get('QA_AUDIO_DELAY_RESTORE') == 'true'
    pilot = os.environ.get('QA_POWER_PILOT') == 'true'
    out = Path('results/background-audio')
    out.mkdir(parents=True, exist_ok=True)
    fixture = Path(os.environ['RUNNER_TEMP']) / 'background-audio.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=24',
                    '-f', 'lavfi', '-i', 'sine=sample_rate=48000', '-t', '3600', '-c:v', 'libx264',
                    '-preset', 'ultrafast', '-crf', '35', '-c:a', 'aac', '-movflags', '+faststart', str(fixture)], check=True)
    server = MediaServer(fixture, out / 'media-requests.jsonl')
    recorder = None
    phases = []
    status = 'incomplete'
    try:
        adb('reverse', 'tcp:18389', 'tcp:18389')
        engine.configure(core)
        enable_audio()
        adb('shell', 'dumpsys', 'battery', 'unplug')
        adb('shell', 'dumpsys', 'battery', 'set', 'level', '80')
        adb('shell', 'dumpsys', 'battery', 'set', 'status', '3')
        adb('shell', 'dumpsys', 'batterystats', '--reset')
        initial = playing(core)
        if delay_restore:
            configure_audio_delay(out)
        if lifecycle_evidence(out, 'after-launch') != 'true':
            raise AssertionError('Background audio preference changed after player launch')
        if core == 'YCore':
            rows = [json.loads(line) for line in (out / 'after-launch-diagnostics.jsonl').read_text(encoding='utf-8').splitlines()
                    if line.startswith('{')]
            routes = [row.get('attributes', {}).get('route') for row in rows if row.get('event') == 'route_selected']
            if not routes or routes[-1] != 'SoftwareFallback':
                raise AssertionError('This fallback study requires the actual cloud software route')
        (out / 'engine-evidence.json').write_text(json.dumps(initial), encoding='utf-8')
        (out / 'conditions.json').write_text(json.dumps({
            'pilot': pilot, 'batterySimulated': True, 'levelSetpoint': 80, 'backgroundAudioEnabled': True,
            'pictureInPictureAppOp': 'deny', 'preferenceSetup': 'Fresh cloud app XML; not a UI setting test',
            'expectedBackgroundBehavior': 'Cloud YCore SoftwareFallback and SenPlayer do not advertise background audio: both pause',
            'scopeLimit': 'This fixture verifies unsupported-route fallback, not active hardware-route background audio',
            'audioDelayMs': 400 if delay_restore else 0,
            'audioProofLimit': 'Actual media-session positions and tagged wake lock, no physical audible-output or energy assertion',
        }, indent=2), encoding='utf-8')
        recorder = Recorder(out)
        schedule = [('foreground-playing', 600), ('background-audio-requested', 1080),
                    ('screenoff-audio-requested', 1080), ('background-paused', 600), ('foreground-return', 240)]
        for name, long_seconds in schedule:
            seconds = 45 if pilot else long_seconds
            row = {'phase': name, 'plannedSeconds': seconds, 'status': 'incomplete'}
            phases.append(row)
            if name == 'background-audio-requested':
                adb('shell', 'input', 'keyevent', '3')
            elif name == 'screenoff-audio-requested':
                adb('shell', 'input', 'keyevent', '223')
            elif name == 'background-paused':
                adb('shell', 'input', 'keyevent', '127')
            elif name == 'foreground-return':
                return_to_player(out)
            active = name in ('foreground-playing', 'foreground-return')
            held = active and name != 'foreground-playing'
            row['entryEvidence'] = require_state(out, name + '-entry', 3 if active else 2, held)
            if name == 'background-audio-requested':
                lifecycle_evidence(out, name + '-entry')
                events = (out / (name + '-entry-diagnostics.jsonl')).read_text(encoding='utf-8')
                if '"background_audio_unsupported"' not in events:
                    raise AssertionError('Missing actual route-capability fallback event')
            adb('shell', 'dumpsys', 'gfxinfo', 'com.yfuse', 'reset')
            began = time.monotonic()
            count = 0
            while time.monotonic() - began < seconds:
                recorder.capture(name, server.counters(), heavy=count % 4 == 0,
                                 intervention=time.monotonic() - began < 60)
                count += 1
                time.sleep(min(30, max(0, seconds - (time.monotonic() - began))))
            row['exitEvidence'] = require_state(out, name + '-exit', 3 if active else 2, held)
            lifecycle_evidence(out, name + '-exit')
            if name == 'foreground-return' and delay_restore:
                rows = [json.loads(line) for line in (out / (name + '-exit-diagnostics.jsonl')).read_text(encoding='utf-8').splitlines()
                        if line.startswith('{')]
                if not any(row.get('event') == 'audio_delay_restored' and row.get('attributes', {}).get('delayms') == '400'
                           for row in rows):
                    raise AssertionError('Original native session did not acknowledge restored400ms audio delay')
            delta = row['exitEvidence']['positionMs'] - row['entryEvidence']['positionMs']
            if (active and delta < seconds * 500) or (not active and abs(delta) > 1500):
                raise AssertionError('Actual session position did not match requested lifecycle: ' + str(delta))
            row.update(status='recorded', measuredSeconds=round(time.monotonic() - began, 2), positionDeltaMs=delta)
            recorder.finish(phases, 'incomplete')
            print('BACKGROUND_AUDIO', core, name, row['status'], flush=True)
        status = 'recorded'
    except Exception:
        lifecycle_evidence(out, 'failure')
        failure_diagnostics(out, 'audio-lifecycle-failure')
        raise
    finally:
        if recorder:
            recorder.finish(phases, status)
        adb('shell', 'am', 'force-stop', 'com.yfuse', check=False)
        adb('shell', 'input', 'keyevent', '224', check=False)
        adb('shell', 'dumpsys', 'battery', 'reset', check=False)
        server.close()


if __name__ == '__main__':
    main()
