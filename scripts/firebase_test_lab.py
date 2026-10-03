"""Real-device runs of Yfuse on Firebase Test Lab, one step at a time.

auth          signs gcloud in with the service-account key held in the environment.
pick-devices  chooses physical phones from the Test Lab catalog, or checks the requested ones.
package       finds the signed APK in a package-run artifact and checks it against its manifest.
run           runs one test matrix, keeps its log and raw results, and writes the job summary.

The key is never printed. Every APK passed to `run` is uploaded to Google's Test Lab.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import uuid

MIN_SDK = 26
DEFAULT_LOCALE = "zh_CN"
DEFAULT_ORIENTATION = "portrait"
KEY_ENV = "FIREBASE_TEST_LAB_SERVICE_ACCOUNT"
PROJECT_ENV = "FIREBASE_TEST_LAB_PROJECT_ID"
SPEC_KEYS = ("model", "version", "locale", "orientation")
# A version with no phone online is never chosen; an unreported capacity ranks last.
CAPACITY = {"DEVICE_CAPACITY_HIGH": 3, "DEVICE_CAPACITY_MEDIUM": 2, "DEVICE_CAPACITY_LOW": 1}
CAPACITY_NAMES = {3: "high", 2: "medium", 1: "low", 0: "unreported"}
EXIT_MEANINGS = {
    0: "every test execution passed",
    1: "gcloud stopped before a result (arguments, upload, quota, permissions or network)",
    2: "gcloud rejected the command line",
    10: "at least one test case failed, or Robo hit a crash",
    15: "Test Lab could not tell whether the matrix passed (inconclusive)",
    18: "the device and version cannot run this APK",
    19: "the test matrix was cancelled",
    20: "Test Lab had an infrastructure error",
}
RAW_RESULTS = re.compile(r"Raw results will be stored in your GCS bucket at \[([^\]]+)\]")
CONSOLE = re.compile(r"(?:Test results will be streamed to|More details are available at) \[([^\]]+)\]")
TABLE_LINE = re.compile(r"^\s*(?:[┌├└│].*|\+[-=+]+\+|\|.*\|)\s*$")
ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


class Failure(Exception):
    """A setup or input problem that one line in the job log explains."""


def api_level(version) -> int | None:
    text = str(version)
    return int(text) if text.isdigit() else None


def unsuitable(tags) -> bool:
    return any(str(tag).startswith(("deprecated", "preview")) for tag in tags or ())


def physical_phone(model) -> bool:
    abis = model.get("supportedAbis") or ["arm64-v8a"]
    return (
        model.get("form") == "PHYSICAL"
        and model.get("formFactor", "PHONE") == "PHONE"
        and "arm64-v8a" in abis
        and not unsuitable(model.get("tags"))
    )


def best_version(model):
    """The model's most available, then newest, release version that can install the app."""
    info = {str(entry.get("versionId")): entry for entry in model.get("perVersionInfo") or ()}
    best = None
    for version in model.get("supportedVersionIds") or ():
        level = api_level(version)
        entry = info.get(str(version), {})
        capacity = entry.get("deviceCapacity")
        if level is None or level < MIN_SDK or capacity == "DEVICE_CAPACITY_NONE" or unsuitable(entry.get("tags")):
            continue
        key = (CAPACITY.get(capacity, 0), level)
        if best is None or key > best[0]:
            best = (key, str(version))
    return best


def describe(model, version, capacity=None) -> str:
    name = " ".join(part for part in (model.get("brand"), model.get("name")) if part) or model.get("id")
    form = "" if model.get("form") == "PHYSICAL" else f", {str(model.get('form', 'unknown')).lower()} device"
    availability = "" if capacity is None else f", {CAPACITY_NAMES[capacity]} availability"
    return f"{name} (`{model.get('id')}`), Android API {version}{availability}{form}"


def spec_line(spec) -> str:
    return ",".join(f"{key}={spec[key]}" for key in SPEC_KEYS if spec.get(key))


