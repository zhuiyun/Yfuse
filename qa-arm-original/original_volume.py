"""Issue physical touch gestures on byte-identical public film and episode originals."""
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import random
import re
import subprocess
import sys
import tempfile
import time
import urllib.request
from urllib.parse import urlsplit

import direct
from natural_frames import OriginalFrames
sys.path.insert(0, 'qa-power-ui')
from media import MediaServer
from power import engine, playing


OUT = Path('results/original-volume')
PUBLISHERS = {'download.blender.org', 'media.xiph.org', 'video.blender.org', 'images-assets.nasa.gov'}


def write(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False), encoding='utf8')
    temporary.replace(path)


def reference_file(item, target):
    url = item['url']
    parts = urlsplit(url)
    if parts.scheme != 'https' or parts.hostname not in PUBLISHERS:
        raise ValueError('The volume test requires a public catalogue original')
    subprocess.run(['curl', '--silent', '--show-error', '--fail', '--location', '--proto', '=https',
                    '--proto-redir', '=https', '--retry', '2', '--connect-timeout', '15', '--max-time', '1200',
                    '--output', str(target), url], check=True)
    size = target.stat().st_size
    ranges = []
    with target.open('rb') as data:
        digest = hashlib.file_digest(data, 'sha256').hexdigest()
        for offset in (0, size // 2, size - 4096):
            request = urllib.request.Request(url, headers={'Range': f'bytes={offset}-{offset + 4095}', 'Accept-Encoding': 'identity'})
            with urllib.request.urlopen(request, timeout=25) as response:
                payload = response.read(4097)
                data.seek(offset)
                if (response.status != 206 or response.headers.get('Content-Range') != f'bytes {offset}-{offset + 4095}/{size}'
                        or len(payload) != 4096 or data.read(4096) != payload):
                    raise ValueError('An independent publisher range differed from the downloaded original')
                ranges.append({'offset': offset, 'bytes': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()})
    probe = subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries',
                                    'format=duration,size,format_name:stream=codec_type,codec_name,width,height,display_aspect_ratio',
                                    '-of', 'json', str(target)])
    media = json.loads(probe)
    assert int(media['format']['size']) == size
    video = next(v for v in media['streams'] if v['codec_type'] == 'video')
    return dict(bytes=size, sha256=digest, publisherRanges=ranges, container=media['format']['format_name'],
                duration = float(media['format']['duration']), video=video, tracks=media['streams'])


def fractions(identifier, duration, count):
    seed = int.from_bytes(hashlib.sha256(identifier.encode()).digest()[:8], 'big')
    rng = random.Random(seed)
    upper = min(.88, 1 - 12 / duration)
    if upper <= .60:
        raise ValueError('Insufficient duration for independent backward and forward placements')
    return [round(rng.uniform(.60, upper) if index % 2 == 0 else rng.uniform(.10, .35), 6) for index in range(count)]


def later_source_picture(reference, first_second):
    started = time.monotonic()
    deadline = started + 5
    while time.monotonic() < deadline:
        _, tree = direct.reveal()
        position = direct.media_clock(tree)
        if position is not None and position > first_second:
            elapsed = time.monotonic() - started
            picture = reference.compare(direct.screen(), position)
            picture['secondsAfterFirstCheck'] = round(elapsed, 3)
            return picture
        time.sleep(.2)
    return None


def measure_touch(index, fraction, duration, reference, case):
    row = {'index': index, 'fraction': fraction, 'issued': False, 'confirmed': False}
    try:
        reply = direct.adb('shell', 'am', 'instrument', '-w', '-e', 'mode', 'seekTo', '-e', 'fraction',
                           str(fraction), 'qa.yfuse.gesture/.Driver')
        fields = dict(re.findall(r'^INSTRUMENTATION_RESULT: (\w+)=([^\r\n]*)', reply, re.MULTILINE))
        if fields.get('outcome') != 'seek-touch-issued':
            raise AssertionError('Touch not confirmed by input driver: ' + fields.get('outcome', 'missing receipt'))
        row['issued'] = True
        if not {'rail', 'clockBefore', 'downX', 'upX'} <= fields.keys():
            raise ValueError('Missing on-device touch geometry')
        left, top, right, bottom = map(int, fields['rail'].split())
        width = right - left - 1
        start, x = int(fields['downX']), int(fields['upX'])
        if (width <= 0 or bottom <= top or not left <= start < right or start == x
                or x != left + int(width * fraction + .5)):
            raise ValueError('Touch did not end at the requested point of the visible rail')
        clock = re.search(r'播放进度\s+((?:\d+:)?\d+:\d{2})\s*/', fields['clockBefore'])
        previous = None
        if clock:
            previous = 0
            for part in clock[1].split(':'):
                previous = previous * 60 + int(part)
        target = duration * (x - left) / width
        tolerance = max(2.25, duration / width + 1.5)
        row.update(beforeSecond=previous, rail=[left, top, right, bottom], downX=start, releaseX=x,
                   targetSecond=target, toleranceSeconds=tolerance)
        out = direct.decode_playback(case, target, duration, max_wait=25, tolerance=tolerance,
                                     picture_check = reference.compare)
        row['output'] = out
        if previous is None or abs(previous - target) <= tolerance + 2:
            row['reason'] = 'pre-touch clock did not distinguish this gesture from continued playback'
            return row
        if out['sourcePicture']['classification'] == 'different-scene':
            # A single screenshot may straddle a cut or a UI transition. Preserve both readings.
            second = later_source_picture(reference, out['laterPos'])
            if second is None:
                row['reason'] = 'second picture clock unavailable after a conflicting scene'
                return row
            row['secondSourcePicture'] = second
            second_position = second['playerSecond']
            latest_position = out['laterPos'] + second['secondsAfterFirstCheck'] + tolerance
            if not out['laterPos'] < second_position < duration or second_position > latest_position:
                row['reason'] = 'second picture timestamp jumped outside the original seek observation'
                return row
            if second['classification'] not in ('distinct-match', 'consistent-repeated-picture'):
                row['reason'] = ('two independent pictures match a different part of the original'
                                 if second['classification'] == 'different-scene'
                                 else 'second picture could not resolve a conflicting scene')
                return row
        row['confirmed'] = True
    except (AssertionError, ValueError, RuntimeError, OSError, subprocess.SubprocessError) as error:
        row['reason'] = type(error).__name__ + ': ' + str(error)[:140]
    return row


