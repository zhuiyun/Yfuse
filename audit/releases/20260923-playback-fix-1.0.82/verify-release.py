"""Verify both locally delivered production-signed APKs without signing secrets."""

import hashlib
import json
import re
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path


AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parents[2]
OUT = ROOT / "artifacts/releases/playback-fix-1.0.82-244"
TOOLS = Path("D:/AndroidSDK/build-tools/36.0.0")
JAVA = Path("C:/Users/app-inkbird/.jdks/ms-21.0.9/bin/java.exe")
PREVIOUS = (
    ROOT
    / "artifacts/releases/performance-1.0.81-243"
    / "Yfuse-1.0.81-243-full-arm64-signed.apk"
)
YCORE = ROOT / "composeApp/libs/ycore-native.aar"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def command(arguments: list[Path | str], output: Path) -> str:
    result = subprocess.run([str(argument) for argument in arguments], capture_output=True)
    text = result.stdout.decode("utf-8", errors="replace")
    text += result.stderr.decode("utf-8", errors="replace")
    text = text.replace("\r\n", "\n")
    output.write_text(text, encoding="utf-8")
    require(result.returncode == 0, f"Command failed; inspect {output}")
    return text


def badging(apk: Path, output: Path) -> tuple[dict[str, str], str]:
    text = command([TOOLS / "aapt.exe", "dump", "badging", apk], output)
    package_line = re.search(r"^package: (.+)$", text, re.M)
    require(package_line is not None, f"No package metadata in {apk.name}")
    return dict(re.findall(r"(\w+)='([^']*)'", package_line.group(1))), text


def signature(apk: Path, output: Path) -> str:
    text = command(
        [JAVA, "-jar", TOOLS / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", apk],
        output,
    )
    require("Verified using v2 scheme (APK Signature Scheme v2): true" in text, "APK v2 signature invalid")
    require(re.search(r"^Number of signers: 1\s*$", text, re.M), "Unexpected signer count")
    match = re.search(r"^Signer #1 certificate SHA-256 digest: ([0-9a-f]+)$", text, re.M)
    require(match is not None, "Missing signing certificate")
    return match.group(1)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    props = dict(re.findall(r"^(VERSION_\w+)=(.+)$", (ROOT / "version.properties").read_text(), re.M))
    version_name = props["VERSION_NAME"]
    version_code = int(props["VERSION_CODE"])
    require(version_name == "1.0.82" and version_code == 244, "Unexpected project version")
    notes = (ROOT / "release-notes.txt").read_text(encoding="utf-8-sig")
    require(notes.splitlines()[0].strip() == version_name, "Release notes heading differs from version")
    require((OUT / "version.properties").read_bytes() == (ROOT / "version.properties").read_bytes(),
            "Archived version metadata differs")
    require((OUT / "release-notes.txt").read_bytes() == (ROOT / "release-notes.txt").read_bytes(),
            "Archived release notes differ")
    for log in ("full-build.log", "compact-build-fixed.log"):
        require("BUILD SUCCESSFUL" in (AUDIT / log).read_text(encoding="utf-8", errors="replace"),
                f"Build did not succeed: {log}")

    old_metadata, _ = badging(PREVIOUS, AUDIT / "previous-badging.txt")
    old_certificate = signature(PREVIOUS, AUDIT / "previous-signature.txt")
    require(version_code > int(old_metadata["versionCode"]), "Version code was not incremented")
    require(tuple(map(int, version_name.split("."))) >
            tuple(map(int, old_metadata["versionName"].split("."))), "Version name was not incremented")

    profiles = {}
    checksums = []
    with zipfile.ZipFile(YCORE) as ycore:
        ycore_libraries = {
            "lib/" + name.removeprefix("jni/"): ycore.read(name)
            for name in ycore.namelist()
            if name.startswith("jni/arm64-v8a/") and name.endswith(".so")
        }
        require(ycore_libraries, "No YCore native libraries found")
        for profile in ("full", "compact"):
            apk = OUT / f"Yfuse-{version_name}-{version_code}-{profile}-arm64-signed.apk"
            require(apk.is_file(), f"Missing {profile} APK")
            metadata, badge_text = badging(apk, AUDIT / f"badging-{profile}.txt")
            require(metadata["name"] == "com.yfuse", "Wrong package name")
            require(metadata["versionName"] == version_name and
                    int(metadata["versionCode"]) == version_code, "APK version differs from project")
            require("application-debuggable" not in badge_text, "Debuggable release APK")
            certificate = signature(apk, AUDIT / f"signature-{profile}.txt")
            require(certificate == old_certificate, "Signing certificate differs from previous APK")
            alignment = command(
                [TOOLS / "zipalign.exe", "-c", "-P", "16", "-v", "4", apk],
                AUDIT / f"alignment-{profile}.txt",
            )
            require("Verification successful" in alignment, "ZIP alignment failed")
            with zipfile.ZipFile(apk) as package:
                require(package.testzip() is None, "APK ZIP CRC failed")
                libraries = {name for name in package.namelist() if name.startswith("lib/") and name.endswith(".so")}
                require(libraries and all(name.startswith("lib/arm64-v8a/") for name in libraries),
                        "Unexpected native ABI")
                require(all(name in libraries and package.read(name) == content
                            for name, content in ycore_libraries.items()), "YCore libraries differ from source AAR")
                mdk_names = {"lib/arm64-v8a/libmdk.so", "lib/arm64-v8a/libyfuse-mdk-jni.so"}
                require((mdk_names <= libraries) if profile == "full" else not (mdk_names & libraries),
                        f"Wrong MDK runtime for {profile}")
                require("lib/arm64-v8a/libmpv.so" in libraries, "MPV runtime missing")
                dex = b"".join(
                    package.read(name)
                    for name in package.namelist()
                    if re.fullmatch(r"classes\d*\.dex", name)
                )
                markers = ("engine_slot_requested", "engine_constructed", "first_video_output", "api_request_timing")
                require(all(marker.encode("ascii") in dex for marker in markers),
                        "New startup timing code is missing from the packaged DEX")
            digest = sha256(apk)
            checksums.append(f"{digest}  {apk.name}")
            profiles[profile] = {
                "apk": apk.name,
                "bytes": apk.stat().st_size,
                "sha256": digest,
                "applicationId": metadata["name"],
                "versionName": metadata["versionName"],
                "versionCode": int(metadata["versionCode"]),
                "certificateSha256": certificate,
                "signatureV2Verified": True,
                "zipalign16KiBVerified": True,
                "zipCrcVerified": True,
                "arm64Only": True,
                "ycoreBytesMatch": True,
                "mdkIncluded": profile == "full",
                "startupTimingMarkersPresent": True,
                "debuggable": False,
            }

    report = {
        "verifiedAtUtc": datetime.now(timezone.utc).isoformat(),
        "previousDeliveredApk": PREVIOUS.relative_to(ROOT).as_posix(),
        "previousVersionName": old_metadata["versionName"],
        "previousVersionCode": int(old_metadata["versionCode"]),
        "previousCertificateSha256": old_certificate,
        "projectMetadataMatches": True,
        "releaseNotesMatch": True,
        "profiles": profiles,
        "installed": False,
        "published": False,
    }
    (OUT / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUT / "SHA256.txt").write_text("\n".join(checksums) + "\n", encoding="utf-8")
    (AUDIT / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
