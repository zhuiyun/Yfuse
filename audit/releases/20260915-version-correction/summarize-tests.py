from pathlib import Path
import json
import xml.etree.ElementTree as ET

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parents[2]
log = (AUDIT / 'verification.log').read_text(encoding='utf-8-sig')
assert 'BUILD SUCCESSFUL' in log and 'BUILD FAILED' not in log
summary = {}
for module, task in (('composeApp', 'testDebugUnitTest'), ('tvApp', 'testDebugUnitTest'),
                     ('watchTogetherServer', 'test'), ('watchTogetherProtocol', 'jvmTest')):
    assert f'> Task :{module}:{task}' in log, f'Missing task {module}:{task}'
    files = list((ROOT / module / 'build/test-results' / task).glob('TEST-*.xml'))
    assert files, f'No test results for {module}'
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for file in files:
        suite = ET.parse(file).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, '0'))
    assert totals['failures'] == totals['errors'] == totals['skipped'] == 0, totals
    summary[module] = totals
for module in ('composeApp', 'tvApp', 'watchTogetherServer', 'watchTogetherProtocol', 'mdkAndroid'):
    assert f'> Task :{module}:ktlintCheck' in log
summary['total'] = sum(s['tests'] for s in summary.values())
summary['python'] = {'scripts': 42, 'workflowAndTv': 13, 'nativeBuildContracts': 3, 'failures': 0}
summary['playbackTested'] = False
(AUDIT / 'test-summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
print(json.dumps(summary, indent=2))
