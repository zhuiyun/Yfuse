#!/usr/bin/env python3
"""Type-check and unit-test the HarmonyOS Cangjie sources on the host compiler.

This does not build a HAP and cannot: the standalone `cjc` targets the host, while a release needs
the DevEco Cangjie toolchain and its `aarch64-linux-ohos` backend. What it does do is compile and
run every package that does not import `ohos.*`, which is most of the module's logic, so playback
routing, subtitle routing, output decisions, navigation, search, danmaku and downloads stop being
unverified source.

Packages that import `stdx.encoding.json` or `stdx.encoding.url` are reported as blocked rather than failed:
those live in `stdx`, which is a separate install. Any other compiler error is a real failure.
"""

from __future__ import annotations

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

# Files that reach the HarmonyOS platform. They need the DevEco toolchain and are excluded here.
PLATFORM_ONLY = ("ui", "ability_stage.cj", "entry_view.cj", "main_ability.cj")

# Compile order; every package only depends on ones before it.
PACKAGES = [
    "data",
    "design",
    "watch",
    "player",
    "app",
    "network",
    "cast",
    "offline",
    "sync",
    "storage",
    "support",
    "provider",
]

# Each test package and the libraries its assertions link against.
TEST_PACKAGES = {
    "player": ["player"],
    "watch": ["watch"],
    "app": ["app", "data"],
}

BLOCKED = re.compile(r"can not find package '((?:stdx\.)?encoding\.[a-z]+)'")


def find_toolchain() -> Path | None:
    home = os.environ.get("CANGJIE_HOME")
    candidates = [Path(home)] if home else []
    candidates += [
        Path("C:/Program Files (x86)/Cangjie"),
        Path("C:/Program Files/Cangjie"),
        Path.home() / "cangjie",
        Path("/opt/cangjie"),
    ]
    for candidate in candidates:
        exe = candidate / "bin" / ("cjc.exe" if os.name == "nt" else "cjc")
        if exe.is_file():
            return candidate
    return None


def toolchain_env(home: Path) -> dict[str, str]:
    env = dict(os.environ)
    env["CANGJIE_HOME"] = str(home)
    # Macro expansion loads the runtime out of process, so it has to be resolvable.
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

    # http_transport.cj holds a pure request/response contract plus one HarmonyOS-backed
    # implementation. Keeping the contract lets the packages above it be type-checked.
    transport = src / "network" / "http_transport.cj"
    text = transport.read_text(encoding="utf-8").replace("import ohos.net.http.*\n", "")
    marker = "private func platformMethod"
    if marker in text:
        text = text[: text.index(marker)].rstrip() + "\n"
    transport.write_text(text, encoding="utf-8", newline="\n")
    return src


def run(command: list[str], cwd: Path, env: dict[str, str]) -> subprocess.CompletedProcess:
    return subprocess.run(command, cwd=cwd, env=env, capture_output=True, text=True)


def main() -> int:
    home = find_toolchain()
    if home is None:
        print("SKIP Cangjie host verification: no cjc found; set CANGJIE_HOME to enable it.")
        return 0

    cjc = str(home / "bin" / ("cjc.exe" if os.name == "nt" else "cjc"))
    env = toolchain_env(home)
    stdx = os.environ.get("CANGJIE_STDX_PATH", "")
    stdx_args = ["--import-path", stdx] if stdx and Path(stdx).is_dir() else []
    version = run([cjc, "--version"], ROOT, env).stdout.strip().splitlines()
    print(f"Cangjie toolchain: {version[0] if version else 'unknown'}")

    failures: list[str] = []
    blocked: dict[str, str] = {}

    with tempfile.TemporaryDirectory(prefix="yfuse-cangjie-") as temp:
        work = Path(temp)
        src = stage_sources(work)
        out = work / "out"
        out.mkdir()

        for package in PACKAGES:
            result = run(
                [cjc, "--package", package, "--output-type=staticlib",
                 "--import-path", str(out), *stdx_args, "-o", str(out / f"lib{package}.a")],
                src,
                env,
            )
            if result.returncode == 0:
                print(f"PASS compile {package}")
                continue
            output = result.stdout + result.stderr
            match = BLOCKED.search(output)
            if match:
                blocked[package] = match.group(1)
                print(f"BLOCKED compile {package}: needs stdx package '{match.group(1)}'")
                continue
            failures.append(package)
            print(f"FAIL compile {package}")
            for line in output.splitlines()[:12]:
                print(f"     {line}")

        for package, libs in TEST_PACKAGES.items():
            if package in blocked:
                print(f"BLOCKED test {package}: its source package is blocked")
                continue
            binary = work / (f"{package}_test.exe" if os.name == "nt" else f"{package}_test")
            command = [cjc, "--test", "--package", package, "--import-path", str(out), "-L", str(out)]
            command += [f"-l{lib}" for lib in libs]
            command += ["-o", str(binary)]
            build = run(command, TEST_ROOT, env)
            if build.returncode != 0:
                failures.append(f"{package} tests (build)")
                print(f"FAIL build test {package}")
                for line in (build.stdout + build.stderr).splitlines()[:12]:
                    print(f"     {line}")
                continue
            executed = run([str(binary)], TEST_ROOT, env)
            summary = [
                line.strip()
                for line in (executed.stdout + executed.stderr).splitlines()
                if "PASSED:" in line or "TOTAL:" in line
            ]
            if executed.returncode != 0:
                failures.append(f"{package} tests")
                print(f"FAIL test {package}")
                for line in (executed.stdout + executed.stderr).splitlines():
                    if "Expect Failed" in line or "FAILED" in line:
                        print(f"     {line.strip()}")
            else:
                print(f"PASS test {package}: {' '.join(summary)}")

    if blocked:
        names = ", ".join(sorted(blocked))
        print(f"\n{len(blocked)} package(s) blocked on stdx: {names}")
        print("Install the Cangjie extended library to type-check them.")

    if failures:
        print(f"\nFAIL {len(failures)} item(s): {', '.join(failures)}", file=sys.stderr)
        return 1
    print("\nPASS Cangjie host verification")
    return 0


if __name__ == "__main__":
    sys.exit(main())
