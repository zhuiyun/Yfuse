"""Create exportable cloud activity curves without turning model estimates into energy measurements."""
import csv
import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent
EVIDENCE = ROOT.parent / 'legacy-engine-20261113' / 'power-ui-20261116'
sys.path.insert(0, str(ROOT / 'plot-deps'))
os.environ['MPLCONFIGDIR'] = str(ROOT / 'plot-cache')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties

FONT = FontProperties(fname='C:/Windows/Fonts/msyh.ttc')
PHASES = {
    'foreground-idle': '前台空闲', 'background-idle': '后台空闲', 'screen-off': '熄屏',
    'foreground-return': '返回前台播放', 'foreground-playing': '前台播放', 'foreground-paused': '前台暂停',
    'background-paused': '后台暂停', 'screenoff-paused': '熄屏暂停', 'force-stopped': '停止进程',
    'stopped': '停止进程', 'servers-idle': '服务器页空闲', 'library-idle': '媒体库空闲',
    'settings-idle': '设置页空闲', 'settings-interactions': '设置交互', 'playing': '播放',
    'paused': '暂停', 'seeking': '拖动', 'after-player-idle': '退出播放器',
    'network-failure': '网络中断', 'network-recovered': '网络恢复',
    'stopped-control': '停止进程对照', 'settings-interaction': '设置交互',
    'recovered-playback': '恢复播放', 'final': '结束采样', 'search-idle': '搜索页空闲',
    'widgets-idle': '组件管理静置', 'calendar-idle': '追剧日历', 'account-login-idle': '未登录账号入口',
    'channels-idle': '频道列表', 'file-browser-idle': '文件浏览', 'wifi-receiver-idle': '临时接收待机',
    'background-after-receiver': '关闭接收后后台',
    'background-audio-requested': '请求后台音频', 'screenoff-audio-requested': '请求熄屏音频',

}


def duration_ms(text):
    parts = re.findall(r'(\d+)(ms|s|m|h|d)', text)
    return sum(int(n) * {'ms': 1, 's': 1000, 'm': 60000, 'h': 3600000, 'd': 86400000}[u]
               for n, u in parts) if parts else None


def service_values(directory, uid):
    result = {'uidRxBytes': None, 'uidTxBytes': None, 'uidPartialWakeMs': None,
              'uidJobActiveMs': None, 'uidBackgroundJobMs': None}
    network = directory / 'netstats.txt'
    if network.exists():
        text = network.read_text(encoding='utf-8')
        block = text.split('UID stats:', 1)[-1].split('UID tag stats:', 1)[0]
        current = False
        values = []
        for line in block.splitlines():
            if 'ident=' in line:
                current = re.search(r'\buid=' + str(uid) + r'\b', line) is not None
            elif current:
                row = dict(re.findall(r'\b(rb|tb)=(\d+)', line))
                if 'rb' in row and 'tb' in row:
                    values.append((int(row['rb']), int(row['tb'])))
        if values:
            result.update(uidRxBytes=sum(v[0] for v in values), uidTxBytes=sum(v[1] for v in values))
    battery = directory / 'batterystats.txt'
    if battery.exists():
        text = battery.read_text(encoding='utf-8')
        alias = 'u0a' + str(uid - 10000) if uid >= 10000 else str(uid)
        match = re.search(r'^  ' + re.escape(alias) + r':\s*\n(.*?)(?=^  \S|^\S|\Z)', text, re.M | re.S)
        block = match[1] if match else ''
        wake = re.search(r'TOTAL wake: (.*?) blamed partial', block)
        if wake:
            result['uidPartialWakeMs'] = duration_ms(wake[1])
        jobs = re.search(r'Job com\.yfuse/androidx\.work.*?: (.*?) realtime.*?(?:, (.*?) background)', block)
        if jobs:
            result['uidJobActiveMs'] = duration_ms(jobs[1])
            result['uidBackgroundJobMs'] = duration_ms(jobs[2])
    return result


