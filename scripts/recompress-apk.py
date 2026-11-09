"""Prepare a release APK for zipalign and signing, preserving its install-time storage methods."""
import argparse
from datetime import datetime
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile


RUNTIME_ENTRY = re.compile(
    r"classes(?:[2-9]|[1-9]\d+)?\.dex|lib/arm64-v8a/[\w.+-]+\.so|"
    r"assets/senplayer/(?:classes\.dex|native/[\w.+-]+\.so)", re.ASCII
)
MIN_COMPRESSED_BYTES = 200_000


def seven_zip():
    for name in ("7zz", "7z"):
        path = shutil.which(name)
        if path:
            return path
    windows_path = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "7-Zip/7z.exe"
    if windows_path.is_file():
        return str(windows_path)
    raise RuntimeError("Install 7-Zip (7zz or 7z) to build the compact release APK")


def entries(archive):
    records = {item.filename: item for item in archive.infolist()}
    if len(records) != len(archive.infolist()):
        raise ValueError("Duplicate APK entries")
    return records


def compact(source, output):
    source, output = source.resolve(), output.resolve()
    if source == output:
        raise ValueError("Use a separate output for the intermediate APK")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
        work = Path(temporary)
        candidate = work / "prepared.apk"
        payload = work / "payload"
        with ZipFile(source) as original:
            before = entries(original)
            selected = [name for name, item in before.items() if RUNTIME_ENTRY.fullmatch(name)
                        and item.compress_type == ZIP_DEFLATED and item.compress_size >= MIN_COMPRESSED_BYTES]
            for name in selected:
                extracted = payload / name
                extracted.parent.mkdir(parents=True, exist_ok=True)
                with original.open(name) as data, extracted.open("wb") as target:
                    shutil.copyfileobj(data, target)
                # The explicit 7-Zip update action recompresses these entries regardless of timestamps.
                stamp = datetime(*before[name].date_time).timestamp()
                os.utime(extracted, (stamp, stamp))
            if selected:
                subprocess.run([seven_zip(), "u", "-tzip", "-mm=Deflate", "-mx=9", "-mmt=1",
                                "-mtc=off", "-bd", "-u-", f"-up1q1r2x2y2z2w2!{candidate}",
                                str(source), *selected], cwd=payload, check=True, stdout=subprocess.DEVNULL)
                with ZipFile(candidate) as result:
                    after = entries(result)
                    if before.keys() != after.keys():
                        raise ValueError("Recompression changed the APK file list")
                    for name, item in before.items():
                        if item.compress_type != after[name].compress_type or item.file_size != after[name].file_size:
                            raise ValueError(f"Recompression changed the storage method or size of {name}")
                        with original.open(name) as a, result.open(name) as b:
                            while True:
                                block = a.read(1024 * 1024)
                                if block != b.read(1024 * 1024):
                                    raise ValueError(f"Recompression changed the contents of {name}")
                                if not block:
                                    break
                    saved = sum(before[n].compress_size - after[n].compress_size for n in selected)
            else:
                saved = 0
        if saved <= 0:
            shutil.copyfile(source, candidate)
        candidate.replace(output)
    print(f"ZIP payload: {len(selected)} large DEFLATE entries, {max(0, saved):,} bytes saved")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    compact(args.source, args.output)
