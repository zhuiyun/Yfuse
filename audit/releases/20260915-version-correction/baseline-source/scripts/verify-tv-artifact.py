#!/usr/bin/env python3
"""Verify the installable Android TV contract of a Yfuse APK.

This gate intentionally checks the packaged artifact rather than trusting source manifests.  It is
used by CI after manifest merging, shrinking and signing, where an inherited phone permission or a
missing launcher category would otherwise be easy to miss.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


FORBIDDEN_TV_PERMISSIONS = {
    "android.permission.CAMERA",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.SCHEDULE_EXACT_ALARM",
}
REQUIRED_ABIS = {"arm64-v8a", "armeabi-v7a"}
FORBIDDEN_PLAYER_LIBRARIES = {"libmpv.so", "libplayer.so", "libmdk.so"}
BADGING_NAMED_RECORD = re.compile(
    r"^\s*(?P<kind>[a-z][a-z0-9-]*):\s*(?:name=)?'(?P<name>[^']+)'(?:\s|$)",
    re.MULTILINE,
)


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"TV artifact verification failed: {message}")


def run(*args: str) -> str:
    result = subprocess.run(args, check=False, capture_output=True, text=True)
    if result.returncode:
        fail(f"{' '.join(args)} exited {result.returncode}: {result.stderr.strip()}")
    return result.stdout


def resolve_tool(explicit: str | None, name: str) -> str:
    if explicit:
        candidate = pathlib.Path(explicit)
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
        fail(f"{name} is not executable: {explicit}")
    discovered = shutil.which(name)
    if discovered:
        return discovered
    fail(f"unable to locate {name}; pass --{name.replace('_', '-')}")


def named_badging_records(badging: str) -> dict[str, set[str]]:
    """Parse the named records emitted by ``aapt dump badging``.

    Modern aapt emits records such as ``uses-feature: name='...'``.  Keeping the record kind
    separate is important: a required feature must not be satisfied by the similarly named
    ``uses-feature-not-required`` record.
    """

    records: dict[str, set[str]] = {}
    for match in BADGING_NAMED_RECORD.finditer(badging):
        records.setdefault(match.group("kind"), set()).add(match.group("name"))
    return records


def manifest_permissions(badging: str) -> set[str]:
    records = named_badging_records(badging)
    return {
        permission
        for kind, permissions in records.items()
        if kind == "uses-permission" or kind.startswith("uses-permission-")
        for permission in permissions
    }


def verify_badging_contract(badging: str, package_name: str) -> None:
    package_match = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    if not package_match or package_match.group(1) != package_name:
        fail(f"expected package {package_name!r}")
    if "leanback-launchable-activity:" not in badging:
        fail("CATEGORY_LEANBACK_LAUNCHER is missing")

    records = named_badging_records(badging)
    if "android.software.leanback" not in records.get("uses-feature", set()):
        fail("android.software.leanback is not required")
    if "android.hardware.touchscreen" not in records.get(
        "uses-feature-not-required", set()
    ):
        fail("android.hardware.touchscreen must be declared required=false")

    forbidden = manifest_permissions(badging) & FORBIDDEN_TV_PERMISSIONS
    if forbidden:
        fail("phone-only permissions leaked into TV APK: " + ", ".join(sorted(forbidden)))


def verify_native_code(apk: pathlib.Path, readelf: str) -> None:
    with zipfile.ZipFile(apk) as archive:
        libraries = sorted(
            name
            for name in archive.namelist()
            if re.fullmatch(r"lib/[^/]+/[^/]+\.so", name)
        )
        if not libraries:
            # A Java/MediaCodec-only TV artifact is ABI-neutral and therefore installable on both
            # 32- and 64-bit TVs.  Native-enabled artifacts must provide both variants below.
            return
        forbidden = {
            pathlib.PurePosixPath(name).name
            for name in libraries
        } & FORBIDDEN_PLAYER_LIBRARIES
        if forbidden:
            fail(
                "TV APK contains a forbidden compatibility player: "
                + ", ".join(sorted(forbidden))
            )
        present_abis = {name.split("/")[1] for name in libraries}
        missing = REQUIRED_ABIS - present_abis
        if missing:
            fail(
                "native-enabled TV APK is missing required ABI(s): "
                + ", ".join(sorted(missing))
            )
        with tempfile.TemporaryDirectory(prefix="yfuse-tv-native-") as temp:
            root = pathlib.Path(temp)
            for name in libraries:
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(name))
                program_headers = run(readelf, "-lW", str(target))
                alignments = []
                for line in program_headers.splitlines():
                    if not re.match(r"^\s*LOAD\s", line):
                        continue
                    raw = line.split()[-1]
                    try:
                        alignments.append(int(raw, 16))
                    except ValueError:
                        fail(f"cannot parse PT_LOAD alignment {raw!r} for {name}")
                if not alignments:
                    fail(f"{name} contains no PT_LOAD segments")
                if min(alignments) < 16 * 1024:
                    fail(f"{name} is not 16 KiB page aligned")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("--aapt")
    parser.add_argument("--readelf")
    parser.add_argument("--package", default="com.yfuse")
    args = parser.parse_args()

    if not args.apk.is_file():
        fail(f"APK does not exist: {args.apk}")
    aapt = resolve_tool(args.aapt, "aapt")
    readelf = resolve_tool(args.readelf, "readelf")
    badging = run(aapt, "dump", "badging", str(args.apk))
    xmltree = run(aapt, "dump", "xmltree", str(args.apk), "AndroidManifest.xml")

    verify_badging_contract(badging, args.package)
    if "android:banner" not in xmltree:
        fail("the merged application manifest has no android:banner")

    verify_native_code(args.apk, readelf)
    print(
        "verified Android TV APK: launcher, leanback/touchscreen contract, banner, permissions, "
        "ABI coverage and 16 KiB alignment"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
