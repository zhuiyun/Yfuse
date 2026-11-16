"""Actual two-engine player popup and bright-video readability evidence."""
import json
import os
import re
from pathlib import Path
import subprocess
import time

from power import engine, adb, screen, playing, pause, direct, ui
from media import MediaServer
from navigation import click, bounds, vertical_pane
from capture import capture, failure_diagnostics

ROUTES = [
    ('paused-controls', []), ('subtitles', ['字幕']), ('audio', ['音轨']),
    ('speed', ['播放速度']), ('skip', ['标记片头片尾']), ('danmaku', [['弹幕，已开启', '弹幕，已关闭']]),
    ('cast', ['投屏']), ('more', ['更多']), ('engine', ['更多', '播放内核']),
    ('media-info', ['更多', '媒体信息']), ('bookmarks', ['更多', '时间书签']),
    ('handoff', ['更多', '设备接力']), ('gestures-help', ['更多', '手势与快捷键']),
    ('playback-settings', ['更多', '播放设置']),
    ('picture', ['更多', '播放设置', '画面设置']),
    ('sleep', ['更多', '播放设置', '睡眠定时']),
    ('gestures', ['更多', '播放设置', '手势设置']),
]

MARKERS = {
    'subtitles': '导入', 'audio': '音频同步', 'speed': '1.25x',
    'skip': '在线获取片头片尾', 'danmaku': '显示设置', 'cast': '局域网投屏设备',
    'more': '媒体信息', 'engine': '‹  播放内核', 'media-info': '播放问题说明与诊断导出',
    'bookmarks': '保存当前播放时间', 'handoff': '接力规则', 'gestures-help': '手势与快捷键',
    'playback-settings': '‹  播放设置', 'picture': '‹  画面设置',
    'sleep': '‹  睡眠定时', 'gestures': '‹  手势设置',
}


def pause_visible(output):
    result = adb('shell', 'am', 'instrument', '-w', '-e', 'mode', 'playerPause', 'qa.yfuse.gesture/.Driver')
    output.write_text(result, encoding='utf-8')
    values = dict(re.findall(r'INSTRUMENTATION_RESULT: (\w+)=(.*)', result))
    clocks = [re.search(r'播放进度 ((?:\d+:)?\d+:\d+)', values.get(key, ''))
              for key in ('firstState', 'secondState')]
    def seconds(match):
        value = 0
        for part in match.group(1).split(':'):
            value = value * 60 + int(part)
        return value
    if values.get('command', '').strip() != 'paused' or not all(clocks) or abs(seconds(clocks[1]) - seconds(clocks[0])) > 1:
        raise AssertionError('Atomic pause action and stable media clock not confirmed: ' + repr(values)[:240])
    return {'positionBefore': seconds(clocks[0]), 'positionAfter': seconds(clocks[1]), 'command': 'paused'}


