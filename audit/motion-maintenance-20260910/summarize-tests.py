from pathlib import Path
import xml.etree.ElementTree as ET
import json

out = {}
for module, task in [('composeApp', 'testReleaseUnitTest'), ('tvApp', 'testDebugUnitTest'),
                     ('watchTogetherProtocol', 'jvmTest'), ('watchTogetherServer', 'test')]:
    files = list(Path(module, 'build/test-results', task).glob('TEST-*.xml'))
    totals = {k: sum(int(ET.parse(p).getroot().get(k, 0)) for p in files)
              for k in ['tests', 'failures', 'errors', 'skipped']}
    totals['suites'] = len(files)
    out[module] = totals
print(json.dumps(out, indent=2))
Path('audit/motion-maintenance-20260910/test-summary.json').write_text(
    json.dumps(out, indent=2), encoding='utf-8')

