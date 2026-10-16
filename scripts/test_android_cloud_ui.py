import argparse
import contextlib
import io
import json
import re
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import android_cloud_ui as cloud

SMOKE_CASES = [
    "Profile page reachable",
    "Account signed-out page reachable",
    "Personal lists, search and one-level back",
    "Servers tab reachable",
    "Browse tab reachable",
    "Watching tab reachable",
    "Discovery page reachable",
    "Discovery returns to Watching",
    "Discovery restored for display and stability checks",
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
        self.arguments = []
        self.summary = {"cases": []}

    def case(self, name, operation):
        self.calls.append(name)
        return operation()

    def __getattr__(self, name):
        def step(*args, **kwargs):
            self.calls.append(name)
            self.arguments.append((name, args, kwargs))

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

    def wait_for_boot_load(self):
        return {}

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
        self.assertLess(session.calls.index("connect"), session.calls.index("wait_for_boot_load"))
        self.assertLess(session.calls.index("wait_for_boot_load"), session.calls.index("install"))
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


class NavigationContractTest(unittest.TestCase):
    def test_dock_selectors_match_the_current_application_destinations(self):
        source = (Path(__file__).resolve().parents[1] /
                  "composeApp/src/commonMain/kotlin/com/yfuse/app/App.kt").read_text()
        dock = source.split("private val tabs =", 1)[1].split("/**", 1)[0]
        self.assertEqual(dict(re.findall(r'TabItem\(Tab\.(\w+), "([^"]+)"', dock)), cloud.TAB_LABELS)

    def test_smoke_uses_page_markers_not_only_persistent_dock_labels(self):
        session = Recorder()
        cloud.run_cases(session, argparse.Namespace(apk_directory=Path("apks"), soak_seconds=0, layout_probe=False))
        navigation = [args for name, args, kwargs in session.arguments if name == "navigate"]
        self.assertEqual([
            ("我的", "账号与同步", "02-profile"),
            ("账号与同步", "登录账号", "03-account-signed-out"),
            ("服务器", "我的服务器", "04-servers"),
            ("媒体库", cloud.LIBRARY_EMPTY, "05-library"),
        ], navigation)
        self.assertLess(session.calls.index("watching"), session.calls.index("discovery"))
        self.assertLess(session.calls.index("discovery"), session.calls.index("discovery_back"))
        restore = ("discovery", ("05d-discovery-restored",), {})
        self.assertIn(restore, session.arguments)
        self.assertLess(session.arguments.index(restore),
                        next(index for index, call in enumerate(session.arguments) if call[0] == "configuration"))

    def test_watching_requires_both_the_history_page_and_its_clean_empty_state(self):
        session = Recorder()
        cloud.Session.watching(session)
        self.assertEqual([
            ("tap", ("观看",), {}),
            ("wait_label", ("播放记录",), {}),
            ("wait_label", (cloud.HISTORY_EMPTY,), {}),
            ("capture", ("05a-watching",), {}),
        ], session.arguments)

    def test_former_home_content_is_reached_through_watching_discovery(self):
        session = Recorder()
        cloud.Session.discovery(session)
        self.assertEqual([
            ("tap", ("观看",), {}),
            ("wait_label", ("播放记录",), {}),
            ("tap", ("发现",), {}),
            ("wait_label", ("返回媒体库",), {}),
            ("wait_label", ("发现",), {}),
            ("capture", ("05b-discovery",), {}),
        ], session.arguments)

    def test_discovery_back_must_restore_the_watching_page(self):
        session = Recorder()
        cloud.Session.discovery_back(session)
        self.assertEqual([
            ("back", (), {}),
            ("wait_label", ("播放记录",), {}),
            ("capture", ("05c-discovery-back",), {}),
        ], session.arguments)

    def test_layout_probe_keeps_library_and_former_home_content_coverage(self):
        session = Recorder()
        with tempfile.TemporaryDirectory() as directory:
            session.output = Path(directory)
            with patch.object(session, "adb", return_value=b"\x89PNG\r\n\x1a\n" + b"\x00" * 16), \
                    patch.object(session, "tree", return_value=(None, "<hierarchy/>")), \
                    patch.object(cloud.time, "sleep"):
                cloud.Session.layout_probe(session)
        self.assertEqual([
            ("back", (), {}),
            ("wait_label", ("播放记录",), {}),
            ("navigate", ("媒体库", cloud.LIBRARY_EMPTY, "layout-library-empty"), {}),
            ("discovery", ("layout-discovery",), {}),
        ], session.arguments[:4])

    def test_restoring_display_checks_discovery_not_its_hidden_dock(self):
        session = Recorder()
        with patch.object(cloud.time, "sleep"):
            cloud.Session.restore_display(session)
        self.assertEqual([
            ("reset_configuration", (), {}),
            ("wait_label", ("返回媒体库",), {}),
            ("wait_label", ("发现",), {}),
            ("capture", ("09b-display-restored",), {}),
        ], session.arguments)

    def test_display_restore_and_layout_navigation_respect_the_hidden_discovery_dock(self):
        class RouteSession(cloud.Session):
            route = "discovery"

            def wait_label(self, label, timeout=45):
                labels = ({"发现", "返回媒体库"} if self.route == "discovery" else
                          set(cloud.TAB_LABELS.values()) | {"播放记录", "发现", cloud.LIBRARY_EMPTY})
                if label not in labels:
                    raise AssertionError(f"{label} is not visible on {self.route}")

            def tap(self, label):
                self.wait_label(label)
                self.route = {"媒体库": "browse", "观看": "watching", "发现": "discovery"}[label]

            def back(self):
                if self.route != "discovery":
                    raise AssertionError("Expected the discovery route before Back")
                self.route = "watching"

            def reset_configuration(self):
                pass

            def capture(self, name):
                return {"name": name, "route": self.route}

        with tempfile.TemporaryDirectory() as directory:
            session = RouteSession(Path(directory), {"versionName": "1.4.0", "versionCode": 292}, 1)
            with patch.object(session, "adb", return_value=b"\x89PNG\r\n\x1a\n" + b"\x00" * 16), \
                    patch.object(session, "tree", return_value=(None, "<hierarchy/>")), \
                    patch.object(cloud.time, "sleep"):
                self.assertEqual("discovery", session.restore_display()["route"])
                captures = session.layout_probe()["captures"]
        self.assertEqual(["browse", "discovery"], [capture["route"] for capture in captures[:2]])


class BootReadinessTest(unittest.TestCase):
    marker = "2026-10-16 14:19:50.123 +0000   625   653 I UserController: Finished processing BOOT_COMPLETED for u0"

    def check_samples(self, values, timeout=180):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        session = cloud.Session(Path(directory.name), {"versionName": "1.4.0", "versionCode": 292}, 1)
        now = [0]
        current = [None]
        def sleep(seconds):
            now[0] += seconds
        def adb(*args, **kwargs):
            self.assertNotIn("/proc/pressure/cpu", args)
            if args[0] == "logcat":
                self.assertIn("UTC", args)
                self.assertIn("year", args)
                current[0] = next(values)
                return self.marker if current[0][2] else ""
            if args[1] == "cat":
                return f"{current[0][0]} {current[0][1]}\n10.0 5.0 2.0 1/100 42"
            if args[1] == "date":
                return (cloud.datetime(2026, 10, 16, 14, 20) + cloud.timedelta(seconds=now[0])).strftime("%Y-%m-%dT%H:%M:%S")
            if args[1:3] == ("dumpsys", "cpuinfo"):
                return "cached old 100% TOTAL: diagnostic only"
            raise AssertionError(args)
        with patch.object(session, "adb", side_effect=adb), \
                patch.object(cloud.time, "monotonic", side_effect=lambda: now[0]), \
                patch.object(cloud.time, "sleep", side_effect=sleep):
            result = session.wait_for_boot_load(timeout)
        return result, json.loads((Path(directory.name) / "boot-readiness.json").read_text())

    def test_requires_three_post_boot_idle_intervals_not_cached_cpuinfo(self):
        result, samples = self.check_samples(iter([(100, 0, False), (105, 0, True),
                                                   (110, 5, True), (115, 10, True), (120, 15, True)]))
        self.assertEqual(5, result["samples"])
        self.assertEqual(1, result["idle_cpu_equivalents"])
        self.assertEqual([0, 0, 1, 2, 3], [sample["consecutive_idle_intervals"] for sample in samples])
        self.assertIn("Cached", samples[-1]["cpuinfo_note"])

    def test_busy_interval_resets_the_streak(self):
        values = [(100, 0, True), (105, 5, True), (110, 5, True),
                  (115, 10, True), (120, 15, True), (125, 20, True)]
        result, samples = self.check_samples(iter(values))
        self.assertEqual(6, result["samples"])
        self.assertEqual([0, 1, 0, 1, 2, 3], [sample["consecutive_idle_intervals"] for sample in samples])

    def test_missing_completion_or_idle_capacity_blocks_within_the_budget(self):
        for values in ([(100 + n * 5, n * 10, False) for n in range(4)],
                       [(100 + n * 5, 0, True) for n in range(4)]):
            with self.subTest(values=values), self.assertRaisesRegex(cloud.EnvironmentBlocked, "not established"):
                self.check_samples(iter(values), timeout=20)

    def test_reset_counters_or_unusable_evidence_block(self):
        for values in ([(100, 10, True), (90, 15, True)],
                       [(100, 10, True), (105, 5, True)], [("NaN", 0, True)]):
            with self.subTest(values=values), self.assertRaisesRegex(cloud.EnvironmentBlocked, "Cannot establish"):
                self.check_samples(iter(values))

    def test_read_timeout_is_retained_as_an_infrastructure_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            session = cloud.Session(Path(directory), {"versionName": "1.4.0", "versionCode": 292}, 1)
            with patch.object(session, "adb", side_effect=subprocess.TimeoutExpired("adb", 10)):
                with self.assertRaisesRegex(cloud.EnvironmentBlocked, "Cannot establish"):
                    session.wait_for_boot_load()
            samples = json.loads((Path(directory) / "boot-readiness.json").read_text())
        self.assertIn("timed out", samples[0]["error"])

    def test_boot_marker_is_exact_framework_user_zero_and_from_this_boot(self):
        valid = self.marker
        for marker in (valid, valid.replace("UserController", "ActivityManager")):
            self.assertEqual(marker, cloud.boot_readiness_evidence("2026-10-16T14:20:00", "100.00 30.00", marker)["boot_completed"])
        invalid = [valid.replace("u0", user) for user in ("u01", "u10", "u0 garbage")]
        invalid += [valid.replace("UserController", "Yfuse"), valid.replace(" I ", " W "),
                    valid.replace("Finished processing", "Posting"), valid.replace("14:19:50", "14:17:00"),
                    valid.replace("14:19:50", "14:30:00"), valid.replace("2026-", "2025-"),
                    valid.replace("BOOT_COMPLETED for u0", "BOOT_COMPLETED for\nu0")]
        for marker in invalid:
            with self.subTest(marker=marker):
                self.assertIsNone(cloud.boot_readiness_evidence("2026-10-16T14:20:00", "100.00 30.00", marker)["boot_completed"])

    def test_real_utc_year_output_from_all_supported_images(self):
        # Exact final callback lines captured from the API 35/36/37 diagnostic run.
        captures = [
            ("2026-10-16T14:35:07", "216.25 204.62", "2026-10-16 14:32:30.631 +0000   559   580 I ActivityManager: Finished processing BOOT_COMPLETED for u0"),
            ("2026-10-16T14:35:36", "222.42 202.19", "2026-10-16 14:33:13.865 +0000   620   681 I ActivityManager: Finished processing BOOT_COMPLETED for u0"),
            ("2026-10-16T14:35:50", "224.31 208.12", "2026-10-16 14:33:36.888 +0000   620   640 I UserController: Finished processing BOOT_COMPLETED for u0"),
        ]
        for now, uptime, marker in captures:
            with self.subTest(marker=marker):
                self.assertEqual(marker, cloud.boot_readiness_evidence(now, uptime, marker)["boot_completed"])
                for offset in ("+0800", "-0400", "UTC", ""):
                    self.assertIsNone(cloud.boot_readiness_evidence(now, uptime, marker.replace("+0000", offset))["boot_completed"])

    def test_invalid_uptime_never_becomes_readiness_evidence(self):
        for raw in ("", "NaN 1", "100 inf", "-1 20", "0 1", "100 20 extra", "100 " + "9" * 400):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                cloud.boot_readiness_evidence("2026-10-16T14:20:00", raw, self.marker)


class FailureOutputTest(unittest.TestCase):
    def test_diagnostics_preserve_anr_and_foreground_state_without_dismissing_dialogs(self):
        with tempfile.TemporaryDirectory() as directory:
            session = cloud.Session(Path(directory), {"versionName": "1.4.0", "versionCode": 292}, 1)
            session.serial = "emulator-5554"
            def adb(*args, **kwargs):
                return b"screenshot" if kwargs.get("binary") else "diagnostic evidence"
            with patch.object(session, "adb", side_effect=adb) as device:
                session.diagnostics()
            self.assertEqual("diagnostic evidence", (Path(directory) / "app-anr-dropbox.txt").read_text())
            self.assertEqual("diagnostic evidence", (Path(directory) / "activity-state.txt").read_text())
            self.assertEqual("diagnostic evidence", (Path(directory) / "window-state.txt").read_text())
            self.assertFalse(any("input" in call.args for call in device.call_args_list))

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
