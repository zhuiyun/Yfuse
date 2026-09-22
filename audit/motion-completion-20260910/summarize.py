from pathlib import Path
import difflib, hashlib, json, re, xml.etree.ElementTree as ET

out = Path(__file__).resolve().parent
root = out.parents[1]
files = (out/'changed-files.txt').read_text(encoding='utf-8').splitlines()
changes = []
for name in files:
    path = root/name
    before = out/'before'/name
    old = before.read_text(encoding='utf-8').splitlines() if before.exists() else []
    new = path.read_text(encoding='utf-8').splitlines()
    delta = list(difflib.ndiff(old, new))
    changes.append(dict(file=name, before=len(old), after=len(new), added=sum(x.startswith('+ ') for x in delta),
        removed=sum(x.startswith('- ') for x in delta), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
(out/'source-snapshot.json').write_text(json.dumps(changes, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
tests = {}
for task in ['composeApp/build/test-results/testReleaseUnitTest','tvApp/build/test-results/testDebugUnitTest',
             'watchTogetherProtocol/build/test-results/jvmTest','watchTogetherServer/build/test-results/test']:
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in (root/task).glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key,0))
    tests[task] = totals
(out/'test-counts.json').write_text(json.dumps(tests, indent=2)+'\n', encoding='utf-8')
print(json.dumps(dict(files=len(changes),added=sum(c['added'] for c in changes),removed=sum(c['removed'] for c in changes),tests=tests),ensure_ascii=False,indent=2))

