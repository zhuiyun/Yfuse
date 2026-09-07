"""Exercise the actual signed-workflow shell steps with disposable signing keys."""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = (
    "publish-android.yml", "sign-android-branch.yml", "repackage-android-signed.yml",
    "ycore-audio-repair.yml", "subtitle-regex-hotfix.yml", "tv-release.yml",
)


class UpdateManifestKeyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.private = subprocess.check_output(["openssl", "genpkey", "-algorithm", "Ed25519"])
        public_der = subprocess.check_output(
            ["openssl", "pkey", "-pubout", "-outform", "DER"], input=cls.private,
        )
        import base64
        cls.public = base64.b64encode(public_der).decode()

    def run_step(self, name, *, private="", configured="", pinned=""):
        workflow = (ROOT / ".github" / "workflows" / name).read_text()
        marker = "      - name: Configure verified update-manifest public key\n"
        self.assertEqual(1, workflow.count(marker))
        tail = workflow.split(marker, 1)[1]
        step = re.split(r"\n      - (?:name:|uses:)", tail, maxsplit=1)[0]
        self.assertIn("secrets.UPDATE_MANIFEST_SIGNING_KEY", step)
        self.assertIn("vars.YFUSE_UPDATE_MANIFEST_PUBLIC_KEY", step)
        script = "\n".join(line[10:] for line in step.split("        run: |\n", 1)[1].splitlines())
        with tempfile.TemporaryDirectory(prefix="yfuse-update-key-") as directory:
            work = Path(directory)
            (work / "gradle.properties").write_text("yfuse.updateManifestPublicKey=" + pinned + "\n")
            env = dict(os.environ, UPDATE_MANIFEST_SIGNING_KEY=private,
                       YFUSE_UPDATE_MANIFEST_PUBLIC_KEY=configured, GITHUB_ENV=str(work / "job-env"))
            result = subprocess.run(["bash", "-c", script], cwd=work, env=env, capture_output=True, text=True)
            output = (work / "job-env").read_text() if (work / "job-env").exists() else ""
            return result, output

    def test_every_signed_workflow_derives_and_exports_the_same_public_key(self):
        for workflow in WORKFLOWS:
            with self.subTest(workflow=workflow):
                result, output = self.run_step(workflow, private=self.private.decode())
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("YFUSE_UPDATE_MANIFEST_PUBLIC_KEY=" + self.public + "\n", output)
                self.assertNotIn("PRIVATE KEY", result.stdout + result.stderr + output)

    def test_signed_artifact_only_workflows_can_use_a_public_key_without_the_private_key(self):
        for workflow in WORKFLOWS[1:]:
            with self.subTest(workflow=workflow):
                result, output = self.run_step(workflow, configured=self.public)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn(self.public, output)

    def test_artifact_packaging_does_not_require_an_update_manifest_key(self):
        for workflow in WORKFLOWS[1:]:
            with self.subTest(workflow=workflow):
                result, output = self.run_step(workflow)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("YFUSE_UPDATE_MANIFEST_PUBLIC_KEY=\n", output)

    def test_invalid_configured_key_and_mismatched_pin_fail_without_exporting(self):
        for workflow in WORKFLOWS:
            for values in ({"configured": "invalid-key"}, {"private": self.private.decode(), "pinned": "wrong-key"},
                           {"configured": self.public, "pinned": "different-key"}):
                with self.subTest(workflow=workflow, case=tuple(values)):
                    result, output = self.run_step(workflow, **values)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual("", output)

    def test_publishing_still_requires_the_private_signing_key(self):
        for values in ({}, {"configured": self.public}):
            with self.subTest(case=tuple(values)):
                result, output = self.run_step(WORKFLOWS[0], **values)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", output)


if __name__ == "__main__":
    unittest.main()
