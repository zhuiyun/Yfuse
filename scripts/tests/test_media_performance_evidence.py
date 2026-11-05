import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("media_evidence", Path(__file__).parents[1] / "media-performance-evidence.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MediaEvidenceTests(unittest.TestCase):
    def test_memory_missing_is_not_zero_and_heap_is_not_pss(self):
        parsed = MODULE.parse_memory(" Native Heap 120 100 0 0 0 400 300 100\n TOTAL PSS: 800\n Graphics: 70\n")
        self.assertEqual(800, parsed["pss_kib"])
        self.assertEqual(300, parsed["native_heap_allocated_kib"])
        self.assertEqual(70, parsed["graphics_pss_kib"])
        self.assertIsNone(MODULE.parse_memory("permission denied")["pss_kib"])

    def test_comparison_refuses_changed_conditions_or_incomplete_run(self):
        with tempfile.TemporaryDirectory() as root:
            paths = [Path(root) / "a", Path(root) / "b"]
            metadata = {"device": "device", "device_id_hash": "same-device", "sample_sha256": "sample", "case": {"cache": "cold"},
                        "requested_duration_seconds": 10, "interval_seconds": 5, "completed_seconds": 11,
                        "package": "example.app", "apk_sha256": "apk"}
            for path in paths:
                path.mkdir()
                (path / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
                (path / "samples.jsonl").write_text('{"pss_kib":123}\n', encoding="utf-8")
            report = MODULE.compare(*paths)
            self.assertIsNone(report["runs"][0]["video_probe"])
            self.assertIsNone(report["runs"][0]["metrics"]["graphics_pss_kib"])
            metadata["device_id_hash"] = "different-device-same-firmware"
            (paths[1] / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "device_id_hash differs"):
                MODULE.compare(*paths)
            metadata["device_id_hash"] = "same-device"
            metadata["case"] = {"cache": "warm"}
            (paths[1] / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "case differs"):
                MODULE.compare(*paths)
            metadata["case"] = {"cache": "cold"}
            metadata["completed_seconds"] = 5
            (paths[1] / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Incomplete"):
                MODULE.compare(*paths)


if __name__ == "__main__":
    unittest.main()
