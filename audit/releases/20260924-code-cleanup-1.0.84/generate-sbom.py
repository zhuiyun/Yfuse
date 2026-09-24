"""Archive the current tracked dependency-lock snapshot for this local package."""

import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from supply_chain_check import read_dependencies, read_npm_dependencies, write_spdx  # noqa: E402


dependencies = read_dependencies(ROOT) + read_npm_dependencies(ROOT)
if not dependencies:
    raise SystemExit("No tracked dependencies found")
destination = ROOT / "artifacts-local/releases/code-cleanup-1.0.84-246/dependencies.spdx.json"
write_spdx(dependencies, [], destination)
document = json.loads(destination.read_text(encoding="utf-8"))
document["documentComment"] = "Tracked lockfile snapshot for local packaging; OSV was not queried."
for package in document["packages"]:
    package["comment"] = package["comment"].replace(
        "; known vulnerability at scan time: no", "; OSV not assessed in local packaging"
    )
destination.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Archived {len(dependencies)} locked dependencies in {destination}")
