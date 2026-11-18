"""Current-run native UI evidence. Reachability is not a visual audit verdict."""
import json
import hashlib
import os
from pathlib import Path
import re
import sys
import time
import xml.etree.ElementTree as ET
from library import LibraryServer
from library_ui import connect
from navigation import click, bounds, vertical_pane

sys.path.insert(0, 'qa-arm-matrix')
import engine
sys.path.insert(0, 'qa-functional')
import features as ui
from ext_run import adb, screen

# Each route is a sequence of visible controls, never hidden application state.
ROUTES = [
    ('servers', '服务器', []), ('library-empty', '媒体库', []),
    ('watching', '观看', []), ('profile', '我的', []),
    ('add-server', '服务器', [['添加服务器', '添加']]),
    ('file-source', '服务器', ['添加文件来源']),
    ('iptv', '服务器', [['IPTV · 电视直播', 'IPTV・电视直播']]),
    ('wifi-import', '服务器', ['Wi-Fi 导入视频']),
    ('personal', '我的', ['我的片单']), ('statistics', '我的', ['观影统计']),
    ('family', '我的', ['家庭资料']), ('account', '我的', ['账号与同步']),
    ('playback', '我的', [['播放设置', '播放与字幕']]),
    ('advanced', '我的', [['播放设置', '播放与字幕'], '高级播放设置']),
    ('engine-choice', '我的', [['播放设置', '播放与字幕'], '高级播放设置', '播放内核']),
    ('speed-choice', '我的', [['播放设置', '播放与字幕'], '默认倍速']),
    ('danmaku', '我的', [['弹幕设置', '弹幕']]),
    ('watch-together', '我的', ['一起看']),
    ('appearance', '我的', [['外观与交互', '外观设置']]),
    ('appearance-layout', '我的', [['外观与交互', '外观设置'], '首页与导航']),
    ('appearance-motion', '我的', [['外观与交互', '外观设置'], '动画与动效']),
    ('appearance-accessibility', '我的', [['外观与交互', '外观设置'], '辅助功能']),
    ('glass', '我的', [['外观与交互', '外观设置'], ['玻璃材质', '玻璃质感']]),
    ('splash', '我的', [['外观与交互', '外观设置'], 'Logo 与开屏动画']),
    ('diagnostics', '我的', [['数据与诊断', '数据管理']]),
    ('downloads', '我的', [['下载与离线库', '下载中心']]),
    ('stremio', '我的', ['Stremio 插件']),
    ('trakt', '我的', [['Trakt', 'Trakt 同步']]),
    ('device-handoff', '我的', [['设备接力', '跨设备接力']]),
    ('sync', '我的', ['同步状态与恢复']), ('about-section', '我的', []),
    ('calendar', '观看', ['追剧日历']), ('history', '观看', []),
    ('discover', '观看', ['发现']),
]

POPULATED_ROUTES = [
    ('populated-library', '媒体库', []),
    ('movie-library', '媒体库', ['测试电影']),
    ('movie-detail', '媒体库', ['测试电影', '山海之间']),
    ('long-title-detail', '媒体库', ['测试电影', '这是一个用于检验换行与省略行为的很长电影标题：夜空下的旅程']),
    ('series-library', '媒体库', ['测试剧集']),
    ('series-detail', '媒体库', ['测试剧集', '城市的一天']),
    ('saved-server', '服务器', []),
]


