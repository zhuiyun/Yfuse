#!/usr/bin/env python3
"""Fast, deterministic checks for the Android-to-HarmonyOS parity workspace."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
ANDROID_FEATURE_ROOT = ROOT / "composeApp/src/commonMain/kotlin/com/yfuse"


def fail(message: str) -> None:
    raise AssertionError(message)


def load_json(relative: str) -> object:
    with (ROOT / relative).open(encoding="utf-8") as source:
        return json.load(source)


def check_contracts() -> None:
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
    valid_statuses = {"implemented", "sdkAdapter", "capabilityGated", "physicalValidation"}
    for item in coverage["features"]:
        if item["status"] not in valid_statuses:
            fail(f"invalid coverage status for {item['id']}")
        for source_path in item["sources"]:
            if not (ROOT / source_path).is_file():
                fail(f"coverage source does not exist for {item['id']}: {source_path}")
        if item["status"] in {"capabilityGated", "physicalValidation"} and item.get("gate") not in gate_ids:
            fail(f"coverage gate missing for {item['id']}")

    artwork = tokens["artworkSurface"]
    if "Never alter the full artwork bitmap" not in artwork["scope"]:
        fail("artwork color scope must preserve the Android bitmap")
    if screens["acceptance"]["behaviorContractPassRate"] != 1.0:
        fail("behavior parity cannot be relaxed")
    if screens["acceptance"]["unapprovedMissingFeatures"] != 0:
        fail("missing-feature allowance cannot be relaxed")
    if screens["sourceOfTruth"]["commit"] != "45d39439":
        fail("Android parity baseline is stale")


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
        fail("no host C++ compiler available")
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
            fail("nm is required to verify the YCore shared-library ABI")
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


def main() -> int:
    checks = [
        ("parity contracts", check_contracts),
        ("Harmony scaffold", check_scaffold),
        ("provider fixtures and media matrix", check_fixtures),
        ("Cangjie source structure", check_cangjie_sources),
        ("portable YCore", check_native_core),
    ]
    for label, check in checks:
        check()
        print(f"PASS {label}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, KeyError, OSError, subprocess.CalledProcessError, yaml.YAMLError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
