#!/usr/bin/env bash
# Temporary: the release gate's Android 15 smoke under one emulator configuration, with the host
# and guest evidence the gate cannot keep when the emulator dies. Removed before the fix is merged.
set -uo pipefail
mkdir -p artifacts/cloud-ui
serial=emulator-5554

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
adb -s $serial logcat -v threadtime > /tmp/guest-logcat.txt 2>&1 &
logcat=$!

gate_smoke() {
  python3 "${1:-scripts/android_cloud_ui.py}" --apk-directory artifacts/cloud-input --output artifacts/cloud-ui \
    --source-run 37128629545 --expected-api 35 \
    --expected-sha256 e06d261f1c207582df890081dd58d71d044d5caedda55b465f447b335729f195 \
    --expected-version-code 261 --expected-version-name 1.0.99
}

case "${PROBE_MODE:-gate}" in
  gate)
    gate_smoke
    ;;
  thermal)
    # Thermal status MODERATE: Yfuse's motion budget draws as 静息 and turns the live backdrop
    # blur (dock and bar glass) off, as it does in 省电模式. The status is set before install, so
    # the app starts with it, and the override holds until reset.
    adb -s $serial shell cmd thermalservice override-status 2
    echo "==== thermal status before the smoke"
    adb -s $serial shell dumpsys thermalservice | grep -iE "status|override" | head -6
    gate_smoke
    ;;
esac
status=$?
sleep 5
kill "$monitor" "$logcat" 2>/dev/null

echo "==== 首页 text as captured (05-home: portrait, 08-dark: landscape dark)"
for f in artifacts/cloud-ui/05-home.xml artifacts/cloud-ui/08-dark.xml; do
  [ -f "$f" ] || continue
  echo "-- $f"
  grep -o 'text="[^"]*"\|content-desc="[^"]*"' "$f" | grep -v '=""' | head -45
done
echo "==== thermal status after the smoke (the override must still hold)"
timeout 15 adb -s $serial shell dumpsys thermalservice | grep -iE "status|override" | head -6 || echo "device not answering"
echo "==== smoke exit status: $status"
echo "==== host monitor"
cat /tmp/host-monitor.txt
echo "==== emulator process"
pgrep -a qemu-system || echo "qemu-system is not running"
echo "==== kernel log"
sudo dmesg -T | grep -iE "oom|killed process|segfault|qemu|trap|general protection" | tail -40 || true
echo "==== guest log: fatal, crash, memory and graphics lines"
grep -E " F |FATAL|AndroidRuntime|DEBUG   :|lowmemorykiller|lmkd|ANR in|Watchdog" /tmp/guest-logcat.txt | tail -60
echo "==== guest log tail"
tail -n 80 /tmp/guest-logcat.txt
exit "$status"
