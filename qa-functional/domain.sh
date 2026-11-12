set -euo pipefail
mkdir -p qa-emby-local qa-emby-evidence feature-evidence
sock="$RUNNER_TEMP/seek-emby.sock"
sudo unshare -n -- /usr/bin/python3 qa-scrub/isolated.py qa-emby-bin/opt/emby-server qa-media qa-emby-local "$sock" > qa-emby-start.log 2>&1 &
service_pid=$!
trap 'sudo kill "$service_pid" 2>/dev/null || true' EXIT
for ((i=0;i<120;i++)); do
 [[ ! -S "$sock" ]] || break
 sudo kill -0 "$service_pid" 2>/dev/null || break
 sleep 1
done
if [[ -S "$sock" ]]; then
 /usr/bin/python3 qa-scrub/isolated.py relay "$sock" > /dev/null 2>&1 &
 adb reverse tcp:18196 tcp:18196
fi
pkg=com.yfuse.validation
adb shell wm dismiss-keyguard
adb shell settings put secure immersive_mode_confirmations confirmed
adb install -r -g "$(find app/composeApp/build/outputs/apk/debug -name '*.apk' | head -1)"
adb install -r -g "$(find app/composeApp/build/outputs/apk/androidTest -name '*.apk' | head -1)"
adb shell "run-as $pkg mkdir -p files"
for f in ProbeA ProbeB; do
 adb shell -T "run-as $pkg sh -c 'cat > files/$f.mp4'" < "qa-media/$f.mp4"
done
python3 - <<'PY'
import pathlib,json,hashlib
base='file:///data/user/0/com.yfuse.validation/files/'
targets=[.72,.14,.84,.27,.64,.10]
scenarios={'0-LocalA':{'url':base+'ProbeA.mp4','seconds':150,'targets':targets},'1-LocalB':{'url':base+'ProbeB.mp4','seconds':105,'targets':[.74,.18]}}
s=pathlib.Path('qa-emby-local/streams.json')
if s.exists():
 items=json.loads(s.read_text())
 pathlib.Path('qa-emby-local/functional-input.json').write_text(json.dumps({'password':pathlib.Path('qa-emby-local/local.secret').read_text(),'item':items['ProbeA']['id'],'sha':hashlib.sha256(pathlib.Path('qa-media/ProbeA.mp4').read_bytes()).hexdigest()}))
 for n,k,clip in [('2-EmbyDirectA','url','ProbeA'),('3-EmbyDirectB','url','ProbeB'),('4-EmbyHls','hls','ProbeA')]:
  if items[clip].get(k):scenarios[n]={'url':items[clip][k],'seconds':150 if clip=='ProbeA' else 105,'targets':targets if clip=='ProbeA' else [.81,.19]}
pathlib.Path('qa-emby-local/input.json').write_text(json.dumps(scenarios))
PY
adb shell -T "run-as $pkg sh -c 'cat > files/touch-input.json'" < qa-emby-local/input.json
adb shell am instrument -w -e class com.yfuse.feature.player.SeekTouchDeviceTest "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee qa-emby-evidence/instrument.txt
adb exec-out run-as "$pkg" tar -cf - -C files/touch-results . | tar -xf - -C qa-emby-evidence
adb shell dumpsys activity exit-info "$pkg" > qa-emby-evidence/exit-info.txt
if test -f qa-emby-local/functional-input.json; then
 adb shell -T "run-as $pkg sh -c 'cat > files/functional-input.json'" < qa-emby-local/functional-input.json
 adb shell am instrument -w -e class com.yfuse.feature.player.PopulatedLibraryDeviceTest "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee feature-evidence/domain-instrument.txt
 adb exec-out run-as "$pkg" cat files/domain-results.jsonl > feature-evidence/domain.jsonl
 /usr/bin/python3 qa-functional/features.py --populated
fi
grep -q 'OK (1 test)' feature-evidence/domain-instrument.txt
grep -q 'OK (1 test)' qa-emby-evidence/instrument.txt
