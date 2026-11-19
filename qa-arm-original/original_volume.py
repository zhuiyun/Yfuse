"""Issue physical touch gestures on byte-identical public film and episode originals."""
from collections import Counter
import base64
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
import zlib
from urllib.parse import urlsplit

import direct
from natural_frames import OriginalFrames, sample_luma
sys.path.insert(0, 'qa-power-ui')
from media import MediaServer
from power import engine, playing


OUT = Path('results/original-volume')
PUBLISHERS = {'download.blender.org', 'media.xiph.org', 'video.blender.org', 'images-assets.nasa.gov'}
MATCHED_PICTURES = ('distinct-match', 'consistent-repeated-picture')


def write(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False), encoding='utf8')
    temporary.replace(path)


def verified_publisher_range(url, offset, size, expected):
    end = offset + len(expected) - 1
    requested = f'bytes={offset}-{end}'
    expected_range = f'bytes {offset}-{end}/{size}'
    attempts = []
    for attempt in range(2):
        headers = {'Range': requested, 'Accept-Encoding': 'identity'}
        if attempt:
            headers['Cache-Control'] = 'no-cache'
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=25) as response:
            actual_range = response.headers.get('Content-Range')
            attempts.append({'status': response.status, 'contentRange': actual_range})
            if response.status != 206 or actual_range != expected_range:
                continue
            payload = response.read(len(expected) + 1)
        if len(payload) != len(expected) or payload != expected:
            raise ValueError(f'Publisher bytes at {offset} differ from the complete file despite an exact 206')
        return {'offset': offset, 'bytes': len(payload), 'sha256': hashlib.sha256(payload).hexdigest(),
                'attempts': attempts}
    raise ValueError(f'Publisher range {offset}-{end} did not match in two responses: {attempts}')


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
            data.seek(offset)
            expected = data.read(4096)
            if len(expected) != 4096:
                raise ValueError(f'Complete file has no 4096-byte reference at {offset}')
            ranges.append(verified_publisher_range(url, offset, size, expected))
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


def later_source_picture(compare, first_second, first_clock_observed):
    deadline = first_clock_observed + 7
    while time.monotonic() < deadline:
        _, tree = direct.reveal()
        before = direct.media_clock(tree)
        before_at = time.monotonic()
        if before is not None and before > first_second:
            frame = direct.screen()
            after = direct.media_clock(direct.snapshot())
            after_at = time.monotonic()
            if after_at <= deadline and after is not None and before <= after <= before + 2:
                elapsed = (before_at + after_at) / 2 - first_clock_observed
                picture = compare(frame, (before + after) / 2)
                picture.update(clockBeforeCapture=before, clockAfterCapture=after,
                               beforeClockMonotonic=before_at, afterClockMonotonic=after_at,
                               secondsAfterFirstCheck=round(elapsed, 3))
                return picture
        time.sleep(.2)
    return None


def issue_seek(fraction, row):
    def invoke():
        reply = direct.adb('shell', 'am', 'instrument', '-w', '-e', 'mode', 'seekTo', '-e', 'fraction',
                           str(fraction), 'qa.yfuse.gesture/.Driver')
        return dict(re.findall(r'^INSTRUMENTATION_RESULT: (\w+)=([^\r\n]*)', reply, re.MULTILINE))

    fields = invoke()
    # Driver.seekTo emits this error before it calculates the release point or sends ACTION_DOWN.
    if fields.get('outcome') == 'java.lang.IllegalStateException: Visible stable rail unavailable':
        row['firstDriverOutcomeBeforeDown'] = fields['outcome']
        direct.reveal()
        fields = invoke()
    return fields


