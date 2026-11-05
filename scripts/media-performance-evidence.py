"""Read-only media-process sampler. Never launches apps, installs packages, or invents video metrics.

Run the same playback case in each app, then collect with identical --case JSON and sample.
True first-frame/seek/drop evidence is optional input from an actual decoder/presentation probe,
not Android UI frame statistics. Missing probes remain unknown in the comparison.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess
import time


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def parse_memory(text: str) -> dict:
    # Android's TOTAL PSS includes native/graphics; heap allocation is a different measurement.
    result = {"pss_kib": None, "native_heap_allocated_kib": None, "graphics_pss_kib": None}
    total = re.search(r"TOTAL PSS:\s*(\d+)", text) or re.search(r"^\s*TOTAL\s+(\d+)", text, re.M)
    if total:
        result["pss_kib"] = int(total[1])
    native = re.search(r"^\s*Native Heap\s+(\d+(?:\s+\d+)+)\s*$", text, re.M)
    if native:
        values = [int(x) for x in native[1].split()]
        if len(values) >= 7:
            result["native_heap_allocated_kib"] = values[-2]
    graphics = re.search(r"^\s*Graphics:\s*(\d+)", text, re.M)
    if graphics:
        result["graphics_pss_kib"] = int(graphics[1])
    return result


def adb(binary: str, serial: str, *args: str) -> str:
    result = subprocess.run([binary, "-s", serial, *args], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=30)
    if result.returncode:
        raise RuntimeError("ADB read failed; inspect device connection and permission")
    return result.stdout.strip()


def collect(args) -> None:
    case = json.loads(args.case.read_text(encoding="utf-8"))
    for field in ("network", "cache", "subtitles", "resolution", "audio_route", "screen_brightness", "ambient_celsius"):
        if field not in case:
            raise ValueError(f"Case must explicitly record {field}")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+", args.package):
        raise ValueError("Invalid package")
    if not 1 <= args.interval <= 60 or not 1 <= args.duration <= 7200:
        raise ValueError("Interval/duration outside bounded recording range")
    if args.output.exists():
        raise ValueError("Use a new evidence directory; prior runs are never overwritten")
    pid = adb(args.adb, args.serial, "shell", "pidof", args.package).split()
    if len(pid) != 1 or not pid[0].isdigit():
        raise ValueError("Open exactly one target process before sampling")
    installed = adb(args.adb, args.serial, "shell", "pm", "path", args.package).splitlines()
    paths = [line.removeprefix("package:") for line in installed if line.startswith("package:")]
    if len(paths) != 1 or not re.fullmatch(r"/[A-Za-z0-9_./=+~-]+\.apk", paths[0]):
        raise ValueError("This recorder requires a single installed APK; split packages need separate evidence")
    expected_apk = digest(args.apk)
    actual_apk = adb(args.adb, args.serial, "shell", "sha256sum", paths[0]).split()[0]
    if actual_apk.lower() != expected_apk:
        raise ValueError("Installed APK differs from --apk; no comparison was recorded")
    args.output.mkdir(parents=True)
    metadata = {
        "schema": 1, "started_utc": datetime.now(timezone.utc).isoformat(),
        "package": args.package, "apk_sha256": expected_apk, "installed_apk_verified": True,
        "sample_sha256": digest(args.sample),
        "device": adb(args.adb, args.serial, "shell", "getprop", "ro.build.fingerprint"),
        "device_id_hash": hashlib.sha256(args.serial.encode()).hexdigest(),
        "case": case, "requested_duration_seconds": args.duration, "interval_seconds": args.interval,
        "video_probe": None, "power_watts": None,
        "limitations": ["No power meter: battery temperature is not power consumption", "Missing video probe values remain unknown", "Sampling overhead is included"],
    }
    if args.video_probe:
        probe = json.loads(args.video_probe.read_text(encoding="utf-8"))
        if probe.get("source") not in ("decoder_presentation_callback", "external_optical_measurement"):
            raise ValueError("Video measurements need an actual video presentation probe")
        if probe.get("apk_sha256") != metadata["apk_sha256"] or probe.get("sample_sha256") != metadata["sample_sha256"]:
            raise ValueError("Probe belongs to another APK or sample")
        if probe.get("device_id_hash") != metadata["device_id_hash"] or probe.get("case") != case:
            raise ValueError("Probe belongs to another device or playback condition")
        metadata["video_probe"] = probe
    (args.output / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    start = time.monotonic()
    index = 0
    with (args.output / "samples.jsonl").open("w", encoding="utf-8") as stream:
        while True:
            current = adb(args.adb, args.serial, "shell", "pidof", args.package).split()
            if current != pid:
                raise RuntimeError("Target process changed or exited; this run is incomplete")
            memory = adb(args.adb, args.serial, "shell", "dumpsys", "meminfo", pid[0])
            battery = adb(args.adb, args.serial, "shell", "dumpsys", "battery")
            (args.output / f"meminfo-{index:04d}.txt").write_text(memory, encoding="utf-8")
            (args.output / f"battery-{index:04d}.txt").write_text(battery, encoding="utf-8")
            temperature = re.search(r"temperature:\s*(-?\d+)", battery)
            elapsed = time.monotonic() - start
            sample = {"elapsed_seconds": elapsed, **parse_memory(memory), "battery_celsius": int(temperature[1]) / 10 if temperature else None}
            stream.write(json.dumps(sample) + "\n")
            stream.flush()
            index += 1
            if elapsed >= args.duration:
                break
            time.sleep(min(args.interval, args.duration - elapsed))
    metadata["completed_seconds"] = time.monotonic() - start
    (args.output / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")


def compare(left: Path, right: Path) -> dict:
    runs = [json.loads((path / "metadata.json").read_text(encoding="utf-8")) for path in (left, right)]
    for key in ("device", "device_id_hash", "sample_sha256", "case", "requested_duration_seconds", "interval_seconds"):
        if runs[0].get(key) != runs[1].get(key):
            raise ValueError(f"Not comparable: {key} differs")
    output = {"same_conditions": True, "runs": [], "conclusion": "Memory samples only; no overall performance ranking"}
    for path, run in zip((left, right), runs):
        if run.get("completed_seconds", 0) < run["requested_duration_seconds"]:
            raise ValueError("Incomplete recording")
        samples = [json.loads(line) for line in (path / "samples.jsonl").read_text(encoding="utf-8").splitlines()]
        metrics = {}
        for key in ("pss_kib", "native_heap_allocated_kib", "graphics_pss_kib", "battery_celsius"):
            values = [sample[key] for sample in samples if sample.get(key) is not None]
            metrics[key] = {"median": statistics.median(values), "peak": max(values), "samples": len(values)} if values else None
        output["runs"].append({"package": run["package"], "apk_sha256": run["apk_sha256"], "metrics": metrics, "video_probe": run.get("video_probe"), "power_watts": None})
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    record = commands.add_parser("collect")
    record.add_argument("--adb", default="adb")
    for flag in ("serial", "package"):
        record.add_argument("--" + flag, required=True)
    for flag in ("apk", "sample", "case", "output"):
        record.add_argument("--" + flag, required=True, type=Path)
    record.add_argument("--video-probe", type=Path)
    record.add_argument("--duration", type=int, default=1800)
    record.add_argument("--interval", type=int, default=5)
    comparison = commands.add_parser("compare")
    comparison.add_argument("left", type=Path)
    comparison.add_argument("right", type=Path)
    args = parser.parse_args()
    if args.command == "collect":
        collect(args)
    else:
        print(json.dumps(compare(args.left, args.right), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
