"""Compare every fixture media request inside the observed touch and no-touch windows."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RAW = ROOT.parent / 'legacy-engine-20261113/power-ui-20261116/thumbnail-origin-control-46'
RUN = 44471505529


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def examine(folder, trial, core):
    origin = read(folder / 'origin-timeline.json')
    held = read(folder / 'thumbnail-held.json')
    identity = read(folder / 'engine.json')
    assert trial == origin['trial'] == held['trial']
    assert identity['active'] and identity['engine'] == core and held['engine'] == identity
    checkpoints = {point['name']: point for point in origin['checkpoints']}
    idle = trial == 'idle-stalled'
    low, high = ('no-touch-start', 'no-touch-end') if idle else ('finger-down', 'finger-up')
    pictures = ['base', 'paused-no-touch'] if idle else ['base', 'held-hot', 'held-cold', 'held-back', 'released']
    images = {n: hashlib.sha256((folder / (n + '.png')).read_bytes()).hexdigest() for n in pictures}
    begin, end = checkpoints[low], checkpoints[high]
    delta = {key: end['counters'][key] - begin['counters'][key] for key in ('GET', 'HEAD', 'bytes', 'image', 'range')}
    # Use the count at each boundary to distinguish events within the same clock millisecond.
    start_id = sum(begin['counters'][k] for k in ('GET', 'HEAD'))
    end_id = sum(end['counters'][k] for k in ('GET', 'HEAD'))
    requests = [v for v in origin['events'] if v['event'] == 'request']
    assert [v['id'] for v in requests] == list(range(1, len(requests) + 1))
    assert len(requests) == sum(origin['counters'][k] for k in ('GET', 'HEAD'))
    inside = [v for v in requests if start_id < v['id'] <= end_id]
    for method in ('GET', 'HEAD'):
        assert sum(v['method'] == method for v in inside) == delta[method]
    assert all(begin['atMs'] <= r['atMs'] <= end['atMs'] for r in inside)
    stalls = [v for v in origin['events'] if v['event'] == 'body-stalled']
    for request in inside:
        earlier = [event for event in stalls if event['atMs'] <= request['atMs']]
        if earlier:
            request['sinceLatestStallMs'] = request['atMs'] - earlier[-1]['atMs']
            request['latestStallOffset'] = earlier[-1]['offset']
    phases = {p['case']: p for p in held['phases']}
    packet_checks = all(phases[key]['ok'] for key in ('hot-packets-delivered', 'cold-packets-withheld'))
    checks = packet_checks and held['pauseConfirmed'] and 'paused-controls' in checkpoints
    if idle:
        checks = checks and (end['atMs'] - begin['atMs'] >= 40000 and held['stationaryPicture'] and
                             abs(held['startPTS'] - held['endingPTS']) <= .25)
    else:
        codes = {k: phases[k]['codes'][-1] for k in ('held-buffered', 'held-unbuffered', 'returned-to-buffer', 'released')}
        checks = (checks and all(p['ok'] for p in held['phases']) and
                  any(abs(v - held['hot']) <= 2.6 for v in codes['held-buffered']) and
                  any(abs(v - held['hot'] - 2) <= 2.6 for v in codes['returned-to-buffer']) and
                  not codes['held-unbuffered'] and not codes['released'])
        assert held['originRequestsWhileHeld'] == len(inside)
    return {'trial': trial, 'durationMs': end['atMs'] - begin['atMs'], 'delta': delta,
            'visualAndPacketChecks': bool(checks), 'checksPassed': bool(checks and (idle or not inside)),
            'requestsDuringWindow': inside, 'stalls': stalls, 'sourceCutoff': origin['cutoff'],
            'firstPicturePts': held['startPTS'], 'stationaryEndingPts': held.get('endingPTS'),
            'pngSha256': images, 'rawTimelineSha256': hashlib.sha256((folder / 'origin-timeline.json').read_bytes()).hexdigest()}


def main():
    old = read(ROOT / 'thumbnail-get-during-hold-46.json')
    owners = read(RAW / 'workflow-artifacts.json')
    assert owners['repo'] == 'zhuiyun/Yfuse' and owners['run'] == RUN
    index = {a['name']: a for a in owners['artifacts']}
    results = {}
    for folder in sorted(RAW.glob('origin-read-control-*')):
        core = folder.name.rsplit('-', 1)[-1]
        assert core in ('SenPlayer', 'YCore')
        owner = index[folder.name]
        receipt = read(folder / 'download-receipt.json')
        assert (owner['id'], owner['digest']) == (receipt['id'], receipt['digest'])
        assert (folder / 'apk.sha256').read_text().split()[0] == old['apkSha256']
        assert 'app=' + old['source'] in (folder / 'provenance.txt').read_text().splitlines()
        results[core] = [examine(folder if t == 'held-stalled' else folder / t, t, core)
                         for t in ('held-stalled', 'idle-stalled', 'held-paced')]
    report = {'run': RUN, 'source': old['source'], 'apkSha256': old['apkSha256'], 'engines': results,
              'previousRun': old['run'], 'previousSenPlayerExtraGets': old['additionalRequestsDuringHold']['GET'],
              'bothEnginesMeasured': set(results) == {'YCore', 'SenPlayer'}}
    (ROOT / 'thumbnail-paired-origin-46.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print({core: [(r['trial'], r['delta'], r['checksPassed']) for r in rows] for core, rows in results.items()})


if __name__ == '__main__':
    main()
