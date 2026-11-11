"""Keep the tested native artifact and delayed release source identity inseparable."""
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]


def workflow(name):
    return yaml.safe_load((ROOT / ".github/workflows" / name).read_text())


class SourcePlaybackWorkflowTest(unittest.TestCase):
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
