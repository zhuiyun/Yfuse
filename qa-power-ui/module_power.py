"""Cloud-only module activity and temporary receiver lifecycle; no physical energy claims."""
import json
import os
from pathlib import Path
import re
import subprocess
import time

from capture import capture, failure_diagnostics
from file_ui import fixture_server
from library_ui import field
from navigation import click
from power import adb, engine, ui
from telemetry import Recorder

PHASES = ['widgets-idle', 'calendar-idle', 'account-login-idle', 'channels-idle',
          'file-browser-idle', 'wifi-receiver-idle', 'background-after-receiver', 'screen-off']


def listener(port):
    text = adb('shell', 'cat', '/proc/net/tcp', '/proc/net/tcp6')
    entries = [line.split() for line in text.splitlines() if len(line.split()) >= 4 and ':' in line.split()[1]]
    return any(int(row[1].rsplit(':', 1)[1], 16) == port and row[3] == '0A' for row in entries)


def page(tab, *actions):
    ui.launch()
    ui.root(tab)
    for action in actions:
        click(action)


def main():
    engine.guard()
    ui.pkg = 'com.yfuse'
    output = Path('results/module-power')
    output.mkdir(parents=True, exist_ok=True)
    pilot = os.environ.get('QA_MODULE_PILOT') == 'true'
    video = Path(os.environ['RUNNER_TEMP']) / 'module-fixture.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=12',
                    '-t', '3', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', str(video)], check=True)
    server = fixture_server(video.read_bytes())
    adb('reverse', 'tcp:18198', 'tcp:18198')
    phases = []
    receiver_port = None
    status = 'incomplete'
    recorder = Recorder(output)
    (output / 'conditions.json').write_text(json.dumps({
        'protocol': 1, 'pilot': pilot, 'batteryIsSimulated': True, 'batteryLevelSetpoint': 80,
        'phaseSeconds': 45 if pilot else 600, 'moduleEnergyAttributionAvailable': False,
        'networkScope': 'UID network counters; no invented controlled-endpoint byte totals',
        'scope': 'Actual module UI, generated local M3U/WebDAV; account signed out; no casting receiver',
        'receiverClosure': 'Verify actual guest TCP listener disappears after Home before sampling background',
    }, indent=2), encoding='utf-8')
    try:
        adb('shell', 'dumpsys', 'battery', 'unplug')
        adb('shell', 'dumpsys', 'battery', 'set', 'level', '80')
        adb('shell', 'dumpsys', 'battery', 'set', 'status', '3')
        adb('shell', 'dumpsys', 'batterystats', '--reset')
        for name in PHASES:
            duration = 45 if pilot else 600
            row = {'phase': name, 'plannedSeconds': duration, 'status': 'incomplete'}
            phases.append(row)
            try:
                expected = None
                if name == 'widgets-idle':
                    page('我的', '外观与交互', '首页与导航', '首页与组件管理')
                    expected = '组件管理'
                elif name == 'calendar-idle':
                    page('观看', '追剧日历')
                    expected = '追剧日历'
                elif name == 'account-login-idle':
                    page('我的', '账号与同步')
                    expected = '登录 Yfuse 账号'
                elif name == 'channels-idle':
                    page('服务器', ['IPTV · 电视直播', 'IPTV・电视直播'])
                    ui.wait('订阅名称', 15)
                    field('订阅名称', 'Module QA')
                    field('M3U 地址', 'http://127.0.0.1:18198/list.m3u')
                    click('添加订阅')
                    expected = 'Cloud Channel A'
                elif name == 'file-browser-idle':
                    page('服务器', '添加文件来源')
                    ui.wait(['WebDAV', 'WebDav'], 15)
                    click('HTTP')
                    field('地址', '127.0.0.1')
                    field('端口', '18198')
                    field('路径', 'dav')
                    field('显示名称', 'Module DAV')
                    click('连接并添加')
                    ui.wait('Module DAV', 30)
                    ui.root('服务器')
                    click('Module DAV')
                    expected = 'clip.mp4'
                elif name == 'wifi-receiver-idle':
                    page('服务器', 'Wi-Fi 导入视频', '开启临时接收')
                    deadline = time.monotonic() + 25
                    while time.monotonic() < deadline:
                        tree = ui.snapshot()
                        permission = ui.find(tree, ['允许', 'Allow', 'While using the app', '使用应用时允许'])
                        if permission is not None:
                            ui.tap(permission)
                            continue
                        # page_info deliberately omits URLs; use raw labels for this synthetic local receiver.
                        words = ' '.join(value for node in ui.nodes(tree) for value in ui.labels(node))
                        match = re.search(r'http://[\d.]+:(\d+)', words)
                        if match:
                            receiver_port = int(match[1])
                            break
                        time.sleep(.3)
                    if receiver_port is None or not listener(receiver_port):
                        raise AssertionError('Temporary receiver did not open its advertised TCP listener')
                    row['receiverListenerVerified'] = True
                    expected = '停止接收'
                elif name == 'background-after-receiver':
                    if receiver_port is None:
                        raise AssertionError('Cannot verify closure without an observed receiver')
                    adb('shell', 'input', 'keyevent', '3')
                    deadline = time.monotonic() + 10
                    while listener(receiver_port) and time.monotonic() < deadline:
                        time.sleep(.25)
                    if listener(receiver_port):
                        raise AssertionError('Temporary receiver kept listening after app entered background')
                    row['receiverListenerClosedVerified'] = True
                elif name == 'screen-off':
                    adb('shell', 'input', 'keyevent', '223')
                if expected:
                    shot = capture(output, name + '-entry', expected=expected, timeout=30)
                    if 'com.yfuse' not in shot['packages'] or not any(expected in text for text in shot['labels']):
                        raise AssertionError('Module entry not verified: ' + name)
                adb('shell', 'dumpsys', 'gfxinfo', 'com.yfuse', 'reset')
                began = time.monotonic()
                count = 0
                print('MODULE_POWER', name, duration, flush=True)
                while time.monotonic() - began < duration:
                    recorder.capture(name, {'requests': None, 'bytes': None}, heavy=count % 4 == 0,
                                     intervention=time.monotonic() - began < 60)
                    count += 1
                    time.sleep(min(30, max(0, duration - (time.monotonic() - began))))
                row.update(status='recorded', measuredSeconds=round(time.monotonic() - began, 2))
            except Exception as error:
                row.update(status='failed', error=type(error).__name__ + ': ' + str(error)[:220])
                failure_diagnostics(output, name)
                capture(output, name + '-failure')
            finally:
                recorder.finish(phases, 'incomplete')
        status = 'recorded' if all(row['status'] == 'recorded' for row in phases) else 'failed'
    finally:
        recorder.finish(phases, status)
        server.shutdown()
        server.server_close()
        adb('shell', 'dumpsys', 'battery', 'reset', check=False)
        adb('shell', 'am', 'force-stop', 'com.yfuse', check=False)
    if status != 'recorded':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
