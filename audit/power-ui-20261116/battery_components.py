"""Verify Android's per-UID battery model against raw dumps and group observations by app activity."""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'plot-deps'))
os.environ['MPLCONFIGDIR'] = str(ROOT / 'plot-cache')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties

FONT = FontProperties(fname='C:/Windows/Fonts/msyh.ttc')
LABELS = {
    'stopped-control': '停止', 'servers-idle': '服务器', 'library-idle': '媒体库', 'settings-idle': '设置',
    'settings-interaction': '设置操作', 'playing': '播放', 'paused': '暂停', 'seeking': '拖动',
    'after-player-idle': '退出播放器', 'background-idle': '后台', 'screen-off': '熄屏',
    'network-failure': '断网', 'recovered-playback': '恢复播放',
}
FIELDS = ('total', 'screen', 'audio', 'video', 'wakelock', 'cpu', 'wifi', 'gps')
NUMBER = r'(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?'


def read(path):
    return json.loads(path.read_text(encoding='utf8'))


def component_snapshot(path, uid):
    user, app = divmod(uid, 100000)
    assert app >= 10000
    token = f'u{user}a{app-10000}'
    data = path.read_text(encoding='utf8', errors='replace')
    block = data.split('Estimated power use (mAh):', 1)
    assert len(block) == 2, path
    record = re.search(r'^\s*UID\s+' + re.escape(token) + r':\s*(' + NUMBER + r')([^\n]*)', block[1], re.M)
    if not record:
        return None, {}
    components = {name: float(value) for name, value in re.findall(r'\b([a-z_]+)=(' + NUMBER + r')', record[2])}
    return float(record[1]), components


def verify_record(record):
    raw = Path(record['evidence']) / 'power'
    summary = read(raw / 'summary.json')
    assert summary['package'] == 'com.yfuse' and not summary['physicalBatteryConsumptionAvailable']
    samples = [json.loads(line) for line in (raw / 'samples.jsonl').read_text(encoding='utf8').splitlines()]
    assert len(samples) == record['samples'] == summary['sampleCount']
    phases = [row['phase'] for row in record['phases']]
    assert len(phases) == 13 and set(phases) == set(LABELS)
    snapshots = []
    for sample in samples:
        if not sample.get('serviceEvidence'):
            continue
        report = raw / sample['serviceEvidence'] / 'batterystats.txt'
        assert report.resolve().is_relative_to(raw.resolve())
        total, components = component_snapshot(report, int(summary['uid']))
        saved = sample['modelEstimate']
        assert total == saved['totalMah'] and components == saved['componentsMah'], report
        snapshots.append({'second': sample['seconds'], 'phase': sample['phase'], 'total': total, **components})
    assert next((row['total'] for row in reversed(snapshots) if row['total'] is not None), None) == record['lastSystemUidModelMah']
    assert all(s['physicalEnergyMah'] is None for s in samples)
    by_phase = []
    for phase in phases:
        ticks = [s for s in snapshots if s['phase'] == phase]
        observed = [s for s in samples if s['phase'] == phase]
        assert len(ticks) >= 2 and len(observed) >= 2
        first, last = ticks[0], ticks[-1]
        row = {'engine': record['core'], 'phase': phase, 'samples': len(observed), 'modelSnapshots': len(ticks),
               'firstSnapshotSeconds': first['second'], 'lastSnapshotSeconds': last['second'],
               'observationSpanSeconds': round(last['second']-first['second'], 3),
               'firstSimulatedBatteryPercent': observed[0]['battery']['levelPercent'],
               'lastSimulatedBatteryPercent': observed[-1]['battery']['levelPercent']}
        for field in FIELDS:
            values = [tick.get(field) for tick in ticks]
            monotonic = all(v is not None for v in values) and all(a <= b for a, b in zip(values, values[1:]))
            row[field+'DeltaModelMah'] = round(values[-1]-values[0], 10) if monotonic else None
        by_phase.append(row)
    return snapshots, by_phase


