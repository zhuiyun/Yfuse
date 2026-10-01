"""Evidence-first checks of a pinned, unmodified APK on a disposable emulator.

No account credentials, server tokens, cloud restore, production build or publishing.
Passing captures establish page reachability only; screenshots need visual review.
Emulator timing/memory observations are not physical-device performance benchmarks.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import time
import traceback
import xml.etree.ElementTree as ET
import zipfile

PACKAGE = "com.yfuse"
CERT_SHA256 = "373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84"


class EnvironmentBlocked(RuntimeError):
    pass


def command(args, *, timeout=60, check=True, binary=False):
    result = subprocess.run(args, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {args[0]}: " +
                           (result.stdout + result.stderr).decode(errors="replace")[-3000:])
    return result.stdout if binary else result.stdout.decode(errors="replace").strip()


def sdk_tool(name):
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
    candidates = sorted((sdk / "build-tools").glob(f"*/{name}"), reverse=True)
    if not candidates:
        raise EnvironmentBlocked(f"Android SDK tool unavailable: {name}")
    return str(candidates[0])


def nodes_for(root, label):
    return [node for node in root.iter("node") if node.get("enabled") != "false" and
            label in (node.get("text"), node.get("content-desc"))]


def center(node):
    values = [int(value) for value in re.findall(r"-?\d+", node.get("bounds", ""))]
    if len(values) != 4 or values[2] <= values[0] or values[3] <= values[1]:
        raise RuntimeError("UI target has no usable bounds")
    return ((values[0] + values[2]) // 2, (values[1] + values[3]) // 2)


class Session:
    def __init__(self, output, expected, source_run, expected_api=None):
        self.expected = expected
        self.expected_api = expected_api
        self.output = output
        self.serial = ""
        self.cases = []
        self.summary = {"scope": "unauthenticated UI smoke, not full acceptance",
                        "baseline": f"Yfuse {expected['versionName']} ({expected['versionCode']})", "cases": self.cases,
                        "source_run": source_run, "performance_valid": False,
                        "not_tested": ["account login/restore", "real media library",
                                       "playback/seek/subtitles", "downloads/order/live updates",
                                       "physical-device performance", "long-duration stability"]}

    def adb(self, *args, **kwargs):
        return command(["adb", "-s", self.serial, *args], **kwargs)

    def save(self):
        (self.output / "summary.json").write_text(json.dumps(self.summary, ensure_ascii=False, indent=2))

    def case(self, name, operation):
        began = time.monotonic()
        try:
            detail = operation()
            row = {"name": name, "status": "passed", "detail": detail}
        except Exception as error:
            row = {"name": name, "status": "blocked" if isinstance(error, EnvironmentBlocked) else "failed",
                   "detail": str(error)}
            self.cases.append(row)
            self.save()
            print(json.dumps(row, ensure_ascii=False), flush=True)
            raise
        row["elapsed_seconds_observation"] = round(time.monotonic() - began, 2)
        self.cases.append(row)
        self.save()
        print(json.dumps(row, ensure_ascii=False), flush=True)
        return detail

    def verify_apk(self, directory):
        apks = list(directory.rglob("*.apk"))
        matching = [p for p in apks if hashlib.sha256(p.read_bytes()).hexdigest() == self.expected["sha256"]]
        if len(matching) != 1:
            raise EnvironmentBlocked("Exactly one APK matching the pinned SHA-256 is required")
        self.apk = matching[0]
        signature = command([sdk_tool("apksigner"), "verify", "--verbose", "--print-certs", str(self.apk)])
        (self.output / "apk-signature.txt").write_text(signature)
        certificates = set(re.findall(r"certificate SHA-256 digest: ([a-fA-F0-9]{64})", signature))
        if {digest.lower() for digest in certificates} != {CERT_SHA256}:
            raise EnvironmentBlocked("Production signing certificate differs from the expected baseline")
        badging = command([sdk_tool("aapt"), "dump", "badging", str(self.apk)])
        (self.output / "apk-badging.txt").write_text(badging)
        package_line = next((line for line in badging.splitlines() if line.startswith("package:")), "")
        fields = dict(re.findall(r"(\w+)='([^']*)'", package_line))
        if (fields.get("name") != PACKAGE or fields.get("versionCode") != str(self.expected["versionCode"])
                or fields.get("versionName") != self.expected["versionName"]):
            raise EnvironmentBlocked("APK identity/version mismatch")
        with zipfile.ZipFile(self.apk) as archive:
            abis = sorted({p.split("/")[1] for p in archive.namelist() if p.startswith("lib/") and p.endswith(".so")})
        return {"sha256": self.expected["sha256"], "certificate": CERT_SHA256, "abis": abis}

    def connect(self):
        devices = command(["adb", "devices"]).splitlines()[1:]
        available = [line.split()[0] for line in devices if line.strip().endswith("\tdevice")]
        if len(available) != 1 or not available[0].startswith("emulator-"):
            raise EnvironmentBlocked("Requires exactly one disposable Android emulator; refuses physical devices")
        self.serial = available[0]
        if self.adb("shell", "getprop", "ro.kernel.qemu") != "1":
            raise EnvironmentBlocked("Target is not a QEMU emulator")
        if self.adb("shell", "getprop", "sys.boot_completed") != "1":
            raise EnvironmentBlocked("Android boot incomplete")
        properties = self.adb("shell", "getprop")
        (self.output / "device-properties.txt").write_text(properties)
        result = {key: self.adb("shell", "getprop", key) for key in
                  ["ro.build.version.sdk", "ro.product.cpu.abilist", "ro.dalvik.vm.native.bridge"]}
        # A release gate names each API level it covers; a mislabelled system image must not
        # stand in for one (Android 17 images carry a minor version, android-37.0).
        if self.expected_api is not None and result["ro.build.version.sdk"] != str(self.expected_api):
            raise EnvironmentBlocked(f"Emulator reports API {result['ro.build.version.sdk'] or 'unknown'}; "
                                     f"this check requires API {self.expected_api}")
        if "arm64-v8a" not in result["ro.product.cpu.abilist"].split(","):
            raise EnvironmentBlocked("System image does not advertise ARM64 ABI translation")
        self.original = {key: self.adb("shell", "settings", "get", "system", key) for key in
                         ["font_scale", "accelerometer_rotation", "user_rotation"]}
        night = re.search(r"Night mode: (\w+)", self.adb("shell", "cmd", "uimode", "night", check=False))
        self.original_night = night.group(1) if night and night.group(1) in ("yes", "no", "auto") else "auto"
        return result

    def install(self):
        installed = self.adb("shell", "pm", "list", "packages", PACKAGE)
        if f"package:{PACKAGE}" in installed.splitlines():
            raise EnvironmentBlocked("Requires a clean emulator; refuses overwriting an existing installation")
        result = self.adb("install", "--no-streaming", str(self.apk), timeout=180)
        if "Success" not in result:
            raise EnvironmentBlocked("APK install did not report success: " + result)
        self.adb("logcat", "-c")
        self.adb("shell", "input", "keyevent", "82")
        return result

    def tree(self):
        path = "/sdcard/yfuse-cloud-window.xml"
        errors = []
        for attempt in range(5):
            self.adb("shell", "rm", "-f", path)
            try:
                result = self.adb("shell", "uiautomator", "dump", "--compressed", path, timeout=30)
                # uiautomator may exit zero after an idle/root failure and write no file.
                value = self.adb("shell", "cat", path)
                return ET.fromstring(value), value
            except Exception as error:
                errors.append(f"attempt {attempt + 1}: {error}")
                time.sleep(2)
        (self.output / "hierarchy-capture-errors.txt").write_text("\n".join(errors))
        raise EnvironmentBlocked("UI hierarchy capture failed after five attempts; see screenshots/logs")

    def wait_label(self, label, timeout=45):
        end = time.monotonic() + timeout
        last = ""
        while time.monotonic() < end:
            try:
                root, _ = self.tree()
                targets = nodes_for(root, label)
                if targets:
                    return targets
                # Notification prompts may appear on an otherwise clean API 33+ install.
                for node in root.iter("node"):
                    if node.get("resource-id", "").endswith(":id/permission_allow_button"):
                        self.adb("shell", "input", "tap", *map(str, center(node)))
                        break
            except Exception as error:
                last = str(error)
            time.sleep(1)
        raise RuntimeError(f"UI label not found: {label}; {last}")

    def tap(self, label):
        # Prefer the bottom tab if another heading has the same label.
        targets = self.wait_label(label)
        point = max((center(node) for node in targets), key=lambda p: p[1])
        self.adb("shell", "input", "tap", *map(str, point))
        time.sleep(2)

    def capture(self, name):
        png = self.adb("exec-out", "screencap", "-p", binary=True)
        if png[:8] != b"\x89PNG\r\n\x1a\n":
            raise RuntimeError("Invalid screenshot output")
        (self.output / f"{name}.png").write_bytes(png)
        root, value = self.tree()
        if not any(node.get("package") == PACKAGE for node in root.iter("node")):
            raise RuntimeError("Yfuse is not represented in the foreground UI hierarchy")
        windows = self.adb("shell", "dumpsys", "window")
        focus = [line.strip() for line in windows.splitlines() if "mCurrentFocus=" in line]
        if not any(PACKAGE in line for line in focus):
            raise RuntimeError("Yfuse is not the focused application: " + str(focus))
        (self.output / f"{name}.xml").write_text(value)
        width, height = struct.unpack(">II", png[16:24])
        pid = self.adb("shell", "pidof", PACKAGE, check=False)
        if not pid:
            raise RuntimeError("App process is missing")
        return {"screenshot": f"{name}.png", "ui_tree": f"{name}.xml",
                "width": width, "height": height, "pid": pid, "focus": focus}

    def launch(self):
        result = self.adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity", timeout=90)
        (self.output / "launch.txt").write_text(result)
        if "Status: ok" not in result:
            raise RuntimeError("Launch did not report Status: ok")
        self.wait_label("我的", timeout=60)
        return self.capture("01-launch")

    def navigate(self, label, marker, name):
        self.tap(label)
        self.wait_label(marker)
        return self.capture(name)

    def back(self):
        self.adb("shell", "input", "keyevent", "4")
        time.sleep(2)
        self.wait_label("我的")

    def configuration(self, name, *settings):
        for args in settings:
            self.adb("shell", *args)
        time.sleep(3)
        return self.capture(name)

    def offline(self):
        self.adb("shell", "svc", "wifi", "disable")
        self.adb("shell", "svc", "data", "disable")
        time.sleep(5)
        (self.output / "offline-connectivity.txt").write_text(self.adb("shell", "dumpsys", "connectivity"))
        try:
            return self.capture("09-network-disabled-ui")
        finally:
            self.adb("shell", "svc", "wifi", "enable")
            self.adb("shell", "svc", "data", "enable")

    def soak(self, seconds):
        begin = time.monotonic()
        cycles = 0
        first_pid = self.adb("shell", "pidof", PACKAGE)
        while time.monotonic() - begin < seconds:
            self.adb("shell", "input", "keyevent", "3")
            time.sleep(2)
            self.adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
            time.sleep(3)
            pid = self.adb("shell", "pidof", PACKAGE, check=False)
            if pid != first_pid:
                raise RuntimeError(f"Process changed during short foreground/background smoke: {first_pid} -> {pid}")
            cycles += 1
        return {"cycles": cycles, "duration_seconds": round(time.monotonic() - begin, 2),
                "capture": self.capture("10-background-return"),
                "limit": "short unauthenticated smoke; not long-duration or playback stability"}

    def layout_probe(self):
        captures = []
        self.tap("库")
        captures.append(self.capture("layout-library-empty"))
        self.tap("首页")
        for scale in ("1.0", "1.3"):
            self.adb("shell", "settings", "put", "system", "user_rotation", "0")
            self.adb("shell", "settings", "put", "system", "font_scale", scale)
            self.adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
            time.sleep(5)
            self.adb("shell", "settings", "put", "system", "user_rotation", "1")
            began = time.monotonic()
            # Avoid hierarchy dumps between frames: their idle waits alter capture timing.
            for target in (1, 3, 10):
                time.sleep(max(0, target - (time.monotonic() - began)))
                observed = time.monotonic() - began
                name = f"layout-font-{scale}-landscape-{target}s"
                png = self.adb("exec-out", "screencap", "-p", binary=True)
                if png[:8] != b"\x89PNG\r\n\x1a\n":
                    raise RuntimeError("Invalid layout probe screenshot")
                (self.output / f"{name}.png").write_bytes(png)
                width, height = struct.unpack(">II", png[16:24])
                captures.append({"screenshot": f"{name}.png", "width": width, "height": height,
                                 "seconds_after_rotation_command": round(observed, 3)})
            root, value = self.tree()
            (self.output / f"layout-font-{scale}-landscape.xml").write_text(value)
        self.adb("shell", "settings", "put", "system", "font_scale", "1.0")
        self.adb("shell", "wm", "size", "1600x2560")
        self.adb("shell", "wm", "density", "320")
        time.sleep(10)
        captures.append(self.capture("layout-tablet-viewport"))
        return {"captures": captures, "limit": "viewport emulation and sampled frames; not physical tablet or continuous frame timing"}

    def diagnostics(self):
        if not self.serial:
            return
        try:
            (self.output / "final-screen.png").write_bytes(self.adb("exec-out", "screencap", "-p", binary=True))
        except Exception:
            pass
        for name, args in [("logcat.txt", ["logcat", "-d", "-v", "threadtime"]),
                           ("crash-buffer.txt", ["logcat", "-d", "-b", "crash"]),
                           ("meminfo.txt", ["shell", "dumpsys", "meminfo", PACKAGE]),
                           ("exit-info.txt", ["shell", "dumpsys", "activity", "exit-info", PACKAGE])]:
            try:
                (self.output / name).write_text(self.adb(*args, check=False))
            except Exception as error:
                (self.output / (name + ".error")).write_text(str(error))

    def reset_configuration(self, check=True):
        """Puts back the font scale, rotation and night mode the smoke cases changed."""
        for key, value in self.original.items():
            operation = ["delete", "system", key] if value == "null" else ["put", "system", key, value]
            self.adb("shell", "settings", *operation, check=check)
        self.adb("shell", "cmd", "uimode", "night", getattr(self, "original_night", "auto"), check=check)

    def settle_for_layout_probe(self):
        # The probe samples its own rotations and font scales; it must not start from the
        # smoke's landscape, 1.3 font and dark theme.
        self.reset_configuration()
        time.sleep(3)
        self.wait_label("我的")
        return self.capture("layout-start")

    def restore(self):
        if not hasattr(self, "original"):
            return
        self.reset_configuration(check=False)
        for args in [("wm", "size", "reset"), ("wm", "density", "reset")]:
            self.adb("shell", *args, check=False)


def tail(path, lines):
    try:
        text = path.read_text(errors="replace")
    except OSError:
        return None
    return "\n".join(text.splitlines()[-lines:])


def print_failure(summary, trace):
    """Puts the cause in the job log itself; the evidence artifact is not always reachable."""
    failed = [case for case in summary["cases"] if case.get("status") != "passed"]
    print("==== Cloud UI failure ====", flush=True)
    print(json.dumps({"result": summary.get("result"), "error": summary.get("error"),
                      "failed_case": failed[-1] if failed else None}, ensure_ascii=False, indent=2))
    print(trace, flush=True)


def print_failure_logs(output):
    """The device's own account of the failure, once diagnostics() has pulled it."""
    hierarchy = tail(output / "hierarchy-capture-errors.txt", 20)
    if hierarchy is not None:
        print("==== hierarchy-capture-errors.txt ====", flush=True)
        print(hierarchy, flush=True)
    for name, lines in (("crash-buffer.txt", 80), ("logcat.txt", 150)):
        text = tail(output / name, lines)
        if text is None:
            error = tail(output / (name + ".error"), 5)
            print(f"==== {name}: not captured" + (f" ({error})" if error else "") + " ====", flush=True)
        else:
            print(f"==== {name} (last {lines} lines) ====", flush=True)
            print(text, flush=True)


