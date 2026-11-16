"""Interact with visible controls, not duplicate headings or occluded semantics."""
import re
import time
import sys
from pathlib import Path
sys.path.insert(0, 'qa-functional')
import features as ui
from ext_run import adb, screen


def bounds(node):
    values = list(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))
    return values if len(values) == 4 else [0, 0, 0, 0]


def vertical_pane(node):
    if node.get('scrollable') != 'true' or 'HorizontalScrollView' in node.get('class', ''):
        return False
    # Compose exposes both LazyRow and LazyColumn as android.view.View. Directional
    # accessibility actions distinguish a horizontal filter strip from page content.
    return node.get('scroll-horizontal') != 'true' or node.get('scroll-vertical') == 'true'


def system_bar_bounds(dump, width, height):
    """Exclude visible system bars even when edge-to-edge semantics extend behind them."""
    area = [0, 0, width, height]
    recognized = False
    for line in dump.splitlines():
        if not re.search(r'(?:ITYPE_(?:STATUS|NAVIGATION)_BAR|type=(?:statusBars|navigationBars))', line):
            continue
        recognized = True
        if not re.search(r'(?:mVisible|visible)=true', line):
            continue
        frame = re.search(r'(?:mFrame|frame)=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', line)
        if frame is None:
            raise RuntimeError('Visible system bar has an unrecognized frame; preserve input-insets.txt')
        left, top, right, bottom = map(int, frame.groups())
        if right <= left or bottom <= top:
            continue
        if right - left > bottom - top:
            if top == 0:
                area[1] = max(area[1], bottom)
            elif bottom == height:
                area[3] = min(area[3], top)
        elif left == 0:
            area[0] = max(area[0], right)
        elif right == width:
            area[2] = min(area[2], left)
    if not recognized:
        raise RuntimeError('System bar visibility could not be verified; preserve input-insets.txt')
    return area


def input_bounds(width, height):
    dump = adb('shell', 'dumpsys', 'window', 'displays', check=False, timeout=20)
    Path('results').mkdir(exist_ok=True)
    Path('results/input-insets.txt').write_text(dump, encoding='utf-8')
    return system_bar_bounds(dump, width, height)


def control(keys, scrolls=8):
    keys = [keys] if isinstance(keys, str) else keys
    width, height = screen().size
    safe_left, safe_top, safe_right, safe_bottom = input_bounds(width, height)
    for attempt in range(scrolls + 1):
        tree = ui.snapshot()
        parents = {child: parent for parent in tree.iter() for child in parent}
        docks = [bounds(n)[1] for n in ui.nodes(tree)
                 if n.get('content-desc') in ('我的', '服务器', '媒体库', '观看')
                 and bounds(n)[1] > height * .65]
        bottom = min([safe_bottom] + docks)
        for key in keys:
            for node in ui.nodes(tree):
                if key not in ui.labels(node):
                    continue
                target = node
                while target is not None and target.get('clickable') != 'true':
                    target = parents.get(target)
                if target is None or target.get('enabled') == 'false':
                    continue
                left, top, right, end = bounds(target)
                if left >= safe_left and right <= safe_right and top >= safe_top and end <= bottom:
                    return target
        if attempt < scrolls:
            panes = [n for n in ui.nodes(tree) if vertical_pane(n)]
            if not panes:
                break
            pane = max(panes, key=lambda n: (bounds(n)[2] - bounds(n)[0]) *
                       (bounds(n)[3] - bounds(n)[1]))
            left, top, right, end = bounds(pane)
            left, top, right, end = max(safe_left, left), max(safe_top, top), min(safe_right, right), min(bottom, end)
            if right <= left or end - top < 40:
                break
            # A player's menu lives on the right. A screen-centre swipe dismisses it
            # and then searches the video underneath, rather than scrolling the menu.
            ui.shell('input', 'swipe', (left + right) // 2, int(top + (end - top) * .82),
                     (left + right) // 2, int(top + (end - top) * .25), 450)
            time.sleep(.5)
    raise LookupError('No visible actionable control: ' + str(keys))


def click(keys, scrolls=8):
    ui.tap(control(keys, scrolls))
    time.sleep(.7)
