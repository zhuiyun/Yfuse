# Cloud UI evidence

This branch runs the unmodified, production-signed **Yfuse 1.0.88 (250)** APK from
Actions run `36309784954`, commit `657de554d3543e6ae53e34c55ddcc9a5bf2433a9`.
It does not verify later unbuilt app changes. The script pins the APK hash and
production certificate, and refuses real devices or existing installations.

`Android cloud UI evidence` runs on a push changing the workflow/script in the
dedicated `codex/cloud-ui-20260928` branch, or a manual dispatch after the workflow
is registered. It uses Ubuntu 24.04 KVM and Android 35/36 Google APIs x86_64 images.
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

Local command (disposable emulator only):

```sh
python3 scripts/android_cloud_ui.py --apk-directory /path/to/pinned-artifact --output artifacts/cloud-ui --soak-seconds 120
```

Reference: https://github.com/ReactiveCircus/android-emulator-runner
Reference: https://docs.github.com/en/actions/reference/runners/github-hosted-runners