def run_cases(session, args):
    session.case("APK identity, hash and signature", lambda: session.verify_apk(args.apk_directory))
    session.case("Booted emulator and ARM64 translation", session.connect)
    session.case("Clean install", session.install)
    session.case("App launch and foreground hierarchy", session.launch)
    session.case("Profile page reachable", lambda: session.navigate("我的", "账号与同步", "02-profile"))
    session.case("Account signed-out page reachable", lambda: session.navigate("账号与同步", "登录账号", "03-account-signed-out"))
    session.back()
    session.case("Servers tab reachable", lambda: session.navigate("服务器", "服务器", "04-servers"))
    session.case("Home tab reachable", lambda: session.navigate("首页", "首页", "05-home"))
    session.case("Large-font foreground capture", lambda: session.configuration("06-font-130",
                 ("settings", "put", "system", "font_scale", "1.3")))
    session.case("Landscape foreground capture", lambda: session.configuration("07-landscape",
                 ("settings", "put", "system", "accelerometer_rotation", "0"),
                 ("settings", "put", "system", "user_rotation", "1")))
    session.case("Dark-theme foreground capture", lambda: session.configuration("08-dark",
                 ("cmd", "uimode", "night", "yes")))
    session.case("UI survives disabling Wi-Fi and mobile data", session.offline)
    session.case("Short foreground/background stability", lambda: session.soak(args.soak_seconds))
    session.summary["result"] = "smoke_completed_visual_review_required"
    if args.layout_probe:
        # The probe follows the smoke in the same session, so the navigation cases run on every
        # workflow run instead of being skipped whenever the probe is asked for.
        session.case("Display settings restored after the smoke", session.settle_for_layout_probe)
        session.case("Targeted rotation, font and tablet viewport evidence", session.layout_probe)
        session.summary["result"] = "smoke_and_layout_probe_completed_visual_review_required"


