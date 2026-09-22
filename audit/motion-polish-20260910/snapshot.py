from pathlib import Path
import difflib, hashlib, json
ROOT=Path(__file__).resolve().parents[2]
OUT=Path(__file__).resolve().parent
new={
 'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/MotionPolish.kt',
 'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/RefreshIndicator.kt',
 'composeApp/src/commonTest/kotlin/com/yfuse/core/designsystem/ToastQueueTest.kt',
}
records=[]
for path in (OUT/'changed-files.txt').read_text().splitlines():
    target=ROOT/path
    before='' if path in new else (OUT/'before'/path).read_text(encoding='utf-8')
    after=target.read_text(encoding='utf-8')
    if before==after:continue
    diff=list(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile=path+' (before turn)',tofile=path))
    out=OUT/'diff'/(path+'.patch');out.parent.mkdir(parents=True,exist_ok=True);out.write_text(''.join(diff),encoding='utf-8')
    records.append(dict(path=path,created=path in new,sha256=hashlib.sha256(target.read_bytes()).hexdigest(),
        added=sum(x.startswith('+') and not x.startswith('+++') for x in diff),
        removed=sum(x.startswith('-') and not x.startswith('---') for x in diff)))
(OUT/'snapshot.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
(OUT/'changed-files.txt').write_text(''.join(r['path']+'\n' for r in records),encoding='utf-8')
print(json.dumps(dict(files=len(records),new=sum(r['created'] for r in records),added=sum(r['added'] for r in records),removed=sum(r['removed'] for r in records))))
