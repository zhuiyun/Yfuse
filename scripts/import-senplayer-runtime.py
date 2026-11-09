"""Import the reviewed SenPlayer APK after complete preflight; write I/O is not atomic."""

import hashlib
import io
import json
import pathlib
import sys
import zipfile

EXPECTED_APK_SHA256 = "45a9deb5018cb705432604b564189cf067473e0f52706544706457655ec5fe4c"
DEFAULT_APK = pathlib.Path("C:/Users/app-inkbird/Downloads/SenPlayer.apk")
DEFAULT_DESTINATION = pathlib.Path(__file__).resolve().parents[1] / "senplayerBridge/src/main/assets/senplayer"
RUNTIMES = ("libFFmpegKit.so", "libMLibKit.so", "libc++_shared.so")
FILES = {
    "classes.dex": "classes.dex",
    "assets/license.json": "license.json",
    **{f"lib/arm64-v8a/{name}": f"native/{name}" for name in RUNTIMES},
}


def import_runtime(apk, destination):
    """Validate one immutable APK snapshot and all five assets before any output write.

    Failed identity/archive/ABI preflight leaves an existing runtime unchanged. Filesystem
    failures during the subsequent writes may leave partial output; this is not an atomic swap.
    """
    apk = pathlib.Path(apk)
    destination = pathlib.Path(destination)
    if not apk.is_file():
        raise ValueError("A reviewed APK file is required; loose runtime directories are not supported.")
    data = apk.read_bytes()
    source_sha = hashlib.sha256(data).hexdigest()
    if source_sha != EXPECTED_APK_SHA256:
        raise ValueError("APK differs from the reviewed source. Review the new ABI and license before importing.")

    assets = []
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                raise ValueError("APK has duplicate entries.")
            for name in names:
                normalized = name.replace("\\", "/")
                parts = pathlib.PurePosixPath(normalized).parts
                if normalized.startswith("/") or ".." in parts or (parts and ":" in parts[0]):
                    raise ValueError("APK contains an unsafe archive path.")
            missing = FILES.keys() - set(names)
            if missing:
                raise ValueError("APK is missing required runtime entries: " + ", ".join(sorted(missing)))
            for entry, target in FILES.items():
                asset = archive.read(entry)
                if not asset:
                    raise ValueError(f"Required runtime entry is empty: {entry}")
                if target.endswith(".so") and (
                    len(asset) < 64
                    or asset[:6] != b"\x7fELF\x02\x01"
                    or int.from_bytes(asset[18:20], "little") != 183
                ):
                    raise ValueError(f"Runtime is not an ELF64 little-endian AArch64 library: {entry}")
                assets.append((entry, target, asset))
    except (zipfile.BadZipFile, KeyError, RuntimeError, NotImplementedError) as error:
        raise ValueError("APK runtime group could not be read completely.") from error

    # Fixed targets only: no archive name is ever passed to extract()/extractall().
    for target in [*(item[1] for item in assets), "runtime.json"]:
        path = destination / target
        if path.is_symlink() or not path.resolve().is_relative_to(destination.resolve()):
            raise ValueError("Runtime destination contains an escaping or symbolic-link target.")
    manifest = {
        "sourceApkSha256": source_sha,
        "abi": "arm64-v8a",
        "files": [
            {"asset": target, "apkEntry": entry, "bytes": len(asset), "sha256": hashlib.sha256(asset).hexdigest()}
            for entry, target, asset in assets
        ],
    }
    for _, target, asset in assets:
        path = destination / target
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(asset)
    (destination / "runtime.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def main(argv=None):
    args = sys.argv[1:] if argv is None else argv
    if len(args) > 1:
        raise SystemExit("Usage: import-senplayer-runtime.py [reviewed.apk]")
    try:
        manifest = import_runtime(pathlib.Path(args[0]) if args else DEFAULT_APK, DEFAULT_DESTINATION)
    except (ValueError, OSError) as error:
        raise SystemExit(str(error)) from error
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
