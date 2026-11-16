"""Cloud Android activity measurements; never equate simulated battery with energy."""
import csv
import io
import json
from pathlib import Path
import re
import subprocess
import time


def shell(*args, timeout=25):
    result = subprocess.run(['adb', 'shell', *args], capture_output=True, timeout=timeout)
    return result.stdout.decode(errors='replace').strip()


def process_stat(text):
    # comm can contain spaces and parentheses; field 3 begins after the final ')'.
    fields = text[text.rfind(')') + 2:].split()
    if len(fields) < 22:
        return None
    return {'cpuTicks': int(fields[11]) + int(fields[12]),
            'startTicks': int(fields[19]), 'rssPages': int(fields[21])}


def battery_fields(text):
    names = {'level': 'levelPercent', 'Charge counter': 'chargeCounterReported',
             'voltage': 'voltageReported', 'temperature': 'temperatureReported'}
    values = {}
    for source, target in names.items():
        match = re.search(r'^\s*' + re.escape(source) + r':\s*(-?\d+)\s*$', text, re.M)
        values[target] = int(match[1]) if match else None
    return values


def estimated_uid_power(text, uid):
    aliases = [str(uid), 'u0a' + str(uid - 10000)] if uid >= 10000 else [str(uid)]
    result = {'totalMah': None, 'componentsMah': {}}
    for alias in aliases:
        match = re.search(r'^\s*UID ' + re.escape(alias) + r':\s*([\d.]+)(.*)$', text, re.M)
        if match:
            result['totalMah'] = float(match[1])
            result['componentsMah'] = {k: float(v) for k, v in
                re.findall(r'([A-Za-z_]+)=([\d.]+)', match[2])}
            break
    return result


class Recorder:
    def __init__(self, output, package='com.yfuse'):
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.package = package
        self.uid = int(shell('stat', '-c', '%u', '/data/user/0/' + package))
        hz = shell('getconf', 'CLK_TCK')
        self.hz = int(hz) if hz.isdigit() else None
        self.started = time.monotonic()
        self.previous = None
        self.rows = []
        self.samples = self.output / 'samples.jsonl'

    def capture(self, phase, endpoint, heavy=False, intervention=False):
        began = time.monotonic()
        pid_text = shell('pidof', self.package).split()
        processes = {}
        for pid in pid_text:
            if not pid.isdigit():
                continue
            value = process_stat(shell('cat', '/proc/' + pid + '/stat'))
            if value:
                processes[pid] = value
        row = {'seconds': round(began - self.started, 3), 'phase': phase,
               'intervention': intervention, 'processes': processes,
               'cpuPercentOneCore': None, 'endpoint': endpoint,
               'batterySimulated': True, 'physicalEnergyMah': None,
               'battery': battery_fields(shell('dumpsys', 'battery'))}
        previous = self.previous
        if (previous and previous['phase'] == phase and self.hz and processes
                and processes.keys() == previous['processes'].keys()):
            same = all(v['startTicks'] == previous['processes'][p]['startTicks'] for p, v in processes.items())
            delta = sum(v['cpuTicks'] - previous['processes'][p]['cpuTicks'] for p, v in processes.items())
            elapsed = row['seconds'] - previous['seconds']
            if same and elapsed > 0 and delta >= 0:
                row['cpuPercentOneCore'] = round(100 * delta / self.hz / elapsed, 3)
        row['intervalIncludesIntervention'] = intervention or bool(previous and previous['intervention'])
        if heavy:
            self.capture_services(row)
        row['collectorSeconds'] = round(time.monotonic() - began, 3)
        self.rows.append(row)
        self.previous = row
        with self.samples.open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(row) + '\n')
        return row

    def capture_services(self, row):
        folder = self.output / 'services' / ('%06d' % row['seconds'])
        folder.mkdir(parents=True, exist_ok=True)
        commands = {'batterystats': ['batterystats', '--charged', self.package],
                    'checkin': ['batterystats', '--checkin'],
                    'frames': ['gfxinfo', self.package], 'power': ['power'],
                    'alarms': ['alarm'], 'jobs': ['jobscheduler'], 'location': ['location'],
                    'netstats': ['netstats', 'detail'], 'idle': ['deviceidle']}
        values = {}
        for name, args in commands.items():
            values[name] = shell('dumpsys', *args)
            (folder / (name + '.txt')).write_text(values[name], encoding='utf-8')
        row['modelEstimate'] = estimated_uid_power(values['batterystats'], self.uid)
        row['uidCheckinRecords'] = [r for r in csv.reader(io.StringIO(values['checkin']))
                                   if len(r) >= 4 and r[1] == str(self.uid)]
        match = re.search(r'Total frames rendered:\s*(\d+)', values['frames'])
        row['uiFramesSincePhaseReset'] = int(match[1]) if match else None
        row['serviceEvidence'] = str(folder.relative_to(self.output))

    def finish(self, phases, status):
        summary = {'schema': 1, 'status': status, 'package': self.package, 'uid': self.uid,
                   'clockTicksPerSecond': self.hz, 'sampleCount': len(self.rows), 'phases': phases,
                   'durationSeconds': round(time.monotonic() - self.started, 2),
                   'physicalBatteryConsumptionAvailable': False,
                   'moduleEnergyAttributionAvailable': False,
                   'limits': ['Android guest has simulated battery and no physical power rails.',
                              'UID power, when present, is a device power-profile model, not measured mAh.',
                              'CPU is main app process CPU; endpoint bytes cover only the controlled media server.',
                              'ADB sampling can wake the guest; this is not natural device suspend validation.',
                              'Software cloud rendering cannot predict a physical device GPU or decoder energy.',
                              'Missing counters remain null, never zero.']}
        (self.output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
        fields = ['seconds', 'phase', 'intervention', 'cpuPercentOneCore', 'batteryLevelSimulated',
                  'modelEstimatedMah', 'uiFramesSincePhaseReset', 'endpointRequests', 'endpointBytes',
                  'libraryRequests', 'collectorSeconds']
        with (self.output / 'curves.csv').open('w', newline='', encoding='utf-8') as stream:
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            for row in self.rows:
                writer.writerow({**{key: row.get(key) for key in fields[:4]},
                    'batteryLevelSimulated': row['battery'].get('levelPercent'),
                    'modelEstimatedMah': row.get('modelEstimate', {}).get('totalMah'),
                    'uiFramesSincePhaseReset': row.get('uiFramesSincePhaseReset'),
                    'endpointRequests': row['endpoint']['requests'], 'endpointBytes': row['endpoint']['bytes'],
                    'libraryRequests': row['endpoint'].get('libraryRequests'),
                    'collectorSeconds': row['collectorSeconds']})
