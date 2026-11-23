"""Validate downloaded current-engine results, keeping earlier evidence separate."""
from collections import Counter
import datetime
import json
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parent
EVIDENCE = ROOT.parent / 'legacy-engine-20261113/power-ui-20261116/final-public-19'
SOURCE = '0127f268d4fb00b4a83c08640b0803a76cfac866'
HASHES = set()


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def validate_source(directory):
    assert 'app=' + SOURCE in (directory / 'provenance.txt').read_text().splitlines()
    HASHES.add((directory / 'apk.sha256').read_text().split()[0])


result = {'source': SOURCE, 'volumeRun': 44036346424, 'originalRun': 44036346367,
          'updatedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'cores': {}}
for core in ['YCore', 'SenPlayer']:
    batches, operations, titles = [], [], set()
    for path in sorted(EVIDENCE.glob('integrated-' + core + '-*/volume.json')):
        validate_source(path.parent)
        data = load(path)
        rows = [row for file in data['files'] for row in file['rows']]
        assert len(rows) == data['issued']
        assert sum(row.get('ok') is True for row in rows) == data['verified'] == data['planned']
        assert all(file['open'] == 'passed' and len(file['sessions']) == 5 for file in data['files'])
        assert all(session['identity']['active'] and session['identity']['engine'] == core
                   for file in data['files'] for session in file['sessions'])
        batches.append(data['shard'])
        operations.extend(rows)
        titles.update(file['titleID'] for file in data['files'])
    measurements = []
    original_shards = []
    for path in sorted(EVIDENCE.glob('integrated-original-' + core + '-*/metrics.json')):
        validate_source(path.parent)
        measurements.extend(load(path)['measurements'])
        original_shards.append(path.parent.name)
    formats = [m for m in measurements if m['case'].endswith('-format')]
    opened = [m for m in measurements if m['case'].endswith('-opened')]
    seeks = [m for m in measurements if m['case'].endswith('-seek')]
    complete = [m for m in measurements if m['case'].endswith('-complete') and m.get('ok') is True]
    assert len(formats) == len(opened) == len(complete)
    assert len(seeks) == 2 * len(complete)
    assert all(m['laterPos'] > m['firstPos'] and len(m['changes']) >= 2 for m in opened + seeks)
    held = load(EVIDENCE / ('cached-holds-' + core) / 'thumbnail-held.json')
    validate_source(EVIDENCE / ('cached-holds-' + core))
    assert held['engine']['active'] and held['engine']['engine'] == core
    assert held['requestsWhileHeld'] == 0 and all(p['ok'] for p in held['phases'])
    latencies = sorted(row['ms'] for row in operations)
    result['cores'][core] = {
        'confirmedSeeks': len(operations), 'completedVolumeShards': batches,
        'preparedClipUniqueWorks': len(titles),
        'confirmationLatencyMsMedian': statistics.median(latencies) if latencies else None,
        'confirmationLatencyMsP95': latencies[int((len(latencies)-1)*.95)] if latencies else None,
        'originalFilesCompleted': len(complete), 'originalSeeksConfirmed': len(seeks),
        'originalKinds': dict(Counter(m['kind'] for m in formats)),
        'originalShards': original_shards,
        'originalSizeBytesMin': min(m['bytes'] for m in formats) if formats else None,
        'originalSizeBytesMax': max(m['bytes'] for m in formats) if formats else None,
        'originalContainers': sorted({m['container'] for m in formats}),
        'originalVideoCodecs': sorted({s['codec_name'] for m in formats for s in m['tracks'] if s['codec_type'] == 'video'}),
        'cachedThumbnailPhasesPassed': len(held['phases']), 'requestsWhileHeld': 0,
    }
assert len(HASHES) == 1
result['apkSha256'] = HASHES.pop()
result['originalsComplete'] = all(c['originalFilesCompleted'] == 88 and c['originalSeeksConfirmed'] == 176
                                  for c in result['cores'].values())
result['volumeComplete'] = all(len(c['completedVolumeShards']) == 16 and c['confirmedSeeks'] == 13760
                              for c in result['cores'].values())
result['complete'] = result['originalsComplete'] and result['volumeComplete']
(ROOT / 'final-public-partial-19.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({core: {key: c[key] for key in ['confirmedSeeks', 'originalFilesCompleted', 'originalSeeksConfirmed']}
                  for core, c in result['cores'].items()}))
print('originalsComplete', result['originalsComplete'], 'volumeComplete', result['volumeComplete'])
