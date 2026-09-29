"""publish-android.yml keeps each production secret in the one job that uses it."""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "publish-android.yml"
CERT = "373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84"
# Real `apksigner verify --verbose --print-certs` output for a published production APK.
PUBLISHED_SIGNATURE = ROOT / "audit" / "releases" / "20260923-performance-1.0.81" / "signature-full.txt"
KEYSTORE = {"ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"}
SECRETS_BY_JOB = {
    "request": {"UPDATE_MANIFEST_SIGNING_KEY"},
    "gates": set(),
    "build": {"TMDB_TOKEN"},
    "sign": KEYSTORE,
    "deploy": {"UPDATE_MANIFEST_SIGNING_KEY", "DEPLOY_SSH_PRIVATE_KEY", "DEPLOY_KNOWN_HOSTS"},
}
SECRET_REFERENCE = re.compile(r"secrets\.([A-Z0-9_]+)")


def jobs():
    header, body = WORKFLOW.read_text().split("\njobs:\n", 1)
    parts = re.split(r"(?m)^  ([a-z][a-z0-9_-]*):\n", body)
    return header, dict(zip(parts[1::2], parts[2::2]))


def step(name):
    text = WORKFLOW.read_text()
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


def outputs(text):
    values, lines = {}, iter(text.splitlines())
    for line in lines:
        name, separator, value = line.partition("=")
        if separator and "<<" not in name:
            values[name] = value
            continue
        name, delimiter = line.split("<<", 1)
        collected = []
        for value_line in lines:
            if value_line == delimiter:
                break
            collected.append(value_line)
        values[name] = "\n".join(collected)
    return values


def fake_tool(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + body)
    path.chmod(0o755)


