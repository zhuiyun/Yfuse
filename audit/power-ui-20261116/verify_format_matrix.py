"""Read actual app identity, decoded picture and seek receipts from both cloud artifacts."""
import json
from pathlib import Path


FIXTURES = {'long-avc', 'avc-cropped', 'hevc-main', 'hevc-main10', 'prores-4444', 'avc-transport',
            'hevc-4k', 'hevc-4k-60s', 'long-gop', 'long-gop-chapter'}
METHODS = {(method, fraction) for method in ('adb-swipe', 'held-preview') for fraction in (.63, .21)}


def check(root, source, apk, expected_fixtures=FIXTURES):
    cores = {}
    fingerprints = {}
    for core in ('YCore', 'SenPlayer'):
        folder = root / ('format-diagnostics-' + core)
        assert 'app=' + source in (folder / 'provenance.txt').read_text().splitlines()
        assert (folder / 'apk.sha256').read_text().split()[0] == apk
        download = json.loads((folder / 'download-receipt.json').read_text())
        assert download['digest'].startswith('sha256:') and 'format-results.json' in download['files']
        rows = json.loads((folder / 'format-results.json').read_text())
        assert len(rows) == len(expected_fixtures) and {r['fixture']['id'] for r in rows} == expected_fixtures
        checks = []
        for row in rows:
            fixture = row['fixture']; name = fixture['id']; digest = fixture['sha256']
            assert len(digest) == 64 and fingerprints.setdefault(name, digest) == digest
            if name == 'dolby-profile5-sdr':
                assert fixture['seconds'] == 64
                assert fixture['matchedVideoPackets'] == fixture['decodedFramesWithRpu'] > 320
            identity = row.get('identity', {})
            active = identity.get('active') and identity.get('engine') == core
            opened = bool(row['openedWithin60Seconds'] and active and row.get('open', {}).get('laterPos', 0) > row.get('open', {}).get('firstPos', 0))
            seeks = []
            assert len(row['seeks']) == len({(s['method'], s['fraction']) for s in row['seeks']})
            for seek in row['seeks']:
                assert (seek['method'], seek['fraction']) in METHODS
                receipt = seek.get('inputReceipt')
                fixed_rail = None
                if receipt:
                    fixed_rail = all(receipt['rail' + edge] == receipt['heldRail' + edge] for edge in ('Left', 'Right'))
                    assert abs(receipt['shownFraction'] - seek['fraction']) <= 1 / (receipt['railRight'] - receipt['railLeft'])
                proof = seek.get('output') or {}
                confirmed = bool(seek.get('rawTargetConfirmed'))
                if confirmed:
                    assert proof['laterPos'] > proof['firstPos'] and len(proof['changes']) >= 2
                    assert all(c['pixelChange'] >= .8 for c in proof['changes'])
                seeks.append({'method': seek['method'], 'fraction': seek['fraction'], 'confirmed': confirmed,
                              'heldRailStable': fixed_rail, 'firstPos': proof.get('firstPos'), 'laterPos': proof.get('laterPos'),
                              'secondsToConfirm': proof.get('secToConfirm'), 'shortTailSamples': seek.get('shortTailSamples')})
            checks.append({'fixture': name, 'sha256': digest, 'opened': opened, 'activeCore': bool(active), 'seeks': seeks})
        seeks = [s for row in checks for s in row['seeks']]
        cores[core] = {'opened': sum(r['opened'] for r in checks), 'confirmedSeeks': sum(s['confirmed'] for s in seeks),
                       'plannedSeeks': len(expected_fixtures) * len(METHODS), 'stableHeldRails': sum(s['heldRailStable'] is True for s in seeks),
                       'artifactDigest': download['digest'], 'checks': checks}
    return {'source': source, 'apkSha256': apk, 'fixtureCount': len(fingerprints), 'cores': cores}
