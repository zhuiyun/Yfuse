"""Observe scheduled work with a known simulated charge state, without media playback."""
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import time

from power import engine, adb, screen
from telemetry import Recorder


def work_names(output, stage):
    """Copy only a disposable guest's scheduler database; export worker/job identities."""
    base = '/data/user/0/com.yfuse/no_backup/androidx.work.workdb'
    result = {'stage': stage, 'available': False, 'workers': [], 'systemIds': []}
    with tempfile.TemporaryDirectory(prefix='yfuse-workdb-', dir=os.environ['RUNNER_TEMP']) as folder:
        destination = Path(folder) / 'work.db'
        for suffix in ('', '-wal', '-shm'):
            subprocess.run(['adb', 'pull', base + suffix, str(destination) + suffix],
                           capture_output=True, timeout=20, check=False)
        try:
            connection = sqlite3.connect(str(destination))
            try:
                result['workers'] = [list(row) for row in connection.execute(
                    'select id, worker_class_name, state, interval_duration, run_attempt_count from WorkSpec')]
                result['systemIds'] = [list(row) for row in connection.execute(
                    'select work_spec_id, system_id from SystemIdInfo')]
                result['available'] = True
            finally:
                connection.close()
        except sqlite3.Error as error:
            result['error'] = type(error).__name__
    (output / (stage + '-work-identities.json')).write_text(json.dumps(result, indent=2), encoding='utf-8')


def main():
    engine.guard()
    output = Path('results/background-jobs')
    output.mkdir(parents=True, exist_ok=True)
    phases = []
    status = 'incomplete'
    # This is an experimental input, not a measured charge level or battery discharge.
    adb('shell', 'dumpsys', 'battery', 'unplug')
    adb('shell', 'dumpsys', 'battery', 'set', 'level', '80')
    adb('shell', 'dumpsys', 'battery', 'set', 'status', '3')
    adb('shell', 'dumpsys', 'batterystats', '--reset')
    adb('shell', 'dumpsys', 'batterystats', '--enable', 'full-wake-history')
    try:
        adb('shell', 'am', 'start', '-W', '-n', 'com.yfuse/com.yfuse.MainActivity')
        time.sleep(12)
        screen().save(output / 'initial-page.png')
        recorder = Recorder(output)
        (output / 'conditions.json').write_text(json.dumps({
            'batteryLevelIsSetpoint': True, 'simulatedPercent': 80, 'chargingSetpoint': False,
            'playback': False, 'profile': 'fresh install with no configured server or follows',
            'controlledHttpEndpointAvailable': False,
            'networkScope': 'Raw UID netstats retained; no fabricated fixture traffic totals.',
            'limit': 'ADB sampling can wake the guest; not natural physical suspend.',
        }, indent=2), encoding='utf-8')
        work_names(output, 'initial')
        for name, duration in [('foreground-idle', 120), ('background-idle', 2100), ('screen-off', 2100)]:
            if name == 'background-idle':
                adb('shell', 'input', 'keyevent', '3')
            elif name == 'screen-off':
                adb('shell', 'input', 'keyevent', '223')
            began = time.monotonic()
            row = {'phase': name, 'plannedSeconds': duration, 'status': 'incomplete'}
            phases.append(row)
            count = 0
            print('BACKGROUND_JOBS', name, duration, flush=True)
            while time.monotonic() - began < duration:
                recorder.capture(name, {'requests': None, 'bytes': None}, heavy=count % 4 == 0,
                                 intervention=time.monotonic() - began < 60)
                count += 1
                time.sleep(min(30, max(0, duration - (time.monotonic() - began))))
            row.update(status='recorded', measuredSeconds=round(time.monotonic() - began, 2))
            work_names(output, name)
            recorder.finish(phases, 'incomplete')
        status = 'recorded'
    finally:
        if 'recorder' in locals():
            recorder.finish(phases, status)
        adb('shell', 'dumpsys', 'battery', 'reset', check=False)
        adb('shell', 'input', 'keyevent', '224', check=False)
    if status != 'recorded':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
