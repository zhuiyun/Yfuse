"""Compare the file and range-HTTP forms of one synthetic clock on one cloud guest."""
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from media import MediaServer


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('This instrumentation requires the disposable cloud guest')
    spec = importlib.util.spec_from_file_location('playback_observer', 'qa-private-arm/test.py')
    observer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(observer)
    adb = observer.adb
    results = Path('results')
    device_dir = '/sdcard/Android/data/com.yfuse/files'
    server = MediaServer(Path('study/buffer-clock.mp4'), results / 'clock-http.jsonl')
    outcomes = {}
    try:
        adb('reverse', 'tcp:18389', 'tcp:18389')
        for transport in ('file', 'http'):
            adb('shell', 'am', 'force-stop', 'com.yfuse')
            before = {(e['time'], e['event']) for e in observer.app_events()}
            traffic = server.counters()
            log = results / f'instrumentation-{transport}.txt'
            with log.open('w') as stream:
                command = ['adb', 'shell', 'am', 'instrument', '-w', '-r',
                           '-e', 'senBufferStudy', 'true', '-e', 'senBufferTransport', transport,
                           '-e', 'class', 'com.yfuse.feature.player.SenPlayerBufferCostInstrumentedTest',
                           'com.yfuse.test/androidx.test.runner.AndroidJUnitRunner']
                try:
                    exit_code = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=480).returncode
                except subprocess.TimeoutExpired:
                    exit_code = 'timeout'
                    adb('shell', 'am', 'force-stop', 'com.yfuse')
            checked = subprocess.run([sys.executable, 'qa-integrated/check-instrumentation.py', log, '1'])
            events = [e for e in observer.app_events() if (e['time'], e['event']) not in before]
            (results / f'events-{transport}.json').write_text(json.dumps(events, indent=2))
            for name in adb('shell', 'ls', device_dir).splitlines():
                if (name == f'sen-buffer-{transport}.json' or
                        re.fullmatch(rf'buffer-{transport}-[0-3](?:-before)?-(surface|display)\.png', name)):
                    adb('pull', device_dir + '/' + name, str(results / name))
            used = server.counters()['bytes'] - traffic['bytes']
            record = results / f'sen-buffer-{transport}.json'
            windows = json.loads(record.read_text()) if record.exists() else []
            outcomes[transport] = {
                'instrumentationExit': exit_code, 'caseCheckExit': checked.returncode,
                'httpBytes': used, 'recordedWindows': len(windows),
                'passed': exit_code == checked.returncode == 0 and
                          (used > 0 if transport == 'http' else used == 0) and
                          [row['videoBufferWidth'] for row in windows] == [1280, 640, 640, 1280],
            }
            (results / 'transport-outcomes.json').write_text(json.dumps(outcomes, indent=2))
    finally:
        server.close()
    if len(outcomes) != 2 or not all(v['passed'] for v in outcomes.values()):
        raise SystemExit('One or more source transports lacked four verified picture windows')


if __name__ == '__main__':
    main()
