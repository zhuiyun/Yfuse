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


def screenshot(name):
    observer.screen().save(output / (name + '.png'))


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


def seek(case, fraction, method, duration):
    rail, _ = observer.reveal()
    left, top, right, bottom = rail
    row = {'fraction': fraction, 'method': method, 'rawTargetSeconds': duration * fraction}
    short_tail = duration * (1 - fraction) <= 12
    if method == 'held-preview' or short_tail:
        mode = 'observed-seek' if method == 'held-preview' else 'short-swipe'
        response = observer.adb('shell', 'am', 'instrument', '-w', '-e', 'mode', mode,
            '-e', 'fraction', fraction, '-e', 'shortTail', str(short_tail).lower(), 'qa.yfuse.gesture/.Driver', timeout=40)
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
    if short_tail:
        receipt = re.search(r'INSTRUMENTATION_RESULT: tailFrames=(\[[^\n]+\])', response)
        row['shortTailSamples'] = json.loads(receipt[1]) if receipt else []
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
    mime = {'mkv': 'video/x-matroska', 'ts': 'video/mp2t', 'mov': 'video/quicktime'}.get(media['file'].rsplit('.', 1)[1], 'video/mp4')
    server = MediaServer(Path('format-fixtures') / media['file'], output / (case + '-http.jsonl'), content_type=mime)
    try:
        observer.adb('shell', 'am', 'force-stop', 'com.yfuse')
        started = time.time(); prior = {(e['time'], e['event']) for e in observer.app_events()}
        url = 'http://127.0.0.1:18389/' + media['file']
        observer.adb('shell', 'am start -W -a android.intent.action.VIEW -n com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity -t ' + mime + ' -d ' + shlex.quote(url))
        try:
            row['open'] = observer.decode_playback(case, None, media['seconds'], max_wait=60)
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
            for method, fraction in [('adb-swipe', .63), ('adb-swipe', .21), ('held-preview', .63), ('held-preview', .21)]:
                row['seeks'].append(seek(case+'-'+method+'-'+str(fraction), fraction, method, duration))
    except Exception as error:
        row['errorType'] = type(error).__name__
        row['errorDetail'] = str(error)[:180]
    finally:
        row['diagnostics'] = [e for e in observer.app_events() if (e['time'], e['event']) not in prior]
        observer.adb('shell', 'am', 'force-stop', 'com.yfuse')
        server.close()
        (output/'format-results.json').write_text(json.dumps(results, indent=2))
        print(case, row['openedWithin60Seconds'], sum(s.get('rawTargetConfirmed', False) for s in row['seeks']), flush=True)
passed = all(r['openedWithin60Seconds'] and r.get('identity', {}).get('active') and len(r['seeks']) == 4
             and all(s['rawTargetConfirmed'] for s in r['seeks']) for r in results)
(output/'summary.json').write_text(json.dumps({'passed': passed, 'core': core, 'fixtures': len(results),
    'scope': 'Synthetic diagnostic counterparts; never substitutes for failed private originals'}, indent=2))
raise SystemExit(0 if passed else 1)
