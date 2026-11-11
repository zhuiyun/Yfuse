"""Reassemble only two user-approved runtime libraries, then verify the reviewed tree."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path.cwd()
PREFIX = 'senplayerBridge/src/main/assets/senplayer/native/'
EXPECTED = {
    PREFIX + 'libMLibKit.so': (15671472, 'cf963053a74a64d7325ef64ed5a466f516787fdd8fe1df6f94b48da1ed5f5f4d'),
    PREFIX + 'libFFmpegKit.so': (54435336, 'e6f26a53f8cf793cc1733ae4fbfed30a71794a2ca932c585048021e865aad1a3'),
}
EXPECTED_TREE = 'afd17658fcb600553dbd6ecceae897abd593b304'
manifest = json.loads(Path('.ci-score-upload/manifest.json').read_text())
if manifest['expectedTree'] != EXPECTED_TREE or {f['target'] for f in manifest['files']} != set(EXPECTED):
    raise SystemExit('Unexpected runtime inventory')
for record in manifest['files']:
    target = record['target']
    expected_size, expected_hash = EXPECTED[target]
    if (record['size'], record['sha256']) != (expected_size, expected_hash):
        raise SystemExit('Changed full-file identity')
    output = bytearray()
    for index, part in enumerate(record['parts']):
        path = f'.ci-score-upload/{Path(target).name}.{index:03}.part'
        if part['path'] != path or Path(path).is_symlink():
            raise SystemExit('Unexpected chunk path')
        data = Path(path).read_bytes()
        if len(data) != part['size'] or hashlib.sha256(data).hexdigest() != part['sha256']:
            raise SystemExit('Damaged chunk: ' + path)
        output.extend(data)
    if len(output) != expected_size or hashlib.sha256(output).hexdigest() != expected_hash:
        raise SystemExit('Reassembled runtime identity mismatch: ' + target)
    if Path(target).exists():
        raise SystemExit('Target already exists: ' + target)
    Path(target).write_bytes(output)
subprocess.run(['git', 'add', '--', *EXPECTED], check=True)
actual = subprocess.check_output(['git', 'diff', '--cached', '--name-only'], text=True).splitlines()
if set(actual) != set(EXPECTED):
    raise SystemExit('Unexpected staged changes')
index = Path(subprocess.check_output(['git', 'rev-parse', '--git-path', 'index'], text=True).strip()).resolve()
with tempfile.TemporaryDirectory() as temporary:
    review_index = Path(temporary) / 'index'
    shutil.copyfile(index, review_index)
    env = dict(os.environ, GIT_INDEX_FILE=str(review_index))
    subprocess.run(['git', 'rm', '-r', '--cached', '--', '.ci-score-upload', '.github/workflows/assemble-score-runtime.yml'], env=env, check=True)
    tree = subprocess.check_output(['git', 'write-tree'], text=True, env=env).strip()
if tree != EXPECTED_TREE:
    raise SystemExit('Reconstructed tree differs from reviewed source: ' + tree)
subprocess.run(['python3', '-m', 'unittest', 'discover', '-s', 'scripts/tests', '-p', 'test_vendored_senplayer_assets.py'], check=True)
print('Verified reviewed tree: ' + tree)
