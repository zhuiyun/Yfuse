"""Actual phone/TV login and remote-consent pilot, on two isolated cloud Androids."""
import json
import os
from pathlib import Path
import re
import sys
import time
import traceback
import xml.etree.ElementTree as ET

if os.environ.get('GITHUB_ACTIONS') != 'true': raise SystemExit('Cloud runner required')
sys.path[:0] = ['qa-functional', 'qa-power-ui']
import features as ui
from ext_run import adb, screen
from library_ui import field, connect
from library import LibraryServer
from navigation import click
from account import Coordinator
from transport import credentials

ui.pkg = 'com.yfuse'
OUT = Path('results'); OUT.mkdir(exist_ok=True)
CASES = []
DEVICES = {'phone': '127.0.0.1:15555', 'tv': '127.0.0.1:15556'}


def use(role):
    os.environ['ANDROID_SERIAL'] = DEVICES[role]


def capture(label):
    tree = ui.snapshot()
    (OUT/(label+'.xml')).write_bytes(ET.tostring(tree, encoding='utf-8'))
    screen().save(OUT/(label+'.png'))
    return tree


def wait_text(fragment, timeout=60):
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        tree = ui.snapshot()
        if any(fragment in word for node in ui.nodes(tree) for word in ui.labels(node)): return tree
        time.sleep(.5)
    capture('missing-expected-state')
    raise AssertionError('Expected state not visible: '+fragment)


def passed(name):
    CASES.append({'case': name, 'passed': True})
    (OUT/'cases.json').write_text(json.dumps(CASES))


def login(role, coordinator):
    use(role)
    before = {item['id'] for item in coordinator.sessions()}
    model = ' '.join(adb('shell', 'getprop', 'ro.product.'+key).strip() for key in ['manufacturer', 'model'])
    since = int(time.time()*1000)
    value = credentials()
    if role == 'phone':
        ui.launch(); ui.root('我的'); click('账号与同步')
        capture('phone-login-empty')
        field('账号名', value['username']); field('密码', value['password'], secret=True)
        action = '登录账号'
    else:
        adb('shell', 'am', 'start', '-W', '-n', 'com.yfuse/com.yfuse.tv.TvMainActivity')
        ui.wait('我的与设置', 60)
        click('服务器')
        tree = capture('tv-empty-servers')
        assert len([n for n in ui.nodes(tree) if n.get('text') == '添加服务器']) == 1
        assert ui.find(tree, '检测连接') is None
        # From the selected rail tab, Right must place focus on the sole empty action.
        adb('shell', 'input', 'keyevent', '22'); time.sleep(.5)
        capture('tv-empty-add-focus')
        adb('shell', 'input', 'keyevent', '23')
        wait_text('主机或完整地址', 15); capture('tv-empty-add-opened-by-dpad')
        passed('tv-empty-server-single-action-and-dpad')
        adb('shell', 'input', 'keyevent', '4')
        click('我的与设置'); click('账号与同步')
        capture('tv-login-empty')
        field('账号', value['username']); field('密码', value['password'], secret=True)
        action = '登录'
    try:
        click(action)
        wait_text('已登录', 90)
        capture(role+'-signed-in')
    finally:
        # Login can succeed at the backend even if a subsequent UI assertion fails.
        tracked = coordinator.track_login(role, before, model, since)
    assert tracked, 'A distinct new cloud account session was not verified'
    passed(role+'-real-login')


