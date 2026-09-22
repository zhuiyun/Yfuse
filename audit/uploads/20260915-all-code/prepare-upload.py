from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
GIT = ['git', '-c', 'safe.directory=' + ROOT.as_posix()]
def git(*args):
    return subprocess.check_output(GIT + list(args), cwd=ROOT)

tracked = git('diff', '--name-only', '-z', 'HEAD').decode().split('\0')
roots = ['composeApp', 'mdkAndroid', 'tvApp', 'watchTogetherProtocol', 'watchTogetherServer',
         'scripts', 'docs', '.github', 'gradle', 'config', 'ycore-native']
new = git('ls-files', '--others', '--exclude-standard', '-z', '--', *roots).decode().split('\0')
baseline = '8dc1e5b22fbd74f014670bf07c1773891f719191'
baseline_additions = git('diff', '--name-only', '--diff-filter=A', '-z', 'HEAD', baseline).decode().split('\0')
paths = sorted(set(p for p in tracked + new + baseline_additions if p))
assert not git('diff', '--cached', '--name-only'), 'Existing staged changes require preservation'
blocked_names = re.compile(r'(^|/)(?:local\.properties|keystore\.properties|\.env(?:\..*)?|storage-state\.json)$|\.(?:jks|keystore|apk|aab|aar|zip|7z)$', re.I)
patterns = {
    'private_key': re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----'),
    'github_token': re.compile(rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})'),
    'aws_key': re.compile(rb'AKIA[0-9A-Z]{16}'),
}
findings = []
manifest = {}
for name in paths:
    assert not blocked_names.search(name), 'Credential or binary path: ' + name
    path = ROOT / name
    if not path.exists():
        manifest[name] = {'deleted': True}
        continue
    data = path.read_bytes()
    assert len(data) < 5_000_000, 'Unexpected large source file: ' + name
    for kind, pattern in patterns.items():
        if pattern.search(data): findings.append({'path': name, 'kind': kind})
    manifest[name] = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
(OUT / 'candidate-files.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
(OUT / 'candidate-paths.txt').write_bytes(b'\0'.join(p.encode() for p in paths) + b'\0')
(OUT / 'secret-scan.json').write_text(json.dumps(findings, indent=2), encoding='utf-8')
print(json.dumps({'candidateFiles': len(paths), 'bytes': sum(i.get('bytes', 0) for i in manifest.values()),
                  'secretPatternFindings': findings}, indent=2))
assert not findings, 'Review secret-pattern findings before staging'
