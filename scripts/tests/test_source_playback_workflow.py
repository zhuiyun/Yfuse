"""Keep the tested native artifact and delayed release source identity inseparable."""
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]


def workflow(name):
    return yaml.safe_load((ROOT / ".github/workflows" / name).read_text())


class SourcePlaybackWorkflowTest(unittest.TestCase):
    def test_auxiliary_android_jobs_consume_their_own_verified_native_build(self):
        expected_inputs = {
            "artifact-id": "${{ needs.native.outputs.artifact_id }}",
            "run-id": "${{ github.run_id }}",
            "aar-sha256": "${{ needs.native.outputs.aar_sha256 }}",
            "provenance-sha256": "${{ needs.native.outputs.provenance_sha256 }}",
            "source-commit": "${{ needs.native.outputs.source_commit }}",
        }
        for filename, consumer in (("codeql.yml", "analyze"), ("validate-dolby-fixes.yml", "validate"),
                                   ("tv-quality-gates.yml", "tv")):
            with self.subTest(workflow=filename):
                config = workflow(filename)
                jobs = config["jobs"]
                self.assertEqual("./.github/workflows/build-ycore-source.yml", jobs["native"]["uses"])
                self.assertNotIn("secrets", jobs["native"])
                self.assertEqual("native", jobs[consumer]["needs"])
                permissions = jobs[consumer].get("permissions", config["permissions"])
                self.assertEqual("read", permissions["actions"])
                steps = jobs[consumer]["steps"]
                inputs = [step for step in steps if step.get("uses") == "./.github/actions/prepare-source-playback"]
                self.assertEqual(1, len(inputs))
                self.assertEqual(expected_inputs, inputs[0]["with"])
                self.assertEqual({}, inputs[0].get("env", {}))
                self.assertFalse(any("fetch-engines.sh" in step.get("run", "") for step in steps))
                self.assertNotIn("secrets.YCORE_NATIVE_AAR_URL", str(config))
                self.assertNotIn("secrets.SENPLAYER_SOURCE_APK_URL", str(config))

    def test_codeql_preserves_all_scanners_and_prepares_inputs_before_extraction(self):
        jobs = workflow("codeql.yml")["jobs"]
        steps = jobs["analyze"]["steps"]
        preparation = next(i for i, step in enumerate(steps)
                           if step.get("uses") == "./.github/actions/prepare-source-playback")
        initialization = next(i for i, step in enumerate(steps)
                              if step.get("uses", "").startswith("github/codeql-action/init@"))
        self.assertLess(preparation, initialization)
        self.assertEqual("java-kotlin", steps[initialization]["with"]["languages"])
        self.assertEqual("manual", steps[initialization]["with"]["build-mode"])
        compilation = next(step["run"] for step in steps if "compileDebugKotlin" in step.get("run", ""))
        for task in (":composeApp:compileDebugKotlin", ":tvApp:compileDebugKotlin", ":watchTogetherServer:classes"):
            self.assertIn(task, compilation)
        self.assertNotIn("needs", jobs["analyze-sources"])
        self.assertEqual(["actions", "python"], jobs["analyze-sources"]["strategy"]["matrix"]["language"])

    def test_dolby_validation_tracks_the_source_runtime_pipeline(self):
        config = workflow("validate-dolby-fixes.yml")
        trigger = config.get("on", config.get(True))
        paths = trigger["push"]["paths"]
        for path in (".github/workflows/build-ycore-source.yml", ".github/actions/prepare-source-playback/**",
                     "senplayerBridge/**", "scripts/ycore-source-receipt.py", "scripts/native-build-download.py"):
            self.assertIn(path, paths)

    def test_tv_push_and_pr_paths_track_the_source_runtime_pipeline(self):
        config = workflow("tv-quality-gates.yml")
        trigger = config.get("on", config.get(True))
        self.assertEqual(["master", "feature/**", "codex/**"], trigger["push"]["branches"])
        self.assertIn("workflow_dispatch", trigger)
        for event in ("push", "pull_request"):
            with self.subTest(event=event):
                paths = trigger[event]["paths"]
                for path in (".github/workflows/build-ycore-source.yml", ".github/actions/prepare-source-playback/**",
                             "senplayerBridge/**", "scripts/prepare-playback-inputs.py",
                             "scripts/playback-runtime-inputs.json", "scripts/ycore-source-receipt.py",
                             "scripts/native-build-*.py", "scripts/build-ycore*.sh", "scripts/build-yfuse-mpv*.sh",
                             "scripts/package-ycore-native-aar.py", "scripts/install-ycore-native.sh",
                             "scripts/verify-ycore-native.sh", "scripts/verify-yfuse-mpv-dolby-aar.sh",
                             "scripts/engine-checksums.sha256", "scripts/yfuse-mpv-sources.txt",
                             "scripts/embed-mini-debuginfo.sh", "scripts/test-ycore*", "scripts/native-test-build.sh",
                             "scripts/native/**", "NOTICE", "LICENSE", "docs/third-party-licenses/**",
                             "scripts/tests/test_source_playback_workflow.py"):
                    self.assertIn(path, paths)

    def test_tv_preserves_signing_review_tests_and_all_artifact_gates(self):
        steps = workflow("tv-quality-gates.yml")["jobs"]["tv"]["steps"]
        preparation = next(i for i, step in enumerate(steps)
                           if step.get("uses") == "./.github/actions/prepare-source-playback")
        by_name = {step["name"]: step for step in steps}
        review = by_name["Run TV unit tests during signing review"]
        self.assertEqual("github.ref == 'refs/heads/codex/ycore-sign-20260830'", review["if"])
        self.assertEqual("./gradlew :tvApp:ktlintCheck :tvShared:ktlintCheck :tvShared:testAndroidHostTest --no-daemon",
                         review["run"])
        gated_names = (
            "Run TV unit tests and build debug APK", "Reject DEX code ART would not verify",
            "Verify packaged TV contract", "Upload verified TV APK",
        )
        for name in gated_names:
            with self.subTest(step=name):
                self.assertEqual("github.ref != 'refs/heads/codex/ycore-sign-20260830'", by_name[name]["if"])
                self.assertLess(preparation, steps.index(by_name[name]))
        self.assertLess(preparation, steps.index(review))
        build = by_name[gated_names[0]]["run"]
        for command in (":tvApp:ktlintCheck :tvShared:ktlintCheck", ":tvShared:testAndroidHostTest",
                        ":tvApp:assembleDebug", ":tvApp:assembleRelease -PallowDebugSigning=true",
                        "-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8", "--no-daemon",
                        "test -s tvApp/build/outputs/apk/release/tvApp-release.apk",
                        "scripts/verify-playback-package.py tvApp/build/outputs/apk/release/tvApp-release.apk",
                        "--mapping tvApp/build/outputs/mapping/release/mapping.txt"):
            self.assertIn(command, build)
        dex = by_name[gated_names[1]]["run"]
        for command in ("scripts/verify-release-dex.sh", "--mapping tvApp/build/outputs/mapping/release/mapping.txt",
                        "tvApp/build/outputs/apk/release/tvApp-release.apk"):
            self.assertIn(command, dex)
        artifact = by_name[gated_names[2]]["run"]
        for command in ("scripts/verify-tv-artifact.py", "tvApp/build/outputs/apk/debug/tvApp-debug.apk",
                        "--aapt", "--readelf", "sha256sum build/tv-quality/Yfuse-TV-debug.apk"):
            self.assertIn(command, artifact)
        upload = by_name[gated_names[3]]
        self.assertTrue(upload["uses"].startswith("actions/upload-artifact@"))
        self.assertEqual({"name": "yfuse-tv-debug-${{ github.sha }}", "path": "build/tv-quality/",
                          "if-no-files-found": "error", "retention-days": 7}, upload["with"])
        source = by_name["Verify source TV contract"]["run"]
        for command in ("scripts/verify-tv-source.py", "scripts/verify-tv-artifact.py",
                        "test_verify_tv_artifact.py", "git diff --check"):
            self.assertIn(command, source)

    def test_quality_tests_share_one_secret_free_native_producer(self):
        quality = workflow("quality-gates-v2.yml")
        jobs = quality["jobs"]
        self.assertEqual("./.github/workflows/build-ycore-source.yml", jobs["native"]["uses"])
        self.assertNotIn("secrets", jobs["native"])
        for name in ("verify", "instrumented"):
            self.assertEqual("native", jobs[name]["needs"])
            step = next(step for step in jobs[name]["steps"] if step.get("uses") == "./.github/actions/prepare-source-playback")
            self.assertEqual("${{ needs.native.outputs.artifact_id }}", step["with"]["artifact-id"])
            self.assertEqual("${{ needs.native.outputs.aar_sha256 }}", step["with"]["aar-sha256"])
            self.assertEqual({}, step.get("env", {}))
        approval = jobs["approved-native"]
        self.assertEqual({"native", "verify", "instrumented", "fast-jvm", "fast-contracts", "supply-chain"}, set(approval["needs"]))
        self.assertNotIn("if", approval)
        self.assertEqual("approved-ycore-${{ github.run_attempt }}", approval["steps"][-1]["with"]["name"])

    def test_release_continues_only_a_successful_master_push_without_waiting(self):
        publish = workflow("publish-android.yml")
        trigger = publish.get("on", publish.get(True))
        self.assertNotIn("push", trigger)
        self.assertEqual(["Android quality gates"], trigger["workflow_run"]["workflows"])
        request = publish["jobs"]["request"]
        for required in ("conclusion == 'success'", "event == 'push'", "head_branch == 'master'",
                         "head_repository.full_name == github.repository"):
            self.assertIn(required, request["if"])
        self.assertEqual("${{ github.event.workflow_run.head_sha || github.sha }}", publish["env"]["SOURCE_COMMIT"])
        self.assertEqual(5, publish["jobs"]["gates"]["timeout-minutes"])
        self.assertEqual(["request", "gates"], publish["jobs"]["build"]["needs"])
        for job in publish["jobs"].values():
            for step in job.get("steps", []):
                if step.get("uses", "").startswith("actions/checkout@"):
                    self.assertEqual("${{ env.SOURCE_COMMIT }}", step["with"]["ref"])
        self.assertEqual("${{ github.event.workflow_run.head_sha || github.sha }}", publish["jobs"]["smoke"]["with"]["source_sha"])

    def test_release_build_downloads_the_approved_attempt_and_never_rebuilds_ycore(self):
        job = workflow("publish-android.yml")["jobs"]["build"]
        steps = job["steps"]
        approved = next(step for step in steps if step["name"] == "Download the quality-approved native identity")
        self.assertEqual("approved-ycore-${{ needs.gates.outputs.run_attempt }}", approved["with"]["name"])
        self.assertEqual("${{ needs.gates.outputs.run_id }}", approved["with"]["run-id"])
        native = next(step for step in steps if step.get("uses") == "./.github/actions/prepare-source-playback")
        self.assertEqual({}, native.get("env", {}))
        self.assertEqual("${{ steps.native.outputs.artifact_id }}", native["with"]["artifact-id"])
        build = next(step for step in steps if step["name"] == "Build release APK without production signing")
        self.assertIn('-PyfuseBuildRevision="$SOURCE_COMMIT"', build["run"])
        self.assertFalse(any("build-ycore-native.sh" in step.get("run", "") for step in steps))

    def test_native_consumer_requires_external_identity_and_readonly_artifact_access(self):
        action = yaml.safe_load((ROOT / ".github/actions/prepare-source-playback/action.yml").read_text())
        for value in action["inputs"].values():
            self.assertTrue(value["required"])
        download, verify = action["runs"]["steps"]
        self.assertEqual("${{ inputs.artifact-id }}", download["with"]["artifact-ids"])
        self.assertEqual("${{ inputs.run-id }}", download["with"]["run-id"])
        self.assertIn("YCORE_SOURCE_RECEIPT", verify["env"])
        self.assertIn("YCORE_SOURCE_AAR_SHA256", verify["env"])
        self.assertIn("YCORE_SOURCE_PROVENANCE_SHA256", verify["env"])
        self.assertIn("YCORE_SOURCE_COMMIT", verify["env"])
        self.assertIn("verify-ycore-native.sh", verify["run"])


if __name__ == "__main__":
    unittest.main()
