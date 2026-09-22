"""Read-only source verification; outputs evidence only under this audit directory."""
import collections
import importlib.util
import json
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = pathlib.Path(__file__).resolve().parent
files = subprocess.check_output(['git', '-c', f'safe.directory={ROOT.as_posix()}', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
sources = [p for p in files if '/src/' in p and p.endswith('.kt')]
counts = {}
texts = {}
for p in sources:
    text = (ROOT / p).read_text(encoding='utf-8-sig')
    texts[p] = text
    module = p.split('/')[0]
    item = counts.setdefault(module, dict(files=0, lines=0, test_files=0))
    item['files'] += 1
    item['lines'] += len(text.splitlines())
    item['test_files'] += any('test' in part.lower() for part in pathlib.PurePosixPath(p).parts[2:-1])
baseline = {}
for p in (ROOT / 'config/ktlint').glob('*.xml'):
    tree = ET.parse(p)
    baseline[p.name] = dict(files=len(tree.findall('.//file')), errors=len(tree.findall('.//error')), rules=dict(collections.Counter(x.attrib['source'] for x in tree.findall('.//error'))))
joined = '\n'.join(texts.values())
patterns = { 'catch_exception': r'catch\s*\([^:]+:\s*Exception\s*\)', 'catch_throwable': r'catch\s*\([^:]+:\s*Throwable\s*\)', 'empty_catch': r'catch\s*\([^)]*\)\s*\{\s*\}', 'discard_exception_name': r'catch\s*\(_\s*:\s*Exception\s*\)', 'runCatching': r'\brunCatching\b', 'non_null_assertion': r'!!', 'suppress': r'@Suppress\b' }
result = dict(commit=subprocess.check_output(['git','-c',f'safe.directory={ROOT.as_posix()}','rev-parse','HEAD'], cwd=ROOT).decode().strip(), source_scope='Git-tracked Kotlin files under module src; physical lines including comments/blanks; regex counts are not defect counts', modules=counts, baseline=baseline, lexical_counts={k:len(re.findall(v,joined)) for k,v in patterns.items()}, largest_files=sorted([(p,len(t.splitlines())) for p,t in texts.items()],key=lambda x:x[1],reverse=True)[:10])
spec = importlib.util.spec_from_file_location('supply_chain_check',ROOT/'scripts/supply_chain_check.py')
scanner = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = scanner
spec.loader.exec_module(scanner)
overrides = scanner.read_security_overrides(ROOT)
deps = {}
locks = [p for p in files if pathlib.PurePosixPath(p).name == 'gradle.lockfile']
for p in locks:
    for line in (ROOT/p).read_text().splitlines():
        m = scanner.COORDINATE.match(line)
        if m:
            group,name,version = m.groups()
            dep = scanner.Dependency(group,name,overrides.get(f'{group}:{name}',version),p)
            deps[dep.coordinate] = dep
result['tracked_locks'] = locks
result['effective_dependency_count'] = len(deps)
old = json.loads((OUT/'sbom.spdx.json').read_text())
old_coords = {p['name']+':'+p['versionInfo'] for p in old['packages']}
result['old_sbom_extra_coordinates'] = sorted(old_coords-set(deps))
result['old_sbom_missing_coordinates'] = sorted(set(deps)-old_coords)
(OUT/'verification-evidence.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,ensure_ascii=False,indent=2),flush=True)
if '--osv' in sys.argv:
    try:
        matches=scanner.query_osv(list(deps.values()))
        details=scanner.fetch_details({identifier for _,identifier in matches})
        findings=[(dep,details[i]) for dep,i in matches if 'withdrawn' not in details[i]]
        rows=[dict(dependency=d.coordinate,id=v['id'],severity=scanner.severity(v),summary=v.get('summary')) for d,v in findings]
        (OUT/'verified-osv-findings.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        scanner.write_spdx(list(deps.values()),findings,OUT/'verified-sbom.spdx.json')
        print(json.dumps(rows,ensure_ascii=False,indent=2))
        print('verified_gate_exit='+str(int(any(scanner.severity(v) in scanner.BLOCKING for _,v in findings))))
    except Exception as e:
        print('OSV_RECHECK_UNAVAILABLE: '+str(e))
        sys.exit(2)
