"""Automatic builds preserve upgrade identity, secret isolation and immutable public assets."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import automatic_release_metadata as metadata
import publish_github_build as publisher


class AutomaticMetadataTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git("init", "-q", "-b", "master")
        self.git("config", "user.name", "Release tests")
        self.git("config", "user.email", "release-tests@example.invalid")
        self.write_version(346, "1.9.4")
        self.anchor = self.commit("Last source before a privately delivered 1.9.5")
        self.baseline = {"branch": "master", "commit": self.anchor, "versionCode": 347, "versionName": "1.9.5"}
        (self.root / "config").mkdir()
        (self.root / "config/automatic-release-baseline.json").write_text(json.dumps(self.baseline))
        self.first = self.commit("Enable automatic releases")
        self.environment = {"GITHUB_REF": "refs/heads/master"}

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], text=True, stderr=subprocess.PIPE).strip()

    def write_version(self, code, name):
        (self.root / "version.properties").write_text(f"VERSION_CODE={code}\nVERSION_NAME={name}\n")
        (self.root / "release-notes.txt").write_text(f"{name}\n\nExisting product changes.\n")

    def commit(self, subject):
        self.git("add", ".")
        self.git("commit", "-qm", subject, "--allow-empty")
        return self.git("rev-parse", "HEAD")

    def test_first_build_is_above_private_and_public_source_versions(self):
        self.assertEqual((348, "1.9.6"), metadata.automatic_version(self.root, self.first, self.baseline))

    def test_parallel_and_retried_commits_have_reproducible_increasing_versions(self):
        second = self.commit("Next change")
        self.assertEqual((349, "1.9.7"), metadata.automatic_version(self.root, second, self.baseline))
        # Completion order does not affect identity; an older retry keeps its original version.
        for _ in range(2):
            self.assertEqual((348, "1.9.6"), metadata.automatic_version(self.root, self.first, self.baseline))

    def test_explicit_source_version_jump_is_respected_and_followed_by_increment(self):
        self.write_version(400, "2.0.0")
        jump = self.commit("A deliberate product version jump")
        self.assertEqual((400, "2.0.0"), metadata.automatic_version(self.root, jump, self.baseline))
        next_commit = self.commit("A change without a manual version bump")
        self.assertEqual((401, "2.0.1"), metadata.automatic_version(self.root, next_commit, self.baseline))

    def test_single_digit_carry_and_exhaustion(self):
        self.assertEqual("2.0.0", metadata.version_name(200))
        with self.assertRaises(ValueError):
            metadata.version_name(1000)
        with self.assertRaises(ValueError):
            metadata.version_number("1.9.10")

    def test_branch_and_baseline_guards(self):
        with self.assertRaisesRegex(ValueError, "restricted to master"):
            metadata.prepare(self.root, self.root / "out", self.first, "automatic", {"GITHUB_REF": "refs/heads/feature"})
        with self.assertRaisesRegex(ValueError, "already delivered"):
            metadata.automatic_version(self.root, self.anchor, self.baseline)
        missing = dict(self.baseline, commit="0" * 40)
        with self.assertRaisesRegex(ValueError, "first-parent"):
            metadata.automatic_version(self.root, self.first, missing)

    def test_stages_and_installs_both_files_before_build_and_detects_mutation(self):
        directory = self.root / "build/request"
        request = metadata.prepare(self.root, directory, self.first, "automatic", self.environment)
        env = dict(self.environment, VERSION_CODE="348", VERSION_NAME="1.9.6")
        # Preparing does not alter the source; installing deliberately updates both real files.
        self.assertIn("VERSION_CODE=346", (self.root / "version.properties").read_text())
        metadata.install(self.root, directory, self.first, env)
        self.assertIn("VERSION_CODE=348", (self.root / "version.properties").read_text())
        notes = (self.root / "release-notes.txt").read_text()
        self.assertTrue(notes.startswith("1.9.6\n"))
        self.assertIn("Enable automatic releases", notes)
        self.assertIn("Existing product changes.", notes)
        metadata.verify(self.root, directory, self.first, env)
        self.assertEqual(self.first, request["sourceCommit"])
        (self.root / "release-notes.txt").write_text(notes + "tampered")
        with self.assertRaisesRegex(ValueError, "mutated"):
            metadata.verify(self.root, directory, self.first, env)

    def test_request_identity_and_hash_tampering_are_rejected(self):
        directory = self.root / "build/request"
        metadata.prepare(self.root, directory, self.first, "automatic", self.environment)
        env = dict(VERSION_CODE="348", VERSION_NAME="1.9.6")
        with self.assertRaisesRegex(ValueError, "different source"):
            metadata.validate(directory, "0" * 40, env)
        with self.assertRaisesRegex(ValueError, "verified job outputs"):
            metadata.validate(directory, self.first, dict(env, VERSION_CODE="349"))
        (directory / "release-notes.txt").write_text("1.9.6\nDifferent notes\n")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            metadata.validate(directory, self.first, env)

    def test_manual_build_cannot_go_below_private_baseline(self):
        env = dict(self.environment, VERSION_CODE="346", VERSION_NAME="1.9.4", RELEASE_NOTES="Old")
        with self.assertRaisesRegex(ValueError, "older"):
            metadata.prepare(self.root, self.root / "out", self.first, "manual", env)

    def test_manual_version_cannot_take_a_future_commits_automatic_identity(self):
        env = dict(self.environment, VERSION_CODE="349", VERSION_NAME="1.9.7", RELEASE_NOTES="Future collision")
        with self.assertRaisesRegex(ValueError, "differs"):
            metadata.prepare(self.root, self.root / "out", self.first, "manual", env)


class FakeGitHub:
    def __init__(self):
        self.release = None
        self.files = {}
        self.commands = []
        self.tamper = False
        self.refs = {}

    def __call__(self, *args):
        args = list(map(str, args))
        self.commands.append(args)
        if args[:3] == ["api", "--paginate", "--slurp"]:
            return json.dumps([[self.release] if self.release else []])
        if args[:2] == ["api", "graphql"]:
            return json.dumps({"data": {"repository": {"release": {"databaseId": 1} if self.release else None}}})
        if args[0] == "api" and "matching-refs" in args[1]:
            tag = args[1].split("matching-refs/tags/")[1]
            return json.dumps([value for key, value in self.refs.items() if key.startswith(tag)])
        if args[:3] == ["api", "--method", "POST"]:
            values = dict(value.split("=", 1) for value in args if value.startswith(("ref=", "sha=")))
            tag = values["ref"].removeprefix("refs/tags/")
            if tag in self.refs:
                raise subprocess.CalledProcessError(1, args)
            self.refs[tag] = {"ref": values["ref"], "object": {"type": "commit", "sha": values["sha"]}}
            return json.dumps(self.refs[tag])
        if args[0] == "api":
            return json.dumps(self.release)
        if args[:2] == ["release", "create"]:
            self.release = {"tag_name": args[2], "target_commitish": args[args.index("--target") + 1],
                            "prerelease": False, "draft": True, "assets": [],
                            "body": Path(args[args.index("--notes-file") + 1]).read_text(),
                            "html_url": "https://github.com/zhuiyun/Yfuse/releases/tag/" + args[2]}
            return ""
        if args[:2] == ["release", "upload"]:
            path = Path(args[3])
            self.files[path.name] = path.read_bytes()
            self.release["assets"].append({"name": path.name})
            return ""
        if args[:2] == ["release", "download"]:
            directory = Path(args[args.index("--dir") + 1])
            for name, contents in self.files.items():
                (directory / name).write_bytes(contents + (b"changed" if self.tamper else b""))
            return ""
        if args[:2] == ["release", "edit"]:
            self.release["draft"] = False
            return ""
        raise AssertionError(args)


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "signed.apk"
        self.apk.write_bytes(b"fixture for an already verified signed APK")
        self.request = self.root / "request"
        self.request.mkdir()
        (self.request / "version.properties").write_text("VERSION_CODE=348\nVERSION_NAME=1.9.6\n")
        (self.request / "release-notes.txt").write_text("1.9.6\n\nAutomatic build test.\n")
        self.identity = {"sourceCommit": "a" * 40, "sourceBranch": "master", "versionCode": 348,
                         "versionName": "1.9.6", "releaseNotes": "Automatic build test.", "mode": "automatic",
                         "files": {name: publisher.digest(self.request / name) for name in ("version.properties", "release-notes.txt")}}
        (self.request / "release-request.json").write_text(json.dumps(self.identity))
        self.env = dict(GITHUB_REPOSITORY="zhuiyun/Yfuse", GITHUB_REF="refs/heads/master", GITHUB_SHA="a" * 40,
                        VERSION_CODE="348", VERSION_NAME="1.9.6", SIGNED_SIZE=str(self.apk.stat().st_size),
                        SIGNED_SHA256=publisher.digest(self.apk), SIGNING_CERTIFICATE=publisher.CERTIFICATE)
        self.output = self.root / "dist"

    def test_only_explicit_safe_artifacts_are_prepared(self):
        (self.request / "SenPlayer.apk").write_bytes(b"raw source must stay private")
        info, files = publisher.prepare(self.apk, self.request, self.output, self.env)
        self.assertEqual({"Yfuse-348-1.9.6.apk", "version.properties", "release-notes.txt", "build-info.json", "SHA256SUMS.txt"}, set(files))
        self.assertEqual(set(files), {path.name for path in self.output.iterdir()})
        self.assertEqual(publisher.CERTIFICATE, info["signingCertificateSha256"])

    def test_wrong_branch_key_hash_size_and_manual_request_are_refused(self):
        changes = [dict(GITHUB_REF="refs/heads/feature"), dict(SIGNING_CERTIFICATE="b" * 64),
                   dict(SIGNED_SIZE="1"), dict(SIGNED_SHA256="0" * 64)]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                publisher.prepare(self.apk, self.request, self.output, dict(self.env, **change))
        self.identity["mode"] = "manual"
        (self.request / "release-request.json").write_text(json.dumps(self.identity))
        with self.assertRaisesRegex(ValueError, "Manual artifact-only"):
            publisher.prepare(self.apk, self.request, self.output, self.env)

    def test_complete_release_uploads_verifies_then_publishes_without_latest(self):
        info, files = publisher.prepare(self.apk, self.request, self.output, self.env)
        fake = FakeGitHub()
        with mock.patch.object(publisher, "gh", fake):
            url = publisher.publish(info, files, self.output, self.request)
            self.assertFalse(fake.release["draft"])
            # A retry verifies the existing bytes without mutating the public release.
            count = len(fake.commands)
            self.assertEqual(url, publisher.publish(info, files, self.output, self.request))
            self.assertFalse(any(command[:2] in (["release", "upload"], ["release", "edit"], ["release", "create"])
                                 for command in fake.commands[count:]))
        self.assertTrue(url.endswith("/build-348"))
        for command in fake.commands:
            if command[:2] in (["release", "create"], ["release", "edit"]):
                self.assertIn("--latest=false", command)

    def test_corrupted_remote_bytes_never_become_public(self):
        info, files = publisher.prepare(self.apk, self.request, self.output, self.env)
        fake = FakeGitHub()
        fake.tamper = True
        with mock.patch.object(publisher, "gh", fake), self.assertRaisesRegex(ValueError, "Remote release bytes"):
            publisher.publish(info, files, self.output, self.request)
        self.assertTrue(fake.release["draft"])

    def test_atomic_claims_cover_both_code_and_name_after_rewritten_history(self):
        fake = FakeGitHub()
        with mock.patch.object(publisher, "gh", fake):
            publisher.claim_tag("build-348", "a" * 40)
            publisher.claim_tag("build-version-1.9.6", "a" * 40)
            publisher.claim_tag("build-400", "b" * 40)
            with self.assertRaisesRegex(ValueError, "another commit"):
                publisher.claim_tag("build-version-1.9.6", "b" * 40)
            with self.assertRaisesRegex(ValueError, "another commit"):
                publisher.claim_tag("build-348", "b" * 40)

    def test_version_collisions_refuse_other_commits_but_allow_out_of_order_distinct_versions(self):
        info, _ = publisher.prepare(self.apk, self.request, self.output, self.env)
        for body in ("versionCode 348", "versionCode 349 · versionName 1.9.6"):
            with self.subTest(body=body), self.assertRaisesRegex(ValueError, "another commit"):
                publisher.reject_collisions(info, [{"body": body, "target_commitish": "b" * 40, "tag_name": "some-tag"}])
        newer = [{"body": "versionCode 349 · versionName 1.9.7", "target_commitish": "b" * 40, "tag_name": "build-349"}]
        with mock.patch.object(publisher, "json_api", return_value={"status": "behind"}):
            publisher.reject_collisions(info, newer)
        for status in ("ahead", "identical", "diverged"):
            with self.subTest(status=status), mock.patch.object(publisher, "json_api", return_value={"status": status}), self.assertRaisesRegex(ValueError, "higher delivered"):
                publisher.reject_collisions(info, newer)

    def test_foreign_asset_or_modified_published_bytes_fail_closed(self):
        info, files = publisher.prepare(self.apk, self.request, self.output, self.env)
        fake = FakeGitHub()
        with mock.patch.object(publisher, "gh", fake):
            publisher.publish(info, files, self.output, self.request)
            fake.release["assets"].append({"name": "SenPlayer.apk"})
            with self.assertRaisesRegex(ValueError, "Unexpected"):
                publisher.publish(info, files, self.output, self.request)
            fake.release["assets"].pop()
            fake.files[files[0]] += b"modified"
            with self.assertRaisesRegex(ValueError, "Remote release bytes"):
                publisher.publish(info, files, self.output, self.request)


class AutomaticWorkflowTest(unittest.TestCase):
    def test_every_master_push_has_independent_runs_and_production_smoke_gate(self):
        workflow = (ROOT / ".github/workflows/publish-android.yml").read_text()
        trigger = workflow.split("  workflow_dispatch:", 1)[0]
        self.assertIn("  push:\n    branches:\n      - master\n", trigger)
        self.assertNotIn("paths:", trigger)
        self.assertIn("group: android-production-publish-${{ github.event_name == 'push' && github.run_id || 'manual' }}", workflow)
        release = workflow.split("\n  github-release:\n", 1)[1].split("\n  deploy:\n", 1)[0]
        self.assertIn("needs: [request, sign, smoke, release-identity]", release)
        self.assertIn("if: github.event_name == 'push'", release)
        self.assertNotIn("secrets.", release)
        self.assertNotIn("-PyfuseVersionCode=", workflow)
        self.assertNotIn("-PyfuseVersionName=", workflow)
        self.assertIn("automatic_release_metadata.py install", workflow)
        self.assertIn("automatic_release_metadata.py verify", workflow)
        allocation = workflow.split("\n  release-identity:\n", 1)[1].split("\n  github-release:\n", 1)[0]
        self.assertIn("needs: [request, sign, smoke]", allocation)
        self.assertIn("if: github.event_name == 'push' || needs.request.outputs.publish == 'true'", allocation)
        self.assertIn("--reserve-only", allocation)
        deploy = workflow.split("\n  deploy:\n", 1)[1]
        self.assertIn("needs: [request, sign, smoke, release-identity]", deploy)
        # Re-running only failed jobs still downloads the original successful request artifact.
        self.assertIn("name: ${{ needs.request.outputs.metadata_artifact }}", release)
        gates = (ROOT / ".github/workflows/quality-gates-v2.yml").read_text()
        self.assertIn("github.event_name == 'pull_request' && github.ref || github.run_id", gates)


if __name__ == "__main__":
    unittest.main()
