"""Summarize current local audit evidence without altering source files."""
import datetime
import json
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
tracked = subprocess.check_output(
    ["git", "-c", "safe.directory=" + ROOT.as_posix(), "ls-files"], cwd=ROOT, text=True,
).splitlines()
test_sets = {"test", "commonTest", "androidUnitTest", "androidInstrumentedTest", "jvmTest"}
production = [p for p in tracked if p.endswith(".kt") and "src" in pathlib.PurePosixPath(p).parts
              and not test_sets.intersection(pathlib.PurePosixPath(p).parts)]
lengths = {p: len((ROOT / p).read_bytes().splitlines()) for p in production}
baselines = {}
for path in (ROOT / "config/ktlint").glob("*-baseline.xml"):
    baselines[path.name] = len(ET.parse(path).findall(".//error"))
tests = {}
for module, directory in [("server", "watchTogetherServer/build/test-results/test"),
                          ("protocol", "watchTogetherProtocol/build/test-results/jvmTest")]:
    files = sorted((ROOT / directory).glob("TEST-*.xml"))
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in files:
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
    tests[module] = dict(totals, suites=len(files),
        latest_mtime=datetime.datetime.fromtimestamp(max(p.stat().st_mtime for p in files), datetime.timezone.utc).isoformat())
report = (ROOT / "audit/code-review-20260915/REPORT.md").read_text(encoding="utf-8")
bad_links = []
for path, line in re.findall(r"\]\((D:/[^)]+):(\d+)\)", report):
    source = pathlib.Path(path)
    if not source.exists() or int(line) > len(source.read_bytes().splitlines()):
        bad_links.append([path, line])
assert not bad_links, bad_links
evidence = {
    "production_kotlin_files": len(production),
    "production_files_over_1000_lines": sum(n > 1000 for n in lengths.values()),
    "production_files_over_2000_lines": sum(n > 2000 for n in lengths.values()),
    "largest_production_files": sorted(lengths.items(), key=lambda pair: pair[1], reverse=True)[:8],
    "ktlint_baseline_records": baselines,
    "test_result_xml_summary": tests,
    "nul_bytes_in_tv_backup_screen": (ROOT / "tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvServerBackupScreen.kt").read_bytes().count(b"\x00"),
    "report_source_links_valid": True,
}
(ROOT / "audit/code-review-20260915/verification-evidence.json").write_text(
    json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(evidence, ensure_ascii=False, indent=2))
