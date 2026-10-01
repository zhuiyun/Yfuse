"""Real-device runs must pick sensible phones, keep the key out of logs and report what ran."""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "firebase_test_lab.py"
WORKFLOW = ROOT / ".github" / "workflows" / "firebase-test-lab.yml"
SPEC = importlib.util.spec_from_file_location("firebase_test_lab", SCRIPT)
if SPEC is None or SPEC.loader is None:  # pragma: no cover - importlib contract guard
    raise RuntimeError(f"cannot load {SCRIPT}")
LAB = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LAB)

KEY = {
    "type": "service_account", "project_id": "yfuse-test-lab", "client_email": "lab@yfuse-test-lab.iam.gserviceaccount.com",
    "private_key": "-----BEGIN PRIVATE KEY-----\nSECRET-MATERIAL\n-----END PRIVATE KEY-----\n",
}


def phone(model_id, versions=("34",), brand="Google", maker="Google", capacity=None, **overrides):
    capacity = capacity or {}
    entry = {
        "id": model_id, "form": "PHYSICAL", "formFactor": "PHONE", "brand": brand, "manufacturer": maker,
        "name": model_id.title(), "supportedAbis": ["arm64-v8a"], "supportedVersionIds": list(versions), "tags": [],
        "perVersionInfo": [
            {"versionId": version, "deviceCapacity": capacity.get(version, "DEVICE_CAPACITY_HIGH")} for version in versions
        ],
    }
    entry.update(overrides)
    return entry


class Completed:
    def __init__(self, returncode=0, stderr=""):
        self.returncode = returncode
        self.stderr = stderr
        self.stdout = ""


class FakeProcess:
    def __init__(self, lines, code):
        self.stdout = iter(lines)
        self.returncode = None
        self._code = code

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.returncode = self._code
        return False


class DeviceChoiceTest(unittest.TestCase):
    def test_the_newest_well_stocked_physical_phone_is_chosen(self):
        catalog = [
            phone("emulator", versions=("36",), form="VIRTUAL"),
            phone("tablet", versions=("36",), formFactor="TABLET"),
            phone("retired", versions=("36",), tags=["deprecated=36"]),
            phone("arm32", versions=("36",), supportedAbis=["armeabi-v7a"]),
            phone("ancient", versions=("25",)),
            phone("galaxy", versions=("34",), brand="Samsung", maker="Samsung"),
            phone("shiba", versions=("34", "35")),
        ]
        [(spec, description)] = LAB.choose(catalog)
        self.assertEqual({"model": "shiba", "version": "35", "locale": "zh_CN", "orientation": "portrait"}, spec)
        self.assertIn("Android API 35", description)
        self.assertEqual("model=shiba,version=35,locale=zh_CN,orientation=portrait", LAB.spec_line(spec))

    def test_a_phone_that_is_online_beats_a_newer_one_in_a_queue(self):
        catalog = [
            phone("newest", versions=("36",), capacity={"36": "DEVICE_CAPACITY_LOW"}),
            phone("offline", versions=("37",), capacity={"37": "DEVICE_CAPACITY_NONE"}),
            phone("steady", versions=("35", "36"), capacity={"36": "DEVICE_CAPACITY_LOW"}),
        ]
        [(spec, _)] = LAB.choose(catalog)
        self.assertEqual(("steady", "35"), (spec["model"], spec["version"]))

    def test_a_second_phone_comes_from_another_manufacturer(self):
        catalog = [
            phone("shiba", versions=("35",)),
            phone("husky", versions=("35",)),
            phone("galaxy", versions=("34",), brand="Samsung", maker="Samsung"),
        ]
        self.assertEqual(["husky", "galaxy"], [spec["model"] for spec, _ in LAB.choose(catalog, count=2)])
        self.assertEqual(["husky", "galaxy", "shiba"], [spec["model"] for spec, _ in LAB.choose(catalog, count=3)])

    def test_an_empty_catalog_is_a_clear_failure(self):
        with self.assertRaisesRegex(LAB.Failure, "no physical arm64 phone"):
            LAB.choose([phone("emulator", form="VIRTUAL")])

    def test_requested_devices_are_checked_against_the_catalog(self):
        catalog = [phone("shiba", versions=("34", "35"))]
        picked = LAB.pick_devices(catalog, "model=shiba,version=34; model=shiba,version=35,locale=en_US\n",
                                  locales={"zh_CN", "en_US"})
        self.assertEqual(["model=shiba,version=34,locale=zh_CN,orientation=portrait",
                          "model=shiba,version=35,locale=en_US,orientation=portrait"],
                         [LAB.spec_line(spec) for spec, _ in picked])
        for requested, message in (
            ("model=husky,version=35", "no device model 'husky'"),
            ("model=shiba,version=36", "no Android version 36"),
            ("shiba", "must read model=ID,version=API"),
            ("model=shiba", "must read model=ID,version=API"),
            ("model=shiba,version=34,speed=fast", "must read model=ID,version=API"),
            ("model=shiba,version=34,locale=xx_YY", "no locale 'xx_YY'"),
        ):
            with self.subTest(requested=requested):
                with self.assertRaisesRegex(LAB.Failure, re.escape(message)):
                    LAB.pick_devices(catalog, requested, locales={"zh_CN", "en_US"})

    def test_an_unlisted_default_locale_falls_back_to_the_test_lab_default(self):
        [(spec, _)] = LAB.pick_devices([phone("shiba")], "", locales={"en_US"})
        self.assertEqual("model=shiba,version=34,orientation=portrait", LAB.spec_line(spec))


