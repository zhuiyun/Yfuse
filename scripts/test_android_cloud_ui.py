import argparse
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import android_cloud_ui as cloud

SMOKE_CASES = [
    "Profile page reachable",
    "Account signed-out page reachable",
    "Servers tab reachable",
    "Home tab reachable",
    "Large-font foreground capture",
    "Landscape foreground capture",
    "Dark-theme foreground capture",
    "UI survives disabling Wi-Fi and mobile data",
    "Display settings restored before the soak",
    "Short foreground/background stability",
]


class Recorder:
    """Stands in for a device session: every case and device step only records itself."""

    def __init__(self):
        self.calls = []
        self.summary = {"cases": []}

    def case(self, name, operation):
        self.calls.append(name)
        return operation()

    def __getattr__(self, name):
        def step(*args, **kwargs):
            self.calls.append(name)

        return step


class FailingSession(cloud.Session):
    """A real session's bookkeeping around a launch that never finds the app."""

    failure = RuntimeError("UI label not found: 我的; ")

    def verify_apk(self, directory):
        return {"sha256": "fixture"}

    def connect(self):
        self.serial = "emulator-5554"
        self.original = {"font_scale": "1.0"}
        self.original_night = "no"
        return {}

    def install(self):
        return "Success"

    def launch(self):
        raise self.failure

    def diagnostics(self):
        (self.output / "crash-buffer.txt").write_text(
            "--------- beginning of crash\nFATAL EXCEPTION: main\njava.lang.IllegalStateException: boom\n"
        )
        (self.output / "logcat.txt").write_text("\n".join(f"logcat line {index}" for index in range(400)))

    def restore(self):
        pass


class BlockedSession(FailingSession):
    def connect(self):
        raise cloud.EnvironmentBlocked("Requires exactly one disposable Android emulator; refuses physical devices")

    def diagnostics(self):
        pass


def run_main(root, session_class, *extra):
    apks = root / "input"
    apks.mkdir()
    (apks / "update.json").write_text(json.dumps({"versionCode": 7, "versionName": "1.0.7", "sha256": "a" * 64}))
    output = root / "output"
    argv = ["android_cloud_ui.py", "--apk-directory", str(apks), "--output", str(output),
            "--source-run", "1", *extra]
    stdout = io.StringIO()
    with patch("sys.argv", argv), \
            patch("release_metadata.read_release", return_value={"versionCode": 7, "versionName": "1.0.7"}), \
            patch.object(cloud, "Session", session_class), \
            contextlib.redirect_stdout(stdout):
        code = cloud.main()
    return code, stdout.getvalue(), json.loads((output / "summary.json").read_text())


class CaseOrderTest(unittest.TestCase):
    def test_the_layout_probe_follows_the_whole_smoke_in_the_same_session(self):
        session = Recorder()
        cloud.run_cases(session, argparse.Namespace(apk_directory=Path("apks"), soak_seconds=0, layout_probe=True))

        cases = [call for call in session.calls if call[0].isupper()]
        self.assertEqual(SMOKE_CASES, [case for case in cases if case in SMOKE_CASES])
        self.assertEqual(
            ["Display settings restored after the smoke", "Targeted rotation, font and tablet viewport evidence"],
            cases[-2:],
        )
        self.assertLess(session.calls.index("offline"), session.calls.index("restore_display"))
        self.assertLess(session.calls.index("restore_display"), session.calls.index("soak"))
        self.assertLess(session.calls.index("soak"), session.calls.index("settle_for_layout_probe"))
        self.assertLess(session.calls.index("settle_for_layout_probe"), session.calls.index("layout_probe"))
        self.assertEqual("smoke_and_layout_probe_completed_visual_review_required", session.summary["result"])

    def test_without_the_probe_only_the_smoke_runs(self):
        session = Recorder()
        cloud.run_cases(session, argparse.Namespace(apk_directory=Path("apks"), soak_seconds=0, layout_probe=False))

        self.assertNotIn("layout_probe", session.calls)
        self.assertNotIn("settle_for_layout_probe", session.calls)
        # A release gate runs without the probe; its soak still starts from the device's own display.
        self.assertLess(session.calls.index("restore_display"), session.calls.index("soak"))
        self.assertEqual("smoke_completed_visual_review_required", session.summary["result"])


class FailureOutputTest(unittest.TestCase):
    def test_a_failed_case_puts_its_cause_and_the_device_logs_in_the_job_log(self):
        with tempfile.TemporaryDirectory() as directory:
            code, stdout, summary = run_main(Path(directory), FailingSession, "--layout-probe")

        self.assertEqual(1, code)
        self.assertEqual("failed_requires_triage", summary["result"])
        self.assertIn("Cloud UI failure", stdout)
        self.assertIn("UI label not found: 我的", stdout)
        self.assertIn('"failed_case"', stdout)
        self.assertIn("App launch and foreground hierarchy", stdout)
        self.assertIn("Traceback", stdout)
        self.assertIn("FATAL EXCEPTION: main", stdout)
        self.assertIn("logcat line 399", stdout)
        self.assertNotIn("logcat line 100\n", stdout)

    def test_a_blocked_environment_still_exits_2_and_says_why(self):
        with tempfile.TemporaryDirectory() as directory:
            code, stdout, summary = run_main(Path(directory), BlockedSession)

        self.assertEqual(2, code)
        self.assertEqual("environment_blocked", summary["result"])
        self.assertIn("refuses physical devices", stdout)
        self.assertIn("crash-buffer.txt: not captured", stdout)


if __name__ == "__main__":
    unittest.main()