def main():
    engine.guard()
    ui.pkg = 'com.yfuse'
    core = os.environ['QA_ENGINE']
    out = Path('results/player-ui')
    out.mkdir(parents=True, exist_ok=True)
    fixture = Path(os.environ['RUNNER_TEMP']) / 'player-ui.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=24',
                    '-f', 'lavfi', '-i', 'sine=sample_rate=48000', '-t', '600', '-c:v', 'libx264',
                    '-preset', 'ultrafast', '-crf', '35', '-c:a', 'aac', '-movflags', '+faststart', str(fixture)], check=True)
    server = MediaServer(fixture, out / 'media-requests.jsonl')
    adb('reverse', 'tcp:18389', 'tcp:18389')
    engine.configure(core)
    rows = []
    requested = {name for name in os.environ.get('QA_PLAYER_ROUTES', '').split(',') if name}
    if requested - {name for name, _ in ROUTES}:
        raise ValueError('Unknown requested player route')
    routes = [(name, path) for name, path in ROUTES if not requested or name in requested]
    try:
        for index, (name, path) in enumerate(routes, 1):
            row = {'name': name, 'path': path, 'status': 'incomplete'}
            rows.append(row)
            try:
                adb('shell', 'am', 'force-stop', 'com.yfuse')
                row['playback'] = playing(core)
                row['pause'] = pause_visible(out / ('%02d-pause-result.txt' % index))
                for label in path:
                    click(label, 8)
                key = '%02d-%s' % (index, name)
                row['top'] = capture(out, key + '-top', expected=MARKERS.get(name))
                if 'com.yfuse' not in row['top']['packages']:
                    raise AssertionError('Requested player is not the foreground screenshot subject')
                if name in MARKERS and not any(MARKERS[name] in label for label in row['top']['labels']):
                    raise AssertionError('Expected player panel content was not captured: ' + name)
                if os.environ.get('QA_REQUIRE_CLEAR_PANELS') == 'true':
                    resume_visible = '继续播放' in row['top']['labels']
                    if bool(path) == resume_visible:
                        raise AssertionError('Center resume action must be visible only without an open panel')
                    row['centerActionVisibilityVerified'] = True
                row['scrolls'] = []
                previous = row['top']['treeSha256']
                for number in range(1, 20):
                    tree = ui.snapshot()
                    scrollables = [n for n in ui.nodes(tree) if vertical_pane(n)]
                    if not scrollables:
                        row['scrollEndObserved'] = True
                        break
                    pane = max(scrollables, key=lambda n: (bounds(n)[2] - bounds(n)[0]) * (bounds(n)[3] - bounds(n)[1]))
                    x1, y1, x2, y2 = bounds(pane)
                    adb('shell', 'input', 'swipe', (x1 + x2) // 2, int(y1 + (y2 - y1) * .82),
                        (x1 + x2) // 2, int(y1 + (y2 - y1) * .25), 450)
                    time.sleep(.6)
                    shot = capture(out, key + '-scroll-' + str(number))
                    row['scrolls'].append(shot)
                    if shot['treeSha256'] == previous:
                        row['scrollEndObserved'] = True
                        break
                    previous = shot['treeSha256']
                else:
                    row['scrollEndObserved'] = False
                if name == 'media-info' and os.environ.get('QA_VERIFY_POWER_LABEL') == 'true':
                    labels = row['top']['labels'] + [label for shot in row['scrolls'] for label in shot['labels']]
                    unavailable = '未评估 · 当前内核未提供功耗依据'
                    if (unavailable in labels) != (core == 'SenPlayer'):
                        raise AssertionError('Power assessment must match the actual engine evidence')
                    if ('重置 YCore 学习数据' in labels) != (core == 'YCore'):
                        raise AssertionError('Engine-specific reset action is incorrectly exposed or hidden')
                    if core == 'YCore' and 'H.264 · SDR' not in labels:
                        raise AssertionError('Actual video information must use readable codec names')
                    row['enginePowerEvidenceLabelVerified'] = True
                row['status'] = 'captured-needs-review'
            except Exception as error:
                row['status'] = 'capture-blocked'
                row['error'] = type(error).__name__ + ': ' + str(error)[:200]
                failure_diagnostics(out, '%02d-%s' % (index, name))
                try:
                    row['blockedState'] = capture(out, '%02d-%s-blocked' % (index, name))
                except Exception:
                    pass
                if 'pause' not in row:
                    break  # A shared setup failure must not be repeated through every menu.
            finally:
                (out / 'coverage.json').write_text(json.dumps({'engine': core, 'routes': rows,
                    'auditComplete': False, 'limits': ['Generated AVC/AAC source; original media compatibility is separate.',
                    'External receiver and account-gated destinations are only inspected in their reachable states.',
                    'Screenshots do not establish animation smoothness or physical energy.']}, ensure_ascii=False, indent=2), encoding='utf-8')
                print('PLAYER_UI', core, name, row['status'], flush=True)
    finally:
        server.close()
    incomplete = [row['name'] for row in rows if row['status'] != 'captured-needs-review']
    if incomplete or len(rows) != len(routes):
        raise SystemExit('Player evidence incomplete: ' + ', '.join(incomplete))


if __name__ == '__main__':
    main()
