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
docker pull --platform linux/arm64 docker.io/redroid/redroid:12.0.0_64only-latest
digest=$(docker image inspect docker.io/redroid/redroid:12.0.0_64only-latest --format '{{index .RepoDigests 0}}')
echo "Cloud Android image $digest"
docker run -d --name qa-android --platform linux/arm64 --privileged -p 127.0.0.1:15555:5555 "$digest" androidboot.use_memfd=true androidboot.redroid_gpu_mode=guest androidboot.redroid_width=720 androidboot.redroid_height=1280 androidboot.redroid_dpi=260
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
mkdir -p "$RUNNER_TEMP/qa-arm-tools"
ln -sf "$ADB" "$RUNNER_TEMP/qa-arm-tools/adb"
echo "$RUNNER_TEMP/qa-arm-tools" >> "$GITHUB_PATH"
echo 'ANDROID_SERIAL=127.0.0.1:15555' >> "$GITHUB_ENV"
