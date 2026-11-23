set -euo pipefail
cd app
mkdir -p ../logic-evidence
./gradlew :phoneShared:tasks --all --console=plain > ../logic-evidence/tasks.txt
task=$(awk '$1 ~ /^connected.*AndroidDeviceTest$/ || $1=="connectedDeviceTest" || $1=="connectedAndroidTest" {print $1; exit}' ../logic-evidence/tasks.txt)
if [[ -z "$task" ]]; then task=connectedCheck; fi
echo "Running :phoneShared:$task"
set +e
./gradlew ":phoneShared:$task" "-Pandroid.testInstrumentationRunnerArguments.notClass=$(cat ../logic-host-store-classes.txt)" --console=plain --no-daemon > ../logic-evidence/gradle.log 2>&1
rc=$?
set -e
tail -45 ../logic-evidence/gradle.log
find phoneShared/build -type f -name '*.xml' \( -path '*test-results*' -o -path '*Test-results*' -o -name 'TEST-*' \) -exec cp --parents '{}' ../logic-evidence \;
python3 - <<'PY'
import pathlib,xml.etree.ElementTree as ET,json,collections
p=pathlib.Path('../logic-evidence');out=[]
for f in p.rglob('*.xml'):
 try:t=ET.parse(f)
 except ET.ParseError:continue
 for v in t.iter('testcase'):
  result='error' if v.find('error') is not None else 'failed' if v.find('failure') is not None else 'skipped' if v.find('skipped') is not None else 'passed'
  out.append(dict(group=v.get('classname'),name=v.get('name'),result=result))
(p/'cases.json').write_text(json.dumps(out))
print('Device logic cases',collections.Counter(a['result'] for a in out))
if not out:raise SystemExit(4)
PY
exit "$rc"
