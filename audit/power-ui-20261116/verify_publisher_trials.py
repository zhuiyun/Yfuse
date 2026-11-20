"""Check publisher-source runtime identity, native pictures and conditional read recovery."""
import json
from pathlib import Path
from statistics import median

ROOT = Path(__file__).resolve().parent
RAW = ROOT.parent / 'legacy-engine-20261113/power-ui-20261116/publisher-original-49'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def main():
    build = read(ROOT / 'iteration-49-verification.json')
    bundles = {}
    for file in sorted(RAW.glob('original-startup-control-*/metrics.json')):
        folder = file.parent
        tag = folder.name.removeprefix('original-startup-control-')
        assert 'app=' + build['source'] in (folder / 'provenance.txt').read_text().splitlines()
        rows = read(file)['measurements']
        complete = [r for r in rows if r['case'].endswith('-complete') and r['ok']]
        picture = [r for r in rows if r['case'].endswith('-opened')]
        seeks = [r for r in rows if r['case'].endswith('-seek')]
        issued = [r for r in rows if r['case'].endswith('-issued')]
        identities = [r for r in rows if r['case'].endswith('-engine')]
        formats = [r for r in rows if r['case'].endswith('-format')]
        recorded = [r for r in rows if r['case'] == 'repeat-summary']
        assert len(recorded) <= 1 and len(seeks) <= len(issued)
        if recorded:
            assert recorded[0]['passedOpens'] == len(complete)
        assert all(r['active'] for r in identities)
        for r in picture + seeks:
            assert r['laterPos'] > r['firstPos'] and len(r['changes']) >= 2
        measured = {'artifact': read(folder / 'download-receipt.json')['digest'], 'source': build['source'],
                    'trialSummary': recorded, 'activeEngines': sorted({r['engine'] for r in identities}),
                    'completeSessions': len(complete), 'movingOpens': len(picture), 'touchesIssued': len(issued),
                    'seeksWithMovingPicture': len(seeks), 'observedFileBytes': sorted({r['bytes'] for r in formats}),
                    'medianFirstPictureSeconds': median(r['secToFirst'] for r in picture) if picture else None,
                    'playbackEvidence': file.relative_to(ROOT.parent).as_posix()}
        if (folder / 'body-timeout-trials.json').is_file():
            faults = read(folder / 'body-timeout-trials.json')
            for fault in faults:
                assert fault['observedTimeout'] and fault['sourceSha256'] == '91768e73427fb1e4a3d3a419cb173e7a9d97340190734c361ac56fe2bb6c8a0d'
                exact = [r for r in fault['conditionalRequests'] if r['start'] == fault['firstMissingByte'] and
                         r['end'] == fault['rangeEnd'] and r['tagMatched']]
                assert len(exact) == fault['exactSuffixRequests']
            measured['stalledReadTrials'] = faults
        if (folder / 'startup-range-trials.json').is_file():
            measured['pairedStartupTrials'] = read(folder / 'startup-range-trials.json')
        bundles[tag] = measured
    data = {'run': 44469148149, 'round': 49, 'source': build['source'], 'cases': bundles,
            'fiveBundlesAvailable': len(bundles) == 5,
            'notes': 'A session counts as complete only when the original run confirmed both target seeks. Previous-build paired trials retain their own identities.'}
    (ROOT / 'publisher-movie-trials-49.json').write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
    print({k: (v['completeSessions'], v['touchesIssued'], v['seeksWithMovingPicture']) for k, v in bundles.items()})


if __name__ == '__main__':
    main()
