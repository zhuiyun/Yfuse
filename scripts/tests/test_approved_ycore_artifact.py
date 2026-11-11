import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).parents[1] / "approved_ycore_artifact.py"
spec = importlib.util.spec_from_file_location("approved_ycore", SCRIPT)
approved = importlib.util.module_from_spec(spec)
spec.loader.exec_module(approved)


class ApprovedYCoreArtifactTest(unittest.TestCase):
    def setUp(self):
        self.record = {"sourceCommit": "a" * 40, "runId": 100, "runAttempt": 2,
                       "artifactId": 123, "aarSha256": "b" * 64, "provenanceSha256": "c" * 64}

    def test_exact_quality_run_produces_fixed_outputs(self):
        self.assertEqual({"artifact_id": "123", "source_commit": "a" * 40,
                          "aar_sha256": "b" * 64, "provenance_sha256": "c" * 64},
                         approved.validate(self.record, "a" * 40, 100, 2))

    def test_wrong_source_run_attempt_and_malformed_values_are_rejected(self):
        for change in ({"sourceCommit": "d" * 40}, {"runId": 101}, {"runAttempt": 1},
                       {"artifactId": 0}, {"artifactId": True}, {"aarSha256": "b" * 63},
                       {"provenanceSha256": "bad\nartifact_id=999"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                approved.validate(dict(self.record, **change), "a" * 40, 100, 2)

    def test_completed_trigger_must_be_exact_successful_master_push(self):
        run = {"head_sha": "a" * 40, "id": 100, "run_attempt": 2, "event": "push",
               "head_branch": "master", "status": "completed", "conclusion": "success",
               "path": ".github/workflows/quality-gates-v2.yml",
               "repository": {"full_name": "zhuiyun/Yfuse"},
               "head_repository": {"full_name": "zhuiyun/Yfuse"}}
        self.assertEqual({"run_id": "100", "run_attempt": "2"},
                         approved.validate_quality_run(run, "a" * 40, 100))
        for change in ({"head_sha": "b" * 40}, {"id": 101}, {"event": "pull_request"},
                       {"head_branch": "feature"}, {"status": "in_progress"},
                       {"conclusion": "failure"}, {"path": ".github/workflows/other.yml"},
                       {"run_attempt": True}, {"head_repository": {"full_name": "someone/fork"}}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                approved.validate_quality_run(dict(run, **change), "a" * 40, 100)


if __name__ == "__main__":
    unittest.main()
