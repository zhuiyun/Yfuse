"""Index post-fix cloud screenshots, check their accessibility evidence, and prepare visual review sheets."""
import argparse
import hashlib
import json
import math
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont, ImageStat


ROOT = Path(__file__).resolve().parent
EVIDENCE = ROOT.parent / 'legacy-engine-20261113' / 'power-ui-20261116'
SOURCE = 'fe814ec63fb1c035ff9fb123146e3ed3700a2b16'


def captures(value):
    if isinstance(value, dict):
        if 'image' in value and 'tree' in value:
            yield value
        else:
            for child in value.values():
                yield from captures(child)
    elif isinstance(value, list):
        for child in value:
            yield from captures(child)


def compare_pixels(picture, prior):
    if not prior.is_file():
        return 'new-view', None
    with Image.open(prior) as old:
        old = old.convert('RGB')
        if old.size != picture.size:
            return 'new-size', None
        diff = ImageChops.difference(old, picture)
        if not diff.getbbox():
            return 'identical-pixels', 0.0
        w, h = picture.size
        content = diff.crop((0, 45, w, h - 38))
        if not content.getbbox():
            return 'differences-only-at-system-edges', 0.0
        return 'changed', round(sum(ImageStat.Stat(content).mean) / 3, 3)


def build(round_no, baseline_round, source, run, expected_apk):
    out = ROOT / f'ui-review-{round_no}'; out.mkdir(exist_ok=True)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 15)
    entries, routes, sheets = [], [], []
    bundles = sorted((EVIDENCE / f'full-ui-{round_no}').glob('ui-verified-*'))
    assert {b.name for b in bundles} == {f'ui-verified-{scope}-{variant}' for scope in ('primary','secondary')
                                         for variant in ('light','dark-large')}
    for bundle in bundles:
        group = bundle.name.removeprefix('ui-verified-')
        assert 'app=' + source in (bundle / 'provenance.txt').read_text().splitlines()
        assert (bundle / 'apk.sha256').read_text().split()[0] == expected_apk
        assert 'run=' + str(run) in (bundle / 'qa-provenance.txt').read_text().splitlines()
        coverage = json.loads((bundle / 'ui' / 'coverage.json').read_text(encoding='utf-8'))
        unique = {}
        for route in coverage['routes']:
            routes.append({'group': group, 'route': route['name'], 'status': route['status'],
                           'captureReason': route.get('reason'), 'scrollEndObserved': route.get('scrollEndObserved')})
            for shot in captures(route):
                file = bundle / 'ui' / shot['image']
                tree = ET.parse(bundle / 'ui' / shot['tree']).getroot()
                assert hashlib.sha256(ET.tostring(tree)).hexdigest() == shot['treeSha256']
                digest = hashlib.sha256(file.read_bytes()).hexdigest()
                with Image.open(file) as frame:
                    assert frame.size == (shot['widthPx'], shot['heightPx'])
                    picture = frame.convert('RGB')
                old = EVIDENCE / f'full-ui-{baseline_round}' / bundle.name / 'ui' / file.name
                comparison, delta = compare_pixels(picture, old)
                # System bars can remain visible while Android masks the sensitive app content.
                region = (0, round(picture.height * .072), picture.width, round(picture.height * .949))
                row = {'group': group, 'route': route['name'], 'file': str(file), 'sha256': digest,
                       'width': picture.width, 'height': picture.height, 'packages': shot['packages'],
                       'flatFrame': all(a == b for a, b in picture.getextrema()),
                       'inspectedAppRegion': region, 'blackAppRegion': picture.crop(region).getbbox() is None,
                       'baselineComparison': comparison, 'centerMeanRgbDelta': delta}
                entries.append(row); unique.setdefault(digest, row)
        unique = list(unique.values())
        for i in range(0, len(unique), 9):
            part = unique[i:i+9]; columns = 3; w, h = 320, 600
            board = Image.new('RGB', (columns*w, math.ceil(len(part)/columns)*h), '#edf0f3')
            draw = ImageDraw.Draw(board); name = f'{group}-{i//9+1:02d}.jpg'
            for j, row in enumerate(part):
                x, y = j % columns * w, j // columns * h
                with Image.open(row['file']) as im:
                    im.thumbnail((w-10,h-36),Image.Resampling.LANCZOS)
                    board.paste(im,(x+(w-im.width)//2,y+27))
                draw.text((x+4,y+4),Path(row['file']).stem,font=font,fill='#233246')
                row['sheet'] = name
            board.save(out / name, quality=90); sheets.append(name)
    report = {'source': source, 'apkSha256': expected_apk, 'run': run, 'baselineRound': baseline_round,
              'routes': routes, 'captures': entries, 'sheets': sheets}
    (out / 'index.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'routeVariants': len(routes), 'imageObservations': len(entries), 'sheets': len(sheets)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--round', type=int, default=25)
    parser.add_argument('--baseline', type=int, default=20)
    parser.add_argument('--source', default=SOURCE)
    parser.add_argument('--run', type=int, default=44218885383)
    parser.add_argument('--apk')
    args = parser.parse_args()
    apk = args.apk or json.loads((ROOT / 'package-verification-24.json').read_text('utf8'))['apkSha256']
    build(args.round, args.baseline, args.source, args.run, apk)
