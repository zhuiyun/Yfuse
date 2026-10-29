"""Compile whole production files and run transport JVM tests with explicit platform fixtures.

This is an isolated host check, not an Android build or a device-playback claim.
No production method is extracted or rewritten before compilation.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent
COMMON = REPO / "composeApp/src/commonMain/kotlin/com/yfuse/core2"
ANDROID = REPO / "composeApp/src/androidMain/kotlin/com/yfuse/core2/android"
TEST = REPO / "composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android"

PRODUCTION = list((COMMON / "network").glob("*.kt")) + [ANDROID / (name + ".kt") for name in [
    "AndroidTransportMediaDataSource", "AndroidYCoreBlockCache", "AndroidForwardCacheWarmer",
    "YProgressiveTransportBlock", "YTransportReadHeads", "AndroidTransportRequestFactory",
    "AndroidProxyMediaSessions", "AndroidPlaybackMemoryBudget", "AndroidRangeReadWatchdog",
    "AndroidRangeReadDiagnostics", "AndroidProbeBudget", "AndroidBoundedProbe",
]]
TESTS = ["AndroidTransportCancellationTest", "AndroidTransportStartupAndResumeTest"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--test", action="append", help="Test class name to run; default all compiled classes")
    parser.add_argument("--skip-compile", action="store_true")
    parser.add_argument("--label", default="host", help="Unique output name to preserve baseline/fixed evidence")
    options = parser.parse_args()
    jars = sorted((ROOT / "deps").glob("*.jar"))
    if not jars:
        raise SystemExit("Run bootstrap-deps.py first")
    classpath = os.pathsep.join(map(str, jars))
    classes = ROOT / "classes-host"
    compiled_tests = options.test or [name for name in TESTS if (TEST / (name + ".kt")).exists()]
    sources = PRODUCTION + [TEST / (name + ".kt") for name in compiled_tests] + list((ROOT / "fixtures").glob("*.kt"))
    records = [{"path": str(p.relative_to(REPO)), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources]
    compiled_manifest = classes / "source-manifest.json"
    if options.skip_compile:
        if not compiled_manifest.exists():
            raise SystemExit("No verified compilation manifest; rerun without --skip-compile")
        compiled = {r["path"]: r["sha256"] for r in json.loads(compiled_manifest.read_text())}
        if any(compiled.get(r["path"]) != r["sha256"] for r in records):
            raise SystemExit("Source changed since compilation; rerun without --skip-compile")
    if not options.skip_compile:
        shutil.rmtree(classes, ignore_errors=True)
        command = ["java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                   "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
                   "-d", str(classes), *map(str, sources)]
        compile_result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (ROOT / f"{options.label}-compile.log").write_text(compile_result.stdout)
        print(compile_result.stdout, end="", flush=True)
        if compile_result.returncode:
            return compile_result.returncode
        compiled_manifest.write_text(json.dumps(records, indent=2) + "\n")
    (ROOT / f"{options.label}-sources.json").write_text(json.dumps(records, indent=2) + "\n")
    chosen = compiled_tests
    command = ["java", "-Xmx1024m", "-cp", str(classes) + os.pathsep + classpath,
               "org.junit.runner.JUnitCore", *["com.yfuse.core2.android." + name for name in chosen]]
    started = time.monotonic()
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    print(result.stdout, end="", flush=True)
    (ROOT / f"{options.label}-tests.log").write_text(result.stdout)
    (ROOT / f"{options.label}-result.json").write_text(json.dumps({
        "scope": "whole production JVM files with platform fixtures; not Android/device validation",
        "tests": chosen, "exit_code": result.returncode, "elapsed_seconds": time.monotonic() - started,
        "java": subprocess.run(["java", "-version"], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout,
    }, indent=2) + "\n")
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
