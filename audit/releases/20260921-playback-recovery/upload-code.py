"""Upload only the reviewed source files matching the delivered APK's input manifest."""
import hashlib
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parents[2]
BASE = 'f627bd04cb0fe307cae0a228abb586f32fc4bb05'
REMOTE = 'git@github.com:zhuiyun/Yfuse.git'
env = os.environ.copy()
env['GIT_SSH_COMMAND'] = 'ssh -o BatchMode=yes -o ConnectTimeout=15'

def git(*args):
    result = subprocess.run(['git', '-c', 'safe.directory=D:/Demo/Yfuse', *args], cwd=ROOT,
                            env=env, capture_output=True, check=True)
    return result.stdout.decode('utf-8').strip()

assert git('branch', '--show-current') == 'master'
assert git('remote', 'get-url', '--push', 'origin') == REMOTE
assert not git('diff', '--cached', '--name-only'), 'Unexpected staged changes'
assert git('rev-parse', 'HEAD') == BASE, 'Local HEAD changed'
assert git('ls-remote', 'origin', 'refs/heads/master').split()[0] == BASE, 'Remote master advanced'
manifest = json.loads((AUDIT / 'source-hashes.json').read_text(encoding='utf-8'))
files = git('diff', '--name-only').splitlines()
files += git('ls-files', '--others', '--exclude-standard', '--', 'composeApp/src').splitlines()
files = sorted(set(files))
assert len(files) == 26, files
assert all(name.startswith('composeApp/src/') or name in ('version.properties', 'release-notes.txt') for name in files)
for name in files:
    assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == manifest[name], name
(AUDIT / 'code-upload-files.json').write_text(json.dumps(files, indent=2) + '\n', encoding='utf-8')
message = AUDIT / 'commit-message.txt'
assert '[artifact only]' in message.read_text(encoding='utf-8').splitlines()[0]
git('add', '--', *files)
assert sorted(git('diff', '--cached', '--name-only').splitlines()) == files
git('diff', '--cached', '--check')
print(git('diff', '--cached', '--stat'), flush=True)
print(git('commit', '-F', str(message)), flush=True)
commit = git('rev-parse', 'HEAD')
assert git('rev-parse', 'HEAD^') == BASE
print(git('push', 'origin', 'HEAD:refs/heads/master'), flush=True)
assert git('ls-remote', 'origin', 'refs/heads/master').split()[0] == commit, 'Remote commit verification failed'
record = dict(versionName='1.0.77', versionCode=239, commit=commit, destination=REMOTE,
              branch='master', pushed=True, remoteVerified=True, artifactOnly=True,
              files=files, validatedTests=862, verifiedAtUtc=datetime.now(timezone.utc).isoformat())
(AUDIT / 'upload-status.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
print(json.dumps({key: record[key] for key in ('commit', 'branch', 'pushed', 'remoteVerified', 'artifactOnly')}, indent=2))
