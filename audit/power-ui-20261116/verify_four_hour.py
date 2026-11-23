"""Verify downloaded cloud activity evidence before attributing it to a candidate."""
import argparse
import json
import re
import statistics
from pathlib import Path

PHASES = [
    ('stopped-control', 5), ('servers-idle', 15), ('library-idle', 15),
    ('settings-idle', 15), ('settings-interaction', 10), ('playing', 35),
    ('paused', 20), ('seeking', 15), ('after-player-idle', 15),
    ('background-idle', 30), ('screen-off', 35), ('network-failure', 20),
    ('recovered-playback', 10),
]


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def advancing(picture):
    assert picture['laterPos'] > picture['firstPos'], 'No confirmed time advance'
    assert len(picture['changes']) >= 2, 'Missing independent picture observations'


def system_snapshots(root, rows, uid):
    owner = re.compile(r'\bcom\.yfuse\b|\b' + str(uid) + r'\b')
    location_count = lock_count = app_location = app_partial = app_screen = 0
    for row in rows:
        if not row.get('serviceEvidence'):
            continue
        directory = root / row['serviceEvidence']
        location = (directory / 'location.txt').read_text(encoding='utf-8')
        if 'Location Manager State:' in location:
            location_count += 1
            app_location += bool(owner.search(location))
        power = (directory / 'power.txt').read_text(encoding='utf-8')
        active = re.search(r'(?ms)^Wake Locks: size=\d+\s*\n(.*?)^\s*Suspend Blockers:', power)
        if active:
            lock_count += 1
            owned = [line for line in active[1].splitlines() if owner.search(line)]
            app_partial += any('PARTIAL_WAKE_LOCK' in line for line in owned)
            app_screen += any('SCREEN_' in line and 'WAKE_LOCK' in line for line in owned)
    return {'locationSnapshots': location_count, 'appNamedInLocationSnapshots': app_location if location_count else None,
            'wakeLockSnapshots': lock_count, 'appAttributedPartialLockSnapshots': app_partial if lock_count else None,
            'appAttributedScreenLockSnapshots': app_screen if lock_count else None}


def quiet_ui_frame_rate(rows):
    previous = None
    interrupted = False
    frame_total = 0
    duration = 0.0
    for row in rows:
        interrupted |= bool(row.get('intervalIncludesIntervention', row.get('intervention')))
        frames = row.get('uiFramesSincePhaseReset')
        if frames is None:
            continue
        if previous is not None and not interrupted and frames >= previous[1]:
            frame_total += frames - previous[1]
            duration += row['seconds'] - previous[0]
        previous = (row['seconds'], frames)
        interrupted = False
    return frame_total / duration if duration else None


def model_window(rows):
    readings = [(r['seconds'], r['modelEstimate']) for r in rows
                if r.get('modelEstimate', {}).get('totalMah') is not None]

    def increase(values):
        if len(values) < 2 or any(v is None for v in values) or any(b < a for a, b in zip(values, values[1:])):
            return None
        return round(values[-1] - values[0], 9)

    window = round(readings[-1][0] - readings[0][0], 3) if len(readings) > 1 else None
    result = {'modelSamples': len(readings), 'modelWindowSeconds': window,
              'uidModelIncreaseMah': increase([v['totalMah'] for _, v in readings])}
    for component in ('screen', 'audio', 'video'):
        result[component + 'ModelIncreaseMah'] = increase([v.get('componentsMah', {}).get(component) for _, v in readings])
    return result


