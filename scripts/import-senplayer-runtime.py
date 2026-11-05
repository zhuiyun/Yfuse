#!/usr/bin/env python3
"""Import the exact supplied build-8 runtime, without copying app assets or credentials."""
import argparse
import hashlib
from pathlib import Path
import tempfile
import zipfile

RUNTIMES = {
    "libFFmpegKit.so": (54435336, "e6f26a53f8cf793cc1733ae4fbfed30a71794a2ca932c585048021e865aad1a3"),
    "libc++_shared.so": (1292904, "f4e1e97c1943e60311e47e8b024d78f5b3b7229b3ccc65feb33af83d6025a670"),
}


def validate(name: str, data: bytes) -> None:
    size, digest = RUNTIMES[name]
    if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f"Unsupported SenPlayer build: {name} checksum/size mismatch")
    if data[:6] != b"\x7fELF\x02\x01" or int.from_bytes(data[18:20], "little") != 183:
        raise ValueError(f"Expected little-endian ARM64 ELF: {name}")


def import_runtime(source: Path, destination: Path) -> None:
    """Validate the whole pair before replacing anything. Never extract arbitrary ZIP paths."""
    payload = {}
    if source.is_dir():
        for name in RUNTIMES:
            payload[name] = (source / name).read_bytes()
    else:
        with zipfile.ZipFile(source) as archive:
            for name, (size, _) in RUNTIMES.items():
                matches = [entry for entry in archive.infolist()
                           if entry.filename == f"lib/arm64-v8a/{name}" or
                           entry.filename.endswith(f"/resources/lib/arm64-v8a/{name}")]
                if len(matches) != 1 or matches[0].file_size != size:
                    raise ValueError(f"Expected one build-8 ARM64 library: {name}")
                payload[name] = archive.read(matches[0])
    for name, data in payload.items():
        validate(name, data)
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination) as staging:
        for name, data in payload.items():
            Path(staging, name).write_bytes(data)
        for name in payload:
            Path(staging, name).replace(destination / name)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="APK, analysis ZIP, or arm64-v8a library directory")
    args = parser.parse_args()
    destination = Path(__file__).resolve().parents[1] / "senPlayerAndroid/runtime/arm64-v8a"
    import_runtime(args.source, destination)
    print("Imported verified build-8 ARM64 runtime. Explicit build flag: -PyfuseIncludeSenPlayer=true")


if __name__ == "__main__":
    main()
