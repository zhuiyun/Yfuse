#!/usr/bin/env python3
"""Scan committed Gradle dependency locks with OSV and emit an SPDX 2.3 SBOM."""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass

OSV_BATCH_URL = "https://api.osv.dev/v1/querybatch"
OSV_VULN_URL = "https://api.osv.dev/v1/vulns/"
COORDINATE = re.compile(r"^([^:#=]+):([^:#=]+):([^:#=]+)=")
SEVERITIES = {"HIGH", "CRITICAL"}
SECURITY_OVERRIDES = pathlib.Path("scripts/security-overrides.properties")
# A record that reaches the gate without a resolvable severity is treated as blocking:
# the whole point of this script is that a security gate must never pass in silence.
UNRESOLVED = "UNRESOLVED"
BLOCKING = SEVERITIES | {UNRESOLVED}


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


def read_security_overrides(root: pathlib.Path) -> dict[str, str]:
    """Read the same effective version overrides consumed by the root Gradle build.

    Java .properties files escape ':' in keys. Keeping one shared manifest prevents the scanner
    from reporting a stale lockfile version after Gradle has intentionally forced a patched version.
    """
    path = root / SECURITY_OVERRIDES
    if not path.is_file():
        return {}
    overrides: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            raise ValueError(f"Malformed security override: {line}")
        coordinate = key.replace(r"\:", ":").strip()
        version = value.strip()
        if coordinate.count(":") != 1 or not version:
            raise ValueError(f"Malformed security override: {line}")
        overrides[coordinate] = version
    return overrides


def read_dependencies(root: pathlib.Path) -> list[Dependency]:
    found: dict[str, Dependency] = {}
    overrides = read_security_overrides(root)
    for path in root.rglob("gradle.lockfile"):
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            match = COORDINATE.match(line)
            if not match:
                continue
            group, name, locked_version = match.groups()
            override = overrides.get(f"{group}:{name}")
            version = override or locked_version
            source = str(path.relative_to(root))
            if override is not None and override != locked_version:
                source += f"; effective security override from {SECURITY_OVERRIDES}"
            dep = Dependency(group, name, version, source)
            found[dep.coordinate] = dep
    return sorted(found.values(), key=lambda item: item.coordinate)


def query_osv(dependencies: list[Dependency]) -> list[tuple[Dependency, str]]:
    """Maps each dependency to the OSV ids affecting it.

    `querybatch` answers with abbreviated records — only `id` and `modified` — so the
    ids returned here have to be resolved through [fetch_details] before anything can
    be said about how bad they are.
    """
    findings: list[tuple[Dependency, str]] = []
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
                identifier = vulnerability.get("id")
                if identifier:
                    findings.append((dep, str(identifier)))
    return findings


