import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from android_performance import (
    apksigner_command, compare_results, export_profiles, main, require_profile_device,
    summarize_results, target_package,
)


def instrumentation_tree(target="com.yfuse.macrobenchmark", runner="androidx.test.runner.AndroidJUnitRunner"):
    return (
        'E: manifest (line=1)\n'
        '  E: instrumentation (line=9)\n'
        f'    A: android:name(0x01010003)="{runner}" (Raw: "{runner}")\n'
        f'    A: android:targetPackage(0x01010021)="{target}" (Raw: "{target}")\n'
        '  E: application (line=12)\n'
        '    A: android:name(0x01010003)="unrelated.Application"\n'
    )


def build_fixture(root, mode):
    sdk = root / "sdk"
    build_tools = sdk / "build-tools/36.0.0"
    (build_tools / "lib").mkdir(parents=True)
    for name in ("aapt", "aapt.exe", "apksigner", "apksigner.bat", "lib/apksigner.jar"):
        (build_tools / name).touch()
    apk = root / f"composeApp/build/outputs/apk/{mode}/composeApp-{mode}.apk"
    test_apk = root / f"macrobenchmark/build/outputs/apk/{mode}/macrobenchmark-{mode}.apk"
    for path, contents in ((apk, b"isolated target APK fixture"), (test_apk, b"test APK fixture")):
        path.parent.mkdir(parents=True)
        path.write_bytes(contents)
    mapping = root / "composeApp/build/outputs/mapping/benchmark/mapping.txt"
    mapping.parent.mkdir(parents=True)
    mapping.write_text("com.yfuse.MainActivity -> a:", encoding="utf-8")
    return sdk, apk, test_apk


def device_reply(command):
    if "devices" in command:
        return "List of devices attached\nphysical1\tdevice"
    if "shell" in command:
        if command[-1] == "ro.build.version.sdk":
            return "33"
        if command[-1] == "ro.kernel.qemu":
            return "0"
        return "fixture"
    return None


def measured_documents():
    return [{"benchmarks": [
        {"className": "com.yfuse.macrobenchmark.StartupBenchmark", "name": "coldStartupAndFirstFrames", "metrics": {
            "timeToInitialDisplayMs": {"runs": [400, 420, 410, 390, 430]},
        }},
        {"className": "com.yfuse.macrobenchmark.HomeJourneyBenchmark", "name": "homeScrollFrames", "sampledMetrics": {
            "frameDurationCpuMs": {"runs": [[8, 10, 12]] * 5},
        }},
    ]}]


