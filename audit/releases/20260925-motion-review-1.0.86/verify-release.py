"""Verify the locally built, production-signed 1.0.86 (248) APKs without signing secrets.

Run after `scripts/build-release-packages.ps1 -ConfirmMdkDistributionRights`, from a clean checkout:

    python audit/releases/20260925-motion-review-1.0.86/verify-release.py --previous <last delivered APK>

`--previous` is the signed APK actually delivered last (1.0.85 / 247 if it went out, otherwise
1.0.84 / 246). The build tools and Java default to the paths the 1.0.84 verification used; pass
`--build-tools` / `--java` to point elsewhere. Run it before committing its results: the packaging
record must name the checked-out commit.
"""

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path


AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parents[2]
EXPECTED_NAME = "1.0.86"
EXPECTED_CODE = 248
# Yfuse's production certificate (CN=Yfuse, OU=Mobile), as recorded for every delivery up to 1.0.84.
PRODUCTION_CERTIFICATE = "373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84"
# String constants that exist only in this release's code: the silent-audio fix, the offline
# download redirects and the motion theme. Their absence means the APK was built from older source.
RELEASE_MARKERS = ("platform_aac_label_rejected", "下载地址重定向次数过多", "静息")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def tool(directory: Path, name: str) -> Path:
    windows = directory / f"{name}.exe"
    return windows if windows.exists() else directory / name


def command(arguments: list, output: Path) -> str:
    result = subprocess.run([str(argument) for argument in arguments], capture_output=True)
    text = result.stdout.decode("utf-8", errors="replace")
    text += result.stderr.decode("utf-8", errors="replace")
    text = text.replace("\r\n", "\n")
    output.write_text(text, encoding="utf-8")
    require(result.returncode == 0, f"Command failed; inspect {output}")
    return text


def badging(tools: Path, apk: Path, output: Path) -> tuple[dict[str, str], str]:
    text = command([tool(tools, "aapt"), "dump", "badging", apk], output)
    package_line = re.search(r"^package: (.+)$", text, re.M)
    require(package_line is not None, f"No package metadata in {apk.name}")
    return dict(re.findall(r"(\w+)='([^']*)'", package_line.group(1))), text


def signature(java: Path, tools: Path, apk: Path, output: Path) -> str:
    text = command(
        [java, "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", apk],
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


def version_tuple(name: str) -> tuple[int, ...]:
    return tuple(int(part) for part in name.split("."))


def find_apk(directory: Path, profile: str) -> Path:
    candidates = [
        directory / f"Yfuse-{EXPECTED_NAME}-{profile}-arm64.apk",
        directory / f"Yfuse-{EXPECTED_NAME}-{EXPECTED_CODE}-{profile}-arm64-signed.apk",
    ]
    found = [candidate for candidate in candidates if candidate.is_file()]
    require(len(found) == 1, f"Expected exactly one {profile} APK in {directory}, found {len(found)}")
    return found[0]


def git_head() -> str:
    result = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "HEAD"], capture_output=True, text=True)
    require(result.returncode == 0, "git rev-parse HEAD failed")
    return result.stdout.strip()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--previous", type=Path, required=True, help="last delivered signed APK")
    parser.add_argument("--apk-dir", type=Path, default=ROOT / "composeApp/build/outputs/distribution")
    parser.add_argument("--build-tools", type=Path, default=Path("D:/AndroidSDK/build-tools/36.1.0"))
    parser.add_argument(
        "--java",
        type=Path,
        default=Path("D:/software/android-studio-2024.1.1.11-windows/android-studio/jbr/bin/java.exe"),
    )
    parser.add_argument("--ycore-aar", type=Path, default=ROOT / "composeApp/libs/ycore-native.aar")
    args = parser.parse_args()
    tools, java, out = args.build_tools, args.java, args.apk_dir

    props = dict(re.findall(r"^(VERSION_\w+)=(.+)$", (ROOT / "version.properties").read_text(), re.M))
    version_name = props["VERSION_NAME"].strip()
    version_code = int(props["VERSION_CODE"])
    require(version_name == EXPECTED_NAME and version_code == EXPECTED_CODE, "Unexpected project version")
    notes = (ROOT / "release-notes.txt").read_text(encoding="utf-8-sig")
    require(notes.splitlines()[0].strip() == version_name, "Release notes heading differs from version")

    # build-release-packages.ps1 records the commit and tree state it built from.
    build_record = json.loads((out / "verification.json").read_text(encoding="utf-8-sig"))
    require(str(build_record.get("versionName")) == version_name and
            int(build_record.get("versionCode")) == version_code, "Packaging record has another version")
    require(build_record.get("treeDirty") is False, "APKs were packaged from an uncommitted tree")
    require(build_record.get("sourceCommit") == git_head(), "APKs were packaged from another commit")

    old_metadata, _ = badging(tools, args.previous, AUDIT / "previous-badging.txt")
    old_certificate = signature(java, tools, args.previous, AUDIT / "previous-signature.txt")
    require(old_certificate == PRODUCTION_CERTIFICATE, "Previous APK is not signed with the production key")
    require(version_code > int(old_metadata["versionCode"]), "Version code was not incremented")
    require(version_tuple(version_name) > version_tuple(old_metadata["versionName"]),
            "Version name was not incremented")

    profiles = {}
    checksums = []
    with zipfile.ZipFile(args.ycore_aar) as ycore:
        ycore_libraries = {
            "lib/" + name.removeprefix("jni/"): ycore.read(name)
            for name in ycore.namelist()
            if name.startswith("jni/arm64-v8a/") and name.endswith(".so")
        }
    require(ycore_libraries, "No YCore native libraries found")
    for profile in ("full", "compact"):
        apk = find_apk(out, profile)
        metadata, badge_text = badging(tools, apk, AUDIT / f"badging-{profile}.txt")
        require(metadata["name"] == "com.yfuse", "Wrong package name")
        require(metadata["versionName"] == version_name and
                int(metadata["versionCode"]) == version_code, "APK version differs from project")
        require("application-debuggable" not in badge_text, "Debuggable release APK")
        certificate = signature(java, tools, apk, AUDIT / f"signature-{profile}.txt")
        require(certificate == PRODUCTION_CERTIFICATE, "APK is not signed with the production key")
        alignment = command(
            [tool(tools, "zipalign"), "-c", "-P", "16", "-v", "4", apk],
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
            missing = [marker for marker in RELEASE_MARKERS if marker.encode("utf-8") not in dex]
            require(not missing, f"This release's code is missing from the packaged DEX: {missing}")
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
            "releaseMarkersPresent": True,
            "debuggable": False,
        }

    report = {
        "verifiedAtUtc": datetime.now(timezone.utc).isoformat(),
        "sourceCommit": build_record["sourceCommit"],
        "previousDeliveredApk": args.previous.name,
        "previousVersionName": old_metadata["versionName"],
        "previousVersionCode": int(old_metadata["versionCode"]),
        "previousCertificateSha256": old_certificate,
        "projectMetadataMatches": True,
        "releaseNotesMatch": True,
        "profiles": profiles,
        "installed": False,
        "published": False,
    }
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    (out / "release-verification.json").write_text(text, encoding="utf-8")
    (out / "SHA256.txt").write_text("\n".join(checksums) + "\n", encoding="utf-8")
    (AUDIT / "verification.json").write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
