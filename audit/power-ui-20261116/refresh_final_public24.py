"""Validate downloaded current-engine results, keeping earlier evidence separate."""
from collections import Counter
import argparse
import datetime
import json
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--round', type=int, default=24)
parser.add_argument('--source', default='fe814ec63fb1c035ff9fb123146e3ed3700a2b16')
parser.add_argument('--volume-run', type=int, default=44068585060)
parser.add_argument('--original-run', type=int, default=44068584899)
parser.add_argument('--apk')
parser.add_argument('--reference-round', type=int, help='Verified public catalogue measurements for cases whose first source probe failed')
args = parser.parse_args()
EVIDENCE = ROOT.parent / f'legacy-engine-20261113/power-ui-20261116/final-public-{args.round}'
SOURCE = args.source
HASHES = set()


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def validate_source(directory):
    assert 'app=' + SOURCE in (directory / 'provenance.txt').read_text().splitlines()
    HASHES.add((directory / 'apk.sha256').read_text().split()[0])


def reference_formats(core):
    if args.reference_round is None:
        return {}
    assert 0 < args.reference_round < args.round
    previous = load(ROOT / f'final-public-partial-{args.reference_round}.json')
    assert previous['originalsFirstAttemptComplete']
    directory = EVIDENCE.with_name(f'final-public-{args.reference_round}')
    recorded = {}
    for path in directory.glob('integrated-original-' + core + '-*/metrics.json'):
        assert 'app=' + previous['source'] in path.with_name('provenance.txt').read_text().splitlines()
        assert path.with_name('apk.sha256').read_text().split()[0] == previous['apkSha256']
        rows = load(path)['measurements']
        finished = {m['case'].removesuffix('-complete') for m in rows if m['case'].endswith('-complete') and m['ok']}
        for row in rows:
            if row['case'].endswith('-format') and row['case'].removesuffix('-format') in finished:
                assert row['case'] not in recorded
                recorded[row['case']] = row
    assert len(recorded) == previous['cores'][core]['originalFilesCompleted']
    return recorded


def verified_ranges(measurements):
    matches = {}
    for row in measurements:
        prefix, _, tag = row['case'].rpartition('-')
        if tag in ('start', 'middle', 'end') and row.get('status') == 206 and row.get('exactRange') and row.get('returned') == 1024:
            matches.setdefault(prefix, set()).add(tag)
    return {prefix for prefix, tags in matches.items() if len(tags) == 3}


def check_seek(row, duration):
    assert row['fraction'] in (.63, .21)
    target = duration * row['fraction']
    assert target - 12.01 <= row['firstPos'] <= target + row['secToFirst'] + 12.01
    assert target - 12.01 <= row['laterPos'] <= target + row['secToConfirm'] + 12.01


