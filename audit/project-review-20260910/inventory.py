import json, subprocess
from pathlib import Path
roots=['composeApp/src','tvApp/src','watchTogetherServer/src','watchTogetherProtocol/src','macrobenchmark/src','harmonyApp/entry/src/main','ycore-native/src']
files=subprocess.check_output(['rg','--files',*roots],text=True,encoding='utf-8').splitlines()
rows=[]
for f in files:
 p=Path(f)
 if p.suffix not in {'.kt','.cj','.cpp','.h'}:continue
 rows.append({'file':p.as_posix(),'lines':len(p.read_text(encoding='utf-8').splitlines()),'test':'test' in p.as_posix().lower()})
out={'sourceFiles':len(rows),'sourceLines':sum(r['lines'] for r in rows),'testFiles':sum(r['test'] for r in rows),'largestProductionFiles':sorted([r for r in rows if not r['test']],key=lambda x:x['lines'],reverse=True)[:18]}
Path('audit/project-review-20260910/inventory.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(out,ensure_ascii=False,indent=2))
