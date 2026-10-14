import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    "playback_startup_baseline", Path(__file__).parents[1] / "playback-startup-baseline.py"
)
BASELINE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASELINE)


def milestone(index, engine="YCore2Native", stage="first_video_output", elapsed=500):
    return {
        "timestamp": f"sample-{index}", "event": "playback_startup_stage",
        "attributes": {"engine": engine, "stage": stage, "playbackTrace": f"trace-{index}",
                       "generationElapsedMs": str(elapsed), "renderPath": "direct"},
    }


class StartupBaselineTest(unittest.TestCase):
    def test_quantiles_group_actual_engines_and_do_not_invent_audio(self):
        records = [milestone(i, elapsed=i * 100) for i in range(1, 21)]
        records.append(milestone(21, engine="libmpv", elapsed=700))
        records.append(records[0])
        records.append(dict(records[1], event="playback_output_resumed"))
        report = BASELINE.summarize(records)
        self.assertEqual(2, len(report["rows"]))
        row = report["rows"][0]
        self.assertEqual((20, 1000, 1900), (row["samples"], row["p50_ms"], row["p95_ms"]))
        self.assertTrue(row["sufficient_samples"])
        self.assertTrue(all(row["stage"] == "first_video_output" for row in report["rows"]))

    def test_missing_or_invalid_measurements_are_rejected_not_zero(self):
        invalid = milestone(1)
        invalid["attributes"].pop("generationElapsedMs")
        report = BASELINE.summarize([invalid, milestone(2, elapsed=-1)])
        self.assertEqual([], report["rows"])
        self.assertEqual(2, report["rejected_startup_entries"])


if __name__ == "__main__":
    unittest.main()
