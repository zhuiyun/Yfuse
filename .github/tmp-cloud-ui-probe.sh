#!/usr/bin/env bash
# Temporary: the release gate's Android 15 smoke under one emulator configuration, with the host
# and guest evidence the gate cannot keep when the emulator dies. Removed before the fix is merged.
set -uo pipefail
mkdir -p artifacts/cloud-ui

(
  while true; do
    printf '%s ' "$(date -u +%T)"
    free -m | awk 'NR==2 { printf "host used=%sM free=%sM available=%sM ", $3, $4, $7 }'
    ps -C qemu-system-x86_64 -o rss=,%cpu= 2>/dev/null | awk '{ printf "qemu rss=%dM cpu=%s%%", $1 / 1024, $2 }'
    echo
    sleep 10
  done
) > /tmp/host-monitor.txt 2>&1 &
monitor=$!
adb -s emulator-5554 logcat -v threadtime > /tmp/guest-logcat.txt 2>&1 &
logcat=$!

echo "==== guest graphics configuration"
adb -s emulator-5554 shell getprop | grep -iE "renderengine|vulkan|hwui.renderer|egl|gltransport|ro.hardware" | head -20
adb -s emulator-5554 shell dumpsys SurfaceFlinger 2>/dev/null | grep -iE "renderengine|vulkan|skia" | head -10
echo "==== end of graphics configuration"

case "${PROBE_MODE:-gate}" in
  portrait)
    # The gate's script, restoring the display settings (font, rotation, night mode) before the soak.
    cp scripts/android_cloud_ui.py scripts/_probe_portrait.py
    python3 - <<'PATCH'
from pathlib import Path
path = Path("scripts/_probe_portrait.py")
text = path.read_text()
soak = '    session.case("Short foreground/background stability", lambda: session.soak(args.soak_seconds))'
assert soak in text
text = text.replace(soak, '    session.case("Display settings restored before the soak", session.settle_for_layout_probe)\n' + soak, 1)
path.write_text(text)
PATCH
    python3 scripts/_probe_portrait.py --apk-directory artifacts/cloud-input --output artifacts/cloud-ui \
      --source-run 37128629545 --expected-api 35 \
      --expected-sha256 e06d261f1c207582df890081dd58d71d044d5caedda55b465f447b335729f195 \
      --expected-version-code 261 --expected-version-name 1.0.99
    ;;
  settings)
    # No Yfuse: the system Settings app through the same landscape, 1.3 font, dark soak.
    serial=emulator-5554
    adb -s $serial shell settings put system font_scale 1.3
    adb -s $serial shell settings put system accelerometer_rotation 0
    adb -s $serial shell settings put system user_rotation 1
    adb -s $serial shell cmd uimode night yes
    timeout 90 adb -s $serial shell am start -W -n com.android.settings/.Settings
    sleep 5
    done_cycles=0
    first=$(timeout 20 adb -s $serial shell pidof com.android.settings)
    for i in $(seq 1 21); do
      timeout 60 adb -s $serial shell input keyevent 3 || { echo "cycle $i: home failed"; break; }
      sleep 2
      timeout 60 adb -s $serial shell am start -W -n com.android.settings/.Settings > /dev/null || { echo "cycle $i: am start failed"; break; }
      sleep 3
      pid=$(timeout 20 adb -s $serial shell pidof com.android.settings)
      echo "cycle $i settings pid=$pid (first $first)"
      [ -n "$pid" ] || break
      done_cycles=$i
    done
    echo "settings control completed $done_cycles of 21 cycles"
    [ "$done_cycles" = 21 ]
    ;;
esac
status=$?
sleep 5
kill "$monitor" "$logcat" 2>/dev/null

echo "==== smoke exit status: $status"
echo "==== host monitor"
cat /tmp/host-monitor.txt
echo "==== emulator process"
pgrep -a qemu-system || echo "qemu-system is not running"
echo "==== kernel log"
sudo dmesg -T | grep -iE "oom|killed process|segfault|qemu|trap|general protection" | tail -40 || true
echo "==== emulator crash records"
find /tmp -maxdepth 3 -name 'emu-crash*' -exec ls -la {} + 2>/dev/null || true
echo "==== guest log: fatal, crash, memory and graphics lines"
grep -E " F |FATAL|AndroidRuntime|DEBUG   :|lowmemorykiller|lmkd|SurfaceFlinger|gralloc|[Vv]ulkan|ANR in|Watchdog|system_server|goldfish|gfxstream|ndk_translation" \
  /tmp/guest-logcat.txt | tail -200
echo "==== guest log tail"
tail -n 150 /tmp/guest-logcat.txt
exit "$status"
