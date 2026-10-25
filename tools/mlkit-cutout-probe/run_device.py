"""Run only on a disposable emulator and retain real SDK output/environment failures."""
from pathlib import Path
import json
import os
import subprocess

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "artifacts"
PACKAGE = "com.yfuse.cutoutprobe"


def adb(*args, timeout=60, binary=False):
    return subprocess.run(["adb", *args], capture_output=True, text=not binary, timeout=timeout)


def main():
    OUT.mkdir(exist_ok=True)
    if adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1":
        raise SystemExit("This unattended runner accepts disposable emulators only")
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    install = adb("install", "-r", str(apk), timeout=120)
    (OUT / "install.txt").write_text(install.stdout + install.stderr)
    install.check_returncode()
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    # Exercise a foreground app, then keep its result files independent of the UI process.
    process = subprocess.Popen(["adb", "shell", "am", "instrument", "-w", "-r",
                                f"{PACKAGE}/.ProbeInstrumentation"],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    with (OUT / "instrumentation.txt").open("w") as log:
        for line in process.stdout:
            print(line, end="", flush=True)
            log.write(line)
            log.flush()
    process.wait(timeout=30)
    files = adb("shell", "run-as", PACKAGE, "ls", "files/mlkit-probe").stdout.splitlines()
    for name in files:
        if "/" in name or ".." in name or not name.endswith((".json", ".png", ".f32")):
            continue
        result = adb("exec-out", "run-as", PACKAGE, "cat", f"files/mlkit-probe/{name}", binary=True)
        result.check_returncode()
        (OUT / name).write_bytes(result.stdout)
    (OUT / "logcat.txt").write_text(adb("logcat", "-d", "-v", "threadtime").stdout)
    (OUT / "memory.txt").write_text(adb("shell", "dumpsys", "meminfo", PACKAGE).stdout)
    (OUT / "google-services.txt").write_text(adb("shell", "dumpsys", "package", "com.google.android.gms").stdout)
    screenshot = adb("exec-out", "screencap", "-p", binary=True)
    if screenshot.returncode == 0:
        (OUT / "screen.png").write_bytes(screenshot.stdout)
    report_path = OUT / "report.json"
    if not report_path.exists():
        raise SystemExit("No report: inspect instrumentation and logcat")
    report = json.loads(report_path.read_text())
    print(json.dumps(report, indent=2))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write("## Real ML Kit probe\n\nStatus: **" + report["status"] + "**\n\n")
            summary.write("Output creation is not a semantic-quality pass. No fake model/mask was used.\n")
    if report["status"] != "segmentation_outputs_created":
        raise SystemExit("Environment/API probe failed; no segmentation quality conclusion")


if __name__ == "__main__":
    main()