result = {'source': SOURCE, 'volumeRun': args.volume_run, 'originalRun': args.original_run,
          'updatedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'cores': {}}
for core in ['YCore', 'SenPlayer']:
    batches, operations, titles, prepared_files = [], [], set(), []
    volume_counts = []
    for path in sorted(EVIDENCE.glob('integrated-' + core + '-*/volume.json')):
        validate_source(path.parent)
        data = load(path)
        rows = [row for file in data['files'] for row in file['rows']]
        assert len(rows) == data['issued']
        assert sum(row.get('ok') is True for row in rows) == data['verified'] <= data['issued'] <= data['planned']
        if data['verified'] == data['planned']:
            assert all(file['open'] == 'passed' and len(file['sessions']) == 5 for file in data['files'])
        assert all(session['identity']['active'] and session['identity']['engine'] == core
                   for file in data['files'] for session in file['sessions'])
        batches.append(data['shard'])
        operations.extend(row for row in rows if row.get('ok') is True)
        volume_counts.append({field: data[field] for field in ('shard','issued','verified','planned')})
        titles.update(file['titleID'] for file in data['files'])
        prepared_files.extend(data['files'])
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
    # Keep unsuccessful attempts visible instead of aborting the partial report or
    # deleting them when a later rerun succeeds. Only complete cases receive file credit.
    assert len({m['case'] for m in formats}) == len(formats)
    assert len({m['case'] for m in complete}) == len(complete)
    for completed in complete:
        prefix = completed['case'].removesuffix('-complete')
        assert sum(m['case'] == prefix + '-format' for m in formats) == 1
        assert sum(m['case'] == prefix + '-opened' for m in opened) == 1
        assert sum(m['case'] == prefix + '-seek' for m in seeks) == 2
        identities = [m for m in measurements if m['case'] == prefix + '-engine']
        assert len(identities) == 1 and identities[0]['active'] and identities[0]['engine'] == core
    unsuccessful = [m for m in measurements if m['case'].endswith('-incomplete') or
                    (m['case'].endswith('-complete') and not m.get('ok'))]
    format_by_id = {m['case'].removesuffix('-format'): m for m in formats}
    incomplete_by_id = {m['case'].removesuffix('-incomplete'): m for m in unsuccessful if m['case'].endswith('-incomplete')}
    before_format = {k: m for k, m in incomplete_by_id.items() if k not in format_by_id}
    attempted = {**before_format, **format_by_id}
    assert all(m['laterPos'] > m['firstPos'] and len(m['changes']) >= 2 for m in opened + seeks)
    for drag in seeks:
        check_seek(drag, format_by_id[drag['case'].removesuffix('-seek')]['seconds'])
    # Rechecks add coverage without replacing or concealing the first attempt.
    coverage_ids = {m['case'].removesuffix('-complete') for m in complete}
    original_ranges = verified_ranges(measurements)
    range_coverage = set(original_ranges)
    rechecks = []
    reference = None
    for path in sorted(EVIDENCE.glob('recheck-*/rechecked-original-' + core + '-*/metrics.json')):
        validate_source(path.parent)
        retry = load(path)['measurements']
        range_coverage.update(verified_ranges(retry))
        finished = [m for m in retry if m['case'].endswith('-complete') and m.get('ok') is True]
        retry_formats = [m for m in retry if m['case'].endswith('-format')]
        assert len({m['case'] for m in finished}) == len(finished)
        retry_failures = [m for m in retry if m['case'].endswith('-incomplete') or
                          (m['case'].endswith('-complete') and not m.get('ok'))]
        prior_round_ids = []
        for done in finished:
            prefix = done['case'].removesuffix('-complete')
            current, = [m for m in retry_formats if m['case'] == prefix + '-format']
            prior = format_by_id.get(prefix)
            if prior is None:
                assert prefix in before_format and before_format[prefix]['kind'] == current['kind']
                if reference is None:
                    reference = reference_formats(core)
                prior = reference[current['case']]
                prior_round_ids.append(prefix)
            assert all(current[key] == prior[key] for key in ['kind', 'bytes', 'seconds', 'container', 'tracks'])
            identities = [m for m in retry if m['case'] == prefix + '-engine']
            assert len(identities) == 1 and identities[0]['active'] and identities[0]['engine'] == core
            pictures = [m for m in retry if m['case'] == prefix + '-opened']
            drags = [m for m in retry if m['case'] == prefix + '-seek']
            assert len(pictures) == 1 and len(drags) == 2
            assert {m['fraction'] for m in drags} == {.63, .21}
            assert all(m['laterPos'] > m['firstPos'] and len(m['changes']) >= 2 for m in pictures + drags)
            for drag in drags:
                check_seek(drag, current['seconds'])
            coverage_ids.add(prefix)
        rechecks.append({'run': int(path.parent.parent.name.removeprefix('recheck-')),
                        'evidence': str(path.relative_to(EVIDENCE)),
                        'completedFiles': len(finished), 'confirmedSeeks': len(finished) * 2,
                        'unsuccessful': retry_failures, 'sourceProbes': [m for m in retry if m['case'].endswith('-probe-attempt')],
                        'previousRoundTechnicalDescriptors': {'round': args.reference_round, 'cases': prior_round_ids}
                        if prior_round_ids else None})
    held_path = EVIDENCE / ('cached-holds-' + core) / 'thumbnail-held.json'
    held = None
    if held_path.exists():
        held = load(held_path)
        validate_source(held_path.parent)
        assert held['engine']['active'] and held['engine']['engine'] == core
        assert len(held['phases']) == 7 and held['requestsWhileHeld'] == 0 and all(p['ok'] for p in held['phases'])
    latencies = sorted(row['ms'] for row in operations)
    result['cores'][core] = {
        'confirmedSeeks': len(operations), 'completedVolumeShards': batches, 'volumeShardResults': volume_counts,
        'preparedClipUniqueWorks': len(titles),
        'preparedClipKinds': dict(Counter(f['kind'] for f in prepared_files)),
        'preparedClipBytesMin': min((f['size'] for f in prepared_files), default=None),
        'preparedClipBytesMax': max((f['size'] for f in prepared_files), default=None),
        'preparedClipSecondsMin': min((f['duration'] for f in prepared_files), default=None),
        'preparedClipSecondsMax': max((f['duration'] for f in prepared_files), default=None),
        'preparedClipContainers': sorted({f['testFile']['format'] for f in prepared_files}),
        'preparedClipVideoCodecs': sorted({s['codec_name'] for f in prepared_files for s in f['testFile']['streams'] if s['codec_type'] == 'video'}),
        'preparedClipAudioCodecs': sorted({s['codec_name'] for f in prepared_files for s in f['testFile']['streams'] if s['codec_type'] == 'audio'}),
        'preparedClipResolutions': sorted({str(s['width']) + 'x' + str(s['height']) for f in prepared_files for s in f['testFile']['streams'] if s['codec_type'] == 'video'}),
        'confirmationLatencyMsMedian': statistics.median(latencies) if latencies else None,
        'confirmationLatencyMsP95': latencies[int((len(latencies)-1)*.95)] if latencies else None,
        'originalFilesCompleted': len(complete), 'originalSeeksConfirmed': len(seeks),
        'originalFilesAttempted': len(attempted), 'originalMovingOpens': len(opened),
        'originalFormatsObserved': len(formats), 'failedBeforeFormat': sorted(before_format),
        'originalFilesWithThreeVerifiedRanges': len(original_ranges),
        'originalFilesWithThreeVerifiedRangesAfterRechecks': len(range_coverage),
        'originalUnsuccessful': unsuccessful,
        'originalRechecks': rechecks,
        'originalFilesCoveredAfterRechecks': len(coverage_ids),
        'originalUniqueSeeksCoveredAfterRechecks': len(coverage_ids) * 2,
        'originalKinds': dict(Counter(m['kind'] for m in attempted.values())),
        'originalShards': original_shards,
        'originalSizeBytesMin': min(m['bytes'] for m in formats) if formats else None,
        'originalSizeBytesMax': max(m['bytes'] for m in formats) if formats else None,
        'originalContainers': sorted({m['container'] for m in formats}),
        'originalVideoCodecs': sorted({s['codec_name'] for m in formats for s in m['tracks'] if s['codec_type'] == 'video'}),
        'cachedThumbnailPhasesPassed': len(held['phases']) if held else None,
        'requestsWhileHeld': held['requestsWhileHeld'] if held else None,
    }
assert len(HASHES) == 1
result['apkSha256'] = HASHES.pop()
if args.apk:
    assert result['apkSha256'] == args.apk, 'Reports do not match the expected APK'
result['originalsFirstAttemptComplete'] = all(c['originalFilesCompleted'] == 88 and c['originalSeeksConfirmed'] == 176
                                  for c in result['cores'].values())
result['originalsComplete'] = all(c['originalFilesCoveredAfterRechecks'] == 88 and c['originalUniqueSeeksCoveredAfterRechecks'] == 176
                                for c in result['cores'].values())
result['volumeComplete'] = all(len(c['completedVolumeShards']) == 16 and c['confirmedSeeks'] == 13760
                              for c in result['cores'].values())
result['thumbnailChecksComplete'] = all(c['cachedThumbnailPhasesPassed'] == 7 for c in result['cores'].values())
result['complete'] = result['originalsComplete'] and result['volumeComplete'] and result['thumbnailChecksComplete']
(ROOT / f'final-public-partial-{args.round}.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({core: {key: c[key] for key in ['confirmedSeeks', 'originalFilesCompleted', 'originalSeeksConfirmed']}
                  for core, c in result['cores'].items()}))
print('originalsComplete', result['originalsComplete'], 'volumeComplete', result['volumeComplete'])