def capture(out, name, expected=None, timeout=12):
    tree = ui.snapshot()
    empty_samples = 0
    deadline = time.monotonic() + 3
    # UiAutomation may briefly expose no root during a scroll transition. Keep
    # that evidence and retry only an empty tree, never a different foreground app.
    while not list(tree.iter('node')) and time.monotonic() < deadline:
        empty_samples += 1
        (out / (name + '-empty-' + str(empty_samples) + '.xml')).write_bytes(ET.tostring(tree, encoding='utf-8'))
        time.sleep(.25)
        tree = ui.snapshot()
    if expected is not None:
        deadline = time.monotonic() + timeout
        while not (any(n.get('package') == 'com.yfuse' for n in ui.nodes(tree)) and
                   any(expected in label for label in ui.page_info(tree))):
            if time.monotonic() >= deadline:
                break  # Preserve the actual wrong state for the caller's coverage gate.
            time.sleep(.25)
            tree = ui.snapshot()
    image = screen()
    image.save(out / (name + '.png'))
    (out / (name + '.xml')).write_bytes(ET.tostring(tree, encoding='utf-8'))
    words = ui.page_info(tree)
    targets = []
    for node in ui.nodes(tree):
        if node.get('clickable') == 'true':
            bounds = list(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))
            if len(bounds) == 4:
                targets.append({'boundsPx': bounds, 'labels': ui.labels(node),
                                'enabled': node.get('enabled'), 'class': node.get('class')})
    return {'image': name + '.png', 'tree': name + '.xml', 'labels': words,
            'packages': sorted({n.get('package') for n in ui.nodes(tree) if n.get('package')}),
            'treeSha256': hashlib.sha256(ET.tostring(tree)).hexdigest(),
            'targets': targets, 'widthPx': image.width, 'heightPx': image.height,
            'emptySamplesRetried': empty_samples, 'humanInspection': 'pending'}


def surface_signature(tree):
    # Preserve node order: selected semantics often live on an unlabelled parent.
    # A set would lose which of two otherwise identical parents became selected.
    return tuple((n.get('text', ''), n.get('content-desc', ''), n.get('checked'), n.get('selected'),
                  n.get('state'), n.get('enabled')) for n in ui.nodes(tree))


def verify_detail_clearance(tree_file, title):
    tree = ET.parse(tree_file).getroot()
    parents = {child: parent for parent in tree.iter() for child in parent}
    titles = [node for node in ui.nodes(tree) if node.get('text') == title and node.get('visible') != 'false']
    backs = [node for node in ui.nodes(tree) if '返回' in ui.labels(node) and node.get('visible') != 'false']
    if not titles or not backs:
        raise AssertionError('Detail title and back control must both be present')
    target = backs[0]
    while target is not None and target.get('clickable') != 'true':
        target = parents.get(target)
    if target is None:
        raise AssertionError('Detail back control is not actionable')
    title_box, back_box = bounds(titles[0]), bounds(target)
    if title_box[1] < back_box[3]:
        raise AssertionError('Detail title overlaps the top navigation: ' + repr((title_box, back_box)))
    return {'titleBoundsPx': title_box, 'backBoundsPx': back_box, 'overlap': False}


def verify_motion_dialog(out, row, title, last_label):
    viewports = [row['top'], *row['scrolls']]
    densities = re.findall(r'(?:Physical|Override) density:\s*(\d+)', adb('shell', 'wm', 'density'))
    if not densities:
        raise AssertionError('Unable to verify the 48 dp close target without display density')
    min_close_px = (48 * int(densities[-1]) + 80) // 160
    subtitle_ending = '播放画面保持安静' if title == '粒子光效' else '关闭时回到原处'
    expected = None
    for shot in viewports:
        tree = ET.parse(out / shot['tree']).getroot()
        visible = [n for n in ui.nodes(tree) if n.get('visible') != 'false']
        close = [n for n in visible if n.get('content-desc') == '关闭' and n.get('class') == 'android.widget.Button'
                 and n.get('enabled') == 'true' and n.get('clickable') == 'true']
        headings = [n for n in visible if n.get('class') == 'android.widget.TextView' and n.get('text') == title]
        descriptions = [n for n in visible if n.get('text', '').endswith(subtitle_ending)]
        if len(close) != 1 or len(headings) != 1 or len(descriptions) != 1:
            raise AssertionError('Dialog title, description or actionable close button disappeared: ' + shot['tree'])
        close_box = bounds(close[0])
        if min(close_box[2] - close_box[0], close_box[3] - close_box[1]) < min_close_px:
            raise AssertionError('Dialog close target smaller than 48 dp: ' + shot['tree'])
        position = [bounds(headings[0]), close_box, bounds(descriptions[0])]
        if expected is not None and position != expected:
            raise AssertionError('Dialog header moved while scrolling: ' + shot['tree'])
        expected = position
    if not any(n.get('text') == last_label for n in visible):
        raise AssertionError('Last dialog option was not reached')
    ui.tap(close[0])
    deadline = time.monotonic() + 6
    while time.monotonic() < deadline:
        tree = ui.snapshot()
        labels = {n.get('text') for n in ui.nodes(tree)}
        if {'粒子光效', '播放器进出场'} <= labels and not any(
                n.get('content-desc') == '关闭' and n.get('visible') != 'false' for n in ui.nodes(tree)):
            return {'viewports': len(viewports), 'headerBoundsPx': expected, 'minCloseTargetPx': min_close_px,
                    'lastOption': last_label, 'dismissed': True}
        time.sleep(.25)
    raise AssertionError('Close at the end did not return to motion settings')


