import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
BUILD = ROOT / '.gradle-tmp/kernel-fixes-build'
log = (HERE / 'all.log').read_text(encoding='utf-8-sig')
assert 'BUILD SUCCESSFUL' in log and ' FAILED' not in log
assert json.loads((HERE / 'all-concurrent-changes.json').read_text(encoding='utf-8-sig')) == []
inputs = json.loads((HERE / 'all-source-before.json').read_text(encoding='utf-8-sig'))
for relative, expected in inputs.items():
    actual = hashlib.sha256((ROOT / relative).read_bytes()).hexdigest()
    assert actual.lower() == expected.lower(), relative

totals = {}
phone_suites = {}
for module in ('phoneShared', 'tvShared'):
    files = sorted((BUILD / module / 'build/test-results/testAndroidHostTest').glob('TEST-*.xml'))
    assert files, module
    summary = dict(tests=0, failures=0, errors=0, skipped=0)
    for file in files:
        suite = ET.parse(file).getroot()
        values = {key: int(suite.get(key, 0)) for key in summary}
        for key, value in values.items():
            summary[key] += value
        if module == 'phoneShared':
            phone_suites[suite.get('name')] = values
    assert summary['failures'] == summary['errors'] == 0, (module, summary)
    totals[module] = summary

required = (
    'com.yfuse.core2.render.YRenderedFrameRateSamplerTest',
    'com.yfuse.core2.android.AndroidVideoOutputEpochTest',
    'com.yfuse.feature.player.PlayerFrameRateReadoutTest',
    'com.yfuse.feature.player.PlaybackRuntimeContentTest',
    'com.yfuse.feature.player.RemoteCastPlaybackTest',
)
assert all(name in phone_suites for name in required)
for task in (':composeApp:compileDebugKotlin', ':tvApp:compileDebugKotlin',
             ':composeApp:lintDebug', ':tvApp:lintDebug', ':composeApp:verifyDesignSystemUsage'):
    assert f'> Task {task}' in log, task

result = dict(
    verifiedAt=datetime.now(timezone.utc).isoformat(),
    tests=totals,
    regressionSuites={name: phone_suites[name] for name in required},
    unchangedSourceInputs=len(inputs),
    compile=['phone Debug Kotlin', 'TV Debug Kotlin'],
    lint=['phone Debug', 'TV Debug', 'design-system usage'],
    packaging='Paused by user; no APK containing this feature built or delivered',
    deviceValidation=False,
)
(HERE / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(result, ensure_ascii=False, indent=2))
