"""Temporary, read-only collection of emulator boot readiness evidence for Yfuse 1.4.0."""

import argparse
import json
from pathlib import Path
import shutil
import time

from android_cloud_ui import Session


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", type=int, choices=(35, 36, 37), required=True)
    args = parser.parse_args()
    output = Path("artifacts/boot-readiness")
    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile("artifact.json", output / "artifact-provenance.json")
    session = Session(output, {
        "sha256": "5cd5e08e8492137b0d04aa2b98610172ae360c144a5fd520883faa3059747b39",
        "versionCode": 292, "versionName": "1.4.0",
    }, 38907588548, args.api)
    session.case("Pinned APK identity and production signature", lambda: session.verify_apk(Path("artifacts/cloud-input")))
    session.case("Disposable emulator identity", session.connect)
    began = time.monotonic()
    samples = []
    commands = {
        "cpuinfo": ("shell", "dumpsys", "cpuinfo"),
        "boot_log": ("logcat", "-d", "-b", "system", "-v", "threadtime",
                     "-s", "UserController:I", "ActivityManager:I", "*:S"),
        "uptime_load": ("shell", "cat", "/proc/uptime", "/proc/loadavg"),
    }
    while time.monotonic() - began < 180:
        sample = {"elapsed_seconds": round(time.monotonic() - began, 2)}
        for name, command in commands.items():
            remaining = 180 - (time.monotonic() - began)
            if remaining <= 0:
                break
            try:
                command_began = time.monotonic()
                sample[name] = session.adb(*command, timeout=max(1, min(10, remaining)))
            except Exception as error:
                sample[name + "_error"] = str(error)
            finally:
                sample[name + "_captured_at_seconds"] = round(time.monotonic() - began, 2)
                sample[name + "_duration_seconds"] = round(time.monotonic() - command_began, 2)
        samples.append(sample)
        (output / "samples.json").write_text(json.dumps(samples, indent=2))
        print(f"Readiness evidence sample {len(samples)} at {sample['elapsed_seconds']}s", flush=True)
        time.sleep(min(10, max(0, 180 - (time.monotonic() - began))))
    session.summary["result"] = "diagnostic_collection_only_not_an_app_smoke_pass"
    session.save()
    session.diagnostics()


if __name__ == "__main__":
    main()
