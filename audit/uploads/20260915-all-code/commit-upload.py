from pathlib import Path
import hashlib
import json
import os
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
BASE = '8dc1e5b22fbd74f014670bf07c1773891f719191'
BRANCH = 'codex/all-code-1.0.65-20260915'
GIT = ['git', '-c', 'safe.directory=' + ROOT.as_posix()]
def git(*args, env=None, input=None):
    return subprocess.check_output(GIT + list(args), cwd=ROOT, env=env, input=input)

assert not git('diff', '--cached', '--name-only'), 'Original index is no longer empty'
manifest = json.loads((OUT / 'candidate-files.json').read_text())
for name, item in manifest.items():
    path = ROOT / name
    if item.get('deleted'):
        assert not path.exists(), name
    else:
        assert hashlib.sha256(path.read_bytes()).hexdigest() == item['sha256'], name
assert git('rev-parse', 'origin/claude/playback-page-close-animation-qwaqfq').decode().strip() == BASE
git('merge-base', '--is-ancestor', 'origin/master', BASE)
index = OUT / 'upload.index'
env = dict(os.environ, GIT_INDEX_FILE=str(index))
git('read-tree', BASE, env=env)
git('add', '--pathspec-from-file=' + str(OUT / 'candidate-paths.txt'), '--pathspec-file-nul', env=env)
git('diff', '--cached', '--check', env=env)
changes = git('diff', '--cached', '--name-only', BASE, env=env).decode().splitlines()
tree = git('write-tree', env=env).decode().strip()
message = ('Consolidate family, sync and playback improvements for 1.0.65 [artifact only]\n\n'
           'Preserve delivered 1.0.64 and current master history while including all local application, '
           'server, protocol, test, build and workflow changes.\n\n'
           'Validation: 2887 JVM/Android tests, 58 Python tests, five ktlint modules, '
           'signed Full ARM64 release and final APK version/signature/native verification.\n'
           'No playback, deployment or APK publication performed.\n')
commit = git('commit-tree', tree, '-p', BASE, input=message.encode(), env=env).decode().strip()
git('update-ref', 'refs/heads/' + BRANCH, commit, '')
# Match the normal index to the already-verified snapshot; never check out or overwrite source files.
git('read-tree', commit)
git('symbolic-ref', 'HEAD', 'refs/heads/' + BRANCH)
assert git('rev-parse', 'HEAD^{tree}').decode().strip() == tree
assert not git('diff', '--name-only'), 'Working tracked files differ from the committed snapshot'
assert not git('diff', '--cached', '--name-only'), 'Index differs from committed snapshot'
report = {'branch': BRANCH, 'commit': commit, 'tree': tree, 'parent': BASE,
          'changedFilesComparedWithDeliveredBaseline': len(changes), 'files': changes,
          'pushed': False}
(OUT / 'upload.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: v for k, v in report.items() if k != 'files'}, indent=2))
