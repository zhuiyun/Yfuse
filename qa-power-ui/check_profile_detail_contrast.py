"""Verify legibility and the recommended engine on the actual primary-route screenshots."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from PIL import Image

from check_detail_specs import painted_contrast


def tree_for(folder, shot):
    tree = ET.parse(folder / shot['tree']).getroot()
    assert hashlib.sha256(ET.tostring(tree)).hexdigest() == shot['treeSha256']
    return tree


def find_painted_labels(folder, route, expressions):
    for shot in [route['top'], *route['scrolls']]:
        tree = tree_for(folder, shot)
        labels = []
        for pattern in expressions:
            matches = []
            for node in tree.iter('node'):
                label = node.get('text', '')
                if len(node) or not re.fullmatch(pattern, label):
                    continue
                box = [int(v) for v in re.findall(r'-?\d+', node.get('bounds', ''))]
                if len(box) == 4 and box[1] >= 64 and box[3] <= shot['heightPx'] - 65:
                    matches.append((label, box))
            if len(matches) == 1:
                labels.append(matches[0])
        if len(labels) != len(expressions):
            continue
        path = folder / shot['image']
        with Image.open(path) as image:
            assert image.size == (shot['widthPx'], shot['heightPx'])
            rgb = image.convert('RGB')
        rows = []
        for label, box in labels:
            bg, ratio = painted_contrast(rgb, box)
            rows.append({'label': label, 'bounds': box, 'backgroundRgb': bg, 'contrast': ratio})
        return {'route': route['name'], 'image': shot['image'],
                'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'rows': rows}
    raise AssertionError('Missing complete label group on ' + route['name'])


def check(folder):
    coverage = json.loads((folder / 'coverage.json').read_text(encoding='utf-8'))
    routes = {r['name']: r for r in coverage['routes']}
    fields = [re.escape(v) for v in ('media.example.com', '留空使用服务器名称', '输入用户名', '留空表示无密码', '显示')]
    groups = [('add-server', fields), ('about-section', [r'版本 \d+\.\d+\.\d+ · 构建 \d+']),
              ('series-detail', ['管理进度'])]
    groups.extend((name, ['360P']) for name in ('movie-detail', 'long-title-detail', 'series-detail'))
    scenes = [find_painted_labels(folder, routes[name], labels) for name, labels in groups]
    choice = routes['engine-choice']['top']
    labels = [n.get('text', '') for n in tree_for(folder, choice).iter('node')]
    recommended = [v for v in labels if '（推荐）' in v]
    expected = (recommended == ['自动选择（推荐）'] and 'YCore' in labels and
                'SCore（实验，需联网许可）' in labels and any('SCore 需手动选择。' in v for v in labels))
    return {'variant': coverage['variant'], 'scenes': scenes,
            'belowFourPointFive': sum(row['contrast'] < 4.5 for scene in scenes for row in scene['rows']),
            'choiceImage': choice['image'], 'choiceSha256': hashlib.sha256((folder/choice['image']).read_bytes()).hexdigest(),
            'recommendedLabels': recommended, 'engineChoicesVerified': expected}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = check(args.folder)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Text samples below 4.5:1:', result['belowFourPointFive'], 'engine choices verified:', result['engineChoicesVerified'])
    raise SystemExit(int(result['belowFourPointFive'] > 0 or not result['engineChoicesVerified']))
