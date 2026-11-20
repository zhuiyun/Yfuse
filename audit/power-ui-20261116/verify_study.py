"""Verify both four-hour cloud recordings from the same APK and export per-phase data."""
import argparse
import csv
import json
from pathlib import Path

from build_activity_charts import plot
from thread_activity import export, matched_thread_intervals, phase_summary
from verify_four_hour import verify


ROOT = Path(__file__).resolve().parent
EVIDENCE = ROOT.parent / 'legacy-engine-20261113' / 'power-ui-20261116'


def read(file):
    return json.loads(file.read_text(encoding='utf8'))


def write(file, value):
    file.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf8')


def verify_study(round_no, run, qualification, source, apk):
    folder = EVIDENCE / f'power-final-{round_no}'
    records, chart_metadata = [], []
    for core in ('YCore', 'SenPlayer'):
        evidence = folder / ('power-baseline-' + core)
        receipt = read(evidence / 'download-receipt.json')
        assert receipt['run'] == run and receipt['source'] == source and receipt['name'] == evidence.name
        build = read(evidence / 'baseline-build-run.json')
        assert build['id'] == qualification and build['conclusion'] == 'success'
        record = verify(evidence, source, core)
        assert record['apkSha256'] == apk
        records.append(record)
        chart_input = evidence / 'power' / 'summary.json'
        samples = [json.loads(line) for line in chart_input.with_name('samples.jsonl').read_text('utf8').splitlines() if line]
        summary = read(chart_input)
        if run == 44421851937 or any(row.get('entryEvidence', {}).get('continuedLibrarySession') for row in summary['phases']):
            entries = {row['phase']:row['entryEvidence'] for row in summary['phases']}
            play, pause, seek = (entries[n] for n in ('playing','paused','seeking'))
            assert play['source'] == seek['source'] == 'normal library detail and PlaybackInfo'
            assert seek['continuedLibrarySession'] is True and play['processId'].isdigit()
            assert play['processId'] == seek['processId'] and seek['pausedPosition'] == pause['positionAfter']
            assert seek['movingPicture']['firstPos'] >= max(0, seek['pausedPosition']-2)
            if core == 'SenPlayer':
                expected = [640,360,1280,720,1280,720]
                assert all(expected in entry['surfaceDimensions'] for entry in (play,seek))
            record['libraryPlayback'] = {'sameProcessAndMedia':True, 'pausedPosition':seek['pausedPosition'],
                                         'advancedTo':seek['movingPicture']['laterPos'],
                                         'surfaceDimensions':play.get('surfaceDimensions')}
        if any('threadCpuSnapshot' in row for row in samples):
            hz = summary['clockTicksPerSecond']
            intervals = list(matched_thread_intervals(samples, hz, core))
            if intervals:
                relative = f'thread-cpu-{round_no}-{core}.csv'
                export(intervals, hz, ROOT / relative)
                record['threadCpu'] = {'csv': relative, 'quietIntervals': len(intervals),
                                       'phasesWithMatchedIntervals': sorted({v['phase'] for v in intervals}),
                                       'phases': phase_summary(intervals, hz),
                                       'scope': 'CPU ticks for identically named threads with the same process, TID and start time at both snapshots. Excludes user interventions, short-lived threads and other processes.'}
        chart_metadata.append(plot(chart_input, samples, summary))
    document = {'run': run, 'qualificationRun': qualification, 'source': source, 'apkSha256': apk, 'results': records}
    write(ROOT / f'four-hour-verification-{round_no}.json', document)
    rows = [{'core': r['core'], 'run': run, 'source': source, **p} for r in records for p in r['phases']]
    with (ROOT / f'activity-by-phase-{round_no}.csv').open('w', encoding='utf-8-sig', newline='') as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    index = ROOT / 'service-counter-index.json'
    merged = {entry['evidence']: entry for entry in read(index)}
    merged.update({entry['evidence']: entry for entry in chart_metadata})
    write(index, sorted(merged.values(), key=lambda e: e['evidence']))
    print([(r['core'], round(r['durationSeconds']/60, 1), r['samples']) for r in records])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('round', type=int)
    parser.add_argument('run', type=int)
    parser.add_argument('qualification', type=int)
    parser.add_argument('source')
    parser.add_argument('apk')
    args = parser.parse_args()
    verify_study(args.round, args.run, args.qualification, args.source, args.apk)
