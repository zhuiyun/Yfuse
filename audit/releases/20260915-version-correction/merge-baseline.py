"""Three-way integrate the verified delivery baseline without touching the index or losing local work."""
import io
import json
import subprocess
import zipfile
from pathlib import Path

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[2]
REMOTE = OUT / 'baseline-source'
GIT = ['git', '-c', 'safe.directory=D:/Demo/Yfuse']
base_zip = subprocess.check_output(GIT + ['archive', '--format=zip', 'HEAD'], cwd=ROOT)
(OUT / 'local-head.zip').write_bytes(base_zip)
with zipfile.ZipFile(io.BytesIO(base_zip)) as archive:
    base = {name: archive.read(name) for name in archive.namelist() if not name.endswith('/')}
remote = {path.relative_to(REMOTE).as_posix(): path.read_bytes() for path in REMOTE.rglob('*') if path.is_file()}
special = {'version.properties', 'release-notes.txt', 'AGENTS.md'}
changed = [name for name in sorted(base.keys() | remote.keys()) if base.get(name) != remote.get(name)]
report = {'baselineCommit': '8dc1e5b22fbd74f014670bf07c1773891f719191', 'changedFiles': changed,
          'applied': [], 'alreadyPresent': [], 'conflicts': [], 'special': []}


def normalized(value):
    return value.replace(b'\r\n', b'\n') if value is not None and b'\x00' not in value else value


for name in changed:
    if name in special:
        report['special'].append(name)
        continue
    target = (ROOT / name).resolve()
    if not target.is_relative_to(ROOT) or '.git' in Path(name).parts:
        raise RuntimeError('Unsafe destination: ' + name)
    ours = target.read_bytes() if target.is_file() else None
    previous, theirs = base.get(name), remote.get(name)
    backup = OUT / 'before-merge' / name
    if ours is not None:
        backup.parent.mkdir(parents=True, exist_ok=True)
        backup.write_bytes(ours)
    if normalized(ours) == normalized(theirs):
        report['alreadyPresent'].append(name)
        continue
    if normalized(ours) == normalized(previous):
        merged = theirs
    elif previous is not None and ours is not None and theirs is not None and all(b'\x00' not in value for value in (previous, ours, theirs)):
        merge_dir = OUT / 'merge-inputs' / name
        merge_dir.mkdir(parents=True, exist_ok=True)
        for label, data in [('base', previous), ('ours', ours), ('theirs', theirs)]:
            (merge_dir / label).write_bytes(normalized(data))
        result = subprocess.run(GIT + ['merge-file', '-p', '-L', 'CURRENT-WORKSPACE', '-L', 'LOCAL-HEAD',
                                     '-L', 'DELIVERED-1.0.64', str(merge_dir / 'ours'), str(merge_dir / 'base'),
                                     str(merge_dir / 'theirs')], capture_output=True)
        (merge_dir / 'merged').write_bytes(result.stdout)
        if result.returncode != 0:
            report['conflicts'].append(name)
            continue
        merged = result.stdout
    else:
        report['conflicts'].append(name)
        continue
    if merged is None:
        if target.exists():
            target.unlink()
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(merged)
    report['applied'].append(name)

(OUT / 'merge-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps({key: value if key in ('conflicts', 'special') else len(value)
                  for key, value in report.items() if key != 'baselineCommit'}, indent=2))
