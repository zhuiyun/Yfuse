"""Validate the focused regression evidence against this release's changed inputs."""
import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[2]
EVIDENCE = ROOT / 'audit/update-check-20260920'
record = json.loads((EVIDENCE / 'verification.json').read_text(encoding='utf-8'))
baseline = json.loads((ROOT / 'audit/releases/20260920-kernel-fixes/source-hashes.json').read_text(encoding='utf-8'))
tested = record['sourceSha256']
for name, expected in tested.items():
    assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == expected, f'Tested source changed: {name}'
changed = sorted(name for name, expected in baseline.items()
                 if not (ROOT / name).is_file() or hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != expected)
assert set(changed) <= set(tested) | {'version.properties', 'release-notes.txt'}, changed
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for result in (EVIDENCE / 'unit-results').glob('TEST-*.xml'):
    suite = ET.parse(result).getroot()
    for key in counts:
        counts[key] += int(suite.attrib[key])
assert counts == record['counts'] and counts == dict(tests=57, failures=0, errors=0, skipped=0), counts
summary = {
    'evidence': 'audit/update-check-20260920/verification.json',
    'updateRegressionTests': counts,
    'androidDebugKotlinCompilation': record['androidDebugKotlinCompilation'],
    'changedFilesKtlint': record['changedFilesKtlint'],
    'changedInputsFromPreviousRelease': changed,
    'previousReleaseInputsCompared': len(baseline),
    'fullSuiteRerun': False,
    'deviceTested': False,
}
(OUT / 'test-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False, indent=2))
