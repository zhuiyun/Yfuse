"""Check original and emitted DEX class names of the supplied release APK (including ExoPlayer 2.x)."""
import argparse
import hashlib
import importlib.util
import json
import re
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('package_check', root / 'scripts/verify-playback-package.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path)
parser.add_argument('mapping', type=Path)
parser.add_argument('out', type=Path)
args = parser.parse_args()
prefixes = tuple(dict.fromkeys(('com.google.android.exoplayer2.', *checker.FORBIDDEN_PACKAGES)))
mapping = checker.read_mapping(args.mapping)
counts, matches = {}, []
with zipfile.ZipFile(args.apk) as archive:
    for member in archive.namelist():
        if not re.fullmatch(r'classes(?:[2-9]|[1-9]\d+)?\.dex', member):
            continue
        count = 0
        for name in checker.dex_defined_classes(archive.read(member)):
            count += 1
            for origin in {name} | mapping.get(name, set()):
                if origin.startswith(prefixes):
                    matches.append({'member': member, 'class': name, 'original': origin})
        counts[member] = count
if not counts:
    raise ValueError('No host DEX classes found')
report = {'sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(), 'prefixes': prefixes,
          'hostClasses': sum(counts.values()), 'dex': counts, 'matches': matches}
args.out.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(f'Checked {report["hostClasses"]} application DEX classes with their R8 origins; {len(matches)} forbidden origins')
raise SystemExit(bool(matches))
