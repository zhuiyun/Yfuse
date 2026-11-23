"""Count actual device-issued and picture-verified gestures from completed cloud shards."""
import collections
import json
from pathlib import Path

root = Path(__file__).resolve().parent
summary = {}
for engine in ("YCore", "SenPlayer"):
    counts = dict(plannedTotal=13760, collectedPlanned=0, issued=0, verified=0,
                  issuedFailures=0, unissuedInCollectedShards=0, files=0, openedFiles=0,
                  openedSessions=0, incompleteSessions=0, completedShards=[], failures=[])
    kinds = collections.Counter()
    formats = collections.Counter()
    for path in sorted((root / "evidence").glob(f"integrated-{engine}-*/volume.json")):
        report = json.loads(path.read_text(encoding="utf-8"))
        counts["completedShards"].append(report["shard"])
        counts["collectedPlanned"] += report["planned"]
        issued = verified = 0
        for media in report["files"]:
            counts["files"] += 1
            counts["openedFiles"] += media.get("open") == "passed"
            kinds[media["kind"]] += 1
            formats[f'{media["codec"]}/{media["name"].rsplit(".", 1)[-1]}'] += 1
            for session in media["sessions"]:
                good = session["opened"] and session["identity"]["active"]
                counts["openedSessions"] += bool(good)
                counts["incompleteSessions"] += not good
            for row in media["rows"]:
                issued += bool(row.get("issued"))
                verified += bool(row.get("ok"))
                if row.get("issued") and not row.get("ok"):
                    counts["failures"].append(dict(shard=report["shard"], file=media["name"], row=row))
        assert issued == report["issued"] and verified == report["verified"]
        counts["issued"] += issued
        counts["verified"] += verified
        counts["unissuedInCollectedShards"] += report["planned"] - issued
    counts["issuedFailures"] = len(counts["failures"])
    counts["pendingPlanned"] = counts["plannedTotal"] - counts["collectedPlanned"]
    counts["mediaKinds"] = dict(kinds)
    counts["formats"] = dict(formats)
    summary[engine] = counts
(root / "current-volume-counts.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
print(json.dumps({engine: {k: v for k, v in row.items() if k != "failures"} for engine, row in summary.items()}))
