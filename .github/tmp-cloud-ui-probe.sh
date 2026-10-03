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

python3 scripts/android_cloud_ui.py --apk-directory artifacts/cloud-input --output artifacts/cloud-ui \
  --source-run 37128629545 --expected-api 35 \
  --expected-sha256 e06d261f1c207582df890081dd58d71d044d5caedda55b465f447b335729f195 \
  --expected-version-code 261 --expected-version-name 1.0.99
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
