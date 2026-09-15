#!/usr/bin/env python3
"""Covers the severity resolution the supply-chain gate depends on.

The gate previously read `database_specific.severity` off the `querybatch` response,
which never carries it — so every advisory resolved to UNKNOWN and the job passed
even with a CRITICAL dependency. These tests pin the behaviour that broke silently.
"""
from __future__ import annotations

import unittest
import io
import json
import pathlib
import subprocess
import tempfile
from unittest.mock import patch

import supply_chain_check

from supply_chain_check import (
    UNRESOLVED,
    Dependency,
    cvss_v3_score,
    query_osv,
    rating_for_score,
    read_dependencies,
    severity,
    fetch_details,
)


class ScanCoverageTest(unittest.TestCase):
    def test_unlocked_security_override_is_still_scanned(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "scripts").mkdir()
            (root / "scripts/security-overrides.properties").write_text(
                "org.bouncycastle\\:bcprov-jdk18on=1.84\n"
            )
            (root / "gradle.lockfile").write_text("example:locked:1.0=runtime\n")
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(["git", "-C", str(root), "add", "gradle.lockfile"], check=True)
            self.assertEqual(
                {item.coordinate for item in read_dependencies(root)},
                {"example:locked:1.0", "org.bouncycastle:bcprov-jdk18on:1.84"},
            )

    def test_incomplete_or_malformed_batch_cannot_be_reported_clean(self) -> None:
        dependencies = [Dependency("example", "one", "1", "lock"), Dependency("example", "two", "1", "lock")]
        for response in ({}, {"results": [{}]}, {"results": [None, {}]},
                         {"results": [{"vulns": [{}]}, {}]},
                         {"results": [{"next_page_token": "more"}, {}]}):
            with self.subTest(response=response), patch(
                "supply_chain_check.urllib.request.urlopen",
                return_value=io.BytesIO(json.dumps(response).encode()),
            ):
                with self.assertRaises(ValueError):
                    query_osv(dependencies)

    def test_complete_batch_preserves_dependency_to_advisory_mapping(self) -> None:
        dependencies = [Dependency("example", "one", "1", "lock"), Dependency("example", "two", "1", "lock")]
        with patch("supply_chain_check.urllib.request.urlopen", return_value=io.BytesIO(
            b'{"results":[{}, {"vulns":[{"id":"GHSA-test"}]}]}'
        )):
            self.assertEqual(query_osv(dependencies), [(dependencies[1], "GHSA-test")])


class SeverityFromAdvisoryTest(unittest.TestCase):
    def test_reads_the_ghsa_label(self) -> None:
        advisory = {"id": "GHSA-test", "database_specific": {"severity": "CRITICAL"}}
        self.assertEqual(severity(advisory), "CRITICAL")

    def test_maps_ghsa_moderate_onto_cvss_medium(self) -> None:
        advisory = {"id": "GHSA-test", "database_specific": {"severity": "MODERATE"}}
        self.assertEqual(severity(advisory), "MEDIUM")

    def test_falls_back_to_the_cvss_vector(self) -> None:
        # A record carrying only a CVSS vector, as raw CVE entries in OSV do.
        advisory = {
            "id": "CVE-test",
            "severity": [
                {
                    "type": "CVSS_V3",
                    "score": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                }
            ],
        }
        self.assertEqual(severity(advisory), "CRITICAL")

    def test_takes_the_worst_of_several_statements(self) -> None:
        advisory = {
            "id": "GHSA-test",
            "database_specific": {"severity": "LOW"},
            "severity": [
                {
                    "type": "CVSS_V3",
                    "score": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                }
            ],
        }
        self.assertEqual(severity(advisory), "CRITICAL")

    def test_an_advisory_without_severity_blocks_rather_than_passes(self) -> None:
        # This is the exact shape `querybatch` returns. It must never look benign.
        self.assertEqual(severity({"id": "GHSA-test", "modified": "2026-01-01T00:00:00Z"}), UNRESOLVED)

    def test_an_unparseable_vector_blocks_rather_than_passes(self) -> None:
        advisory = {"id": "CVE-test", "severity": [{"type": "CVSS_V4", "score": "CVSS:4.0/AV:N"}]}
        self.assertEqual(severity(advisory), UNRESOLVED)


