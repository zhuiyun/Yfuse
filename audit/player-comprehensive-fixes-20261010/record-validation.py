import difflib
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
AUDIT = Path(__file__).resolve().parent
sources = re.findall(r'"(composeApp/[^"\n]+\.kt)"', (AUDIT / "ktlint-changed.gradle").read_text(encoding="utf-8"))


def read_log(name):
    data = (AUDIT / name).read_bytes()
    encoding = "utf-16" if data.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig"
    return data.decode(encoding)


assert "BUILD SUCCESSFUL" in read_log("validation.log")
assert "BUILD SUCCESSFUL" in read_log("ktlint.log")
subprocess.run(["git", "diff", "--check"], cwd=ROOT, check=True, capture_output=True)
head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
release = ROOT / "artifacts-local/releases/player-1.1.9-271"
previous_head = json.loads((ROOT / "audit/releases/20261010-player-1.1.9/verification.json").read_text(encoding="utf-8-sig"))["sourceCommit"]
assert head == previous_head, "Review the comparison base when HEAD has changed"
diffs = []
changed = []
with zipfile.ZipFile(release / "changed-source.zip") as archive:
    for name in sources:
        current = (ROOT / name).read_bytes()
        if name in archive.namelist():
            baseline = archive.read(name)
            origin = "delivered 1.1.9 source archive"
        else:
            show = subprocess.run(["git", "show", f"{head}:{name}"], cwd=ROOT, capture_output=True)
            baseline = show.stdout if show.returncode == 0 else b""
            origin = "release source HEAD" if show.returncode == 0 else "new file"
        original = baseline.decode("utf-8-sig").splitlines(keepends=True)
        final = current.decode("utf-8-sig").splitlines(keepends=True)
        original = [line.rstrip("\r\n") + "\n" for line in original]
        final = [line.rstrip("\r\n") + "\n" for line in final]
        diffs.extend(difflib.unified_diff(original, final, fromfile=f"1.1.9/{name}", tofile=f"working/{name}"))
        changed.append({"path": name, "sha256": hashlib.sha256(current).hexdigest(), "comparisonBase": origin})
(AUDIT / "changes.patch").write_text("".join(diffs), encoding="utf-8")

junit = AUDIT / "junit"
junit.mkdir(exist_ok=True)
counts = dict(suites=0, tests=0, failures=0, errors=0, skipped=0)
for result in (ROOT / "phoneShared/build/test-results/testAndroidHostTest").glob("TEST-com.yfuse.feature.player.*.xml"):
    suite = ET.parse(result).getroot()
    counts["suites"] += 1
    for field in ("tests", "failures", "errors", "skipped"):
        counts[field] += int(suite.get(field, "0"))
    if suite.get("name", "").rsplit(".", 1)[-1] in {
        "PlayerGestureCommandsTest", "PlayerGestureStateTest", "PlayerPlaybackSpeedPolicyTest", "PreparingPlaybackGateTest"
    }:
        shutil.copy2(result, junit / result.name)
assert counts["tests"] >= 1017 and counts["failures"] == counts["errors"] == counts["skipped"] == 0, counts
report = {
    "verifiedAt": datetime.now(timezone(timedelta(hours=8))).isoformat(),
    "sourceHead": head,
    "comparisonRelease": "1.1.9 (271)",
    "apkGenerated": False,
    "deviceUiVerified": False,
    "phoneCompile": "passed",
    "tvCompile": "passed",
    "kotlinStyle": f"passed ({len(sources)} files)",
    "gitDiffCheck": "passed",
    "regression": counts,
    "validationCommand": "gradlew :phoneShared:testAndroidHostTest --tests com.yfuse.feature.player.* :tvShared:compileAndroidMain -PyfuseIncludeMdk=true -PyfuseNativeOnlyRuntime=false --no-parallel --max-workers=1 --console=plain",
    "changedSources": changed,
}
(AUDIT / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"sources": len(sources), "regression": counts}, ensure_ascii=False))