def cloud_remote(coordinator):
    television_id = coordinator.state['created']['tv']
    deadline = time.monotonic()+60
    while time.monotonic() < deadline:
        inbox = coordinator.api('/api/v1/account/handoff')
        targets = [d for d in inbox['devices'] if d['sessionId'] == television_id and d.get('acceptsRemote')]
        if targets: break
        time.sleep(2)
    assert len(targets) == 1, 'Created cloud TV did not advertise remote control'
    name = targets[0]['name']
    assert sum(1 for d in inbox['devices'] if d['name'] == name and d.get('acceptsRemote', False)) == 1, 'Ambiguous UI target; do not address another device'
    use('phone'); ui.root('我的'); click('设备接力')
    wait_text('遥控 '+name); capture('phone-cloud-device-discovered')
    passed('exact-cloud-tv-discovery')
    click('遥控 '+name)
    wait_text('等待电视确认', 30); capture('remote-waiting')
    passed('remote-requires-tv-consent')
    use('tv'); ui.wait('允许一次', 30); capture('tv-consent')
    click('允许一次')
    use('phone'); wait_text('已连接', 30); capture('remote-connected')
    use('tv'); wait_text('手机遥控中', 30); capture('tv-remotely-connected')
    passed('remote-allow-once-connected-on-both-devices')
    # Send a real key from the phone; capture the TV's resulting page transition.
    use('phone')
    # The page's header also says Back; exercise the lower remote key, not navigation.
    keys = [node for node in ui.nodes(ui.snapshot()) if node.get('text') == '返回'
            and node.get('enabled') != 'false' and node.get('visible') != 'false']
    assert keys, 'Visible remote Back key unavailable'
    ui.tap(max(keys, key=lambda node: ui.coords(node)[1]))
    use('tv'); time.sleep(1); capture('tv-after-phone-back')
    assert ui.find(ui.snapshot(), '账号与同步') is not None, 'Remote Back did not return to TV settings'
    passed('phone-back-reaches-tv')
    use('phone')
    value = 'cloud-remote-' + os.environ['GITHUB_RUN_ID']
    field('输入文字', value)
    use('tv'); wait_text(value, 20); capture('tv-text-from-phone')
    assert any(n.get('class', '').endswith('EditText') and n.get('text') == value
               for n in ui.nodes(ui.snapshot())), 'Remote text did not reach the TV editor'
    passed('phone-text-reaches-tv-search')
    use('phone'); field('输入文字', '')
    use('phone'); adb('shell', 'input', 'keyevent', '4')
    wait_text('设备接力'); click('遥控 '+name)
    # Allow once lasts for this TV host session, including transient phone reconnects.
    wait_text('已连接', 30); capture('remote-reconnected-with-consent')
    use('tv'); wait_text('手机遥控中', 30)
    assert ui.find(ui.snapshot(), '允许一次') is None, 'Existing one-session consent was lost'
    passed('remote-reconnect-retains-one-session-consent')
    # An explicit TV disconnect revokes that grant. Only now must reconnect ask again.
    click('手机遥控中 · 断开')
    use('phone'); wait_text('重新连接', 30); capture('remote-disconnected-by-tv')
    passed('tv-disconnect-revokes-one-session-consent')
    click('重新连接')
    wait_text('等待电视确认', 30)
    use('tv'); ui.wait('拒绝', 30); click('拒绝')
    use('phone'); wait_text('重新连接', 30); capture('remote-refused')
    assert not any('已连接' in word for node in ui.nodes(ui.snapshot()) for word in ui.labels(node))
    passed('remote-refusal-keeps-phone-disconnected')
    return name


def server_sign_in(television_name):
    name = 'Cloud QA ' + os.environ['GITHUB_RUN_ID']
    fixture = LibraryServer(OUT/'fixture-api.jsonl', name=name)
    fixture.server_id = 'account-live-' + os.environ['GITHUB_RUN_ID']
    try:
        for role in DEVICES:
            use(role); adb('reverse', 'tcp:18196', 'tcp:18196')
        use('phone'); connect(); capture('phone-fixture-server')
        passed('phone-synthetic-server-login')
        use('tv'); click('服务器'); click('添加服务器')
        wait_text('用手机登录', 30); click('用手机登录')
        capture('tv-waiting-for-server')
        use('phone'); ui.root('我的'); click('设备接力')
        wait_text('登录服务器到 '+television_name, 30)
        click('登录服务器到 '+television_name)
        wait_text(name, 30); click(name)
        wait_text('发送', 30); capture('phone-server-send-confirmation')
        use('tv'); wait_text(name, 30); capture('tv-offered-server')
        passed('server-offer-shown-before-explicit-send')
        use('phone'); click('发送')
        wait_text('已登录', 45); wait_text('完成', 20); capture('phone-server-sent')
        click('完成')
        use('tv'); wait_text(name, 30); capture('tv-server-login-complete')
        passed('phone-server-session-saved-on-cloud-tv')
    finally:
        fixture.close()



success = False
try:
    coordinator = Coordinator()
    passed('trusted-tls-account-coordinator')
    login('phone', coordinator)
    login('tv', coordinator)
    television_name = cloud_remote(coordinator)
    server_sign_in(television_name)
    success = True
except Exception:
    (OUT/'failure.txt').write_text(traceback.format_exc(), encoding='utf-8')
    for role in DEVICES:
        try:
            use(role); capture(role+'-failure')
            (OUT/(role+'-logcat.txt')).write_text(adb('logcat', '-d', '-v', 'threadtime'), encoding='utf-8')
        except Exception: pass
finally:
    # Only fixed identifiers and booleans may be public; no UI text, account data or exceptions.
    public = Path('account-sealed'); public.mkdir(exist_ok=True)
    (public/'summary.json').write_text(json.dumps({'passed': success, 'scope': 'real-account-remote-and-server-transfer',
        'cases': CASES, 'handoffPlaybackTested': False, 'serverSignInTested': any(c['case'] == 'phone-server-session-saved-on-cloud-tv' for c in CASES)}))
raise SystemExit(0 if success else 1)