class AuthenticationTest(unittest.TestCase):
    def sign_in(self, environment, returncodes=(0, 0)):
        calls, key_files = [], []
        codes = iter(returncodes)

        def run(command, **_):
            calls.append(command)
            for argument in command:
                if argument.startswith("--key-file="):
                    path = Path(argument.split("=", 1)[1])
                    key_files.append((path, path.read_text(), path.stat().st_mode & 0o777))
            return Completed(next(codes), stderr="ERROR: permission denied")

        try:
            return LAB.authenticate(environment, run), calls, key_files
        finally:
            for path, _, _ in key_files:
                self.assertFalse(path.exists(), "the key file must not outlive the sign-in")

    def test_the_key_signs_in_and_is_removed(self):
        raw = json.dumps(KEY)
        project, calls, key_files = self.sign_in({LAB.KEY_ENV: raw})
        self.assertEqual("yfuse-test-lab", project)
        self.assertEqual([(key_files[0][0], raw, 0o600)], key_files)
        self.assertEqual(["gcloud", "auth", "activate-service-account", KEY["client_email"]], calls[0][:4])
        self.assertEqual(["gcloud", "config", "set", "project", "yfuse-test-lab", "--quiet"], calls[1])
        self.assertNotIn("SECRET-MATERIAL", json.dumps(calls))

    def test_a_configured_project_overrides_the_key(self):
        project, calls, _ = self.sign_in({LAB.KEY_ENV: json.dumps(KEY), LAB.PROJECT_ENV: "yfuse-lab-2"})
        self.assertEqual("yfuse-lab-2", project)
        self.assertIn("yfuse-lab-2", calls[1])

    def test_bad_configuration_fails_before_gcloud(self):
        for environment, message in (
            ({}, "secret is not set"),
            ({LAB.KEY_ENV: "not json"}, "service-account JSON key"),
            ({LAB.KEY_ENV: json.dumps(dict(KEY, type="authorized_user"))}, "service-account JSON key"),
            ({LAB.KEY_ENV: json.dumps(dict(KEY, project_id=""))}, "names its project"),
            ({LAB.KEY_ENV: json.dumps(KEY), LAB.PROJECT_ENV: "Bad Project"}, "names its project"),
        ):
            with self.subTest(message=message):
                with self.assertRaisesRegex(LAB.Failure, message):
                    LAB.authenticate(environment, lambda *_, **__: self.fail("gcloud must not run"))

    def test_a_rejected_key_still_removes_the_file(self):
        with self.assertRaisesRegex(LAB.Failure, "permission denied"):
            self.sign_in({LAB.KEY_ENV: json.dumps(KEY)}, returncodes=(1,))


class PackageTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.apk = self.root / "Yfuse-259-1.0.97.apk"
        self.apk.write_bytes(b"signed apk bytes")
        self.manifest = {"versionCode": 259, "versionName": "1.0.97", "size": self.apk.stat().st_size,
                         "sha256": hashlib.sha256(b"signed apk bytes").hexdigest()}
        (self.root / "update.json").write_text(json.dumps(self.manifest))
        (self.root / "update-v2.json").write_text(json.dumps(self.manifest))

    def test_the_signed_apk_matches_its_manifest(self):
        found = LAB.find_package(self.root)
        self.assertEqual((str(self.apk), "1.0.97", "259"), (found["apk"], found["version_name"], found["version_code"]))

    def test_a_changed_apk_or_a_second_one_is_refused(self):
        (self.root / "update.json").write_text(json.dumps(dict(self.manifest, sha256="0" * 64)))
        with self.assertRaisesRegex(LAB.Failure, "does not match"):
            LAB.find_package(self.root)
        (self.root / "update.json").write_text(json.dumps(self.manifest))
        (self.root / "other.apk").write_bytes(b"x")
        with self.assertRaisesRegex(LAB.Failure, "exactly one APK"):
            LAB.find_package(self.root)


class ResultsTest(unittest.TestCase):
    def test_raw_results_are_found_in_any_console_link_form(self):
        for line, expected in (
            ("Raw results will be stored in your GCS bucket at "
             "[https://console.developers.google.com/storage/browser/test-lab-abc-def/yfuse/7-1/robo/]",
             "gs://test-lab-abc-def/yfuse/7-1/robo"),
            ("Raw results will be stored in your GCS bucket at "
             "[https://console.cloud.google.com/storage/browser/test-lab-abc-def/yfuse/7-1/robo?project=p]",
             "gs://test-lab-abc-def/yfuse/7-1/robo"),
            ("Raw results will be stored in your GCS bucket at [gs://own-bucket/yfuse/7-1/robo/]",
             "gs://own-bucket/yfuse/7-1/robo"),
            ("nothing about results here", None),
        ):
            with self.subTest(line=line):
                self.assertEqual(expected, LAB.raw_results(line, "yfuse/7-1/robo"))
        self.assertEqual("gs://own/yfuse/7-1/robo", LAB.raw_results("", "/yfuse/7-1/robo/", bucket="own"))

    def test_the_summary_names_the_outcome_devices_and_table(self):
        log = "\n".join([
            "Test results will be streamed to [https://console.firebase.google.com/project/p/testlab/histories/h/matrices/1].",
            "14:00:00 Test is Pending",
            "┌─────────┬──────────────────────────┬──────────────┐",
            "│ OUTCOME │     TEST_AXIS_VALUE      │ TEST_DETAILS │",
            "│ Failed  │ shiba-35-zh_CN-portrait  │ 1 test failed│",
            "└─────────┴──────────────────────────┴──────────────┘",
        ])
        text = LAB.summary("Instrumented tests", 10, ["model=shiba,version=35"], LAB.console_link(log),
                           "gs://b/d", True, "test-lab-instrumentation-7-1", log)
        self.assertIn("**Result:** failed (exit 10): at least one test case failed", text)
        self.assertIn("- `model=shiba,version=35`", text)
        self.assertIn("(https://console.firebase.google.com/project/p/testlab/histories/h/matrices/1)", text)
        self.assertIn("│ Failed  │ shiba-35-zh_CN-portrait", text)
        self.assertNotIn("Test is Pending", text)


