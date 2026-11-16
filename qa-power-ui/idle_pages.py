"""Paired real library/search pages: same cloud conditions before and after idle-render fixes."""
import json
from pathlib import Path
import time
from capture import capture, failure_diagnostics
from library import LibraryServer
from library_ui import connect, field, submit_search
from navigation import click
from power import engine, adb, ui, home
from telemetry import Recorder


def enter(name, output):
    if name == 'library-idle':
        home('媒体库')
        capture(output, name, expected='Cloud QA Library')
    elif name == 'search-idle':
        home('媒体库')
        search = ui.dock(ui.snapshot(), '搜索')
        if search is None:
            raise AssertionError('Search navigation not visible')
        ui.tap(search)
        ui.hide_keyboard()
        field('搜索电影、剧集、演员', '山海')
        submit_search('山海')
        evidence = capture(output, name, expected='山海之间', timeout=25)
        if not any('山海之间' in text for text in evidence['labels']):
            raise AssertionError('Search results did not arrive')
    elif name == 'settings-idle':
        home('我的')
    elif name == 'background-idle':
        adb('shell', 'input', 'keyevent', '3')


def main():
    engine.guard()
    ui.pkg = 'com.yfuse'
    output = Path('results/idle-pages')
    output.mkdir(parents=True, exist_ok=True)
    library = LibraryServer(output / 'library-http.jsonl')
    adb('reverse', 'tcp:18196', 'tcp:18196')
    phases = []
    status = 'incomplete'
    try:
        connect()
        home('我的')
        click('外观与交互')
        row = ui.grab('库页轮播图', 8)
        # Capture both states through the actual settings switch; do not silently
        # treat a missed tap as a controlled carousel-off condition.
        def enabled():
            tree = ui.snapshot()
            candidates = [n for n in ui.nodes(tree) if n.get('clickable') == 'true'
                          and n.get('state') in ('On', 'Off', '开启', '关闭')
                          and any('库页轮播图' in ui.labels(child) for child in n.iter('node'))]
            if len(candidates) != 1:
                raise AssertionError('Carousel switch state unavailable')
            return candidates[0].get('checked') == 'true'
        if enabled():
            ui.tap(row)
        if enabled():
            raise AssertionError('Carousel did not switch off')
        adb('shell', 'dumpsys', 'battery', 'unplug')
        adb('shell', 'dumpsys', 'battery', 'set', 'level', '80')
        adb('shell', 'dumpsys', 'battery', 'set', 'status', '3')
        adb('shell', 'dumpsys', 'batterystats', '--reset')
        recorder = Recorder(output)
        (output / 'conditions.json').write_text(json.dumps({'batterySimulated': True,
            'levelSetpoint': 80, 'chargingSetpoint': False, 'playback': False,
            'carouselEnabled': False, 'searchSubmittedByIme': True,
            'phasesMinutes': {'library-idle': 10, 'search-idle': 10, 'settings-idle': 5, 'background-idle': 5},
            'collectorSeconds': 30, 'excludeFirstSecondsPerPhase': 60,
            'scope': 'Identical synthetic library; normal theme and default motion; no physical energy measurement'}), encoding='utf-8')
        for name, duration in [('library-idle', 600), ('search-idle', 600), ('settings-idle', 300), ('background-idle', 300)]:
            row = {'phase': name, 'plannedSeconds': duration, 'status': 'incomplete'}
            phases.append(row)
            try:
                enter(name, output)
                adb('shell', 'dumpsys', 'gfxinfo', 'com.yfuse', 'reset')
                began = time.monotonic()
                count = 0
                print('IDLE_PAGE', name, duration, flush=True)
                while time.monotonic() - began < duration:
                    recorder.capture(name, {'requests': None, 'bytes': None, 'libraryRequests': library.requests},
                                     heavy=count % 4 == 0, intervention=time.monotonic() - began < 60)
                    count += 1
                    time.sleep(min(30, max(0, duration - (time.monotonic() - began))))
                row.update(status='recorded', measuredSeconds=round(time.monotonic() - began, 2))
            except Exception as error:
                row.update(status='failed', error=type(error).__name__ + ': ' + str(error)[:200])
                failure_diagnostics(output, name)
            finally:
                recorder.finish(phases, 'incomplete')
        status = 'recorded' if all(row['status'] == 'recorded' for row in phases) else 'failed'
    finally:
        if 'recorder' in locals():
            recorder.finish(phases, status)
        library.close()
        adb('shell', 'dumpsys', 'battery', 'reset', check=False)
    if status != 'recorded':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
