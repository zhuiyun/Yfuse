"""Resolve an explicit owner's MDK confirmation for one package-only delivery."""

import argparse
import json
from pathlib import Path
import re

from release_metadata import read_release


def is_confirmed(root: Path, package_only: bool) -> bool:
    if not package_only:
        return False
    try:
        approval = json.loads((root / ".github/mdk-distribution-approval.json").read_text())
        release = read_release(root)
        checksums = []
        for line in (root / "scripts/engine-checksums.sha256").read_text().splitlines():
            fields = line.split()
            if len(fields) == 2 and fields[1] == "mdk-sdk-android.7z":
                checksums.append(fields[0])
        return (
            isinstance(approval, dict)
            and approval.get("confirmed") is True
            and approval.get("scope") == "package-only"
            and approval.get("versionName") == release["versionName"]
            and type(approval.get("versionCode")) is int
            and approval["versionCode"] == release["versionCode"]
            and len(checksums) == 1
            and re.fullmatch(r"[0-9a-f]{64}", checksums[0]) is not None
            and approval.get("mdkArtifactSha256") == checksums[0]
        )
    except (OSError, ValueError):
        return False


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--package-only", action="store_true")
    args = parser.parse_args()
    print("true" if is_confirmed(args.root, args.package_only) else "false")
