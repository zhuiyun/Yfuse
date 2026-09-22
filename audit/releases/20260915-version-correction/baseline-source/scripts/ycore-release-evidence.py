#!/usr/bin/env python3
"""Collect, merge, and verify privacy-redacted YCore device evidence."""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, Callable, Iterable


SCHEMA_VERSION = 1
PROFILES = {"matrix", "stress", "continuous_soak", "queue_soak"}
SHA1_RE = re.compile(r"^[0-9a-f]{40}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
RESULT_RE = re.compile(r"^INSTRUMENTATION_STATUS:\s*ycoreResult=(\{.*\})\s*$")
FAILED_MARKERS = ("FAILURES!!!", "INSTRUMENTATION_FAILED:", "Process crashed")
COUNT_FIELDS = (
    "droppedFrames",
    "decoderFailures",
    "maximumAbsoluteAvDriftMs",
    "peakPssBytes",
    "maximumThermalStatus",
    "seekCycles",
    "surfaceRecreations",
    "queueTransitions",
    "continuousSoakMinutes",
    "queueSoakMinutes",
)
MINIMUM_MEDIA_CASES = 18
REQUIRED_OPERATIONS = {
    "open", "play", "seek", "pause", "resume", "track_switch", "subtitle_switch",
    "surface_recreate", "background", "foreground", "finish", "next_episode",
}
REQUIRED_VIDEO_VARIANTS = {
    "h264:8", "h264:10", "hevc:8", "hevc:10", "vp9:10", "av1:8", "av1:10",
    "vc1:8", "prores:10",
}
REQUIRED_DOLBY_PROFILES = {"p5", "p7_mel", "p7_fel", "p8.1", "p8.4"}
REQUIRED_HDR = {"hdr10", "hdr10+", "hlg", "dolbyvision"}
REQUIRED_CONTAINERS = {"mp4", "mkv", "ts", "m2ts", "iso", "mov", "webm", "hls", "dash"}
REQUIRED_AUDIO = {"aac", "ac3", "eac3", "truehd", "dts-hd", "flac", "opus", "vorbis", "pcm_s24le"}
REQUIRED_SUBTITLES = {"srt", "ass", "pgs"}
REQUIRED_FRAME_RATES = (23.976, 24.0, 25.0, 29.97, 50.0, 59.94, 120.0)


class EvidenceError(ValueError):
    pass


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def require_sha(value: str, pattern: re.Pattern[str], label: str) -> str:
    stripped = value.strip()
    normalized = stripped.lower()
    if stripped != normalized or not pattern.fullmatch(normalized):
        raise EvidenceError(f"{label} is not a valid lowercase hexadecimal digest")
    return normalized


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise EvidenceError(f"cannot read {path}: {error}") from error
    if not isinstance(value, dict):
        raise EvidenceError(f"{path} must contain a JSON object")
    return value


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        handle.write(payload)
        temporary = Path(handle.name)
    temporary.replace(path)


def parse_observations(log_text: str) -> list[dict[str, Any]]:
    observations: list[dict[str, Any]] = []
    for line in log_text.splitlines():
        match = RESULT_RE.match(line.strip())
        if match is None:
            continue
        try:
            observation = json.loads(match.group(1))
        except json.JSONDecodeError as error:
            raise EvidenceError(f"invalid ycoreResult JSON: {error}") from error
        validate_observation(observation)
        observations.append(observation)
    case_ids = [item["caseId"] for item in observations]
    if len(case_ids) != len(set(case_ids)):
        raise EvidenceError("instrumentation log contains duplicate ycoreResult case IDs")
    return observations


def validate_observation(value: Any) -> None:
    if not isinstance(value, dict):
        raise EvidenceError("each ycoreResult must be a JSON object")
    if not isinstance(value.get("caseId"), str) or not value["caseId"].strip():
        raise EvidenceError("each ycoreResult requires a non-empty caseId")
    elapsed = value.get("elapsedMs")
    if not isinstance(elapsed, int) or isinstance(elapsed, bool) or elapsed < 0:
        raise EvidenceError(f"{value['caseId']}: elapsedMs must be a non-negative integer")
    for field in ("completed", "timedOut"):
        if not isinstance(value.get(field), bool):
            raise EvidenceError(f"{value['caseId']}: {field} must be a boolean")
    if "audioCodec" in value and value["audioCodec"] is not None and not isinstance(value["audioCodec"], str):
        raise EvidenceError(f"{value['caseId']}: audioCodec must be a string or null")
    for field in ("audioOutputRoute", "dolbyAtmosOutputMode"):
        if field in value and value[field] is not None and not isinstance(value[field], str):
            raise EvidenceError(f"{value['caseId']}: {field} must be a string or null")
    for field in (
        "serverTranscodeUsed",
        "audioOutputVerified",
        "audioOutputRouteVerified",
        "dolbyAtmosSourceDetected",
        "spatialAudioOutput",
        "dolbyAtmosOutput",
        "dolbyVisionOutput",
        "dolbyRpuApplied",
        "dolbyEnhancementLayerDelivered",
        "dolbyEnhancementLayerComposed",
    ):
        if field in value and not isinstance(value[field], bool):
            raise EvidenceError(f"{value['caseId']}: {field} must be a boolean")
    for field in COUNT_FIELDS:
        number = value.get(field, 0)
        if not isinstance(number, int) or isinstance(number, bool) or number < 0:
            raise EvidenceError(f"{value['caseId']}: {field} must be a non-negative integer")


def validate_suite(suite: dict[str, Any]) -> None:
    if suite.get("version") != 1:
        raise EvidenceError("suite version must be 1")
    operations = suite.get("operations")
    if not isinstance(operations, list) or not all(isinstance(item, str) for item in operations):
        raise EvidenceError("suite operations must be a string array")
    missing_operations = REQUIRED_OPERATIONS - {item.strip().lower() for item in operations}
    if missing_operations:
        raise EvidenceError(f"suite is missing operations: {', '.join(sorted(missing_operations))}")
    cases = suite.get("cases")
    if not isinstance(cases, list) or len(cases) < MINIMUM_MEDIA_CASES:
        raise EvidenceError(f"suite requires at least {MINIMUM_MEDIA_CASES} cases")
    if not all(isinstance(case, dict) for case in cases):
        raise EvidenceError("every suite case must be an object")
    case_ids = [str(case.get("id", "")).strip() for case in cases]
    if any(not case_id for case_id in case_ids) or len(case_ids) != len(set(case_ids)):
        raise EvidenceError("suite case IDs must be non-empty and unique")
    for case in cases:
        relative = str(case.get("relativePath", "")).replace("\\", "/")
        parts = relative.split("/")
        if (
            not relative
            or relative.startswith("/")
            or ":" in parts[0]
            or any(part in {"", ".", ".."} for part in parts)
        ):
            raise EvidenceError(f"{case.get('id')}: suite relativePath is unsafe")

    def values(name: str) -> set[str]:
        return {
            str(case[name]).strip().lower()
            for case in cases
            if case.get(name) is not None
        }

    video_variants = {
        f"{str(case.get('videoCodec', '')).strip().lower()}:{case.get('bitDepth')}"
        for case in cases
    }
    requirements = (
        ("video variants", REQUIRED_VIDEO_VARIANTS, video_variants),
        ("Dolby Vision profiles", REQUIRED_DOLBY_PROFILES, values("dolbyVisionProfile")),
        ("HDR families", REQUIRED_HDR, values("hdr")),
        ("containers", REQUIRED_CONTAINERS, values("container")),
        ("audio codecs", REQUIRED_AUDIO, values("audioCodec")),
        ("subtitle families", REQUIRED_SUBTITLES, values("subtitle")),
    )
    for label, required, actual in requirements:
        missing = required - actual
        if missing:
            raise EvidenceError(f"suite is missing {label}: {', '.join(sorted(missing))}")
    try:
        frame_rates = [float(case.get("frameRate", 0.0)) for case in cases]
        heights = [int(case.get("height", 0)) for case in cases]
        bitrates = [int(case.get("bitrateBitsPerSecond", 0)) for case in cases]
    except (TypeError, ValueError) as error:
        raise EvidenceError("suite contains non-numeric frame-rate, height, or bitrate data") from error
    missing_frame_rates = [
        required
        for required in REQUIRED_FRAME_RATES
        if not any(abs(actual - required) < 0.002 for actual in frame_rates)
    ]
    if missing_frame_rates:
        raise EvidenceError(f"suite is missing frame rates: {missing_frame_rates}")
    if not any(height <= 720 for height in heights) or not any(height >= 4320 for height in heights):
        raise EvidenceError("suite must cover both 720p-or-lower and 8K")
    if not any(value <= 1_000_000 for value in bitrates) or not any(value >= 150_000_000 for value in bitrates):
        raise EvidenceError("suite must cover both 1 Mbps-or-lower and 150 Mbps-or-higher")


def instrumentation_succeeded(log_text: str, observations: list[dict[str, Any]]) -> bool:
    final_codes = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", log_text, re.MULTILINE)
    failed_status = re.search(r"^INSTRUMENTATION_STATUS_CODE:\s*-2\s*$", log_text, re.MULTILINE)
    failed_marker = any(marker in log_text for marker in FAILED_MARKERS)
    return bool(observations) and final_codes[-1:] == ["-1"] and failed_status is None and not failed_marker


def build_report(args: argparse.Namespace) -> dict[str, Any]:
    log_path = Path(args.instrumentation_log)
    try:
        log_bytes = log_path.read_bytes()
        log_text = log_bytes.decode("utf-8", errors="replace")
    except OSError as error:
        raise EvidenceError(f"cannot read instrumentation log: {error}") from error
    observations = parse_observations(log_text)
    profile = args.profile.strip()
    if profile not in PROFILES:
        raise EvidenceError(f"unsupported profile: {profile}")
    if args.sdk < 1:
        raise EvidenceError("sdk must be positive")
    device_serial = args.device_serial.strip()
    if not device_serial:
        raise EvidenceError("device serial must not be empty")

    report_header = {
        "schemaVersion": SCHEMA_VERSION,
        "commitSha": require_sha(args.commit_sha, SHA1_RE, "commitSha"),
        "artifactSha256": require_sha(args.artifact_sha256, SHA256_RE, "artifactSha256"),
        "testedApkSha256": require_sha(args.apk_sha256, SHA256_RE, "testedApkSha256"),
        "testedTestApkSha256": require_sha(
            args.test_apk_sha256,
            SHA256_RE,
            "testedTestApkSha256",
        ),
        "generatedAtUtc": utc_now(),
    }
    run: dict[str, Any] = {
        "deviceIdHash": sha256_bytes(device_serial.encode()),
        "manufacturer": args.manufacturer.strip(),
        "model": args.model.strip(),
        "sdk": args.sdk,
        "chipset": args.chipset.strip(),
        "abi": args.abi.strip(),
        "profile": profile,
        "apkPurityVerified": bool(args.apk_purity_verified),
        "physicalDeviceVerified": bool(args.physical_device_verified),
        "instrumentationSucceeded": instrumentation_succeeded(log_text, observations),
        "sourceLogSha256": sha256_bytes(log_bytes),
        "observations": observations,
    }
    for label in ("manufacturer", "model", "chipset", "abi"):
        if not run[label]:
            raise EvidenceError(f"{label} must not be empty")
    run_identity = {
        "commitSha": report_header["commitSha"],
        "artifactSha256": report_header["artifactSha256"],
        "testedApkSha256": report_header["testedApkSha256"],
        "testedTestApkSha256": report_header["testedTestApkSha256"],
        "deviceIdHash": run["deviceIdHash"],
        "profile": run["profile"],
        "sourceLogSha256": run["sourceLogSha256"],
    }
    run["runId"] = sha256_bytes(canonical_bytes(run_identity))
    report_header["runs"] = [run]
    validate_report(report_header)
    return report_header


def validate_report(report: dict[str, Any]) -> None:
    if report.get("schemaVersion") != SCHEMA_VERSION:
        raise EvidenceError(f"unsupported schemaVersion: {report.get('schemaVersion')}")
    require_sha(str(report.get("commitSha", "")), SHA1_RE, "commitSha")
    require_sha(str(report.get("artifactSha256", "")), SHA256_RE, "artifactSha256")
    require_sha(str(report.get("testedApkSha256", "")), SHA256_RE, "testedApkSha256")
    require_sha(str(report.get("testedTestApkSha256", "")), SHA256_RE, "testedTestApkSha256")
    runs = report.get("runs")
    if not isinstance(runs, list):
        raise EvidenceError("runs must be an array")
    seen: set[str] = set()
    for run in runs:
        validate_run(run)
        identity = {
            "commitSha": report["commitSha"],
            "artifactSha256": report["artifactSha256"],
            "testedApkSha256": report["testedApkSha256"],
            "testedTestApkSha256": report["testedTestApkSha256"],
            "deviceIdHash": run["deviceIdHash"],
            "profile": run["profile"],
            "sourceLogSha256": run["sourceLogSha256"],
        }
        expected_run_id = sha256_bytes(canonical_bytes(identity))
        if run["runId"] != expected_run_id:
            raise EvidenceError(f"runId does not match bound run inputs: {run['runId']}")
        if run["runId"] in seen:
            raise EvidenceError(f"duplicate runId: {run['runId']}")
        seen.add(run["runId"])


def validate_run(run: Any) -> None:
    if not isinstance(run, dict):
        raise EvidenceError("each run must be an object")
    for field in ("runId", "deviceIdHash", "sourceLogSha256"):
        require_sha(str(run.get(field, "")), SHA256_RE, field)
    for field in ("manufacturer", "model", "chipset", "abi"):
        if not isinstance(run.get(field), str) or not run[field].strip():
            raise EvidenceError(f"run {field} must be non-empty")
    sdk = run.get("sdk")
    if not isinstance(sdk, int) or isinstance(sdk, bool) or sdk < 1:
        raise EvidenceError("run sdk must be a positive integer")
    if run.get("profile") not in PROFILES:
        raise EvidenceError(f"invalid run profile: {run.get('profile')}")
    for field in ("apkPurityVerified", "physicalDeviceVerified", "instrumentationSucceeded"):
        if not isinstance(run.get(field), bool):
            raise EvidenceError(f"run {field} must be a boolean")
    observations = run.get("observations")
    if not isinstance(observations, list):
        raise EvidenceError("run observations must be an array")
    for observation in observations:
        validate_observation(observation)


def merge_reports(reports: Iterable[dict[str, Any]]) -> dict[str, Any]:
    reports = list(reports)
    if not reports:
        raise EvidenceError("at least one evidence report is required")
    for report in reports:
        validate_report(report)
    binding = (
        reports[0]["commitSha"],
        reports[0]["artifactSha256"],
        reports[0]["testedApkSha256"],
        reports[0]["testedTestApkSha256"],
    )
    runs: dict[str, dict[str, Any]] = {}
    for report in reports:
        current = (
            report["commitSha"],
            report["artifactSha256"],
            report["testedApkSha256"],
            report["testedTestApkSha256"],
        )
        if current != binding:
            raise EvidenceError(
                "reports do not reference the same commit, YCore artifact, app APK, and test APK"
            )
        for run in report["runs"]:
            existing = runs.get(run["runId"])
            if existing is not None and existing != run:
                raise EvidenceError(f"conflicting duplicate runId: {run['runId']}")
            runs[run["runId"]] = run
    merged = {
        "schemaVersion": SCHEMA_VERSION,
        "commitSha": binding[0],
        "artifactSha256": binding[1],
        "testedApkSha256": binding[2],
        "testedTestApkSha256": binding[3],
        "generatedAtUtc": utc_now(),
        "runs": [runs[key] for key in sorted(runs)],
    }
    validate_report(merged)
    return merged


def gate(actual: Any, required: Any, measured: bool, passed: bool, detail: str) -> dict[str, Any]:
    return {
        "status": "NotMeasured" if not measured else ("Pass" if passed else "Fail"),
        "actual": actual,
        "required": required,
        "detail": detail,
    }


def observation_is_healthy(observation: dict[str, Any]) -> bool:
    return (
        observation.get("completed") is True
        and observation.get("timedOut") is False
        and not observation.get("failureCategory")
        and observation.get("decoderFailures", 0) == 0
        and observation.get("serverTranscodeUsed", False) is False
    )


def verify_evidence(
    report: dict[str, Any],
    suite: dict[str, Any],
    *,
    enforce_suite_contract: bool = True,
) -> dict[str, Any]:
    validate_report(report)
    if enforce_suite_contract:
        validate_suite(suite)
    cases = suite.get("cases")
    if not isinstance(cases, list) or not cases:
        raise EvidenceError("suite cases must be a non-empty array")
    case_ids = {case.get("id") for case in cases if isinstance(case, dict)}
    if None in case_ids or any(not isinstance(case_id, str) or not case_id for case_id in case_ids):
        raise EvidenceError("every suite case requires a non-empty id")
    if len(case_ids) != len(cases):
        raise EvidenceError("suite case IDs must be unique")
    suite_dolby_profiles = {
        str(case.get("dolbyVisionProfile", "")).strip().lower()
        for case in cases
        if isinstance(case, dict) and case.get("dolbyVisionProfile")
    }

    def normalized_audio_codec(value: Any) -> str:
        return str(value or "").strip().lower().replace("_", "-")

    atmos_case_ids = {
        case["id"]
        for case in cases
        if isinstance(case, dict)
        and normalized_audio_codec(case.get("audioCodec")) in {"eac3-joc", "truehd-atmos"}
    }
    dual_dolby_case_ids = {
        case["id"]
        for case in cases
        if isinstance(case, dict)
        and case.get("dolbyVisionProfile")
        and normalized_audio_codec(case.get("audioCodec")) in {"eac3-joc", "truehd-atmos"}
    }

    runs: list[dict[str, Any]] = report["runs"]
    all_observations = [observation for run in runs for observation in run["observations"]]
    instrumentation_ok = bool(runs) and all(run["instrumentationSucceeded"] for run in runs)
    packaging_ok = bool(runs) and all(run["apkPurityVerified"] for run in runs)
    physical_ok = bool(runs) and all(run["physicalDeviceVerified"] for run in runs)
    health_ok = bool(all_observations) and all(observation_is_healthy(item) for item in all_observations)
    healthy_runs = [
        run
        for run in runs
        if run["instrumentationSucceeded"]
        and run["physicalDeviceVerified"]
        and bool(run["observations"])
        and all(observation_is_healthy(item) for item in run["observations"])
    ]

    matrix_cases_by_device: dict[str, set[str]] = {}
    chipsets_by_device: dict[str, set[str]] = {}
    for run in healthy_runs:
        if run["profile"] != "matrix" or not run["apkPurityVerified"]:
            continue
        device = run["deviceIdHash"]
        matrix_cases_by_device.setdefault(device, set()).update(
            item["caseId"] for item in run["observations"] if item["caseId"] in case_ids
        )
        chipset = run["chipset"].strip().lower()
        if chipset not in {"unknown", "n/a", "none"}:
            chipsets_by_device.setdefault(device, set()).add(chipset)
    qualified_devices = {
        device for device, observed_cases in matrix_cases_by_device.items() if observed_cases >= case_ids
    }
    qualified_chipsets = {
        chipset
        for device in qualified_devices
        for chipset in chipsets_by_device.get(device, set())
    }

    def operation_total(field: str) -> int:
        return sum(item.get(field, 0) for run in healthy_runs for item in run["observations"])

    seeks = operation_total("seekCycles")
    surfaces = operation_total("surfaceRecreations")
    transitions = operation_total("queueTransitions")
    continuous_soak = max(
        (item.get("continuousSoakMinutes", 0) for run in healthy_runs for item in run["observations"]),
        default=0,
    )
    queue_soak = max(
        (item.get("queueSoakMinutes", 0) for run in healthy_runs for item in run["observations"]),
        default=0,
    )

    healthy_matrix_observations = [
        item
        for run in healthy_runs
        if run["profile"] == "matrix" and run["deviceIdHash"] in qualified_devices
        for item in run["observations"]
    ]

    def dolby_profile_observations(*profiles: str) -> list[dict[str, Any]]:
        accepted = {profile.lower() for profile in profiles}
        return [
            item
            for item in healthy_matrix_observations
            if str(item.get("dolbyVisionProfile", "")).lower() in accepted
        ]

    native_dolby = dolby_profile_observations("p5", "p8.1", "p8.4", "p10.1", "p10.4")
    p7 = dolby_profile_observations("p7_mel", "p7_fel", "p7_unknown")
    p7_fel = dolby_profile_observations("p7_fel")
    atmos_observations = [
        item for item in healthy_matrix_observations if item.get("caseId") in atmos_case_ids
    ]
    dual_dolby_observations = [
        item for item in healthy_matrix_observations if item.get("caseId") in dual_dolby_case_ids
    ]

    def dolby_gate(
        observations: list[dict[str, Any]],
        field: str,
        required_profiles: set[str],
        detail: str,
    ) -> dict[str, Any]:
        if not (suite_dolby_profiles & required_profiles):
            return gate(0, 0, True, True, "The selected suite does not require this Dolby variant.")
        passed_count = sum(1 for item in observations if item.get(field) is True)
        return gate(
            passed_count,
            len(observations),
            bool(observations),
            bool(observations) and passed_count == len(observations),
            detail,
        )

    def case_gate(
        observations: list[dict[str, Any]],
        required_case_ids: set[str],
        predicate: Callable[[dict[str, Any]], bool],
        detail: str,
    ) -> dict[str, Any]:
        if not required_case_ids:
            return gate(0, 0, True, True, "The selected suite does not require this output path.")
        passed_count = sum(1 for item in observations if predicate(item))
        return gate(
            passed_count,
            len(observations),
            bool(observations),
            bool(observations) and passed_count == len(observations),
            detail,
        )

    gates = {
        "artifactBinding": gate(
            f"{report['commitSha']}:{report['artifactSha256']}:"
            f"{report['testedApkSha256']}:{report['testedTestApkSha256']}",
            "one commit + one artifact + two APKs",
            True,
            True,
            "Every merged run is cryptographically bound to the same inputs.",
        ),
        "nativeOnlyApk": gate(
            sum(1 for run in runs if run["apkPurityVerified"]),
            len(runs),
            bool(runs),
            packaging_ok,
            "Every tested APK must pass verify-ycore-native-apk.sh.",
        ),
        "physicalDevicesOnly": gate(
            sum(1 for run in runs if run["physicalDeviceVerified"]),
            len(runs),
            bool(runs),
            physical_ok,
            "Every included run must pass the collector's emulator checks.",
        ),
        "instrumentation": gate(
            sum(1 for run in runs if run["instrumentationSucceeded"]),
            len(runs),
            bool(runs),
            instrumentation_ok,
            "Every included instrumentation process must complete without runner or JUnit failure.",
        ),
        "playbackHealth": gate(
            sum(1 for item in all_observations if observation_is_healthy(item)),
            len(all_observations),
            bool(all_observations),
            health_ok,
            "Every observation must complete without timeout, decoder failure, route failure, or transcode.",
        ),
        "matrixDevices": gate(
            len(qualified_devices),
            4,
            bool(matrix_cases_by_device),
            len(qualified_devices) >= 4,
            f"Each qualifying physical device must pass all {len(case_ids)} corpus cases.",
        ),
        "chipsetFamilies": gate(
            len(qualified_chipsets),
            3,
            bool(qualified_devices),
            len(qualified_chipsets) >= 3,
            "Qualified matrix devices must span at least three reported SoC families.",
        ),
        "seekCycles": gate(seeks, 1000, seeks > 0, seeks >= 1000, "Counted only from healthy runs."),
        "surfaceRecreations": gate(
            surfaces, 1000, surfaces > 0, surfaces >= 1000, "Counted only from healthy runs."
        ),
        "queueTransitions": gate(
            transitions, 100, transitions > 0, transitions >= 100, "Counted only from healthy runs."
        ),
        "continuousSoakMinutes": gate(
            continuous_soak,
            480,
            continuous_soak > 0,
            continuous_soak >= 480,
            "Requires one uninterrupted healthy run; shorter runs are not summed.",
        ),
        "queueSoakMinutes": gate(
            queue_soak,
            1440,
            queue_soak > 0,
            queue_soak >= 1440,
            "Requires one uninterrupted healthy run; shorter runs are not summed.",
        ),
        "dolbyAtmosPassthrough": case_gate(
            atmos_observations,
            atmos_case_ids,
            lambda item: item.get("audioOutputVerified") is True
            and item.get("audioOutputRouteVerified") is True
            and item.get("dolbyAtmosSourceDetected") is True
            and item.get("dolbyAtmosOutput") is True
            and item.get("dolbyAtmosOutputMode") in {"Eac3JocPassthrough", "TrueHdAtmosPassthrough"},
            "Every Atmos case must prove the active routed device and exact JOC/TrueHD-Atmos output mode; a source codec or carrier label is insufficient.",
        ),
        "nativeDualDolbyOutput": case_gate(
            dual_dolby_observations,
            dual_dolby_case_ids,
            lambda item: item.get("dolbyVisionOutput") is True
            and item.get("audioOutputVerified") is True
            and item.get("audioOutputRouteVerified") is True
            and item.get("dolbyAtmosSourceDetected") is True
            and item.get("dolbyAtmosOutput") is True
            and item.get("dolbyAtmosOutputMode") in {"Eac3JocPassthrough", "TrueHdAtmosPassthrough"},
            "Dolby Vision video and exact routed-device Atmos output must be verified in the same healthy observation.",
        ),
        "dolbyVisionNativeOutput": dolby_gate(
            native_dolby,
            "dolbyVisionOutput",
            {"p5", "p8.1", "p8.4", "p10.1", "p10.4"},
            "Every qualifying P5/P8/P10 observation must render verified native Dolby Vision output.",
        ),
        "dolbyVisionP7Rpu": dolby_gate(
            p7,
            "dolbyRpuApplied",
            {"p7_mel", "p7_fel", "p7_unknown"},
            "RPU must enter the exact Dolby decoder and be followed by verified native-DV output.",
        ),
        "dolbyVisionP7EnhancementDelivery": dolby_gate(
            p7,
            "dolbyEnhancementLayerDelivered",
            {"p7_mel", "p7_fel", "p7_unknown"},
            "P7 enhancement-layer delivery is measured separately from composition.",
        ),
        "dolbyVisionP7FelComposition": dolby_gate(
            p7_fel,
            "dolbyEnhancementLayerComposed",
            {"p7_fel"},
            "FEL requires independent output evidence; EL presence or decoder delivery never passes this gate.",
        ),
    }
    release_ready = all(item["status"] == "Pass" for item in gates.values())
    return {
        "schemaVersion": SCHEMA_VERSION,
        "commitSha": report["commitSha"],
        "artifactSha256": report["artifactSha256"],
        "testedApkSha256": report["testedApkSha256"],
        "testedTestApkSha256": report["testedTestApkSha256"],
        "suiteSha256": sha256_bytes(canonical_bytes(suite)),
        "verifiedAtUtc": utc_now(),
        "releaseReady": release_ready,
        "gates": gates,
    }


def print_gate_summary(result: dict[str, Any]) -> None:
    print(f"YCore release evidence: {'READY' if result['releaseReady'] else 'NOT READY'}")
    for name, item in result["gates"].items():
        print(f"  {item['status']:11} {name}: {item['actual']} / {item['required']}")


def collect_command(args: argparse.Namespace) -> int:
    report = build_report(args)
    write_json(Path(args.output), report)
    run = report["runs"][0]
    state = "passed" if run["instrumentationSucceeded"] else "failed"
    print(f"wrote redacted {args.profile} evidence ({state}): {args.output}")
    return 0 if run["instrumentationSucceeded"] else 1


def merge_command(args: argparse.Namespace) -> int:
    report = merge_reports(load_json(Path(path)) for path in args.reports)
    write_json(Path(args.output), report)
    print(f"merged {len(report['runs'])} unique runs: {args.output}")
    return 0


def verify_command(args: argparse.Namespace) -> int:
    report = load_json(Path(args.evidence))
    suite = load_json(Path(args.suite))
    result = verify_evidence(report, suite)
    if args.output:
        write_json(Path(args.output), result)
    print_gate_summary(result)
    return 0 if result["releaseReady"] else 1


class EvidenceSelfTest(unittest.TestCase):
    def test_release_suite_contract_rejects_reduced_or_relabelled_corpus(self) -> None:
        example = load_json(Path(__file__).resolve().parent.parent / "media-tests/ycore-suite.example.json")
        validate_suite(example)

        reduced = copy.deepcopy(example)
        reduced["cases"] = reduced["cases"][:1]
        with self.assertRaisesRegex(EvidenceError, "at least 18"):
            validate_suite(reduced)

        missing_hdr10_plus = copy.deepcopy(example)
        for case in missing_hdr10_plus["cases"]:
            if str(case.get("hdr", "")).lower() == "hdr10+":
                case["hdr"] = "HDR10"
        with self.assertRaisesRegex(EvidenceError, "HDR families"):
            validate_suite(missing_hdr10_plus)

    def test_instrumentation_parser_requires_successful_runner_completion(self) -> None:
        payload = {
            "caseId": "baseline-smoke",
            "elapsedMs": 10,
            "completed": True,
            "timedOut": False,
            "seekCycles": 3,
        }
        successful_log = (
            "INSTRUMENTATION_STATUS: ycoreResult="
            + json.dumps(payload, separators=(",", ":"))
            + "\nINSTRUMENTATION_STATUS_CODE: 3\nINSTRUMENTATION_CODE: -1\n"
        )
        observations = parse_observations(successful_log)
        self.assertEqual(3, observations[0]["seekCycles"])
        self.assertTrue(instrumentation_succeeded(successful_log, observations))
        self.assertFalse(
            instrumentation_succeeded(
                successful_log.replace("INSTRUMENTATION_STATUS_CODE: 3", "INSTRUMENTATION_STATUS_CODE: -2"),
                observations,
            )
        )

    def test_ready_requires_full_per_device_matrix_and_non_summed_soaks(self) -> None:
        case_ids = [f"case-{index}" for index in range(18)]
        suite = {"cases": [{"id": case_id} for case_id in case_ids]}
        runs: list[dict[str, Any]] = []

        def observation(case_id: str, **metrics: int) -> dict[str, Any]:
            value: dict[str, Any] = {
                "caseId": case_id,
                "elapsedMs": 1,
                "completed": True,
                "timedOut": False,
            }
            value.update(metrics)
            return value

        for index in range(4):
            runs.append(
                self.device_run(
                    f"device-{index}",
                    "matrix",
                    f"chip-{index % 3}",
                    [observation(case_id) for case_id in case_ids],
                )
            )
        runs.extend(
            [
                self.device_run(
                    "device-0",
                    "stress",
                    "chip-0",
                    [observation("stress", seekCycles=1000, surfaceRecreations=1000)],
                ),
                self.device_run(
                    "device-1",
                    "continuous_soak",
                    "chip-1",
                    [observation("soak-single-item", continuousSoakMinutes=480)],
                ),
                self.device_run(
                    "device-2",
                    "queue_soak",
                    "chip-2",
                    [observation("soak-queue", queueSoakMinutes=1440, queueTransitions=144)],
                ),
            ]
        )
        report = self.report(runs)
        self.assertTrue(verify_evidence(report, suite, enforce_suite_contract=False)["releaseReady"])

        partial = copy.deepcopy(report)
        partial["runs"][0]["observations"].pop()
        result = verify_evidence(partial, suite, enforce_suite_contract=False)
        self.assertFalse(result["releaseReady"])
        self.assertEqual("Fail", result["gates"]["matrixDevices"]["status"])

        short_soaks = copy.deepcopy(report)
        short_soaks["runs"][-2]["observations"][0]["continuousSoakMinutes"] = 240
        short_soaks["runs"].append(
            self.device_run(
                "device-3",
                "continuous_soak",
                "chip-0",
                [observation("soak-single-item-2", continuousSoakMinutes=240)],
            )
        )
        result = verify_evidence(short_soaks, suite, enforce_suite_contract=False)
        self.assertFalse(result["releaseReady"])
        self.assertEqual(240, result["gates"]["continuousSoakMinutes"]["actual"])

    def test_p7_fel_gate_never_promotes_layer_delivery_to_composition(self) -> None:
        suite = {"cases": [{"id": "p7-fel", "dolbyVisionProfile": "p7_fel"}]}
        observation = {
            "caseId": "p7-fel",
            "elapsedMs": 1,
            "completed": True,
            "timedOut": False,
            "dolbyVisionProfile": "p7_fel",
            "dolbyVisionOutput": True,
            "dolbyRpuApplied": True,
            "dolbyEnhancementLayerDelivered": True,
            "dolbyEnhancementLayerComposed": False,
        }
        report = self.report([self.device_run("device-0", "matrix", "chip-0", [observation])])
        result = verify_evidence(report, suite, enforce_suite_contract=False)
        self.assertEqual("Pass", result["gates"]["dolbyVisionP7Rpu"]["status"])
        self.assertEqual("Pass", result["gates"]["dolbyVisionP7EnhancementDelivery"]["status"])
        self.assertEqual("Fail", result["gates"]["dolbyVisionP7FelComposition"]["status"])

        proven = copy.deepcopy(report)
        proven["runs"][0]["observations"][0]["dolbyEnhancementLayerComposed"] = True
        self.assertEqual(
            "Pass",
            verify_evidence(proven, suite, enforce_suite_contract=False)["gates"]["dolbyVisionP7FelComposition"]["status"],
        )

    def test_native_dual_dolby_requires_video_and_audio_evidence_in_one_observation(self) -> None:
        suite = {
            "cases": [
                {
                    "id": "p8-atmos",
                    "dolbyVisionProfile": "p8.1",
                    "audioCodec": "eac3-joc",
                }
            ]
        }
        observation = {
            "caseId": "p8-atmos",
            "elapsedMs": 1,
            "completed": True,
            "timedOut": False,
            "dolbyVisionProfile": "p8.1",
            "audioCodec": "eac3-joc",
            "dolbyVisionOutput": True,
            "audioOutputVerified": True,
            "dolbyAtmosOutput": False,
        }
        report = self.report([self.device_run("device-0", "matrix", "chip-0", [observation])])
        result = verify_evidence(report, suite, enforce_suite_contract=False)
        self.assertEqual("Fail", result["gates"]["dolbyAtmosPassthrough"]["status"])
        self.assertEqual("Fail", result["gates"]["nativeDualDolbyOutput"]["status"])

        proven = copy.deepcopy(report)
        proven["runs"][0]["observations"][0]["dolbyAtmosOutput"] = True
        proven["runs"][0]["observations"][0]["audioOutputRoute"] = "HDMI eARC"
        proven["runs"][0]["observations"][0]["audioOutputRouteVerified"] = True
        proven["runs"][0]["observations"][0]["dolbyAtmosSourceDetected"] = True
        proven["runs"][0]["observations"][0]["dolbyAtmosOutputMode"] = "Eac3JocPassthrough"
        result = verify_evidence(proven, suite, enforce_suite_contract=False)
        self.assertEqual("Pass", result["gates"]["dolbyAtmosPassthrough"]["status"])
        self.assertEqual("Pass", result["gates"]["nativeDualDolbyOutput"]["status"])

    @staticmethod
    def device_run(
        device: str,
        profile: str,
        chipset: str,
        observations: list[dict[str, Any]],
    ) -> dict[str, Any]:
        seed = f"{device}:{profile}:{len(observations)}:{observations[-1]}"
        run = {
            "deviceIdHash": sha256_bytes(device.encode()),
            "manufacturer": "YCore",
            "model": device,
            "sdk": 35,
            "chipset": chipset,
            "abi": "arm64-v8a",
            "profile": profile,
            "apkPurityVerified": True,
            "physicalDeviceVerified": True,
            "instrumentationSucceeded": True,
            "sourceLogSha256": sha256_bytes((seed + ":log").encode()),
            "observations": observations,
        }
        identity = {
            "commitSha": "1" * 40,
            "artifactSha256": "2" * 64,
            "testedApkSha256": "3" * 64,
            "testedTestApkSha256": "4" * 64,
            "deviceIdHash": run["deviceIdHash"],
            "profile": profile,
            "sourceLogSha256": run["sourceLogSha256"],
        }
        run["runId"] = sha256_bytes(canonical_bytes(identity))
        return run

    @staticmethod
    def report(runs: list[dict[str, Any]]) -> dict[str, Any]:
        return {
            "schemaVersion": 1,
            "commitSha": "1" * 40,
            "artifactSha256": "2" * 64,
            "testedApkSha256": "3" * 64,
            "testedTestApkSha256": "4" * 64,
            "generatedAtUtc": "2026-01-01T00:00:00Z",
            "runs": runs,
        }


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)

    collect = commands.add_parser("collect", help="collect one adb instrumentation log")
    collect.add_argument("--instrumentation-log", required=True)
    collect.add_argument("--output", required=True)
    collect.add_argument("--commit-sha", required=True)
    collect.add_argument("--artifact-sha256", required=True)
    collect.add_argument("--apk-sha256", required=True)
    collect.add_argument("--test-apk-sha256", required=True)
    collect.add_argument("--device-serial", required=True)
    collect.add_argument("--manufacturer", required=True)
    collect.add_argument("--model", required=True)
    collect.add_argument("--sdk", required=True, type=int)
    collect.add_argument("--chipset", required=True)
    collect.add_argument("--abi", required=True)
    collect.add_argument("--profile", choices=sorted(PROFILES), required=True)
    collect.add_argument("--apk-purity-verified", action="store_true")
    collect.add_argument("--physical-device-verified", action="store_true")
    collect.set_defaults(handler=collect_command)

    merge = commands.add_parser("merge", help="merge reports bound to the same build")
    merge.add_argument("--output", required=True)
    merge.add_argument("reports", nargs="+")
    merge.set_defaults(handler=merge_command)

    verify = commands.add_parser("verify", help="evaluate all native release gates")
    verify.add_argument("--evidence", required=True)
    verify.add_argument("--suite", required=True)
    verify.add_argument("--output")
    verify.set_defaults(handler=verify_command)
    return root


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv == ["--self-test"]:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(EvidenceSelfTest)
        return 0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1
    try:
        args = parser().parse_args(argv)
        return args.handler(args)
    except EvidenceError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
