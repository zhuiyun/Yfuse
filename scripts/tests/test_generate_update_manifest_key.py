import base64
import concurrent.futures
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/generate-update-manifest-key.sh"
PRIVATE = "update-manifest-signing-key.pem"
PUBLIC = "update-manifest-public-key.b64"


class GenerateUpdateManifestKeyTest(unittest.TestCase):
    def setUp(self):
        for command in ("bash", "git", "openssl"):
            if not shutil.which(command):
                self.skipTest(f"{command} is required")
        self.temporary = tempfile.TemporaryDirectory(prefix="yfuse-manifest-key-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def generate(self, *arguments, cwd=None):
        return subprocess.run(
            ["bash", SCRIPT.as_posix(), *map(str, arguments)],
            cwd=cwd or self.directory,
            capture_output=True,
            text=True,
            timeout=30,
        )

    def test_requires_explicit_output_directory(self):
        result = self.generate()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Usage:", result.stderr)
        self.assertEqual([], list(self.directory.iterdir()))

    def test_rejects_a_git_worktree_even_for_ignored_subdirectories(self):
        subprocess.run(["git", "init", "-q", str(self.directory)], check=True)
        (self.directory / ".gitignore").write_text("keys/\n", encoding="utf-8")
        destination = self.directory / "keys"
        result = self.generate(destination)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("inside a Git worktree", result.stderr)
        self.assertEqual([], list(destination.iterdir()))

    def test_generates_matching_ed25519_pair_without_printing_private_material(self):
        destination = self.directory / "key pair"
        result = self.generate(destination)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("PRIVATE KEY", result.stdout)
        public_der = subprocess.check_output(
            ["openssl", "pkey", "-in", str(destination / PRIVATE), "-pubout", "-outform", "DER"]
        )
        self.assertEqual(public_der, base64.b64decode((destination / PUBLIC).read_bytes()))
        # Ed25519 SubjectPublicKeyInfo prefix (OID 1.3.101.112) followed by its 32-byte key.
        self.assertEqual(bytes.fromhex("302a300506032b6570032100"), public_der[:-32])
        self.assertEqual({PRIVATE, PUBLIC}, {path.name for path in destination.iterdir()})
        if os.name != "nt":
            self.assertEqual(0o600, (destination / PRIVATE).stat().st_mode & 0o777)

    def test_rerun_preserves_both_existing_keys(self):
        self.assertEqual(0, self.generate(self.directory).returncode)
        original = {name: (self.directory / name).read_bytes() for name in (PRIVATE, PUBLIC)}
        result = self.generate(self.directory)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Refusing to overwrite", result.stderr)
        self.assertTrue(all((self.directory / name).read_bytes() == value for name, value in original.items()))

    def test_either_existing_output_prevents_generation(self):
        for name in (PRIVATE, PUBLIC):
            with self.subTest(existing=name):
                destination = self.directory / name.replace(".", "_")
                destination.mkdir()
                (destination / name).write_bytes(b"keep existing key")
                result = self.generate(destination)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([name], [path.name for path in destination.iterdir()])
                self.assertEqual(b"keep existing key", (destination / name).read_bytes())

    def test_concurrent_generators_do_not_replace_the_winning_pair(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(lambda _: self.generate(self.directory), range(2)))
        self.assertEqual(1, sum(result.returncode == 0 for result in results))
        public_der = subprocess.check_output(
            ["openssl", "pkey", "-in", str(self.directory / PRIVATE), "-pubout", "-outform", "DER"]
        )
        self.assertEqual(public_der, base64.b64decode((self.directory / PUBLIC).read_bytes()))
        self.assertEqual({PRIVATE, PUBLIC}, {path.name for path in self.directory.iterdir()})

    def test_private_key_filename_is_ignored_by_repository(self):
        result = subprocess.run(
            ["git", "check-ignore", "--no-index", PRIVATE, f"keys/{PRIVATE}"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=True,
        )
        self.assertEqual([PRIVATE, f"keys/{PRIVATE}"], result.stdout.splitlines())


if __name__ == "__main__":
    unittest.main()
