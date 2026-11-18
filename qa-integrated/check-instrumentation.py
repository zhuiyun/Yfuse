"""Separate completed, skipped, and failed instrumentation cases."""
import collections
import json
import pathlib
import re
import sys
path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
codes = collections.Counter()
completed_cases = collections.Counter()
case = {}
for line in text.splitlines():
    field = re.match(r"INSTRUMENTATION_STATUS: (class|test)=(.*)", line)
    if field:
        case[field[1]] = field[2]
    status = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
    if status:
        # reportProgress sends status 0 without a class or test; it is not a passed case.
        if case.get("class") and case.get("test"):
            codes[int(status[1])] += 1
            if status[1] == "0":
                completed_cases[case["class"] + "#" + case["test"]] += 1
        case = {}
reported = re.search(r"OK \((\d+) tests?\)", text)
result = {"completed": codes[0], "skipped": codes[-3] + codes[-4], "failed": codes[-1] + codes[-2], "finished": bool(reported)}
invalid = not reported or result["completed"] < int(sys.argv[2] if len(sys.argv) > 2 else 20) or result["failed"] > 0
if sys.argv[3:]:
    result["requiredCaseCounts"] = {name: completed_cases[name] for name in sys.argv[3:]}
    invalid = (invalid or result["skipped"] > 0 or result["completed"] != int(reported[1])
               or any(count != 1 for count in result["requiredCaseCounts"].values()))
path.with_suffix(".json").write_text(json.dumps(result, indent=2))
print(result)
raise SystemExit(int(invalid))
