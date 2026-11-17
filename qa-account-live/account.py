"""Track and revoke only account sessions created by this disposable cloud test."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.request
from transport import credentials, TEMP, RUN

ORIGIN = 'https://47.112.219.60'
STATE = TEMP/'account-coordinator.json'


def request(path, body=None, token=None, method=None):
    headers = {'Content-Type': 'application/json', 'User-Agent': 'Yfuse-cloud-account-qa'}
    if token: headers['Authorization'] = 'Bearer '+token
    req = urllib.request.Request(ORIGIN+path,
        data=json.dumps(body).encode() if body is not None else None,
        headers=headers, method=method)
    # Default TLS validation is mandatory. Never follow an auth request to another origin.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs): return None
    with urllib.request.build_opener(NoRedirect()).open(req, timeout=30) as response:
        raw = response.read(2_000_001)
    assert len(raw) <= 2_000_000
    return json.loads(raw) if raw else None


class Coordinator:
    def __init__(self, restore=False):
        if restore:
            self.state = json.loads(STATE.read_text())
            return
        value = credentials()
        body = {'username': value['username'], 'deviceName': 'Yfuse QA coordinator '+RUN}
        try: proof = request('/api/v1/auth/prelogin', {'username': value['username']})
        except urllib.error.HTTPError as error:
            if error.code != 404: raise
            proof = {'authProtocol': 1}
        if proof['authProtocol'] == 2:
            assert proof['kdf'] == 'PBKDF2-HMAC-SHA256'
            iterations = proof['kdfIterations']; assert 600000 <= iterations <= 2000000
            salt = base64.urlsafe_b64decode(proof['kdfSalt']+'==='); assert len(salt) == 16
            master = hashlib.pbkdf2_hmac('sha256', value['password'].encode(), salt, iterations, 32)
            auth = hmac.new(master, b'yfuse-auth-v2\x01', hashlib.sha256).digest()
            body['authKey'] = base64.urlsafe_b64encode(auth).decode().rstrip('=')
        else:
            assert proof['authProtocol'] == 1
            body['password'] = value['password']
        auth = request('/api/v1/auth/login', body)
        self.state = {'auth': auth, 'created': {}, 'started': int(time.time()*1000)}
        self.save()
        self.state['baseline'] = [item['id'] for item in self.sessions()]
        self.save()

    def save(self):
        STATE.write_text(json.dumps(self.state)); STATE.chmod(0o600)

    def api(self, path, body=None, method=None):
        auth = self.state['auth']
        if auth['accessExpiresAtEpochMs'] < time.time()*1000+60000:
            self.state['auth'] = request('/api/v1/auth/refresh', {'refreshToken': auth['refreshToken']})
            self.save(); auth = self.state['auth']
        return request(path, body, auth['accessToken'], method)

    def sessions(self):
        return self.api('/api/v1/account/sessions')['sessions']

    def track_login(self, role, before, model, since):
        after = self.sessions()
        found = [item for item in after if item['id'] not in before]
        if not found: return False
        assert len(found) == 1, 'Ambiguous new sessions; refuse to revoke unrelated devices'
        item = found[0]
        assert item['deviceName'] == model[:64] and since-5000 <= item['createdAtEpochMs'] <= time.time()*1000+5000
        assert item['id'] not in self.state['baseline'] and not item['current']
        self.state['created'][role] = item['id']; self.save()
        return True

    def cleanup(self):
        for session in self.state['created'].values():
            assert session not in self.state['baseline'] and re.fullmatch(r'[A-Za-z0-9-]{16,100}', session)
            try: self.api('/api/v1/account/sessions/'+session, method='DELETE')
            except urllib.error.HTTPError as error:
                if error.code != 404: raise
        remaining = {s['id'] for s in self.sessions()}
        assert not remaining.intersection(self.state['created'].values())
        self.api('/api/v1/auth/logout', method='POST')
        STATE.unlink()


if __name__ == '__main__':
    if os.environ.get('GITHUB_ACTIONS') != 'true': raise SystemExit('Cloud runner required')
    if sys.argv[1] == 'cleanup' and STATE.exists(): Coordinator(restore=True).cleanup()