def verify(folder, source, core):
    assert 'pilot' not in str(folder).lower(), 'A pilot cannot satisfy four hours'
    provenance = (folder / 'provenance.txt').read_text().splitlines()
    assert 'app=' + source in provenance, 'Wrong product source'
    root = folder / 'power'
    summary, conditions = read(root / 'summary.json'), read(root / 'conditions.json')
    rows = [json.loads(line) for line in (root / 'samples.jsonl').read_text().splitlines() if line]
    assert summary['status'] == 'recorded', 'Incomplete or failed phase'
    assert summary['physicalBatteryConsumptionAvailable'] is False
    assert summary['moduleEnergyAttributionAvailable'] is False
    assert conditions['protocol'] == 2, 'Outage and comparison protocol not qualified'
    assert summary['sampleCount'] == len(rows)
    assert all(b['seconds'] > a['seconds'] for a, b in zip(rows, rows[1:]))
    assert all(r['batterySimulated'] is True and r['physicalEnergyMah'] is None for r in rows)
    phases = summary['phases']
    assert [p['phase'] for p in phases] == [name for name, _ in PHASES]
    assert sum(p['plannedSeconds'] for p in phases) == 14400
    result = []
    for phase, (name, minutes) in zip(phases, PHASES):
        assert phase['status'] == 'recorded'
        assert phase['plannedSeconds'] == minutes * 60
        assert phase['measuredSeconds'] >= minutes * 60
        selected = [r for r in rows if r['phase'] == name]
        assert len(selected) >= 2, 'Insufficient samples: ' + name
        quiet = [r['cpuPercentOneCore'] for r in selected
                 if not r.get('intervalIncludesIntervention', r.get('intervention'))
                 and r.get('cpuPercentOneCore') is not None]
        quiet.sort()
        if name in ('playing', 'seeking', 'recovered-playback'):
            entry = phase['entryEvidence']
            assert entry['identity']['active'] and entry['identity']['engine'] == core
            advancing(entry['movingPicture'])
        if name in ('playing', 'recovered-playback'):
            advancing(phase['endPicture'])
        if name == 'paused':
            pause = phase['entryEvidence']
            assert abs(pause['positionAfter'] - pause['positionBefore']) <= 1
        if name == 'seeking':
            assert phase.get('seeks'), 'No actual drag evidence'
            for seek in phase['seeks']:
                advancing(seek)
        if name == 'network-failure':
            assert any(r['endpoint'].get('unavailableResponses', 0) > 0 for r in selected)
        endpoints = [r['endpoint'] for r in selected]
        assert all(b['bytes'] >= a['bytes'] and b['requests'] >= a['requests']
                   for a, b in zip(endpoints, endpoints[1:])), 'Endpoint counter reset'
        byte_changes = [b['seconds'] for a, b in zip(selected, selected[1:])
                        if b['endpoint']['bytes'] > a['endpoint']['bytes']]
        frame_rate = quiet_ui_frame_rate(selected)
        result.append({
            'phase': name, 'seconds': phase['measuredSeconds'], 'samples': len(selected),
            'quietCpuSamples': len(quiet),
            'cpuMedianPercentOneGuestCore': statistics.median(quiet) if quiet else None,
            'cpuP95PercentOneGuestCore': quiet[int((len(quiet)-1)*.95)] if quiet else None,
            'controlledBytesDuringSampledInterval': endpoints[-1]['bytes'] - endpoints[0]['bytes'],
            'controlledRequestsDuringSampledInterval': endpoints[-1]['requests'] - endpoints[0]['requests'],
            'sampledSeconds': round(selected[-1]['seconds'] - selected[0]['seconds'], 3),
            'lastMediaByteChangeAtSampleSeconds': round(byte_changes[-1] - selected[0]['seconds'], 3) if byte_changes else None,
            'quietMediaTailSeconds': round(selected[-1]['seconds'] - (byte_changes[-1] if byte_changes else selected[0]['seconds']), 3),
            'uiFramesPerSecondOutsideInterventions': round(frame_rate, 4) if frame_rate is not None else None,
            'confirmedSeekActions': len(phase.get('seeks', [])),
            **system_snapshots(root, selected, summary['uid']),
            **model_window(selected),
            'physicalEnergyMah': None,
        })
    return {
        'source': source, 'core': core, 'evidence': str(folder.resolve()),
        'apkSha256': (folder / 'apk.sha256').read_text().split()[0],
        'durationSeconds': summary['durationSeconds'], 'samples': len(rows),
        'simulatedBatteryLevels': sorted({r['battery']['levelPercent'] for r in rows if r['battery'].get('levelPercent') is not None}),
        'lastSystemUidModelMah': next((r['modelEstimate']['totalMah'] for r in reversed(rows)
                                      if r.get('modelEstimate', {}).get('totalMah') is not None), None),
        'verified': True, 'phases': result,
        'limits': summary['limits'] + ['Counter deltas exclude the phase-entry interval before its first sample.',
            'Model deltas span the first to last available UID model reading within the phase. A missing component or decreasing counter leaves its delta unavailable. Quantized estimates may remain constant.',
            'Wake-lock snapshot counts cover observed locks attributable to the app UID, including system WorkSource entries. Brief locks between snapshots can be missed. Membership in Location Manager snapshots is not a count of location fixes.'],
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path)
    parser.add_argument('--source', required=True)
    parser.add_argument('--core', choices=['YCore', 'SenPlayer'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    evidence = verify(args.folder, args.source, args.core)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: evidence[key] for key in ['core', 'durationSeconds', 'samples', 'verified']}))
