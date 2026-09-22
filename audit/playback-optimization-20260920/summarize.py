"""Save final local verification evidence; deliberately makes no device calls."""
import datetime
import hashlib
import html
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = pathlib.Path(__file__).parent
for name in ("test.log", "lint.log", "reports.log"):
    assert "BUILD SUCCESSFUL" in (OUT / name).read_text(encoding="utf-8", errors="replace"), name
assert not (OUT / "format-files.log").read_text(encoding="utf-8", errors="replace").strip(), "Formatting errors"
results = {}
for project in ("phoneShared", "tvShared"):
    # AGP host tests retain Gradle binary results; generate this HTML directly from
    # our isolated binary directory, without rerunning or borrowing another task's results.
    source = OUT / "unit-reports" / project / "index.html"
    report = source.read_text(encoding="utf-8")
    suites = [
        {"name": html.unescape(name), "tests": int(tests), "failures": int(failures), "skipped": int(skipped)}
        for name, tests, failures, skipped in re.findall(
            r'<td class="path">([^<]+)</td>\s*<td>(\d+)</td>\s*<td>(\d+)</td>\s*<td>(\d+)</td>',
            report,
        )
    ]
    assert suites, project
    totals = {key: sum(s[key] for s in suites) for key in ("tests", "failures", "skipped")}
    for key, value in totals.items():
        assert re.search(rf'<div class="counter">{value}</div>\s*<p>{key}</p>', report), (project, key)
    assert totals["failures"] == totals["skipped"] == 0, totals
    binary = OUT / "isolated-results" / project / "testAndroidHostTest/binary/results-generic.bin"
    results[project] = {
        "totals": totals,
        "suites": suites,
        "report": str(source.relative_to(ROOT)),
        "result_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
    }
paths = json.loads((OUT / "format-paths.json").read_text(encoding="utf-8-sig"))
paths += ["scripts/native/ycore_demux_jni.cpp", "gradle/libs.versions.toml", "docs/PLAYBACK_OPTIMIZATION_20260920.md"]
paths += [f"{p}/gradle.lockfile" for p in ("composeApp", "phoneShared", "tvApp", "tvShared", "macrobenchmark")]
manifest = {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in sorted(set(paths))}
native = json.loads((OUT / "native/result.json").read_text())
assert native["compiled_source_sha256"]["ycore_demux_jni.cpp"] == manifest["scripts/native/ycore_demux_jni.cpp"]
assert native["aar_sha256"] == hashlib.sha256((ROOT / "composeApp/libs/ycore-native.aar").read_bytes()).hexdigest()
concurrent = {
    mode: json.loads((OUT / f"{mode}-concurrent-changes.json").read_text(encoding="utf-8-sig"))
    for mode in ("test", "lint")
}
assert not any(concurrent.values()), concurrent
summary = {
    "verified_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "implementation": "A-F playback optimizations and launch/session timing",
    "unit_results": results,
    "compile": ["phoneShared", "tvShared", "composeApp debug", "tvApp debug"],
    "lint": ["composeApp debug", "tvApp debug", "design system usage"],
    "format": "passed",
    "concurrent_source_changes": concurrent,
    "native": native,
    "device": "Not run; user requested no phone operations. No test or application installed.",
    "performance": "Not measured on a device; no numerical speedup claim.",
    "source_sha256": manifest,
}
(OUT / "verification.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({p: r["totals"] for p, r in results.items()}, indent=2))
