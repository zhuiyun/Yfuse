"""Recompute destinations from saved Android UI trees and validate single-tap receipts when present."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parents[1]
RAW = ROOT.parent / 'legacy-engine-20261113/power-ui-20261116'
sys.path[:0] = [str(PROJECT / 'qa-power-ui'), str(PROJECT / 'qa-functional')]
import features
from route_content import CONTENT, destination_visible


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def check_picture(folder, receipt, name):
    file = folder / receipt['tree']
    assert file.parent == folder and (folder / receipt['image']).is_file()
    tree = ET.parse(file).getroot()
    # The on-device capture stores UTF-8 XML and hashes ElementTree's default ASCII serialization.
    assert hashlib.sha256(ET.tostring(tree)).hexdigest() == receipt['treeSha256']
    return destination_visible(tree, name, features.selected_dock)


def verify(number):
    source = read(ROOT / f'iteration-{number}-verification.json')
    package = read(ROOT / f'package-verification-{number}.json')
    expected_apk = package['apkSha256']
    assert package['packageName'] == 'com.yfuse'
    features.pkg = package['packageName']
    raw = RAW / f'full-ui-{number}'
    owners = read(raw / 'workflow-artifacts.json')
    assert owners['run'] == source['uiRun'] and owners['repo'] == 'zhuiyun/Yfuse'
    index = {a['name']: a for a in owners['artifacts']}
    observed = []
    by_theme = {}
    misses = []
    taps = []
    for bundle in sorted(raw.glob('ui-verified-*')):
        receipt = read(bundle / 'download-receipt.json')
        owner = index[bundle.name]
        assert owner['id'] == receipt['id'] and owner['digest'] == receipt['digest']
        assert (bundle / 'apk.sha256').read_text().split()[0] == expected_apk
        assert 'app=' + source['source'] in (bundle / 'provenance.txt').read_text().splitlines()
        qa = (bundle / 'qa-provenance.txt').read_text().splitlines()
        assert 'run=' + str(owners['run']) in qa and 'qa=' + owner['workflowHead'] in qa
        if 'uiWorkflowHead' in source:
            assert owner['workflowHead'] == source['uiWorkflowHead']
        target = bundle / 'ui'
        data = read(target / 'coverage.json')
        theme = data['variant']
        assert bundle.name.endswith('-' + theme)
        routes = by_theme.setdefault(theme, set())
        for row in data['routes']:
            name = row['name']
            assert name in CONTENT and name not in routes
            routes.add(name)
            shot = row.get('top')
            if not shot or row['status'] != 'captured-needs-review' or not check_picture(target, shot, name):
                misses.append({'bundle': bundle.name, 'page': name, 'tree': shot['tree'] if shot else None})
        observed.append(bundle.name)
        taps_file = target / 'single-tap-tabs.json'
        if taps_file.is_file():
            trials = read(taps_file)
            assert [v['page'] for v in trials] == ['library-empty', 'watching', 'profile', 'servers'] * 3
            for n, trial in enumerate(trials, 1):
                assert trial['step'] == n and trial['issued'] and trial['verified']
                assert check_picture(target, trial, trial['page'])
            taps.append({'bundle': bundle.name, 'verifiedSingleTaps': len(trials), 'sha256': hashlib.sha256(taps_file.read_bytes()).hexdigest()})
    count = sum(len(rows) for rows in by_theme.values())
    complete = set(by_theme) == {'light', 'dark-large'} and all(pages == CONTENT.keys() for pages in by_theme.values())
    if number >= 49 and complete:
        assert len(taps) == 2
    result = {'round': number, 'pathThemeObservations': count, 'verifiedPageDestinations': count - len(misses),
              'all72RoutesInBothThemes': complete and not misses, 'observedVariants': observed, 'mismatches': misses,
              'singleTapReceipts': taps, 'verifiedSingleTaps': sum(r['verifiedSingleTaps'] for r in taps),
              'evidence': 'UI tree SHA-256, run ownership and APK identity verified. Each route requires its visible content and root routes require the matching selected tab. Single taps count only when separately issued and corroborated by their saved destination tree.'}
    (ROOT / f'ui-destinations-{number}.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(number, count, result['verifiedPageDestinations'], 'single taps', result['verifiedSingleTaps'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('round', type=int)
    verify(parser.parse_args().round)
