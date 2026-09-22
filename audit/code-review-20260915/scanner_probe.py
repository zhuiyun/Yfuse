"""Local audit evidence: no network calls and no production-file changes."""
from __future__ import annotations

import io
import json
import pathlib
import subprocess
import sys
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import supply_chain_check as scanner

tracked = subprocess.check_output(
    ["git", "-c", "safe.directory=" + ROOT.as_posix(), "ls-files", "--", "**/gradle.lockfile"],
    cwd=ROOT, text=True,
).splitlines()
tracked_paths = {pathlib.PurePosixPath(p).as_posix() for p in tracked}
all_locks = sorted(p.relative_to(ROOT).as_posix() for p in ROOT.rglob("gradle.lockfile"))
dependencies = scanner.read_dependencies(ROOT)
overrides = scanner.read_security_overrides(ROOT)
tracked_coordinates = set()
for relative in tracked:
    for raw in (ROOT / relative).read_text(encoding="utf-8").splitlines():
        match = scanner.COORDINATE.match(raw.strip())
        if match:
            group, name, version = match.groups()
            version = overrides.get(f"{group}:{name}", version)
            tracked_coordinates.add(f"{group}:{name}:{version}")

batch = [scanner.Dependency("audit", "fixture", "1", "audit")]
malformed_outcomes = {}
for label, response in {"missing_results": {}, "short_results": {"results": []}}.items():
    with patch.object(scanner.urllib.request, "urlopen", return_value=io.BytesIO(json.dumps(response).encode())):
        malformed_outcomes[label] = scanner.query_osv(batch)
assert all(value == [] for value in malformed_outcomes.values())

evidence = {
    "tracked_lockfiles": tracked,
    "extra_scanned_lockfiles": [p for p in all_locks if p not in tracked_paths],
    "effective_scanned_coordinate_count": len(dependencies),
    "effective_tracked_coordinate_count": len(tracked_coordinates),
    "extra_coordinates": sorted({d.coordinate for d in dependencies} - tracked_coordinates),
    "malformed_osv_responses_silently_return_no_findings": malformed_outcomes,
    "probe_note": "Assertions describe the current defect, not corrected behavior; OSV is mocked.",
}
output = pathlib.Path(__file__).with_name("scanner-evidence.json")
output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(evidence, ensure_ascii=False, indent=2))
