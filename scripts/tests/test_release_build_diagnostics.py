"""Retain exact release R8 evidence without weakening gates or publishing raw inputs."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = yaml.safe_load((ROOT / ".github/workflows/publish-android.yml").read_text())
BUILD = WORKFLOW["jobs"]["build"]
STEPS = {step["name"]: step for step in BUILD["steps"]}
IDENTITY = STEPS["Record release build diagnostic identity"]
UPLOAD = STEPS["Keep release build diagnostics"]
MAPPING = "composeApp/build/outputs/mapping/release/mapping.txt"
APK = "build/compact/composeApp-release.apk"
IDENTITY_PATH = "build/release-diagnostics/identity.json"
EXPECTED_FILES = {
    IDENTITY_PATH,
    "build/release-diagnostics/dex-verification.txt",
    "build/release-diagnostics/playback-package-verification.txt",
    MAPPING,
    "composeApp/build/outputs/mapping/release/configuration.txt",
    "composeApp/build/outputs/mapping/release/seeds.txt",
    "composeApp/build/outputs/mapping/release/usage.txt",
    "composeApp/build/outputs/apk/release/output-metadata.json",
    "composeApp/build/outputs/logs/manifest-merger-release-report.txt",
    "composeApp/build/reports/lint-results-release-fatal.html",
    "composeApp/build/reports/lint-results-release-fatal.xml",
    "composeApp/build/reports/lint-results-release-fatal.txt",
    "build/reports/problems/problems-report.html",
}


def write(work, path, contents):
    target = work / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(contents)
    return target


def run_script(work, script, extra_env=None):
    env = dict(os.environ, SOURCE_COMMIT="a" * 40, GITHUB_SHA="b" * 40,
               GITHUB_REPOSITORY="zhuiyun/Yfuse", GITHUB_RUN_ID="12345", GITHUB_RUN_ATTEMPT="2",
               VERSION_CODE="310", VERSION_NAME="2.0.1", BUILD_STATUS="failure",
               DO_NOT_EXPORT_SECRET="must-never-appear-in-diagnostics")
    env.update(extra_env or {})
    return subprocess.run(["bash", "-c", script], cwd=work, env=env, capture_output=True, text=True)


class ReleaseBuildDiagnosticsTest(unittest.TestCase):
    def test_retention_runs_on_failure_but_never_suppresses_errors(self):
        self.assertNotIn("secrets.", str(BUILD))
        self.assertFalse(BUILD.get("continue-on-error", False))
        for step in BUILD["steps"]:
            self.assertFalse(step.get("continue-on-error", False), step["name"])
        for step in (IDENTITY, UPLOAD):
            self.assertEqual("always()", step["if"])
        self.assertEqual("${{ job.status }}", IDENTITY["env"]["BUILD_STATUS"])
        self.assertIn("set -euo pipefail", IDENTITY["run"])
        self.assertEqual("error", UPLOAD["with"]["if-no-files-found"])
        self.assertEqual(7, UPLOAD["with"]["retention-days"])
        self.assertFalse(UPLOAD["with"]["overwrite"])
        self.assertTrue(UPLOAD["uses"].startswith("actions/upload-artifact@"))
        self.assertEqual(
            "android-release-diagnostics-${{ env.SOURCE_COMMIT }}-${{ github.run_id }}-${{ github.run_attempt }}",
            UPLOAD["with"]["name"],
        )
        self.assertEqual([IDENTITY, UPLOAD], BUILD["steps"][-2:])

    def test_upload_is_an_exact_diagnostics_only_file_allowlist(self):
        self.assertEqual(EXPECTED_FILES, set(UPLOAD["with"]["path"].splitlines()))
        self.assertNotIn(APK, EXPECTED_FILES)
        self.assertFalse(UPLOAD["with"].get("include-hidden-files", False))
        # The separate build artifact still contains precisely the APK verified for signing.
        apk_upload = STEPS["Keep the release APK for signing"]
        self.assertNotIn("if", apk_upload)
        self.assertEqual("${{ steps.built.outputs.path }}", apk_upload["with"]["path"])
        self.assertEqual("${{ steps.built.outputs.artifact }}", BUILD["outputs"]["artifact"])
        for name, job in WORKFLOW["jobs"].items():
            if name != "build":
                self.assertNotIn("release-diagnostics", str(job), name)
        public = WORKFLOW["jobs"]["github-release"]
        downloads = [step["with"]["name"] for step in public["steps"]
                     if step.get("uses", "").startswith("actions/download-artifact@")]
        self.assertEqual(["${{ needs.sign.outputs.artifact }}", "${{ needs.request.outputs.metadata_artifact }}"], downloads)
        self.assertEqual(["request", "sign", "smoke", "release-identity"], public["needs"])

    def test_identity_links_exact_source_run_version_mapping_and_apk_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            payloads = {MAPPING: b"original.Player -> a.b:\n", APK: b"exact unsigned release"}
            for path, data in payloads.items():
                write(work, path, data)
            result = run_script(work, IDENTITY["run"])
            self.assertEqual(0, result.returncode, result.stderr)
            report_text = (work / IDENTITY_PATH).read_text()
            self.assertNotIn("must-never-appear", report_text)
            self.assertEqual({
                "sourceCommit": "a" * 40, "workflowCommit": "b" * 40,
                "repository": "zhuiyun/Yfuse", "runId": "12345", "runAttempt": "2",
                "versionCode": "310", "versionName": "2.0.1", "buildStatus": "failure",
                "files": {path: {"sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
                          for path, data in payloads.items()},
            }, json.loads(report_text))
            self.assertNotIn("::warning::", result.stdout)

    def test_failure_before_r8_still_records_identity_and_warns_about_mapping(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            result = run_script(work, IDENTITY["run"])
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("::warning::No release R8 mapping", result.stdout)
            self.assertIn("before R8 completed", result.stdout)
            self.assertEqual({}, json.loads((work / IDENTITY_PATH).read_text())["files"])

    def test_identity_io_errors_are_not_masked(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            write(work, "build/release-diagnostics", b"not a directory")
            result = run_script(work, IDENTITY["run"])
            self.assertNotEqual(0, result.returncode)

    def test_verification_reports_preserve_verifier_and_tee_failures(self):
        for verifier, report, python in (
            ("scripts/verify-release-dex.sh", "dex-verification.txt", False),
            ("scripts/verify-playback-package.py", "playback-package-verification.txt", True),
        ):
            for failure in ("verifier", "report"):
                with self.subTest(verifier=verifier, failure=failure), tempfile.TemporaryDirectory() as directory:
                    work = Path(directory)
                    write(work, APK, b"APK")
                    write(work, "composeApp/build/outputs/apk/release/output-metadata.json", b"{}")
                    code = 41 if failure == "verifier" else 0
                    body = (f'import sys\nprint("verification evidence", file=sys.stderr)\nsys.exit({code})\n'
                            if python else f'#!/bin/bash\necho "verification evidence" >&2\nexit {code}\n')
                    write(work, verifier, body.encode()).chmod(0o755)
                    step = STEPS["Verify release APK contents" if python else "Reject DEX code ART would not verify"]
                    # Stop after this check's pipeline, before unrelated Android SDK checks.
                    script = step["run"].split("\nactual_version_code=", 1)[0]
                    self.assertIn("set -euo pipefail", script)
                    path = work / "build/release-diagnostics" / report
                    if failure == "report":
                        path.mkdir(parents=True)  # tee cannot overwrite a directory.
                    result = run_script(work, script + '\necho "must not reach signing"\n')
                    self.assertNotEqual(0, result.returncode)
                    self.assertNotIn("must not reach signing", result.stdout)
                    if failure == "verifier":
                        self.assertEqual("verification evidence\n", path.read_text())


if __name__ == "__main__":
    unittest.main()
