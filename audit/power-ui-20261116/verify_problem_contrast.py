"""Recalculate the diagnostic caption measurements from both Android packages' screenshots."""
from collections import Counter
import hashlib
import json
from pathlib import Path

from PIL import Image


LABELS = {'连接与读取', '播放器', '视频输出', '声音输出', '当前音轨', '前向缓冲', '实时读取', '网络恢复'}
ADVICE = '检查服务器可达性与下载速度，再重试当前片源。'
SCREENS = {'light-black', 'light-white', 'dark-black', 'dark-white', 'dark-default'}


def read(path):
    return json.loads(path.read_text(encoding='utf8'))


def luma(rgb):
    fraction = (channel / 255 for channel in rgb)
    return sum(weight * (x / 12.92 if x <= .04045 else ((x+.055)/1.055)**2.4)
               for weight, x in zip((.2126, .7152, .0722), fraction))


def measured_ratio(image, bounds):
    left, top, right, bottom = bounds
    assert 0 <= left < right <= image.width and 0 <= top < bottom <= image.height
    counts = Counter(image.crop(bounds).get_flattened_data())
    background = counts.most_common(1)[0][0]
    base = luma(background)
    ratios = sorted((((max(base, value)+.05)/(min(base, value)+.05), counts[rgb])
                     for rgb in counts for value in (luma(rgb),)), reverse=True)
    pixels = 0
    for value, count in ratios:
        pixels += count
        if pixels >= 7:
            return value
    raise ValueError('Caption has less than seven measured pixels')


def verify(folder, run=44380288060, source='8cdb127108db020a0e78153c77cc070af0816e07',
           previous_source='94ec647fac5132b5f3ee92e1304b98f57084e078', *, include_settled=False):
    raw = folder/'native-video-repair-contracts'
    receipt, owners = read(raw/'download-receipt.json'), read(folder/'workflow-artifacts.json')
    assert owners['run'] == run and owners['repo'] == 'zhuiyun/Yfuse'
    assert any(a['id']==receipt['id'] and a['digest']==receipt['digest'] for a in owners['artifacts'])
    labels = LABELS | {ADVICE} if include_settled else LABELS
    frames = [('contrast', '', 'sha256')]
    if include_settled:
        frames.append(('settledContrast', 'settled-', 'settledSha256'))
    measurements = {}
    for stage in ('prior', 'current'):
        path = raw/(stage+'-problem-contrast')
        observed = read(path/'measurements.json')
        assert len(observed) == len(labels)*len(SCREENS)
        unique = {(r['screen'], r['label']) for r in observed}
        assert unique == {(s, label) for s in SCREENS for label in labels}
        records = {}
        for name in sorted(SCREENS):
            record = {'labels': {}}
            for metric, prefix, hash_key in frames:
                file = path/(prefix+name+'.png')
                with Image.open(file) as capture:
                    image = capture.convert('RGB')
                assert image.size == (720, 1280)
                record[hash_key] = hashlib.sha256(file.read_bytes()).hexdigest()
                for row in (r for r in observed if r['screen'] == name):
                    bounds = list(map(int, row['bounds'].split()))
                    value = measured_ratio(image, bounds)
                    assert abs(value-row[metric]) < .002, (stage, name, row['label'], metric, value, row[metric])
                    measured = record['labels'].setdefault(row['label'], {'bounds': bounds})
                    measured[metric] = round(value, 4)
            record['minimum'] = min(row[key] for row in record['labels'].values() for key, _, _ in frames)
            records[name] = record
        measurements[stage]=records
    for screen in SCREENS:
        for label in labels:
            assert measurements['prior'][screen]['labels'][label]['bounds']==measurements['current'][screen]['labels'][label]['bounds']
    counts = {stage: sum(any(row[k] < 4.5 for k, _, _ in frames) for screen in screens.values()
                         for row in screen['labels'].values()) for stage, screens in measurements.items()}
    prior_failures, remaining = counts['prior'], counts['current']
    return {'run':owners['run'],'source':source,
            'previousSource':previous_source,'artifactDigest':receipt['digest'],
            'measuredPerVersion':len(labels)*len(SCREENS),'screenshotsPerVersion':len(frames)*len(SCREENS),
            'priorBelowThreshold':prior_failures,
            'remainingBelowThreshold':remaining,'passed':prior_failures>0 and remaining==0,'observations':measurements}