def measure_touch(index, fraction, duration, reference, case, *, save_confirmed, save_failed):
    row = {'index': index, 'fraction': fraction, 'issued': False, 'confirmed': False}
    images = []
    retain_followup = False

    def compare(image, clock):
        nonlocal retain_followup
        result = reference.compare(image, clock)
        if retain_followup or result['classification'] not in MATCHED_PICTURES:
            retain_followup = True
            pixels = sample_luma(image).tobytes()
            result['luma96x54'] = base64.b64encode(zlib.compress(pixels)).decode('ascii')
            result['lumaSha256'] = hashlib.sha256(pixels).hexdigest()
        if retain_followup and (save_confirmed or save_failed):
            images.append((image, result))
        return result

    try:
        fields = issue_seek(fraction, row)
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
                                     picture_check=compare)
        row['output'] = out
        if previous is None or abs(previous - target) <= tolerance + 2:
            row['reason'] = 'pre-touch clock did not distinguish this gesture from continued playback'
            return row
        first_class = out['sourcePicture']['classification']
        row['sourceCorroborated'] = first_class in MATCHED_PICTURES
        second = None
        if not row['sourceCorroborated']:
            second = later_source_picture(compare, out['laterPos'], out['sourcePicture']['clockObservedMonotonic'])
            if second is None and first_class == 'different-scene':
                row['reason'] = 'second picture clock unavailable after a conflicting scene'
                return row
        if second is not None:
            row['secondSourcePicture'] = second
            second_position = second['playerSecond']
            latest_position = out['laterPos'] + second['secondsAfterFirstCheck'] + tolerance
            if not out['laterPos'] < second_position < duration or second_position > latest_position:
                row['reason'] = 'second picture timestamp jumped outside the original seek observation'
                return row
            if second['classification'] == 'different-scene':
                row['reason'] = 'later picture matches a different part of the original'
                return row
            row['sourceCorroborated'] = second['classification'] in MATCHED_PICTURES
            if first_class == 'different-scene' and not row['sourceCorroborated']:
                row['reason'] = 'second picture could not resolve a conflicting scene'
                return row
        row['confirmed'] = True
    except (AssertionError, ValueError, RuntimeError, OSError, subprocess.SubprocessError) as error:
        row['reason'] = type(error).__name__ + ': ' + str(error)[:140]
    finally:
        if (save_confirmed if row['confirmed'] else save_failed):
            for seq, (image, observation) in enumerate(images):
                path = OUT / f'{case}-source-{seq}.png'
                image.save(path)
                observation.update(observedImage=path.name, imageSha256=hashlib.sha256(path.read_bytes()).hexdigest())
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
            'sessions': [], 'touches': [], 'referenceTimeline': reference.timeline,
            'sourceImageExamplesPerOutcome': 2,
            'cachePolicy': 'clear on engine entry, retain between sessions'}
    result = OUT / f'{label}-{core}.json'
    write(result, record)
    endpoint = MediaServer(path, OUT / f'{label}-{core}-ranges.jsonl', content_type=item['mime'])
    url = f"http://127.0.0.1:18389/{original['sha256']}.{path.suffix.lstrip('.') or 'media'}"
    try:
        captured_gestures = Counter()
        for first in range(0, count, 40):
            direct.adb('shell', 'am', 'force-stop', 'com.yfuse')
            observations = len(direct.ROWS)
            try:
                opened = playing(core, url, item['mime'], hide_controls=False)
            except (AssertionError, ValueError, RuntimeError, OSError, subprocess.SubprocessError):
                label_prefix = re.sub(r'[^A-Za-z0-9_.-]', '_', f'{label}-{core}-open-{first}')
                record['failedOpen'] = {
                    'firstPlannedTouch': first, 'diagnosticPrefix': label_prefix,
                    'observations': [v for v in direct.ROWS[observations:] if v['case'] == f'power-{core}-observation'],
                }
                write(result, record)
                direct.capture_failure(label_prefix)
                raise
            record['sessions'].append({'firstTouch': first, 'opened': opened})
            for index in range(first, min(first + 40, count)):
                case = f'{label}-{core}-{index}'
                row = measure_touch(index, plans[index], original['duration'], reference, case,
                                    save_confirmed=captured_gestures[True] < record['sourceImageExamplesPerOutcome'],
                                    save_failed=captured_gestures[False] < record['sourceImageExamplesPerOutcome'])
                if row.get('output', {}).get('sourcePicture', {}).get('observedImage'):
                    captured_gestures[row['confirmed']] += 1
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
        record['sourceCorroboratedGestures'] = [row['index'] for row in record['touches']
                                               if row['confirmed'] and row.get('sourceCorroborated')]
        record['passed'] = record['confirmed'] == count and len(record['sourceCorroboratedGestures']) >= min(count, 2)
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
    elif stage == 'reference-control':
        selected = {'blender-ff8fe61b-026f-4f07-b66b-2a790d6f6ab1', 'blender-6402b77c-b61f-4a06-96ca-c8420a2becf4'}
        items = [item for item in catalog if item['id'] in selected]
        assert len(items) == len(selected)
        count = 120
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
