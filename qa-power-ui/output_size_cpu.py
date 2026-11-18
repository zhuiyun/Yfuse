"""Compare unchanged fixture playback at two output sizes on the disposable cloud guest."""
import json
import os
from pathlib import Path
import re
import time

from telemetry import process_stat, shell


PROCESSES = ('com.yfuse', 'surfaceflinger', 'media.swcodec', 'media.codec')
SIZES = ((720, 1280, 260), (360, 640, 130), (360, 640, 130), (720, 1280, 260))


def player_surfaces(dump):
    """Return actual laid-out SurfaceView sizes in the visible external player."""
    sizes = set()
    for section in re.split(r'(?m)^\s*ACTIVITY ', dump)[1:]:
        # The exported ExternalPlaybackActivity hands the item to PlayerActivity and finishes.
        component = section.splitlines()[0].split()[0]
        if component != 'com.yfuse/.feature.player.PlayerActivity' and component != 'com.yfuse/com.yfuse.feature.player.PlayerActivity':
            continue
        for line in section.splitlines():
            if 'SurfaceView{' not in line:
                continue
            bounds = re.search(r' (-?\d+),(-?\d+)-(-?\d+),(-?\d+)(?=[\s}])', line)
            if bounds:
                l, t, r, b = map(int, bounds.groups())
                if r > l and b > t:
                    sizes.add((r-l, b-t))
    return sorted(sizes)


def cpu_sample():
    samples = {}
    for name in PROCESSES:
        pid = shell('pidof', name)
        if pid.isdecimal():
            stat = process_stat(shell('cat', '/proc/' + pid + '/stat'))
            if stat:
                samples[name] = (pid, stat['startTicks'], stat['cpuTicks'], time.monotonic())
    return samples


def cpu_percent(before, after, clock_hz):
    result = {}
    for name in PROCESSES:
        first, last = before.get(name), after.get(name)
        if first and last and first[:2] == last[:2] and last[2] >= first[2] and last[3] > first[3]:
            result[name] = round(100 * (last[2]-first[2]) / clock_hz / (last[3]-first[3]), 3)
        else:
            result[name] = None
    return result


def measure(out, core, playing, observe, screen, adb, server):
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('ANDROID_SERIAL') != '127.0.0.1:15555':
        raise RuntimeError('The output-size comparison requires the disposable cloud guest')
    result = {'status': 'incomplete', 'core': core, 'outsideTimedStudy': True,
              'fixture': 'same local 640x360 24fps AVC/AAC file', 'windows': []}
    target = Path(out) / 'output-size-cpu.json'
    def save():
        target.write_text(json.dumps(result, indent=2), encoding='utf-8')

    try:
        clock_hz = int(shell('getconf', 'CLK_TCK'))
        if clock_hz <= 0:
            raise ValueError('Invalid clock frequency')
        for index, (width, height, density) in enumerate(SIZES):
            row = {'naturalDisplayOverride': [width, height], 'densityDpi': density}
            result['windows'].append(row)
            adb('shell', 'am', 'force-stop', 'com.yfuse')
            adb('shell', 'wm', 'size', f'{width}x{height}')
            adb('shell', 'wm', 'density', str(density))
            row['start'] = playing(core, f'http://127.0.0.1:18389/size-{index}.mp4')
            time.sleep(5)
            row['screenshotSize'] = screen().size
            row['surfaceViewSizes'] = player_surfaces(shell('dumpsys', 'activity', 'top'))
            row['reportedDisplay'] = shell('wm', 'size')
            row['reportedDensity'] = shell('wm', 'density')
            row['httpBefore'] = server.counters()
            start = time.monotonic()
            before = cpu_sample()
            time.sleep(30)
            after = cpu_sample()
            row['elapsedSeconds'] = round(time.monotonic()-start, 3)
            row['cpuPercentOneGuestCore'] = cpu_percent(before, after, clock_hz)
            row['httpAfter'] = server.counters()
            row['end'] = observe('size-' + str(index), None, 3600, max_wait=25)
            row['mediaAdvanceSeconds'] = row['end']['firstPos'] - row['start']['movingPicture']['laterPos']
            if row['mediaAdvanceSeconds'] < row['elapsedSeconds'] * .75:
                raise AssertionError('Player clock made insufficient progress across the CPU window')
            save()
        surfaces = [row['surfaceViewSizes'] for row in result['windows']]
        all_measured = all(row['cpuPercentOneGuestCore'][p] is not None for row in result['windows']
                           for p in ('com.yfuse', 'surfaceflinger'))
        repeated = all(surfaces) and surfaces[0] == surfaces[3] and surfaces[1] == surfaces[2]
        two_sizes = repeated and max(w*h for w, h in surfaces[0]) >= 3 * max(w*h for w, h in surfaces[1])
        result['status'] = 'recorded' if all_measured and two_sizes else 'partial'
    except (OSError, RuntimeError, AssertionError, ValueError) as error:
        result['errorType'] = type(error).__name__
        raise
    finally:
        try:
            # This workflow installs every guest with these exact display overrides.
            adb('shell', 'wm', 'size', '720x1280')
            adb('shell', 'wm', 'density', '260')
        finally:
            save()
