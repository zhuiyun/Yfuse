"""Check the contrast of file information text against its painted card on real screenshots."""
import argparse
from collections import Counter
from functools import lru_cache
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from PIL import Image
from check_hero_indicators import contrast, luminance


SCENES = ('movie-detail', 'long-title-detail', 'series-detail')
LABELS = {'编码', '分辨率', '布局', '声道', 'H264', 'AAC', '640x360', '1 声道', 'MP4'}
pixel_luma = lru_cache(maxsize=8192)(luminance)


def read_scene(folder, route):
    assert route['scrollEndObserved']
    shot = (route['scrolls'] or [route['top']])[-1]
    tree = ET.parse(folder / shot['tree']).getroot()
    assert hashlib.sha256(ET.tostring(tree)).hexdigest() == shot['treeSha256']
    photo = folder / shot['image']
    with Image.open(photo) as file:
        rgb = file.convert('RGB')
    assert rgb.size == (shot['widthPx'], shot['heightPx'])
    rows = []
    for node in tree.iter('node'):
        label = node.get('text')
        if label not in LABELS:
            continue
        x1,y1,x2,y2 = map(int, re.findall(r'\d+', node.get('bounds')))
        if y1 < 60 or y2 >= rgb.height-40:
            continue
        colours = Counter(rgb.crop((x1,y1,x2,y2)).getdata())
        bg, _ = colours.most_common(1)[0]
        # Several fully covered glyph pixels must provide readable contrast.
        # Median/stroke averages would incorrectly penalise anti-aliased edges.
        grouped = sorted(((contrast(pixel_luma(colour),pixel_luma(bg)),n) for colour,n in colours.items()), reverse=True)
        pixels = 0
        for ratio, n in grouped:
            pixels += n
            if pixels >= 7:
                break
        rows.append({'label':label, 'bounds':[x1,y1,x2,y2], 'backgroundRgb':bg, 'contrast':round(ratio,3)})
    assert {'编码','分辨率','H264','AAC','MP4'} <= {row['label'] for row in rows}
    return {'scene':route['name'],'image':shot['image'], 'sha256':hashlib.sha256(photo.read_bytes()).hexdigest(),
            'rows':rows}


def check(folder):
    coverage = json.loads((folder/'coverage.json').read_text(encoding='utf-8'))
    routes = {r['name']:r for r in coverage['routes']}
    scenes = [read_scene(folder, routes[name]) for name in SCENES]
    return {'variant':coverage['variant'], 'scenes':scenes,
            'belowFourPointFive':sum(v['contrast']<4.5 for s in scenes for v in s['rows'])}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    result=check(args.folder)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('File information text below 4.5:1:',result['belowFourPointFive'])
    raise SystemExit(bool(result['belowFourPointFive']))
