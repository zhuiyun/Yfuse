"""Verify and archive the authorized local full ARM64 delivery without publishing it."""
import datetime
import hashlib
import json
import re
import shutil
import struct
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
AUDIT = Path(__file__).resolve().parent
OUT = ROOT / "artifacts-local/releases/widget-backlog-1.6.5-317"
PREVIOUS = ROOT / "artifacts-local/releases/widget-layout-1.6.4-316"
VALIDATION = ROOT / "audit/widget-backlog-20261023"
SDK = Path("D:/AndroidSDK/build-tools/36.1.0")
JAVA = Path("C:/Users/app-inkbird/.jdks/corretto-17.0.15/bin/java.exe")
APK = ROOT / "composeApp/build/outputs/apk/release/composeApp-release.apk"
MAPPING = ROOT / "composeApp/build/outputs/mapping/release/mapping.txt"
CERTIFICATE = "373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84"


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def record(name, value):
    text = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    (AUDIT / name).write_text(text, encoding="utf-8")
    (OUT / name).write_text(text, encoding="utf-8")


def run(log, args):
    result = subprocess.run([str(arg) for arg in args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = result.stdout.decode("utf-8", errors="replace")
    (OUT / log).write_text(output, encoding="utf-8")
    assert result.returncode == 0, f"Verification failed: {log} (exit {result.returncode})"
    return output


sys.path.insert(0, str(ROOT / "scripts"))
from release_metadata import read_release
from mdk_distribution_approval import is_confirmed

release = read_release(ROOT)
assert release["versionName"] == "1.6.5" and release["versionCode"] == 317
assert re.fullmatch(r"[0-9]\.[0-9]\.[0-9]", release["versionName"])
assert is_confirmed(ROOT, True)
assert read_json(OUT / "build-result.json")["exitCode"] == 0
start = read_json(OUT / "build-start.json")
assert start["versionName"] == "1.6.5" and start["versionCode"] == 317
assert start["profile"] == "full" and start["yfuseIncludeMdk"] is True
assert start["yfuseNativeOnlyRuntime"] is False
assert "-PconfirmMdkDistributionRights=true" in start["command"]
assert "-PyfusePackageOnly=true" in start["command"]
started_at = re.sub(r"(\.\d{6})\d+", r"\1", start["startedAtUtc"].replace("Z", "+00:00"))
assert APK.stat().st_mtime >= datetime.datetime.fromisoformat(started_at).timestamp()
assert MAPPING.is_file() and MAPPING.stat().st_size > 0
inputs = read_json(OUT / "source-inputs.json")
assert sha(OUT / "source-inputs.json") == start["sourceInputsSha256"]
for item in inputs["files"]:
    assert sha(ROOT / item["path"]) == item["sha256"], "Source changed during build: " + item["path"]
for item in inputs["nativeInputs"]:
    assert sha(ROOT / item["file"]) == item["sha256"], "Native input changed: " + item["file"]
print(f"Source snapshot verified: {len(inputs['files'])} files, {len(inputs['nativeInputs'])} native inputs", flush=True)

# Match the source snapshot validated by the 159 passing regressions.
validation_record = read_json(VALIDATION / "verification-result.json")
assert validation_record["exitCode"] == 0 and not validation_record["sourceDrift"]
validated = read_json(VALIDATION / "source-inputs.json")
validated_sources = {item["path"]:item["sha256"] for item in validated}
snapshot_sources = {item["path"]:item["sha256"] for item in inputs["files"]}
assert len(validated_sources) == len(validated) == validation_record["sourceInputCount"] == 2830
release_metadata_paths = {"version.properties", "release-notes.txt", ".github/mdk-distribution-approval.json"}
for path,digest in validated_sources.items():
    assert snapshot_sources[path] == sha(ROOT/path), path
    if path not in release_metadata_paths: assert sha(ROOT/path) == digest, path
validated_unchanged_count = len(validated)-3
pre_bump = read_json(AUDIT / "pre-bump-verification.json")
assert pre_bump["versionName"] == "1.6.4" and pre_bump["versionCode"] == 316
assert pre_bump["sourceManifestSha256"] == sha(VALIDATION / "source-inputs.json")
release_metadata_changes = [{"path":p,"validatedSha256":validated_sources[p],"packagedSha256":snapshot_sources[p]} for p in sorted(release_metadata_paths)]
validation = read_json(VALIDATION / "test-summary.json")
assert validation["summary"] == {"tests":159,"failures":0,"errors":0,"skipped":0}
reported = {x["name"]:x for x in validation["suites"]}
xml_evidence = []
for path in sorted((VALIDATION/"junit").glob("TEST-*.xml")):
    x=ET.parse(path).getroot(); name=x.attrib["name"]
    counters={k:int(x.attrib[k]) for k in ("tests","failures","errors","skipped")}
    assert counters == {k:reported[name][k] for k in counters}
    assert len(x.findall("testcase")) == counters["tests"]
    assert not x.findall(".//failure") and not x.findall(".//error")
    xml_evidence.append({"path":path.relative_to(ROOT).as_posix(),"sha256":sha(path)})
assert len(xml_evidence)==len(reported)
expected_totals={"suites":len(reported),**validation["summary"]}
assert "BUILD SUCCESSFUL" in (VALIDATION/"verification.log").read_text("utf-8-sig")
print("159 passing regressions and validated source snapshot verified",flush=True)

mapping_text = MAPPING.read_text("utf-8")
mapping_markers = (
    "com.yfuse.core.widget.WidgetFeed",
    "com.yfuse.core.widget.WidgetSourceContext",
    "com.yfuse.feature.home.TmdbInfoComponent",
    "com.yfuse.feature.home.HomeTabComponent$Config$WidgetHome",
    "com.yfuse.feature.widgets.WidgetBrowsePageKt",
    "com.yfuse.feature.widgets.WidgetSourceSectionKt",
    "com.yfuse.feature.widgets.WidgetHomeControlsKt",
    "com.yfuse.feature.widgets.WidgetEditorialCardsKt",
    "com.yfuse.core.widget.WidgetStyle",
    "com.yfuse.feature.widgets.WidgetKineticLayoutsKt",
    "com.yfuse.feature.widgets.WidgetGalleryLayoutsKt",
    "com.yfuse.feature.widgets.WidgetEpisodeLayoutsKt",
)
for marker in mapping_markers:
    assert marker in mapping_text, "Latest widget/detail implementation missing from R8 mapping: " + marker


badging = run("full-badging.txt", [SDK / "aapt2.exe", "dump", "badging", APK])
identity = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
assert identity and identity.groups() == ("com.yfuse", "317", "1.6.5")
assert re.search(r"^native-code: (.+)$", badging, re.MULTILINE).group(1).strip() == "'arm64-v8a'"
previous = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", (OUT / "previous-badging.txt").read_text("utf-8"))
assert previous and previous.groups() == ("com.yfuse", "316", "1.6.4")
assert previous.group(1) == identity.group(1)
assert int(identity.group(2)) > int(previous.group(2))
assert tuple(map(int, identity.group(3).split("."))) > tuple(map(int, previous.group(3).split(".")))
signature = run("full-signature.txt", [JAVA, "-jar", SDK / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", APK])
assert "Number of signers: 1" in signature
assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
assert "Signer #1 certificate SHA-256 digest: " + CERTIFICATE in signature
assert CERTIFICATE in (OUT / "previous-signature.txt").read_text("utf-8")
run("full-alignment.txt", [SDK / "zipalign.exe", "-c", "-P", "16", "-v", "4", APK])
print("Actual package/version, production signing certificate and APK alignment verified", flush=True)

native = []
widgets = {}
dex_hashes = {}
dex_data = []
old_verification = read_json(PREVIOUS / "verification.json")
assert old_verification["versionName"] == "1.6.4" and old_verification["versionCode"] == 316
assert sha(PREVIOUS / old_verification["file"]) == old_verification["sha256"], "Previous APK changed"
assert read_json(OUT / "preparation.json")["previousApkSha256"] == old_verification["sha256"]
assert read_json(ROOT / ".github/mdk-distribution-approval.json")["mdkArtifactSha256"] == old_verification["mdkArtifactSha256"]
assert inputs["nativeInputs"] == read_json(PREVIOUS / "native-inputs.json"), "Pinned native inputs changed"
with zipfile.ZipFile(APK) as archive:
    names = archive.namelist()
    assert len(names) == len(set(names)), "Duplicate APK entry"
    assert archive.testzip() is None
    for name in names:
        if name.startswith("lib/") and name.endswith(".so"):
            assert name.startswith("lib/arm64-v8a/"), "Unexpected native ABI: " + name
            data = archive.read(name)
            assert data[:6] == b"\x7fELF\x02\x01", "Expected ELF64 little endian: " + name
            assert struct.unpack_from("<H", data, 18)[0] == 183, "Expected ARM64: " + name
            offset = struct.unpack_from("<Q", data, 32)[0]
            entry_size, count = struct.unpack_from("<HH", data, 54)
            alignments = []
            for index in range(count):
                base = offset + index * entry_size
                if struct.unpack_from("<I", data, base)[0] == 1:
                    file_offset, virtual_address = struct.unpack_from("<QQ", data, base + 8)
                    alignment = struct.unpack_from("<Q", data, base + 48)[0]
                    assert alignment >= 16384 and alignment & (alignment - 1) == 0, name
                    assert file_offset % 16384 == virtual_address % 16384, name
                    alignments.append(alignment)
            assert alignments, "No PT_LOAD segments: " + name
            native.append({"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "loadAlignment": min(alignments)})
        if re.fullmatch(r"classes\d*\.dex", name):
            data = archive.read(name)
            dex_data.append(data)
            dex_hashes[name] = hashlib.sha256(data).hexdigest()
    for filename in ("tmdb.js", "movie-recommendations.js"):
        entry = "assets/widgets/" + filename
        source = "composeApp/src/androidMain/" + entry
        data = archive.read(entry)
        digest = hashlib.sha256(data).hexdigest()
        assert digest == sha(ROOT / source) == validated_sources[source] == snapshot_sources[source], entry
        widgets[filename] = {"path": entry, "bytes": len(data), "sha256": digest, "matchesValidatedSource": True}
assert dex_hashes
build_config = (ROOT / "composeApp/build/generated/source/buildConfig/release/com/yfuse/BuildConfig.java").read_text("utf-8")
build_revision_match = re.search(r'BUILD_REVISION = "([a-f0-9]+-dirty-[a-f0-9]+)"', build_config)
assert build_revision_match, "Missing dirty-tree build identity"
build_revision = build_revision_match.group(1)
assert build_revision.startswith(inputs["sourceCommit"] + "-dirty-")
assert any(build_revision.encode("ascii") in data for data in dex_data), "Build identity absent from APK"
for marker in (b"plugin_builtin_tmdb", b"plugin_builtin_movie_recommend"):
    assert any(marker in data for data in dex_data), "Missing bundled widget bootstrap identifier"
new_style_ids = ('layered-poster', 'next-direct', 'big-rank', 'accordion', 'arc-poster', 'staggered-columns', 'borderless', 'season-showcase', 'episode-preview', 'episode-matrix', 'decade-shelf', 'poster-waterfall', 'book-spine', 'mirror-poster', 'overlap-info', 'double-feature', 'floating-poster', 'staggered-rail', 'banner-stack', 'depth-gallery', 'folding-gallery')
for style_id in new_style_ids:
    assert any(style_id.encode("ascii") in data for data in dex_data), "New style missing from APK: " + style_id
mdk = {}
for name in ("lib/arm64-v8a/libmdk.so", "lib/arm64-v8a/libyfuse-mdk-jni.so"):
    entry = next(item for item in native if item["path"] == name)
    assert entry["sha256"] == old_verification["mdkLibraries"][name]["sha256"], "Pinned MDK runtime changed"
    mdk[name] = {"bytes": entry["bytes"], "sha256": entry["sha256"]}
record("native-verification.json", native)
print(f"Bundled widget assets and bootstrap identifiers verified; {len(native)} native libraries aligned; MDK matches previous delivery", flush=True)

classpath = []
for line in (ROOT / "scripts/dex-verify/tools.sha256").read_text("utf-8").splitlines():
    if not line.strip() or line.startswith("#"):
        continue
    digest, path = line.split()
    jar = ROOT / "build/dex-verify-tools" / Path(path).name
    assert sha(jar) == digest, "DEX verification tool checksum mismatch"
    classpath.append(str(jar))
print("Running pinned DEX verifier against final release and R8 mapping", flush=True)
dex_log = run("dex-verification.txt", [JAVA, "-Xmx2g", "-cp", ";".join(classpath), ROOT / "scripts/dex-verify/DexRegisterTypeCheck.java", "--mapping", MAPPING, APK])
summary = re.search(r"(\d+) classes, (\d+) methods with code, (\d+) findings, (\d+) methods not analysable", dex_log)
assert summary and int(summary.group(3)) == 0 and int(summary.group(4)) == 0, "DEX verification incomplete"
print(summary.group(0), flush=True)

filename = "Yfuse-1.6.5-full-arm64.apk"
destination = OUT / filename
shutil.copy2(APK, destination)
shutil.copy2(MAPPING, OUT / "mapping.txt")
assert sha(destination) == sha(APK)
shutil.copy2(ROOT / "release-notes.txt", OUT / "release-notes.txt")
verification = {
    "versionName": identity.group(3), "versionCode": int(identity.group(2)), "packageName": identity.group(1),
    "profile": "full", "abi": "arm64-v8a", "file": filename, "bytes": destination.stat().st_size, "sha256": sha(destination),
    "certificateSha256": CERTIFICATE, "signatureV2Verified": True, "zipAlignment16K": True, "nativeLoadAlignment16K": True,
    "nativeLibrariesVerified": len(native), "dexVerified": True, "dexClasses": int(summary.group(1)), "dexMethodsWithCode": int(summary.group(2)),
    "dexFindings": 0, "dexMethodsNotAnalysable": 0, "dexFiles": dex_hashes, "mdkIncluded": True, "mdkLibraries": mdk,
    "mdkArtifactSha256": read_json(ROOT / ".github/mdk-distribution-approval.json")["mdkArtifactSha256"],
    "bundledWidgets": widgets, "bundledWidgetBootstrapIdentifiersInDex": True,
    "previousActualVersionName": previous.group(3), "previousActualVersionCode": int(previous.group(2)),
    "sourceCommit": inputs["sourceCommit"], "buildRevision": build_revision, "treeDirtyAtBuild": True, "buildInputsRecheckedAfterBuild": True,
    "sourceFilesChecked": len(inputs["files"]), "sourceInputsSha256": sha(OUT / "source-inputs.json"),
    "buildLogSha256": sha(OUT / "build-full.log"), "buildStartSha256": sha(OUT / "build-start.json"), "mappingSha256": sha(OUT / "mapping.txt"),
    "mappingMarkersVerified": list(mapping_markers),
    "newStyleIdsVerifiedInDex": list(new_style_ids),
    "priorRegressionEvidence": {
        "latestWidgetLayoutCapacity": {
            **expected_totals, "phoneSharedCompile": "passed", "tvSharedCompile": "passed",
            "record": "audit/widget-backlog-20261023/verification-result.json",
            "recordSha256": sha(VALIDATION / "verification-result.json"),
            "testsRecord": "audit/widget-backlog-20261023/test-summary.json",
            "testsRecordSha256": sha(VALIDATION / "test-summary.json"),
            "compileLog": "audit/widget-backlog-20261023/verification.log",
            "compileLogSha256": sha(VALIDATION / "verification.log"),
            "validatedFiles": len(validated_sources), "unchangedValidatedInputs": validated_unchanged_count,
            "sourceHashes": "audit/widget-backlog-20261023/source-inputs.json",
            "sourceHashesSha256": sha(VALIDATION / "source-inputs.json"),
            "preBumpVerification": (AUDIT / "pre-bump-verification.json").relative_to(ROOT).as_posix(),
            "preBumpVerificationSha256": sha(AUDIT / "pre-bump-verification.json"),
            "releaseMetadataChanges": release_metadata_changes, "testXml": xml_evidence,
        },
        "note": "All 2827 non-release-metadata inputs match the latest 2830-file validated snapshot. The only changes are the required version, release notes and pinned MDK confirmation update to 1.6.5/317. Original JUnit XML supports 159 passing tests. Existing tests were not rerun during packaging. Device/network/visual acceptance remains pending."
    },
    "nativeVisualQa": "pending; intended OPPO was unavailable during code validation; no device installation or visual acceptance performed during packaging",
    "exactDouyinPlaybackValidation": "unverified; installed local script and original device unavailable during code validation; packaging does not establish playback compatibility",
    "deviceTestsExecuted": False, "sourcePushed": False, "apkUploaded": False, "serverDeployed": False,
    "verifiedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat()
}
record("verification.json", verification)
(OUT / "SHA256SUMS.txt").write_text(f"{verification['sha256']}  {filename}\n", encoding="utf-8")
distribution = ROOT / "composeApp/build/outputs/distribution"
distribution.mkdir(parents=True, exist_ok=True)
shutil.copy2(destination, distribution / filename)
assert sha(distribution / filename) == verification["sha256"]
(distribution / "verification.json").write_text(json.dumps(verification, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({key: verification[key] for key in ("versionName", "versionCode", "packageName", "bytes", "sha256", "mdkIncluded", "dexVerified")}, ensure_ascii=False), flush=True)
print(str(destination), flush=True)
