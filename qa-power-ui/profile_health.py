"""Measure regular server probes while settings are visible and on return to the server list."""
import json
import os
import time
from pathlib import Path

from library import LibraryServer
from library_ui import connect, ui


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or ui.adb('get-serialno').strip() != '127.0.0.1:15555':
        raise RuntimeError('The profile health check requires disposable cloud Android')
    if ui.adb('shell', 'pm', 'clear', 'com.yfuse').strip() != 'Success':
        raise RuntimeError('Could not prepare a fresh cloud installation')
    out = Path('results/power')
    log = out / 'profile-health-requests.jsonl'
    server = LibraryServer(log)
    ui.adb('reverse', 'tcp:18196', 'tcp:18196')

    def probes_since(start):
        with server.lock:
            rows = [json.loads(line) for line in log.read_text(encoding='utf-8').splitlines()]
        return [row for row in rows if row['time'] >= start and row['method'] == 'GET'
                and row['route'] == '/System/Info' and row['status'] == 200]

    try:
        connect()
        ui.root('我的')
        time.sleep(2)
        start = time.time()
        time.sleep(125)
        tree = ui.snapshot()
        assert ui.at_root(tree, '我的'), 'Settings was covered during the quiet interval'
        tab = ui.dock(tree, '服务器')
        assert tab is not None, 'Server destination was unavailable on the settings page'
        end = time.time()
        quiet = probes_since(start)
        ui.tap(tab)
        deadline = time.monotonic() + 12
        while not ui.at_root(ui.snapshot(), '服务器'):
            if time.monotonic() >= deadline:
                raise AssertionError('A single server tab tap did not open the server list')
            time.sleep(.25)
        selected = time.time()
        until = time.monotonic() + 12
        while not probes_since(end) and time.monotonic() < until:
            time.sleep(.25)
        first = probes_since(end)
        time.sleep(68)
        returned = probes_since(end)
        assert ui.at_root(ui.snapshot(), '服务器'), 'Server list was covered during its refresh interval'
        evidence = {'quietSeconds': round(end - start, 3), 'quietProbeCount': len(quiet),
                    'settingsSelected': True, 'serversSelected': True, 'navigationTaps': 1,
                    'returnNavigationSeconds': round(selected - end, 3),
                    'immediateReturnProbeCount': len(first),
                    'returnedProbesSeconds': [round(row['time'] - end, 3) for row in returned]}
        (out / 'profile-health-check.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')
        if quiet or len(first) != 1 or len(returned) != 2:
            raise AssertionError('Expected a quiet profile, one due return probe and one periodic server probe')
        if not 55 <= returned[1]['time'] - returned[0]['time'] <= 75:
            raise AssertionError('The server list did not restore its regular one-minute refresh')
    finally:
        server.close()


if __name__ == '__main__':
    main()
