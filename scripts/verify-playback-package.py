#!/usr/bin/env python3
"""Check the YCore package boundary without running native or DEX code."""
import argparse
import hashlib
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
YCORE_LIBRARIES = {
    "libavcodec.so", "libavformat.so", "libavutil.so", "libc++_shared.so",
    "libswresample.so", "libswscale.so", "libycore_demux.so", "libycore_gpu.so",
}
SUPPORT_LIBRARIES = {
    "libandroidx.graphics.path.so", "libimage_processing_util_jni.so",
    "libquickjs.so", "libsurface_util_jni.so",
}
SYSTEM_LIBRARIES = {
    "libandroid.so", "libc.so", "libdl.so", "libEGL.so", "libGLESv2.so",
    "libGLESv3.so", "libjnigraphics.so", "liblog.so", "libm.so", "libmediandk.so",
    "libOpenSLES.so", "libvulkan.so", "libz.so", "libnativewindow.so", "libaaudio.so",
}
FORBIDDEN_PACKAGES = (
    "com.google.android.exoplayer2.", "androidx.media3.exoplayer.",
    "dev.jdtech.mpv.", "dev.yfuse.mpv.",
    "com.mediadevkit.sdk.", "org.videolan.libvlc.",
    "com.yfuse.senplayer.", "com.yfuse.feature.player.SenPlayer",
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def elf_metadata(data, name):
    require(len(data) >= 64 and data[:6] == b"\x7fELF\x02\x01", f"{name}: expected ELF64 little-endian")
    require(struct.unpack_from("<H", data, 18)[0] == 183, f"{name}: expected AArch64")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    require(size >= 56 and offset + size * count <= len(data), f"{name}: invalid ELF program headers")
    loads, dynamic = [], None
    for index in range(count):
        kind, _, file_offset, address, _, file_size, _, alignment = struct.unpack_from("<IIQQQQQQ", data, offset + index * size)
        require(file_offset + file_size <= len(data), f"{name}: truncated ELF segment")
        if kind == 1:
            require(alignment >= 16384, f"{name}: PT_LOAD alignment is below 16 KiB")
            loads.append((address, file_offset, file_size))
        if kind == 2:
            dynamic = (file_offset, file_size)
    require(loads and dynamic, f"{name}: missing load/dynamic segments")
    needed, strings, string_size = [], None, None
    for entry in range(dynamic[0], dynamic[0] + dynamic[1], 16):
        tag, value = struct.unpack_from("<qQ", data, entry)
        if tag == 0:
            break
        if tag == 1:
            needed.append(value)
        elif tag == 5:
            strings = value
        elif tag == 10:
            string_size = value
    require(strings is not None and string_size is not None, f"{name}: missing dynamic string table")
    string_offset = next((file_offset + strings - address for address, file_offset, file_size in loads
                          if address <= strings and strings + string_size <= address + file_size), None)
    require(string_offset is not None, f"{name}: invalid dynamic string table")
    table = data[string_offset:string_offset + string_size]
    result = []
    for entry in needed:
        require(entry < len(table) and b"\0" in table[entry:], f"{name}: invalid DT_NEEDED")
        result.append(table[entry:table.index(b"\0", entry)].decode("ascii"))
    return result


def dex_defined_classes(data):
    require(len(data) >= 112 and data[:4] == b"dex\n", "Invalid application DEX header")
    require(struct.unpack_from("<I", data, 32)[0] == len(data), "Application DEX file size mismatch")
    require(struct.unpack_from("<I", data, 40)[0] == 0x12345678, "Unsupported DEX endianness")
    strings_count, strings_offset = struct.unpack_from("<II", data, 56)
    types_count, types_offset = struct.unpack_from("<II", data, 64)
    class_count, class_offset = struct.unpack_from("<II", data, 96)
    for count, offset, width in [(strings_count, strings_offset, 4), (types_count, types_offset, 4), (class_count, class_offset, 32)]:
        require(offset + count * width <= len(data), "Truncated application DEX table")
    for index in range(class_count):
        type_index = struct.unpack_from("<I", data, class_offset + index * 32)[0]
        require(type_index < types_count, "Invalid DEX class type index")
        string_index = struct.unpack_from("<I", data, types_offset + type_index * 4)[0]
        require(string_index < strings_count, "Invalid DEX descriptor index")
        cursor = struct.unpack_from("<I", data, strings_offset + string_index * 4)[0]
        for _ in range(5):
            require(cursor < len(data), "Truncated DEX string length")
            value = data[cursor]
            cursor += 1
            if value < 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = data.find(b"\0", cursor)
        require(end >= cursor, "Unterminated DEX descriptor")
        descriptor = data[cursor:end].decode("utf-8")
        require(descriptor.startswith("L") and descriptor.endswith(";"), "Invalid defined class descriptor")
        yield descriptor[1:-1].replace("/", ".")


def read_mapping(path):
    if path is None:
        return {}
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([^ #\s]+) -> ([^ :\s]+):", line)
        if match:
            # R8 may merge several original classes into one output class. Check every origin.
            result.setdefault(match[2], set()).add(match[1])
    require(result, "Mapping file has no class entries")
    return result


def verify_classes(data, mapping):
    count = 0
    for name in dex_defined_classes(data):
        count += 1
        origins = mapping.get(name, {name}) | {name}
        require(not any(origin.startswith(FORBIDDEN_PACKAGES) for origin in origins),
                f"Application defines a removed playback runtime: {name}")
    return count


def verify(apk, ycore, mapping_path=None):
    mapping = read_mapping(mapping_path)
    expected_ycore_hash = ycore.with_suffix(".aar.sha256").read_text(encoding="utf-8").split()[0]
    require(sha256(ycore.read_bytes()) == expected_ycore_hash, "YCore input SHA-256 mismatch")
    with zipfile.ZipFile(ycore) as carrier, zipfile.ZipFile(apk) as archive:
        entries = archive.namelist()
        require(len(entries) == len(set(entries)), "APK contains duplicate ZIP entries")
        require(not any(name.casefold().startswith("assets/senplayer/") for name in entries),
                "APK contains removed SCore assets")
        packaged_native = {name for name in entries if name.startswith("lib/") and name.endswith(".so")}
        require(packaged_native, "APK contains no native runtime")
        for name in packaged_native:
            require(name.startswith("lib/arm64-v8a/") and name.count("/") == 2,
                    f"Unsupported native ABI/path: {name}")
            require(name.rsplit("/", 1)[-1] in YCORE_LIBRARIES | SUPPORT_LIBRARIES,
                    f"Unreviewed or removed native runtime in APK: {name}")
        for name in YCORE_LIBRARIES:
            entry = f"lib/arm64-v8a/{name}"
            require(entry in packaged_native, f"Missing YCore library: {name}")
            require(sha256(archive.read(entry)) == sha256(carrier.read(f"jni/arm64-v8a/{name}")),
                    f"Packaged YCore library differs from verified input: {name}")
        host_names = {name.rsplit("/", 1)[-1] for name in packaged_native}
        for entry in sorted(packaged_native):
            needed = set(elf_metadata(archive.read(entry), entry))
            require(needed <= host_names | SYSTEM_LIBRARIES, f"Unresolved host native dependencies in {entry}: {sorted(needed - host_names - SYSTEM_LIBRARIES)}")
        dex_entries = [name for name in entries if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name)]
        require(dex_entries and "classes.dex" in dex_entries, "APK has no application classes.dex")
        class_count = sum(verify_classes(archive.read(name), mapping) for name in dex_entries)
        require(class_count > 0, "APK defines no application classes")
    return {"profile": "ycore", "hostNativeLibraries": sorted(host_names),
            "applicationClassesChecked": class_count,
            "mappingUsed": mapping_path is not None, "apkSha256": sha256(apk.read_bytes())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--mapping", type=Path)
    parser.add_argument("--ycore", type=Path, default=ROOT / "composeApp/libs/ycore-native.aar")
    args = parser.parse_args()
    try:
        result = verify(args.apk, args.ycore, args.mapping)
    except (ValueError, KeyError, OSError, struct.error, zipfile.BadZipFile) as error:
        print(f"Playback package rejected: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
