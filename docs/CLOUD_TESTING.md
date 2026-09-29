# Cloud UI evidence

**Publish Android update** calls this workflow for every production-signed package,
publishing or not, before anything is saved or uploaded: its `smoke` job hands over the
signed APK artifact, its SHA-256 and the expected version, and the release goes no further
unless that exact APK installs, starts and passes the smoke on every API level below. It
used to run only after packaging had finished, so it could not stop a bad release.

It uses Ubuntu 24.04 KVM and Android 35, 36 and 37 Google APIs x86_64 images (37 through
the `android-37.0` packages; the runner's own cmdline-tools misread that image, so the job
installs the pinned newer ones first). The script fails a run whose booted system does not
report the expected API level, or whose APK does not match the expected SHA-256, versionCode
and versionName. ARM64 native translation must be advertised and the actual APK must install
and start; image assumptions alone never count as a pass. Real devices and existing
installations are refused.

A manual dispatch remains for looking at an already published package: it takes that
package run's ID and exact source SHA, verifies the source version, artifact manifest
SHA-256 and production signing certificate, and can run the layout probe below instead of
the full smoke.

Artifacts contain JSON case results, screenshots, accessibility trees, startup
output, crash logs, exit info and diagnostic memory output. A passed page capture
only means the page was reachable: inspect screenshots for layout and appearance.
Two minutes of background/foreground cycling is a short smoke test, not a
long-duration stability run. Emulator observations are not phone benchmarks.

No secrets or private account data are accepted. Real-account login/restore,
media-server requests, playback, downloads, live update UI and real-device
performance remain separate pending tests. Artifacts on this public repository
must not contain account credentials or restored private media configurations.

The `--layout-probe` mode (the manual dispatch's `layout_probe` input) samples 1/3/10
seconds after rotation at font scales 1.0 and 1.3, captures the empty Library tab and an
emulated tablet viewport, and skips the foreground/background smoke loop. Actual elapsed
capture times and pixel dimensions are saved; these sampled frames do not establish exact
blank-frame duration. A release's own smoke always runs the full loop.

Local command (disposable emulator only):

```sh
python3 scripts/android_cloud_ui.py --apk-directory /path/to/pinned-artifact --output artifacts/cloud-ui --soak-seconds 120 --source-run PACKAGE_RUN_ID
```

Reference: https://github.com/ReactiveCircus/android-emulator-runner
Reference: https://docs.github.com/en/actions/reference/runners/github-hosted-runners
