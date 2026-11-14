"""Authenticate inside the installed app before obtaining any media URLs."""
import base64
import json
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

sys.path.insert(0, 'qa-full-library')
import run
import retest_transport
import transport_audit
sys.path.insert(0, 'qa-differential')
from controls import resilient_snapshot, instrument_timeouts

ui, measure = run.ui, run.measure
payload = measure.load_envelope()
run.screenshot_key = base64.b64decode(payload['screenKey'])
ui.snapshot = resilient_snapshot
stage = 'begin'


def field(label, value):
    p = '/data/local/tmp/yfuse-qa-login-field'
    subprocess.run(['adb', 'shell', 'umask 077; cat > ' + p], input=value.encode(), capture_output=True, check=True)
    try:
        for attempt in range(5):
            result = ui.adb('shell', 'am', 'instrument', '-w', '-e', 'mode', 'loginfield', '-e', 'label',
                            base64.b64encode(label.encode()).decode(), 'qa.yfuse.gesture/.Driver', t=25)
            if 'changed=true' in result:
                return
            width, height = ui.size()
            ui.shell('input', 'swipe', width // 2, height * 68 // 100, width // 2, height * 38 // 100, 350)
        raise RuntimeError('login field unavailable')
    finally:
        ui.shell('rm', '-f', p)


def saved_account():
    raw = ui.shell('cat', run.prefs)
    if '<map' not in raw:
        return None
    settings = ET.fromstring(raw)
    sync = settings.find("./boolean[@name='sync.progress']")
    assert sync is not None and sync.get('value') == 'false'
    entry = settings.find("./string[@name='servers.data']")
    if entry is None:
        return None
    servers = json.loads(entry.text).get('servers', [])
    for server in servers:
        url = urlsplit(server.get('baseUrl', ''))
        if (url.scheme, url.netloc) == ('http', '47.112.219.60:19001') and server.get('accessToken'):
            return {k: server[k] for k in ('baseUrl', 'userName', 'userId', 'accessToken')}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def logout(account):
    req = Request(account['baseUrl'].rstrip('/') + '/Sessions/Logout', data=b'{}',
                  headers={'X-Emby-Token': account['accessToken'], 'Content-Type': 'application/json'})
    try:
        with build_opener(NoRedirect()).open(req, timeout=20) as response:
            status = response.status
    except HTTPError as error:
        status = error.code
    Path('private-results/session-cleanup.json').write_text(json.dumps({'logoutHTTP': status}))
    assert status in (200, 204, 401)


account = None
try:
    run.provision('YCore')
    stage = 'open-server-form'
    ui.adb('shell', 'am', 'start', '-n', 'com.yfuse/com.yfuse.MainActivity')
    tab = run.wait_node(lambda t: ui.dock(t, '服务器'), 50)
    ui.tap(tab)
    button = run.wait_node(lambda t: ui.find(t, ['添加服务器', '添加']), 25)
    run.press_node(button)
    stage = 'enter-origin'
    field('地址', 'http://47.112.219.60:19001/emby')
    field('端口', '19001')
    field('显示名称', run.server_name)
    stage = 'enter-credentials'
    field('用户名', payload['credentials']['username'])
    field('密码', payload['credentials']['password'])
    payload.pop('credentials')
    stage = 'native-authentication'
    button = run.wait_node(lambda t: ui.find(t, ['连接到服务器']), 15)
    run.press_node(button)
    until = time.monotonic() + 75
    while time.monotonic() < until:
        account = saved_account()
        if account:
            break
        time.sleep(1)
    if not account:
        raise RuntimeError('native login unverified')
    measure.measured('native-app-login', verified=True, savedTokenGeneratedByApp=True, progressSync=False)
    payload['account'] = account
    measure.load_envelope = lambda: payload
    run.controls = retest_transport.controls_with_startup_operations
    stage = 'full-app-playback'
    run.main()
except Exception as error:
    measure.measured('native-login-flow-failed', stage=stage, error=type(error).__name__)
    # Only encrypted screenshots may contain a username or partially completed login form.
    run.capture('native-login-flow-failed')
    raise SystemExit(1)
finally:
    if account is None:
        try:
            account = saved_account()
        except Exception:
            pass
    try:
        if account:
            try:
                transport_audit.inspect(account, payload['samples'])
            finally:
                logout(account)
    finally:
        Path('private-results/observer-timeouts.json').write_text(json.dumps(instrument_timeouts))
        ui.shell('am', 'force-stop', ui.pkg)
        ui.shell('pm', 'clear', ui.pkg)
