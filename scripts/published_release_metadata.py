"""Prepare a GitHub release from the successful publishing run's update artifact.

Artifacts must come from the trusted successful publishing run, which checked the APK
version and signing certificate. This step checks their integrity and effective metadata;
it does not independently authenticate the manifest signature or read repository defaults.
"""

import argparse
import base64
import binascii
import hashlib
import json
import os
import re
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlsplit


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate manifest field: {key}")
        result[key] = value
    return result


def _reject_constant(value):
    raise ValueError(f"Invalid JSON number: {value}")


def _regular_artifact(path: Path, root: Path) -> None:
    if not path.is_file() or not path.resolve().is_relative_to(root):
        raise ValueError("The manifest and APK must be regular files inside the artifact directory")
    if any(part.is_symlink() for part in (path, *path.parents) if part.is_relative_to(root)):
        raise ValueError("Artifact files and directories must not be symbolic links")


def _read_manifest(artifacts: Path):
    if artifacts.is_symlink() or not artifacts.is_dir():
        raise ValueError("The publishing run's artifact directory is missing or symbolic")
    root = artifacts.resolve()
    manifests = list(root.rglob("update-v2.json"))
    if len(manifests) != 1:
        raise ValueError("Expected exactly one update-v2.json from the publishing run")
    manifest = manifests[0]
    _regular_artifact(manifest, root)
    if manifest.stat().st_size > 1_048_576:
        raise ValueError("The update manifest exceeds 1 MiB")
    metadata = json.loads(
        manifest.read_text(encoding="utf-8"),
        object_pairs_hook=_unique_object,
        parse_constant=_reject_constant,
    )
    if not isinstance(metadata, dict):
        raise ValueError("The update manifest must be a JSON object")
    code, name = metadata.get("versionCode"), metadata.get("versionName")
    if type(code) is not int or not 0 < code <= 2_100_000_000:
        raise ValueError("versionCode must be a valid positive Android integer")
    if not isinstance(name, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", name):
        raise ValueError("versionName must use numeric major.minor.patch format")
    notes = metadata.get("notes")
    if not isinstance(notes, str) or not notes.strip():
        raise ValueError("The published version must have non-empty release notes")
    if any((ord(char) < 32 and char not in "\t\n\r") or ord(char) == 127 for char in notes):
        raise ValueError("Release notes contain unsupported control characters")
    notes.encode("utf-8", errors="strict")
    size, sha256 = metadata.get("size"), metadata.get("sha256")
    if type(size) is not int or size <= 0:
        raise ValueError("size must be a positive integer")
    if not isinstance(sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", sha256):
        raise ValueError("sha256 must contain 64 lowercase hexadecimal digits")
    signature = metadata.get("signature")
    try:
        signature_bytes = base64.b64decode(signature, validate=True) if isinstance(signature, str) else b""
    except (ValueError, binascii.Error) as error:
        raise ValueError("The published manifest must contain an encoded Ed25519 signature") from error
    if len(signature_bytes) != 64:
        raise ValueError("The published manifest must contain an encoded Ed25519 signature")

    expected_name = f"Yfuse-{code}-{name}.apk"
    apk_url = metadata.get("apkUrl")
    if not isinstance(apk_url, str) or any(char.isspace() or ord(char) < 32 for char in apk_url):
        raise ValueError("apkUrl must be the published HTTPS APK URL")
    url = urlsplit(apk_url)
    # Accessing port also rejects syntactically invalid or out-of-range port numbers.
    _ = url.port
    if (
        url.scheme != "https" or not url.hostname or url.username or url.password
        or url.query or url.fragment or "\\" in apk_url
        or any(segment in {".", ".."} for segment in url.path.split("/"))
        or url.path.rsplit("/", 1)[-1] != expected_name
    ):
        raise ValueError("apkUrl must name the APK for the exact published version")
    # Use the validated, locally constructed name, never a URL-derived filesystem path.
    apk = manifest.parent / expected_name
    _regular_artifact(apk, root)
    return metadata, apk


def prepare_release(artifacts: Path, apk_output: Path, notes_output: Path) -> dict:
    metadata, apk = _read_manifest(artifacts)
    if apk_output.resolve() == notes_output.resolve():
        raise ValueError("APK and notes outputs must be different files")
    if any(path.resolve().is_relative_to(artifacts.resolve()) for path in (apk_output, notes_output)):
        raise ValueError("Release outputs must be outside the downloaded artifact directory")
    if apk.stat().st_size != metadata["size"]:
        raise ValueError("Uploaded APK size does not match update-v2.json")

    temporary_paths = []
    try:
        apk_output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=apk_output.parent, delete=False) as target:
            temporary_paths.append(Path(target.name))
            digest = hashlib.sha256()
            actual_size = 0
            with apk.open("rb") as source:
                for chunk in iter(lambda: source.read(1_048_576), b""):
                    digest.update(chunk)
                    actual_size += len(chunk)
                    target.write(chunk)
        if actual_size != metadata["size"] or digest.hexdigest() != metadata["sha256"]:
            raise ValueError("Uploaded APK SHA-256 or size does not match update-v2.json")
        notes_output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=notes_output.parent, mode="w", encoding="utf-8", newline="", delete=False) as target:
            temporary_paths.append(Path(target.name))
            target.write(metadata["notes"])
        # No downstream output exists until all metadata and the copied APK are verified.
        os.replace(temporary_paths[0], apk_output)
        os.replace(temporary_paths[1], notes_output)
    finally:
        for path in temporary_paths:
            path.unlink(missing_ok=True)
    return {"versionCode": metadata["versionCode"], "versionName": metadata["versionName"]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", required=True, type=Path)
    parser.add_argument("--apk-output", required=True, type=Path)
    parser.add_argument("--notes-output", required=True, type=Path)
    args = parser.parse_args()
    try:
        metadata = prepare_release(args.artifacts, args.apk_output, args.notes_output)
    except (ValueError, OSError, UnicodeError) as error:
        parser.exit(1, f"Published release artifact invalid: {error}\n")
    # Only strictly validated numeric fields reach GITHUB_OUTPUT. Notes stay in a file.
    print(f"version_code={metadata['versionCode']}\nversion_name={metadata['versionName']}")


if __name__ == "__main__":
    main()
