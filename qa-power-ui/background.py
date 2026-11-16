"""Isolate paused/background diagnostic polling from actual decoding work."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import time

from power import engine, adb, screen, playing, pause
from media import MediaServer
from telemetry import Recorder


def diagnostics(out, phase, began):
    raw = adb('shell', 'cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl', check=False)
    (out / (phase + '-diagnostics.jsonl')).write_text(raw, encoding='utf-8')
    rows = []
    readable = False
    for line in raw.splitlines():
        try:
            row = json.loads(line)
            timestamp = datetime.fromisoformat(row['timestamp'].replace('Z', '+00:00')).timestamp()
            readable = True
            if timestamp >= began and row.get('category') == 'player.performance' and row.get('event') == 'process_memory':
                rows.append({'timestamp': row['timestamp'], 'event': row['event']})
        except (ValueError, KeyError, TypeError):
            pass
    return rows if readable else None


def main():
    engine.guard()
    core = os.environ['QA_ENGINE']
    out = Path('results/background')
    out.mkdir(parents=True, exist_ok=True)
    fixture = Path(os.environ['RUNNER_TEMP']) / 'background.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=24',
                    '-f', 'lavfi', '-i', 'sine=sample_rate=48000', '-t', '600', '-c:v', 'libx264',
                    '-preset', 'ultrafast', '-crf', '35', '-c:a', 'aac', '-movflags', '+faststart', str(fixture)], check=True)
    server = MediaServer(fixture, out / 'media-requests.jsonl')
    adb('reverse', 'tcp:18389', 'tcp:18389')
    engine.configure(core)
    evidence = playing(core)
    recorder = Recorder(out)
    phases = []
    status = 'incomplete'
    try:
        for name, seconds in [('foreground-playing', 60), ('foreground-paused', 120),
                              ('background-paused', 180), ('screenoff-paused', 180), ('force-stopped', 120)]:
            if name == 'foreground-paused':
                pause()
            elif name == 'background-paused':
                adb('shell', 'input', 'keyevent', '3')
            elif name == 'screenoff-paused':
                adb('shell', 'input', 'keyevent', '223')
            elif name == 'force-stopped':
                adb('shell', 'am', 'force-stop', 'com.yfuse')
            began = time.monotonic()
            stamp = datetime.now(timezone.utc).timestamp()
            print('BACKGROUND_PHASE', core, name, seconds, flush=True)
            screen().save(out / (name + '.png'))
            row = {'phase': name, 'plannedSeconds': seconds, 'status': 'incomplete', 'startedEpoch': stamp}
            phases.append(row)
            first_sample = True
            while time.monotonic() - began < seconds:
                elapsed = time.monotonic() - began
                recorder.capture(name, server.counters(), heavy=first_sample, intervention=elapsed < 30)
                first_sample = False
                time.sleep(min(30, max(0, seconds - (time.monotonic() - began))))
            row['diagnosticEvents'] = diagnostics(out, name, stamp)
            row['memorySamplesWhileInPhase'] = len(row['diagnosticEvents']) if row['diagnosticEvents'] is not None else None
            row['measuredSeconds'] = round(time.monotonic() - began, 2)
            row['status'] = 'recorded'
            recorder.finish(phases, 'incomplete')
        status = 'recorded'
    finally:
        recorder.finish(phases, status)
        (out / 'engine-evidence.json').write_text(json.dumps(evidence), encoding='utf-8')
        adb('shell', 'input', 'keyevent', '224', check=False)
        server.close()


if __name__ == '__main__':
    main()