def plot(source, samples, summary):
    relative = source.parent.relative_to(EVIDENCE)
    key = '-'.join(relative.parts)
    output = ROOT / 'long-curves'
    output.mkdir(exist_ok=True)
    uid = summary['uid']
    conditions_path = source.with_name('conditions.json')
    protocol = json.loads(conditions_path.read_text(encoding='utf-8')).get('protocol') if conditions_path.exists() else None
    for row in samples:
        row['serviceCounters'] = service_values(source.parent / row['serviceEvidence'], uid) if row.get('serviceEvidence') else {}
    x = [r['seconds'] / 60 for r in samples]
    starts = [(i, r['phase']) for i, r in enumerate(samples)
              if r['phase'] != 'final' and (i == 0 or samples[i - 1]['phase'] != r['phase'])]
    fig, axes = plt.subplots(6, 1, figsize=(13, 16), sharex=True)
    colors = ['#275ca5', '#c86e2b', '#4b896a', '#965da6']

    def line(axis, values, label, color=colors[0]):
        if any(v is not None for v in values):
            axis.plot(x, [float('nan') if v is None else v for v in values], '.', markersize=3,
                      label=label, color=color)
            axis.legend(prop=FONT, loc='best', framealpha=.90)
            return True
        return False

    available = []
    available.append(line(axes[0], [r['cpuPercentOneCore'] for r in samples], '主进程 CPU'))
    available.append(line(axes[1], [(r['serviceCounters'].get('uidRxBytes') + r['serviceCounters'].get('uidTxBytes')) / 1048576
                                   if r['serviceCounters'].get('uidRxBytes') is not None else None for r in samples], '系统 UID 收发累计'))
    available[-1] |= line(axes[1], [r['endpoint']['bytes'] / 1048576 if r['endpoint'].get('bytes') is not None else None for r in samples], '受控媒体服务累计', colors[1])
    available.append(line(axes[2], [r['serviceCounters'].get('uidPartialWakeMs') / 1000 if r['serviceCounters'].get('uidPartialWakeMs') is not None else None for r in samples], '应用部分唤醒锁累计秒数'))
    available[-1] |= line(axes[2], [r['serviceCounters'].get('uidBackgroundJobMs') / 1000 if r['serviceCounters'].get('uidBackgroundJobMs') is not None else None for r in samples], '系统后台任务累计秒数', colors[1])
    available.append(line(axes[3], [r.get('uiFramesSincePhaseReset') for r in samples], '系统 UI 帧数（部分测试在阶段入口重置）'))
    available.append(line(axes[4], [r.get('modelEstimate', {}).get('totalMah') for r in samples], '系统 UID 模型总估算'))
    for index, component in enumerate(['screen', 'wakelock']):
        available[-1] |= line(axes[4], [r.get('modelEstimate', {}).get('componentsMah', {}).get(component) for r in samples], component + ' 模型分项', colors[index + 1])
    available.append(line(axes[5], [r['battery'].get('levelPercent') for r in samples], '模拟电池电量'))
    labels = ['单个模拟 CPU 核 %', 'MiB（网络计数）', '秒（系统累计）', '帧（系统计数）', 'mAh（系统模型）', '%（模拟电量）']
    for index, (axis, present, label) in enumerate(zip(axes, available, labels)):
        if not present:
            axis.text(.5, .5, '系统未提供此计数', transform=axis.transAxes, ha='center', fontproperties=FONT)
            axis.set_yticks([])
        axis.set_ylabel(label, fontproperties=FONT, fontsize=9)
        axis.grid(alpha=.2)
        for n, (start, phase) in enumerate(starts):
            end = x[starts[n + 1][0]] if n + 1 < len(starts) else x[-1]
            if n % 2 == 0:
                axis.axvspan(x[start], end, color='#ccd7e5', alpha=.15)
            axis.axvline(x[start], color='#8a97a6', alpha=.4, linewidth=.5)
            if index == 0:
                axis.text(x[start], 1.025, str(n + 1).zfill(2), ha='center',
                          transform=axis.get_xaxis_transform(), fontproperties=FONT, fontsize=8)
    axes[5].set_ylim(-2, 102)
    axes[-1].set_xlabel('云端观察分钟数', fontproperties=FONT)
    core = next((name for name in ('YCore', 'SenPlayer') if name in str(relative)), None)
    run_label = {'power-final-24': '修复版', 'power-baseline-141': '对照版'}.get(relative.parts[0], relative.parts[0])
    title = (core or '功能模块') + ' 云端活动曲线 · ' + run_label
    fig.suptitle(title, fontproperties=FONT, fontsize=16, y=.995)
    fig.text(.5, .966, f'{x[-1]:.1f} 分钟  ·  {len(starts)} 个场景  ·  {len(samples)} 次采样',
             ha='center', fontproperties=FONT, fontsize=10)
    # Number short phases so adjacent labels never collide; the legend retains full names.
    for n, (_, phase) in enumerate(starts):
        label = '旧版断网步骤（缺少 503 验证）' if phase == 'network-failure' and protocol != 2 else PHASES.get(phase, phase)
        fig.text(.08 + .31 * (n % 3), .945 - .018 * (n // 3),
                 str(n + 1).zfill(2) + '  ' + label, fontproperties=FONT, fontsize=9)
    legend_bottom = .945 - .018 * ((len(starts) - 1) // 3)
    fig.tight_layout(rect=(0, 0, 1, legend_bottom - .025))
    fig.savefig(output / (key + '.png'), dpi=140, bbox_inches='tight', pad_inches=.15)
    fig.savefig(output / (key + '.svg'), bbox_inches='tight', pad_inches=.15)
    plt.close(fig)
    exported = []
    for row in samples:
        exported.append({'seconds': row['seconds'], 'phase': row['phase'], 'cpuPercentOneGuestCore': row['cpuPercentOneCore'],
                         **{key: row['serviceCounters'].get(key) for key in ['uidRxBytes', 'uidTxBytes', 'uidPartialWakeMs', 'uidJobActiveMs', 'uidBackgroundJobMs']},
                         'uiFramesSincePhaseReset': row.get('uiFramesSincePhaseReset'),
                         'controlledMediaBytes': row['endpoint'].get('bytes'),
                         'controlledMediaRequests': row['endpoint'].get('requests'),
                         'controlledLibraryRequests': row['endpoint'].get('libraryRequests'),
                         **{'uidModel' + name.title() + 'Mah': row.get('modelEstimate', {}).get('componentsMah', {}).get(name)
                            for name in ('screen', 'audio', 'video', 'wakelock')},
                         'uidModelMahNotMeasured': row.get('modelEstimate', {}).get('totalMah'),
                         'simulatedBatteryPercent': row['battery'].get('levelPercent'), 'physicalEnergyMah': None})
    with (output / (key + '.csv')).open('w', encoding='utf-8-sig', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=list(exported[0]))
        writer.writeheader()
        writer.writerows(exported)
    return {'evidence': str(relative), 'curve': key + '.png', 'lastCounters': next((r['serviceCounters'] for r in reversed(samples) if r['serviceCounters']), {}),
            'physicalEnergyAvailable': False, 'missingIsNotZero': True}


if __name__ == '__main__':
    results = []
    for source in sorted(EVIDENCE.rglob('summary.json')):
        if 'pilot' in str(source) or not source.with_name('samples.jsonl').exists():
            continue
        summary = json.loads(source.read_text(encoding='utf-8'))
        if summary.get('status') != 'recorded' or summary.get('durationSeconds', 0) < 1800:
            continue
        samples = [json.loads(line) for line in source.with_name('samples.jsonl').read_text(encoding='utf-8').splitlines() if line]
        results.append(plot(source, samples, summary))
    (ROOT / 'service-counter-index.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(results, ensure_ascii=True))
