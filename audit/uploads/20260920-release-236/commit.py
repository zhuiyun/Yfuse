from pathlib import Path
import hashlib
import json
import subprocess

ROOT = Path('D:/Demo/Yfuse')
OUT = Path(__file__).resolve().parent
GIT = ['git', '-c', 'safe.directory=D:/Demo/Yfuse']
def git(*args):
    return subprocess.check_output(GIT + list(args), cwd=ROOT)

expected_head = '1bbf5f12f05e43ef4b6e84ef837766ee92a31bfc'
assert git('rev-parse', 'HEAD').decode().strip() == expected_head
assert git('branch', '--show-current').decode().strip() == 'master'
assert not git('diff', '--cached', '--name-only')
manifest = json.loads((OUT / 'files.json').read_text(encoding='utf-8'))
for name, item in manifest.items():
    p = ROOT / name
    if item.get('deleted'):
        assert not p.exists(), name
    else:
        assert hashlib.sha256(p.read_bytes()).hexdigest() == item['sha256'], name
git('add', '--pathspec-from-file=' + str(OUT / 'paths.nul'), '--pathspec-file-nul')
staged = set(filter(None, git('diff', '--cached', '--name-only', '-z').decode().split('\0')))
assert staged == set(manifest), 'Unexpected staged paths'
git('diff', '--cached', '--check')
message = '''Ship 1.0.74 kernel fixes and frame rate diagnostics [artifact only]

Include the delivered phone/TV playback, HTTP compatibility, API 37,
glass settings, sync, server resource bounds and dependency improvements.
Add page, rendered output and source FPS settings and regression tests.
Update native demux interruption, software fallback and lifecycle cleanup.

Validation: source matches the signed 1.0.74 (236) release; existing verified
phone 2871 and TV 63 tests match current inputs. Node 10 and Python 47 tests
passed before upload; git diff --check passed. No physical device validation.

The signed APK is already published; retain the artifact-only marker to
avoid repeating production publication on this source upload.
'''
(OUT / 'commit-message.txt').write_text(message, encoding='utf-8')
print(git('commit', '-F', str(OUT / 'commit-message.txt')).decode())
record = {'branch': 'master', 'commit': git('rev-parse', 'HEAD').decode().strip(),
          'parent': expected_head, 'files': len(staged), 'pushed': False}
(OUT / 'upload.json').write_text(json.dumps(record, indent=2), encoding='utf-8')
print(json.dumps(record, indent=2))
