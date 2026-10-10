"""Retain the exact uncommitted source used for this local signed package."""

import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[3]
AUDIT = Path(__file__).resolve().parent
DELIVERY = ROOT / "artifacts-local/releases/splash-1.2.1-273"


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


names = git("diff", "HEAD", "--name-only", "-z").split(b"\0")
names += git("ls-files", "--others", "--exclude-standard", "-z").split(b"\0")
paths = sorted({name.decode("utf-8") for name in names if name})
paths = [
    path for path in paths
    if path.startswith(("composeApp/", "phoneShared/", "tvShared/", "tvApp/", "gradle/", "scripts/"))
    or path in ("version.properties", "release-notes.txt", ".github/mdk-distribution-approval.json", "build.gradle.kts", "settings.gradle.kts", "gradle.properties")
]
files = []
with zipfile.ZipFile(DELIVERY / "changed-source.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for path in paths:
        full = ROOT / path
        if not full.is_file():
            raise RuntimeError(f"Changed source missing: {path}")
        data = full.read_bytes()
        files.append({"path": path, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
        archive.writestr(path, data)
source = {"sourceCommit": git("rev-parse", "HEAD").decode().strip(), "treeDirty": True, "files": files}
for destination in (AUDIT, DELIVERY):
    (destination / "source-inputs.json").write_text(json.dumps(source, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
(DELIVERY / "source.patch").write_bytes(git("diff", "HEAD", "--binary", "--no-ext-diff"))
print(f"Archived {len(files)} changed source files; source commit {source['sourceCommit']}")
