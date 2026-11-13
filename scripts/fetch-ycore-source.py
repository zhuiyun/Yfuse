#!/usr/bin/env python3
"""Fetch the independent source inputs declared in the YCore lock."""
import hashlib
import json
import os
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
directory = Path(sys.argv[1]).resolve()
directory.mkdir(parents=True, exist_ok=True)
lock = json.loads((root / "scripts/ycore-dependencies.json").read_text())


def git(path, *args):
    return subprocess.check_output(["git", "-C", str(path), *args], text=True).strip()


for name, source in lock["repositories"].items():
    path = directory / name
    if not (path / ".git").is_dir():
        path.mkdir(exist_ok=True)
        git(path, "init", "--quiet")
    try:
        git(path, "rev-parse", "--verify", "HEAD")
    except subprocess.CalledProcessError:
        git(path, "fetch", "--depth=1", source["url"], source["commit"])
        git(path, "checkout", "--quiet", "--detach", "FETCH_HEAD")
    if git(path, "rev-parse", "HEAD") != source["commit"]:
        raise SystemExit(f"{name} checkout differs from locked commit")
    if name == "mbedtls":
        git(path, "submodule", "update", "--init", "--recursive", "--depth=1")

records = []
for name, source in lock["archives"].items():
    path = directory / name
    with tempfile.TemporaryDirectory(dir=directory) as staging:
        archive = Path(staging) / "source"
        subprocess.run(["curl", "--fail", "--location", "--retry", "3", "--proto", "=https",
                        "--proto-redir", "=https", "--output", str(archive), source["url"]], check=True)
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        if digest != source["sha256"]:
            raise SystemExit(f"{name} archive differs from locked SHA-256")
        if path.exists():
            raise SystemExit(f"{path} already exists, use a fresh native build workspace")
        unpacked = Path(staging) / "files"
        with tarfile.open(archive) as tar:
            tar.extractall(unpacked, filter="data")
        (folder,) = list(unpacked.iterdir())
        if not folder.is_dir() or folder.is_symlink():
            raise SystemExit("Invalid source archive layout")
        folder.rename(path)
        records.append(dict(name=name, url=source["url"], sha256=digest, bytes=archive.stat().st_size))

receipt = Path(os.environ.get("YCORE_DOWNLOAD_RECEIPTS", directory.parent.parent / "downloads.jsonl"))
receipt.parent.mkdir(parents=True, exist_ok=True)
receipt.write_text("".join(json.dumps(entry) + "\n" for entry in records), encoding="utf-8")
