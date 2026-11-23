set -eu
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell wm dismiss-keyguard
mkdir -p feature-evidence
collect(){ adb logcat -b crash -d | sed -E 's@https?://[^ ]+@[url]@g' >feature-evidence/crashes.txt || true; adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|mFocusedApp' >feature-evidence/foreground.txt || true; adb exec-out screencap -p >feature-evidence/final.png || true; }
trap collect EXIT
if [[ "$1" == extra || "$1" == settings-actions || "$1" == score ]]; then
 bash qa-functional/driver-build.sh
 touch /tmp/yfuse-tree-active
fi
if [[ "$1" == settings ]]; then
 /usr/bin/python3 qa-functional/smoke.py "${2%%.*}"
 exit
fi
if [[ "$1" == external ]]; then
 bash qa-functional/external.sh
 exit
fi
if [[ "$1" == extra ]]; then
 adb install -r -g "$(find app-apk -name '*.apk' | head -1)"
 /usr/bin/python3 qa-functional/extra.py
 exit
fi
sock="$RUNNER_TEMP/functional-media.sock"
mkdir -p qa-emby-local feature-evidence
sudo unshare -n -- /usr/bin/python3 qa-scrub/isolated.py qa-emby-bin/opt/emby-server qa-media qa-emby-local "$sock" > qa-server.log 2>&1 &
server=$!
trap 'sudo kill "$server" 2>/dev/null || true' EXIT
for n in $(seq 1 120); do [[ ! -S "$sock" ]] || break; sleep 1; done
test -S "$sock"
/usr/bin/python3 qa-scrub/isolated.py relay "$sock" >/dev/null 2>&1 &
adb reverse tcp:18196 tcp:18196
adb install -r -g "$(find app-apk -name '*.apk' | head -1)"
if [[ "$1" == score ]]; then
 /usr/bin/python3 qa-functional/score.py
 adb shell getprop ro.product.cpu.abilist >feature-evidence/device-abis.txt
 adb shell getprop ro.dalvik.vm.native.bridge >>feature-evidence/device-abis.txt
 adb shell dumpsys package com.yfuse.validation | grep -E 'primaryCpuAbi|secondaryCpuAbi' >>feature-evidence/device-abis.txt || true
 adb logcat -d -s nativeloader:V linker:V 'NativeBridge:V' 'art:W' | grep -iE 'failed|bridge|unsatisfied|ffmpegkit|mlibkit|could not|wrong elf|libc\+\+' | sed -E 's@https?://[^ ]+@[url]@g' >feature-evidence/native-loader.txt || true
elif [[ "$1" == settings-actions ]]; then
 /usr/bin/python3 qa-functional/settings-actions.py
else
 /usr/bin/python3 qa-functional/features.py
fi
adb shell dumpsys activity exit-info com.yfuse.validation > feature-evidence/process-exit.txt