def choose(catalog, count=1, locale=DEFAULT_LOCALE, orientation=DEFAULT_ORIENTATION):
    """Picks the best-available newest phones; a second one comes from another manufacturer."""
    ranked = []
    for model in catalog:
        if not physical_phone(model):
            continue
        best = best_version(model)
        if best:
            (capacity, level), version = best
            order = (-capacity, -level, model.get("brand") != "Google", str(model.get("id")))
            ranked.append((order, model, version, capacity))
    ranked.sort(key=lambda item: item[0])
    chosen, makers = [], set()
    for other_maker_only in (True, False):
        for _, model, version, capacity in ranked:
            if len(chosen) == count:
                break
            if any(picked[0] is model for picked in chosen):
                continue
            if other_maker_only and model.get("manufacturer") in makers:
                continue
            chosen.append((model, version, capacity))
            makers.add(model.get("manufacturer"))
    if not chosen:
        raise Failure(f"Test Lab lists no physical arm64 phone with Android API {MIN_SDK} or newer")
    return [
        ({"model": model["id"], "version": version, "locale": locale, "orientation": orientation},
         describe(model, version, capacity))
        for model, version, capacity in chosen
    ]


def parse_specs(text):
    specs = []
    for raw in re.split(r"[;\n]", text or ""):
        raw = raw.strip()
        if not raw:
            continue
        fields = {}
        for part in raw.split(","):
            key, separator, value = part.partition("=")
            if not separator or not key.strip() or not value.strip():
                raise Failure(f"Device '{raw}' must read model=ID,version=API[,locale=..][,orientation=..]")
            fields[key.strip()] = value.strip()
        if not {"model", "version"} <= fields.keys() or not fields.keys() <= set(SPEC_KEYS):
            raise Failure(f"Device '{raw}' must read model=ID,version=API[,locale=..][,orientation=..]")
        fields.setdefault("locale", DEFAULT_LOCALE)
        fields.setdefault("orientation", DEFAULT_ORIENTATION)
        specs.append(fields)
    return specs


def check_requested(catalog, specs, locales=None):
    models = {str(model.get("id")): model for model in catalog}
    checked = []
    for spec in specs:
        model = models.get(spec["model"])
        if model is None:
            raise Failure(f"Test Lab has no device model '{spec['model']}'")
        if spec["version"] not in {str(version) for version in model.get("supportedVersionIds") or ()}:
            raise Failure(f"Test Lab offers no Android version {spec['version']} on '{spec['model']}'")
        if locales is not None and spec["locale"] not in locales:
            raise Failure(f"Test Lab has no locale '{spec['locale']}'")
        checked.append((spec, describe(model, spec["version"])))
    return checked


def pick_devices(catalog, requested="", count=1, locales=None):
    specs = parse_specs(requested)
    if specs:
        return check_requested(catalog, specs, locales)
    # Without a locale catalog the default is trusted; with one, an unknown default falls back
    # to Test Lab's own default rather than failing a run nobody configured.
    locale = DEFAULT_LOCALE if locales is None or DEFAULT_LOCALE in locales else None
    return choose(catalog, count, locale=locale)


def authenticate(environ=os.environ, run=subprocess.run) -> str:
    raw = environ.get(KEY_ENV, "").strip()
    if not raw:
        raise Failure(f"The {KEY_ENV} secret is not set")
    try:
        key = json.loads(raw)
    except ValueError:
        key = None
    if not isinstance(key, dict) or key.get("type") != "service_account" or not key.get("client_email") \
            or not key.get("private_key"):
        raise Failure(f"{KEY_ENV} must hold a service-account JSON key")
    project = environ.get(PROJECT_ENV, "").strip() or str(key.get("project_id") or "")
    if not re.fullmatch(r"[a-z][-a-z0-9.:]*[a-z0-9]", project):
        raise Failure(f"Set {PROJECT_ENV}, or use a key that names its project")
    descriptor, path = tempfile.mkstemp(suffix=".json")
    try:
        with os.fdopen(descriptor, "w") as handle:
            handle.write(raw)
        for command in (
            ["gcloud", "auth", "activate-service-account", key["client_email"], f"--key-file={path}", "--quiet"],
            ["gcloud", "config", "set", "project", project, "--quiet"],
        ):
            result = run(command, capture_output=True, text=True)
            if result.returncode:
                raise Failure(f"{' '.join(command[:3])} failed: {(result.stderr or '').strip()[-500:]}")
    finally:
        os.remove(path)
    return project


