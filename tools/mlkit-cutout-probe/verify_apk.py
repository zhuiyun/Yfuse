"""Verify the actual diagnostic APK, then stage the exact checked bytes for delivery."""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent


def parse_badging(badging):
    line = next((line for line in badging.splitlines() if line.startswith("package:")), "")
    # Codename/platformBuildVersionName must never overwrite the literal `name` attribute.
    fields = re.findall(r"(?:^|\s)(name|versionCode|versionName)='([^']*)'", line)
    if len(fields) != 3 or len(dict(fields)) != 3:
        raise ValueError("missing or duplicate APK package/version attributes")
    return dict(fields)


def main():
    config = dict(line.strip().split("=", 1) for line in (ROOT / "version.properties").read_text().splitlines()
                  if line.strip() and not line.startswith("#"))
    assert (ROOT / "release-notes.txt").read_text().splitlines()[0] == config["VERSION_NAME"]
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
    build_tools = sdk / "build-tools/37.0.0"
    badging = subprocess.run([str(build_tools / "aapt2"), "dump", "badging", str(apk)],
                             capture_output=True, text=True, check=True).stdout
    actual = parse_badging(badging)
    assert actual == {"name": config["APPLICATION_ID"], "versionCode": config["VERSION_CODE"],
                      "versionName": config["VERSION_NAME"]}, actual
    assert tuple(map(int, config["VERSION_NAME"].split("."))) > (0, 0, 1)
    assert int(config["VERSION_CODE"]) > 1
    assert re.fullmatch(r"\d\.\d\.\d", config["VERSION_NAME"])
    signature = subprocess.run([str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
                               capture_output=True, text=True, check=True).stdout
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None
        assert archive.read("classes.dex").startswith(b"dex\n")
        for name in ("whalefall", "heart-of-the-beast"):
            assert len(archive.read(f"assets/{name}.jpg")) > 0
    out = ROOT / "artifacts/apk"
    out.mkdir(parents=True, exist_ok=True)
    destination = out / f"Yfuse-MLKit-probe-{config['VERSION_NAME']}.apk"
    shutil.copyfile(apk, destination)
    metadata = {"package": actual["name"], "version_name": actual["versionName"],
                "version_code": int(actual["versionCode"]), "signing": "verified_diagnostic_debug_v2",
                "size_bytes": apk.stat().st_size, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                "commit": os.environ.get("GITHUB_SHA", "local"), "real_device_inference": "not_run_by_build"}
    (out / "apk-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (out / "signature.txt").write_text(signature)
    (out / "badging.txt").write_text(badging)
    shutil.copyfile(ROOT / "release-notes.txt", out / "release-notes.txt")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
