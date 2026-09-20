"""Behavior coverage for the renderer's lockfile and OSV/SBOM integration."""
import io
import json
import pathlib
import subprocess
import tempfile
import unittest
import uuid
from datetime import datetime, timezone
from unittest.mock import patch

from supply_chain_check import Dependency, query_osv, read_npm_dependencies, write_spdx


class NpmSupplyChainTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)

    def lock(self, relative, packages, tracked=True, version=3):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"lockfileVersion": version, "packages": packages}), encoding="utf-8")
        if tracked:
            subprocess.run(["git", "-C", str(self.root), "add", relative], check=True)
        return path

    def test_transitive_scoped_optional_packages_and_duplicate_sources(self):
        packages = {
            "": {"name": "renderer", "version": "1.0.0"},
            "node_modules/playwright": {"version": "1.62.1"},
            "node_modules/playwright/node_modules/@scope/helper": {"version": "2.0.0-rc.1"},
            "node_modules/fsevents": {"version": "2.3.2", "optional": True},
        }
        self.lock("renderer/package-lock.json", packages)
        self.lock("tools/package-lock.json", packages)
        self.lock("audit/old/package-lock.json", {"node_modules/obsolete": {"version": "0.0.1"}}, tracked=False)
        dependencies = read_npm_dependencies(self.root)
        self.assertEqual({d.name for d in dependencies}, {"playwright", "@scope/helper", "fsevents"})
        for dependency in dependencies:
            self.assertEqual("npm", dependency.ecosystem)
            self.assertIn("renderer", dependency.source)
            self.assertIn("tools", dependency.source)
        scoped = next(d for d in dependencies if d.name.startswith("@"))
        self.assertEqual("pkg:npm/%40scope/helper@2.0.0-rc.1", scoped.purl)

    def test_unresolved_or_unsupported_locks_fail_closed(self):
        for record in ({}, {"version": "*"}, {"version": "file:../local"}, {"link": True}):
            with self.subTest(record=record):
                self.lock("package-lock.json", {"node_modules/example": record})
                with self.assertRaises(ValueError):
                    read_npm_dependencies(self.root)
        self.lock("package-lock.json", {}, version=1)
        with self.assertRaises(ValueError):
            read_npm_dependencies(self.root)

    def test_missing_tracked_lock_is_not_a_clean_scan(self):
        path = self.lock("package-lock.json", {})
        path.unlink()
        with self.assertRaises(ValueError):
            read_npm_dependencies(self.root)

    def test_osv_receives_the_correct_ecosystem_and_sbom_keeps_it(self):
        dependency = Dependency("", "@scope/helper", "2.0.0", "renderer/package-lock.json", "npm")
        with patch("supply_chain_check.urllib.request.urlopen", return_value=io.BytesIO(
            b'{"results":[{"vulns":[{"id":"GHSA-example"}]}]}'
        )) as request:
            self.assertEqual(query_osv([dependency]), [(dependency, "GHSA-example")])
            query = json.loads(request.call_args.args[0].data)["queries"][0]
            self.assertEqual(query["package"], {"name": "@scope/helper", "ecosystem": "npm"})
        advisory = {"id": "GHSA-example", "database_specific": {"severity": "HIGH"}}
        output = self.root / "sbom.json"
        write_spdx([dependency], [(dependency, advisory)], output)
        package = json.loads(output.read_text())["packages"][0]
        self.assertEqual(package["name"], "@scope/helper")
        self.assertEqual(package["externalRefs"][0]["referenceLocator"], dependency.purl)
        self.assertIn("HIGH", package["comment"])

    def test_sbom_snapshots_have_utc_creation_times_and_unique_namespaces(self):
        dependency = Dependency("", "playwright", "1.62.1", "renderer/package-lock.json", "npm")
        documents = []
        for index in range(2):
            output = self.root / f"sbom-{index}.json"
            write_spdx([dependency], [], output)
            document = json.loads(output.read_text())
            self.assertRegex(document["creationInfo"]["created"], r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
            created = datetime.fromisoformat(document["creationInfo"]["created"].replace("Z", "+00:00"))
            self.assertEqual(created.tzinfo, timezone.utc)
            self.assertIn("Tool: Yfuse supply_chain_check.py", document["creationInfo"]["creators"])
            namespace_id = document["documentNamespace"].removeprefix("https://yfuse.app/spdx/yfuse-dependency-locks/")
            self.assertEqual(uuid.UUID(namespace_id).version, 4)
            documents.append(document)
        self.assertNotEqual(documents[0]["documentNamespace"], documents[1]["documentNamespace"])
        self.assertEqual(documents[0]["packages"], documents[1]["packages"])


if __name__ == "__main__":
    unittest.main()
