#!/usr/bin/env bash
set -euo pipefail
test "$GITHUB_ACTIONS" = true
abi=$(adb shell getprop ro.product.cpu.abilist64 | tr -d '\r')
echo "Guest ABI $abi, API $(adb shell getprop ro.build.version.sdk)"
test "${abi%%,*}" = arm64-v8a
adb root || true
adb wait-for-device
id=$(adb shell id)
[[ "$id" == uid=0* ]]
adb install -r -g preview/composeApp-release.apk
adb install -r pool/driver.apk
adb reverse tcp:18382 tcp:18382
adb shell wm size 720x1280
adb shell wm density 260
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put secure accessibility_interactive_ui_timeout_ms 60000
adb shell wm dismiss-keyguard
touch /tmp/yfuse-tree-active
tar -xzf pool/fixtures.tgz
mkdir -p results
for engine in YCore SenPlayer; do
  python3 qa-arm-dual/engine.py "$engine"
  rm -f results/volume.json
  if ! QA_ENGINE="$engine" python3 qa-arm-dual/test.py 0; then echo "Incomplete $engine confirmations"; fi
  if [[ -s results/volume.json ]]; then cp results/volume.json "results/$engine.json"; fi
done
python3 - <<'PY'
import json,pathlib
for engine in ['YCore','SenPlayer']:
    d=json.loads(pathlib.Path('results',engine+'.json').read_text())
    print('REAL CLOUD TOTAL',engine,d['planned'],d['issued'],d['verified'])
    if not d['issued'] or d['verified']!=d['planned']: raise SystemExit(1)
PY
