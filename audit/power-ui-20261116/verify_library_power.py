"""Check the normal-library four-hour runs against their qualified APK and raw Android dumps."""
import argparse
from collections import Counter
import csv
import json
from pathlib import Path

import battery_components
from build_activity_charts import EVIDENCE, ROOT, plot
from thread_activity import export, matched_thread_intervals, phase_summary
from verify_four_hour import verify


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def save(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def library_session(phases, core):
    entries = {p['phase']: p['entryEvidence'] for p in phases}
    start, pause, resume = (entries[name] for name in ('playing', 'paused', 'seeking'))
    assert start['source'] == resume['source'] == 'normal library detail and PlaybackInfo'
    assert start['identity']['engine'] == resume['identity']['engine'] == core
    assert resume['continuedLibrarySession'] is True
    assert start['processId'].isdigit() and start['processId'] == resume['processId']
    paused = pause['positionAfter']
    assert abs(resume['pausedPosition'] - paused) <= 1
    picture = resume['movingPicture']
    assert paused - 2 <= picture['firstPos'] < picture['laterPos']
    assert picture['laterPos'] <= paused + picture['secToConfirm'] + 3
    if core == 'SenPlayer':
        assert [640, 360, 1280, 720, 1280, 720] in start['surfaceDimensions']
        assert resume['surfaceDimensions'] == start['surfaceDimensions']
    return {'origin': start['source'], 'sameProcess': True, 'sameRequestedPlayer': True,
            'pausePosition': paused, 'resumeFirstPosition': picture['firstPos'],
            'resumeLaterPosition': picture['laterPos'], 'surfaceDimensions': start.get('surfaceDimensions')}


def library_requests(root, samples, phases):
    requests = [json.loads(line) for line in (root / 'library-http.jsonl').read_text(encoding='utf-8').splitlines()]
    for phase in phases:
        points = [v['endpoint'].get('libraryRequests') for v in samples if v['phase'] == phase['phase']]
        assert points and all(x is not None and 0 <= x <= len(requests) for x in points)
        assert all(a <= b for a, b in zip(points, points[1:]))
        observed = requests[points[0]:points[-1]]
        counts = Counter((v['method'], v['route'], v['status']) for v in observed)
        phase['controlledLibraryRequestsDuringSampledInterval'] = len(observed)
        phase['controlledLibraryRoutes'] = [dict(method=m, route=r, status=s, count=n) for (m,r,s),n in counts.items()]
        shapes = Counter(json.dumps(v['catalogShape'], sort_keys=True) for v in observed if 'catalogShape' in v)
        phase['catalogQueryShapes'] = [{'shape': json.loads(key), 'count': count} for key, count in shapes.items()]
        phase['serverInfoProbes'] = sum(n for (m,r,s),n in counts.items() if m == 'GET' and r == '/System/Info')
        phase['libraryRequestBurstsOverFiveSecondsApart'] = sum(
            i == 0 or v['time'] - observed[i - 1]['time'] > 5 for i, v in enumerate(observed))


def engine_record(folder, run, round_no, package, qualification, core):
    receipt = read(folder / 'download-receipt.json')
    assert (receipt['run'], receipt['source'], receipt['name']) == (run, package['source'], 'power-baseline-' + core)
    build = read(folder / 'baseline-build-run.json')
    assert build['id'] == qualification['qualificationRun'] and build['conclusion'] == 'success'
    verified = verify(folder, package['source'], core)
    assert verified['apkSha256'] == package['apkSha256']
    root = folder / 'power'
    summary = read(root / 'summary.json')
    samples = [json.loads(line) for line in (root / 'samples.jsonl').read_text(encoding='utf-8').splitlines()]
    verified['normalLibrarySession'] = library_session(summary['phases'], core)
    verified['artifactId'], verified['artifactDigest'] = receipt['id'], receipt['digest']
    library_requests(root, samples, verified['phases'])
    hz = summary['clockTicksPerSecond']
    intervals = list(matched_thread_intervals(samples, hz, core))
    thread_file = f'thread-cpu-{round_no}-{core}.csv'
    export(intervals, hz, ROOT / thread_file)
    matched = phase_summary(intervals, hz)
    verified['threadCpu'] = {'csv': thread_file, 'quietIntervals': len(intervals),
                             'phasesWithMatchedIntervals': list(matched), 'phases': matched}
    snapshots, _ = battery_components.verify_record(verified)
    verified['independentlyParsedBatteryDumps'] = len(snapshots)
    chart = plot(root / 'summary.json', samples, summary)
    index_file = ROOT / 'service-counter-index.json'
    index = [v for v in read(index_file) if v['evidence'] != chart['evidence']]
    save(index_file, sorted([*index, chart], key=lambda v: v['evidence']))
    verified['curveFile'] = 'long-curves/' + chart['curve']
    verified['curveCsv'] = str(Path(verified['curveFile']).with_suffix('.csv')).replace('\\', '/')
    return verified


def study(round_no, run):
    package = read(ROOT / f'package-verification-{round_no}.json')
    qualification = read(ROOT / f'iteration-{round_no}-verification.json')
    assert qualification['source'] == package['source'] and qualification['apkSha256'] == package['apkSha256']
    parent = EVIDENCE / f'power-final-{round_no}'
    results = []
    for core in ('YCore', 'SenPlayer'):
        folder = parent / ('power-baseline-' + core)
        if not (folder / 'download-receipt.json').is_file():
            continue
        local = ROOT / f'library-power-{round_no}-{core}.json'
        result = read(local) if local.is_file() else None
        receipt = read(folder / 'download-receipt.json')
        if (not result or result.get('artifactDigest') != receipt['digest'] or 'serverInfoProbes' not in result['phases'][0]
                or result['source'] != package['source'] or result['apkSha256'] != package['apkSha256']
                or not all((ROOT / result[k]).is_file() for k in ('curveCsv', 'curveFile'))):
            result = engine_record(folder, run, round_no, package, qualification, core)
            save(local, result)
        assert receipt['run'] == run and result['verified'] and result['core'] == core
        results.append(result)
    if not results:
        return
    data = {'run': run, 'qualificationRun': qualification['qualificationRun'], 'source': package['source'],
            'apkSha256': package['apkSha256'], 'bothEnginesVerified': len(results) == 2, 'results': results}
    save(ROOT / f'four-hour-verification-{round_no}.json', data)
    rows = [{'engine': r['core'], **phase, 'controlledLibraryRoutes': json.dumps(phase['controlledLibraryRoutes']),
             'catalogQueryShapes': json.dumps(phase.get('catalogQueryShapes', []))}
            for r in results for phase in r['phases']]
    with (ROOT / f'activity-by-phase-{round_no}.csv').open('w', encoding='utf-8-sig', newline='') as out:
        writer = csv.DictWriter(out, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)
    if data['bothEnginesVerified']:
        battery_components.make(round_no)
    print([(r['core'], r['durationSeconds'], r['samples'], r['independentlyParsedBatteryDumps']) for r in results])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('round', type=int)
    parser.add_argument('run', type=int)
    args = parser.parse_args()
    study(args.round, args.run)
