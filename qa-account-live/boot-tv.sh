#!/usr/bin/env bash
set -euo pipefail
test "$GITHUB_ACTIONS" = true
test "$(uname -m)" = aarch64
image=$(docker inspect qa-android --format '{{.Image}}')
docker run -d --name qa-account-tv --platform linux/arm64 --privileged -p 127.0.0.1:15556:5555 "$image" \
  androidboot.use_memfd=true androidboot.redroid_gpu_mode=guest androidboot.redroid_width=1280 \
  androidboot.redroid_height=720 androidboot.redroid_dpi=200
ready=false
for attempt in $(seq 1 120); do
  adb connect 127.0.0.1:15556 >/dev/null 2>&1 || true
  boot=$(adb -s 127.0.0.1:15556 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [[ "$boot" == 1 ]]; then ready=true; break; fi
  sleep 3
done
test "$ready" = true
adb -s 127.0.0.1:15556 root >/dev/null 2>&1 || true
for attempt in $(seq 1 45); do
  adb connect 127.0.0.1:15556 >/dev/null 2>&1 || true
  uid=$(adb -s 127.0.0.1:15556 shell id -u 2>/dev/null | tr -d '\r' || true)
  if [[ "$uid" == 0 ]]; then break; fi
  sleep 2
done
test "$uid" == 0
adb -s 127.0.0.1:15556 shell wm size 1280x720
adb -s 127.0.0.1:15556 shell wm density 200
