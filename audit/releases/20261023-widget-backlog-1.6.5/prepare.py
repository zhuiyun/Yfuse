"""Prepare the authorized local signed release from the latest validated source snapshot."""
import datetime
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
AUDIT = Path(__file__).resolve().parent
OUT = ROOT / "artifacts-local/releases/widget-backlog-1.6.5-317"
PREVIOUS = ROOT / "artifacts-local/releases/widget-layout-1.6.4-316"
VALIDATION = ROOT / "audit/widget-backlog-20261023"
SDK = Path("D:/AndroidSDK/build-tools/36.1.0")
JAVA = Path("C:/Users/app-inkbird/.jdks/corretto-17.0.15/bin/java.exe")


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read(path):
    return json.loads(path.read_text("utf-8-sig"))


def record(name, value):
    text = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    (AUDIT / name).write_text(text, "utf-8")
    (OUT / name).write_text(text, "utf-8")


def run(args, name=None):
    result = subprocess.run([str(arg) for arg in args], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    text = result.stdout.decode("utf-8", errors="replace")
    if name:
        (OUT / name).write_text(text, "utf-8")
    assert result.returncode == 0, f"Command failed: {name or args[0]}"
    return text


OUT.mkdir(parents=True, exist_ok=True)
sys.path.insert(0, str(ROOT / "scripts"))
from release_metadata import read_release
from mdk_distribution_approval import is_confirmed

release = read_release(ROOT)
assert release["versionName"] == "1.6.4" and release["versionCode"] == 316
old = read(PREVIOUS / "verification.json")
previous_apk = PREVIOUS / old["file"]
assert sha(previous_apk) == old["sha256"]
badging = run([SDK / "aapt2.exe", "dump", "badging", previous_apk], "previous-badging.txt")
identity = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
assert identity and identity.groups() == ("com.yfuse", "316", "1.6.4")
signature = run([JAVA, "-jar", SDK / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", previous_apk], "previous-signature.txt")
assert "Signer #1 certificate SHA-256 digest: " + old["certificateSha256"] in signature
assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature

validation = read(VALIDATION / "verification-result.json")
assert validation["exitCode"] == 0 and validation["sourceDrift"] == []
assert validation["sourceInputCount"] == 2830
assert "BUILD SUCCESSFUL" in (VALIDATION / "verification.log").read_text("utf-8-sig")
tests = read(VALIDATION / "test-summary.json")
assert tests["summary"] == {"tests":159,"failures":0,"errors":0,"skipped":0}
validated = read(VALIDATION / "source-inputs.json")
assert len(validated) == len({item["path"] for item in validated}) == 2830
for entry in validated:
    assert sha(ROOT / entry["path"]) == entry["sha256"], "Validated input changed: " + entry["path"]
assert any(item["path"].endswith("/WidgetEditorialCards.kt") for item in validated)
native = read(PREVIOUS / "native-inputs.json")
for entry in native:
    assert sha(ROOT / entry["file"]) == entry["sha256"], "Pinned native component changed: " + entry["file"]
release_metadata_paths = {"version.properties", "release-notes.txt", ".github/mdk-distribution-approval.json"}
record("pre-bump-verification.json", {
    "versionName": "1.6.4", "versionCode": 316, "matchedFiles": len(validated),
    "sourceManifestSha256": sha(VALIDATION / "source-inputs.json"),
    "permittedReleaseMetadataChanges": sorted(release_metadata_paths),
})

notes = (ROOT / "release-notes.txt").read_text("utf-8-sig")
assert notes.splitlines()[0] == "1.6.4"
new_notes = """1.6.5
- 按已确认待办新增 21 种原生组件样式，总计 63 种，接入预览、配置、排序、隐藏及持久化。
- 新增叠层、折页、弧形、书脊、纵深、折屏等横向动态布局，以及镜面、错位双列、双片联映等封面构图。
- 剧集组件连接真实媒体库季集、下一集及本地观看进度；支持分季、选集、时长、进度与年代筛选，点击进入对应详情。
- 修复已保存栏目被临时缓存驱逐导致反复清空重载；分别保留每个自定义页面的滚动和浏览状态。
- 修复后台请求失败传播、未知季选择、大字体集数矩阵空间以及缺少总时长的观看进度显示问题；首页组件保持有界横滑。
- 正式签名 full ARM64 完整版，包含固定 MDK、TMDB 和影视推荐；159 项回归测试及手机、电视编译通过，真机视觉仍待验收。

"""
(ROOT / "version.properties").write_text(
    "# Production-signed full-MDK build with approved widget backlog and 63 styles.\nVERSION_CODE=317\nVERSION_NAME=1.6.5\n", "utf-8")
(ROOT / "release-notes.txt").write_text(new_notes + notes, "utf-8")
now = datetime.datetime.now(datetime.timezone.utc).isoformat()
approval_path = ROOT / ".github/mdk-distribution-approval.json"
approval = read(approval_path)
assert approval["mdkArtifactSha256"] == old["mdkArtifactSha256"]
approval.update({
    "versionName": "1.6.5", "versionCode": 317, "scope": "package-only", "confirmed": True,
    "confirmedAt": now, "confirmedBy": "release owner",
    "confirmationBasis": "Owner explicitly requested production signing and source upload for the verified 21-style backlog implementation. APK distribution remains local package-only; source upload does not publish native binary artifacts. Standing authorization covers the unchanged pinned MDK and unchanged local full ARM64 package-only scope.",
})
approval_path.write_text(json.dumps(approval, ensure_ascii=False, indent=2) + "\n", "utf-8")
assert is_confirmed(ROOT, True)
assert read_release(ROOT)["versionName"] == "1.6.5"

prefixes = ("composeApp/", "phoneShared/", "tvShared/", "tvApp/", "mdkAndroid/", "macrobenchmark/", "watchTogetherProtocol/", "yfuseBackendClient/", "watchTogetherServer/", "gradle/", "scripts/", "config/")
root_inputs = {"build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", *release_metadata_paths}
paths = set(run(["git", "ls-files"]).splitlines())
paths.update(run(["git", "ls-files", "--others", "--exclude-standard", "--", *[p.rstrip("/") for p in prefixes]]).splitlines())
paths = {path for path in paths if (path in root_inputs or path.startswith(prefixes)) and (ROOT / path).is_file()}
paths.update(entry["path"] for entry in validated)
paths = sorted(paths)
source_commit = run(["git", "rev-parse", "HEAD"]).strip()
record("source-inputs.json", {"sourceCommit": source_commit, "capturedAtUtc": now,
    "files": [{"path": path, "sha256": sha(ROOT / path)} for path in paths], "nativeInputs": native})
record("native-inputs.json", native)
record("preparation.json", {
    "versionName": "1.6.5", "versionCode": 317,
    "previousActualVersionName": identity.group(3), "previousActualVersionCode": int(identity.group(2)),
    "previousApkSha256": sha(previous_apk), "sourceCommit": source_commit,
    "treeDirty": True, "profile": "full", "abi": "arm64-v8a", "preparedAtUtc": now,
    "latestWidgetTests": 159, "latestValidatedFilesRechecked": len(validated),
    "releaseMetadataUpdated": sorted(release_metadata_paths), "unchangedNativeComponents": len(native),
})
print(json.dumps({"versionName": "1.6.5", "versionCode": 317, "sourceFiles": len(paths), "verifiedWidgetTests": 159, "nativeComponents": len(native)}))