def failure_diagnostics(out, key):
    # Only disposable cloud profiles are used; preserve crash and focus evidence before relaunch.
    commands = {
        'crash': ['logcat', '-d', '-b', 'crash', '-t', '1000'],
        'logcat': ['logcat', '-d', '-v', 'threadtime', '-t', '2500'],
        'window': ['shell', 'dumpsys', 'window', 'windows'],
        'exit-info': ['shell', 'dumpsys', 'activity', 'exit-info', 'com.yfuse'],
        'pid': ['shell', 'pidof', 'com.yfuse'],
    }
    for label, command in commands.items():
        try:
            (out / (key + '-' + label + '.txt')).write_text(
                adb(*command, check=False, timeout=20), encoding='utf-8')
        except Exception as error:
            (out / (key + '-' + label + '-unavailable.txt')).write_text(type(error).__name__)


def capture_hero_pages(out, key):
    labels = [node.get('content-desc', '') for node in ui.nodes(ui.snapshot())
              if re.fullmatch(r'第 \d+ 张，共 \d+ 张', node.get('content-desc', ''))]
    if not labels:
        raise AssertionError('Loaded discovery has no carousel page controls')
    captured = []
    for number, label in enumerate(labels, 1):
        for attempt in range(1, 4):
            current = next(node for node in ui.nodes(ui.snapshot()) if node.get('content-desc') == label)
            if current.get('selected') != 'true':
                # Autoplay can select this dot between snapshots. Its bounds stay actionable
                # even when a newly selected dot intentionally loses the click action.
                left, top, right, bottom = bounds(current)
                if current.get('visible') != 'true' or right <= left or bottom <= top:
                    raise AssertionError('Carousel control has no visible bounds')
                adb('shell', 'input', 'tap', (left + right) // 2, (top + bottom) // 2)
            deadline = time.monotonic() + 5
            selected = lambda tree: any(node.get('content-desc') == label and node.get('selected') == 'true'
                                        for node in ui.nodes(tree))
            while not selected(ui.snapshot()):
                if time.monotonic() >= deadline:
                    raise AssertionError('Carousel selection did not settle: ' + label)
                time.sleep(.2)
            time.sleep(.8)
            name = key + '-hero-' + str(number) + '-attempt-' + str(attempt)
            shot = capture(out, name)
            if selected(ET.parse(out / (name + '.xml')).getroot()) and selected(ui.snapshot()):
                shot['heroPageLabel'] = label
                captured.append(shot)
                break
        else:
            raise AssertionError('Carousel moved during all three captures: ' + label)
    return captured


def main():
    engine.guard()
    ui.pkg = 'com.yfuse'
    out = Path('results/ui')
    out.mkdir(parents=True, exist_ok=True)
    variant = os.environ.get('QA_UI_VARIANT', 'light')
    dark_large = variant in {'dark-large', 'compact-large'}
    adb('shell', 'cmd', 'uimode', 'night', 'yes' if dark_large else 'no')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3' if dark_large else '1.0')
    # The application defaults to Dark, independent of the system setting.
    # Select the actual application theme through its visible control.
    ui.launch()
    ui.root('我的')
    click('外观与交互')
    # Selected segments intentionally have no click action. First choose Light,
    # since a clean install defaults to Dark, then choose Dark if requested.
    click('浅色', 4)
    if dark_large:
        click('深色', 4)
    time.sleep(1)
    theme = adb('shell', 'cat', '/data/user/0/com.yfuse/shared_prefs/yfuse.xml')
    selected = ET.fromstring(theme).find("./string[@name='theme.mode']")
    if selected is None or selected.text != ('Dark' if dark_large else 'Light'):
        raise AssertionError('Application theme change was not verified')
    rows = []
    secondary = os.environ.get('QA_UI_SCOPE') == 'secondary'
    if secondary:
        from secondary_routes import ROUTES as routes
    else:
        routes = ROUTES + POPULATED_ROUTES
    requested = {name for name in os.environ.get('QA_UI_ROUTES', '').split(',') if name}
    if requested:
        available = {route[0] for route in routes}
        if requested - available:
            raise ValueError('Unknown requested routes')
        routes = [route for route in routes if route[0] in requested]
    library = LibraryServer(out / 'fixture-requests.jsonl')
    adb('reverse', 'tcp:18196', 'tcp:18196')
    populated = False
    for number, (name, tab, path) in enumerate(routes, 1):
        key = '%02d-%s' % (number, name)
        row = {'step': number, 'name': name, 'tab': tab, 'route': path, 'status': 'unverified'}
        rows.append(row)
        try:
            needs_library = name in {route[0] for route in POPULATED_ROUTES}
            if not secondary and needs_library and not populated:
                connect()
                populated = True
            if not secondary and needs_library and not populated:
                raise RuntimeError('Populated fixture sign-in was not verified')
            ui.launch()
            ui.root(tab)
            for route_step, label in enumerate(path):
                # page_info deliberately filters long/private-looking labels for display.
                # Navigation verification must still notice a newly expanded long paragraph.
                before = surface_signature(ui.snapshot())
                if os.environ.get('QA_UI_TRACE') == 'true':
                    capture(out, key + '-step-' + str(route_step) + '-before')
                category_context = ('测试电影', '测试剧集', '播放列表') if label in ('测试电影', '测试剧集') else ()
                click(label, 10, horizontal_context=category_context)
                if os.environ.get('QA_UI_TRACE') == 'true':
                    capture(out, key + '-step-' + str(route_step) + '-after')
                after = surface_signature(ui.snapshot())
                if after == before:
                    raise AssertionError('Control did not expose a new page or dialog: ' + str(label))
            if name == 'about-section':
                # About is an inline section, not a link or a separate screen.
                from navigation import control
                control('检测升级', 10)
            if name == 'discover':
                # A skeleton is valid loading UI, not evidence of the loaded page.
                deadline = time.monotonic() + 60
                while not any('热门' == label for label in ui.page_info(ui.snapshot())):
                    if time.monotonic() >= deadline:
                        raise AssertionError('Discovery did not expose its loaded catalogue within 60 seconds')
                    time.sleep(.5)
                tip = '按住海报可以浮起菜单，滑到选项松手即可'
                if tip in ui.page_info(ui.snapshot()):
                    row['posterTipBeforeDismiss'] = capture(out, key + '-poster-tip')
                    click('知道了', 5)
                    deadline = time.monotonic() + 5
                    while tip in ui.page_info(ui.snapshot()):
                        if time.monotonic() >= deadline:
                            raise AssertionError('Poster contextual tip did not dismiss')
                        time.sleep(.2)
                    row['posterTipDismissed'] = True
                if os.environ.get('QA_CAPTURE_HERO_PAGES') == 'true':
                    row['heroPages'] = capture_hero_pages(out, key)
            time.sleep(1)
            row['top'] = capture(out, key + '-top')
            if 'com.yfuse' not in row['top']['packages']:
                raise AssertionError('Requested app is not the foreground screenshot subject')
            if os.environ.get('QA_REQUIRE_DETAIL_CLEARANCE') == 'true' and name in {'movie-detail', 'long-title-detail', 'series-detail'}:
                row['navigationClearance'] = verify_detail_clearance(out / (key + '-top.xml'), path[-1])
            # Retain successive viewports so long settings pages are actually reviewed.
            previous = row['top']['treeSha256']
            row['scrolls'] = []
            row['scrollEndObserved'] = False
            scroll_deadline = time.monotonic() + 240
            while time.monotonic() < scroll_deadline:
                tree = ui.snapshot()
                if not any(n.get('package') == 'com.yfuse' for n in ui.nodes(tree)):
                    raise AssertionError('No foreground application tree while checking scroll extent')
                panes = [node for node in ui.nodes(tree) if vertical_pane(node)]
                if not panes:
                    row['scrollEndObserved'] = True
                    break
                pane = max(panes, key=lambda node: (bounds(node)[2] - bounds(node)[0]) * (bounds(node)[3] - bounds(node)[1]))
                can_scroll_down = pane.get('scroll-down')
                if can_scroll_down == 'false':
                    row['scrollEndObserved'] = True
                    break
                left, top, right, bottom = bounds(pane)
                adb('shell', 'input', 'swipe', (left + right) // 2, int(top + (bottom - top) * .82),
                    (left + right) // 2, int(top + (bottom - top) * .25), 450)
                time.sleep(.6)
                shot = capture(out, key + '-scroll-' + str(len(row['scrolls']) + 1))
                if 'com.yfuse' not in shot['packages']:
                    raise AssertionError('App left the foreground while collecting scroll evidence')
                row['scrolls'].append(shot)
                if shot['treeSha256'] == previous:
                    if can_scroll_down is None:
                        row['scrollEndObserved'] = True
                        break
                previous = shot['treeSha256']
            else:
                raise AssertionError('Scroll end was not observed within the route time budget')
            if name in {'particle-choice', 'player-transition-choice'}:
                last = '细微光尘 · 海报边缘' if name == 'particle-choice' else '完成'
                row['motionDialog'] = verify_motion_dialog(out, row, path[-1], last)
            if name in {'series-detail', 'saved-server'}:
                labels = {label for shot in [row['top'], *row['scrolls']] for label in shot['labels']}
                if name == 'series-detail':
                    expected = {'第1集 · 清晨之后', '第2集 · 清晨之后'}
                    if not expected <= labels or any(re.search(r'第(\d+)集\s*·\s*第\s*\1\s*集', label) for label in labels):
                        raise AssertionError('Episode cards must show the source title with each episode number once')
                elif not {'Cloud QA Library', '添加文件来源'} <= labels or {'其他服务器', '还没有其他服务器'} & labels:
                    raise AssertionError('Single-server screen contains an unused group or lost a source action')
                row['fixtureLabelsVerified'] = True
            row['status'] = 'captured-needs-review'
        except Exception as error:
            row['status'] = 'capture-blocked'
            row['reason'] = type(error).__name__ + ': ' + str(error)[:180]
            failure_diagnostics(out, key)
            try:
                row['blockedState'] = capture(out, key + '-blocked')
            except Exception:
                pass
        finally:
            (out / 'coverage.json').write_text(json.dumps({'variant': variant, 'routes': rows,
                'auditComplete': False, 'limits': ['Populated library is a deterministic loopback fixture, not a real Emby acceptance test.',
                'A screenshot and successful navigation do not establish visual or accessibility compliance.',
                'Account-gated and external-device flows require separate coverage.']}, ensure_ascii=False, indent=2), encoding='utf-8')
            print('UI_CAPTURE', key, row['status'], flush=True)
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    library.close()
    if any(r['status'] != 'captured-needs-review' for r in rows):
        raise SystemExit('Some requested route captures are incomplete; inspect coverage.json')


if __name__ == '__main__':
    main()
