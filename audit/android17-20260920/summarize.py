"""Verify final migration checks without creating or delivering an APK."""
import json
import xml.etree.ElementTree as ET
from pathlib import Path

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
log = (AUDIT / 'verify.log').read_text(encoding='utf-8-sig')
assert 'BUILD SUCCESSFUL' in log and 'BUILD FAILED' not in log
for task in (':composeApp:compileDebugKotlin', ':tvApp:compileDebugKotlin',
             ':phoneShared:testAndroidHostTest', ':tvShared:testAndroidHostTest',
             ':composeApp:verifyDesignSystemUsage', ':composeApp:lintDebug', ':tvApp:lintDebug'):
    assert f'> Task {task}' in log, task
result = {'packaged': False, 'uploaded': False, 'deviceValidated': False, 'manifests': {}, 'tests': {}, 'lint': {}}
for module in ('composeApp', 'tvApp'):
    path = ROOT / module / 'build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml'
    manifest = ET.parse(path).getroot()
    sdk = manifest.find('uses-sdk')
    assert sdk.get(ANDROID + 'targetSdkVersion') == '37'
    assert sdk.get(ANDROID + 'minSdkVersion') == '26'
    permissions = [node.get(ANDROID + 'name') for node in manifest.findall('uses-permission')]
    assert permissions.count('android.permission.ACCESS_LOCAL_NETWORK') == 1
    assert manifest.find('application').get(ANDROID + 'networkSecurityConfig')
    result['manifests'][module] = {'targetSdk': 37, 'minSdk': 26, 'localNetworkPermission': True}
    (AUDIT / f'{module}-merged-manifest.xml').write_bytes(path.read_bytes())
    lint = ET.parse(ROOT / module / 'build/reports/lint-results-debug.xml').getroot()
    issues = [{'id': item.get('id'), 'severity': item.get('severity'), 'message': item.get('message')}
              for item in lint.findall('issue')]
    assert not any(item['severity'] in ('Error', 'Fatal') for item in issues), issues
    result['lint'][module] = issues
for module, expected in (('phoneShared', 126), ('tvShared', 63)):
    reports = sorted((ROOT / module / 'build/test-results/testAndroidHostTest').glob('TEST-*.xml'))
    suites = []
    for path in reports:
        suite = ET.parse(path).getroot()
        record = {'name': suite.get('name'), **{key: int(suite.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}}
        assert record['failures'] == record['errors'] == record['skipped'] == 0, record
        suites.append(record)
    assert sum(suite['tests'] for suite in suites) == expected
    result['tests'][module] = {'passed': expected, 'suites': suites}
for module in ('composeApp', 'phoneShared', 'tvApp', 'tvShared', 'macrobenchmark'):
    assert not any(line.startswith('io.ktor:') and ':3.0.3=' in line
                   for line in (ROOT / module / 'gradle.lockfile').read_text().splitlines())
(AUDIT / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'targetSdk': 37, 'phoneTests': 126, 'tvTests': 63,
                  'lint': {module: len(issues) for module, issues in result['lint'].items()},
                  'deviceValidated': False, 'packaged': False}, ensure_ascii=False))
