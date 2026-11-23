"""Reconcile individual cloud playback observations across private media runs."""
import argparse
import csv
import hashlib
import json
import re
from collections import Counter, defaultdict
from pathlib import Path


CORES = ('YCore', 'SenPlayer')
FRACTIONS = (.63, .21)


def metadata_key(row):
    fields = [row[k] for k in ('kind', 'container', 'size', 'durationSeconds', 'technical')]
    return hashlib.sha256(json.dumps(fields, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def advancing(row):
    changes = row.get('changes', [])
    return (len(changes) >= 2 and row['laterPos'] > row['firstPos'] and
            all(c['pixelChange'] >= .8 for c in changes) and changes[-1]['s'] == row['laterPos'])


def selected_core(row, core):
    proof = row.get('identity', {})
    return (proof.get('active') is True and proof.get('engine') == core and
            proof.get('ycoreLibrary') == (core == 'YCore') and
            proof.get('senOriginalLibrary') == (core == 'SenPlayer'))


def android_range(rows):
    if not rows:
        return None
    row, = rows
    return {key: row[key] for key in ('code', 'bytesRead', 'elapsedMs', 'errorType', 'driverResult') if key in row}


def summarize(directory):
    groups = defaultdict(list)
    inputs = sorted(directory.glob('relay-source-results-*/metrics.json'))
    if not inputs:
        raise ValueError('No cloud shard metrics found')
    for file in inputs:
        rows = json.loads(file.read_text(encoding='utf-8'))['measurements']
        for row in rows:
            groups[row['case']].append(row)
    media = []
    for case in sorted(k for k in groups if re.fullmatch(r'[em]-\d+-admission', k)):
        admission, = groups[case]
        label = case.removesuffix('-admission')
        probe_rows = groups.get(label + '-format-probe', [])
        probe = probe_rows[0] if probe_rows else {}
        streams = probe.get('streams', [])
        video = next((v for v in streams if v.get('codec_type') == 'video'), {})
        admitted = [key for key in ('start', 'middle', 'end') if admission['source'].get(key) == 206
                    and admission['runner'].get(key, {}).get('code') == 206 and admission['runner'][key].get('correct')]
        assert admission['kind'] in ('Movie', 'Episode') and admission['size'] > 0
        assert (label.startswith('m-')) == (admission['kind'] == 'Movie')
        item = {'label': label, 'metadataKey': metadata_key(admission), 'kind': admission['kind'],
                'bytes': admission['size'], 'seconds': admission['durationSeconds'], 'container': admission['container'],
                'reportedCodec': admission['technical'].get('videoCodec'), 'probedCodec': video.get('codec_name'),
                'profile': video.get('profile'), 'pixelFormat': video.get('pix_fmt'),
                'probedAudioCodecs': list(dict.fromkeys(s['codec_name'] for s in streams
                                                        if s.get('codec_type') == 'audio' and s.get('codec_name'))),
                'reportedBitrate': admission['technical'].get('bitrate'),
                'averageFrameRate': video.get('avg_frame_rate'), 'colorPrimaries': video.get('color_primaries'),
                'colorTransfer': video.get('color_transfer'),
                'dolbyVision': next(({key: side[key] for key in ('dv_profile', 'dv_level', 'dv_bl_signal_compatibility_id')
                                      if key in side} for side in video.get('sideData', []) if 'dv_profile' in side), None),
                'width': video.get('width'), 'height': video.get('height'), 'probeExitCode': probe.get('exitCode'),
                'sampledTransfer': next((v['samples'] for v in groups.get(label + '-range-benchmark', [])), None),
                'matchedSourceAndRunnerRanges': admitted, 'cores': {}}
        for core in CORES:
            stem = core + '-' + label + '-'
            opens = groups.get(stem + 'opened', [])
            if len(opens) > 1:
                raise ValueError('Duplicate open observation for ' + stem)
            opened = opens[0] if opens else {}
            active = selected_core(opened, core)
            first_range = android_range(groups.get(stem + 'android-network', []))
            repeat_range = android_range(groups.get(stem + 'android-network-recheck', []))
            if repeat_range:
                assert first_range and first_range['code'] != 206
            late = None
            if late_rows := groups.get(stem + 'late-open'):
                extra, = late_rows
                assert opened.get('moving') is False
                late = {'movingWithSelectedCore': extra.get('moving') is True and advancing(extra) and selected_core(extra, core),
                        'confirmationSecondsFromExtraObservation': extra.get('secToConfirm')}
            confirmed = {}
            issued = Counter(r['fraction'] for r in groups.get(stem + 'issued', []))
            assert all(fraction in FRACTIONS and count == 1 for fraction, count in issued.items())
            for row in groups.get(stem + 'confirmed', []):
                fraction = row['fraction']
                assert fraction in FRACTIONS and fraction not in confirmed and issued[fraction] == 1
                assert active and advancing(row)
                target = opened['durationSeconds'] * fraction
                assert target - 12.01 <= row['firstPos'] <= target + row['secToFirst'] + 12.01
                assert target - 12.01 <= row['laterPos'] <= target + row['secToConfirm'] + 12.01
                confirmed[fraction] = row['secToConfirm']
            events = (groups.get(stem + 'diagnostics') or [{}])[0].get('events', [])
            failures = Counter()
            for event in events:
                if not event['event'].endswith('_failed'):
                    continue
                values = event.get('values', {})
                stage = values.get('failurestage') or values.get('stage') or 'undetermined'
                failures[(event['event'], stage, values.get('exceptiontype', ''))] += 1
            status = {'openObserved': bool(opens), 'movingOpen': opened.get('moving') is True and advancing(opened),
                      'activeCore': active, 'openConfirmationSeconds': opened.get('secToConfirm'),
                      'androidRangeStatus': (first_range or {}).get('code'),
                      'androidRange': first_range, 'androidRangeRecheck': repeat_range, 'lateOpenObservation': late,
                      'issuedFractions': list(issued), 'confirmedFractions': list(confirmed),
                      'seekConfirmationSeconds': confirmed,
                      'recordedUnconfirmed': [v['fraction'] for v in groups.get(stem + 'unconfirmed', [])],
                      'inputReceiptsAfterFailure': [{'fraction': v['fraction'], **v['receipt']}
                                                    for v in groups.get(stem + 'input-diagnostic', [])],
                      'diagnosticRetries': [{'fraction': v['fraction'], 'advancingPicture': v.get('moving') is True and advancing(v)}
                                            for v in groups.get(stem + 'diagnostic-retry', [])],
                      'failureEvents': [{'event': e, 'stage': s, 'exception': t, 'count': count}
                                        for (e, s, t), count in failures.items()]}
            item['cores'][core] = status
        media.append(item)
    totals = {}
    for core in CORES:
        states = [item['cores'][core] for item in media]
        totals[core] = {'observedOpens': sum(s['openObserved'] for s in states),
                        'movingWithSelectedCore': sum(s['movingOpen'] and s['activeCore'] for s in states),
                        'additionalOpenObservations': sum(s['lateOpenObservation'] is not None for s in states),
                        'movingOnlyInAdditionalObservation': sum(bool(s['lateOpenObservation'] and s['lateOpenObservation']['movingWithSelectedCore']) for s in states),
                        'issuedSeeks': sum(len(s['issuedFractions']) for s in states),
                        'confirmedSeeks': sum(len(s['confirmedFractions']) for s in states),
                        'plannedSeeks': len(FRACTIONS) * len(media)}
    return {'shards': [f.parent.name for f in inputs], 'works': dict(Counter(m['kind'] for m in media)),
            'media': media, 'totals': totals,
            'identityEvidence': 'Within each run the runner checked the source fingerprints at the start, midpoint and end. Cross-run matching uses container, size, duration and complete catalog stream metadata.'}


def export_files(current, destination):
    with destination.open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.writer(stream)
        fields = ('Android 取数状态', '实际选中内核且确认起播画面', '起播确认秒数', '首次实际拖动数',
                  '63% 首次落点确认秒数', '21% 首次落点确认秒数', '未通过后的独立复测',
                  'Android 取数耗时毫秒', 'Android 取数异常类型', 'Android 再次取数状态',
                  '追加观察确认起播', '从追加观察开始至确认起播的秒数')
        writer.writerow(['本轮匿名编号','类型','字节','目录时长（秒）','封装','探测视频编码','编码档次','像素格式','分辨率',
                         '探测音频编码','目录码率 bit/s','平均帧率','色彩原色','传递函数','杜比视界档次','基础层兼容编号',
                         '源站与云端一致的取样位置数', *(core + ' ' + f for core in CORES for f in fields)])
        for item in current['media']:
            states = []
            for core in CORES:
                status = item['cores'][core]
                retries = ['%g%% %s' % (v['fraction']*100, '已确认' if v['advancingPicture'] else '未确认')
                           for v in status['diagnosticRetries']]
                late = status['lateOpenObservation']
                network = status['androidRange'] or {}
                states.extend([status['androidRangeStatus'], int(status['activeCore'] and status['movingOpen']),
                               status['openConfirmationSeconds'], len(status['issuedFractions']),
                               *(status['seekConfirmationSeconds'].get(f) for f in FRACTIONS), ' / '.join(retries),
                               network.get('elapsedMs'), network.get('errorType'),
                               (status['androidRangeRecheck'] or {}).get('code'),
                               int(late['movingWithSelectedCore']) if late else None,
                               late['confirmationSecondsFromExtraObservation'] if late else None])
            resolution = f"{item['width']}×{item['height']}" if item['width'] and item['height'] else ''
            dovi = item['dolbyVision'] or {}
            writer.writerow([item['label'], '电影' if item['kind']=='Movie' else '剧集', item['bytes'], item['seconds'],
                             item['container'], item['probedCodec'], item['profile'], item['pixelFormat'], resolution,
                             ' / '.join(item['probedAudioCodecs']), item['reportedBitrate'], item['averageFrameRate'],
                             item['colorPrimaries'], item['colorTransfer'], dovi.get('dv_profile'),
                             dovi.get('dv_bl_signal_compatibility_id'),
                             len(item['matchedSourceAndRunnerRanges']), *states])


def compare(current, previous):
    current_by_key = defaultdict(list)
    for item in current['media']:
        current_by_key[item['metadataKey']].append(item)
    previous_key_count = Counter(item['metadataKey'] for item in previous['media'])
    differences = []
    for old in previous['media']:
        matches = current_by_key.get(old['metadataKey'], [])
        new = matches[0] if len(matches) == 1 and previous_key_count[old['metadataKey']] == 1 else None
        for core in CORES:
            prior = old['cores'][core]
            now = new['cores'][core] if new else {}
            differences.append({'previousLabel': old['label'], 'matchedLabel': new['label'] if new else None,
                'core': core, 'previousMoving': prior['movingOpen'] and prior['activeCore'],
                'currentMoving': now['movingOpen'] and now['activeCore'] if new else None,
                'previousConfirmed': prior['confirmedFractions'], 'currentConfirmed': now.get('confirmedFractions')})
    return differences


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence', type=Path)
    parser.add_argument('--previous', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--csv', type=Path)
    args = parser.parse_args()
    current = summarize(args.evidence)
    if args.previous:
        current['previousRunComparison'] = compare(current, summarize(args.previous))
    args.output.write_text(json.dumps(current, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if args.csv:
        export_files(current, args.csv)
    print(json.dumps({'works': current['works'], 'cores': current['totals']}))
