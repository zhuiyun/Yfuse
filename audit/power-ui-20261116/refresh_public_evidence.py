"""Aggregate downloaded, source-checked cloud records without erasing earlier failures."""
from pathlib import Path
import collections
import datetime
import json
import statistics

ROOT = Path(__file__).resolve().parent
BASE = ROOT.parent / 'legacy-engine-20261113' / 'power-ui-20261116'
PUBLIC = BASE / 'latest-public-14'
SOURCE = '8a47d4fa983adaa222fb8b59fee380523ff54a0a'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def save(name, value):
    (ROOT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def provenance(directory):
    assert 'app=' + SOURCE in (directory / 'provenance.txt').read_text().splitlines()
    digest = (directory / 'apk.sha256').read_text().split()[0]
    assert len(digest) == 64
    return digest


def volume():
    aggregate = {}
    hashes = set()
    for core in ['YCore', 'SenPlayer']:
        shards = []
        latencies = []
        for path in sorted(PUBLIC.glob('integrated-' + core + '-*/volume.json')):
            hashes.add(provenance(path.parent))
            data = read(path)
            rows = [row for file in data['files'] for row in file['rows']]
            assert len(rows) == data['issued']
            assert sum(row.get('ok') is True for row in rows) == data['verified'] == data['planned']
            assert all(file['open'] == 'passed' and len(file['sessions']) == 5 for file in data['files'])
            assert all(session['identity']['active'] and session['identity']['engine'] == core
                       for file in data['files'] for session in file['sessions'])
            latencies.extend(row['ms'] for row in rows)
            shards.append({'shard': data['shard'], 'verified': data['verified'],
                           'opens': sum(len(file['sessions']) for file in data['files']),
                           'titleIds': [file['titleID'] for file in data['files']]})
        ordered = sorted(latencies)
        aggregate[core] = {'shards': len(shards), 'verifiedSeeks': sum(s['verified'] for s in shards),
                           'opens': sum(s['opens'] for s in shards),
                           'uniqueWorks': len({t for s in shards for t in s['titleIds']}),
                           'confirmationLatencyMs': {'median': statistics.median(ordered),
                                                     'p95': ordered[int((len(ordered)-1)*.95)],
                                                     'maximum': max(ordered)}, 'raw': shards}
    assert len(hashes) == 1
    result = {'updatedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'source': SOURCE, 'run': 43994190136, 'apkSha256': hashes.pop(),
              'scope': 'Prepared/transcoded public clips; original file formats are separate.',
              'complete': all(v['shards'] == 16 and v['verifiedSeeks'] == 13760 for v in aggregate.values()),
              'cores': aggregate}
    save('latest-public-volume-partial.json', result)
    return result


def originals():
    aggregate = {}
    for core in ['YCore', 'SenPlayer']:
        measurements = []
        shards = []
        for path in sorted(PUBLIC.glob('integrated-original-' + core + '-*/metrics.json')):
            replacement = BASE / 'original-retry-16' / path.parent.name / path.name
            selected = replacement if replacement.exists() else path
            provenance(selected.parent)
            measurements.extend(read(selected)['measurements'])
            shards.append(str(selected.relative_to(BASE)))
        formats = [m for m in measurements if m.get('case', '').endswith('-format')]
        aggregate[core] = {'shards': len(shards),
                           'opens': sum(m.get('case', '').endswith('-opened') for m in measurements),
                           'seeks': sum(m.get('case', '').endswith('-seek') for m in measurements),
                           'completedFiles': sum(m.get('case', '').endswith('-complete') and m.get('ok') is True
                                                 for m in measurements),
                           'kinds': dict(collections.Counter(m['kind'] for m in formats)),
                           'sizeBytesMin': min(m['bytes'] for m in formats),
                           'sizeBytesMax': max(m['bytes'] for m in formats),
                           'formats': sorted({m['container'] for m in formats}),
                           'videoCodecs': sorted({s['codec_name'] for m in formats for s in m['tracks']
                                                  if s['codec_type'] == 'video'}), 'evidence': shards}
    result = {'source': SOURCE, 'run': 43994190617, 'cores': aggregate,
              'completeAfterRetry': all(v['completedFiles'] == 88 and v['seeks'] == 176 for v in aggregate.values()),
              'earlierFailure': {'attempt': 1, 'engine': 'YCore', 'shard': 2,
                                 'evidence': 'latest-public-14/integrated-original-YCore-2',
                                 'causeObserved': 'Original first-byte range unavailable to independent cloud host and player',
                                 'diagnosticRun': 44002000539, 'originAndLoopbackEachOpens': 12,
                                 'originAndLoopbackEachSeeks': 24, 'retryCount': 1}}
    save('latest-public-originals.json', result)
    return result


if __name__ == '__main__':
    matrix, original = volume(), originals()
    progress = read(ROOT / 'progress.json')
    progress['completedRegression']['partialVolume'] = {
        'product': SOURCE, 'run': matrix['run'], 'wholeRunComplete': matrix['complete'],
        **{k: {'verified': v['verifiedSeeks'], 'shards': v['shards'], 'opens': v['opens']}
           for k, v in matrix['cores'].items()}}
    progress['completedRegression']['latestPublicOriginalPartial'] = {
        'run': original['run'], 'source': SOURCE, 'wholeRunComplete': original['completeAfterRetry'],
        'earlierFailureRetained': True, 'retryCount': 1,
        **{k: {name: v[name] for name in ['shards', 'opens', 'seeks', 'completedFiles']}
           for k, v in original['cores'].items()}}
    save('progress.json', progress)
    print(json.dumps({'seeks': {k: v['verifiedSeeks'] for k, v in matrix['cores'].items()},
                      'originals': {k: v['completedFiles'] for k, v in original['cores'].items()},
                      'matrixComplete': matrix['complete'], 'originalsCompleteAfterRetry': original['completeAfterRetry']}))
