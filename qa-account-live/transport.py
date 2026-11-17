"""One-run credential envelope and locally decryptable cloud evidence."""
import base64
import io
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request
import zipfile

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

TEMP = Path(os.environ['RUNNER_TEMP'])
RUN = os.environ['GITHUB_RUN_ID']
ATTEMPT = os.environ['GITHUB_RUN_ATTEMPT']
OAEP = padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None)


def aad(kind):
    return f'{RUN}:{ATTEMPT}:account-{kind}-v1'.encode()


def prepare():
    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    (TEMP/'account-inbound.pem').write_bytes(key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    (TEMP/'account-inbound.pem').chmod(0o600)
    out = Path('account-public'); out.mkdir(exist_ok=True)
    (out/'public.pem').write_bytes(key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
    (out/'binding.json').write_text(json.dumps({'run': RUN, 'attempt': ATTEMPT,
        'commit': os.environ['GITHUB_SHA'], 'expires': int(time.time()+3600)}))


def wait():
    path = f'qa-account-live-input/{RUN}-{ATTEMPT}.json'
    endpoint = f'https://api.github.com/repos/{os.environ["GITHUB_REPOSITORY"]}/contents/{path}?ref=codex%2Ftwo-engines-cloud-seek-20261113'
    request = urllib.request.Request(endpoint, headers={'Authorization': 'Bearer '+os.environ['API_TOKEN'],
        'Accept': 'application/vnd.github.raw+json', 'User-Agent': 'Yfuse-cloud-account-qa'})
    deadline = time.monotonic()+2400
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                data = response.read(32769)
            assert len(data) <= 32768
            (TEMP/'account-envelope.json').write_bytes(data)
            (TEMP/'account-envelope.json').chmod(0o600)
            credentials()  # Fail closed before starting UI on another run's envelope.
            return
        except urllib.error.HTTPError as error:
            if error.code != 404: raise
        time.sleep(6)
    raise TimeoutError('One-run credential envelope was not supplied')


def credentials():
    envelope = json.loads((TEMP/'account-envelope.json').read_text())
    key = serialization.load_pem_private_key((TEMP/'account-inbound.pem').read_bytes(), password=None)
    secret = key.decrypt(base64.b64decode(envelope['key']), OAEP)
    payload = json.loads(AESGCM(secret).decrypt(base64.b64decode(envelope['nonce']),
        base64.b64decode(envelope['ciphertext']), aad('live')))
    assert str(payload['run']) == RUN and str(payload['attempt']) == ATTEMPT
    assert time.time() < payload['expires'] <= time.time()+7200
    return payload


def seal():
    # Every UI capture/log is private, including failed sign-in screens and stack traces.
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
        for root in [Path('results'), TEMP/'account-private-logs']:
            if root.exists():
                for path in root.rglob('*'):
                    if path.is_file(): archive.write(path, root.name+'/'+str(path.relative_to(root)))
    recipient = serialization.load_pem_public_key(Path('qa-account-live/evidence-recipient.pem').read_bytes())
    key = AESGCM.generate_key(bit_length=256); nonce = os.urandom(12)
    encode = lambda value: base64.b64encode(value).decode()
    out = Path('account-sealed'); out.mkdir(exist_ok=True)
    (out/'evidence.sealed.json').write_text(json.dumps({'run': RUN, 'attempt': ATTEMPT,
        'key': encode(recipient.encrypt(key, OAEP)), 'nonce': encode(nonce),
        'ciphertext': encode(AESGCM(key).encrypt(nonce, buffer.getvalue(), aad('evidence')))}))


if __name__ == '__main__':
    if os.environ.get('GITHUB_ACTIONS') != 'true': raise SystemExit('Cloud runner required')
    {'prepare': prepare, 'wait': wait, 'seal': seal}[sys.argv[1]]()
