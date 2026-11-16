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
from navigation import click

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
    ('calendar', '观看', ['追剧日历']), ('history', '观看', ['播放记录']),
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


def capture(out, name):
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
            'treeSha256': hashlib.sha256(ET.tostring(tree)).hexdigest(),
            'targets': targets, 'widthPx': image.width, 'heightPx': image.height,
            'humanInspection': 'pending'}


def main():
    engine.guard()
    ui.pkg = 'com.yfuse'
    out = Path('results/ui')
    out.mkdir(parents=True, exist_ok=True)
    variant = os.environ.get('QA_UI_VARIANT', 'light')
    adb('shell', 'cmd', 'uimode', 'night', 'yes' if variant == 'dark-large' else 'no')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3' if variant == 'dark-large' else '1.0')
    # The application defaults to Dark, independent of the system setting.
    # Select the actual application theme through its visible control.
    ui.launch()
    ui.root('我的')
    click('外观与交互')
    # Selected segments intentionally have no click action. First choose Light,
    # since a clean install defaults to Dark, then choose Dark if requested.
    click('浅色', 4)
    if variant == 'dark-large':
        click('深色', 4)
    time.sleep(1)
    theme = adb('shell', 'cat', '/data/user/0/com.yfuse/shared_prefs/yfuse.xml')
    selected = ET.fromstring(theme).find("./string[@name='theme.mode']")
    if selected is None or selected.text != ('Dark' if variant == 'dark-large' else 'Light'):
        raise AssertionError('Application theme change was not verified')
    rows = []
    library = LibraryServer(out / 'fixture-requests.jsonl')
    adb('reverse', 'tcp:18196', 'tcp:18196')
    populated = False
    for number, (name, tab, path) in enumerate(ROUTES + POPULATED_ROUTES, 1):
        key = '%02d-%s' % (number, name)
        row = {'step': number, 'name': name, 'tab': tab, 'route': path, 'status': 'unverified'}
        rows.append(row)
        try:
            if number == len(ROUTES) + 1:
                connect()
                populated = True
            if number > len(ROUTES) and not populated:
                raise RuntimeError('Populated fixture sign-in was not verified')
            ui.launch()
            ui.root(tab)
            for label in path:
                before = set(ui.page_info(ui.snapshot()))
                click(label, 10)
                if set(ui.page_info(ui.snapshot())) == before:
                    raise AssertionError('Control did not expose a new page or dialog: ' + str(label))
            if name == 'about-section':
                # About is an inline section, not a link or a separate screen.
                from navigation import control
                control('检测升级', 10)
            time.sleep(1)
            row['top'] = capture(out, key + '-top')
            width, height = ui.size()
            # Retain successive viewports so long settings pages are actually reviewed.
            previous = row['top']['treeSha256']
            row['scrolls'] = []
            for page in range(1, 26):
                adb('shell', 'input', 'swipe', width // 2, int(height * .78),
                    width // 2, int(height * .30), 450)
                time.sleep(.6)
                shot = capture(out, key + '-scroll-' + str(page))
                row['scrolls'].append(shot)
                if shot['treeSha256'] == previous:
                    row['scrollEndObserved'] = True
                    break
                previous = shot['treeSha256']
            else:
                row['scrollEndObserved'] = False
            row['status'] = 'captured-needs-review'
        except Exception as error:
            row['status'] = 'capture-blocked'
            row['reason'] = type(error).__name__ + ': ' + str(error)[:180]
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
    if not any(r['status'] == 'captured-needs-review' for r in rows):
        raise SystemExit('No valid route captures')


if __name__ == '__main__':
    main()
