"""Search, person, and multi-server pages using only disposable synthetic libraries."""
import json
import os
from pathlib import Path
import time
from capture import capture, failure_diagnostics
from library import LibraryServer, item
from library_ui import connect, field
from navigation import click, vertical_pane, bounds
from power import engine, adb, ui


def main():
    engine.guard()
    ui.pkg = 'com.yfuse'
    output = Path('results/extra-ui')
    output.mkdir(parents=True, exist_ok=True)
    variant = os.environ.get('QA_UI_VARIANT', 'light')
    dark = variant == 'dark-large'
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3' if dark else '1.0')
    ui.launch()
    ui.root('我的')
    click('外观与交互')
    click('浅色')
    if dark:
        click('深色')
    first = LibraryServer(output / 'first-requests.jsonl')
    second = LibraryServer(output / 'second-requests.jsonl', port=18197, name='Cloud QA Second')
    first.movies[0]['People'] = [{'Id': 'person-qa', 'Name': '测试演员甲', 'Type': 'Actor',
                                  'Role': '测试角色', 'PrimaryImageTag': 'fixture'}]
    first.items['person-qa'] = item('person-qa', '测试演员甲', 'Person',
        Overview='这是用于验证人物页排版的合成简介，不对应真实人物。' * 12,
        PremiereDate='1990-01-01T00:00:00Z', ProductionLocations=['测试城市'])
    for port in (18196, 18197):
        adb('reverse', 'tcp:' + str(port), 'tcp:' + str(port))
    rows = []

    def record(name, expected, scroll=True):
        row = {'name': name, 'status': 'unverified', 'top': capture(output, name + '-top', expected=expected)}
        rows.append(row)
        if 'com.yfuse' not in row['top']['packages'] or not any(expected in s for s in row['top']['labels']):
            raise AssertionError('Requested extra page not visible: ' + name)
        row['scrolls'] = []
        previous = row['top']['treeSha256']
        for index in range(1, 15) if scroll else ():
            panes = [n for n in ui.nodes(ui.snapshot()) if vertical_pane(n)]
            if not panes:
                row['scrollEndObserved'] = True
                break
            pane = max(panes, key=lambda n: (bounds(n)[2] - bounds(n)[0]) * (bounds(n)[3] - bounds(n)[1]))
            left, top, right, bottom = bounds(pane)
            adb('shell', 'input', 'swipe', (left + right) // 2, int(top + .8 * (bottom-top)),
                (left + right) // 2, int(top + .25 * (bottom-top)), 450)
            shot = capture(output, name + '-scroll-' + str(index))
            row['scrolls'].append(shot)
            if 'com.yfuse' not in shot['packages']:
                raise AssertionError('Extra page left the foreground')
            if shot['treeSha256'] == previous:
                row['scrollEndObserved'] = True
                break
            previous = shot['treeSha256']
        row['status'] = 'captured-needs-review'

    try:
        connect()
        ui.root('媒体库')
        search = ui.dock(ui.snapshot(), '搜索')
        if search is None:
            raise AssertionError('Visible search navigation unavailable')
        ui.tap(search)
        # An IME can own the active accessibility window. Preserve this image as
        # contextual evidence, then verify the actual app page after dismissing it.
        rows.append({'name': 'search-ime-context', 'status': 'context-only-needs-review',
                     'top': capture(output, 'search-ime-context')})
        ui.hide_keyboard()
        record('01-search-entry', '搜索电影、剧集、演员', scroll=False)
        field('搜索电影、剧集、演员', '山海')
        adb('shell', 'input', 'keyevent', '66')
        ui.hide_keyboard()
        record('02-search-results', '山海之间', scroll=False)
        click('山海之间')
        record('03-search-detail', '山海之间', scroll=False)
        click('测试演员甲', 12)
        record('04-person', '测试演员甲')
        connect(18197)
        ui.root('媒体库')
        click('Cloud QA Second')
        click('浏览全部服务器')
        record('05-unified-library', '全部服务器')
    except Exception as error:
        rows.append({'name': 'flow-failure', 'status': 'capture-blocked', 'error': type(error).__name__ + ': ' + str(error)[:220]})
        failure_diagnostics(output, 'failure')
        capture(output, 'failure')
        raise
    finally:
        (output / 'coverage.json').write_text(json.dumps({'variant': variant, 'routes': rows,
            'auditComplete': False, 'scope': 'Synthetic search, person and multi-server pages; not real server/authentication acceptance'},
            ensure_ascii=False, indent=2), encoding='utf-8')
        first.close()
        second.close()


if __name__ == '__main__':
    main()
