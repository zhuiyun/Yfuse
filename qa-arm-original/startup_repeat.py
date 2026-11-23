"""Repeat the public Charge cold open and two original-sequence seeks on disposable ARM Android."""
import json
import os
from pathlib import Path
import tempfile

import original_volume as volume


def main():
    volume.engine.guard()
    if volume.direct.adb('get-serialno').strip() != '127.0.0.1:15555':
        raise RuntimeError('The cold-open comparison requires the local cloud Android transport')
    volume.direct.adb('reverse', 'tcp:18389', 'tcp:18389')
    root = volume.OUT
    item = next(entry for entry in json.loads(Path('qa-titles/catalog.json').read_text())
                if entry['id'] == 'blender-04da454b-9893-4184-98f3-248d00625efe')
    item = dict(item, mime='video/mp4')
    results = []
    with tempfile.TemporaryDirectory(dir=os.environ['RUNNER_TEMP']) as tmp:
        path = Path(tmp) / 'original.mp4'
        original = volume.reference_file(item, path)
        if (original['sha256'] != '908bb9704ade1e7d6359d3977d48a2c712daf3ac55dd1242adb75096701b6095'
                or original['bytes'] != 100635721):
            raise ValueError('The publisher file differs from the original Charge cold-open failure')
        reference = volume.OriginalFrames(path, original['video'])
        for repeat in range(6):
            order = ('SenPlayer', 'YCore') if repeat % 2 == 0 else ('YCore', 'SenPlayer')
            for core in order:
                folder = root / f'{repeat + 1}-{core}'
                folder.mkdir()
                volume.OUT = volume.direct.OUT = folder
                volume.direct.ROWS.clear()
                passed = volume.exercise(item, original, path, core, 2, reference)
                record = json.loads((folder / f"{item['id']}-{core}.json").read_text())
                results.append({'repeat': repeat + 1, 'core': core, 'confirmedOpen': bool(record['sessions']),
                                'confirmedSeeks': record['confirmed'], 'plannedSeeks': 2, 'passed': passed,
                                'evidence': folder.name})
                volume.write(root / 'cold-starts.json', {'app': os.environ['QA_APP_SHA'],
                             'bytes': original['bytes'], 'sha256': original['sha256'], 'trials': results})
    raise SystemExit(0 if len(results) == 12 and all(r['passed'] for r in results) else 1)


if __name__ == '__main__':
    main()
