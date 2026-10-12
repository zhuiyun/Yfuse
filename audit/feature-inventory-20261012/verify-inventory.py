"""Validate inventory references and preserve real Gradle regression results."""

from collections import Counter
from pathlib import Path
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET

audit = Path(__file__).resolve().parent
root = audit.parent.parent
document = root / "docs/FEATURE_INVENTORY_20261012.md"
source = document.read_text(encoding="utf-8")
features = []
group = ""
for line in source.splitlines():
    if line.startswith("## "):
        group = line[3:]
    if not re.match(r"^\| [A-N]\d{2} \|", line):
        continue
    fields = [value.strip() for value in line.strip("|").split("|")]
    assert len(fields) == 5, line
    priority, necessity = fields[2].split(" ", 1)
    assert priority in {"P0", "P1", "P2", "P3"}
    assert necessity in {"必须", "场景必要", "可选", "暂缓"}
    features.append(dict(id=fields[0], group=group, feature=fields[1], priority=priority, necessity=necessity, status=fields[3], rationale=fields[4]))
assert len({feature["id"] for feature in features}) == len(features)
references = re.findall(r"\]\((D:/[^)]+)\)", source)
missing = [value for value in references if not Path(value).exists()]
assert not missing, missing
test_results = []
destination = audit / "calendar-test-results"
destination.mkdir(exist_ok=True)
for path in sorted((root / "phoneShared/build/test-results/testAndroidHostTest").glob("TEST-com.yfuse.feature.calendar.*.xml")):
    suite = ET.parse(path).getroot()
    result = {name: int(suite.attrib.get(name, "0")) for name in ("tests", "failures", "errors", "skipped")}
    result["suite"] = suite.attrib["name"]
    assert result["failures"] == result["errors"] == result["skipped"] == 0, result
    test_results.append(result)
    shutil.copyfile(path, destination / path.name)
assert any(result["suite"].endswith("CalendarIcsTest") and result["tests"] == 8 for result in test_results)
before = ET.parse(audit / "calendar-before-fix.xml").getroot()
assert int(before.attrib["tests"]) == 8 and int(before.attrib["failures"]) == 8
assert "BUILD SUCCESSFUL" in (audit / "calendar-after-fix.log").read_text(encoding="utf-8-sig")
assert "BUILD SUCCESSFUL" in (audit / "ktlint.log").read_text(encoding="utf-8-sig")
summary = {
    "sourceCommit": "b172c7e9b1dad94871166b7545a94f9b9bf08f38",
    "featureCount": len(features),
    "groupCount": len({feature["group"] for feature in features}),
    "priorities": dict(Counter(feature["priority"] for feature in features)),
    "necessity": dict(Counter(feature["necessity"] for feature in features)),
    "byGroup": dict(Counter(feature["group"] for feature in features)),
    "validatedLocalReferences": len(references),
    "calendarRegressionTests": sum(result["tests"] for result in test_results),
    "calendarRegressionSuites": len(test_results),
    "calendarExportBeforeFix": {"tests": 8, "failures": 8},
    "calendarExportAfterFix": {"tests": 8, "failures": 0},
    "kotlinStyleChecksPassed": True,
    "artifactSha256": {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in (document, root / "composeApp/src/commonMain/kotlin/com/yfuse/feature/calendar/CalendarIcs.kt", root / "composeApp/src/commonTest/kotlin/com/yfuse/feature/calendar/CalendarIcsTest.kt")},
    "apkRebuilt": False,
    "serverChanged": False,
}
(audit / "inventory.json").write_text(json.dumps({"summary": summary, "features": features}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
(audit / "validation.json").write_text(json.dumps({"summary": summary, "testSuites": test_results}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(summary, ensure_ascii=False, indent=2))