class PublishJobSplitTest(unittest.TestCase):
    def run_step(self, name, work, env, drop=()):
        environment = {key: value for key, value in os.environ.items()
                       if key not in drop and not key.startswith("GITHUB_")}
        environment.update(env, GITHUB_OUTPUT=str(work / "github-output"), RUNNER_TEMP=str(work / "tmp"))
        (work / "tmp").mkdir(exist_ok=True)
        result = subprocess.run(["bash", "-c", script(step(name))], cwd=work, env=environment,
                                capture_output=True, text=True)
        written = work / "github-output"
        return result, outputs(written.read_text()) if written.exists() else {}

    def test_each_production_secret_is_referenced_only_by_the_job_that_uses_it(self):
        header, job_blocks = jobs()
        self.assertIsNone(SECRET_REFERENCE.search(header))
        self.assertEqual(set(SECRETS_BY_JOB), set(job_blocks))
        for name, block in job_blocks.items():
            with self.subTest(job=name):
                self.assertEqual(SECRETS_BY_JOB[name], set(SECRET_REFERENCE.findall(block)))
                self.assertNotIn("secrets: inherit", block)
                # A secret goes to the one step that needs it, never to a whole job's environment.
                for line in block.splitlines():
                    if SECRET_REFERENCE.search(line):
                        self.assertTrue(line.startswith(" " * 10), line)
                if SECRETS_BY_JOB[name]:
                    self.assertIn("    environment:\n      name: production\n", block)
                # Package-only runs build, sign and save the package through the same jobs.
                self.assertNotRegex(block, r"(?m)^    if:")

    def test_signing_waits_for_the_gates_and_publishing_follows_signing(self):
        _, job_blocks = jobs()
        self.assertRegex(job_blocks["sign"], r"(?m)^    needs: \[request, gates, build\]$")
        self.assertRegex(job_blocks["deploy"], r"(?m)^    needs: \[request, sign\]$")
        for name in ("Save release artifact", "Publish update atomically"):
            owners = [job for job, block in job_blocks.items() if f"      - name: {name}\n" in block]
            self.assertEqual(["deploy"], owners, name)
        for removed in ("Run unit tests", "Run Android lint", "Reject new ktlint violations",
                        "Configure release signing", "keystore.properties", "signing/yfuse-release.jks"):
            self.assertNotIn(removed, WORKFLOW.read_text())

    def test_the_request_hands_over_validated_values_and_requires_a_key_to_publish(self):
        public_key = "MCowBQYDK2VwAyEA" + "A" * 43 + "="
        request = {
            "PUBLISH_UPDATE": "true", "VERSION_CODE": "254", "VERSION_NAME": "1.0.92",
            "CONFIRM_MDK_DISTRIBUTION_RIGHTS": "true", "UPDATE_BASE_URL": "https://update.example/yfuse",
            "DEPLOY_HOST": "203.0.113.7", "DEPLOY_USER": "yfuse-deploy", "DEPLOY_PORT": "22",
            "DEPLOY_REMOTE_DIR": "/srv/yfuse-update/yfuse", "RELEASE_NOTES": "First line\nrelease_notes<<x\nLast",
        }
        name = "Hand the verified request to the release jobs"
        with tempfile.TemporaryDirectory(prefix="yfuse-request-") as directory:
            result, handed = self.run_step(name, Path(directory),
                                           dict(request, YFUSE_UPDATE_MANIFEST_PUBLIC_KEY=public_key))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"publish": "true", "version_code": "254", "version_name": "1.0.92",
                          "confirm_mdk_distribution_rights": "true",
                          "update_base_url": "https://update.example/yfuse", "deploy_host": "203.0.113.7",
                          "deploy_user": "yfuse-deploy", "deploy_port": "22",
                          "deploy_remote_dir": "/srv/yfuse-update/yfuse", "update_manifest_public_key": public_key,
                          "release_notes": request["RELEASE_NOTES"]}, handed)

        for publish, accepted in (("true", False), ("false", True)):
            with self.subTest(publish=publish), tempfile.TemporaryDirectory(prefix="yfuse-request-") as directory:
                result, handed = self.run_step(name, Path(directory), dict(request, PUBLISH_UPDATE=publish),
                                               drop=("YFUSE_UPDATE_MANIFEST_PUBLIC_KEY",))
                if accepted:
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertIn("::warning::", result.stdout)
                    self.assertEqual("", handed["update_manifest_public_key"])
                else:
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("CI refuses to publish", result.stdout)
                    self.assertEqual({}, handed)

    def signing_fixture(self, work):
        sdk = work / "sdk" / "build-tools" / "37.0.0"
        fake_tool(sdk / "zipalign", 'printf "zipalign %s\\n" "$*" >> "$CALL_LOG"\ncp "${@: -2:1}" "${@: -1}"\n')
        fake_tool(sdk / "apksigner", "\n".join((
            'printf "apksigner %s\\n" "$*" >> "$CALL_LOG"',
            'out=""; keystore=""',
            'while (( $# )); do',
            '  case "$1" in',
            '    --out) out="$2"; shift 2 ;;',
            '    --ks) keystore="$2"; shift 2 ;;',
            '    *) input="$1"; shift ;;',
            '  esac',
            'done',
            '[[ "$(cat "$keystore")" == "keystore-bytes" ]]',
            '[[ "$ANDROID_KEYSTORE_PASSWORD" == "store-secret" && "$ANDROID_KEY_PASSWORD" == "key-secret" ]]',
            'printf "%s" "$keystore" > "$KEYSTORE_SEEN"',
            'cp "$input" "$out"',
            "")))
        fake_tool(work / "bin" / "keytool", "\n".join((
            'printf "keytool %s\\n" "$*" >> "$CALL_LOG"',
            '[[ "$ANDROID_KEYSTORE_PASSWORD" == "store-secret" ]]',
            'printf "Certificate fingerprints:\\n\\t SHA1: 00\\n\\t SHA256: %s\\n" "$FAKE_CERT"',
            "")))
        release = work / "build" / "release" / "composeApp-release.apk"
        release.parent.mkdir(parents=True)
        release.write_bytes(b"release apk bytes")
        import base64
        return {
            "PATH": f"{work / 'bin'}:{os.environ['PATH']}", "ANDROID_HOME": str(work / "sdk"),
            "CALL_LOG": str(work / "calls.log"), "KEYSTORE_SEEN": str(work / "keystore-path"),
            "RELEASE_APK": str(release), "CONFIRM_MDK_DISTRIBUTION_RIGHTS": "true",
            "EXPECTED_ANDROID_CERT_SHA256": CERT, "FAKE_CERT": ":".join(re.findall("..", CERT.upper())),
            "ANDROID_KEYSTORE_BASE64": base64.b64encode(b"keystore-bytes").decode(),
            "ANDROID_KEYSTORE_PASSWORD": "store-secret", "ANDROID_KEY_ALIAS": "yfuse",
            "ANDROID_KEY_PASSWORD": "key-secret",
        }

    def test_signing_applies_the_published_schemes_and_removes_the_keystore(self):
        with tempfile.TemporaryDirectory(prefix="yfuse-sign-") as directory:
            work = Path(directory)
            env = self.signing_fixture(work)
            result, _ = self.run_step("Sign release APK", work, env)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(b"release apk bytes", (work / "build/signed/composeApp-release.apk").read_bytes())
            keystore = Path((work / "keystore-path").read_text())
            self.assertTrue(keystore.is_relative_to(work / "tmp"))
            self.assertEqual([], list((work / "tmp").iterdir()))
            calls = (work / "calls.log").read_text()
            signer = next(line for line in calls.splitlines() if line.startswith("apksigner "))
            for flag in ("--v1-signing-enabled false", "--v2-signing-enabled true", "--v3-signing-enabled false",
                         "--v4-signing-enabled false", "--ks-pass env:ANDROID_KEYSTORE_PASSWORD",
                         "--key-pass env:ANDROID_KEY_PASSWORD", "--ks-key-alias yfuse"):
                self.assertIn(flag, signer)
            self.assertIn("-storepass:env ANDROID_KEYSTORE_PASSWORD", calls)
            self.assertNotIn("store-secret", calls)
            self.assertNotIn("key-secret", calls)

    def test_signing_refuses_an_unconfirmed_package_a_missing_secret_or_another_certificate(self):
        for change in ({"CONFIRM_MDK_DISTRIBUTION_RIGHTS": "false"}, {"ANDROID_KEY_PASSWORD": ""},
                       {"FAKE_CERT": "AA:" * 31 + "AA"}):
            with self.subTest(change=tuple(change)), tempfile.TemporaryDirectory(prefix="yfuse-sign-") as directory:
                work = Path(directory)
                result, _ = self.run_step("Sign release APK", work, dict(self.signing_fixture(work), **change))
                self.assertNotEqual(0, result.returncode)
                self.assertIn("::error::", result.stdout)
                self.assertFalse((work / "build/signed").exists())
                self.assertEqual([], list((work / "tmp").iterdir()))

    def test_the_signed_apk_must_carry_exactly_the_published_signature(self):
        published = PUBLISHED_SIGNATURE.read_text()
        variants = {
            "published": (published, True),
            "v3 added": (published.replace("(APK Signature Scheme v3): false", "(APK Signature Scheme v3): true"),
                         False),
            "v1 added": (published.replace("(JAR signing): false", "(JAR signing): true"), False),
            "second signer": (published.replace("Number of signers: 1", "Number of signers: 2"), False),
            "other certificate": (published.replace(CERT, "0" * 64), False),
        }
        for label, (signature, accepted) in variants.items():
            with self.subTest(label), tempfile.TemporaryDirectory(prefix="yfuse-verify-") as directory:
                work = Path(directory)
                (work / "signature.txt").write_text(signature)
                sdk = work / "sdk" / "build-tools" / "37.0.0"
                fake_tool(sdk / "apksigner", 'cat "$SIGNATURE"\n')
                fake_tool(sdk / "zipalign", '[[ "$1 $2 $3 $4" == "-c -v 4 build/signed/composeApp-release.apk" ]]\n')
                fake_tool(sdk / "aapt", "echo \"package: name='com.yfuse' versionCode='254' versionName='1.0.92'\"\n")
                apk = work / "build" / "signed" / "composeApp-release.apk"
                apk.parent.mkdir(parents=True)
                apk.write_bytes(b"signed apk bytes")
                result, handed = self.run_step("Verify APK metadata and signing certificate", work, {
                    "ANDROID_HOME": str(work / "sdk"), "SIGNATURE": str(work / "signature.txt"),
                    "VERSION_CODE": "254", "VERSION_NAME": "1.0.92", "EXPECTED_ANDROID_CERT_SHA256": CERT,
                    "APK_SIZE_BUDGET_BYTES": "30000000", "GITHUB_RUN_ATTEMPT": "2",
                })
                if accepted:
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual("android-release-signed-2", handed["artifact"])
                    self.assertEqual(str(len(b"signed apk bytes")), handed["size"])
                    self.assertRegex(handed["sha256"], r"^[0-9a-f]{64}$")
                else:
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual({}, handed)


if __name__ == "__main__":
    unittest.main()
