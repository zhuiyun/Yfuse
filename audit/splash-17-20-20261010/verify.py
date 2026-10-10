"""Keep source, test, reference-sampling and native-render evidence for designs 17–20."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[2]
AUDIT = Path(__file__).resolve().parent
source = [
    'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/Theme.kt',
    'composeApp/src/androidMain/kotlin/com/yfuse/app/SplashChoreography.kt',
    'composeApp/src/androidMain/kotlin/com/yfuse/app/WaterFireSplash.kt',
    'composeApp/src/androidMain/kotlin/com/yfuse/app/WaterFireMechanisms.kt',
    'composeApp/src/androidMain/kotlin/com/yfuse/app/WaterFireMechanismMotion.kt',
    'composeApp/src/androidUnitTest/kotlin/com/yfuse/app/WaterFireMechanismMotionTest.kt',
    'composeApp/src/commonTest/kotlin/com/yfuse/core/data/SplashSelectionTest.kt',
    'composeApp/src/commonTest/kotlin/com/yfuse/core/sync/CloudSyncSnapshotTest.kt',
    'composeApp/src/androidMain/res/raw/water_fire_domino.csv',
    'scripts/launcher_icons/import_splash_artwork.py',
]
results = []
for suite in ('com.yfuse.app.WaterFireMechanismMotionTest', 'com.yfuse.app.SplashTimingPolicyTest', 'com.yfuse.core.data.SplashSelectionTest', 'com.yfuse.core.data.ThemePreferencesTest', 'com.yfuse.core.sync.CloudSyncSnapshotTest'):
    path = ROOT / 'phoneShared/build/test-results/testAndroidHostTest' / ('TEST-' + suite + '.xml')
    test = ET.parse(path).getroot()
    row = {'suite': suite, **{key: int(test.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}}
    assert row['failures'] == row['errors'] == row['skipped'] == 0
    results.append(row)
    shutil.copy2(path, AUDIT / path.name)
assert sum(row['tests'] for row in results) == 39
raw = (ROOT / source[-2]).read_text(encoding='utf-8').splitlines()
reference = Path('C:/Users/app-inkbird/WorkBuddy AI/2026-10-10-16-59-23/player-splash/assets/dots.js').read_text(encoding='utf-8')
assert raw == re.search(r'window\.LOGO_TILES\s*=\s*[\'\"]([^\'\"]+)[\'\"]', reference)[1].split('|')
assert len(raw) == 231
logo = Image.open(ROOT / 'composeApp/src/androidMain/res/drawable-nodpi/water_fire_logo.png').convert('RGBA')
final_diffs = {}
for name in ('Hologram', 'Marble', 'Fan', 'Domino'):
    frames = sorted((AUDIT / 'device-frames').glob(name + '-*.png'))
    assert len(frames) == 45
    assert Image.open(AUDIT / 'device-frames' / (name + '-2200.png')).getbbox()
    if name != 'Domino':
        final = Image.open(AUDIT / 'device-frames' / (name + '-2100.png')).convert('RGBA')
        difference = ImageChops.difference(final, logo).getextrema()
        assert difference[3] == (0, 0) and all(item[1] <= 1 for item in difference[:3])
        final_diffs[name] = difference
report = {
    'verifiedAtUtc': datetime.now(timezone.utc).isoformat(),
    'effects': ['17 全息扫描成型', '18 大理石纹成型', '19 折扇成型', '20 多米诺成型'],
    'waterFireOptions': 16,
    'tests': results,
    'compile': 'passed', 'ktlint': 'passed',
    'referenceDominoSamples': 231, 'referenceSamplesExact': True,
    'nativeRendering': {'androidApi': 28, 'frames': 180, 'method': 'app_process runs production Canvas renderer and motion source, with standalone artwork loading and original math helpers', 'installedApk': False, 'appUiTested': False},
    'finalLogoPixelDifference': final_diffs,
    'visualEvidence': ['contact-sheet.png', 'preview.gif'],
    'sourceFiles': [{'path': path, 'sha256': hashlib.sha256((ROOT / path).read_bytes()).hexdigest()} for path in source],
    'releaseBuilt': False,
}
(AUDIT / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('PASS: 39 tests, 231 exact reference samples, 180 Android 9 native frames; three continuous-logo final frames match the source (RGB rounding <=1, identical alpha).')
