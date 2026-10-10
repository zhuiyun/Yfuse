import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
AUDIT = Path(__file__).resolve().parent


def read_log(path):
    data = path.read_bytes()
    return data.decode("utf-16" if data.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")


sources = json.loads((AUDIT / "source-inputs.json").read_text(encoding="utf-8"))
for source in sources:
    assert hashlib.sha256((ROOT / source["path"]).read_bytes()).hexdigest() == source["sha256"], source["path"]
assert len(sources) == 7
assert "Checking 7 changed Kotlin files" in read_log(AUDIT / "ktlint-final.log")
assert "BUILD SUCCESSFUL" in read_log(AUDIT / "ktlint-final.log")
assert "BUILD SUCCESSFUL" in read_log(AUDIT / "tests-final.log")

modules = {}
relevant_suites = {}
for module in ("phoneShared", "tvShared"):
    counts = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    files = sorted((ROOT / module / "build/test-results/testAndroidHostTest").glob("TEST-*.xml"))
    assert files, module
    for file in files:
        suite = ET.parse(file).getroot()
        for key in counts:
            counts[key] += int(suite.attrib.get(key, "0"))
        if suite.attrib["name"].rsplit(".", 1)[-1] in (
            "CalendarReminderSubscriptionTest",
            "CalendarFollowStoreTest",
            "OfficialAiringScheduleCatalogTest",
        ):
            relevant_suites[suite.attrib["name"]] = {
                "tests": int(suite.attrib["tests"]),
                "failures": int(suite.attrib.get("failures", "0")),
                "errors": int(suite.attrib.get("errors", "0")),
                "cases": [case.attrib["name"] for case in suite.findall("testcase")],
            }
    assert counts["tests"] > 0 and all(counts[key] == 0 for key in ("failures", "errors", "skipped")), (module, counts)
    modules[module] = counts

assert relevant_suites["com.yfuse.core.data.CalendarReminderSubscriptionTest"]["tests"] == 9
result = {
    "source_files": sources,
    "format_checked_files": len(sources),
    "tests": modules,
    "relevant_suites": relevant_suites,
    "new_regression_tests": 14,
    "new_apk_built": False,
    "device_notification_test_performed": False,
    "pushed": False,
    "deployed": False,
}
(AUDIT / "verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"tests": modules, "format_checked_files": len(sources)}, ensure_ascii=False))
