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
import tempfile
from unittest.mock import patch

from supply_chain_check import (
    UNRESOLVED,
    Dependency,
    cvss_v3_score,
    query_osv,
    rating_for_score,
    read_dependencies,
    severity,
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


if __name__ == "__main__":
    unittest.main()
