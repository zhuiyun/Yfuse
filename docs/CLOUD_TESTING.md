# Cloud UI evidence

The workflow runs the production-signed APK from a successful **Publish Android update**
run on master, using that run's exact source commit. It verifies the source version,
artifact manifest SHA-256 and production signing certificate, and refuses real devices
or existing installations. A manual run requires both the package run ID and source SHA.

`Android cloud UI evidence` runs after packaging completes. It uses Ubuntu 24.04 KVM
and Android 35/36 Google APIs x86_64 images.
ARM64 native translation must be advertised and the actual APK must install and
start; image assumptions alone never count as a pass.

Artifacts contain JSON case results, screenshots, accessibility trees, startup
output, crash logs, exit info and diagnostic memory output. A passed page capture
only means the page was reachable: inspect screenshots for layout and appearance.
Two minutes of background/foreground cycling is a short smoke test, not a
long-duration stability run. Emulator observations are not phone benchmarks.

No secrets or private account data are accepted. Real-account login/restore,
media-server requests, playback, downloads, live update UI and real-device
performance remain separate pending tests. Artifacts on this public repository
must not contain account credentials or restored private media configurations.

The follow-up `--layout-probe` mode samples 1/3/10 seconds after rotation at
font scales 1.0 and 1.3, captures the empty Library tab and an emulated tablet
viewport. It skips the already completed foreground/background smoke loop.
Actual elapsed capture times and pixel dimensions are saved; these sampled
frames do not establish exact blank-frame duration. The current workflow runs
this targeted mode to investigate observations from run `36351030665`.

Local command (disposable emulator only):

```sh
python3 scripts/android_cloud_ui.py --apk-directory /path/to/pinned-artifact --output artifacts/cloud-ui --soak-seconds 120 --source-run PACKAGE_RUN_ID
```

Reference: https://github.com/ReactiveCircus/android-emulator-runner
Reference: https://docs.github.com/en/actions/reference/runners/github-hosted-runners
