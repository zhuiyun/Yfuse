"""Export initial and repeated observations for each original file on both cores."""
import argparse
import csv
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
CORES = ('YCore', 'SenPlayer')


def observations(measures, case, core):
    evidence = {kind: [m for m in measures if m['case'] == case + '-' + kind]
                for kind in ('format', 'engine', 'opened', 'issued', 'seek', 'complete', 'incomplete')}
    pictures = evidence['opened'] + evidence['seek']
    assert all(m['laterPos'] > m['firstPos'] and len(m['changes']) >= 2 for m in pictures)
    assert all(len(evidence[k]) <= 1 for k in ('format', 'engine', 'opened', 'complete', 'incomplete'))
    seeks = {m['fraction'] for m in evidence['seek']}
    assert seeks <= {.63, .21} and len(seeks) == len(evidence['seek'])
    active = any(m['active'] and m['engine'] == core for m in evidence['engine'])
    complete = any(m['ok'] for m in evidence['complete'])
    if complete:
        assert active and evidence['format'] and evidence['opened'] and len(seeks) == 2 and not evidence['incomplete']
    if not evidence['format']:
        assert evidence['incomplete'] and not any(evidence[k] for k in ('engine', 'opened', 'issued', 'seek'))
    first = (evidence['format'] or evidence['incomplete'])[0]
    note = ('通过' if complete else '原文件探测未完成' if not evidence['format'] else
            '起播画面未确认' if not evidence['opened'] else '拖动落点未全部确认')
    return first['kind'], next(iter(evidence['format']), None), [
        int(active), int(bool(evidence['opened'])), evidence['opened'][0]['secToConfirm'] if evidence['opened'] else '',
        len(evidence['issued']), int(.63 in seeks), int(.21 in seeks), int(complete), note]


def export(round_no):
    summary = json.loads((ROOT / f'final-public-partial-{round_no}.json').read_text('utf8'))
    folder = ROOT.parent / f'legacy-engine-20261113/power-ui-20261116/final-public-{round_no}'
    cases = {}
    for core in CORES:
        first_paths = sorted(folder.glob(f'integrated-original-{core}-*/metrics.json'))
        retry_paths = sorted(folder.glob(f'recheck-*/rechecked-original-{core}-*/metrics.json'))
        for path in first_paths + retry_paths:
            assert 'app=' + summary['source'] in path.with_name('provenance.txt').read_text().splitlines()
            assert path.with_name('apk.sha256').read_text().split()[0] == summary['apkSha256']
            measures = json.loads(path.read_text('utf8'))['measurements']
            ids = {m['case'].rsplit('-', 1)[0] for m in measures if m['case'].endswith(('-format', '-incomplete'))}
            retry_id = None if path in first_paths else int(path.parent.parent.name.removeprefix('recheck-'))
            for case in ids:
                kind, fmt, result = observations(measures, case, core)
                combined = cases.setdefault(case, {'kind': kind, 'format': None, 'formatFrom': '', 'attempts': {}})
                assert combined['kind'] == kind
                if fmt:
                    assert combined['format'] is None or combined['format'] == fmt
                    if combined['format'] is None or retry_id is None:
                        combined.update(format=fmt, formatFrom='首轮' if retry_id is None else f'复测 {retry_id}')
                attempts = combined['attempts'].setdefault(core, {})
                assert retry_id not in attempts
                attempts[retry_id] = result
        observed = [record['attempts'][core] for record in cases.values() if core in record['attempts']]
        assert sum(a[None][-2] for a in observed if None in a) == summary['cores'][core]['originalFilesCompleted']
        assert sum(any(r[-2] for r in a.values()) for a in observed) == summary['cores'][core]['originalFilesCoveredAfterRechecks']
    columns = ['内核身份已确认', '起播画面已确认', '起播确认秒数', '实际发起拖动', '63%落点已确认', '21%落点已确认', '首轮通过', '首轮结果',
               '复测运行与结果', '含复测通过']
    with (ROOT / f'public-original-files-{round_no}.csv').open('w', encoding='utf-8-sig', newline='') as dest:
        writer = csv.writer(dest)
        writer.writerow(['测试文件标识', '类型', '字节', '时长（秒）', '封装', '视频编码', '分辨率', '音频编码', '格式数据来自',
                         *(core + ' ' + col for core in CORES for col in columns)])
        for case, record in sorted(cases.items()):
            fmt = record['format'] or {}
            video = [s for s in fmt.get('tracks', []) if s['codec_type'] == 'video']
            audio = [s for s in fmt.get('tracks', []) if s['codec_type'] == 'audio']
            outcomes = []
            for core in CORES:
                attempts = record['attempts'].get(core, {})
                outcomes.extend(attempts.get(None, [''] * 8))
                outcomes.append(' / '.join(f'{run} {row[-1]}' for run, row in attempts.items() if run is not None))
                outcomes.append(int(any(row[-2] for row in attempts.values())) if attempts else '')
            writer.writerow([case, {'movie': '电影', 'episode': '剧集'}[record['kind']], fmt.get('bytes'), fmt.get('seconds'),
                             fmt.get('container'), '/'.join(sorted({v['codec_name'] for v in video})),
                             '/'.join(sorted({f"{v['width']}×{v['height']}" for v in video})),
                             '/'.join(sorted({a['codec_name'] for a in audio})), record['formatFrom'], *outcomes])
    return len(cases)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('round', type=int)
    print(export(parser.parse_args().round))
