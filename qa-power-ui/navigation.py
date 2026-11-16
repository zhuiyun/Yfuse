"""Interact with visible controls, not duplicate headings or occluded semantics."""
import re
import time
import sys
sys.path.insert(0, 'qa-functional')
import features as ui
from ext_run import screen


def bounds(node):
    values = list(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))
    return values if len(values) == 4 else [0, 0, 0, 0]


def control(keys, scrolls=8):
    keys = [keys] if isinstance(keys, str) else keys
    width, height = screen().size
    for attempt in range(scrolls + 1):
        tree = ui.snapshot()
        parents = {child: parent for parent in tree.iter() for child in parent}
        docks = [bounds(n)[1] for n in ui.nodes(tree)
                 if n.get('content-desc') in ('我的', '服务器', '媒体库', '观看')
                 and bounds(n)[1] > height * .65]
        bottom = min(docks) if docks else height
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
                if left >= 0 and right <= width and top >= 0 and end <= bottom:
                    return target
        if attempt < scrolls:
            ui.shell('input', 'swipe', width // 2, int(bottom * .87),
                     width // 2, int(height * .25), 450)
            time.sleep(.5)
    raise LookupError('No visible actionable control: ' + str(keys))


def click(keys, scrolls=8):
    ui.tap(control(keys, scrolls))
    time.sleep(.7)