def find_package(directory):
    root = Path(directory)
    manifests = sorted(root.rglob("update.json"))
    apks = sorted(root.rglob("*.apk"))
    if len(manifests) != 1 or len(apks) != 1:
        raise Failure("The package artifact must hold exactly one APK and one update.json")
    manifest = json.loads(manifests[0].read_text())
    digest = hashlib.sha256()
    with apks[0].open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    if manifest.get("sha256") != digest.hexdigest() or manifest.get("size") != apks[0].stat().st_size:
        raise Failure(f"{apks[0].name} does not match the SHA-256 and size in its update manifest")
    return {"apk": str(apks[0]), "version_name": str(manifest.get("versionName")),
            "version_code": str(manifest.get("versionCode")), "sha256": digest.hexdigest()}


def single_apk(path) -> str:
    candidate = Path(path)
    if candidate.is_file():
        return str(candidate)
    apks = sorted(candidate.glob("*.apk"))
    if len(apks) != 1:
        raise Failure(f"Expected exactly one APK in {candidate}, found {len(apks)}")
    return str(apks[0])


def gcloud_command(test_type, app, devices, timeout, history, results_dir, test=None, bucket=None, label=None):
    command = ["gcloud", "firebase", "test", "android", "run", "--type", test_type, "--app", app]
    if test:
        # Each test in its own instrumentation: one crash cannot take the rest of the run with it.
        command += ["--test", test, "--use-orchestrator"]
    for device in devices:
        command += ["--device", device]
    command += ["--timeout", timeout, "--results-history-name", history, "--results-dir", results_dir]
    if bucket:
        command += ["--results-bucket", bucket]
    if label:
        command += ["--client-details", f"matrixLabel={label}"]
    return command + ["--quiet"]


def raw_results(log_text, results_dir, bucket=None):
    if bucket:
        return f"gs://{bucket}/{results_dir.strip('/')}"
    match = RAW_RESULTS.search(log_text)
    if not match:
        return None
    url = match.group(1).split("?", 1)[0]
    if url.startswith("gs://"):
        return url.rstrip("/")
    _, separator, path = url.partition("/storage/browser/")
    return f"gs://{path.strip('/')}" if separator and path.strip("/") else None


def console_link(log_text):
    links = CONSOLE.findall(log_text)
    return links[-1] if links else None


def summary(title, exit_code, devices, console, raw, downloaded, artifact, log_text):
    verdict = "passed" if exit_code == 0 else f"failed (exit {exit_code})"
    meaning = EXIT_MEANINGS.get(exit_code, "gcloud ended unexpectedly")
    lines = [f"### {title}", "", f"**Result:** {verdict}: {meaning}.", "", "**Devices:**"]
    lines += [f"- `{device}`" for device in devices]
    lines.append("")
    if console:
        lines.append(f"- [Firebase console]({console})")
    if raw:
        lines.append(f"- Raw results: `{raw}`" + ("" if downloaded else " (could not be copied into the artifact)"))
    lines.append(f"- Artifact `{artifact}`: gcloud log" + (", logcat, video, screenshots and test XML" if downloaded else ""))
    table = [line.rstrip() for line in log_text.splitlines() if TABLE_LINE.match(line)]
    if table:
        lines += ["", "```", *table, "```"]
    return "\n".join(lines) + "\n"


def stream(command, log_path, popen=subprocess.Popen) -> int:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w") as log, popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                          text=True, bufsize=1) as process:
        for line in process.stdout:
            sys.stdout.write(line)
            log.write(line)
    return process.returncode


def download(raw, destination, run=subprocess.run):
    destination.mkdir(parents=True, exist_ok=True)
    result = run(["gcloud", "storage", "cp", "--recursive", f"{raw}/*", str(destination)],
                 capture_output=True, text=True)
    return result.returncode == 0, (result.stderr or "").strip()[-1000:]


def write_outputs(path, values):
    if not path:
        return
    with open(path, "a") as handle:
        for name, value in values.items():
            delimiter = f"EOF_{uuid.uuid4().hex}"
            handle.write(f"{name}<<{delimiter}\n{value}\n{delimiter}\n")


