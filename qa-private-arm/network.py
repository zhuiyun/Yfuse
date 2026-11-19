"""Compare bounded direct reads on cloud hosts without launching a player."""
import json
import os
import platform
import re
import runpy
from pathlib import Path

if os.environ.get("GITHUB_ACTIONS") != "true":
    raise SystemExit("Cloud runner required")

state = runpy.run_path("qa-private-arm/test.py")
items = state["load_envelope"]()
rows = []
for label, media in items.items():
    if not re.fullmatch(r"[me]-[0-9]{3,}", label):
        raise SystemExit("Unexpected source label")
    positions = {"start": 0, "middle": media["size"] // 2, "end": media["size"] - 1024}
    routes = {"resolved-edge": media["location"]}
    if media.get("providerEntry"):
        routes["provider-entry"] = media["providerEntry"]
    for route, location in routes.items():
        source = dict(media, location=location)
        results = {}
        for where, position in positions.items():
            sample = state["sample_range"](source, position)
            results[where] = {key: sample[key] for key in
                              ("code", "bytesRead", "validRange", "providerCode", "providerError", "providerHint")
                              if key in sample}
            results[where]["sameBytesAsSource"] = bool(
                sample.get("validRange") and
                sample.get("signature") == media.get("rangeHashes", {}).get(where)
            )
        rows.append({"label": label, "route": route, "samples": results})

report = {"architecture": platform.machine(), "rows": rows}
Path("network-results").mkdir(exist_ok=True)
Path("network-results/metrics.json").write_text(json.dumps(report, indent=2))
print(json.dumps(report), flush=True)
raise SystemExit(int(not rows or any(not s["sameBytesAsSource"] for row in rows for s in row["samples"].values())))
