"""Check the eight-module cloud recordings against Android's raw UID battery statements."""
import argparse
import csv
import json
from pathlib import Path
from statistics import median

from battery_components import component_snapshot
from build_activity_charts import plot
from verify_four_hour import system_snapshots


ROOT = Path(__file__).resolve().parent
RAW = ROOT.parent / 'legacy-engine-20261113/power-ui-20261116'
PHASES = ('widgets-idle', 'calendar-idle', 'account-login-idle', 'channels-idle',
          'file-browser-idle', 'wifi-receiver-idle', 'background-after-receiver', 'screen-off')


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def fixture_delta(before, after):
    delta = {key: after[key] - before[key] for key in ('requests', 'bytes', 'interruptedBodies')}
    assert all(value >= 0 for value in delta.values())
    return delta


def raw_uid_models(folder, phase_samples, uid):
    values = []
    for sample in phase_samples:
        if not sample.get('serviceEvidence'):
            continue
        path = (folder / sample['serviceEvidence'] / 'batterystats.txt').resolve()
        assert path.is_relative_to(folder)
        total, components = component_snapshot(path, uid)
        saved = sample['modelEstimate']
        assert total == saved['totalMah'] and components == saved['componentsMah'], path
        values.append({'second': sample['seconds'], 'totalMah': total, 'componentsMah': components})
    return values


def verify(number, run, variant):
    base = RAW / f'module-energy-{number}' / ('module-power-' + variant)
    if not (base / 'service-receipt.json').is_file():
        return
    folder = base / 'module-power'
    package = read(ROOT / f'package-verification-{number}.json')
    qual = read(ROOT / f'iteration-{number}-verification.json')
    artifact = read(base / 'service-receipt.json')
    assert artifact['run'] == run and artifact['name'] == base.name and artifact['source'] == package['source']
    assert 'app=' + package['source'] in (base / 'provenance.txt').read_text().splitlines()
    assert (base / 'apk.sha256').read_text().split()[0] == package['apkSha256']
    build = read(base / 'baseline-build-run.json')
    assert build['id'] == qual['qualificationRun'] and build['conclusion'] == 'success'
    summary, conditions = (read(folder / f) for f in ('summary.json', 'conditions.json'))
    samples = [json.loads(line) for line in (folder / 'samples.jsonl').read_text(encoding='utf-8').splitlines()]
    assert summary['package'] == 'com.yfuse' and summary['status'] == 'recorded' and len(samples) == summary['sampleCount']
    assert not summary['physicalBatteryConsumptionAvailable'] and not summary['moduleEnergyAttributionAvailable']
    assert conditions['pilot'] == (variant == 'pilot') and conditions['phaseSeconds'] == (45 if variant == 'pilot' else 600)
    assert all(s['physicalEnergyMah'] is None and s['batterySimulated'] for s in samples)
    assert all(b['seconds'] > a['seconds'] for a, b in zip(samples, samples[1:]))
    assert tuple(v['phase'] for v in summary['phases']) == PHASES
    phases, model_rows = [], []
    for phase in summary['phases']:
        name = phase['phase']
        subset = [s for s in samples if s['phase'] == name]
        assert phase['measuredSeconds'] >= conditions['phaseSeconds'] and len(subset) >= (2 if variant == 'pilot' else 18)
        cpu = [s['cpuPercentOneCore'] for s in subset if s['cpuPercentOneCore'] is not None]
        quiet = [s['cpuPercentOneCore'] for s in subset if s['cpuPercentOneCore'] is not None and not s['intervalIncludesIntervention']]
        assert all(v >= 0 for v in cpu)
        before, entered, idle = [phase[k] for k in ('localFixtureBeforeEntry', 'localFixtureAfterEntry', 'localFixtureAfterObservation')]
        models = raw_uid_models(folder, subset, summary['uid'])
        totals = [m['totalMah'] for m in models]
        valid_window = len(totals) >= 2 and all(v is not None for v in totals) and all(a <= b for a,b in zip(totals,totals[1:]))
        for m in models:
            model_rows.append({'phase': name, 'second': m['second'], 'totalMah': m['totalMah'],
                               'componentsMah': json.dumps(m['componentsMah'], sort_keys=True)})
        phases.append({'phase': name, 'seconds': phase['measuredSeconds'], 'samples':len(subset), 'cpuAccepted':len(cpu),
                       'cpuPercentOneCore':cpu, 'settledCpuIntervals':len(quiet), 'medianSettledCpuPercent':median(quiet) if quiet else None,
                       'entryFixture':fixture_delta(before, entered), 'idleFixture':fixture_delta(entered, idle),
                       'simulatedBatteryLevels':sorted({s['battery']['levelPercent'] for s in subset}),
                       'modelUidMah':[v for v in totals if v is not None], 'rawBatteryDumpCount':len(totals),
                       'modelWindowSeconds':round(models[-1]['second'] - models[0]['second'], 3) if valid_window else None,
                       'modeledUidIncreaseMah':round(totals[-1] - totals[0], 10) if valid_window else None,
                       'receiverListenerVerified':phase.get('receiverListenerVerified'),
                       'receiverClosedOnBackground':phase.get('receiverListenerClosedVerified'),
                       'serviceSnapshots':system_snapshots(folder, subset, summary['uid'])})
    receiver = next(p for p in phases if p['phase'] == 'wifi-receiver-idle')
    background = next(p for p in phases if p['phase'] == 'background-after-receiver')
    assert receiver['receiverListenerVerified'] and background['receiverClosedOnBackground']
    chart = plot(folder / 'summary.json', samples, summary)
    curve = 'long-curves/' + chart['curve']
    model_file = f'module-uid-dumps-{variant}-{number}.csv'
    with (ROOT / model_file).open('w', newline='', encoding='utf-8-sig') as stream:
        writer = csv.DictWriter(stream, fieldnames=['phase','second','totalMah','componentsMah'])
        writer.writeheader()
        writer.writerows(model_rows)
    report = {'run':run, 'source':package['source'], 'apkSha256':package['apkSha256'], 'artifactDigest':artifact['digest'],
              'pilot':conditions['pilot'], 'wallSeconds':summary['durationSeconds'], 'recordedPhaseSeconds':sum(v['seconds'] for v in phases),
              'samples':len(samples), 'phases':phases, 'curveFile':(folder/'curves.csv').relative_to(ROOT.parent).as_posix(),
              'activityCurve':curve, 'serviceCurvesCsv':str(Path(curve).with_suffix('.csv')).replace('\\','/'),
              'rawUidModelCsv':model_file, 'rawBatteryDumpCount':len(model_rows), 'physicalBatteryConsumptionAvailable':False,
              'note':'UID model differences describe the window between independently parsed Android snapshots. Fixture traffic covers the controlled IPTV/WebDAV endpoint. Cloud CPU and battery estimates do not measure physical device energy.'}
    (ROOT / f'module-power-{variant}-{number}.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(variant, len(samples), report['recordedPhaseSeconds'], len(model_rows))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('round', type=int)
    parser.add_argument('run', type=int)
    args = parser.parse_args()
    for variant in ('pilot','long'):
        verify(args.round, args.run, variant)
