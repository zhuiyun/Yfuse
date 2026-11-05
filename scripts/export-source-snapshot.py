#!/usr/bin/env python3
"""Export the current source tree with provenance; never publish or mutate Git.

Run after the editing/build freeze. An output directory must be new. The original
comparison baseline remains untouched. Locally generated SenPlayer inputs are the
only ignored binary assets admitted, and each must match its pinned manifest.
"""

from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import json
import subprocess
import zipfile
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED_ROOTS = {"audit", "artifacts", "artifacts-local", "tmp", ".git", ".worktrees"}
EXCLUDED_PARTS = {"build", "node_modules", "__pycache__", ".gradle", ".kotlin", ".cxx", ".idea"}
PRIVATE_NAMES = {
    "local.properties", "keystore.properties", "storage-state.json", "renderer.environment",
    "google-services.json", ".npmrc", ".pypirc", ".netrc", ".dockercfg",
}
PRIVATE_SUFFIXES = {".jks", ".keystore", ".pem", ".p12", ".pfx", ".key"}
ENGINE_PROVENANCE = {
    f"composeApp/libs/{engine}{suffix}"
    for engine in ("libmpv-release", "ycore-native", "ycore-gpu")
    for suffix in (".aar.sha256", ".sources.txt")
}


def git(*args: str) -> bytes:
    return subprocess.run(["git", "-C", str(ROOT), *args], check=True, capture_output=True).stdout


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def exclusion(path: str) -> str | None:
    parts = Path(path).parts
    if parts[0] in EXCLUDED_ROOTS or any(part in EXCLUDED_PARTS for part in parts):
        return "historical_evidence_or_generated_output"
    name = Path(path).name.lower()
    environment_file = name.startswith(".env") and not name.endswith((".example", ".sample", ".template"))
    if environment_file or name in PRIVATE_NAMES or Path(path).suffix.lower() in PRIVATE_SUFFIXES:
        return "local_environment_or_signing_material"
    return None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--write", action="store_true", help="Create the reviewed source archive after freeze")
    parser.add_argument("--estimate-zip", action="store_true", help="Estimate ZIP size in memory during a dry run")
    args = parser.parse_args()
    destination = args.output.resolve()
    if not destination.is_relative_to(ROOT / "audit") or destination.exists():
        raise SystemExit("Output must be a NEW directory under this workspace's audit directory")
    head = git("rev-parse", "HEAD").decode().strip()
    tracked: dict[str, dict] = {}
    for row in git("ls-tree", "-r", "-z", "HEAD").split(b"\0"):
        if not row:
            continue
        header, raw_path = row.split(b"\t", 1)
        mode, kind, blob = header.decode().split()
        if kind != "blob":
            raise SystemExit("Submodule needs explicit source export: " + raw_path.decode())
        tracked[raw_path.decode()] = {"headBlob": blob, "gitMode": mode}
    source_roots = {p.split("/")[0] for p in tracked if exclusion(p) is None}
    source_roots.add("senplayerBridge")
    candidates = set(tracked)
    ignored_untracked = []
    for raw in git("ls-files", "--others", "--exclude-standard", "-z").split(b"\0"):
        if raw:
            path = raw.decode()
            if path.split("/")[0] in source_roots:
                candidates.add(path)
            else:
                ignored_untracked.append(path)

    runtime_root = "senplayerBridge/src/main/assets/senplayer/"
    runtime_manifest = json.loads((ROOT / runtime_root / "runtime.json").read_text(encoding="utf-8"))
    if runtime_manifest["sourceApkSha256"] != "45a9deb5018cb705432604b564189cf067473e0f52706544706457655ec5fe4c":
        raise SystemExit("Unreviewed SenPlayer runtime")
    runtime_entries = {}
    allowed = {"classes.dex", "license.json", "native/libFFmpegKit.so", "native/libMLibKit.so", "native/libc++_shared.so"}
    if {entry["asset"] for entry in runtime_manifest["files"]} != allowed:
        raise SystemExit("Unexpected SenPlayer runtime entries")
    for entry in runtime_manifest["files"]:
        path = runtime_root + entry["asset"]
        data = (ROOT / path).read_bytes()
        if digest(data) != entry["sha256"] or len(data) != entry["bytes"]:
            raise SystemExit("Runtime asset checksum mismatch: " + path)
        runtime_entries[path] = entry
        candidates.add(path)
    # Reviewed source commits/build capabilities/checksums, never the downloaded AARs.
    for path in ENGINE_PROVENANCE:
        if (ROOT / path).is_file():
            candidates.add(path)

    entries, excluded = [], []
    estimated_zip_bytes = 22
    for path in sorted(candidates):
        reason = exclusion(path)
        if reason:
            excluded.append({"path": path, "reason": reason})
            continue
        source = ROOT / path
        if not source.exists():
            excluded.append({"path": path, "reason": "deleted_in_worktree", **tracked.get(path, {})})
            continue
        if source.is_symlink() or not source.resolve().is_relative_to(ROOT):
            raise SystemExit("External/symlink source needs review: " + path)
        data = source.read_bytes()
        if args.estimate_zip:
            estimated_zip_bytes += len(zlib.compress(data, 6)) - 6 + 76 + 2 * len(path.encode("utf-8"))
        entry = {
            "path": path,
            "bytes": len(data),
            "sha256": digest(data),
            "source": "tracked_worktree" if path in tracked else "new_task_source",
            "baseCommit": head if path in tracked else "",
            "headBlob": tracked.get(path, {}).get("headBlob", ""),
            "gitMode": tracked.get(path, {}).get("gitMode", "100644"),
            "apkEntry": "",
        }
        if path in runtime_entries:
            entry.update(source="unchanged_user_supplied_apk_entry", apkEntry=runtime_entries[path]["apkEntry"])
        elif path in ENGINE_PROVENANCE:
            entry.update(source="reviewed_native_build_provenance")
        entries.append(entry)
    planned = {
        "head": head, "fileCount": len(entries), "bytes": sum(e["bytes"] for e in entries),
        "output": str(destination),
        "sources": dict(collections.Counter(e["source"] for e in entries)),
        "exclusions": dict(collections.Counter(e["reason"] for e in excluded)),
        "untrackedOutsideSourceRootsCount": len(ignored_untracked),
    }
    if args.estimate_zip:
        planned["estimatedZipBytes"] = estimated_zip_bytes
    if not args.write:
        print(json.dumps(planned, indent=2))
        return
    destination.mkdir(parents=True)
    archive = destination / "yfuse-integrated-source.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for entry in entries:
            data = (ROOT / entry["path"]).read_bytes()
            if digest(data) != entry["sha256"]:
                raise SystemExit("Source changed during snapshot: " + entry["path"])
            info = zipfile.ZipInfo(entry["path"], (2026, 11, 5, 0, 0, 0))
            info.external_attr = int(entry["gitMode"], 8) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            bundle.writestr(info, data)
    with zipfile.ZipFile(archive) as bundle:
        if set(bundle.namelist()) != {entry["path"] for entry in entries}:
            raise SystemExit("Archive inventory mismatch")
        for entry in entries:
            if digest(bundle.read(entry["path"])) != entry["sha256"]:
                raise SystemExit("Archive hash mismatch: " + entry["path"])
    with (destination / "source-provenance.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(entries[0]))
        writer.writeheader()
        writer.writerows(entries)
    report = {
        **planned, "archive": archive.name, "archiveBytes": archive.stat().st_size,
        "archiveSha256": digest(archive.read_bytes()), "allZipFileHashesVerified": True,
        "baseComparisonPreserved": "audit/senplayer-comparison-20261105/yfuse-merged-source.zip",
        "runtimeSourceApkSha256": runtime_manifest["sourceApkSha256"],
        "scope": "Current formal worktree plus task additions; legacy experiments remain separately archived.",
        "limitations": "Build caches, downloaded engines other than supplied SenPlayer runtime, local environment and signing material are excluded. No APK/HAP delivery or publication.",
    }
    (destination / "snapshot-manifest.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (destination / "source-exclusions.json").write_text(json.dumps({"excluded": excluded, "untrackedOutsideSourceRoots": ignored_untracked}, indent=2) + "\n", encoding="utf-8")
    (destination / "SHA256SUMS.txt").write_text(report["archiveSha256"] + "  " + archive.name + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
