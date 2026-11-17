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
        # No credentials: retain exact ownership evidence inside the encrypted artifact,
        # including when a later API error prevents automatic cleanup.
        out = Path('results'); out.mkdir(exist_ok=True)
        (out/'session-ownership.json').write_text(json.dumps({
            key: self.state[key] for key in ['created', 'started', 'baseline'] if key in self.state}))

    def api(self, path, body=None, method=None):
        auth = self.state['auth']
        if auth['accessExpiresAtEpochMs'] < time.time()*1000+60000:
            self.state['auth'] = request('/api/v1/auth/refresh', {'refreshToken': auth['refreshToken']})
            self.save(); auth = self.state['auth']
        return request(path, body, auth['accessToken'], method)

    def sessions(self):
        return self.api('/api/v1/account/sessions')['sessions']

    def verify_refresh_identity(self):
        current = [item for item in self.sessions() if item['current']]
        assert len(current) == 1 and current[0]['deviceName'] == 'Yfuse QA coordinator '+RUN
        identity = (current[0]['id'], current[0]['createdAtEpochMs'])
        observations = []
        for _ in range(3):
            refreshed = request('/api/v1/auth/refresh', {'refreshToken': self.state['auth']['refreshToken']})
            self.state['auth'] = refreshed
            self.save()
            after = [item for item in self.sessions() if item['current']]
            observed = {'oneCurrentSession': len(after) == 1,
                        'sameSessionId': len(after) == 1 and after[0]['id'] == identity[0],
                        'sameCreationTime': len(after) == 1 and after[0]['createdAtEpochMs'] == identity[1]}
            observations.append(observed)
            (Path('results')/'refresh-identity.json').write_text(json.dumps(observations))
            assert observed['sameSessionId'], 'Refresh changed the active session ID'
            assert observed['sameCreationTime'], 'Refresh changed the session creation time'
        return 3

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
        failures = []
        for session in self.state['created'].values():
            assert session not in self.state['baseline'] and re.fullmatch(r'[A-Za-z0-9-]{16,100}', session)
            try: self.api('/api/v1/account/sessions/'+session, method='DELETE')
            except urllib.error.HTTPError as error:
                # A missing tracked ID can mean a legacy backend rotated it. Preserve
                # that failure instead of guessing ownership of another device.
                failures.append({'stage': 'revoke', 'id': session, 'http': error.code})
            except Exception as error:
                failures.append({'stage': 'revoke', 'id': session, 'type': type(error).__name__})
        try:
            remaining = self.sessions()
            (Path('results')/'sessions-after-cleanup.json').write_text(json.dumps(remaining))
            if {s['id'] for s in remaining}.intersection(self.state['created'].values()):
                failures.append({'stage': 'verify', 'reason': 'owned-session-remains'})
        except Exception as error:
            failures.append({'stage': 'verify', 'type': type(error).__name__})
        finally:
            try: self.api('/api/v1/auth/logout', method='POST')
            except Exception as error:
                failures.append({'stage': 'coordinator-logout', 'type': type(error).__name__})
        (Path('results')/'cleanup-result.json').write_text(json.dumps({'failures': failures}))
        if failures: raise RuntimeError('Account cleanup incomplete; encrypted ownership receipt retained')
        STATE.unlink()


if __name__ == '__main__':
    if os.environ.get('GITHUB_ACTIONS') != 'true': raise SystemExit('Cloud runner required')
    if sys.argv[1] == 'cleanup' and STATE.exists(): Coordinator(restore=True).cleanup()
