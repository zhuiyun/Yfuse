# Android release workflow

Yfuse production APKs are built, signed, and uploaded by
`.github/workflows/publish-android.yml`. The workflow is manual and runs in the
`production` environment.

## One-time GitHub setup

Create an environment named `production` in the repository settings. If the
repository plan supports required reviewers, add at least one reviewer as an
additional deployment gate. On plans without that protection rule, the manual
workflow dispatch is the release gate. Add these environment secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded contents of `signing/yfuse-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Release keystore password |
| `ANDROID_KEY_ALIAS` | Release signing alias |
| `ANDROID_KEY_PASSWORD` | Release key password |
| `DEPLOY_SSH_PRIVATE_KEY` | Private key for the restricted deployment account |
| `DEPLOY_KNOWN_HOSTS` | Verified `known_hosts` entry for the deployment server on port 443 |

The public signing-certificate SHA-256 fingerprint is pinned directly in the
workflow and must match the currently published APK. Changing it requires an
explicit signing-key migration; it is not a secret.

The workflow defaults to the existing deployment server. These repository or
environment variables can override it:

| Variable | Default |
| --- | --- |
| `DEPLOY_HOST` | `47.112.219.60` |
| `DEPLOY_USER` | `yfuse-deploy` |
| `DEPLOY_PORT` | `443` |
| `DEPLOY_REMOTE_DIR` | `/srv/yfuse-update/yfuse` |
| `UPDATE_BASE_URL` | `http://47.112.219.60/yfuse` |

On Windows PowerShell, copy the keystore as Base64 without writing a temporary
text file:

```powershell
[Convert]::ToBase64String(
    [IO.File]::ReadAllBytes("signing\yfuse-release.jks")
) | Set-Clipboard
```

Obtain the certificate fingerprint from a known-good signed APK:

```powershell
apksigner verify --print-certs .\composeApp-release.apk
```

Generate the host-key entry, then compare its fingerprint with the fingerprint
shown by the server administrator before saving it as `DEPLOY_KNOWN_HOSTS`:

```powershell
ssh-keyscan -p 443 47.112.219.60 | ssh-keygen -lf -
ssh-keyscan -p 443 47.112.219.60
```

Use a deployment-only SSH key for the unprivileged `yfuse-deploy` account. The
account must own the update directory, must not have sudo access, and its
authorized key should disable forwarding and interactive terminals. Do not
reuse a personal SSH key.

## Publishing

1. Open **Actions → Publish Android update → Run workflow**.
2. Select the repository default branch.
3. Enter a new positive `version_code`, a numeric `version_name` such as
   `0.2.01`, and the release notes.
4. Review and approve the `production` deployment when required reviewers are
   available for the repository plan.
5. Wait for the final server and public-download verification.

The workflow refuses duplicate or older versions when the current update
manifest is reachable. It verifies the APK metadata, signing certificate,
server-side SHA-256, public APK SHA-256, and public update manifest before
reporting success.

The generated APK and `update.json` are also retained as a GitHub Actions
artifact for 30 days.

## APK size

The build is already configured for a small package: R8 with resource
shrinking, `arm64-v8a` only, legacy (deflated) `jniLibs` packaging so the
download stays compact, and META-INF exclusions. What remains is dominated by
the native players rather than by anything in the app's own code.

Measured for `libmpv-release.aar` (arm64-v8a), which is what the APK actually
carries after deflate:

| Library | On disk | In the APK |
| --- | --- | --- |
| `libavcodec.so` | 11.4 MB | 5.8 MB |
| `libmpv.so` | 6.2 MB | 2.6 MB |
| `libavformat.so` | 2.8 MB | 1.3 MB |
| `libc++_shared.so`, `libswscale.so`, `libavutil.so`, rest | 3.3 MB | 1.3 MB |
| **mpv total** | **23.7 MB** | **11.0 MB** |

MDK is a second, independent stack of the same kind — its own FFmpeg, linked
into `libmdk.so` — on top of ExoPlayer/media3, which is Java and comparatively
small. Three playback engines ship in every APK and a device uses one at a
time.

So the only change that materially moves the number is dropping a native
engine. Either one is worth roughly the table above; the choice is about which
formats and containers the app must still play without transcoding, not about
build configuration.

To see the real breakdown of a build rather than an estimate:

```bash
./gradlew :composeApp:assembleRelease
unzip -l composeApp/build/outputs/apk/release/*.apk | sort -k1 -nr | head -30
```

Two things that look like savings and are not: turning off
`useLegacyPackaging` makes the APK *larger* (uncompressed `.so`, in exchange
for a smaller install footprint), and the `.so` files in both engines are
already stripped, so there is nothing for `strip` to remove.

Unrelated to the APK: `mpvaar/` is 24 MB of an extracted libmpv AAR tracked in
git. No build file references it — `composeApp` consumes
`libs/libmpv-release.aar`, which `scripts/fetch-engines.sh` downloads — so it
costs every clone 24 MB and ships nothing.
