"""Separate slow startup, decoder rejection and long-duration touch/seek mapping."""
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import sys
import time
from short_tail import confirm as confirm_short_tail

sys.path.insert(0, 'qa-arm-matrix')
import engine
engine.guard()
sys.path.insert(0, 'qa-power-ui')
from media import MediaServer

spec = importlib.util.spec_from_file_location('playback_observer', 'qa-private-arm/test.py')
observer = importlib.util.module_from_spec(spec); spec.loader.exec_module(observer)
core = os.environ['QA_ENGINE']
output = Path('results'); output.mkdir(exist_ok=True)
observer.OUT = output
engine.configure(core)
observer.adb('reverse', 'tcp:18389', 'tcp:18389')
catalog = json.loads(Path('format-fixtures/catalog.json').read_text())
selected = set(filter(None, os.environ.get('QA_DIAGNOSTIC_CASES', '').split(',')))
if selected:
    assert selected <= {item['id'] for item in catalog}
    catalog = [item for item in catalog if item['id'] in selected]
results = []
completion_probe = os.environ.get('QA_COMPLETION_PROBE') == 'true'


def screenshot(name):
    observer.screen().save(output / (name + '.png'))


def native_failures(events):
    wanted = {e['time'] for e in events if e['event'] == 'senplayer_playback_failed'}
    failures = []
    if not wanted:
        return failures
    raw = observer.adb('shell', 'cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl', check=False)
    for line in raw.splitlines():
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if row.get('event') != 'senplayer_playback_failed' or row.get('timestamp') not in wanted:
            continue
        # Only public synthetic fixtures run here. Retain code and stack locations, never URLs or raw exception text.
        exception = row.get('exception') or {}
        stack = str(exception.get('stackTrace', ''))
        code = re.search(r'SenPlayer command failed \((-?\d+)\)', str(exception.get('message', '')) + '\n' + stack)
        frames = re.findall(r'(?m)^\s*at ([\w.$]+)\(([^\r\n()/:]{1,90})(?::(\d+))?\)', stack)
        failures.append({'time': row['timestamp'], 'nativeCommandCode': int(code[1]) if code else None,
                         'exceptionType': exception.get('type'), 'stack': frames[:12]})
    return failures


def observe_open(case, launch, duration, evidence):
    started = time.monotonic()
    observer.adb('shell', launch)
    if duration <= 32:
        response = observer.adb('shell', 'am', 'instrument', '-w', '-e', 'mode', 'observe-open',
                                'qa.yfuse.gesture/.Driver', timeout=18)
        receipt = re.search(r'INSTRUMENTATION_RESULT: tailFrames=(\[[^\n]+\])', response)
        evidence['openingFrames'] = json.loads(receipt[1]) if receipt else []
        result = confirm_short_tail(evidence['openingFrames'], None, duration)
        if result:
            result['wallSecondsFromLaunch'] = round(time.monotonic() - started, 3)
            if result['wallSecondsFromLaunch'] <= 60:
                return result
    remaining = 60 - (time.monotonic() - started)
    if remaining <= 0:
        raise AssertionError('No verified output within the original 60-second launch window')
    result = observer.decode_playback(case, None, duration, max_wait=remaining)
    result['wallSecondsFromLaunch'] = round(time.monotonic() - started, 3)
    if result['wallSecondsFromLaunch'] > 60:
        raise AssertionError('Moving output was first confirmed after the launch deadline')
    return result


def natural_completion(case, launch, duration):
    observer.adb('shell', 'am', 'force-stop', 'com.yfuse')
    prior = {(e['time'], e['event']) for e in observer.app_events()}
    evidence = {}
    opened = observe_open(case + '-natural-open', launch, duration, evidence)
    time.sleep(max(1, duration - opened['laterPos'] + 3))
    screenshot(case + '-natural-end')
    tree = observer.snapshot()
    labels = {n.get(attr, '') for n in tree.iter('node') for attr in ('text', 'content-desc', 'state')}
    events = [e for e in observer.app_events() if (e['time'], e['event']) not in prior]
    failed = native_failures(events)
    ended = any('重播' in s or '播放结束' in s for s in labels) or any(e['values'].get('phase') == 'ended' for e in events)
    result = {**evidence, 'opened': opened, 'endClock': observer.media_clock(tree), 'ended': ended,
              'errorVisible': any('播放遇到问题' in s or '播放失败' in s for s in labels), 'nativeFailures': failed}
    if ended and not result['errorVisible']:
        result['seekFromEnd'] = seek(case + '-natural-rewind', .21, 'adb-swipe', duration, known_ended=True)
        events = [e for e in observer.app_events() if (e['time'], e['event']) not in prior]
        result['nativeFailures'] = native_failures(events)
    return result


