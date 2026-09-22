from pathlib import Path
import difflib
import json
import re

out = Path(__file__).resolve().parent
root = out.parents[1]
files = (out / 'changed-files.txt').read_text(encoding='utf-8').splitlines()
diff = []
for name in files:
    before = out / 'before' / name
    old = before.read_text(encoding='utf-8') if before.exists() else ''
    new = (root / name).read_text(encoding='utf-8')
    diff.extend(difflib.unified_diff(old.splitlines(True), new.splitlines(True), 'before/' + name, name))
(out / 'changes.diff').write_text(''.join(diff), encoding='utf-8')

ds = 'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/'
tokens = (root / (ds + 'Tokens.kt')).read_text(encoding='utf-8')
values = dict(re.findall(r'const val ((?:ENTER|EXIT)_\w+) = (\d+)', tokens))
old = (out / 'before' / (ds + 'DialogAnimation.kt')).read_text(encoding='utf-8')
new = (root / (ds + 'DialogAnimation.kt')).read_text(encoding='utf-8')
new = re.sub(r'Motion.Dialog.((?:ENTER|EXIT)_\w+)', lambda m: values[m[1]], new)
pattern = r'(\w+)\(\s*"([^"]*)",\s*"([^"]*)",\s*(\d+),\s*(\d+),?\s*\)'
old_entries = re.findall(pattern, old)
new_entries = re.findall(pattern, new)
assert len(old_entries) == 43 and old_entries == new_entries

old_dialogs = (out / 'before' / (ds + 'Dialogs.kt')).read_text(encoding='utf-8')
curves = re.findall(r'CubicBezierEasing\(([^)]+)\)', old_dialogs)
assert len(curves) == 2 and all('CubicBezierEasing(' + curve + ')' in tokens for curve in curves)
report = {'changedFiles': len(files), 'dialogCount': len(old_entries),
          'dialogNamesOrderLabelsDescriptionsDurations': 'unchanged', 'dialogCurves': 'unchanged'}
(out / 'compatibility.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