def plot(round_no, engines, by_phase):
    fig, axes = plt.subplots(3, 2, figsize=(14, 8), sharex=True, sharey='row', layout='constrained')
    config = [(['total', 'screen'], ['UID 总估算', '屏幕分项'], 1, 'mAh'),
              (['audio'], ['音频分项'], 1, 'mAh'), (['video'], ['视频分项'], 1000, 'µAh')]
    colors = {'total': '#254967', 'screen': '#ac5c2d', 'audio': '#157560', 'video': '#81539b'}
    for col, (core, points) in enumerate(engines.items()):
        phases = [p for p in by_phase if p['engine'] == core]
        centres = []
        for i, row in enumerate(phases):
            start = row['firstSnapshotSeconds'] / 60
            end = phases[i+1]['firstSnapshotSeconds'] / 60 if i+1 < len(phases) else points[-1]['second']/60
            centres.append((start+end)/2)
            if i % 2 == 0:
                for ax in axes[:,col]:
                    ax.axvspan(start, end, color='#667b96', alpha=.07, linewidth=0)
        for row, (keys, names, scale, unit) in enumerate(config):
            ax = axes[row,col]
            ax.spines[['top','right']].set_visible(False)
            ax.grid(axis='y', alpha=.22)
            for key, name in zip(keys, names):
                values = [s.get(key) for s in points]
                if all(v is None for v in values):
                    ax.text(.5, .48, '系统未报告该应用的'+name, transform=ax.transAxes,
                            ha='center', va='center', fontproperties=FONT, color='#555d66')
                    continue
                ax.plot([s['second']/60 for s in points], [float('nan') if v is None else v*scale for v in values],
                        label=name, color=colors[key], linewidth=1.65)
            if ax.lines:
                ax.legend(loc='upper left', prop=FONT, frameon=False)
            ax.set_ylim(bottom=0)
            ax.set_ylabel(unit)
        axes[0,col].set_title(core, fontsize=14)
        axes[-1,col].set_xticks(centres, [LABELS[p['phase']] for p in phases],
                                 rotation=53, ha='right', fontproperties=FONT, fontsize=9)
        axes[-1,col].set_xlabel('按时间排列的应用场景（各色带宽度对应记录时长）', fontproperties=FONT)
    fig.suptitle('四小时 Android 电池模型分项估算 · 第 '+str(round_no)+' 轮', fontproperties=FONT, fontsize=17)
    fig.savefig(ROOT / f'uid-model-components-{round_no}.png', dpi=150)
    plt.close(fig)


def make(round_no):
    study = read(ROOT / f'four-hour-verification-{round_no}.json')
    engines, rows = {}, []
    for rec in study['results']:
        assert rec['verified'] and rec['source'] == study['source']
        snapshots, phases = verify_record(rec)
        engines[rec['core']] = snapshots
        rows.extend(phases)
    assert len(engines) == 2 and len(rows) == 26
    with (ROOT / f'uid-model-by-phase-{round_no}.csv').open('w', encoding='utf-8-sig', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    plot(round_no, engines, rows)
    coverage = {core: {'snapshots': len(series), 'lastModelMah': {k: series[-1].get(k) for k in FIELDS},
                       'nonemptyModelSnapshots': dict(Counter(key for row in series for key in FIELDS if row.get(key) is not None))}
                for core, series in engines.items()}
    result = {'run':study['run'], 'source':study['source'], 'apkSha256':study['apkSha256'], 'engines':coverage,
              'physicalEnergyMeasured':False, 'phaseRows':len(rows),
              'definition':'A phase delta uses its first and last system snapshots only. Empty values mean Android did not report the UID or component at one or both endpoints. Gaps between phases are unassigned. Cumulative values are rounded by Android.'}
    (ROOT / f'uid-model-components-{round_no}.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(round_no, {core:(v['snapshots'],v['lastModelMah']['total']) for core,v in coverage.items()})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('round', type=int, nargs='+')
    for requested in parser.parse_args().round:
        make(requested)