def exercise(item, original, path, core, count, reference):
    label = item['id']
    direct.adb('shell', 'am', 'force-stop', 'com.yfuse')
    if direct.adb('shell', 'pm', 'clear', 'com.yfuse').strip() != 'Success':
        raise RuntimeError('Could not prepare the disposable package')
    engine.configure(core)
    os.environ['QA_ENGINE'] = core
    plans = fractions(label, original['duration'], count)
    record = {'core': core, 'id': label, 'kind': item['kind'], 'original': original, 'planned': count, 'targets': plans,
            'sessions': [], 'touches': [], 'cachePolicy': 'clear on engine entry, retain between sessions'}
    result = OUT / f'{label}-{core}.json'
    write(result, record)
    endpoint = MediaServer(path, OUT / f'{label}-{core}-ranges.jsonl', content_type=item['mime'])
    url = f"http://127.0.0.1:18389/{original['sha256']}.{path.suffix.lstrip('.') or 'media'}"
    try:
        for first in range(0, count, 40):
            direct.adb('shell', 'am', 'force-stop', 'com.yfuse')
            opened = playing(core, url, item['mime'], hide_controls=False)
            record['sessions'].append({'firstTouch': first, 'opened': opened})
            for index in range(first, min(first + 40, count)):
                case = f'{label}-{core}-{index}'
                row = measure_touch(index, plans[index], original['duration'], reference, case)
                record['touches'].append(row)
                if not row['confirmed'] and sum(not t['confirmed'] for t in record['touches']) <= 2:
                    direct.screen().save(OUT / (case + '-unconfirmed.png'))
                write(result, record)
            print('ORIGINAL TOUCHES', core, label, len(record['touches']), sum(t['confirmed'] for t in record['touches']), flush=True)
    except (AssertionError, ValueError, RuntimeError, OSError, subprocess.SubprocessError) as error:
        record['sessionError'] = type(error).__name__ + ': ' + str(error)[:140]
    finally:
        direct.adb('shell', 'am', 'force-stop', 'com.yfuse', check=False)
        endpoint.close()
        record['mediaTraffic'] = endpoint.counters()
        record['pictureClasses'] = dict(Counter(row['output']['sourcePicture']['classification'] for row in record['touches'] if 'output' in row))
        record['confirmed'] = sum(row['confirmed'] for row in record['touches'])
        matched = sum(record['pictureClasses'].get(k, 0) for k in ('distinct-match', 'consistent-repeated-picture'))
        record['passed'] = record['confirmed'] == count and matched >= min(count, 2)
        write(result, record)
    return record['passed']


def main():
    engine.guard()
    if direct.adb('get-serialno').strip() != '127.0.0.1:15555':
        raise RuntimeError('Cloud Android transport required')
    direct.adb('reverse', 'tcp:18389', 'tcp:18389')
    catalog = json.loads(Path('qa-format-originals/sources.json').read_text())
    catalog += [dict(v, mime='video/mp4') for v in json.loads(Path('qa-titles/catalog.json').read_text())]
    assert len(catalog) == len({v['id'] for v in catalog})
    stage = os.environ['QA_STAGE']
    if stage == 'pilot':
        chosen = ['sintel-blender-1080p-mkv', 'tears-of-steel-xiph-webm', 'blender-fb70d459-48d2-4db5-adba-813c84f9200a',
                next(v['id'] for v in catalog if v['kind'] == 'episode' and v['id'].startswith('nasa-'))]
        items = [next(v for v in catalog if v['id'] == key) for key in chosen]
        count = 6
    elif stage == 'volume':
        shard = int(os.environ['QA_SHARD'])
        if not 0 <= shard < 22:
            raise ValueError('Unknown catalogue partition')
        items = catalog[shard::22]
        count = 120
    else:
        raise ValueError('Unknown test stage')
    OUT.mkdir(parents=True, exist_ok=True)
    direct.OUT = OUT
    summary = {'stage': stage, 'files': [v['id'] for v in items], 'plannedPerFilePerCore': count, 'sources': {}}
    success = True
    for item in items:
        try:
            with tempfile.TemporaryDirectory(dir=os.environ['RUNNER_TEMP']) as tmp:
                path = Path(tmp) / ('original' + Path(urlsplit(item['url']).path).suffix)
                original = reference_file(item, path)
                reference = OriginalFrames(path, original['video'])
                summary['sources'][item['id']] = {'sha256': original['sha256'], 'bytes': original['bytes'], 'results': {}}
                for core in ('YCore', 'SenPlayer'):
                    ok = exercise(item, original, path, core, count, reference)
                    summary['sources'][item['id']]['results'][core] = ok
                    success = success and ok
        except (AssertionError, ValueError, RuntimeError, OSError, subprocess.SubprocessError) as error:
            summary['sources'].setdefault(item['id'], {})['failure']=type(error).__name__ + ': ' + str(error)[:140]
            success = False
        write(OUT / 'summary.json', summary)
    raise SystemExit(0 if success else 1)


if __name__ == '__main__':
    main()