def append(path, text):
    if path:
        with open(path, "a") as handle:
            handle.write(text)


def run_matrix(args, popen=subprocess.Popen, run=subprocess.run) -> int:
    devices = [line.strip() for line in args.devices.splitlines() if line.strip()]
    if not devices:
        raise Failure("No Test Lab device was chosen")
    output = Path(args.output)
    results_dir = args.results_dir.strip("/")
    command = gcloud_command(args.type, single_apk(args.app), devices, args.timeout, args.history, results_dir,
                             test=single_apk(args.test) if args.test else None, bucket=args.bucket or None,
                             label=args.label)
    print("+ " + shlex.join(command), flush=True)
    code = stream(command, output / "gcloud.log", popen)
    log_text = ANSI.sub("", (output / "gcloud.log").read_text(errors="replace"))
    raw = raw_results(log_text, results_dir, args.bucket or None)
    downloaded = False
    if raw:
        downloaded, error = download(raw, output / "results", run)
        if not downloaded:
            print(f"::warning::Raw results stay in {raw}; copying them failed: {error}")
    console = console_link(log_text)
    append(args.summary, summary(args.title, code, devices, console, raw, downloaded, args.artifact, log_text))
    write_outputs(args.github_output, {"exit_code": code, "console": console or "", "raw_results": raw or ""})
    return code


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("auth")
    pick = commands.add_parser("pick-devices")
    pick.add_argument("--catalog", type=Path, required=True, help="gcloud firebase test android models list --format=json")
    pick.add_argument("--locales", type=Path, help="gcloud firebase test android locales list --format=json")
    pick.add_argument("--requested", default="", help="model=ID,version=API entries separated by newlines or ';'")
    pick.add_argument("--count", type=int, default=1)
    pick.add_argument("--github-output")
    pick.add_argument("--summary")
    package = commands.add_parser("package")
    package.add_argument("--directory", type=Path, required=True)
    package.add_argument("--github-output")
    matrix = commands.add_parser("run")
    matrix.add_argument("--type", choices=("robo", "instrumentation"), required=True)
    matrix.add_argument("--app", required=True, help="APK file, or a directory holding exactly one")
    matrix.add_argument("--test", help="instrumentation APK file, or a directory holding exactly one")
    matrix.add_argument("--devices", required=True, help="one --device spec per line")
    matrix.add_argument("--timeout", default="15m")
    matrix.add_argument("--history", required=True)
    matrix.add_argument("--results-dir", required=True)
    matrix.add_argument("--bucket", default="")
    matrix.add_argument("--label")
    matrix.add_argument("--title", required=True)
    matrix.add_argument("--artifact", required=True)
    matrix.add_argument("--output", type=Path, required=True)
    matrix.add_argument("--github-output")
    matrix.add_argument("--summary")
    args = parser.parse_args(argv)
    try:
        if args.command == "auth":
            print(f"gcloud signed in to project {authenticate()}")
            return 0
        if args.command == "pick-devices":
            if not 1 <= args.count <= 5:
                parser.error("count must be between 1 and 5")
            locales = None
            if args.locales:
                locales = {str(entry.get("id")) for entry in json.loads(args.locales.read_text())}
            picked = pick_devices(json.loads(args.catalog.read_text()), args.requested, args.count, locales)
            for spec, description in picked:
                print(f"{spec_line(spec)}  # {description}")
            write_outputs(args.github_output, {"devices": "\n".join(spec_line(spec) for spec, _ in picked)})
            append(args.summary, "### Test Lab devices\n\n" + "".join(f"- {text}\n" for _, text in picked))
            return 0
        if args.command == "package":
            found = find_package(args.directory)
            print(f"Signed APK {Path(found['apk']).name}: {found['version_name']} ({found['version_code']}), "
                  f"SHA-256 {found['sha256']}")
            write_outputs(args.github_output, found)
            return 0
        if args.type == "instrumentation" and not args.test:
            parser.error("instrumentation needs --test")
        return run_matrix(args)
    except Failure as error:
        print(f"::error::{error}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
