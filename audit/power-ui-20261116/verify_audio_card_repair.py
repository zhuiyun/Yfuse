"""Compare the audio information actually shown in the six before/after cloud captures."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image


ROOT = Path(__file__).resolve().parent
EVIDENCE = ROOT.parent / 'legacy-engine-20261113/power-ui-20261116'
sys.path.insert(0, str(ROOT.parents[1] / 'qa-power-ui'))
from check_detail_specs import SCENES, check, contrast, pixel_luma


def read(path):
    return json.loads(path.read_text(encoding='utf8'))


def verify(round_no, previous_round, source, apk, run):
    previous = read(ROOT / f'ui-review-{previous_round}/audio-channel-duplicates.json')
    assert {(row['variant'], row['scene']) for row in previous if 'count appears twice' in row['matchedMessage']} == {
        (variant, scene) for variant in ('light', 'dark-large') for scene in SCENES}
    current = []
    for variant in ('light', 'dark-large'):
        bundle = EVIDENCE / f'full-ui-{round_no}' / f'ui-verified-primary-{variant}'
        assert f'app={source}' in (bundle / 'provenance.txt').read_text().splitlines()
        assert f'run={run}' in (bundle / 'qa-provenance.txt').read_text().splitlines()
        assert (bundle / 'apk.sha256').read_text().split()[0] == apk
        folder = bundle / 'ui'
        result = json.loads(json.dumps(check(folder, count_only_audio=True), ensure_ascii=False))
        assert result == read(folder / 'file-info-contrast.json') and result['belowFourPointFive'] == 0
        routes = {r['name']: r for r in read(folder / 'coverage.json')['routes']}
        for sample in result['scenes']:
            route = routes[sample['scene']]
            shot = (route['scrolls'] or [route['top']])[-1]
            tree = ET.parse(folder / shot['tree']).getroot()
            cards = [node for node in tree.iter('node') if any(child.get('text') == '音频' for child in node)]
            assert len(cards) == 1
            labels = [child.get('text') for child in cards[0]]
            count_node = cards[0][labels.index('声道') + 1]
            assert count_node.get('text') == '1'
            bounds = tuple(map(int, re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', count_node.get('bounds')).groups()))
            photo = folder / shot['image']
            assert hashlib.sha256(photo.read_bytes()).hexdigest() == sample['sha256']
            with Image.open(photo) as image:
                assert 0 <= bounds[0] < bounds[2] <= image.width and 0 <= bounds[1] < bounds[3] <= image.height
                counts = Counter(image.convert('RGB').crop(bounds).getdata())
            background = counts.most_common(1)[0][0]
            ink = sorted(((contrast(pixel_luma(colour), pixel_luma(background)), n) for colour, n in counts.items()), reverse=True)
            covered = 0
            for ratio, n in ink:
                covered += n
                if covered >= 7:
                    break
            assert ratio >= 4.5
            current.append({'variant': variant, 'scene': sample['scene'], 'image': shot['image'], 'sha256': sample['sha256'],
                            'card': labels, 'countBounds': bounds, 'countContrast': round(ratio, 3),
                            'otherTextSamples': len(sample['rows']), 'lowestOtherTextContrast': min(r['contrast'] for r in sample['rows'])})
    output = {'source': source, 'apkSha256': apk, 'run': run, 'previousRound': previous_round,
              'previousDuplicateCards': len(previous), 'verifiedSingleCountCards': len(current), 'scenes': current}
    path = ROOT / f'ui-review-{round_no}' / 'audio-channel-repair.json'
    path.parent.mkdir(exist_ok=True)
    path.write_text(json.dumps(output, indent=2, ensure_ascii=False) + '\n', encoding='utf8')
    return output