def fetch_details(identifiers: set[str]) -> dict[str, dict]:
    """Resolves abbreviated batch ids into the full records that carry severity."""
    details: dict[str, dict] = {}
    for identifier in sorted(identifiers):
        request = urllib.request.Request(
            f"{OSV_VULN_URL}{urllib.parse.quote(identifier, safe='')}",
            headers={"User-Agent": "Yfuse-supply-chain/1"},
            method="GET",
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            details[identifier] = json.load(response)
    return details


def roundup(value: float) -> float:
    """CVSS 3.1 Appendix A rounding: the float-safe ceiling to one decimal."""
    scaled = int(round(value * 100_000))
    if scaled % 10_000 == 0:
        return scaled / 100_000.0
    return (scaled // 10_000 + 1) / 10.0


CVSS_WEIGHTS = {
    "AV": {"N": 0.85, "A": 0.62, "L": 0.55, "P": 0.2},
    "AC": {"L": 0.77, "H": 0.44},
    "UI": {"N": 0.85, "R": 0.62},
    "C": {"H": 0.56, "L": 0.22, "N": 0.0},
    "I": {"H": 0.56, "L": 0.22, "N": 0.0},
    "A": {"H": 0.56, "L": 0.22, "N": 0.0},
}
CVSS_PRIVILEGES = {
    False: {"N": 0.85, "L": 0.62, "H": 0.27},
    True: {"N": 0.85, "L": 0.68, "H": 0.5},
}


def cvss_v3_score(vector: str) -> float | None:
    """Computes the CVSS 3.x base score, or None when the vector is not v3/is malformed."""
    parts = vector.strip().split("/")
    if not parts or not parts[0].startswith("CVSS:3"):
        return None
    metrics = {}
    for part in parts[1:]:
        key, separator, value = part.partition(":")
        if separator:
            metrics[key] = value
    scope_changed = metrics.get("S") == "C"
    try:
        confidentiality = CVSS_WEIGHTS["C"][metrics["C"]]
        integrity = CVSS_WEIGHTS["I"][metrics["I"]]
        availability = CVSS_WEIGHTS["A"][metrics["A"]]
        exploitability = (
            8.22
            * CVSS_WEIGHTS["AV"][metrics["AV"]]
            * CVSS_WEIGHTS["AC"][metrics["AC"]]
            * CVSS_PRIVILEGES[scope_changed][metrics["PR"]]
            * CVSS_WEIGHTS["UI"][metrics["UI"]]
        )
    except KeyError:
        return None
    subscore = 1 - ((1 - confidentiality) * (1 - integrity) * (1 - availability))
    if scope_changed:
        impact = 7.52 * (subscore - 0.029) - 3.25 * (subscore - 0.02) ** 15
    else:
        impact = 6.42 * subscore
    if impact <= 0:
        return 0.0
    combined = impact + exploitability
    return roundup(min(combined * 1.08 if scope_changed else combined, 10.0))


def rating_for_score(score: float) -> str:
    if score >= 9.0:
        return "CRITICAL"
    if score >= 7.0:
        return "HIGH"
    if score >= 4.0:
        return "MEDIUM"
    if score > 0.0:
        return "LOW"
    return "NONE"


SEVERITY_ORDER = {"NONE": 0, "LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}
# GHSA publishes MODERATE where CVSS says MEDIUM; everything else lines up.
SEVERITY_ALIASES = {"MODERATE": "MEDIUM"}


def severity(vulnerability: dict) -> str:
    """Worst severity the record states, from either the GHSA label or a CVSS vector.

    Returns [UNRESOLVED] rather than a benign default when neither is present, so an
    unreadable record fails the gate instead of slipping through it.
    """
    ratings: list[str] = []
    explicit = vulnerability.get("database_specific", {}).get("severity")
    if explicit:
        normalized = str(explicit).upper()
        normalized = SEVERITY_ALIASES.get(normalized, normalized)
        if normalized in SEVERITY_ORDER:
            ratings.append(normalized)
    for entry in vulnerability.get("severity", []):
        score = cvss_v3_score(str(entry.get("score", "")))
        if score is not None:
            ratings.append(rating_for_score(score))
    if not ratings:
        return UNRESOLVED
    return max(ratings, key=lambda rating: SEVERITY_ORDER[rating])


def write_spdx(dependencies: list[Dependency], findings: list[tuple[Dependency, dict]], output: pathlib.Path) -> None:
    worst: dict[str, str] = {}
    for dep, vulnerability in findings:
        rating = severity(vulnerability)
        current = worst.get(dep.coordinate)
        if current is None or SEVERITY_ORDER.get(rating, -1) > SEVERITY_ORDER.get(current, -1):
            worst[dep.coordinate] = rating
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
                "comment": f"source lockfile: {dep.source}; known vulnerability at scan time: {worst.get(dep.coordinate, 'no')}",
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
    try:
        dependencies = read_dependencies(root)
    except ValueError as exc:
        print(f"::error::Security override manifest is invalid: {exc}")
        return 2
    if not dependencies:
        print("::error::No Maven dependencies found in committed Gradle lockfiles")
        return 2
    try:
        matches = query_osv(dependencies)
        details = fetch_details({identifier for _, identifier in matches})
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        print(f"::error::OSV supply-chain query failed: {exc}")
        return 2
    # A withdrawn advisory is one the upstream database has retracted; it is not a finding.
    findings = [
        (dep, details[identifier])
        for dep, identifier in matches
        if "withdrawn" not in details[identifier]
    ]
    write_spdx(dependencies, findings, root / args.sbom)
    blocking = [(dep, vuln, severity(vuln)) for dep, vuln in findings if severity(vuln) in BLOCKING]
    print(
        f"Scanned {len(dependencies)} Maven dependencies; "
        f"OSV returned {len(findings)} active vulnerability records "
        f"({len(matches) - len(findings)} withdrawn)."
    )
    for dep, vulnerability, level in blocking:
        detail = (
            "no severity could be resolved from the advisory"
            if level == UNRESOLVED
            else vulnerability.get("summary", "no summary")
        )
        print(
            f"::error title={level} dependency vulnerability::{dep.coordinate} "
            f"is affected by {vulnerability.get('id', 'unknown')} ({detail})"
        )
    return 1 if blocking else 0


if __name__ == "__main__":
    sys.exit(main())