def expected_release(parser, args):
    """Identity the APK under test must have.

    A release gate runs before any update manifest exists and may package a manually requested
    version, so it passes the signed APK's SHA-256 and version explicitly. Otherwise the
    checked-out release metadata and the package run's update.json supply them.
    """
    pinned = (args.expected_sha256, args.expected_version_code, args.expected_version_name)
    if any(pinned):
        if not all(pinned):
            parser.error("--expected-sha256, --expected-version-code and --expected-version-name go together")
        if not re.fullmatch(r"[a-f0-9]{64}", args.expected_sha256):
            parser.error("--expected-sha256 must be 64 lowercase hexadecimal digits")
        if not re.fullmatch(r"[1-9][0-9]*", args.expected_version_code):
            parser.error("--expected-version-code must be a positive integer")
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", args.expected_version_name):
            parser.error("--expected-version-name must use numeric major.minor.patch format")
        return {"versionCode": int(args.expected_version_code), "versionName": args.expected_version_name,
                "sha256": args.expected_sha256}
    from release_metadata import read_release
    expected = read_release(Path(__file__).resolve().parents[1])
    manifests = list(args.apk_directory.rglob("update.json"))
    if len(manifests) != 1:
        parser.error("Exactly one signed-build update manifest is required")
    manifest = json.loads(manifests[0].read_text())
    if any(manifest.get(key) != expected[key] for key in ("versionCode", "versionName")):
        parser.error("Artifact version must match the checked-out source")
    if not re.fullmatch(r"[a-f0-9]{64}", manifest.get("sha256", "")):
        parser.error("The update manifest must supply the APK SHA-256")
    expected["sha256"] = manifest["sha256"]
    return expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-directory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--soak-seconds", type=int, default=120)
    parser.add_argument("--layout-probe", action="store_true",
                        help="after the smoke cases, sample rotation, font scale and a tablet viewport")
    parser.add_argument("--source-run", type=int, required=True)
    # An empty value means "not given", so a workflow can pass these on every run.
    parser.add_argument("--expected-sha256", default="", help="SHA-256 of the signed APK under release")
    parser.add_argument("--expected-version-code", default="", help="versionCode that APK must declare")
    parser.add_argument("--expected-version-name", default="", help="versionName that APK must declare")
    parser.add_argument("--expected-api", type=int, help="API level the booted emulator must report")
    args = parser.parse_args()
    if not 0 <= args.soak_seconds <= 600:
        parser.error("soak-seconds must be between 0 and 600")
    args.output.mkdir(parents=True, exist_ok=True)
    expected = expected_release(parser, args)
    session = Session(args.output, expected, args.source_run, args.expected_api)
    code = 0
    try:
        run_cases(session, args)
    except Exception as error:
        session.summary["result"] = "environment_blocked" if isinstance(error, EnvironmentBlocked) else "failed_requires_triage"
        session.summary["error"] = str(error)
        trace = traceback.format_exc()
        (args.output / "failure.txt").write_text(trace)
        # Before diagnostics: pulling logs from an emulator that has gone away can take minutes.
        print_failure(session.summary, trace)
        code = 2 if isinstance(error, EnvironmentBlocked) else 1
    finally:
        session.diagnostics()
        try:
            session.restore()
        except Exception as error:
            session.summary["restore_error"] = str(error)
        session.save()
    if code:
        print_failure_logs(args.output)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
