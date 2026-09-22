"""Collect local verification evidence; does not run tests or query external services."""
from pathlib import Path
import importlib.util
import hashlib
import json
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent


def git(*args):
    return subprocess.check_output(
        ["git", "-c", f"safe.directory={ROOT.as_posix()}", *args], cwd=ROOT
    ).decode("utf-8")


def tests(relative):
    directory = ROOT / relative
    rows = []
    for file in sorted(directory.glob("TEST-*.xml")):
        suite = ET.parse(file).getroot()
        rows.append({
            "suite": suite.attrib["name"],
            **{key: int(suite.attrib.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")},
        })
    return {
        "directory": relative,
        **{key: sum(row[key] for row in rows) for key in ("tests", "failures", "errors", "skipped")},
        "suites": rows,
    }


spec = importlib.util.spec_from_file_location("supply_chain_check", ROOT / "scripts/supply_chain_check.py")
scanner = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = scanner
spec.loader.exec_module(scanner)
changed = sorted(set(
    git("diff", "--name-only").splitlines()
    + git("ls-files", "--others", "--exclude-standard", "--", "composeApp/src", "tvApp/src", "watchTogetherServer/src").splitlines()
))
kotlin = [file for file in changed if file.endswith(".kt")]
(OUT / "changed-kotlin.txt").write_text("\n".join(kotlin) + "\n", encoding="utf-8")
baseline = {}
for file in sorted((ROOT / "config/ktlint").glob("*-baseline.xml")):
    path = file.relative_to(ROOT).as_posix()
    before = ET.fromstring(git("show", f"HEAD:{path}"))
    after = ET.parse(file).getroot()
    baseline[file.name] = {"before": len(before.findall(".//error")), "after": len(after.findall(".//error"))}
result = {
    "head": git("rev-parse", "HEAD").strip(),
    "tests": {
        "server": tests("watchTogetherServer/build/test-results/test"),
        "protocol": tests("watchTogetherProtocol/build/test-results/jvmTest"),
        "android": tests("composeApp/build/test-results/testDebugUnitTest"),
        "tv": tests("tvApp/build/test-results/testDebugUnitTest"),
    },
    "scanner": {
        "tracked_locks": [path.relative_to(ROOT).as_posix() for path in scanner.tracked_lockfiles(ROOT)],
        "dependency_coordinates": len(scanner.read_dependencies(ROOT)),
        "network_scan_performed": False,
    },
    "baseline": baseline,
    "changed_kotlin_files": kotlin,
    "version_changes": git("diff", "--", "version.properties", "release-notes.txt"),
    "changed_sources_with_nul": [
        file for file in changed
        if (ROOT / file).is_file() and b"\0" in (ROOT / file).read_bytes()
    ],
}
local_cache = ROOT / ".gradle-tmp/verification-javac"
shared_cache = Path("C:/Users/app-inkbird/.gradle/caches/modules-2/files-2.1")
cache_files = [file for file in local_cache.rglob("*") if file.is_file()]
result["javac_workspace_cache"] = {
    "files": len(cache_files),
    "sha256_mismatches": [
        file.relative_to(local_cache).as_posix() for file in cache_files
        if hashlib.sha256(file.read_bytes()).digest()
        != hashlib.sha256((shared_cache / file.relative_to(local_cache)).read_bytes()).digest()
    ],
}
(OUT / "validation-summary.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(json.dumps({
    "tests": {key: {k: v for k, v in value.items() if k != "suites"} for key, value in result["tests"].items()},
    "scanner": result["scanner"], "baseline": baseline, "kotlin_files": len(kotlin),
}, indent=2))
