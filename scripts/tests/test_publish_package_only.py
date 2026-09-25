"""A package-only run of publish-android.yml must never reach the servers or create a release."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
GATE = "if: env.PUBLISH_UPDATE == 'true'"


def step(workflow, name):
    text = (WORKFLOWS / workflow).read_text()
    marker = "      - name: " + name + "\n"
    assert text.count(marker) == 1, name
    return re.split(r"\n      - (?:name:|uses:)", text.split(marker, 1)[1], maxsplit=1)[0]


def script(block):
    lines = []
    for line in block.split("        run: |\n", 1)[1].splitlines():
        # A job's last step runs into the next job; its script ends at the first shallower line.
        if line.strip() and not line.startswith(" " * 10):
            break
        lines.append(line[10:])
    return "\n".join(lines)


def run_with_fake(tool, body, step_script, env):
    with tempfile.TemporaryDirectory(prefix="yfuse-package-only-") as directory:
        work = Path(directory)
        fake = work / tool
        fake.write_text("#!/usr/bin/env bash\n" + body)
        fake.chmod(0o755)
        output = work / "github-output"
        environment = dict(os.environ, PATH=f"{work}:{os.environ['PATH']}", GITHUB_OUTPUT=str(output), **env)
        result = subprocess.run(["bash", "-c", step_script], cwd=work, env=environment,
                                capture_output=True, text=True)
        return result, output.read_text() if output.exists() else ""


class PublishPackageOnlyTest(unittest.TestCase):
    def test_push_always_publishes_and_a_manual_run_follows_its_switch(self):
        workflow = (WORKFLOWS / "publish-android.yml").read_text()
        self.assertIn("      PUBLISH_UPDATE: ${{ github.event_name == 'push' || inputs.publish }}\n", workflow)
        publish_input = workflow.split("      publish:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertIn("default: true", publish_input)
        self.assertIn("type: boolean", publish_input)

    def test_every_server_facing_step_is_skipped_without_publishing(self):
        self.assertIn(GATE, step("publish-android.yml", "Verify watch server protocol"))
        names = re.findall(r"^      - name: (.+)$", (WORKFLOWS / "publish-android.yml").read_text(), re.M)
        after_artifact = names[names.index("Save release artifact") + 1:]
        self.assertIn("Publish update atomically", after_artifact)
        for name in after_artifact:
            with self.subTest(step=name):
                block = step("publish-android.yml", name)
                # The rollback only runs after a successful activation, which is itself gated.
                self.assertTrue(GATE in block or "steps.activate.outcome == 'success'" in block, block)

    def test_the_saved_artifact_is_required_only_when_it_is_the_deliverable(self):
        block = step("publish-android.yml", "Save release artifact")
        self.assertIn("continue-on-error: ${{ env.PUBLISH_UPDATE == 'true' }}", block)

    def test_gradle_is_told_when_the_package_is_not_published(self):
        build = script(step("publish-android.yml", "Build signed release APK"))
        for publish, package_only in (("true", "false"), ("false", "true")):
            with self.subTest(publish=publish):
                result, _ = run_with_fake("gradlew", 'printf "%s\\n" "$@"\n', build.replace("./gradlew", "gradlew"), {
                    "PUBLISH_UPDATE": publish, "VERSION_CODE": "248", "VERSION_NAME": "1.0.86",
                    "CONFIRM_MDK_DISTRIBUTION_RIGHTS": "true",
                })
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("-PyfusePackageOnly=" + package_only, result.stdout.splitlines())

    def test_a_release_is_created_only_after_a_successful_upload(self):
        release = (WORKFLOWS / "release.yml").read_text()
        self.assertIn("    needs: published\n    if: needs.published.outputs.published == 'true'\n", release)
        check = script(step("release.yml", "Check that the run uploaded the update"))
        # A stand-in gh answers with the run's jobs through the real jq, as gh --jq would.
        fake_gh = 'expr="${@: -1}"\njq -r "$expr" <<<"$RUN_JOBS"\n'
        for conclusion, published in (("success", "true"), ("skipped", "false"), (None, "false")):
            steps = [{"name": "Save release artifact", "conclusion": "success"}]
            if conclusion:
                steps.append({"name": "Publish update atomically", "conclusion": conclusion})
            with self.subTest(conclusion=conclusion):
                result, output = run_with_fake("gh", fake_gh, check, {
                    "RUN_ID": "1", "GITHUB_REPOSITORY": "owner/repo",
                    "RUN_JOBS": json.dumps({"jobs": [{"name": "Build, sign, and publish", "steps": steps}]}),
                })
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("published=" + published + "\n", output)


if __name__ == "__main__":
    unittest.main()
