"""Matched carousel-on recordings; preserve real UI, idle CPU, and manual navigation evidence."""
import json
import os
from pathlib import Path
import re
import time

from capture import capture, failure_diagnostics
from library import LibraryServer
from library_ui import connect
from power import engine, adb, ui, home
from telemetry import Recorder


def carousel_state():
    states = {n.get('state', '') for n in ui.nodes(ui.snapshot())
              if re.fullmatch(r'第 \d+ 项，共 \d+ 项', n.get('state', ''))}
    assert len(states) == 1, 'Exactly one visible carousel state is required'
    state = states.pop()
    assert int(re.findall(r'\d+', state)[1]) > 1, 'Real multiple-slide carousel required'
    return state


def main():
    engine.guard(); ui.pkg = 'com.yfuse'
    output = Path('results/carousel-idle'); output.mkdir(parents=True, exist_ok=True)
    library = LibraryServer(output/'library-http.jsonl')
    phases = []; status = 'incomplete'
    expected_settled = os.environ['QA_EXPECT_CAROUSEL_SETTLED'] == 'true'
    try:
        adb('reverse', 'tcp:18196', 'tcp:18196'); connect(); home('媒体库')
        capture(output, 'carousel-enabled', expected='Cloud QA Library')
        carousel_state()
        recorder = Recorder(output)
        (output/'conditions.json').write_text(json.dumps({
            'batterySimulated': True, 'carouselEnabled': True, 'expectFinitePreview': expected_settled,
            'scope': 'Identical loaded library with carousel enabled; no physical energy measurement',
            'collectorSeconds': 30, 'firstQuietSecondsExcluded': 60}))
        for name, duration in [('carousel-preview', 90), ('carousel-rest', 300),
                               ('carousel-manual-preview', 90), ('carousel-rest-after-touch', 300),
                               ('background-idle', 180)]:
            if name == 'carousel-manual-preview':
                before = carousel_state()
                adb('shell', 'input', 'swipe', 580, 290, 140, 290, 450)
                time.sleep(1)
                after = carousel_state()
                assert before != after, 'Manual carousel navigation did not change the displayed item'
                capture(output, 'manual-slide-confirmed')
            elif name == 'background-idle':
                adb('shell', 'input', 'keyevent', '3')
            phase = {'phase': name, 'plannedSeconds': duration, 'status': 'incomplete', 'states': []}
            phases.append(phase); start = time.monotonic(); count = 0
            adb('shell', 'dumpsys', 'gfxinfo', 'com.yfuse', 'reset')
            while time.monotonic()-start < duration:
                elapsed = time.monotonic()-start
                if name.startswith('carousel-rest') and elapsed >= 60:
                    phase['states'].append(carousel_state())
                recorder.capture(name, {'requests': 0, 'bytes': 0, 'libraryRequests': library.requests},
                                 heavy=count % 2 == 0, intervention=elapsed < 60)
                count += 1
                time.sleep(min(30, max(0, duration-(time.monotonic()-start))))
            if expected_settled and name.startswith('carousel-rest'):
                assert len(phase['states']) >= 5 and len(set(phase['states'])) == 1, 'Carousel kept advancing while idle'
            phase.update(status='recorded', measuredSeconds=round(time.monotonic()-start, 2))
            capture(output, name+'-end')
            recorder.finish(phases, 'incomplete')
        status = 'recorded'
    except Exception:
        failure_diagnostics(output, 'carousel-failure')
        capture(output, 'carousel-failure')
        raise
    finally:
        if 'recorder' in locals(): recorder.finish(phases, status)
        library.close()


if __name__ == '__main__':
    main()
