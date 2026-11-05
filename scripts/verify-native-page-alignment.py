#!/usr/bin/env python3
"""Inspect ELF LOAD segments and APK ZIP data offsets for 16 KiB page compatibility.

Static packaging evidence only: this does not claim a library runs on a 16 KiB device.
"""
import argparse
import json
from pathlib import Path
import struct
import zipfile

PAGE = 16384


def inspect_elf(data):
    if len(data) < 6 or data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError("not a supported ELF")
    endian = "<" if data[5] == 1 else ">"
    wide = data[4] == 2
    phoff = struct.unpack_from(endian + ("Q" if wide else "I"), data, 32 if wide else 28)[0]
    entsize, count = struct.unpack_from(endian + "HH", data, 54 if wide else 42)
    if count > 4096 or entsize < (56 if wide else 32):
        raise ValueError("invalid ELF program header table")
    segments = []
    for i in range(count):
        off = phoff + i * entsize
        fields = struct.unpack_from(endian + ("IIQQQQQQ" if wide else "IIIIIIII"), data, off)
        if fields[0] != 1:
            continue
        offset, address, alignment = (fields[2], fields[3], fields[7]) if wide else (fields[1], fields[2], fields[7])
        segments.append({"offset": offset, "virtual_address": address, "alignment": alignment,
                         "aligned_16k": alignment >= PAGE and alignment & (alignment - 1) == 0
                         and offset % PAGE == address % PAGE})
    if not segments:
        raise ValueError("ELF has no LOAD segment")
    return {"elf_class": 64 if wide else 32, "load_segments": segments,
            "elf_16k_pass": all(segment["aligned_16k"] for segment in segments)}


def inspect_apk(path):
    records = []
    with zipfile.ZipFile(path) as archive, path.open("rb") as raw:
        for item in archive.infolist():
            if not item.filename.startswith("lib/") or not item.filename.endswith(".so"):
                continue
            record = {"path": item.filename}
            try:
                record.update(inspect_elf(archive.read(item)))
                raw.seek(item.header_offset)
                header = raw.read(30)
                if header[:4] != b"PK\x03\x04":
                    raise ValueError("invalid ZIP local header")
                name_size, extra_size = struct.unpack_from("<HH", header, 26)
                data_offset = item.header_offset + 30 + name_size + extra_size
                record.update(zip_data_offset=data_offset, compressed=item.compress_type != zipfile.ZIP_STORED,
                              zip_16k_pass=item.compress_type != zipfile.ZIP_STORED or data_offset % PAGE == 0)
                record["pass"] = record["elf_16k_pass"] and record["zip_16k_pass"]
            except (ValueError, struct.error, IndexError) as error:
                record.update({"pass": False, "error": str(error)})
            records.append(record)
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    records = inspect_apk(args.apk)
    result = {"apk": str(args.apk.resolve()), "page_bytes": PAGE, "libraries": records,
              "pass": bool(records) and all(row["pass"] for row in records),
              "scope": "ELF and ZIP alignment only; no device execution"}
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text + "\n", encoding="utf-8")
    print(json.dumps({"libraries": len(records), "pass": result["pass"],
                      "failed": [row["path"] for row in records if not row["pass"]]}))
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