class RunMatrixTest(unittest.TestCase):
    def run_matrix(self, test_type, code=0, bucket="", download_code=0):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        (root / "app").mkdir()
        (root / "app" / "composeApp-debug.apk").write_bytes(b"app")
        (root / "test").mkdir()
        (root / "test" / "composeApp-debug-androidTest.apk").write_bytes(b"test")
        launched, copies = [], []
        lines = [
            "Raw results will be stored in your GCS bucket at "
            "[https://console.developers.google.com/storage/browser/test-lab-abc/yfuse/7-1/run/]\n",
            "More details are available at [https://console.firebase.google.com/project/p/testlab/histories/h/matrices/9].\n",
        ]

        def popen(command, **_):
            launched.append(command)
            return FakeProcess(lines, code)

        def run(command, **_):
            copies.append(command)
            return Completed(download_code, stderr="AccessDenied")

        args = argparse.Namespace(
            type=test_type, app=str(root / "app"), test=str(root / "test") if test_type == "instrumentation" else None,
            devices="model=shiba,version=35,locale=zh_CN,orientation=portrait\n", timeout="30m", history="History",
            results_dir="yfuse/7-1/run", bucket=bucket, label="Yfuse 1.0.97", title="Title", artifact="test-lab-7-1",
            output=root / "out", github_output=str(root / "outputs"), summary=str(root / "summary"),
        )
        with contextlib.redirect_stdout(io.StringIO()) as printed:
            result = LAB.run_matrix(args, popen=popen, run=run)
        return result, launched[0], copies, root, printed.getvalue()

    def test_instrumentation_runs_isolated_and_keeps_its_results(self):
        code, command, copies, root, _ = self.run_matrix("instrumentation", code=10)
        self.assertEqual(10, code)
        self.assertEqual(["gcloud", "firebase", "test", "android", "run", "--type", "instrumentation"], command[:7])
        self.assertIn(str(root / "test" / "composeApp-debug-androidTest.apk"), command)
        self.assertIn("--use-orchestrator", command)
        self.assertEqual(["--device", "model=shiba,version=35,locale=zh_CN,orientation=portrait"],
                         command[command.index("--device"):command.index("--device") + 2])
        self.assertNotIn("--results-bucket", command)
        self.assertEqual(["gcloud", "storage", "cp", "--recursive", "gs://test-lab-abc/yfuse/7-1/run/*",
                          str(root / "out" / "results")], copies[0])
        outputs = (root / "outputs").read_text()
        self.assertRegex(outputs, r"exit_code<<(EOF_\w+)\n10\n\1\n")
        self.assertIn("gs://test-lab-abc/yfuse/7-1/run", outputs)
        self.assertIn("failed (exit 10)", (root / "summary").read_text())
        self.assertIn("More details", (root / "out" / "gcloud.log").read_text())

    def test_robo_needs_no_test_apk_and_an_own_bucket_is_used(self):
        code, command, copies, _, _ = self.run_matrix("robo", bucket="own-results")
        self.assertEqual(0, code)
        self.assertNotIn("--test", command)
        self.assertNotIn("--use-orchestrator", command)
        self.assertEqual("own-results", command[command.index("--results-bucket") + 1])
        self.assertEqual("gs://own-results/yfuse/7-1/run/*", copies[0][4])

    def test_results_that_cannot_be_copied_are_reported_not_hidden(self):
        code, _, _, root, printed = self.run_matrix("robo", download_code=1)
        self.assertEqual(0, code)
        self.assertIn("::warning::Raw results stay in gs://test-lab-abc/yfuse/7-1/run", printed)
        self.assertIn("could not be copied into the artifact", (root / "summary").read_text())


class WorkflowContractTest(unittest.TestCase):
    def setUp(self):
        self.text = WORKFLOW.read_text()

    def test_it_runs_only_after_a_successful_package_or_on_request(self):
        triggers = self.text.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertEqual(["workflow_run", "workflow_dispatch"], re.findall(r"(?m)^  (\w+):", triggers))
        self.assertIn("workflows: [Publish Android update]", triggers)
        self.assertIn("github.event.workflow_run.conclusion == 'success'", self.text)

    def test_the_key_reaches_only_the_configuration_check_and_sign_in(self):
        steps = re.split(r"\n      - (?=name:|uses:)", self.text)
        holders = [re.match(r"name: (.+)", step).group(1) for step in steps if "secrets." in step]
        self.assertEqual({"Check Firebase configuration", "Sign in to Firebase"}, set(holders))
        self.assertEqual(4, len(holders))

    def test_permissions_stay_read_only(self):
        permissions = self.text.split("\npermissions:\n", 1)[1].split("\n\n", 1)[0]
        self.assertEqual("  contents: read\n  actions: read", permissions)
        self.assertNotIn("write", self.text)

    def test_third_party_actions_are_pinned(self):
        for action in re.findall(r"uses: (\S+)", self.text):
            with self.subTest(action=action):
                self.assertTrue(action.startswith("./") or re.fullmatch(r"[\w.-]+/[\w.-]+@[0-9a-f]{40}", action), action)

    def test_instrumentation_signs_in_only_after_the_build(self):
        job = self.text.split("\n  instrumentation:\n", 1)[1]
        self.assertLess(job.index("name: Build the debug app"), job.index("name: Sign in to Firebase"))

    def test_the_helper_runs_cleanly_from_the_command_line(self):
        result = subprocess.run(["python3", str(SCRIPT), "--help"], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("pick-devices", result.stdout)


if __name__ == "__main__":
    unittest.main()
