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
capture_pid = None
capture_path = Path(os.environ['RUNNER_TEMP']) / 'yfuse-own-login.pcap'


def field(label, value):
    p = '/data/local/tmp/yfuse-qa-login-field'
    subprocess.run(['adb', 'shell', 'umask 077; cat > ' + p + '; chown shell:shell ' + p], input=value.encode(), capture_output=True, check=True)
    try:
        for attempt in range(5):
            result = ui.adb('shell', 'am', 'instrument', '-w', '-e', 'mode', 'loginfield', '-e', 'label',
                            base64.b64encode(label.encode()).decode(), 'qa.yfuse.gesture/.Driver', t=25)
            if 'changed=true' in result:
                return
            if 'error=' in result:
                measure.measured('login-field-driver-error', field=label, driverError=True)
                raise RuntimeError('login observer rejected field input')
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
    data = json.loads(entry.text)
    assert data.get('v') == 2, 'unexpected native registry version'
    for server in data.get('s', []):
        url = urlsplit(server.get('b', ''))
        if (url.scheme, url.netloc) == ('http', '47.112.219.60:19001') and server.get('r'):
            return {k: server[v] for k, v in [('baseUrl', 'b'), ('userName', 'a'), ('userId', 'u')]}


def start_login_capture():
    # Capture only this disposable app's authorized HTTP origin, never unrelated traffic.
    # The response token stays on this runner for audit/logout and is never persisted publicly.
    import shlex
    command = 'umask 077; tcpdump -i any -U -s 0 -w ' + shlex.quote(str(capture_path)) + " 'host 47.112.219.60 and tcp port 19001' >/dev/null 2>&1 & echo $!"
    pid = subprocess.check_output(['sudo', 'bash', '-c', command], text=True).strip()
    assert pid.isdecimal()
    time.sleep(.5)
    subprocess.run(['sudo', 'kill', '-0', pid], check=True, capture_output=True)
    return pid


def stop_login_capture():
    global capture_pid
    if capture_pid:
        subprocess.run(['sudo', 'kill', '-INT', capture_pid], capture_output=True)
        capture_pid = None
        time.sleep(.5)


def native_response_account(metadata=None):
    stop_login_capture()
    if not capture_path.exists():
        return None
    result = subprocess.run(['sudo', 'tshark', '-r', str(capture_path), '-d', 'tcp.port==19001,http',
                             '-Y', 'http.response.code == 200', '-T', 'fields', '-e', 'http.file_data'],
                            capture_output=True, text=True, timeout=20, check=True)
    for line in result.stdout.splitlines():
        try:
            value = json.loads(bytes.fromhex(line.replace(':', '')))
            if not isinstance(value, dict) or not value.get('AccessToken') or not value.get('User', {}).get('Id'):
                continue
            if metadata and value['User']['Id'] != metadata['userId']:
                continue
            return dict(metadata or {'baseUrl': 'http://47.112.219.60:19001/emby',
                                     'userId': value['User']['Id'], 'userName': value['User'].get('Name', '')},
                        accessToken=value['AccessToken'])
        except (ValueError, UnicodeError, AttributeError):
            continue
    return None


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
    visible = ' '.join(n.get('text', '') for n in ui.nodes(ui.snapshot()))
    assert '47.112.219.60' in visible and '19001' in visible
    field('显示名称', run.server_name)
    stage = 'enter-credentials'
    field('用户名', payload['credentials']['username'])
    field('密码', payload['credentials']['password'])
    payload.pop('credentials')
    stage = 'native-authentication'
    capture_pid = start_login_capture()
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
    metadata = account
    account = native_response_account(metadata)
    if not account:
        raise RuntimeError('native response unavailable for session cleanup')
    measure.measured('native-app-login', verified=True, savedTokenGeneratedByApp=True, progressSync=False)
    # Keep the app's encrypted registry and its device identity throughout both cores.
    original_provision = run.provision
    run.provision = lambda core, account=None: original_provision(core)
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
    if account is None or 'accessToken' not in account:
        try:
            account = native_response_account(saved_account())
        except Exception:
            account = None
    try:
        if account:
            try:
                transport_audit.inspect(account, payload['samples'])
            finally:
                logout(account)
    finally:
        stop_login_capture()
        subprocess.run(['sudo', 'rm', '-f', str(capture_path)], capture_output=True)
        Path('private-results/observer-timeouts.json').write_text(json.dumps(instrument_timeouts))
        ui.shell('am', 'force-stop', ui.pkg)
        ui.shell('pm', 'clear', ui.pkg)
