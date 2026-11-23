"""Observe actual system navigation pixels across app themes and rotations in cloud."""
import json
import os
from pathlib import Path
import re
import statistics
import time

from capture import capture, failure_diagnostics
from navigation import click
from power import adb, engine, ui
from ext_run import screen


def navigation_frame(width, height, dump):
    # Android 12 and newer dumps use different field names for InsetsSource.
    pattern = (r'(?:type=ITYPE_NAVIGATION_BAR|mType=navigationBars)[^\n]*?'
               r'(?:frame|mFrame)=\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    candidates = []
    for found in re.findall(pattern, dump):
        left, top, right, bottom = map(int, found)
        if 0 <= left < right <= width and 0 <= top < bottom <= height:
            if (right-left == width and bottom-top < height/3) or (bottom-top == height and right-left < width/3):
                candidates.append((left, top, right, bottom))
    assert candidates, 'Actual system navigation inset was not identified'
    return candidates[-1]


def main():
    engine.guard(); ui.pkg = 'com.yfuse'
    output = Path('results/system-bars'); output.mkdir(parents=True, exist_ok=True)
    rows = []
    try:
        # Deliberately keep system Light while app switches Light/Dark.
        adb('shell', 'cmd', 'uimode', 'night', 'no')
        adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
        adb('shell', 'cmd', 'overlay', 'enable-exclusive', '--category',
            'com.android.internal.systemui.navbar.threebutton')
        for theme, label in [('light', '浅色'), ('dark', '深色')]:
            adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
            time.sleep(1); ui.launch(); ui.root('我的'); click('外观与交互'); click(label)
            for rotation in (0, 1, 3):
                adb('shell', 'settings', 'put', 'system', 'user_rotation', str(rotation))
                deadline = time.monotonic()+15
                while time.monotonic() < deadline:
                    # wm size reports the natural panel dimensions after rotation.
                    # Observe the actual compositor output instead.
                    width, height = screen().size
                    if (width > height) == (rotation != 0): break
                    time.sleep(.3)
                else: raise AssertionError('Requested orientation did not become visible')
                time.sleep(1); ui.root('我的'); click('同步状态与恢复')
                ui.wait('同步状态', 20)
                key = theme+'-rotation-'+str(rotation)
                capture(output, key, expected='同步状态')
                dump = adb('shell', 'dumpsys', 'window', 'displays')
                (output/(key+'-display.txt')).write_text(dump)
                picture = screen().convert('RGB'); width, height = picture.size
                frame = navigation_frame(width, height, dump)
                left, top, right, bottom = frame
                pixels = [picture.getpixel((x, y)) for x in range(left+3, right-3, 5)
                          for y in range(top+3, bottom-3, 5)]
                brightness = statistics.median(sum(pixel)/3 for pixel in pixels)
                rows.append({'theme': theme, 'systemTheme': 'light', 'rotation': rotation,
                             'navigationFrame': frame, 'medianRgbBrightness': brightness})
                (output/'observations.json').write_text(json.dumps(rows, indent=2))
                assert brightness < 100 if theme == 'dark' else brightness > 170, \
                    'System navigation background did not match the visible app theme'
                # The system Back key must still return to the parent settings page.
                # Back restores the parent's scroll position, so its top heading may
                # correctly be off-screen. This row is the visible entry we opened.
                adb('shell', 'input', 'keyevent', '4'); ui.wait('同步状态与恢复', 20)
        (output/'passed.json').write_text(json.dumps({'passed': True, 'cases': len(rows),
            'scope': 'actual three-button bar background and Back across two themes and three rotations',
            'manualReviewRequired': 'icon contrast and complete screenshots'}))
    except Exception:
        failure_diagnostics(output, 'failure'); capture(output, 'failure')
        raise
    finally:
        adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')


if __name__ == '__main__':
    main()
