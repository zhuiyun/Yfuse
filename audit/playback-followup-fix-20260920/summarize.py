"""Record local regression results for the follow-up playback fixes. No device access."""
import datetime
import hashlib
import html
import json
from pathlib import Path
import re

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[1]


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


for mode in ("test", "lint"):
    assert "BUILD SUCCESSFUL" in (OUT / f"{mode}.log").read_text(encoding="utf-8", errors="replace"), mode
    assert not read_json(OUT / f"{mode}-concurrent-changes.json"), f"Source changed during {mode}"
test_sources = read_json(OUT / "test-source-before.json")
lint_sources = read_json(OUT / "lint-source-before.json")
assert test_sources == lint_sources, "Source changed between test and lint"
for path, expected_hash in lint_sources.items():
    assert sha(ROOT / path) == expected_hash.lower(), f"Source changed after verification: {path}"
assert not (OUT / "format-files.log").read_text(encoding="utf-8", errors="replace").strip(), "Formatting errors"
results = {}
for module in ("phoneShared", "tvShared"):
    report_path = OUT / "unit-reports" / module / "index.html"
    report = report_path.read_text(encoding="utf-8")
    suites = [
        {"name": html.unescape(name), "tests": int(tests), "failures": int(failures), "skipped": int(skipped)}
        for name, tests, failures, skipped in re.findall(
            r'<td class="path">([^<]+)</td>\s*<td>(\d+)</td>\s*<td>(\d+)</td>\s*<td>(\d+)</td>', report
        )
    ]
    assert suites, module
    totals = {key: sum(s[key] for s in suites) for key in ("tests", "failures", "skipped")}
    for key, value in totals.items():
        assert re.search(rf'<div class="counter">{value}</div>\s*<p>{key}</p>', report), (module, key)
    assert totals["failures"] == totals["skipped"] == 0, totals
    results[module] = {
        "totals": totals,
        "suites": suites,
        "report": str(report_path.relative_to(ROOT)),
        "binary_sha256": sha(OUT / "isolated-results" / module / "testAndroidHostTest/binary/results-generic.bin"),
    }
paths = read_json(OUT / "changed-files.json")
paths += ["docs/PLAYBACK_FOLLOWUP_FIX_20260920.md"]
native = read_json(ROOT / "audit/playback-optimization-20260920/native/result.json")
assert sha(ROOT / "scripts/native/ycore_demux_jni.cpp") == native["compiled_source_sha256"]["ycore_demux_jni.cpp"]
assert sha(ROOT / "composeApp/libs/ycore-native.aar") == native["aar_sha256"]
record = {
    "verified_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "scope": "Series cold launch, initial track selection and tunnel no-op handling, YCore proxy validation reuse",
    "tests": results,
    "compile": ["phone shared and application", "TV shared and application"],
    "lint": ["phone", "TV", "design system usage"],
    "format": "passed",
    "device_operations": "None; user requested no phone operations",
    "performance": "No device timing measured; request counts and behavior verified locally",
    "native": "Source and artifact unchanged from preceding rebuilt and verified native library",
    "native_aar_sha256": native["aar_sha256"],
    "source_sha256": {path: sha(ROOT / path) for path in sorted(set(paths))},
}
(OUT / "verification.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({module: result["totals"] for module, result in results.items()}, indent=2))
