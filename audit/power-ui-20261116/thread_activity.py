"""Summarize the measured CPU time of app threads that survived between two snapshots."""
import csv
from collections import Counter
from pathlib import Path


def matched_thread_intervals(samples, hz, core):
    previous = None
    intervened = False
    phase = None
    for row in samples:
        if row['phase'] != phase:
            previous = None
            intervened = False
            phase = row['phase']
        intervened |= row.get('intervalIncludesIntervention', row['intervention'])
        snap = row.get('threadCpuSnapshot')
        if not snap:
            continue
        if previous is not None and not intervened and hz:
            elapsed = snap['seconds'] - previous['seconds']
            same_processes = bool(snap['processes']) and previous['processes'].keys() == snap['processes'].keys()
            if elapsed > 0 and same_processes and all(
                ts[pid]['startTicks'] == previous['processes'][pid][pid]['startTicks']
                for pid, ts in snap['processes'].items()
            ):
                totals = Counter()
                for pid, threads in snap['processes'].items():
                    old = previous['processes'][pid]
                    for tid, thread in threads.items():
                        before = old.get(tid)
                        if before and before['startTicks'] == thread['startTicks'] and before['name'] == thread['name']:
                            delta = thread['cpuTicks'] - before['cpuTicks']
                            if delta >= 0:
                                totals[thread['name']] += delta
                if totals:
                    yield {'core': core, 'phase': phase, 'fromSeconds': previous['seconds'],
                           'toSeconds': snap['seconds'], 'seconds': elapsed, 'ticks': totals}
        previous = snap
        intervened = False


def export(intervals, hz, path):
    with Path(path).open('w', encoding='utf-8-sig', newline='') as out:
        writer = csv.writer(out)
        writer.writerow(['engine', 'phase', 'from_seconds', 'to_seconds', 'thread_name',
                         'cpu_ticks', 'ticks_per_second', 'percent_of_one_guest_cpu'])
        for interval in intervals:
            for name, ticks in interval['ticks'].most_common():
                writer.writerow([interval['core'], interval['phase'], interval['fromSeconds'], interval['toSeconds'],
                                 name, ticks, hz, round(100 * ticks / hz / interval['seconds'], 3)])


def phase_summary(intervals, hz):
    summary = {}
    for phase in dict.fromkeys(v['phase'] for v in intervals):
        selected = [v for v in intervals if v['phase'] == phase]
        ticks, seconds = Counter(), Counter()
        for row in selected:
            ticks.update(row['ticks'])
            for name in row['ticks']:
                seconds[name] += row['seconds']
        summary[phase] = {
            'quietIntervals': len(selected), 'quietSeconds': round(sum(r['seconds'] for r in selected), 2),
            'highestByMeasuredCpuTime': [
                {'name': name, 'cpuSeconds': round(total/hz, 3), 'matchedSeconds': round(seconds[name], 2),
                 'percentOfOneGuestCoreWhenMatched': round(100*total/hz/seconds[name], 3)}
                for name, total in ticks.most_common(5) if total > 0
            ],
        }
    return summary
