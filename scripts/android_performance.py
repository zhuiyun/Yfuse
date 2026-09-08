"""Run isolated Android performance variants, retain raw evidence, and reject incomplete results.

Uses only the Python standard library. Profile collection requires API 33+ or an already
rooted API 28+ device; this script never roots a phone or installs over the production app.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
TEST_NAMESPACE = "com.yfuse.macrobenchmark"
TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
EXPECTED = {
    "StartupBenchmark": ("coldStartupAndFirstFrames", "timeToInitialDisplayMs", "metrics"),
    "HomeJourneyBenchmark": ("homeScrollFrames", "frameDurationCpuMs", "sampledMetrics"),
}


def run(command: list[str], *, env=None, log: Path | None = None) -> str:
    if log:
        with log.open("w", encoding="utf-8") as output:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(f"Command failed ({result.returncode}); see {log}")
        return ""
    result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {result.stderr.strip()}")
    return result.stdout.strip()


def target_package(app_id: str) -> str:
    if not re.fullmatch(r"[A-Za-z]\w*(?:\.[A-Za-z]\w*)+", app_id) or app_id.endswith(".benchmark"):
        raise ValueError("Use the base application ID; the isolated .benchmark suffix is mandatory")
    return app_id + ".benchmark"


def require_profile_device(sdk: int, rooted: bool) -> None:
    if sdk < 28 or (sdk < 33 and not rooted):
        raise ValueError("Profile collection needs API 33+ or an already rooted API 28+ phone; no profile was generated")


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def numeric_samples(values, name: str) -> list[float]:
    if not isinstance(values, list) or not values:
        raise ValueError(f"Missing samples for {name}")
    if any(isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0 for value in values):
        raise ValueError(f"Invalid/non-positive samples for {name}")
    return [float(value) for value in values]


def summarize_results(documents: list[dict], iterations: int = 5) -> dict:
    selected = {}
    for document in documents:
        for benchmark in document.get("benchmarks", []):
            short_class = benchmark.get("className", "").split(".")[-1]
            if short_class not in EXPECTED:
                continue
            method, metric, bucket = EXPECTED[short_class]
            if benchmark.get("name", "").split("[")[0] != method:
                continue
            if short_class in selected:
                raise ValueError(f"Ambiguous duplicate benchmark result: {short_class}")
            measurement = benchmark.get(bucket, {}).get(metric)
            if not isinstance(measurement, dict):
                raise ValueError(f"Missing {metric} in {short_class}")
            runs = measurement.get("runs", [])
            if len(runs) != iterations:
                raise ValueError(f"Expected {iterations} measured iterations in {short_class}, got {len(runs)}")
            values = (
                [value for index, samples in enumerate(runs) for value in numeric_samples(samples, f"{metric}/{index}")]
                if bucket == "sampledMetrics" else numeric_samples(runs, metric)
            )
            selected[short_class] = {
                "method": method, "metric": metric, "iterations": len(runs), "samples": len(values),
                "median_ms": statistics.median(values), "p95_ms": percentile(values, 0.95),
                "params": benchmark.get("params", {}),
            }
    if set(selected) != set(EXPECTED):
        raise ValueError(f"Incomplete benchmark run; missing {sorted(set(EXPECTED) - set(selected))}")
    return selected


def compare_results(current: dict, baseline: dict, maximum_regression: float | None = None) -> dict:
    for key in ("device", "fixture_hash", "package", "variant", "runtime_profile"):
        if current.get(key) != baseline.get(key):
            raise ValueError(f"Incompatible baseline: {key} differs")
    changes = {}
    for name in EXPECTED:
        now, old = current["measurements"][name], baseline["measurements"][name]
        for key in ("iterations", "params", "metric"):
            if now.get(key) != old.get(key):
                raise ValueError(f"Incompatible baseline: {name}/{key} differs")
        field = "median_ms" if name == "StartupBenchmark" else "p95_ms"
        previous = old[field]
        if not isinstance(previous, (float, int)) or not math.isfinite(previous) or previous <= 0:
            raise ValueError("Invalid baseline measurement")
        change = 100 * (now[field] / previous - 1)
        changes[name] = {"metric": field, "baseline_ms": previous, "current_ms": now[field], "change_percent": change}
    if maximum_regression is not None and any(value["change_percent"] > maximum_regression for value in changes.values()):
        raise ValueError(f"Performance regression exceeded the explicitly selected {maximum_regression}% threshold: {changes}")
    return changes


def profile_rules(paths: list[Path]) -> list[str]:
    rules = set()
    for path in paths:
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if not re.match(r"^[HSP]*L[^;]+;", line):
                raise ValueError(f"Malformed profile rule in {path.name}")
            if "Lcom/yfuse/performance/" not in line:
                rules.add(line)
    if not any("Lcom/yfuse/" in line for line in rules):
        raise ValueError("Profile lacks unobfuscated production Yfuse methods/classes")
    return sorted(rules)


def export_profiles(raw: Path, destination: Path) -> dict:
    baseline = profile_rules(list(raw.rglob("*-baseline-prof.txt")))
    startup = profile_rules(list(raw.rglob("*-startup-prof.txt")))
    if not any("Lcom/yfuse/MainActivity;" in line for line in startup):
        raise ValueError("Startup profile did not capture the real MainActivity")
    destination.mkdir(parents=True, exist_ok=True)
    for name, rules in (("baseline-prof.txt", baseline), ("startup-prof.txt", startup)):
        (destination / name).write_text("\n".join(rules) + "\n", encoding="utf-8")
    return {"baseline_rules": len(baseline), "startup_rules": len(startup)}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def apksigner_command(apksigner: Path) -> list[str]:
    if not apksigner.is_file():
        raise ValueError("Android build-tools/apksigner is required to verify both APK signatures")
    if apksigner.suffix.lower() != ".bat":
        return [str(apksigner)]
    # Use the same jar as the SDK's Windows .bat launcher without passing SDK/APK
    # paths through cmd.exe expansion. JAVA_HOME is already needed for the build.
    jars = (apksigner.parent / "apksigner.jar", apksigner.parent / "lib/apksigner.jar",
            apksigner.parent.parent / "framework/apksigner.jar")
    jar = next((path for path in jars if path.is_file()), None)
    if jar is None:
        raise ValueError("The Windows apksigner.bat installation lacks apksigner.jar")
    java_home = os.environ.get("JAVA_HOME", "").strip('"')
    java = str(Path(java_home) / "bin/java.exe") if java_home else "java.exe"
    return [java, "-jar", str(jar)]


def test_instrumentation(xmltree: str) -> dict:
    declarations = []
    active = None
    depth = -1
    for line in xmltree.splitlines():
        indent = len(line) - len(line.lstrip())
        if active is not None and line.strip() and indent <= depth:
            active = None
        if re.match(r"\s*E: instrumentation(?:\s|$)", line):
            active = {}
            declarations.append(active)
            depth = indent
        elif active is not None and indent == depth + 2:
            match = re.match(r'\s*A: android:(name|targetPackage)\([^)]*\)="([^"]+)"', line)
            if match:
                key, value = match.groups()
                if key in active:
                    raise ValueError("Duplicate test instrumentation identity field")
                active[key] = value
    expected = {"name": TEST_RUNNER, "targetPackage": TEST_NAMESPACE}
    if declarations != [expected]:
        raise ValueError("Test APK must contain one AndroidJUnitRunner targeting its own isolated test package")
    return {"test_instrumentation_runner": TEST_RUNNER, "test_instrumentation_target": TEST_NAMESPACE}


def verify_apks(apk: Path, test_apk: Path, package: str, aapt: Path, apksigner: Path, output: Path) -> dict:
    for path in (apk, test_apk):
        if not path.is_file():
            raise ValueError(f"Built APK is missing: {path}")
    badging = run([str(aapt), "dump", "badging", str(apk)])
    match = re.search(r"package: name='([^']+)'", badging)
    if not match or match.group(1) != package or "application-debuggable" in badging:
        raise ValueError("Refusing a wrong-package or debuggable target APK")
    test_badging = run([str(aapt), "dump", "badging", str(test_apk)])
    match = re.search(r"package: name='([^']+)'", test_badging)
    if not match or match.group(1) != TEST_NAMESPACE:
        raise ValueError("Refusing a test APK with the wrong package identity")
    identity = test_instrumentation(run([str(aapt), "dump", "xmltree", str(test_apk), "AndroidManifest.xml"]))
    signer = apksigner_command(apksigner)
    for label, path in (("target", apk), ("test", test_apk)):
        run([*signer, "verify", "--verbose", "--print-certs", str(path)], log=output / f"{label}-signature.txt")
    identity.update({"package": package, "test_package": TEST_NAMESPACE,
                     "apk_sha256": sha256(apk), "test_apk_sha256": sha256(test_apk)})
    # Keep the validated binaries and identities even if connected tests subsequently fail.
    shutil.copy2(apk, output / "target.apk")
    shutil.copy2(test_apk, output / "test.apk")
    (output / "apk-identity.json").write_text(json.dumps(identity, indent=2), encoding="utf-8")
    return identity


def fixture_hash() -> str:
    digest = hashlib.sha256()
    files = sorted((ROOT / "composeApp/src/performance").rglob("*"))
    files += [ROOT / "macrobenchmark/src/main/kotlin/com/yfuse/macrobenchmark" / name for name in (
        "HomeJourney.kt", "HomeJourneyBenchmark.kt", "StartupBenchmark.kt",
    )]
    for path in files:
        if path.is_file():
            digest.update(str(path.relative_to(ROOT)).replace("\\", "/").encode())
            digest.update(path.read_bytes())
    return digest.hexdigest()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("benchmark", "profile"), default="benchmark")
    parser.add_argument("--serial", required=True, help="Explicit physical device serial")
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME"))
    parser.add_argument("--app-id", default="com.yfuse")
    parser.add_argument("--runtime-profile", choices=("native-only", "full"), default="native-only")
    parser.add_argument("--output", type=Path, required=True, help="A new directory; existing runs are never overwritten")
    parser.add_argument("--baseline", type=Path, help="summary.json from the same device and fixed journey")
    parser.add_argument("--max-regression-percent", type=float, help="Optional explicit gate; default only reports measurements")
    parser.add_argument("--export-profiles", action="store_true", help="After real profile capture, update production source rules")
    parser.add_argument("--gradle-arg", action="append", default=[])
    args = parser.parse_args(argv)
    if any(value.startswith(("-PyfuseApplicationId=", "-PyfuseNativeOnlyRuntime=")) for value in args.gradle_arg):
        raise ValueError("Use --app-id/--runtime-profile so the recorded benchmark identity remains accurate")
    package = target_package(args.app_id)
    if args.sdk is None:
        raise ValueError("Supply --sdk or ANDROID_SDK_ROOT")
    if args.max_regression_percent is not None and (not math.isfinite(args.max_regression_percent) or args.max_regression_percent < 0 or not args.baseline):
        raise ValueError("A finite nonnegative threshold requires --baseline")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    adb = str(args.sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
    connected = {line.split()[0]: line.split()[1] for line in run([adb, "devices"]).splitlines()[1:] if len(line.split()) >= 2}
    if connected.get(args.serial) != "device":
        raise ValueError("Requested device is missing, offline, or unauthorized")
    def shell(*command):
        return run([adb, "-s", args.serial, "shell", *command])
    if shell("getprop", "ro.kernel.qemu") == "1":
        raise ValueError("Use a physical device for comparable frame timings")
    sdk = int(shell("getprop", "ro.build.version.sdk"))
    if sdk < 28:
        raise ValueError("Macrobenchmark target requires API 28+")
    if args.mode == "profile":
        require_profile_device(sdk, shell("id", "-u") == "0")
    device = {
        "serial": args.serial, "model": shell("getprop", "ro.product.model"),
        "fingerprint": shell("getprop", "ro.build.fingerprint"), "sdk": sdk,
        "display_size": shell("wm", "size"), "display_density": shell("wm", "density"),
        "font_scale": shell("settings", "get", "system", "font_scale"),
        "peak_refresh_rate": shell("settings", "get", "system", "peak_refresh_rate"),
        "min_refresh_rate": shell("settings", "get", "system", "min_refresh_rate"),
        "animation_scales": {
            key: shell("settings", "get", "global", key)
            for key in ("animator_duration_scale", "window_animation_scale", "transition_animation_scale")
        },
    }
    variant = args.mode
    metadata = {"device": device, "package": package, "variant": variant, "runtime_profile": args.runtime_profile, "fixture_hash": fixture_hash(), "started_epoch": time.time()}
    (output / "device.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    (output / "battery-before.txt").write_text(shell("dumpsys", "battery"), encoding="utf-8")
    env = dict(os.environ, ANDROID_SERIAL=args.serial)
    gradle = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    native_only = str(args.runtime_profile == "native-only").lower()
    base = [gradle, "--no-daemon", "--console=plain", f"-PyfuseApplicationId={args.app_id}", f"-PyfuseNativeOnlyRuntime={native_only}", *args.gradle_arg]
    title = variant.capitalize()
    run([*base, f":composeApp:assemble{title}", f":macrobenchmark:assemble{title}"], env=env, log=output / "build.log")
    apk = ROOT / f"composeApp/build/outputs/apk/{variant}/composeApp-{variant}.apk"
    test_apk = ROOT / f"macrobenchmark/build/outputs/apk/{variant}/macrobenchmark-{variant}.apk"
    aapts = list((args.sdk / "build-tools").glob("*/aapt.exe" if os.name == "nt" else "*/aapt"))
    if not aapts:
        raise ValueError("Android build-tools/aapt is required for APK identity verification")
    aapt = sorted(aapts)[-1]
    apksigner = aapt.parent / ("apksigner.bat" if os.name == "nt" else "apksigner")
    metadata.update(verify_apks(apk, test_apk, package, aapt, apksigner, output))
    if variant == "benchmark":
        mapping = ROOT / "composeApp/build/outputs/mapping/benchmark/mapping.txt"
        if not mapping.is_file() or "com.yfuse." not in mapping.read_text(encoding="utf-8"):
            raise ValueError("R8 mapping missing: benchmark must use the optimized benchmark variant")
        metadata["r8_mapping_sha256"] = sha256(mapping)
    remote = f"/sdcard/Android/media/{TEST_NAMESPACE}/performance-{uuid.uuid4().hex}"
    shell("mkdir", "-p", remote)
    classes = (f"{TEST_NAMESPACE}.BaselineProfileGenerator" if variant == "profile" else ",".join(f"{TEST_NAMESPACE}.{name}" for name in EXPECTED))
    run([
        *base, f":macrobenchmark:connected{title}AndroidTest",
        # AGP normally uninstalls test APKs and removes their Android/media output.
        # Keep only this run's isolated target/test APKs so the following pull can read it.
        "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
        f"-Pandroid.testInstrumentationRunnerArguments.class={classes}",
        f"-Pandroid.testInstrumentationRunnerArguments.additionalTestOutputDir={remote}",
    ], env=env, log=output / "instrumentation.log")
    raw = output / "raw"
    run([adb, "-s", args.serial, "pull", remote, str(raw)])
    (output / "battery-after.txt").write_text(shell("dumpsys", "battery"), encoding="utf-8")
    if variant == "profile":
        metadata["profiles"] = export_profiles(raw, output / "profiles")
        if args.export_profiles:
            export_profiles(raw, ROOT / "composeApp/src/main")
    else:
        documents = [json.loads(path.read_text(encoding="utf-8")) for path in raw.rglob("*benchmarkData.json")]
        metadata["measurements"] = summarize_results(documents)
        (output / "summary.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False), encoding="utf-8")
        if args.baseline:
            baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
            metadata["comparison"] = compare_results(metadata, baseline)
    (output / "summary.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False), encoding="utf-8")
    if args.mode == "benchmark" and args.baseline and args.max_regression_percent is not None:
        # Even a threshold failure leaves the validated current measurements available for review.
        compare_results(metadata, baseline, args.max_regression_percent)
    print(f"Validated {variant} evidence: {output / 'summary.json'}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, RuntimeError, OSError, KeyError) as error:
        print(f"Performance run incomplete: {error}", file=sys.stderr)
        raise SystemExit(1)
