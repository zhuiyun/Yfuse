#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
test "$(uname -m)" = aarch64
kernel=$(uname -r)
echo "Cloud ARM64 kernel $kernel"
if [[ ! -d /sys/module/binder_linux ]]; then
  if ! modinfo binder_linux >/dev/null 2>&1; then
    sudo apt-get -qq update
    sudo apt-get install -y "linux-modules-extra-$kernel" || true
  fi
  sudo modprobe binder_linux devices=binder,hwbinder,vndbinder
fi
grep -w binder /proc/filesystems
command -v adb >/dev/null || { sudo apt-get -qq update && sudo apt-get install -y adb; }
if ! adb version >/dev/null 2>&1; then
  sudo apt-get -qq update
  sudo apt-get install -y adb
fi
ADB=$(command -v adb)
if ! "$ADB" version >/dev/null 2>&1; then ADB=/usr/bin/adb; fi
"$ADB" version
image=docker.io/redroid/redroid@sha256:a6c464bbedcf1dcb67dbf91f329fbb19bee5b50631f0ca6bda6ed7c41b0e64e2
for attempt in 1 2 3; do
  if docker pull --platform linux/arm64 "$image" 2>&1 | tee "$RUNNER_TEMP/android-image-pull.log"; then break; fi
  if (( attempt == 3 )) || ! grep -Eiq 'timeout|timed out|connection reset|temporar(il)?y unavailable|bad gateway|service unavailable|gateway time.?out' "$RUNNER_TEMP/android-image-pull.log"; then
    exit 1
  fi
  sleep $((attempt * 5))
done
test "$(docker image inspect "$image" --format '{{.Architecture}}')" = arm64
echo "Cloud Android image $image"
docker run -d --name qa-android --platform linux/arm64 --privileged -p 127.0.0.1:15555:5555 "$image" androidboot.use_memfd=true androidboot.redroid_gpu_mode=guest androidboot.redroid_width=720 androidboot.redroid_height=1280 androidboot.redroid_dpi=260
for i in $(seq 1 120); do
  "$ADB" connect 127.0.0.1:15555 >/dev/null 2>&1 || true
  boot=$("$ADB" -s 127.0.0.1:15555 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [[ "$boot" == 1 ]]; then break; fi
  sleep 3
done
test "$boot" = 1
abi=$("$ADB" -s 127.0.0.1:15555 shell getprop ro.product.cpu.abilist64 | tr -d '\r')
echo "Android boot completed, Android ABI $abi"
test "${abi%%,*}" = arm64-v8a
# adbd restarts as root. Its old TCP transport may still briefly report 'device'.
"$ADB" -s 127.0.0.1:15555 root >/dev/null 2>&1 || true
root_ready=false
for attempt in $(seq 1 45); do
  "$ADB" connect 127.0.0.1:15555 >/dev/null 2>&1 || true
  uid=$("$ADB" -s 127.0.0.1:15555 shell id -u 2>/dev/null | tr -d '\r' || true)
  if [[ "$uid" == 0 ]]; then
    root_ready=true
    break
  fi
  sleep 2
done
test "$root_ready" = true
mkdir -p "$RUNNER_TEMP/qa-arm-tools"
ln -sf "$ADB" "$RUNNER_TEMP/qa-arm-tools/adb"
echo "$RUNNER_TEMP/qa-arm-tools" >> "$GITHUB_PATH"
echo 'ANDROID_SERIAL=127.0.0.1:15555' >> "$GITHUB_ENV"
