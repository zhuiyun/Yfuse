"""Exercise the actual signed-workflow shell steps with disposable signing keys."""

import os
import json
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

    def workflow_step(self, name, step_name):
        workflow = (ROOT / ".github" / "workflows" / name).read_text()
        marker = "      - name: " + step_name + "\n"
        self.assertEqual(1, workflow.count(marker))
        tail = workflow.split(marker, 1)[1]
        step = re.split(r"\n      - (?:name:|uses:)", tail, maxsplit=1)[0]
        script = "\n".join(line[10:] for line in step.split("        run: |\n", 1)[1].splitlines())
        return step, script

    def bash_script(self, script):
        if os.name == "nt":
            # Git Bash needs POSIX tool paths; inherited Windows PATH entries may select
            # an incompatible base64/OpenSSL or omit sed entirely.
            return "export PATH=/usr/bin:/mingw64/bin:$PATH\n" + script
        return script

    def run_step(self, name, *, private="", configured="", pinned=""):
        step, script = self.workflow_step(name, "Configure verified update-manifest public key")
        self.assertIn("secrets.UPDATE_MANIFEST_SIGNING_KEY", step)
        self.assertIn("vars.YFUSE_UPDATE_MANIFEST_PUBLIC_KEY", step)
        with tempfile.TemporaryDirectory(prefix="yfuse-update-key-") as directory:
            work = Path(directory)
            (work / "gradle.properties").write_text("yfuse.updateManifestPublicKey=" + pinned + "\n")
            env = dict(os.environ, UPDATE_MANIFEST_SIGNING_KEY=private,
                       YFUSE_UPDATE_MANIFEST_PUBLIC_KEY=configured, GITHUB_ENV=str(work / "job-env"))
            result = subprocess.run(["bash", "-c", self.bash_script(script)], cwd=work, env=env,
                                    capture_output=True, text=True)
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

    def test_packaging_and_publishing_accept_a_missing_update_manifest_key(self):
        for workflow in WORKFLOWS:
            with self.subTest(workflow=workflow):
                result, output = self.run_step(workflow)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("", output)
                self.assertNotIn("::error::", result.stdout + result.stderr)

    def test_signed_artifact_only_workflows_can_use_a_local_public_pin(self):
        for workflow in WORKFLOWS[1:]:
            with self.subTest(workflow=workflow):
                result, output = self.run_step(workflow, pinned=self.public)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn(self.public, output)

    def test_invalid_configured_key_and_mismatched_pin_fail_without_exporting(self):
        for workflow in WORKFLOWS:
            for values in ({"configured": "invalid-key"}, {"private": self.private.decode(), "pinned": "wrong-key"},
                           {"configured": self.public, "pinned": "different-key"}):
                with self.subTest(workflow=workflow, case=tuple(values)):
                    result, output = self.run_step(workflow, **values)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual("", output)

    def test_publishing_a_pinned_client_requires_the_matching_private_signing_key(self):
        for values in ({"pinned": self.public}, {"configured": self.public}):
            with self.subTest(case=tuple(values)):
                result, output = self.run_step(WORKFLOWS[0], **values)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", output)
                self.assertIn("pinned clients cannot accept the update", result.stdout)

    def test_no_private_key_keeps_both_published_manifests_without_a_signature(self):
        _, script = self.workflow_step(WORKFLOWS[0], "Create update package")
        # Execute the actual optional-signing section against already-created manifests.
        # The package's existing APK copy and jq metadata validation are unchanged.
        signing = script[script.index("# Manifest signatures are optional;"):]
        signing = signing.split("\n\njq -e \\\n", 1)[0]
        self.assertIn('if [[ -n "${UPDATE_MANIFEST_SIGNING_KEY:-}" ]]', signing)
        env = dict(os.environ)
        env.pop("UPDATE_MANIFEST_SIGNING_KEY", None)
        with tempfile.TemporaryDirectory(prefix="yfuse-unsigned-manifest-") as directory:
            work = Path(directory)
            output = work / "build" / "update"
            output.mkdir(parents=True)
            manifests = {}
            for name, origin in (("update.json", "http://legacy.example"),
                                 ("update-v2.json", "https://update.example")):
                content = json.dumps(dict(versionCode=42, versionName="1.2.3",
                                          apkUrl=origin + "/Yfuse-42-1.2.3.apk",
                                          sha256="a" * 64, size=123, notes="Update notes"))
                (output / name).write_text(content)
                manifests[name] = content
            result = subprocess.run(["bash", "-c", self.bash_script("set -euo pipefail\n" + signing)],
                                    cwd=work, env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            for name, original in manifests.items():
                actual = (output / name).read_text()
                self.assertEqual(original, actual)
                self.assertNotIn("signature", json.loads(actual))

    def test_changed_workflow_shell_steps_are_syntactically_valid(self):
        steps = [(name, "Configure verified update-manifest public key") for name in WORKFLOWS]
        steps.append((WORKFLOWS[0], "Create update package"))
        for name, step_name in steps:
            with self.subTest(workflow=name, step=step_name):
                _, script = self.workflow_step(name, step_name)
                result = subprocess.run(["bash", "-n"], input=script, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
