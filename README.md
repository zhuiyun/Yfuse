# Yfuse

Yfuse is a Kotlin Multiplatform Android client with a Ktor watch-together relay.

The phone app navigates through a bottom bar of four tabs — 首页 (Home), 库 (Library),
服务器 (Servers) and 我的 (Profile) — plus a separate search key that opens the full-screen
search surface from any tab.

## Supported Android devices

The distributed APK targets Android API 36, requires Android 8.0/API 26 or newer,
and currently contains only the `arm64-v8a` ABI. It does not support 32-bit-only
devices, x86/x86_64 Android emulators, or x86 Chromebooks.

## Build

The build uses AGP 9.1.1, Kotlin 2.4.20, and Android SDK Platform 37.0. Install
`platforms;android-37.0` before building; the application's target SDK remains 36.
`:composeApp` and `:tvApp` own Android packaging, manifests, signing, and runtime
dependencies. `:phoneShared` and `:tvShared` compile their existing KMP source trees
using the Android KMP library plugin. See [the migration notes](docs/AGP9_MIGRATION_20260916.md).

Compatibility player artifacts are downloaded from pinned HTTPS release URLs and
verified against `scripts/engine-checksums.sha256` before installation:

```bash
scripts/fetch-engines.sh
./gradlew :composeApp:assembleDebug
```

The standalone YCore runtime is built and verified separately. The native-only build
packages `ycore-native.aar`, keeps the compatibility AAR compile-only, disables MDK,
and makes playback fail closed instead of falling back to a legacy engine:

```bash
scripts/build-ycore-native.sh --arch arm64
scripts/install-ycore-native.sh
./gradlew :composeApp:assembleDebug -PyfuseNativeOnlyRuntime=true
scripts/verify-ycore-native-apk.sh composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

Gradle dependency lockfiles are committed per module. When intentionally changing
dependencies, regenerate them with:

```bash
./gradlew \
  :composeApp:dependencies \
  :phoneShared:dependencies \
  :tvApp:dependencies \
  :tvShared:dependencies \
  :macrobenchmark:dependencies \
  :mdkAndroid:dependencies \
  :watchTogetherProtocol:dependencies \
  :watchTogetherServer:dependencies \
  --write-locks
```

`ktlintCheck` uses committed per-module baselines. Existing debt is tolerated, while
new violations fail CI. Baselines must only be regenerated in an explicit formatting
debt cleanup review.

Run the client and relay unit tests with:

```bash
./gradlew :phoneShared:testAndroidHostTest :tvShared:testAndroidHostTest \
  :watchTogetherProtocol:jvmTest :watchTogetherServer:test
```

## Release

Production Android releases use the GitHub Actions workflow documented in
[docs/android-release.md](docs/android-release.md). Release assembly never edits
`version.properties`; version changes are an explicit reviewed operation. Standalone
YCore promotion additionally requires physical-device coverage plus the committed
8-hour continuous and 24-hour queue-soak gates; absent evidence remains `NotMeasured`.

## Licensing and security

Original project code is all-rights-reserved; see [LICENSE](LICENSE). Third-party
native notices and release obligations are summarized in [NOTICE](NOTICE) and
[docs/third-party-licenses/README.md](docs/third-party-licenses/README.md). Vulnerability
reports should be sent privately to the repository owner rather than opened as a
public issue.
