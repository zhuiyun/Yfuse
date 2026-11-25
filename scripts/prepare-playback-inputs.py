#!/usr/bin/env python3
"""Prepare the verified YCore runtime input."""
import hashlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAX_DOWNLOAD_BYTES = 256 * 1024 * 1024
YCORE_LIBRARIES = {
    "libavcodec.so", "libavformat.so", "libavutil.so", "libc++_shared.so",
    "libswresample.so", "libswscale.so", "libycore_demux.so", "libycore_gpu.so",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


class HttpsRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        require(urllib.parse.urlparse(new_url).scheme == "https", "Input download redirect must remain HTTPS")
        return super().redirect_request(request, fp, code, message, headers, new_url)


def download(url, destination):
    require(urllib.parse.urlparse(url).scheme == "https", "Pinned input URL must use HTTPS")
    # URLs may be private signed URLs; never log them or attach account tokens to remote hosts.
    opener = urllib.request.build_opener(HttpsRedirectHandler())
    try:
        with opener.open(url, timeout=60) as response, destination.open("wb") as output:
            count = 0
            while chunk := response.read(128 * 1024):
                count += len(chunk)
                require(count <= MAX_DOWNLOAD_BYTES, "Pinned input download exceeds its size budget")
                output.write(chunk)
    except OSError:
        raise ValueError("Unable to download a configured pinned playback input") from None


def verify_ycore(data, pin):
    require(len(data) == pin["bytes"] and sha256(data) == pin["sha256"], "Standalone YCore input does not match its fixed release pin")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate standalone YCore ZIP entry")
        native = {name for name in names if name.endswith(".so")}
        require(native == {f"jni/arm64-v8a/{name}" for name in YCORE_LIBRARIES}, "Unexpected standalone YCore native library inventory")
        provenance = archive.read("META-INF/ycore-native-sources.txt")
        require(sha256(provenance) == pin["provenanceSha256"], "YCore provenance differs from the audited input")
        require(archive.read("META-INF/NOTICE"), "YCore notices are missing")
    return provenance


def input_path(root, environment, prefix, destination, fetch):
    configured = environment.get(prefix + "_PATH")
    # A source artifact carries sibling provenance. Always validate the explicit bundle.
    if prefix == "YCORE_NATIVE_AAR" and environment.get("YCORE_SOURCE_RECEIPT") and configured:
        source = Path(configured)
        require(source.is_file(), "Configured source-built YCore AAR does not name a file")
        return source
    if destination.is_file():
        return destination
    if configured:
        source = Path(configured)
        require(source.is_file(), f"Configured {prefix}_PATH does not name a file")
        return source
    url = environment.get(prefix + "_URL")
    require(url, f"Missing pinned {prefix}; provide a verified local input/cache or explicitly configure {prefix}_PATH / {prefix}_URL. No legacy fallback is permitted.")
    staging = root / "build/playback-input-downloads"
    staging.mkdir(parents=True, exist_ok=True)
    # Caller deletes this temporary download even when identity verification fails.
    with tempfile.NamedTemporaryFile(prefix="input-", dir=staging, delete=False) as temporary:
        path = Path(temporary.name)
    try:
        fetch(url, path)
    except BaseException:
        path.unlink(missing_ok=True)
        raise
    return path


def ycore_pin(root, source, pins, environment):
    keys = ("YCORE_SOURCE_RECEIPT", "YCORE_SOURCE_AAR_SHA256",
            "YCORE_SOURCE_PROVENANCE_SHA256", "YCORE_SOURCE_COMMIT")
    if not any(environment.get(key) for key in keys):
        return pins["ycore"], "historical-pinned-input"
    require(all(environment.get(key) for key in keys), "Incomplete source-built YCore identity")
    expected_commit = environment["YCORE_SOURCE_COMMIT"]
    require(environment.get("SOURCE_COMMIT", environment.get("GITHUB_SHA", expected_commit)) == expected_commit,
            "Source-built YCore does not belong to this application commit")
    spec = importlib.util.spec_from_file_location("ycore_source_receipt", root / "scripts/ycore-source-receipt.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    receipt = module.verify_receipt(
        root, source, Path(environment["YCORE_SOURCE_RECEIPT"]),
        environment["YCORE_SOURCE_AAR_SHA256"], environment["YCORE_SOURCE_PROVENANCE_SHA256"],
        expected_commit)
    return receipt["ycore"], "verified-source-build"


def prepare(root=ROOT, environment=None, fetch=download):
    environment = os.environ if environment is None else environment
    pins = json.loads((root / "scripts/playback-runtime-inputs.json").read_text(encoding="utf-8"))
    destination = root / "composeApp/libs/ycore-native.aar"
    source = input_path(root, environment, "YCORE_NATIVE_AAR", destination, fetch)
    try:
        data = source.read_bytes()
        expected_ycore, input_kind = ycore_pin(root, source, pins, environment)
        provenance = verify_ycore(data, expected_ycore)
        destination.parent.mkdir(parents=True, exist_ok=True)
        if source != destination:
            destination.write_bytes(data)
        (destination.parent / "ycore-native.aar.sha256").write_text(expected_ycore["sha256"] + "  ycore-native.aar\n", encoding="utf-8")
        (destination.parent / "ycore-native.sources.txt").write_bytes(provenance)
    finally:
        if source.parent == root / "build/playback-input-downloads":
            source.unlink(missing_ok=True)
    return {"profile": pins["profile"], "ycoreSha256": expected_ycore["sha256"],
            "ycoreInputKind": input_kind}


def main():
    try:
        print(json.dumps(prepare(), indent=2))
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        print(f"Playback inputs unavailable: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
