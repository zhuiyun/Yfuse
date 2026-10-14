"""Summarize real startup milestones from exported diagnostic JSONL, grouped by actual engine.

Run separately for each device, media case and network scenario. This reads existing logs and
never treats a missing output milestone (notably MDK audio) as a zero-latency success.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
from collections import defaultdict


STAGES = {"first_video_output", "first_audio_output"}


def summarize(entries: list[dict]) -> dict:
    groups = defaultdict(list)
    seen = set()
    rejected = 0
    for entry in entries:
        if entry.get("event") != "playback_startup_stage":
            continue  # Rebuffer/output-resumed events are not fresh startups.
        attrs = {str(k).lower(): str(v) for k, v in entry.get("attributes", {}).items()}
        stage = attrs.get("stage")
        if stage not in STAGES:
            continue
        engine = attrs.get("engine", "").strip()
        trace = attrs.get("playbacktrace", "").strip()
        timestamp = entry.get("timestamp")
        try:
            elapsed = int(attrs.get("generationelapsedms", ""))
        except ValueError:
            elapsed = -1
        if not engine or not trace or not timestamp or elapsed < 0:
            rejected += 1
            continue
        route = attrs.get("renderpath", "unknown") or "unknown"
        identity = (timestamp, trace, engine, stage, attrs.get("outputgeneration", ""))
        if identity in seen:
            continue
        seen.add(identity)
        groups[(engine, route, stage)].append(elapsed)
    rows = []
    for (engine, route, stage), samples in sorted(groups.items()):
        ordered = sorted(samples)
        rows.append({
            "engine": engine,
            "render_path": route,
            "stage": stage,
            "samples": len(ordered),
            "p50_ms": ordered[math.ceil(len(ordered) * 0.50) - 1],
            "p95_ms": ordered[math.ceil(len(ordered) * 0.95) - 1],
            "max_ms": ordered[-1],
            "sufficient_samples": len(ordered) >= 20,
        })
    return {
        "metric": "output_generation_to_first_verified_output_ms",
        "quantile_method": "nearest_rank",
        "rows": rows,
        "rejected_startup_entries": rejected,
        "limitations": [
            "Only successful observed output milestones are represented; this is not a startup success rate.",
            "Generation timing excludes work before the engine's output generation; it is not tap-to-play latency.",
            "Unobserved audio/video outputs are missing, never zero.",
            "Seek recovery, memory, thermals and sustained playback require separate device measurements.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", type=Path, nargs="+")
    parser.add_argument("--device", required=True, help="Device model/OS label for this run")
    parser.add_argument("--media-case", required=True, help="Same test clip identifier, without credentials")
    parser.add_argument("--scenario", required=True, help="For example local-cold or webdav-warm")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.resolve() in {path.resolve() for path in args.logs}:
        parser.error("output must not overwrite an input log")
    entries = []
    inputs = []
    for path in args.logs:
        raw = path.read_bytes()
        inputs.append({"name": path.name, "sha256": hashlib.sha256(raw).hexdigest()})
        for number, line in enumerate(raw.decode("utf-8-sig").splitlines(), 1):
            if not line.strip():
                continue
            try:
                entry = json.loads(line)
                if not isinstance(entry, dict) or not isinstance(entry.get("attributes", {}), dict):
                    raise ValueError("expected a log object with attributes")
                entries.append(entry)
            except ValueError as error:
                parser.error(f"{path.name}:{number}: invalid diagnostic JSONL ({type(error).__name__})")
    report = summarize(entries)
    if not report["rows"]:
        parser.error("no verified startup milestones; no baseline can be produced")
    report.update(device=args.device, media_case=args.media_case, scenario=args.scenario, inputs=inputs)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(report['rows'])} measured engine/stage groups to {args.output}")


if __name__ == "__main__":
    main()
