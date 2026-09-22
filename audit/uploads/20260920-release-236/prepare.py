from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path('D:/Demo/Yfuse')
OUT = Path(__file__).resolve().parent
GIT = ['git', '-c', 'safe.directory=D:/Demo/Yfuse']
def git(*args):
    return subprocess.check_output(GIT + list(args), cwd=ROOT)

assert not git('diff', '--cached', '--name-only'), 'Existing staged changes require preservation'
tracked = git('diff', 'HEAD', '--name-only', '-z').decode().split('\0')
roots = ['.github', 'composeApp', 'tvApp', 'phoneShared', 'tvShared', 'mdkAndroid',
         'watchTogetherProtocol', 'watchTogetherServer', 'castReceiver', 'scripts', 'docs',
         'gradle', 'config', 'ycore-native', 'macrobenchmark']
new = git('ls-files', '--others', '--exclude-standard', '-z', '--', *roots).decode().split('\0')
paths = sorted({p for p in tracked + new if p and not p.startswith(('audit/', 'artifacts/'))})
blocked = re.compile(r'(^|/)(?:local\.properties|keystore\.properties|\.env(?:\..*)?|storage-state\.json|renderer\.environment)$|\.(?:jks|keystore|apk|aab|aar|zip|7z|pem|p12)$', re.I)
patterns = {
    'private-key': rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----',
    'github-token': rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})',
    'aws-access-key': rb'AKIA[0-9A-Z]{16}',
    'google-api-key': rb'AIza[0-9A-Za-z_-]{35}',
}
manifest = {}
findings = []
for name in paths:
    assert not blocked.search(name), 'Unexpected secret/artifact path: ' + name
    p = ROOT / name
    if not p.exists():
        manifest[name] = {'deleted': True}
        continue
    data = p.read_bytes()
    assert len(data) < 5_000_000, 'Unexpected large file: ' + name
    for kind, pattern in patterns.items():
        if re.search(pattern, data):
            findings.append({'file': name, 'kind': kind})
    manifest[name] = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
(OUT / 'files.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
(OUT / 'paths.nul').write_bytes(b'\0'.join(p.encode() for p in paths) + b'\0')
(OUT / 'secret-scan.json').write_text(json.dumps(findings, indent=2), encoding='utf-8')
print(json.dumps({'files': len(paths), 'bytes': sum(m.get('bytes', 0) for m in manifest.values()),
                  'findings': findings, 'head': git('rev-parse', 'HEAD').decode().strip()}, indent=2))
assert not findings, 'Review credential findings'