def confirm_identity(row, started):
    """Decoded pictures can arrive before SenPlayer's next five-second telemetry sample."""
    first = engine.engine_evidence(core, started)
    row['initialIdentity'] = first
    row['identity'] = first
    row['identityPolls'] = 1
    matching_libraries = (first['senOriginalLibrary'] and not first['ycoreLibrary'] if core == 'SenPlayer'
                          else first['ycoreLibrary'] and not first['senOriginalLibrary'])
    began = time.monotonic()
    while matching_libraries and not row['identity']['active'] and time.monotonic() - began < 12:
        time.sleep(.5)
        row['identity'] = engine.engine_evidence(core, started)
        row['identityPolls'] += 1
    row['identityWaitSeconds'] = round(time.monotonic() - began, 3)


def play_control(tree):
    return next((n for n in tree.iter('node') if n.get('content-desc') == '播放'
                 and n.get('visible') != 'false' and n.get('enabled') != 'false'), None)


def seek(case, fraction, method, duration, known_ended=False):
    rail, tree = observer.reveal()
    left, top, right, bottom = rail
    row = {'fraction': fraction, 'method': method, 'rawTargetSeconds': duration * fraction,
           'beforeClock': observer.media_clock(tree), 'beforeInputUnix': time.time()}
    row['clockWithinDuration'] = row['beforeClock'] is not None and 0 <= row['beforeClock'] <= duration
    row['fromEnded'] = known_ended or (
        (row['beforeClock'] or 0) >= duration - 1
        and (play_control(tree) is not None or observer.find(tree, ['重播', '播放结束']) is not None))
    short_tail = duration * (1 - fraction) <= 12
    if method == 'held-preview' or short_tail:
        mode = 'observed-seek' if method == 'held-preview' else 'short-swipe'
        response = observer.adb('shell', 'am', 'instrument', '-w', '-e', 'mode', mode,
            '-e', 'fraction', fraction, '-e', 'shortTail', str(short_tail).lower(), '-e', 'keepTailOpen',
            str(completion_probe).lower(), 'qa.yfuse.gesture/.Driver', timeout=40)
        (output / (case + '-input.txt')).write_text(response)
    if method == 'held-preview':
        match = re.search(r'INSTRUMENTATION_RESULT: observedSeek=(\{[^\n]+\})', response)
        if not match:
            (output / (case + '-driver-crash.txt')).write_text(observer.adb('logcat', '-d', '-b', 'crash', check=False))
            raise AssertionError('No held-preview input receipt')
        row['inputReceipt'] = json.loads(match[1])
        row['shownTargetSeconds'] = duration * row['inputReceipt']['shownFraction']
        observer.adb('pull', row['inputReceipt']['heldScreenshot'], str(output / (case + '-held.png')))
    elif not short_tail:
        observer.adb('shell', 'input', 'swipe',
            round(left + (right-left) * (.83 if fraction < .5 else .17)), (top+bottom)//2,
            round(left + (right-left) * fraction), (top+bottom)//2, 330)
    # The completed YCore timeline clears play intent. Verify the seek actually landed
    # while paused before tapping its normal Play control and observing moving output.
    boundary = row['beforeClock'] is not None and row['beforeClock'] >= duration - 1
    if (row['fromEnded'] or boundary) and core == 'YCore' and not short_tail:
        deadline = time.monotonic() + 6
        while time.monotonic() < deadline:
            _, after = observer.reveal()
            control = play_control(after)
            position = observer.media_clock(after)
            if control is not None and position is not None and abs(position - duration*fraction) <= 1.6:
                time.sleep(.7)
                _, after = observer.reveal()
                control = play_control(after)
                if control is None or observer.media_clock(after) != position:
                    continue
                screenshot(case + '-paused-at-requested-position')
                row['pausedAtTargetSeconds'] = position
                observer.tap(*observer.coords(control))
                row['resumeTapped'] = True
                break
            if not row['fromEnded'] and observer.find(after, ['暂停']) is not None:
                break
        if row['fromEnded'] and not row.get('resumeTapped'):
            row['rawTargetConfirmed'] = False
            screenshot(case + '-completed-seek-unconfirmed')
            return row
    if short_tail:
        receipt = re.search(r'INSTRUMENTATION_RESULT: tailFrames=(\[[^\n]+\])', response)
        row['shortTailSamples'] = json.loads(receipt[1]) if receipt else []
        row['clockWithinDuration'] &= all(0 <= sample['seconds'] <= duration for sample in row['shortTailSamples'])
        row['output'] = confirm_short_tail(row['shortTailSamples'], duration * fraction, duration)
        row['rawTargetConfirmed'] = row['output'] is not None
        if not row['rawTargetConfirmed']:
            screenshot(case + '-unconfirmed')
        return row
    try:
        row['output'] = observer.decode_playback(case, duration*fraction, duration)
        row['rawTargetConfirmed'] = True
    except (AssertionError, RuntimeError):
        row['rawTargetConfirmed'] = False
        screenshot(case + '-unconfirmed')
    return row


for media in catalog:
    case = core + '-' + media['id']
    row = {'case': case, 'fixture': media, 'openedWithin60Seconds': False, 'seeks': []}
    results.append(row)
    prior = set()
    launch = None
    mime = {'mp4': 'video/mp4', 'mkv': 'video/x-matroska', 'ts': 'video/mp2t',
            'mov': 'video/quicktime', 'webm': 'video/webm'}[media['file'].rsplit('.', 1)[1]]
    server = MediaServer(Path('format-fixtures') / media['file'], output / (case + '-http.jsonl'), content_type=mime)
    try:
        observer.adb('shell', 'am', 'force-stop', 'com.yfuse')
        started = time.time(); prior = {(e['time'], e['event']) for e in observer.app_events()}
        url = 'http://127.0.0.1:18389/' + media['file']
        launch = 'am start -a android.intent.action.VIEW -n com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity -t ' + mime + ' -d ' + shlex.quote(url)
        try:
            row['open'] = observe_open(case, launch, media['seconds'], row)
            row['openedWithin60Seconds'] = True
        except (AssertionError, RuntimeError):
            screenshot(case + '-60s')
            # Diagnostic continuation is recorded separately, never promoted to a 60s pass.
            try: row['lateOpen'] = observer.decode_playback(case+'-late', None, media['seconds'], max_wait=120)
            except (AssertionError, RuntimeError): pass
        confirm_identity(row, started)
        if row.get('open') or row.get('lateOpen'):
            screenshot(case + '-opened')
            duration = (row.get('open') or row['lateOpen'])['durationSeconds']
            for method in ('adb-swipe', 'held-preview'):
                for fraction in media.get('seekFractions', (.63, .21)):
                    row['seeks'].append(seek(case+'-'+method+'-'+str(fraction), fraction, method, duration))
    except Exception as error:
        row['errorType'] = type(error).__name__
        row['errorDetail'] = str(error)[:180]
    finally:
        row['diagnostics'] = [e for e in observer.app_events() if (e['time'], e['event']) not in prior]
        row['nativeFailures'] = native_failures(row['diagnostics'])
        if completion_probe and launch and row.get('open'):
            try:
                row['naturalCompletion'] = natural_completion(case, launch, media['seconds'])
            except (AssertionError, RuntimeError, TimeoutError) as error:
                row['naturalCompletion'] = {'errorType': type(error).__name__}
        observer.adb('shell', 'am', 'force-stop', 'com.yfuse')
        server.close()
        (output/'format-results.json').write_text(json.dumps(results, indent=2))
        print(case, row['openedWithin60Seconds'], sum(s.get('rawTargetConfirmed', False) for s in row['seeks']), flush=True)
passed = all(r['openedWithin60Seconds'] and r.get('identity', {}).get('active') and not r['nativeFailures'] and len(r['seeks']) == 4
             and all(s['rawTargetConfirmed'] and s['clockWithinDuration'] for s in r['seeks']) for r in results)
if completion_probe:
    passed = passed and all(r.get('naturalCompletion', {}).get('ended') and not r['naturalCompletion'].get('errorVisible')
                            and r['naturalCompletion'].get('seekFromEnd', {}).get('rawTargetConfirmed')
                            and r['naturalCompletion']['seekFromEnd'].get('clockWithinDuration')
                            and not r['nativeFailures'] and not r['naturalCompletion'].get('nativeFailures') for r in results)
(output/'summary.json').write_text(json.dumps({'passed': passed, 'core': core, 'fixtures': len(results),
    'scope': 'Synthetic diagnostic counterparts; never substitutes for failed private originals'}, indent=2))
raise SystemExit(0 if passed else 1)
