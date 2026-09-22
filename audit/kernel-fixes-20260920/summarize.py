"""Record source identity and observed results; fail rather than summarize stale test evidence."""
import datetime
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


log = (OUT / "all.log").read_text(encoding="utf-8", errors="replace")
assert "BUILD SUCCESSFUL" in log, "Unified verification did not pass"
assert not read_json(OUT / "all-concurrent-changes.json"), "Sources changed during verification"
sources = read_json(OUT / "all-source-before.json")
for path, expected in sources.items():
    assert sha(ROOT / path) == expected.lower(), f"Source changed after verification: {path}"
assert "BUILD SUCCESSFUL" in (OUT / "artifacts.log").read_text(encoding="utf-8", errors="replace")
assert not read_json(OUT / "artifacts-concurrent-changes.json")
artifact_sources = read_json(OUT / "artifacts-source-before.json")
for path, expected in artifact_sources.items():
    assert sha(ROOT / path) == expected.lower(), f"Source changed after artifact validation: {path}"
for path, expected in sources.items():
    assert artifact_sources[path] == expected, f"Tested source differs from artifact validation: {path}"

tests = {}
for module in ("phoneShared", "tvShared"):
    build = ROOT / ".gradle-tmp/kernel-fixes-build" / module / "build"
    report_path = build / "reports/tests/testAndroidHostTest/index.html"
    report = report_path.read_text(encoding="utf-8")
    suites = []
    for path in sorted((build / "test-results/testAndroidHostTest").glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        suites.append({
            "name": suite.attrib["name"],
            "tests": int(suite.attrib["tests"]),
            "failures": int(suite.attrib["failures"]) + int(suite.attrib.get("errors", "0")),
            "skipped": int(suite.attrib["skipped"]),
            "result_sha256": sha(path),
        })
    assert suites, f"No test suites in {module} report"
    totals = {key: sum(suite[key] for suite in suites) for key in ("tests", "failures", "skipped")}
    assert totals["failures"] == totals["skipped"] == 0, totals
    for key, value in totals.items():
        assert re.search(rf'<div class="counter">{value}</div>\s*<p>{key}</p>', report), (module, key)
    tests[module] = {"totals": totals, "suites": suites, "report": report_path.relative_to(ROOT).as_posix()}

native = read_json(OUT / "native/result.json")
for name, expected in native["compiled_source_sha256"].items():
    assert sha(ROOT / "scripts/native" / name) == expected, name
assert sha(ROOT / "composeApp/libs/ycore-native.aar") == native["aar_sha256"]
paths = read_json(OUT / "changed-files.json")
required_tests = [Path(path).stem for path in paths if path.endswith("Test.kt")]
phone_suites = tests["phoneShared"]["suites"]
for name in required_tests:
    assert any(suite["name"].endswith("." + name) and suite["tests"] > 0 for suite in phone_suites), f"Regression suite did not execute: {name}"
record = {
    "verified_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "scope": "Disc software recovery, serialized engine retirement, crash ownership, resumable demux controls, MDK state, proxy and memory bounds",
    "tests": tests,
    "required_regression_suites_observed": required_tests,
    "source_inputs_unchanged_during_verification": True,
    "compile": ["phone and TV shared/application Kotlin", "phone instrumented-test Kotlin", "MDK arm64 JNI"],
    "lint": ["phone", "TV", "design-system usage"],
    "native": native,
    "artifact_gate": "Standalone YCore and GPU companion Gradle gates passed; updated read-control API and extradata markers were checked with the final build configuration. Kotlin/Java/native source fingerprints match the preceding unified test/lint run.",
    "native_helper_tests": "Both cross-compiled/syntax-checked with Android NDK; binaries were not executed on this Windows host. CI execution is configured, not observed.",
    "device_validation": "Not run; no device operated, no performance measurements",
    "apk_delivery": "None; no version bump, push or publication requested",
    "source_sha256": {path: sha(ROOT / path) for path in paths},
}
(OUT / "verification.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({module: result["totals"] for module, result in tests.items()}, ensure_ascii=False, indent=2))
