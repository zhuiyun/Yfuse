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
import shutil
import struct
import subprocess
import time
import traceback
import xml.etree.ElementTree as ET
import zipfile

PACKAGE = "com.yfuse"
APK_SHA256 = "6e2f03664ace292d64c02124cdd943970cd82fed0e3c8974851b5d9c2c1eb557"
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
    def __init__(self, output):
        self.output = output
        self.serial = ""
        self.cases = []
        self.summary = {"scope": "unauthenticated UI smoke, not full acceptance",
                        "baseline": "Yfuse 1.0.88 (250)", "cases": self.cases,
                        "source_run": 36309784954, "performance_valid": False,
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
            raise
        row["elapsed_seconds_observation"] = round(time.monotonic() - began, 2)
        self.cases.append(row)
        self.save()
        print(json.dumps(row, ensure_ascii=False), flush=True)
        return detail

    def verify_apk(self, directory):
        apks = list(directory.rglob("*.apk"))
        matching = [p for p in apks if hashlib.sha256(p.read_bytes()).hexdigest() == APK_SHA256]
        if len(matching) != 1:
            raise EnvironmentBlocked("Exactly one APK matching the pinned SHA-256 is required")
        self.apk = matching[0]
        signature = command([sdk_tool("apksigner"), "verify", "--verbose", "--print-certs", str(self.apk)])
        (self.output / "apk-signature.txt").write_text(signature)
        if f"certificate SHA-256 digest: {CERT_SHA256}" not in signature:
            raise EnvironmentBlocked("Production signing certificate differs from the expected baseline")
        badging = command([sdk_tool("aapt"), "dump", "badging", str(self.apk)])
        (self.output / "apk-badging.txt").write_text(badging)
        if not re.search(r"package: name='com.yfuse' versionCode='250' versionName='1.0.88'", badging):
            raise EnvironmentBlocked("APK identity/version mismatch")
        with zipfile.ZipFile(self.apk) as archive:
            abis = sorted({p.split("/")[1] for p in archive.namelist() if p.startswith("lib/") and p.endswith(".so")})
        return {"sha256": APK_SHA256, "certificate": CERT_SHA256, "abis": abis}

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
        if "arm64-v8a" not in result["ro.product.cpu.abilist"].split(","):
            raise EnvironmentBlocked("System image does not advertise ARM64 ABI translation")
        self.original = {key: self.adb("shell", "settings", "get", "system", key) for key in
                         ["font_scale", "accelerometer_rotation", "user_rotation"]}
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
        self.adb("shell", "rm", "-f", path)
        self.adb("shell", "uiautomator", "dump", "--compressed", path, timeout=30)
        value = self.adb("shell", "cat", path)
        return ET.fromstring(value), value

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
        root, value = self.tree()
        if not any(node.get("package") == PACKAGE for node in root.iter("node")):
            raise RuntimeError("Yfuse is not represented in the foreground UI hierarchy")
        windows = self.adb("shell", "dumpsys", "window", "windows")
        focus = [line.strip() for line in windows.splitlines() if "mCurrentFocus=" in line]
        if not any(PACKAGE in line for line in focus):
            raise RuntimeError("Yfuse is not the focused application: " + str(focus))
        (self.output / f"{name}.xml").write_text(value)
        png = self.adb("exec-out", "screencap", "-p", binary=True)
        if png[:8] != b"\x89PNG\r\n\x1a\n":
            raise RuntimeError("Invalid screenshot output")
        (self.output / f"{name}.png").write_bytes(png)
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

    def diagnostics(self):
        if not self.serial:
            return
        for name, args in [("logcat.txt", ["logcat", "-d", "-v", "threadtime"]),
                           ("crash-buffer.txt", ["logcat", "-d", "-b", "crash"]),
                           ("meminfo.txt", ["shell", "dumpsys", "meminfo", PACKAGE]),
                           ("exit-info.txt", ["shell", "dumpsys", "activity", "exit-info", PACKAGE])]:
            try:
                (self.output / name).write_text(self.adb(*args, check=False))
            except Exception as error:
                (self.output / (name + ".error")).write_text(str(error))

    def restore(self):
        if not hasattr(self, "original"):
            return
        for key, value in self.original.items():
            operation = ["delete", "system", key] if value == "null" else ["put", "system", key, value]
            self.adb("shell", "settings", *operation, check=False)
        for args in [("wm", "size", "reset"), ("wm", "density", "reset"), ("cmd", "uimode", "night", "auto")]:
            self.adb("shell", *args, check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-directory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--soak-seconds", type=int, default=120)
    args = parser.parse_args()
    if not 0 <= args.soak_seconds <= 600:
        parser.error("soak-seconds must be between 0 and 600")
    args.output.mkdir(parents=True, exist_ok=True)
    session = Session(args.output)
    code = 0
    try:
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
    except Exception as error:
        session.summary["result"] = "environment_blocked" if isinstance(error, EnvironmentBlocked) else "failed_requires_triage"
        session.summary["error"] = str(error)
        (args.output / "failure.txt").write_text(traceback.format_exc())
        code = 2 if isinstance(error, EnvironmentBlocked) else 1
    finally:
        session.diagnostics()
        try:
            session.restore()
        except Exception as error:
            session.summary["restore_error"] = str(error)
        session.save()
    return code


if __name__ == "__main__":
    raise SystemExit(main())
