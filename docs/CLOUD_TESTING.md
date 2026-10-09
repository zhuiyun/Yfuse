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
SHA-256 and production signing certificate, and can add the layout probe below after the
full smoke.

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
emulated tablet viewport. It runs after the full smoke (including the foreground/background
loop) in the same session, once font scale, rotation and night mode are restored; the result
reads `smoke_and_layout_probe_completed` when both finished. A release's own smoke runs
without it. Actual elapsed capture times and pixel dimensions are saved; these sampled frames
do not establish exact blank-frame duration.

On a failure the script prints the failed case, the summary and the traceback, then the
tails of the crash buffer and logcat, so the cause is readable from the job log without
downloading artifacts.

Local command (disposable emulator only):

```sh
python3 scripts/android_cloud_ui.py --apk-directory /path/to/pinned-artifact --output artifacts/cloud-ui --soak-seconds 120 --source-run PACKAGE_RUN_ID
```

Reference: https://github.com/ReactiveCircus/android-emulator-runner
Reference: https://docs.github.com/en/actions/reference/runners/github-hosted-runners

# Real devices on Firebase Test Lab

`Firebase Test Lab` runs after every successful **Publish Android update** run on
master, and by hand with the same package run ID and source SHA as above. It has two
jobs, each on the same physical phone:

- **Robo crawl of the signed APK**: the exact production-signed APK from the package
  run, checked against its update manifest, is explored by Robo for 8 minutes. Robo
  taps through the signed-out UI and fails the run on a crash.
- **Instrumented tests on real devices**: the debug app and `androidInstrumentedTest`
  APKs are built from the packaged commit, and every instrumented test (motion,
  dialogs, focus, playback runtime on generated media, subtitles, network policy)
  runs under the Android Test Orchestrator, 30 minutes at most. The quality gates
  compile these tests but no other workflow runs them.

By default one phone is chosen from the Test Lab catalog: physical, arm64, Android 8.0
or newer, the best-stocked and then newest version, a Pixel on a tie, `zh_CN`, portrait.
To pin phones, set the repository variable `FIREBASE_TEST_LAB_DEVICES` (or the
`devices` input) to `model=ID,version=API` entries separated by `;`;
`gcloud firebase test android models list` shows the IDs.

The job summary gives the outcome, the phones and the Firebase console link. The
`test-lab-robo-*` and `test-lab-instrumentation-*` artifacts (7 days) hold the gcloud
log and Test Lab's raw results (logcat, video, screenshots, JUnit XML), so a run can
be read without the Firebase console. Without the secret below, a run after packaging
skips itself with a notice; a manual run fails and says what is missing.

## One-time setup

1. Create a Firebase project used only for testing. The no-cost Spark plan allows 5
   physical-device test runs a day, and each package uses 2 (Robo and instrumented
   tests). Blaze includes 30 physical-device minutes a day, then charges per device
   minute.
2. In the Google Cloud console for that project, enable the **Cloud Testing API** and
   the **Cloud Tool Results API**.
3. Create a service account and grant it **Editor** on the test-only project; the
   default results bucket requires it. For less access, grant **Firebase Test Lab
   Admin** (`roles/cloudtestservice.testAdmin`) and **Firebase Analytics Viewer**
   (`roles/firebase.analyticsViewer`), create a Cloud Storage bucket, give the account
   **Storage Object Admin** on it and set `FIREBASE_TEST_LAB_RESULTS_BUCKET`.
4. Create a JSON key for the account and save the whole file as the repository secret
   `FIREBASE_TEST_LAB_SERVICE_ACCOUNT`. Optional repository variables:
   `FIREBASE_TEST_LAB_PROJECT_ID` (default: the key's project),
   `FIREBASE_TEST_LAB_DEVICES` and `FIREBASE_TEST_LAB_RESULTS_BUCKET`.
5. Start it once from Actions → Firebase Test Lab → Run workflow with the run ID and
   commit of a successful package run.

The key never reaches the build or the test steps: it is written to a private file
only while gcloud signs in, then deleted. Rotate it if it is ever exposed.

## Limits

- Robo has no account, so the media server, library, playback and danmaku stay
  untested; they need a Robo script and a test server reachable from the internet.
- The instrumented tests exercise the debug build of the packaged commit, not the
  signed APK.
- Every run uploads the APK, MDK player SDK included, to Google; the default bucket
  keeps raw results for 90 days. Configure the secret only if that is acceptable.
- Physical phones can queue. Robo stops after 8 minutes and the instrumented tests
  after 30; an inconclusive or infrastructure result is not a pass.
- Job logs and artifacts of this public repository are public: never give these
  tests account credentials.

Reference: https://firebase.google.com/docs/test-lab/android/command-line
Reference: https://firebase.google.com/docs/test-lab/android/iam-permissions-reference
Reference: https://firebase.google.com/docs/test-lab/usage-quotas-pricing
