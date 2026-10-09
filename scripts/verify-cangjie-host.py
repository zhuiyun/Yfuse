#!/usr/bin/env python3
"""Compile/test staged host Cangjie sources; never claim HAP or platform validation.

Optional source checks may finish with PARTIAL/SKIPPED and exit 0. Use
--require-complete to reject missing tooling/dependencies with exit 2. Real
compiler/test failures always exit 1. --report writes the actual coverage as JSON.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "harmonyApp/entry/src/main/cangjie/src"
TEST_ROOT = ROOT / "harmonyApp/entry/src/test/cangjie"
PLATFORM_ONLY = ("ui", "ability_stage.cj", "entry_view.cj", "main_ability.cj")
PACKAGES = ["data", "design", "watch", "player", "app", "network", "cast", "offline",
            "sync", "storage", "support", "provider"]
TEST_PACKAGES = {"player": ["player"], "watch": ["watch"], "app": ["app", "data"]}
BLOCKED = re.compile(r"can not find package '((?:stdx\.)?encoding\.[a-z]+)'")


def find_toolchain() -> Path | None:
    home = os.environ.get("CANGJIE_HOME")
    candidates = [Path(home)] if home else []
    executable = shutil.which("cjc")
    if executable:
        candidates.append(Path(executable).resolve().parent.parent)
    candidates += [Path("C:/Program Files (x86)/Cangjie"), Path("C:/Program Files/Cangjie"),
                   Path.home() / "cangjie", Path("/opt/cangjie")]
    for candidate in candidates:
        exe = candidate / "bin" / ("cjc.exe" if os.name == "nt" else "cjc")
        if exe.is_file():
            return candidate
    return None


def toolchain_env(home: Path) -> dict[str, str]:
    env = dict(os.environ)
    env["CANGJIE_HOME"] = str(home)
    runtime = home / "runtime" / "lib" / "windows_x86_64_cjnative"
    parts = [str(runtime), str(home / "bin"), str(home / "tools" / "bin")]
    env["PATH"] = os.pathsep.join(parts + [env.get("PATH", "")])
    return env


def stage_sources(work: Path) -> Path:
    src = work / "src"
    shutil.copytree(SOURCE_ROOT, src)
    for name in PLATFORM_ONLY:
        target = src / name
        if target.is_dir():
            shutil.rmtree(target)
        elif target.is_file():
            target.unlink()
    transport = src / "network" / "http_transport.cj"
    text = transport.read_text(encoding="utf-8").replace("import ohos.net.http.*\n", "")
    marker = "private func platformMethod"
    if marker in text:
        text = text[:text.index(marker)].rstrip() + "\n"
    transport.write_text(text, encoding="utf-8", newline="\n")
    return src


def run(command: list[str], cwd: Path, env: dict[str, str]) -> subprocess.CompletedProcess:
    return subprocess.run(command, cwd=cwd, env=env, capture_output=True, text=True)


def new_report() -> dict:
    return {"schemaVersion": 1, "scope": "cangjie-host", "status": "skipped",
            "platformValidated": False, "excludedPlatformSources": list(PLATFORM_ONLY),
            "plannedPackages": list(PACKAGES), "plannedTestPackages": list(TEST_PACKAGES),
            "packages": [], "tests": []}


def test_counts(output: str) -> dict[str, int]:
    output = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", output)
    counts = {}
    for label, key in (("TOTAL", "total"), ("PASSED", "passed"), ("FAILED", "failed"),
                       ("SKIPPED", "skipped")):
        matches = re.findall(rf"\b{label}:\s*(\d+)", output)
        if matches:
            counts[key] = int(matches[-1])
    return counts


def verify() -> dict:
    report = new_report()
    home = find_toolchain()
    if home is None:
        report["reason"] = "No cjc found; set CANGJIE_HOME to enable host verification."
        return report
    cjc = str(home / "bin" / ("cjc.exe" if os.name == "nt" else "cjc"))
    env = toolchain_env(home)
    stdx = os.environ.get("CANGJIE_STDX_PATH", "")
    stdx_args = ["--import-path", stdx] if stdx and Path(stdx).is_dir() else []
    version = run([cjc, "--version"], ROOT, env)
    if version.returncode != 0:
        report.update(status="failed", reason="Cangjie compiler version probe failed.")
        return report
    report["compilerVersion"] = version.stdout.strip().splitlines()[0] if version.stdout.strip() else "unknown"
    print(f"Cangjie toolchain: {report['compilerVersion']}")

    with tempfile.TemporaryDirectory(prefix="yfuse-cangjie-") as temp:
        work = Path(temp)
        src = stage_sources(work)
        out = work / "out"
        out.mkdir()
        for package in PACKAGES:
            result = run([cjc, "--package", package, "--output-type=staticlib",
                          "--import-path", str(out), *stdx_args, "-o", str(out / f"lib{package}.a")], src, env)
            item = {"package": package, "status": "passed"}
            if result.returncode == 0:
                print(f"PASS compile {package}")
            else:
                output = result.stdout + result.stderr
                match = BLOCKED.search(output)
                if match:
                    item.update(status="blocked", reason=f"Missing stdx package: {match.group(1)}")
                    print(f"BLOCKED compile {package}: {item['reason']}")
                else:
                    item.update(status="failed", reason="Compiler error")
                    print(f"FAIL compile {package}")
                    for line in output.splitlines()[:12]:
                        print(f"     {line}")
            report["packages"].append(item)

        package_status = {item["package"]: item["status"] for item in report["packages"]}
        for package, libs in TEST_PACKAGES.items():
            item = {"package": package, "status": "passed"}
            unavailable = [lib for lib in libs if package_status.get(lib) != "passed"]
            if unavailable:
                item.update(status="blocked", reason="Source libraries unavailable: " + ", ".join(unavailable))
                print(f"BLOCKED test {package}: {item['reason']}")
                report["tests"].append(item)
                continue
            binary = work / (f"{package}_test.exe" if os.name == "nt" else f"{package}_test")
            command = [cjc, "--test", "--package", package, "--import-path", str(out), "-L", str(out)]
            command += [f"-l{lib}" for lib in libs] + ["-o", str(binary)]
            build = run(command, TEST_ROOT, env)
            if build.returncode != 0:
                item.update(status="failed", reason="Test compilation failed")
                print(f"FAIL build test {package}")
                for line in (build.stdout + build.stderr).splitlines()[:12]:
                    print(f"     {line}")
            else:
                executed = run([str(binary)], TEST_ROOT, env)
                output = executed.stdout + executed.stderr
                item["counts"] = test_counts(output)
                if executed.returncode != 0:
                    item.update(status="failed", reason="Test execution failed")
                    print(f"FAIL test {package}")
                    for line in output.splitlines():
                        if "Expect Failed" in line or "FAILED" in line:
                            print(f"     {line.strip()}")
                else:
                    print(f"PASS test {package}: {item['counts']}")
            report["tests"].append(item)

    statuses = [item["status"] for item in report["packages"] + report["tests"]]
    report["status"] = "failed" if "failed" in statuses else "partial" if "blocked" in statuses else "passed"
    return report


def finish(report: dict, *, require_complete: bool, report_path: Path | None) -> int:
    status = report["status"]
    exit_code = 1 if status == "failed" else 2 if require_complete and status != "passed" else 0
    report["requireComplete"] = require_complete
    report["exitCode"] = exit_code
    report["counts"] = {
        kind: {status: sum(item["status"] == status for item in report[kind])
               for status in ("passed", "blocked", "failed")}
        for kind in ("packages", "tests")
    }
    if report_path:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    label = {"passed": "PASS", "failed": "FAIL", "partial": "PARTIAL", "skipped": "SKIP"}[status]
    print(f"\n{label} Cangjie host verification: {report.get('reason', report['counts'])}")
    print("Host results exclude Harmony platform/UI sources and do not validate a HAP.")
    return exit_code


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-complete", action="store_true", help="Exit 2 for skipped/blocked coverage")
    parser.add_argument("--report", type=Path, help="Write structured host coverage as JSON")
    args = parser.parse_args(argv)
    try:
        report = verify()
    except (OSError, subprocess.SubprocessError) as error:
        report = new_report()
        report.update(status="failed", reason=str(error))
    return finish(report, require_complete=args.require_complete, report_path=args.report)


if __name__ == "__main__":
    sys.exit(main())