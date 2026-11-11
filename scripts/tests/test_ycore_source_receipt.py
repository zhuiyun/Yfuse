import importlib.util
import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

spec = importlib.util.spec_from_file_location("source_receipt", Path(__file__).parents[1] / "ycore-source-receipt.py")
receipt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(receipt)


class SourceReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in receipt.REQUIRED_SOURCES:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture source: " + name)
        for args in (("init", "-q"), ("add", "."),
                     ("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture")):
            subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True)
        self.commit = receipt.git(self.root, "rev-parse", "HEAD")
        self.directory = self.root / "artifact"
        self.directory.mkdir()
        self.aar = self.directory / "ycore-native.aar"
        self.provenance = b"fixture native provenance\n"
        self.write_aar()
        self.record = {"schemaVersion": 1, "sourceCommit": self.commit,
                       "sourceFiles": receipt.source_files(self.root, self.commit),
                       "ycore": receipt.artifact_identity(self.aar), "licenseFiles": {}}
        self.path = self.directory / "source-build.json"
        self.save_record()

    def write_aar(self, extra=None):
        with zipfile.ZipFile(self.aar, "w") as archive:
            for name in receipt.LIBRARIES:
                archive.writestr("jni/arm64-v8a/" + name, b"test ELF fixture")
            archive.writestr("META-INF/ycore-native-sources.txt", self.provenance)
            archive.writestr("META-INF/NOTICE", b"fixture license notice")
            if extra:
                archive.writestr(extra, b"unexpected")
        (self.directory / "NATIVE-SOURCES.txt").write_bytes(self.provenance)
        (self.directory / "ycore-native.aar.sha256").write_text(receipt.sha256(self.aar.read_bytes()) + "  ycore-native.aar\n")

    def save_record(self):
        self.path.write_text(json.dumps(self.record))

    def verify(self, **changes):
        arguments = dict(root=self.root, aar=self.aar, receipt=self.path,
                         expected_aar_sha256=self.record["ycore"]["sha256"],
                         expected_provenance_sha256=self.record["ycore"]["provenanceSha256"],
                         expected_source_commit=self.commit)
        arguments.update(changes)
        return receipt.verify_receipt(**arguments)

    def test_matching_external_identities_are_accepted(self):
        self.assertEqual(self.commit, self.verify()["sourceCommit"])

    def test_receipt_cannot_replace_external_aar_identity(self):
        with self.assertRaisesRegex(ValueError, "external workflow SHA"):
            self.verify(expected_aar_sha256="a" * 64)

    def test_missing_external_hash_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "External expected hashes"):
            self.verify(expected_aar_sha256=None)

    def test_provenance_identity_is_independently_checked(self):
        with self.assertRaisesRegex(ValueError, "Provenance differs"):
            self.verify(expected_provenance_sha256="a" * 64)

    def test_receipt_source_commit_mismatch(self):
        self.record["sourceCommit"] = "a" * 40
        self.save_record()
        with self.assertRaisesRegex(ValueError, "Receipt source commit"):
            self.verify()

    def test_checkout_source_mutation_is_rejected(self):
        (self.root / "scripts/native/ycore_demux_jni.cpp").write_text("mutated")
        with self.assertRaisesRegex(ValueError, "differs from committed bytes"):
            self.verify()

    def test_omitted_source_hash_is_rejected(self):
        self.record["sourceFiles"].pop("scripts/native/ycore_demux_jni.cpp")
        self.save_record()
        with self.assertRaisesRegex(ValueError, "source file hashes mismatch"):
            self.verify()

    def test_sidecar_cannot_silently_change(self):
        (self.directory / "NATIVE-SOURCES.txt").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "Embedded provenance"):
            self.verify()

    def test_legacy_native_engine_is_rejected(self):
        self.write_aar("jni/arm64-v8a/libmpv.so")
        with self.assertRaisesRegex(ValueError, "library inventory"):
            receipt.artifact_identity(self.aar)

    def test_symlink_artifact_is_rejected(self):
        real = self.directory / "other.aar"
        self.aar.rename(real)
        self.aar.symlink_to(real)
        with self.assertRaisesRegex(ValueError, "symbolic-link"):
            self.verify()

    def test_license_traversal_is_rejected(self):
        self.record["licenseFiles"] = {"licenses/../../private": "a" * 64}
        self.save_record()
        with self.assertRaisesRegex(ValueError, "Invalid notice path"):
            self.verify()

    def test_changed_checkout_commit_is_rejected(self):
        self.record["sourceCommit"] = "a" * 40
        self.save_record()
        with self.assertRaisesRegex(ValueError, "Checkout commit differs"):
            self.verify(expected_source_commit="a" * 40)

    def test_invalid_schema_and_artifact_size_are_rejected(self):
        self.record["schemaVersion"] = True
        self.save_record()
        with self.assertRaisesRegex(ValueError, "schema"):
            self.verify()
        self.record["schemaVersion"] = 1
        self.record["ycore"]["bytes"] += 1
        self.save_record()
        with self.assertRaisesRegex(ValueError, "artifact identity"):
            self.verify()

    def test_creation_writes_the_verified_contract(self):
        ledger = self.root / "downloads.jsonl"
        ledger.write_text(json.dumps({"url": "https://fixture.test/source", "sha256": "a" * 64}) + "\n")
        with mock.patch.object(receipt, "dependency_evidence", return_value={"fixture": {"commit": "b" * 40}}), \
                mock.patch.object(receipt, "collect_licenses", return_value={}), \
                mock.patch.object(receipt, "toolchain_evidence", return_value={"fixture": "toolchain"}):
            record = receipt.create_receipt(self.root, self.directory, self.commit, self.root / "upstream", ledger)
        self.assertEqual(record, self.verify())
        self.assertEqual(1, record["schemaVersion"])
        self.assertEqual(self.commit, record["sourceCommit"])
        self.assertEqual(self.record["ycore"], record["ycore"])


if __name__ == "__main__":
    unittest.main()
