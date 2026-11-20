"""Summarize verified cloud profiling evidence separately from the timed power study."""
import argparse
from collections import defaultdict
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
EVIDENCE = ROOT.parent / 'legacy-engine-20261113' / 'power-ui-20261116'


def summarize(folder, core, run, source, apk):
    def read(name):
        return json.loads((folder / name).read_text(encoding='utf-8'))

    receipt = read('download-receipt.json')
    assert receipt['run'] == run and receipt['source'] == source and receipt['name'] == folder.name
    assert 'app=' + source in (folder / 'provenance.txt').read_text().splitlines()
    assert (folder / 'apk.sha256').read_text().split()[0] == apk
    profile = read('power/playback-cpu-profile.json')
    assert profile['core'] == core and profile['outsideTimedStudy'] and profile['event'] == 'task-clock:u'
    row = {'core': core, 'stage': folder.name.split('-')[1], 'status': profile['status'],
           'archiveDigest': receipt['digest'], 'androidEglDriver': profile['androidEglDriver'],
           'totalSamples': profile.get('totalSamples'), 'durationSeconds': profile['durationSeconds'],
           'frequencyHz': profile['frequencyHz']}
    if profile['status'] == 'recorded':
        proof = profile.get('pictureAfterSampling', {})
        assert proof.get('laterPos', 0) > proof.get('firstPos', 0) and len(proof['changes']) >= 2
        per_library, per_thread, worker_libraries = defaultdict(float), defaultdict(float), defaultdict(float)
        for group in profile['groups']:
            value = group['percentOfSampledAppCpu']
            assert 0 <= value <= 100
            per_library[group['library']] += value
            per_thread[group['thread']] += value
            if group['thread'] in {'Thread<00>', 'Thread<01>', 'Thread<02>', 'Thread<03>'}:
                worker_libraries[group['library']] += value
        assert abs(sum(per_library.values()) - 100) <= .01 * len(profile['groups'])
        def shares(values):
            return {k: round(v, 2) for k, v in sorted(values.items(), key=lambda i: -i[1])}
        row.update(libraryShares=shares(per_library), threadShares=shares(per_thread),
                   fourWorkerLibraryShares=shares(worker_libraries), pictureAfterSampling=proof)
    size_file = folder / 'power/output-size-cpu.json'
    if size_file.is_file():
        experiment = read('power/output-size-cpu.json')
        assert experiment['core'] == core and experiment['outsideTimedStudy']
        if experiment['status'] == 'recorded':
            windows = experiment['windows']
            assert [w['naturalDisplayOverride'] for w in windows] == [[720,1280],[360,640],[360,640],[720,1280]]
            actual = [w['surfaceViewSizes'] for w in windows]
            assert all(actual) and actual[0] == actual[3] and actual[1] == actual[2]
            assert max(w*h for w,h in actual[0]) >= 3 * max(w*h for w,h in actual[1])
            for w in windows:
                assert w['start']['identity']['active'] and w['start']['identity']['engine'] == core
                assert w['elapsedSeconds'] >= 30 and w['mediaAdvanceSeconds'] >= w['elapsedSeconds'] * .75
                assert w['end']['laterPos'] > w['end']['firstPos'] and len(w['end']['changes']) >= 2
                assert all(w['cpuPercentOneGuestCore'][name] is not None for name in ['com.yfuse','surfaceflinger'])
        row['outputSizeComparison'] = experiment
    return row


def export(round_no, run, source, apk):
    results = []
    for core in ('YCore', 'SenPlayer'):
        for study in ('pilot', 'baseline'):
            folder = EVIDENCE / f'power-final-{round_no}' / f'power-{study}-{core}'
            if (folder / 'power/playback-cpu-profile.json').is_file():
                results.append(summarize(folder, core, run, source, apk))
    assert results
    value = {'run': run, 'source': source, 'apkSha256': apk, 'profiles': results,
             'scope': 'Percentages are shares of sampled userspace CPU time inside com.yfuse, during a separate 20-second foreground interval after the timed study. Unresolved library entries keep their original label.'}
    (ROOT / f'playback-cpu-{round_no}.json').write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('round', type=int)
    parser.add_argument('run', type=int)
    parser.add_argument('source')
    parser.add_argument('apk')
    args = parser.parse_args()
    for profile in export(args.round, args.run, args.source, args.apk):
        print(profile['stage'], profile['core'], profile['status'])
