"""Separate completed, skipped, and failed instrumentation cases."""
import collections
import json
import pathlib
import re
import sys
path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
codes = collections.Counter(int(code) for code in re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)$", text, re.M))
result = {"completed": codes[0], "skipped": codes[-3] + codes[-4], "failed": codes[-1] + codes[-2], "finished": bool(re.search(r"OK \(\d+ tests?\)", text))}
path.with_suffix(".json").write_text(json.dumps(result, indent=2))
print(result)
raise SystemExit(int(not result["finished"] or result["completed"] < 20 or result["failed"] > 0))