class AndroidPerformanceTest(unittest.TestCase):
    def test_connected_run_retains_isolated_apks_until_raw_evidence_is_pulled(self):
        for mode in ("benchmark", "profile"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                sdk, apk, test_apk = build_fixture(root, mode)
                retained = False
                verified = []

                def fake_run(command, **kwargs):
                    nonlocal retained
                    device = device_reply(command)
                    if device is not None:
                        return device
                    if "badging" in command:
                        package = "com.yfuse.macrobenchmark" if command[-1] == str(test_apk) else "com.yfuse.benchmark"
                        return f"package: name='{package}'"
                    if "xmltree" in command:
                        return instrumentation_tree()
                    if "verify" in command:
                        verified.append(Path(command[-1]))
                    if f":macrobenchmark:connected{mode.capitalize()}AndroidTest" in command:
                        self.assertEqual([apk, test_apk], verified)
                        options = [value.split("=", 1)[1] for value in command if value.startswith(
                            "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun="
                        )]
                        retained = bool(options) and options[-1] == "true"
                    if "pull" in command:
                        if not retained:
                            raise RuntimeError("AGP removed the test package's Android/media output")
                        raw = Path(command[-1])
                        raw.mkdir()
                        if mode == "benchmark":
                            (raw / "results-benchmarkData.json").write_text(json.dumps(measured_documents()[0]), encoding="utf-8")
                        else:
                            for suffix in ("baseline", "startup"):
                                (raw / f"startup-{suffix}-prof.txt").write_text("HSLcom/yfuse/MainActivity;\n", encoding="utf-8")
                    return ""

                output = root / "evidence"
                with patch("android_performance.ROOT", root), patch("android_performance.fixture_hash", return_value="fixture"), patch(
                    "android_performance.run", side_effect=fake_run,
                ) as runner:
                    self.assertEqual(0, main([
                        "--mode", mode, "--serial", "physical1", "--sdk", str(sdk), "--output", str(output),
                        "--gradle-arg=-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=false",
                    ]))
                self.assertTrue((output / "summary.json").is_file())
                summary = json.loads((output / "summary.json").read_text(encoding="utf-8"))
                self.assertEqual(hashlib.sha256(test_apk.read_bytes()).hexdigest(), summary["test_apk_sha256"])
                self.assertEqual("com.yfuse.macrobenchmark", summary["test_package"])
                self.assertEqual(test_apk.read_bytes(), (output / "test.apk").read_bytes())
                self.assertEqual(summary["test_apk_sha256"], json.loads((output / "apk-identity.json").read_text())["test_apk_sha256"])
                commands = [call.args[0] for call in runner.call_args_list]
                self.assertFalse(any("uninstall" in command for command in commands))
                self.assertTrue(any("pull" in command for command in commands))

    def test_unsigned_target_or_test_apk_stops_before_connected(self):
        for unsigned in ("target", "test"):
            with self.subTest(unsigned=unsigned), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                sdk, apk, test_apk = build_fixture(root, "benchmark")
                rejected = apk if unsigned == "target" else test_apk

                def fake_run(command, **kwargs):
                    device = device_reply(command)
                    if device is not None:
                        return device
                    if "badging" in command:
                        package = "com.yfuse.macrobenchmark" if command[-1] == str(test_apk) else "com.yfuse.benchmark"
                        return f"package: name='{package}'"
                    if "xmltree" in command:
                        return instrumentation_tree()
                    if "verify" in command and command[-1] == str(rejected):
                        raise RuntimeError("apksigner: DOES NOT VERIFY, Missing META-INF/MANIFEST.MF")
                    return ""

                output = root / "evidence"
                with patch("android_performance.ROOT", root), patch("android_performance.fixture_hash", return_value="fixture"), patch(
                    "android_performance.run", side_effect=fake_run,
                ) as runner:
                    with self.assertRaisesRegex(RuntimeError, "DOES NOT VERIFY"):
                        main(["--serial", "physical1", "--sdk", str(sdk), "--output", str(output)])
                commands = [call.args[0] for call in runner.call_args_list]
                self.assertFalse(any(any("connectedBenchmark" in argument for argument in command) for command in commands))
                self.assertFalse((output / "summary.json").exists())
                self.assertFalse((output / "apk-identity.json").exists())

    def test_wrong_test_package_or_instrumentation_stops_before_connected(self):
        cases = (
            ("com.yfuse", instrumentation_tree()),
            ("com.yfuse.macrobenchmark", instrumentation_tree(target="com.yfuse")),
            ("com.yfuse.macrobenchmark", instrumentation_tree(runner="wrong.Runner")),
            ("com.yfuse.macrobenchmark", "E: manifest (line=1)\n"),
            ("com.yfuse.macrobenchmark", instrumentation_tree() + instrumentation_tree()),
        )
        for package, tree in cases:
            with self.subTest(package=package, tree=tree), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                sdk, _, test_apk = build_fixture(root, "benchmark")

                def fake_run(command, **kwargs):
                    device = device_reply(command)
                    if device is not None:
                        return device
                    if "badging" in command:
                        return f"package: name='{package if command[-1] == str(test_apk) else 'com.yfuse.benchmark'}'"
                    if "xmltree" in command:
                        return tree
                    return ""

                output = root / "evidence"
                with patch("android_performance.ROOT", root), patch("android_performance.fixture_hash", return_value="fixture"), patch(
                    "android_performance.run", side_effect=fake_run,
                ) as runner:
                    with self.assertRaises(ValueError):
                        main(["--serial", "physical1", "--sdk", str(sdk), "--output", str(output)])
                commands = [call.args[0] for call in runner.call_args_list]
                self.assertFalse(any(any("connectedBenchmark" in argument for argument in command) for command in commands))
                self.assertFalse((output / "summary.json").exists())

    def test_windows_bat_uses_its_official_jar_and_linux_executes_the_tool_directly(self):
        with tempfile.TemporaryDirectory(prefix="sdk path & ") as temporary:
            root = Path(temporary)
            (root / "lib").mkdir()
            (root / "lib/apksigner.jar").touch()
            (root / "apksigner.bat").touch()
            (root / "apksigner").touch()
            with patch.dict("os.environ", {"JAVA_HOME": str(root / "jdk path")}):
                self.assertEqual(
                    [str(root / "jdk path/bin/java.exe"), "-jar", str(root / "lib/apksigner.jar")],
                    apksigner_command(root / "apksigner.bat"),
                )
            self.assertEqual([str(root / "apksigner")], apksigner_command(root / "apksigner"))
            (root / "lib/apksigner.jar").unlink()
            with self.assertRaises(ValueError):
                apksigner_command(root / "apksigner.bat")

    def test_only_isolated_package_names_are_constructed(self):
        self.assertEqual("com.yfuse.benchmark", target_package("com.yfuse"))
        for bad in ("com.yfuse.benchmark", "com.yfuse;pm clear", "", "a"):
            with self.assertRaises(ValueError):
                target_package(bad)

    def test_stock_android9_cannot_claim_profile_collection(self):
        with self.assertRaises(ValueError):
            require_profile_device(28, False)
        require_profile_device(33, False)
        require_profile_device(28, True)

    def test_summary_requires_both_actual_five_iteration_metrics(self):
        summary = summarize_results(measured_documents())
        self.assertEqual(410, summary["StartupBenchmark"]["median_ms"])
        self.assertEqual(12, summary["HomeJourneyBenchmark"]["p95_ms"])
        self.assertEqual(15, summary["HomeJourneyBenchmark"]["samples"])
        with self.assertRaises(ValueError):
            summarize_results([])
        incomplete = measured_documents()
        incomplete[0]["benchmarks"].pop()
        with self.assertRaises(ValueError):
            summarize_results(incomplete)

    def test_empty_nan_and_missing_iterations_cannot_pass(self):
        for bad_runs in ([], [[8]] * 4, [[8], [8], [], [8], [8]], [[8], [8], [float("nan")], [8], [8]]):
            data = measured_documents()
            data[0]["benchmarks"][1]["sampledMetrics"]["frameDurationCpuMs"]["runs"] = bad_runs
            with self.assertRaises(ValueError):
                summarize_results(data)

    def test_duplicate_output_files_are_not_silently_double_counted(self):
        with self.assertRaises(ValueError):
            summarize_results(measured_documents() * 2)

    def test_comparison_rejects_other_phones_or_changed_journeys_and_only_gates_explicitly(self):
        baseline = {"device": {"serial": "phone1"}, "fixture_hash": "fixture1", "package": "com.yfuse.benchmark", "variant": "benchmark", "measurements": summarize_results(measured_documents())}
        current = copy.deepcopy(baseline)
        current["measurements"]["HomeJourneyBenchmark"]["p95_ms"] = 15
        self.assertEqual(25, compare_results(current, baseline)["HomeJourneyBenchmark"]["change_percent"])
        with self.assertRaises(ValueError):
            compare_results(current, baseline, 10)
        for key in ("device", "fixture_hash", "package", "variant"):
            wrong = copy.deepcopy(current)
            wrong[key] = "different"
            with self.assertRaises(ValueError):
                compare_results(wrong, baseline)

    def test_profile_export_requires_real_source_named_rules_and_excludes_fixture(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            raw = root / "raw"
            raw.mkdir()
            with self.assertRaises(ValueError):
                export_profiles(raw, root / "out")
            (raw / "startup-baseline-prof.txt").write_text("HSLcom/yfuse/MainActivity;->onCreate()V\nLcom/yfuse/performance/HomeFixtureActivity;\n", encoding="utf-8")
            (raw / "home-baseline-prof.txt").write_text("HLcom/yfuse/feature/home/HomeScreenKt;->HomeContentBody()V\n", encoding="utf-8")
            (raw / "startup-startup-prof.txt").write_text("HSLcom/yfuse/MainActivity;->onCreate()V\n", encoding="utf-8")
            counts = export_profiles(raw, root / "out")
            self.assertEqual(2, counts["baseline_rules"])
            self.assertNotIn("performance", (root / "out/baseline-prof.txt").read_text())
            self.assertNotIn("HomeScreen", (root / "out/startup-prof.txt").read_text())


if __name__ == "__main__":
    unittest.main()
