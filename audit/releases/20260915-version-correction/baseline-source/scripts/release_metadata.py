"""Validate the shared Android release metadata without reading signing credentials."""

import argparse
import json
import re
import sys
from pathlib import Path


def read_release(root: Path) -> dict:
    values = {}
    for line in (root / "version.properties").read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or key not in {"VERSION_CODE", "VERSION_NAME"} or key in values:
            raise ValueError("version.properties must contain each version field exactly once")
        values[key] = value
    code, name = values.get("VERSION_CODE", ""), values.get("VERSION_NAME", "")
    if not re.fullmatch(r"[1-9][0-9]*", code) or int(code) > 2_100_000_000:
        raise ValueError("VERSION_CODE must be a valid positive Android version code")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", name):
        raise ValueError("VERSION_NAME must use numeric major.minor.patch format")
    notes = (root / "release-notes.txt").read_text(encoding="utf-8-sig").strip()
    lines = notes.splitlines()
    if not lines or lines[0].strip() != name:
        raise ValueError("release-notes.txt must start with VERSION_NAME")
    current_notes = []
    for line in lines[1:]:
        if re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", line.strip()):
            break
        if line.strip():
            current_notes.append(line.strip())
    if not current_notes:
        raise ValueError("The current version needs release notes")
    trigger = root / ".github" / "sign-android-request"
    if trigger.is_file():
        for line in trigger.read_text(encoding="utf-8-sig").splitlines():
            if line.strip() and not line.lstrip().startswith("#"):
                raise ValueError("sign-android-request is a trigger only; store versions in version.properties")
    return {"versionCode": int(code), "versionName": name, "releaseNotes": "\n".join(current_notes)}


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--format", choices=("json", "github-output", "github-env"), default="json")
    args = parser.parse_args()
    try:
        metadata = read_release(args.root)
    except (ValueError, OSError) as error:
        parser.exit(1, f"Release metadata invalid: {error}\n")
    if args.format == "github-output":
        print(f"version_code={metadata['versionCode']}\nversion_name={metadata['versionName']}")
    elif args.format == "github-env":
        print(f"VERSION_CODE={metadata['versionCode']}\nVERSION_NAME={metadata['versionName']}")
        print("RELEASE_NOTES=" + " ".join(metadata["releaseNotes"].split()))
    else:
        print(json.dumps(metadata, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
