"""Reuse observed tests only when their actual source inputs still match the packaging checkout."""
import hashlib
import json
from pathlib import Path

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[2]
EVIDENCE = ROOT / "audit/frame-rate-overlay-20260920"
record = json.loads((EVIDENCE / "verification.json").read_text(encoding="utf-8"))
before = json.loads((EVIDENCE / "all-source-before.json").read_text(encoding="utf-8-sig"))
assert json.loads((EVIDENCE / "all-concurrent-changes.json").read_text(encoding="utf-8-sig")) == []
allowed = {"version.properties", "release-notes.txt"}
changed = []
metadata_changes = []
for relative, expected in before.items():
    path = ROOT / relative
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected.lower():
        (metadata_changes if relative in allowed else changed).append(relative)
if changed:
    raise SystemExit("Tested inputs changed: " + ", ".join(changed))
kernel_record = json.loads((ROOT / "audit/kernel-fixes-20260920/verification.json").read_text(encoding="utf-8"))
native = kernel_record["native"]
if hashlib.sha256((ROOT / "composeApp/libs/ycore-native.aar").read_bytes()).hexdigest() != native["aar_sha256"]:
    raise SystemExit("Verified native artifact changed")
summary = {
    "evidence": "audit/frame-rate-overlay-20260920/verification.json",
    "verifiedAt": record["verifiedAt"],
    "sourceInputsCompared": len(before),
    "onlyUntestedInputChanges": sorted(metadata_changes),
    "phone": record["tests"]["phoneShared"],
    "tv": record["tests"]["tvShared"],
    "requiredRegressionSuites": record["regressionSuites"],
    "lint": record["lint"],
    "native": native,
    "devicePlaybackTested": False,
    "instrumentationTests": "Not executed; earlier kernel validation compiled instrumentation sources before the FPS addition",
}
for module in ("phone", "tv"):
    if summary[module]["failures"] or summary[module].get("errors", 0) or summary[module]["skipped"]:
        raise SystemExit("Regression results are not passing")
(OUT / "test-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"phone": summary["phone"], "tv": summary["tv"], "sourceInputsCompared": summary["sourceInputsCompared"]}, indent=2))
