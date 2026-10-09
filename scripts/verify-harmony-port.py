#!/usr/bin/env python3
"""Fast, deterministic checks for the Android-to-HarmonyOS parity workspace."""

from __future__ import annotations

import argparse
import re
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    import yaml
except ModuleNotFoundError:
    yaml = None


ROOT = Path(__file__).resolve().parents[1]
ANDROID_FEATURE_ROOT = ROOT / "composeApp/src/commonMain/kotlin/com/yfuse"


class CheckBlocked(RuntimeError):
    """A required validation tool/dependency is unavailable."""


def fail(message: str) -> None:
    raise AssertionError(message)


def load_json(relative: str) -> object:
    with (ROOT / relative).open(encoding="utf-8") as source:
        return json.load(source)


def check_contracts() -> None:
    if yaml is None:
        raise CheckBlocked("PyYAML missing; run python -m pip install -r scripts/requirements-validation.txt")
    tokens = load_json("parity/design-tokens.json")
    features = load_json("parity/feature-matrix.json")
    gates = load_json("parity/capability-gates.json")
    coverage = load_json("parity/implementation-coverage.json")
    with (ROOT / "parity/screen-catalog.yaml").open(encoding="utf-8") as source:
        screens = yaml.safe_load(source)

    if tokens["schemaVersion"] != 1:
        fail("unsupported design token schema")
    if features["schemaVersion"] != 1 or gates["schemaVersion"] != 1 or coverage["schemaVersion"] != 1:
        fail("unsupported parity schema")

    tabs = screens["rootNavigation"]
    expected_tabs = ["home", "library", "servers", "search", "profile"]
    if [tab["id"] for tab in tabs] != expected_tabs:
        fail("Harmony root navigation no longer matches Android")
    for tab in tabs:
        android_source = ANDROID_FEATURE_ROOT / tab["android"]
        if not android_source.is_file():
            fail(f"missing Android source-of-truth: {android_source.relative_to(ROOT)}")

    screen_ids = [screen["id"] for screen in screens["screens"]]
    if len(screen_ids) != len(set(screen_ids)):
        fail("duplicate screen id")
    feature_ids = [feature["id"] for feature in features["features"]]
    if len(feature_ids) != len(set(feature_ids)):
        fail("duplicate feature id")
    gate_ids = [gate["id"] for gate in gates["gates"]]
    if len(gate_ids) != len(set(gate_ids)):
        fail("duplicate capability gate id")

    target_ids = {feature["id"] for feature in features["features"] if feature["harmonyTarget"]}
    coverage_ids = [feature["id"] for feature in coverage["features"]]
    if len(coverage_ids) != len(set(coverage_ids)):
        fail("duplicate implementation coverage id")
    if set(coverage_ids) != target_ids:
        missing = sorted(target_ids - set(coverage_ids))
        extra = sorted(set(coverage_ids) - target_ids)
        fail(f"implementation coverage mismatch; missing={missing}, extra={extra}")
    # "implemented" once covered 22 features that no screen could reach, because the vocabulary
    # had no way to say "the code exists but nothing calls it". These statuses separate what
    # exists to build on from what a user can actually do.
    valid_statuses = {
        "implemented",
        "logicOnly",
        "contractOnly",
        "placeholderUi",
        "sdkAdapter",
        "capabilityGated",
        "physicalValidation",
        "sourceWired",
    }
    if set(coverage["statusVocabulary"]) != valid_statuses:
        fail("coverage status vocabulary does not match the statuses this check enforces")
    for item in coverage["features"]:
        status = item["status"]
        if status not in valid_statuses:
            fail(f"invalid coverage status for {item['id']}")
        for source_path in item["sources"]:
            if not (ROOT / source_path).is_file():
                fail(f"coverage source does not exist for {item['id']}: {source_path}")
        gate = item.get("gate")
        if gate is not None and gate not in gate_ids:
            fail(f"unknown coverage gate for {item['id']}: {gate}")
        if status in {"capabilityGated", "physicalValidation"} and gate is None:
            fail(f"coverage gate missing for {item['id']}")
        # Claiming a feature works requires naming the evidence; every other status must say
        # what is missing. Restoring "implemented" is therefore a visible, reviewable act.
        if status == "implemented":
            if not item.get("evidence"):
                fail(f"coverage claims {item['id']} is implemented without evidence")
        elif not item.get("note"):
            fail(f"coverage status {status} for {item['id']} needs a note stating what is missing")

    artwork = tokens["artworkSurface"]
    if "Never alter the full artwork bitmap" not in artwork["scope"]:
        fail("artwork color scope must preserve the Android bitmap")
    if screens["acceptance"]["behaviorContractPassRate"] != 1.0:
        fail("behavior parity cannot be relaxed")
    if screens["acceptance"]["unapprovedMissingFeatures"] != 0:
        fail("missing-feature allowance cannot be relaxed")
    baseline = screens["sourceOfTruth"]["commit"]
    if not re.fullmatch(r"[0-9a-f]{7,40}", baseline):
        fail("Android parity baseline commit is malformed")
    if (ROOT / ".git").exists() and shutil.which("git"):
        shallow = subprocess.run(
            ["git", "rev-parse", "--is-shallow-repository"],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        # A shallow CI checkout cannot see the baseline; a full clone must.
        if shallow.returncode == 0 and shallow.stdout.strip() == "false":
            probe = subprocess.run(
                ["git", "cat-file", "-e", f"{baseline}^{{commit}}"],
                cwd=ROOT,
                capture_output=True,
            )
            if probe.returncode != 0:
                fail(f"Android parity baseline commit {baseline} is not in this repository")
    for feature in features["features"]:
        if not feature["android"] and not feature.get("reason"):
            fail(f"feature {feature['id']} is marked absent on Android without a reason")


def check_scaffold() -> None:
    required = [
        "harmonyApp/build-profile.json5",
        "harmonyApp/hvigorfile.ts",
        "harmonyApp/entry/build-profile.json5",
        "harmonyApp/entry/hvigorfile.ts",
        "harmonyApp/entry/src/main/module.json5",
        "harmonyApp/entry/src/main/cangjie/cjpm.toml",
        "harmonyApp/entry/src/main/cangjie/src/ability_stage.cj",
        "harmonyApp/entry/src/main/cangjie/src/main_ability.cj",
        "harmonyApp/entry/src/main/cangjie/src/entry_view.cj",
        "harmonyApp/entry/src/main/cangjie/src/provider/provider_codec.cj",
        "harmonyApp/entry/src/main/cangjie/src/storage/secure_store.cj",
        "harmonyApp/entry/src/main/cangjie/src/ui/player_screen.cj",
        "harmonyApp/entry/src/main/cpp/CMakeLists.txt",
        "ycore-native/include/ycore/ycore.h",
        "ycore-native/src/ycore.cpp",
    ]
    missing = [path for path in required if not (ROOT / path).is_file()]
    if missing:
        fail("missing scaffold files: " + ", ".join(missing))

    module = (ROOT / "harmonyApp/entry/src/main/module.json5").read_text(encoding="utf-8")
    if '"srcEntry": "com.yfuse.harmony.entry.MainAbility"' not in module:
        fail("Cangjie ability bridge is not configured")
    for permission in ("ohos.permission.INTERNET", "ohos.permission.GET_NETWORK_INFO"):
        if permission not in module:
            fail(f"missing permission {permission}")

    root_hvigor = (ROOT / "harmonyApp/hvigorfile.ts").read_text(encoding="utf-8")
    entry_hvigor = (ROOT / "harmonyApp/entry/hvigorfile.ts").read_text(encoding="utf-8")
    if "@ohos/cangjie-build-support" not in root_hvigor + entry_hvigor:
        fail("Hvigor is not using Cangjie build support")

    models = (ROOT / "harmonyApp/entry/src/main/cangjie/src/data/models.cj").read_text(encoding="utf-8")
    saved_server = models[models.index("public class SavedServer"):models.index("public class AuthSession")]
    if "accessToken" in saved_server or "password" in saved_server.lower():
        fail("SavedServer must not persist credentials")

    native_cmake = (ROOT / "harmonyApp/entry/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
    if "message(FATAL_ERROR" not in native_cmake or "YFUSE_ENABLE_HARMONY_NATIVE_RENDERER" not in native_cmake:
        fail("native media features must fail closed")
    if "YCORE_BUILDING_LIBRARY=1" not in native_cmake:
        fail("Harmony native build must export the stable YCore ABI")


def check_native_core() -> None:
    compiler = shutil.which("g++") or shutil.which("clang++")
    if compiler is None:
        raise CheckBlocked("No host C++ compiler; install g++ or clang++ for portable YCore checks")
    with tempfile.TemporaryDirectory(prefix="yfuse-ycore-") as temp:
        executable = Path(temp) / "ycore_test"
        command = [
            compiler,
            "-std=c++17",
            "-Wall",
            "-Wextra",
            "-Wpedantic",
            "-Werror",
            "-fPIC",
            "-I",
            str(ROOT / "ycore-native/include"),
            str(ROOT / "ycore-native/src/ycore.cpp"),
            str(ROOT / "ycore-native/src/ycore_build.cpp"),
            str(ROOT / "harmonyApp/entry/src/main/cpp/ysecure_huks.cpp"),
            str(ROOT / "ycore-native/tests/ycore_test.cpp"),
            "-o",
            str(executable),
        ]
        subprocess.run(command, check=True)
        subprocess.run([str(executable)], check=True)

        shared_library = Path(temp) / "libycore.so"
        shared_command = [
            compiler,
            "-std=c++17",
            "-Wall",
            "-Wextra",
            "-Wpedantic",
            "-Werror",
            "-fPIC",
            "-fvisibility=hidden",
            "-DYCORE_BUILDING_LIBRARY=1",
            "-shared",
            "-I",
            str(ROOT / "ycore-native/include"),
            str(ROOT / "ycore-native/src/ycore.cpp"),
            str(ROOT / "ycore-native/src/ycore_build.cpp"),
            str(ROOT / "harmonyApp/entry/src/main/cpp/ysecure_huks.cpp"),
            "-o",
            str(shared_library),
        ]
        subprocess.run(shared_command, check=True)
        symbol_tool = shutil.which("nm")
        if symbol_tool is None:
            raise CheckBlocked("nm is required to verify the YCore shared-library ABI")
        symbols = subprocess.run(
            [symbol_tool, "-D", "--defined-only", str(shared_library)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        required_symbols = {
            "ycore_session_create",
            "ycore_session_open_values",
            "ycore_session_handover",
            "ycore_session_state_reason",
            "ycore_get_build_info",
            "ysecure_put",
            "ysecure_get",
            "ysecure_remove",
            "ysecure_random_reference",
        }
        missing_symbols = sorted(symbol for symbol in required_symbols if symbol not in symbols)
        if missing_symbols:
            fail("YCore shared library does not export: " + ", ".join(missing_symbols))


def check_cangjie_sources() -> None:
    subprocess.run([sys.executable, str(ROOT / "scripts/validate-cangjie-sources.py")], check=True)


def check_cangjie_host_build() -> dict:
    # Host verification is optional in source-only CI, but its actual coverage must survive
    # aggregation. Exit 0 alone does not distinguish complete, blocked and skipped runs.
    with tempfile.TemporaryDirectory(prefix="yfuse-cangjie-report-") as temp:
        report_path = Path(temp) / "host.json"
        completed = subprocess.run([sys.executable, str(ROOT / "scripts/verify-cangjie-host.py"),
                                    "--report", str(report_path)])
        if not report_path.is_file():
            fail(f"Cangjie verifier did not produce coverage (exit {completed.returncode})")
        report = json.loads(report_path.read_text(encoding="utf-8"))
        if report.get("status") not in {"passed", "partial", "skipped", "failed"}:
            fail("Invalid Cangjie host status")
        if completed.returncode != report.get("exitCode"):
            fail("Cangjie verifier exit code does not match its report")
        return report


def check_fixtures() -> None:
    auth = load_json("parity/fixtures/emby-auth.json")
    emby = load_json("parity/fixtures/emby-items.json")
    playback = load_json("parity/fixtures/emby-playback.json")
    plex = load_json("parity/fixtures/plex-metadata.json")
    matrix = load_json("parity/media-validation-matrix.json")
    if not auth.get("AccessToken") or not auth.get("User", {}).get("Id"):
        fail("invalid Emby auth fixture")
    if emby.get("TotalRecordCount") != len(emby.get("Items", [])):
        fail("invalid Emby item fixture")
    if not playback.get("MediaSources"):
        fail("invalid Emby playback fixture")
    if not plex.get("MediaContainer", {}).get("Metadata"):
        fail("invalid Plex fixture")
    matrix_ids = [case["id"] for case in matrix["cases"]]
    if len(matrix_ids) != len(set(matrix_ids)) or len(matrix_ids) < 12:
        fail("media matrix coverage is incomplete")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-host", action="store_true",
                        help="Reject skipped/blocked Cangjie host coverage with exit 2; does not validate a HAP")
    parser.add_argument("--report", type=Path, help="Write source and host results as JSON")
    args = parser.parse_args(argv)
    checks = [
        ("parity contracts", check_contracts),
        ("Harmony scaffold", check_scaffold),
        ("provider fixtures and media matrix", check_fixtures),
        ("Cangjie source structure", check_cangjie_sources),
        ("Cangjie host build and tests", check_cangjie_host_build),
        ("portable YCore", check_native_core),
    ]
    report = {"schemaVersion": 1, "scope": "harmony-source-and-host",
              "platformValidated": False, "requireHost": args.require_host, "checks": []}
    yaml_error = yaml.YAMLError if yaml is not None else ValueError
    for label, check in checks:
        item = {"name": label, "status": "passed"}
        try:
            details = check()
            if details is not None:
                item.update(status=details["status"], details=details)
        except CheckBlocked as error:
            item.update(status="blocked", reason=str(error))
        except (AssertionError, KeyError, ValueError, OSError,
                subprocess.CalledProcessError, yaml_error) as error:
            item.update(status="failed", reason=str(error))
        report["checks"].append(item)
        label_status = {"passed": "PASS", "failed": "FAIL", "blocked": "BLOCKED",
                        "partial": "PARTIAL", "skipped": "SKIP"}[item["status"]]
        print(f"{label_status} {label}" + (f": {item['reason']}" if "reason" in item else ""), flush=True)
    statuses = [item["status"] for item in report["checks"]]
    status = "failed" if "failed" in statuses else "blocked" if "blocked" in statuses else (
        "partial" if any(value != "passed" for value in statuses) else "passed")
    report["status"] = status
    exit_code = 1 if status == "failed" else 2 if status == "blocked" or (
        args.require_host and status != "passed") else 0
    report["exitCode"] = exit_code
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"{status.upper()} Harmony source/host verification; platform/HAP validation not performed.")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
