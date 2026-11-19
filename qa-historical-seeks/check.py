"""Reconcile old unconfirmed gesture indices with physical touch and frame evidence."""

import argparse
import json
import re
from pathlib import Path


PLAN = Path(__file__).with_name("affected-batches.json")
APP = "382077026e5747530f320c0188ba2eeebd529b7e"
SHARDS = set(range(24)) - {19}


def targets(seed, duration):
    """Match java.util.Random.nextInt(bound) in the recorded Android driver."""
    state = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)
    bound = duration - 13
    previous = -20
    values = []
    while len(values) < 16:
        state = (state * 0x5DEECE66D + 11) & ((1 << 48) - 1)
        bits = state >> 17
        if bound & (bound - 1):
            value = bits % bound
            if bits - value + bound - 1 >= 1 << 31:
                continue
        else:
            value = (bound * bits) >> 31
        target = 5 + value
        if abs(target - previous) >= min(18, duration // 4):
            values.append(target)
            previous = target
    return values


def read_plan():
    plan = json.loads(PLAN.read_text())
    assert plan["schema"] == 1 and plan["sourceRuns"] == [43360363368, 43364313750]
    assert {int(key) for key in plan["shards"]} == SHARDS and plan["batchLength"] == 16
    digests = plan["originalFileDigests"]
    assert set(digests) == {f"volume-{shard}.json" for shard in SHARDS}
    assert all(re.fullmatch("[0-9a-f]{64}", value) for value in digests.values())
    files = batches = count = 0
    for shard, clips in plan["shards"].items():
        for name, clip in clips.items():
            match = re.fullmatch(r"clip-(\d{2})-(\d{2})\.(mp4|mkv|mov|webm|ts)", name)
            assert match and int(match[1]) == int(shard) and 0 <= int(match[2]) < 8
            assert clip["width"] in (320, 480, 640, 960, 1280) and clip["historicalBytes"] > 0
            assert clip["codec"] in ("h264", "hevc", "vp9", "mpeg4")
            assert clip["duration"] == 79 + (int(shard) * 11 + int(match[2]) * 7) % 34
            files += 1
            for batch, group in clip["batches"].items():
                assert batch in ("0", "1", "2", "3")
                seed = 381710 + int(shard) * 701 + int(match[2]) * 13 + int(batch)
                assert group["seed"] == seed and group["failedTargets"]
                expected = targets(seed, clip["duration"])
                for index, target in group["failedTargets"].items():
                    assert str(int(index)) == index and 0 <= int(index) < 16
                    assert expected[int(index)] == target
                    count += 1
                batches += 1
    assert (files, batches, count) == (172, 383, 1971)
    assert (batches, count) == (plan["affectedBatches"], plan["unconfirmedGestures"])
    return plan


def moving_frames(row, target):
    if row.get("issued") is not True or row.get("ok") is not True:
        return False
    first, later, elapsed, anchored = (row.get(key) for key in ("first", "later", "ms", "anchorElapsedMs"))
    if not all(isinstance(n, (int, float)) for n in (first, later, elapsed, anchored)):
        return False
    return (0 <= anchored <= elapsed and first == row.get("anchorPTS") and
            target - 2.1 <= first <= target + anchored / 1000 + 1.8 and
            first + .375 <= later <= min(target + elapsed / 1000 + 1.8,
                                         first + (elapsed - anchored) / 1000 + 1.75))


def verify_engine(clips, core, volume, fixtures, shard):
    expected = {}
    for name, clip in clips.items():
        for group in clip["batches"].values():
            for index, target in enumerate(targets(group["seed"], clip["duration"])):
                expected[name, group["seed"], index] = (target, str(index) in group["failedTargets"])
    counts = dict(planned=len(expected), issued=0, confirmed=0,
                  plannedHistorical=sum(old for _, old in expected.values()),
                  issuedHistorical=0, confirmedHistorical=0)
    issues = []
    seen = set()
    seen_files = set()
    for clip in volume.get("files", []):
        name = clip.get("name")
        if name not in clips or name in seen_files:
            issues.append({"file": name, "reason": "unexpected or duplicate file"})
            continue
        seen_files.add(name)
        old, fixture = clips[name], fixtures.get(name, {})
        descriptors = all(clip.get(k) == old[k] == fixture.get(k) for k in ("duration", "codec", "width"))
        size = fixture.get("size", 0)
        valid = descriptors and size > 0 and clip.get("size") == size and clip.get("mime") == fixture.get("mime")
        sessions = clip.get("sessions", [])
        identity = sessions[0].get("identity", {}) if len(sessions) == 1 else {}
        active = (identity.get("senOriginalLibrary") and identity.get("senOutputEvent") if core == "SenPlayer"
                  else not identity.get("senOriginalLibrary") and (identity.get("ycoreLibrary") or identity.get("ycoreEvents", 0) > 0))
        valid = bool(valid and active and identity.get("engine") == core and identity.get("active") is True
                     and sessions[0].get("opened") is True)
        if not valid:
            issues.append({"file": name, "reason": "missing verified fixture, engine or advancing open"})
        for row in clip.get("rows", []):
            key = name, row.get("seed"), row.get("i")
            if key not in expected or key in seen:
                issues.append({"file": name, "seed": key[1], "index": key[2], "reason": "unexpected or duplicate row"})
                continue
            seen.add(key)
            target, old_miss = expected[key]
            matched = row.get("target") == target and row.get("historicalUnconfirmed") is old_miss
            issued = matched and row.get("issued") is True
            passed = valid and matched and moving_frames(row, target)
            counts["issued"] += int(issued)
            counts["confirmed"] += int(passed)
            counts["issuedHistorical"] += int(issued and old_miss)
            counts["confirmedHistorical"] += int(passed and old_miss)
            if not passed:
                issues.append({"file": name, "seed": key[1], "index": key[2], "target": target,
                               "historical": old_miss, "reason": row.get("reason", "target, frame pair or provenance mismatch")})
    for key in sorted(expected.keys() - seen):
        target, old_miss = expected[key]
        issues.append({"file": key[0], "seed": key[1], "index": key[2], "target": target,
                       "historical": old_miss, "reason": "missing observation"})
    for key in ("planned", "issued", "verified", "plannedHistoricalFailures", "shard"):
        expected_value = {"planned": counts["planned"], "issued": counts["issued"], "verified": counts["confirmed"],
                          "plannedHistoricalFailures": counts["plannedHistorical"], "shard": shard}[key]
        if volume.get(key) != expected_value:
            issues.append({"reason": f"device total differs for {key}"})
    return {**counts, "issues": issues}


def verify_results(root, shard, plan):
    clips = plan["shards"][str(shard)]
    provenance = (root / "provenance.txt").read_text().splitlines()
    assert f"app={APP}" in provenance
    digest = (root / "apk.sha256").read_text().strip().split()
    assert len(digest) == 2 and re.fullmatch("[0-9a-f]{64}", digest[0]) and digest[1] == "composeApp-release.apk"
    fixtures = {clip["name"]: clip for clip in json.loads((root / "fixtures.json").read_text())}
    receipt = {"app": APP, "apkSha256": digest[0], "shard": shard, "originalRuns": plan["sourceRuns"],
               "originalResultSha256": plan["originalFileDigests"][f"volume-{shard}.json"], "engines": {}}
    for core in ("YCore", "SenPlayer"):
        path = root / core / "volume.json"
        volume = json.loads(path.read_text()) if path.is_file() else {}
        receipt["engines"][core] = verify_engine(clips, core, volume, fixtures, shard)
    (root / "reconciliation.json").write_text(json.dumps(receipt, indent=2) + "\n")
    for core, summary in receipt["engines"].items():
        print(core, {k: v for k, v in summary.items() if k != "issues"}, "issues", len(summary["issues"]))
    return all(not item["issues"] for item in receipt["engines"].values())


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path)
    parser.add_argument("--shard", type=int, choices=sorted(SHARDS))
    args = parser.parse_args()
    if (args.results is None) != (args.shard is None):
        parser.error("--results and --shard must be used together")
    plan = read_plan()
    print("172 historical files, 383 batches and all 1971 original target indices verified")
    if args.results is not None:
        raise SystemExit(0 if verify_results(args.results, args.shard, plan) else 1)
