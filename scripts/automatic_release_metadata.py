"""Stage reproducible, increasing release metadata without creating recursive bot commits."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

from release_metadata import read_release


def require(condition, message):
    if not condition:
        raise ValueError(message)


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def version_number(name):
    require(re.fullmatch(r"[0-9]\.[0-9]\.[0-9]", name), "Version names must have three single-digit components")
    return int(name.replace(".", ""))


def version_name(number):
    require(0 <= number <= 999, "Automatic version space is exhausted; explicitly establish a new version policy")
    return ".".join(f"{number:03d}")


def properties(text):
    values = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        require(separator and key.strip() not in values, "Duplicate or invalid version property")
        values[key.strip()] = value.strip()
    require(set(values) == {"VERSION_CODE", "VERSION_NAME"}, "Unexpected version properties")
    code = values["VERSION_CODE"]
    require(re.fullmatch(r"[1-9][0-9]*", code) and int(code) <= 2_100_000_000, "Invalid Android versionCode")
    return int(code), version_number(values["VERSION_NAME"])


def automatic_version(root, commit, baseline):
    require(re.fullmatch(r"[0-9a-f]{40}", commit), "Invalid source commit")
    anchor = baseline["commit"]
    require(re.fullmatch(r"[0-9a-f]{40}", anchor), "Invalid baseline commit")
    history = git(root, "rev-list", "--first-parent", commit).splitlines()
    require(anchor in history, "Source must descend from the release baseline on the first-parent history")
    code, number = baseline["versionCode"], version_number(baseline["versionName"])
    require(type(code) is int and 0 < code <= 2_100_000_000, "Invalid baseline versionCode")
    # A known private delivery can be newer than the checked-in version at the anchor.
    committed_code, committed_number = properties(git(root, "show", f"{anchor}:version.properties"))
    code, number = max(code, committed_code), max(number, committed_number)
    newer = list(reversed(history[:history.index(anchor)]))
    require(newer, "The baseline itself is already delivered; automatic packaging needs a newer commit")
    for revision in newer:
        committed_code, committed_number = properties(git(root, "show", f"{revision}:version.properties"))
        code = max(code + 1, committed_code)
        number = max(number + 1, committed_number)
    require(code <= 2_100_000_000, "Android versionCode space exhausted")
    return code, version_name(number)


def prepare(root, output, commit, mode, environment=os.environ):
    require(git(root, "rev-parse", "HEAD") == commit, "Checkout is not the requested source commit")
    baseline = json.loads((root / "config/automatic-release-baseline.json").read_text())
    require(environment.get("GITHUB_REF") == "refs/heads/" + baseline["branch"] == "refs/heads/master",
            "Production packages are restricted to master")
    current = read_release(root)
    if mode == "automatic":
        code, name = automatic_version(root, commit, baseline)
        subject = git(root, "log", "-1", "--format=%s", commit)
        notes = f"• 自动构建：{subject}\n• 源码提交：{commit}"
    else:
        code, name = properties(f"VERSION_CODE={environment.get('VERSION_CODE', '')}\n"
                                f"VERSION_NAME={environment.get('VERSION_NAME', '')}\n")
        name = version_name(name)
        notes = environment.get("RELEASE_NOTES", "").strip()
        require(notes, "Manual packaging requires release notes")
        # Manual builds cannot silently reuse a known older delivery's Android identity.
        expected_code, expected_name = (baseline["versionCode"], baseline["versionName"]) if commit == baseline["commit"] else automatic_version(root, commit, baseline)
        require(code == max(current["versionCode"], expected_code)
                and version_number(name) == max(version_number(current["versionName"]), version_number(expected_name)),
                "Requested version is older than or differs from this source's automatic identity; commit a deliberate version jump first")
    output.mkdir(parents=True, exist_ok=True)
    props = f"# Effective metadata for source commit {commit}\nVERSION_CODE={code}\nVERSION_NAME={name}\n"
    historical_notes = (root / "release-notes.txt").read_text(encoding="utf-8-sig").strip()
    all_notes = f"{name}\n\n{notes}\n\n{historical_notes}\n"
    (output / "version.properties").write_text(props, encoding="utf-8")
    (output / "release-notes.txt").write_text(all_notes, encoding="utf-8")
    request = {"sourceCommit": commit, "sourceBranch": "master", "mode": mode,
               "versionCode": code, "versionName": name, "releaseNotes": notes,
               "files": {filename: hashlib.sha256((output / filename).read_bytes()).hexdigest()
                         for filename in ("version.properties", "release-notes.txt")}}
    (output / "release-request.json").write_text(json.dumps(request, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    require(read_release(output)["versionCode"] == code, "Staged version is inconsistent")
    return request


def validate(directory, commit, environment=os.environ):
    request = json.loads((directory / "release-request.json").read_text(encoding="utf-8"))
    require(re.fullmatch(r"[0-9a-f]{40}", commit) and request["sourceCommit"] == commit,
            "Release metadata names a different source commit")
    require(request["sourceBranch"] == "master", "Release metadata names an unsupported branch")
    require(set(request["files"]) == {"version.properties", "release-notes.txt"}, "Unexpected staged file inventory")
    for filename, expected in request["files"].items():
        path = directory / filename
        require(path.is_file() and not path.is_symlink(), "Staged metadata must be regular files")
        require(hashlib.sha256(path.read_bytes()).hexdigest() == expected, "Staged release metadata hash mismatch")
    metadata = read_release(directory)
    require(metadata["versionCode"] == request["versionCode"] and metadata["versionName"] == request["versionName"],
            "Staged release identity differs from the request")
    require(str(metadata["versionCode"]) == environment["VERSION_CODE"] and metadata["versionName"] == environment["VERSION_NAME"],
            "Staged release identity differs from the verified job outputs")
    version_number(metadata["versionName"])
    return request


def install(root, directory, commit, environment=os.environ):
    request = validate(directory, commit, environment)
    for filename in request["files"]:
        shutil.copyfile(directory / filename, root / filename)


def verify(root, directory, commit, environment=os.environ):
    request = validate(directory, commit, environment)
    for filename, digest in request["files"].items():
        require(hashlib.sha256((root / filename).read_bytes()).hexdigest() == digest,
                "The build mutated the effective release metadata")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "install", "verify"))
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--mode", choices=("automatic", "manual"), default="automatic")
    args = parser.parse_args()
    try:
        if args.action == "prepare":
            prepare(args.root, args.directory, args.commit, args.mode)
        else:
            globals()[args.action](args.root, args.directory, args.commit)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Release metadata refused: {error}\n")


if __name__ == "__main__":
    main()
