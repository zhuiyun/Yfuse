"""Validate the immutable YCore identity selected by the successful quality run."""

import argparse
import json
import os
from pathlib import Path
import re


def validate_quality_run(record, commit, run_id):
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or record.get("head_sha") != commit:
        raise ValueError("Quality run source does not match the requested commit")
    if type(record.get("id")) is not int or record["id"] != run_id or run_id < 1:
        raise ValueError("Quality run ID differs from the requested run")
    expected = {"event": "push", "head_branch": "master", "status": "completed",
                "conclusion": "success", "path": ".github/workflows/quality-gates-v2.yml"}
    if any(record.get(key) != value for key, value in expected.items()):
        raise ValueError("A successful master push quality run is required; rerun after its checks pass")
    if any(record.get(key, {}).get("full_name") != "zhuiyun/Yfuse" for key in ("repository", "head_repository")):
        raise ValueError("Quality run belongs to a different repository")
    attempt = record.get("run_attempt")
    if type(attempt) is not int or attempt < 1:
        raise ValueError("Invalid quality run attempt")
    return {"run_id": str(run_id), "run_attempt": str(attempt)}


def validate(record, commit, run_id, attempt):
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or record.get("sourceCommit") != commit:
        raise ValueError("Approved YCore belongs to a different source commit")
    for key, expected in (("runId", run_id), ("runAttempt", attempt)):
        if type(record.get(key)) is not int or record[key] != expected or expected < 1:
            raise ValueError("Approved YCore belongs to a different quality run or attempt")
    if type(record.get("artifactId")) is not int or record["artifactId"] < 1:
        raise ValueError("Invalid immutable YCore artifact ID")
    for key in ("aarSha256", "provenanceSha256"):
        if not isinstance(record.get(key), str) or not re.fullmatch(r"[0-9a-f]{64}", record[key]):
            raise ValueError("Invalid approved YCore content hash")
    return {"artifact_id": str(record["artifactId"]), "source_commit": commit,
            "aar_sha256": record["aarSha256"], "provenance_sha256": record["provenanceSha256"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("record", type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--run-id", type=int, required=True)
    parser.add_argument("--attempt", type=int)
    parser.add_argument("--quality-run", action="store_true")
    args = parser.parse_args()
    try:
        record = json.loads(args.record.read_text())
        result = (validate_quality_run(record, args.commit, args.run_id) if args.quality_run else
                  validate(record, args.commit, args.run_id, args.attempt))
        with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
            output.write("".join(f"{key}={value}\n" for key, value in result.items()))
    except (ValueError, KeyError, OSError) as error:
        parser.exit(1, f"Approved YCore refused: {error}\n")


if __name__ == "__main__":
    main()
