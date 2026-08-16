#!/usr/bin/env python3
"""Scan committed Gradle dependency locks with OSV and emit an SPDX 2.3 SBOM."""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass

OSV_BATCH_URL = "https://api.osv.dev/v1/querybatch"
COORDINATE = re.compile(r"^([^:#=]+):([^:#=]+):([^:#=]+)=")
SEVERITIES = {"HIGH", "CRITICAL"}


@dataclass(frozen=True)
class Dependency:
    group: str
    name: str
    version: str
    source: str

    @property
    def purl(self) -> str:
        return f"pkg:maven/{self.group}/{self.name}@{self.version}"

    @property
    def coordinate(self) -> str:
        return f"{self.group}:{self.name}:{self.version}"


def read_dependencies(root: pathlib.Path) -> list[Dependency]:
    found: dict[str, Dependency] = {}
    for path in root.rglob("gradle.lockfile"):
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            match = COORDINATE.match(line)
            if not match:
                continue
            group, name, version = match.groups()
            dep = Dependency(group, name, version, str(path.relative_to(root)))
            found[dep.coordinate] = dep
    return sorted(found.values(), key=lambda item: item.coordinate)


def query_osv(dependencies: list[Dependency]) -> list[tuple[Dependency, dict]]:
    findings: list[tuple[Dependency, dict]] = []
    for start in range(0, len(dependencies), 1000):
        batch = dependencies[start : start + 1000]
        payload = {
            "queries": [
                {
                    "package": {"name": f"{dep.group}:{dep.name}", "ecosystem": "Maven"},
                    "version": dep.version,
                }
                for dep in batch
            ]
        }
        request = urllib.request.Request(
            OSV_BATCH_URL,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json", "User-Agent": "Yfuse-supply-chain/1"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            result = json.load(response)
        for dep, result_item in zip(batch, result.get("results", [])):
            for vulnerability in result_item.get("vulns", []):
                findings.append((dep, vulnerability))
    return findings


def severity(vulnerability: dict) -> str:
    explicit = vulnerability.get("database_specific", {}).get("severity")
    if explicit:
        return str(explicit).upper()
    return "UNKNOWN"


def write_spdx(dependencies: list[Dependency], findings: list[tuple[Dependency, dict]], output: pathlib.Path) -> None:
    vulnerable = {dep.coordinate for dep, _ in findings}
    packages = []
    relationships = []
    for index, dep in enumerate(dependencies, start=1):
        package_id = f"SPDXRef-Package-{index}"
        packages.append(
            {
                "SPDXID": package_id,
                "name": f"{dep.group}:{dep.name}",
                "versionInfo": dep.version,
                "downloadLocation": "NOASSERTION",
                "filesAnalyzed": False,
                "licenseConcluded": "NOASSERTION",
                "licenseDeclared": "NOASSERTION",
                "externalRefs": [
                    {
                        "referenceCategory": "PACKAGE-MANAGER",
                        "referenceType": "purl",
                        "referenceLocator": dep.purl,
                    }
                ],
                "comment": f"source lockfile: {dep.source}; known vulnerability at scan time: {'yes' if dep.coordinate in vulnerable else 'no'}",
            }
        )
        relationships.append(
            {
                "spdxElementId": "SPDXRef-DOCUMENT",
                "relationshipType": "DESCRIBES",
                "relatedSpdxElement": package_id,
            }
        )
    document = {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": "Yfuse Gradle dependency lock snapshot",
        "documentNamespace": "https://yfuse.app/spdx/yfuse-gradle-locks",
        "creationInfo": {"creators": ["Tool: Yfuse supply_chain_check.py"]},
        "packages": packages,
        "relationships": relationships,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--sbom", required=True)
    args = parser.parse_args()
    root = pathlib.Path(args.root).resolve()
    dependencies = read_dependencies(root)
    if not dependencies:
        print("::error::No Maven dependencies found in committed Gradle lockfiles")
        return 2
    try:
        findings = query_osv(dependencies)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        print(f"::error::OSV supply-chain query failed: {exc}")
        return 2
    write_spdx(dependencies, findings, root / args.sbom)
    high = [(dep, vuln, severity(vuln)) for dep, vuln in findings if severity(vuln) in SEVERITIES]
    print(f"Scanned {len(dependencies)} Maven dependencies; OSV returned {len(findings)} vulnerability records.")
    for dep, vulnerability, level in high:
        print(
            f"::error title={level} dependency vulnerability::{dep.coordinate} "
            f"is affected by {vulnerability.get('id', 'unknown')} ({vulnerability.get('summary', 'no summary')})"
        )
    return 1 if high else 0


if __name__ == "__main__":
    sys.exit(main())
