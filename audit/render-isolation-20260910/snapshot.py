from pathlib import Path
import difflib, hashlib, json

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
new = {
    'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/ThemeColorConsumer.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/ThemeColorContent.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlaybackRuntimeProjection.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerBatteryStatus.kt',
    'composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerBatteryStatus.android.kt',
    'composeApp/src/androidMain/kotlin/com/yfuse/feature/player/SubtitleBitmapCanvas.android.kt',
    'composeApp/src/commonTest/kotlin/com/yfuse/feature/player/PlaybackRuntimeContentTest.kt',
    'composeApp/src/commonTest/kotlin/com/yfuse/feature/player/PlayerBatteryStatusTest.kt',
    'composeApp/src/androidUnitTest/kotlin/com/yfuse/feature/player/SubtitleBitmapCanvasTest.kt',
}
records = []
for path in (OUT / 'changed-files.txt').read_text(encoding='utf-8').splitlines():
    target = ROOT / path
    before = '' if path in new else (OUT / 'before' / path).read_text(encoding='utf-8')
    after = target.read_text(encoding='utf-8')
    if before == after:
        continue
    diff = list(difflib.unified_diff(before.splitlines(True), after.splitlines(True), fromfile=path+' (before turn)', tofile=path))
    output = OUT / 'diff' / (path + '.patch')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(''.join(diff), encoding='utf-8')
    records.append(dict(path=path, created=path in new, sha256=hashlib.sha256(target.read_bytes()).hexdigest(),
                        added=sum(line.startswith('+') and not line.startswith('+++') for line in diff),
                        removed=sum(line.startswith('-') and not line.startswith('---') for line in diff)))
(OUT / 'snapshot.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
(OUT / 'changed-files.txt').write_text(''.join(row['path']+'\n' for row in records), encoding='utf-8')
print(json.dumps(dict(files=len(records), new=sum(row['created'] for row in records),
                     added=sum(row['added'] for row in records), removed=sum(row['removed'] for row in records))))
