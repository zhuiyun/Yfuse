"""Check selected carousel marks on the actual discovery and library screenshots."""
import argparse
import hashlib
import json
from pathlib import Path
import re
from statistics import median
import xml.etree.ElementTree as ET

from PIL import Image


def luminance(rgb):
    channels = [channel / 255 for channel in rgb]
    red, green, blue = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in channels]
    return .2126 * red + .7152 * green + .0722 * blue


def contrast(left, right):
    low, high = sorted((left, right))
    return (high + .05) / (low + .05)


def measure(folder, shot):
    tree = ET.parse(folder / shot['tree']).getroot()
    assert hashlib.sha256(ET.tostring(tree)).hexdigest() == shot['treeSha256']
    selected, = [node for node in tree.iter('node') if node.get('selected') == 'true'
                 and re.fullmatch(r'第 \d+ 张，共 \d+ 张', node.get('content-desc', ''))]
    label = selected.get('content-desc')
    assert label == shot.get('heroPageLabel', label)
    left, top, right, bottom = map(int, re.findall(r'\d+', selected.get('bounds')))
    x, y = (left + right) // 2, (top + bottom) // 2
    # All indicators are centered in a 48dp-high tab. The filled mark is 26dp by 3dp.
    density = (bottom - top) / 48
    file = folder / shot['image']
    with Image.open(file) as source:
        picture = source.convert('RGB')
    assert picture.size == (shot['widthPx'], shot['heightPx'])
    def level(dx, dy):
        return luminance(picture.getpixel((x + dx, y + dy)))
    middle = range(-round(5 * density), round(5 * density) + 1)
    fill = median(level(dx, 0) for dx in middle)
    sides = [median(level(side * dx, dy) for dx in range(round(15.5 * density), round(18.5 * density) + 1)
                    for dy in (-1, 0, 1)) for side in (-1, 1)]
    rim = median(level(dx, side * round(2 * density)) for dx in middle for side in (-1, 1))
    return {'image': shot['image'], 'sha256': hashlib.sha256(file.read_bytes()).hexdigest(), 'selected': label,
            'centerPx': [x, y], 'adjacentContrast': round(contrast(fill, median(sides)), 3),
            'rimContrast': round(contrast(fill, rim), 3)}


def check(folder):
    capture = json.loads((folder / 'coverage.json').read_text())
    routes = {r['name']: r for r in capture['routes']}
    posters = routes['discover']['heroPages']
    assert len(posters) == 8 and all(r['scrollEndObserved'] for r in routes.values())
    rows = [dict(route='discover', **measure(folder, shot)) for shot in posters]
    assert len({r['selected'] for r in rows}) == len(posters)
    rows.append(dict(route='populated-library', **measure(folder, routes['populated-library']['top'])))
    return {'variant': capture['variant'], 'observations': rows,
            'belowThreeToOne': sum(max(row['adjacentContrast'], row['rimContrast']) < 3 for row in rows)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = check(args.folder)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"{len(result['observations'])} selected marks, {result['belowThreeToOne']} below 3:1")
    raise SystemExit(bool(result['belowThreeToOne']))
