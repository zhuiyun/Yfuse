# Yfuse

Yfuse is a Kotlin Multiplatform Android client with a Ktor watch-together relay.

## Supported Android devices

The distributed APK targets Android API 36, requires Android 8.0/API 26 or newer,
and currently contains only the `arm64-v8a` ABI. It does not support 32-bit-only
devices, x86/x86_64 Android emulators, or x86 Chromebooks. Predictive-back animation
is intentionally opted out by product decision.

## Build

Native player artifacts are downloaded from pinned HTTPS release URLs and verified
against `scripts/engine-checksums.sha256` before installation:

```bash
scripts/fetch-engines.sh
./gradlew :composeApp:assembleDebug
```

Gradle dependency lockfiles are committed per module. When intentionally changing
dependencies, regenerate them with:

```bash
./gradlew \
  :composeApp:dependencies \
  :mdkAndroid:dependencies \
  :watchTogetherProtocol:dependencies \
  :watchTogetherServer:dependencies \
  --write-locks
```

`ktlintCheck` uses committed per-module baselines. Existing debt is tolerated, while
new violations fail CI. Baselines must only be regenerated in an explicit formatting
debt cleanup review.

## Release

Production Android releases use the GitHub Actions workflow documented in
[docs/android-release.md](docs/android-release.md). Release assembly never edits
`version.properties`; version changes are an explicit reviewed operation.

## Licensing and security

Original project code is all-rights-reserved; see [LICENSE](LICENSE). Third-party
native notices and release obligations are summarized in [NOTICE](NOTICE) and
[docs/third-party-licenses/README.md](docs/third-party-licenses/README.md). Vulnerability
reports should be sent privately to the repository owner rather than opened as a
public issue.
