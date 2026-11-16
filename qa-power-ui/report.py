"""Summarize recorded activity; flag review candidates without inventing physical energy."""
import argparse
import json
from pathlib import Path
import statistics

IDLE = {'servers-idle', 'library-idle', 'settings-idle', 'paused',
        'after-player-idle', 'background-idle', 'screen-off'}


def analyze(directory):
    directory = Path(directory)
    rows = [json.loads(line) for line in (directory / 'samples.jsonl').read_text().splitlines() if line]
    summary = json.loads((directory / 'summary.json').read_text())
    phases = []
    candidates = []
    for phase in summary['phases']:
        name = phase['phase']
        samples = [r for r in rows if r['phase'] == name and
                   not r.get('intervalIncludesIntervention', r['intervention'])]
        cpu = sorted(r['cpuPercentOneCore'] for r in samples if r['cpuPercentOneCore'] is not None)
        elapsed = samples[-1]['seconds'] - samples[0]['seconds'] if len(samples) > 1 else 0
        result = {'name': name, 'recordingStatus': phase['status'], 'usableSamples': len(samples),
                  'usableSpanSeconds': round(elapsed, 2),
                  'cpuMedianPercentOneCore': statistics.median(cpu) if cpu else None,
                  'cpuP95PercentOneCore': cpu[min(len(cpu) - 1, int(.95 * len(cpu)))] if cpu else None,
                  'libraryRequestsPerMinute': None, 'controlledMediaMiBPerMinute': None}
        if elapsed > 0:
            first, last = samples[0]['endpoint'], samples[-1]['endpoint']
            if 'libraryRequests' in first and 'libraryRequests' in last:
                result['libraryRequestsPerMinute'] = round((last['libraryRequests'] - first['libraryRequests']) * 60 / elapsed, 3)
            result['controlledMediaMiBPerMinute'] = round((last['bytes'] - first['bytes']) * 60 / elapsed / 1048576, 4)
        frames = [r for r in samples if r.get('uiFramesSincePhaseReset') is not None]
        result['uiFramesPerSecond'] = None
        lifetimes = {tuple(sorted((p, v['startTicks']) for p, v in r['processes'].items())) for r in samples}
        result['observedProcessLifetimes'] = len(lifetimes)
        if len(frames) >= 2 and len(lifetimes) == 1:
            delta = frames[-1]['uiFramesSincePhaseReset'] - frames[0]['uiFramesSincePhaseReset']
            span = frames[-1]['seconds'] - frames[0]['seconds']
            if delta >= 0 and span > 0:
                result['uiFramesPerSecond'] = round(delta / span, 3)
        phases.append(result)
        # These are explicit triage rules, not a device power budget or an automatic verdict.
        if name in IDLE and len(cpu) >= 5 and statistics.median(cpu) > 5:
            candidates.append({'phase': name, 'category': 'idle CPU activity',
                               'rule': 'at least five clean intervals, median >5% of one guest CPU',
                               'status': 'needs cause verification', 'evidence': result})
        if name in {'background-idle', 'screen-off'} and (result['libraryRequestsPerMinute'] or 0) > 0:
            candidates.append({'phase': name, 'category': 'background API requests',
                               'status': 'check request ledger and cancellation timing', 'evidence': result})
        if name in IDLE and (result['uiFramesPerSecond'] or 0) > 1:
            candidates.append({'phase': name, 'category': 'idle rendering',
                               'status': 'inspect visible animation and trace before assigning a defect', 'evidence': result})
    result = {'physicalEnergyAvailable': False, 'allRequestedModulesCovered': False,
              'measurementStatus': summary['status'], 'phases': phases, 'reviewCandidates': candidates,
              'conclusion': 'Recorded evidence requires human review; no physical battery or complete UI sign-off.'}
    (directory / 'analysis.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    return rows, result


def plot(directory, rows):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    figure, axes = plt.subplots(5, 1, figsize=(13, 12), sharex=True)
    x = [r['seconds'] / 60 for r in rows]
    values = [([r['cpuPercentOneCore'] for r in rows], 'App CPU\n% of one guest core'),
              ([r['endpoint']['bytes'] / 1048576 for r in rows], 'Media transferred\nMiB, cumulative'),
              ([r['endpoint'].get('libraryRequests') for r in rows], 'Fixture API requests\ncumulative'),
              ([r.get('modelEstimate', {}).get('totalMah') for r in rows], 'UID power MODEL\nmAh, if available'),
              ([r['battery'].get('levelPercent') for r in rows], 'SIMULATED battery\nreported %')]
    starts = [(r['seconds'] / 60, r['phase']) for i, r in enumerate(rows) if i == 0 or rows[i - 1]['phase'] != r['phase']]
    for axis, (series, label) in zip(axes, values):
        if any(v is not None for v in series):
            # Preserve unavailable intervals and phase boundaries as gaps.
            axis.plot(x, [float('nan') if v is None else v for v in series], linewidth=1.1, marker='.', markersize=3)
        else:
            axis.text(.5, .5, 'Unavailable — not zero', transform=axis.transAxes, ha='center')
            axis.set_yticks([])
        axis.set_ylabel(label, fontsize=8)
        axis.grid(alpha=.2)
        for t, _ in starts:
            axis.axvline(t, color='grey', linewidth=.5, alpha=.35)
    for index, (t, name) in enumerate(starts):
        axes[0].text(t, 1.02 + .08 * (index % 2), name, rotation=50,
                     transform=axes[0].get_xaxis_transform(), fontsize=7)
    axes[-1].set_xlabel('Elapsed cloud guest observation (minutes)')
    axes[-1].set_ylim(-2, 102)
    figure.suptitle('Cloud activity curves — no physical energy measurement', y=.995)
    figure.tight_layout(rect=(0, 0, 1, .95))
    figure.savefig(Path(directory) / 'activity-curves.png', dpi=160)
    figure.savefig(Path(directory) / 'activity-curves.svg')
    plt.close(figure)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=Path)
    parser.add_argument('--plot', action='store_true')
    args = parser.parse_args()
    rows, result = analyze(args.directory)
    if args.plot:
        plot(args.directory, rows)
    print(json.dumps({'phases': len(result['phases']), 'reviewCandidates': len(result['reviewCandidates']),
                      'physicalEnergyAvailable': False}))
