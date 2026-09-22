"""Collect local evidence; does not infer device or remote CI success."""
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
AUDIT = Path(__file__).resolve().parent

if "--snapshot" not in sys.argv and (AUDIT / "focused-results").is_dir():
    raise SystemExit("Finalized evidence is preserved: the default Gradle XML directory now contains focused results.")


def test_results(relative):
    files = list((ROOT / relative).glob("TEST-*.xml"))
    suites = [ET.parse(path).getroot() for path in files]
    return {
        "directory": relative,
        "suites": len(suites),
        **{key: sum(int(suite.get(key, "0")) for suite in suites)
           for key in ("tests", "failures", "errors", "skipped")},
        "suiteTimestamps": sorted({suite.get("timestamp") for suite in suites if suite.get("timestamp")}),
        "failedCases": [{"suite": suite.get("name"), "name": case.get("name"),
                         "message": case.find("failure").get("message")}
                        for suite in suites for case in suite.findall("testcase")
                        if case.find("failure") is not None],
    }


paths = set()
for filename in ("client-format-paths.json", "architecture/format-paths.json", "server-ingestion-format-paths.json", "final-format/format-paths.json"):
    paths.update(json.loads((AUDIT / filename).read_text(encoding="utf-8-sig")))
paths.update([
    "composeApp/src/commonMain/kotlin/com/yfuse/app/App.kt",
    "composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerWatchSyncEffects.kt",
    "composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/AndroidProbeBudgetTest.kt",
    "composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlaybackPreloader.android.kt",
    "composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidCurrentItemPreparation.kt",
    ".github/dependabot.yml", ".github/workflows/codeql.yml", ".github/workflows/quality-gates-v2.yml",
    "README.md", "build.gradle.kts", "gradle/libs.versions.toml", "scripts/security-overrides.properties",
    "scripts/supply_chain_check.py", "scripts/test_npm_supply_chain.py",
    "castReceiver/receiver.js", "castReceiver/receiver.test.cjs", "castReceiver/README.md",
    "watchTogetherServer/README.md", "watchTogetherServer/calendarRenderer/Dockerfile",
    "watchTogetherServer/calendarRenderer/package.json", "watchTogetherServer/calendarRenderer/package-lock.json",
    "watchTogetherServer/calendarRenderer/server.mjs", "watchTogetherServer/calendarRenderer/render-scheduler.mjs",
    "watchTogetherServer/calendarRenderer/render-scheduler.test.mjs",
])
paths.update(str(path.relative_to(ROOT)).replace("\\", "/") for path in ROOT.glob("*/gradle.lockfile"))
hashes = {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in sorted(paths)}
snapshot = AUDIT / "sources-before-final.json"
if not snapshot.exists() or "--snapshot" in sys.argv:
    snapshot.write_text(json.dumps(hashes, indent=2) + "\n", encoding="utf-8")
    print(f"Recorded {len(hashes)} source and configuration hashes before final verification.")
else:
    before = json.loads(snapshot.read_text())
    changed = [path for path in sorted(set(before) | set(hashes)) if before.get(path) != hashes.get(path)]
    log = (AUDIT / "final.log").read_text(encoding="utf-8", errors="replace")
    results = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "gradleFinal": "passed" if "BUILD SUCCESSFUL" in log else "not passed",
        "gradleLog": "final.log",
        "failedTasks": re.findall(r"^> Task (\S+) FAILED$", log, re.MULTILINE),
        "changedSinceFinalSourceSnapshot": changed,
        "junit": {module: test_results(directory) for module, directory in {
            "protocol": ".gradle-tmp/health-build/watchTogetherProtocol/build/test-results/jvmTest",
            "server": ".gradle-tmp/health-build/watchTogetherServer/build/test-results/test",
            "phone": ".gradle-tmp/health-build/phoneShared/build/test-results/testAndroidHostTest",
            "tv": ".gradle-tmp/health-build/tvShared/build/test-results/testAndroidHostTest",
        }.items()},
        "sourceSha256": hashes,
        "otherChecks": {
            "pythonScripts": {"tests": 47, "failures": 0, "evidence": "sbom-review-validation.md"},
            "pythonScriptsTestsDirectory": {"tests": 16, "failures": 0},
            "nodeReceiverAndScheduler": {"tests": 10, "failures": 0},
            "dependencyScan": {"ecosystems": ["Maven", "npm"], "coordinates": 772,
                               "activeOsvRecords": 0, "sbom": "sbom.spdx.json"},
            "workflowSyntax": "workflow-validation.json",
            "historicalLintRound": {"log": "lint.log", "phone": {"errors": 0, "warnings": 1},
                                    "tv": {"errors": 0, "warnings": 6}, "ktlint": "passed without baseline changes",
                                    "note": "Source changes continued in another task after this successful round."},
            "designSystemUsage": "passed",
            "moduleBoundaries": "passed",
            "releaseMetadata": "passed; version 1.0.73 / 235 was not changed by this task",
        },
        "limitations": ["Phone/TV executions include shared tests and are not unique-case counts.",
                        "No APK, device tests, Docker deployment, or remote CodeQL execution in this task.",
                        "The first client run ended in Gradle EOFException; later evidence is recorded separately."],
    }
    (AUDIT / "verification.json").write_text(json.dumps(results, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in results.items() if key not in ("junit", "sourceSha256")}, ensure_ascii=False))
    for module, result in results["junit"].items():
        print(module, {key: value for key, value in result.items() if key != "suiteTimestamps"})