class CvssScoreTest(unittest.TestCase):
    def test_scores_published_vectors(self) -> None:
        cases = {
            # Log4Shell (CVE-2021-44228), the canonical 10.0.
            "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H": 10.0,
            "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H": 9.8,
            "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N": 7.5,
            "CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:N/I:N/A:H": 5.5,
            "CVSS:3.1/AV:N/AC:H/PR:H/UI:R/S:C/C:L/I:L/A:N": 4.0,
            "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N": 0.0,
        }
        for vector, expected in cases.items():
            with self.subTest(vector=vector):
                self.assertEqual(cvss_v3_score(vector), expected)

    def test_scope_change_raises_the_score(self) -> None:
        unchanged = cvss_v3_score("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H")
        changed = cvss_v3_score("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H")
        assert unchanged is not None and changed is not None
        self.assertGreater(changed, unchanged)

    def test_rejects_non_v3_and_malformed_vectors(self) -> None:
        self.assertIsNone(cvss_v3_score("CVSS:2.0/AV:N/AC:L/Au:N/C:P/I:P/A:P"))
        self.assertIsNone(cvss_v3_score("CVSS:3.1/AV:N/AC:L"))
        self.assertIsNone(cvss_v3_score(""))

    def test_rating_boundaries(self) -> None:
        self.assertEqual(rating_for_score(0.0), "NONE")
        self.assertEqual(rating_for_score(3.9), "LOW")
        self.assertEqual(rating_for_score(4.0), "MEDIUM")
        self.assertEqual(rating_for_score(6.9), "MEDIUM")
        self.assertEqual(rating_for_score(7.0), "HIGH")
        self.assertEqual(rating_for_score(8.9), "HIGH")
        self.assertEqual(rating_for_score(9.0), "CRITICAL")


class ScanInputTest(unittest.TestCase):
    def test_only_tracked_locks_are_read_and_all_sources_are_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            for relative in ("app/gradle.lockfile", "server/gradle.lockfile", "build/gradle.lockfile",
                             ".worktrees/old/app/gradle.lockfile"):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("example:used:1=runtime\n" if relative.startswith(("app/", "server/"))
                                else "example:stale:0=runtime\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(root), "add", "app/gradle.lockfile", "server/gradle.lockfile"], check=True)
            dependencies = read_dependencies(root)
            self.assertEqual([d.coordinate for d in dependencies], ["example:used:1"])
            self.assertIn("app", dependencies[0].source)
            self.assertIn("server", dependencies[0].source)

    def test_missing_tracked_lock_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            lock = root / "gradle.lockfile"
            lock.write_text("example:used:1=runtime\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(root), "add", "gradle.lockfile"], check=True)
            lock.unlink()
            with self.assertRaises(ValueError):
                read_dependencies(root)


class OsvResponseTest(unittest.TestCase):
    dependency = Dependency("example", "library", "1", "fixture")

    def test_incomplete_or_invalid_batch_is_not_a_clean_scan(self) -> None:
        invalid = [{}, {"results": []}, {"results": [None]}, {"results": [{"error": "unavailable"}]},
                   {"results": [{"vulns": None}]}, {"results": [{"vulns": [{}]}]},
                   {"results": [{}, {}]}]
        for response in invalid:
            with self.subTest(response=response), patch.object(
                supply_chain_check.urllib.request, "urlopen", return_value=io.BytesIO(json.dumps(response).encode()),
            ):
                with self.assertRaises(ValueError):
                    query_osv([self.dependency])

    def test_complete_empty_result_is_valid(self) -> None:
        with patch.object(supply_chain_check.urllib.request, "urlopen", return_value=io.BytesIO(b'{"results":[{}]}')):
            self.assertEqual(query_osv([self.dependency]), [])

    def test_advisory_identity_must_match(self) -> None:
        with patch.object(supply_chain_check.urllib.request, "urlopen", return_value=io.BytesIO(b'{"id":"wrong"}')):
            with self.assertRaises(ValueError):
                fetch_details({"GHSA-test"})


if __name__ == "__main__":
    unittest.main()
