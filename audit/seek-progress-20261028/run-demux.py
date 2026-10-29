"""Run real read-ahead queues and existing tests with explicit Android/JNI type fixtures.

This compiles whole production files. No queue/gate method is extracted or replaced.
It is a host-side concurrency/behavior test, not an Android build or device test.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("host_runner", ROOT / "run-host.py")
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)

PRODUCTION = host.PRODUCTION + [host.COMMON / name for name in [
    "demux/YDemuxer.kt", "capability/YCapability.kt", "bitstream/YBitstream.kt",
    "dolby/YDolbyVisionConfig.kt", "hdr/YHdrMetadata.kt", "subtitle/YSubtitle.kt",
    "subtitle/YSubtitleCueBuffer.kt", "api/YPlaybackException.kt", "recovery/YPlaybackRecoveryPolicy.kt",
]] + [host.ANDROID / (name + ".kt") for name in [
    "AndroidDemuxReadAheadNode", "AndroidDemuxInputQueue", "AndroidDemuxReadControl", "AndroidBufferWaitMonitor",
    "AndroidMediaExtractorReadAheadNode", "AndroidPreparedExtractorSlot",
]]
TESTS = ["AndroidDemuxReadAheadNodeTest", "AndroidDemuxReadAheadBufferTest",
         "AndroidDemuxInputQueueTest", "AndroidMediaExtractorReadAheadNodeTest"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--test", action="append")
    args = parser.parse_args()
    suffix = ""
    jars = sorted((ROOT / "deps").glob("*.jar"))
    classpath = os.pathsep.join(map(str, jars))
    sources = PRODUCTION + [host.TEST / (name + ".kt") for name in TESTS]
    sources += list((ROOT / "fixtures").glob("*.kt")) + list((ROOT / "fixtures-demux").glob("*.kt"))
    records = [{"path": str(p.relative_to(host.REPO)), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources]
    (ROOT / ("demux-sources" + suffix + ".json")).write_text(json.dumps(records, indent=2) + "\n")
    classes = ROOT / ("classes-demux" + suffix)
    command = ["java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
               "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
               "-d", str(classes), *map(str, sources)]
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (ROOT / ("demux-compile" + suffix + ".log")).write_text(result.stdout)
    print(result.stdout, end="", flush=True)
    if result.returncode:
        return result.returncode
    chosen = args.test or TESTS
    started = time.monotonic()
    result = subprocess.run(["java", "-Xmx1024m", "-cp", str(classes) + os.pathsep + classpath,
                             "org.junit.runner.JUnitCore", *["com.yfuse.core2.android." + name for name in chosen]],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
    (ROOT / ("demux-tests" + suffix + ".log")).write_text(result.stdout)
    print(result.stdout, end="", flush=True)
    (ROOT / ("demux-result" + suffix + ".json")).write_text(json.dumps({
        "scope": "whole production read-ahead queues with explicit Android/JNI type fixtures; no device claim",
        "tests": chosen, "exit_code": result.returncode,
        "elapsed_seconds": time.monotonic() - started,
    }, indent=2) + "\n")
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
