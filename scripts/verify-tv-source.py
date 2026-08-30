#!/usr/bin/env python3
"""Fast source-level Android TV manifest and banner validation."""

from __future__ import annotations

import argparse
import pathlib
import struct
import sys
import xml.etree.ElementTree as ET


ANDROID = "{http://schemas.android.com/apk/res/android}"
FORBIDDEN_PERMISSIONS = {
    "android.permission.CAMERA",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.SCHEDULE_EXACT_ALARM",
}


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"TV source verification failed: {message}")


def png_size(path: pathlib.Path) -> tuple[int, int]:
    data = path.read_bytes()
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        fail(f"banner must be a PNG with a readable IHDR: {path}")
    color_type = data[25]
    chunk_offset = 8
    has_transparency_chunk = False
    while chunk_offset + 12 <= len(data):
        chunk_length = struct.unpack(">I", data[chunk_offset : chunk_offset + 4])[0]
        chunk_type = data[chunk_offset + 4 : chunk_offset + 8]
        chunk_end = chunk_offset + 12 + chunk_length
        if chunk_end > len(data):
            fail(f"banner contains a truncated PNG chunk: {path}")
        has_transparency_chunk = has_transparency_chunk or chunk_type == b"tRNS"
        chunk_offset = chunk_end
        if chunk_type == b"IEND":
            break
    if color_type in {4, 6} or has_transparency_chunk:
        fail("banner must be opaque; alpha channels and transparent palettes are not allowed")
    return struct.unpack(">II", data[16:24])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=pathlib.Path)
    parser.add_argument("--banner", required=True, type=pathlib.Path)
    args = parser.parse_args()
    if not args.manifest.is_file():
        fail(f"manifest does not exist: {args.manifest}")
    if not args.banner.is_file():
        fail(f"banner does not exist: {args.banner}")

    root = ET.parse(args.manifest).getroot()
    features = {
        node.get(ANDROID + "name"): node.get(ANDROID + "required", "true")
        for node in root.findall("uses-feature")
    }
    if features.get("android.software.leanback") != "true":
        fail("android.software.leanback must be required=true in the TV artifact")
    if features.get("android.hardware.touchscreen") != "false":
        fail("android.hardware.touchscreen must be required=false")

    permissions = {
        node.get(ANDROID + "name") for node in root.findall("uses-permission")
    }
    forbidden = permissions & FORBIDDEN_PERMISSIONS
    if forbidden:
        fail("phone-only TV permissions: " + ", ".join(sorted(forbidden)))

    application = root.find("application")
    if application is None or not application.get(ANDROID + "banner"):
        fail("application android:banner is required")
    has_leanback_launcher = False
    for activity in application.findall("activity") + application.findall("activity-alias"):
        for intent_filter in activity.findall("intent-filter"):
            actions = {
                item.get(ANDROID + "name") for item in intent_filter.findall("action")
            }
            categories = {
                item.get(ANDROID + "name") for item in intent_filter.findall("category")
            }
            if (
                "android.intent.action.MAIN" in actions
                and "android.intent.category.LEANBACK_LAUNCHER" in categories
            ):
                has_leanback_launcher = True
    if not has_leanback_launcher:
        fail("MAIN + LEANBACK_LAUNCHER activity is required")

    width, height = png_size(args.banner)
    if (width, height) != (320, 180):
        fail(f"banner must be exactly 320x180 px, got {width}x{height}")
    print("verified Android TV source manifest and 320x180 banner")
    return 0


if __name__ == "__main__":
    sys.exit(main())
