#!/usr/bin/env python3
"""Report prerequisites for repository checks without installing or running a build."""

from __future__ import annotations

import argparse
import importlib.metadata
import importlib.util
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROFILES = {
    "scripts": ("python", "bash", "openssl", "jq"),
    "harmony-source": ("python", "pyyaml", "cpp", "nm"),
    "harmony-host": ("python", "cjc", "stdx"),
    "android": ("java", "android-sdk", "node"),
}
TOOLS = {
    "bash": (("bash",), ["--version"], "Install Git for Windows or bash; include its bin directory in PATH."),
    "openssl": (("openssl",), ["version"], "Include Git usr/bin in PATH on Windows, or install OpenSSL."),
    "jq": (("jq",), ["--version"], "Install the official jq executable and add its directory to PATH."),
    "cpp": (("g++", "clang++"), ["--version"], "Use a host C++ toolchain; portable ABI checks currently use ELF/nm on Linux."),
    "nm": (("nm",), ["--version"], "Install host binutils and include nm in PATH."),
    "node": (("node",), ["--version"], "Install Node.js or add the bundled runtime bin directory to PATH."),
}


def version_probe(executable: str, arguments: list[str], hint: str) -> dict:
    try:
        completed = subprocess.run([executable, *arguments], capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError) as error:
        return {"status": "blocked", "reason": str(error), "remedy": hint}
    lines = (completed.stdout + completed.stderr).strip().splitlines()
    if completed.returncode != 0 or not lines:
        return {"status": "blocked", "reason": "Version probe failed", "remedy": hint}
    return {"status": "passed", "executable": executable, "version": lines[0]}


def check(name: str) -> dict:
    if name == "python":
        return {"status": "passed" if sys.version_info >= (3, 10) else "blocked",
                "version": sys.version.split()[0], "executable": sys.executable,
                "remedy": "Use Python 3.10+; CI uses 3.13."}
    if name == "pyyaml":
        try:
            version = importlib.metadata.version("PyYAML")
        except importlib.metadata.PackageNotFoundError:
            version = None
        ready = version == "6.0.3" and importlib.util.find_spec("yaml") is not None
        return {"status": "passed" if ready else "blocked", "version": version,
                "reason": "PyYAML 6.0.3 is required for reproducible parity checks",
                "remedy": "python -m pip install -r scripts/requirements-validation.txt"}
    if name == "cjc":
        spec = importlib.util.spec_from_file_location("cangjie_host_check", ROOT / "scripts/verify-cangjie-host.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        home = module.find_toolchain()
        hint = "Set CANGJIE_HOME to the host compiler installation; this is not the Harmony HAP SDK."
        if home is None:
            return {"status": "blocked", "reason": "No host Cangjie compiler found", "remedy": hint}
        executable = str(home / "bin" / ("cjc.exe" if os.name == "nt" else "cjc"))
        return version_probe(executable, ["--version"], hint)
    if name == "stdx":
        configured = os.environ.get("CANGJIE_STDX_PATH")
        ready = bool(configured and Path(configured).is_dir())
        return {"status": "passed" if ready else "blocked",
                "reason": "Import directory configured; actual packages are checked by cjc" if ready else "No valid CANGJIE_STDX_PATH",
                "remedy": "Install matching host stdx (encoding.json/url) and set CANGJIE_STDX_PATH to its import directory."}
    if name == "android-sdk":
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        ready = bool(sdk and any((Path(sdk) / "platforms" / platform / "android.jar").is_file()
                                 for platform in ("android-37.0", "android-37")))
        return {"status": "passed" if ready else "blocked",
                "reason": "SDK 37 platform found" if ready else "No configured SDK 37 platform",
                "remedy": "Set ANDROID_HOME and install platform android-37; Gradle may alternatively use your local SDK configuration."}
    if name == "java":
        home = os.environ.get("JAVA_HOME")
        candidate = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java") if home else None
        executable = str(candidate) if candidate and candidate.is_file() else shutil.which("java")
        if executable:
            return version_probe(executable, ["-version"], "Configure JAVA_HOME for the project JDK (CI uses 21).")
        return {"status": "blocked", "reason": "No Java executable", "remedy": "Configure JAVA_HOME for JDK 21."}
    candidates, arguments, hint = TOOLS[name]
    executable = next((path for tool in candidates if (path := shutil.which(tool))), None)
    if executable is None:
        return {"status": "blocked", "reason": "Not available in PATH", "remedy": hint}
    return version_probe(executable, arguments, hint)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=PROFILES, nargs="+", default=["scripts"])
    parser.add_argument("--report", type=Path)
    args = parser.parse_args(argv)
    names = list(dict.fromkeys(name for profile in args.profile for name in PROFILES[profile]))
    checks = []
    for name in names:
        item = {"name": name, **check(name)}
        checks.append(item)
        label = "PASS" if item["status"] == "passed" else "BLOCKED"
        print(f"{label} {name}: {item.get('version') or item.get('reason', '')}")
        if item["status"] != "passed":
            print(f"  {item['remedy']}")
    blocked = sum(item["status"] != "passed" for item in checks)
    report = {"schemaVersion": 1, "scope": "validation-prerequisites", "profiles": args.profile,
              "status": "blocked" if blocked else "passed", "checks": checks,
              "exitCode": 2 if blocked else 0,
              "note": "Prerequisite presence does not prove compilation, device behavior or a release."}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report["exitCode"]


if __name__ == "__main__":
    sys.exit(main())