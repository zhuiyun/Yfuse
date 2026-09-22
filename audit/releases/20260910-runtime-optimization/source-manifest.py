"""Record source/build inputs, excluding local properties and signing credentials."""
import hashlib
import json
import sys
from pathlib import Path

OUTPUT = Path(__file__).resolve().parent
ROOT = OUTPUT.parents[2]
paths = []
for directory in ('composeApp/src', 'mdkAndroid/src', 'watchTogetherProtocol/src', 'gradle'):
    paths.extend(p for p in (ROOT / directory).rglob('*') if p.is_file())
for name in ('composeApp/build.gradle.kts', 'composeApp/proguard-rules.pro',
             'mdkAndroid/build.gradle.kts', 'watchTogetherProtocol/build.gradle.kts',
             'build.gradle.kts', 'settings.gradle.kts', 'version.properties', 'release-notes.txt'):
    path = ROOT / name
    if path.is_file():
        paths.append(path)
manifest = {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(set(paths))}
target = OUTPUT / 'source-hashes.json'
if sys.argv[1] == 'capture':
    target.write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print(f'Recorded {len(manifest)} source and build files')
else:
    original = json.loads(target.read_text(encoding='utf-8'))
    changed = sorted(k for k in original.keys() | manifest.keys() if original.get(k) != manifest.get(k))
    if changed:
        raise SystemExit('Release inputs changed: ' + ', '.join(changed))
    print(f'Verified {len(manifest)} source and build files unchanged during packaging')
