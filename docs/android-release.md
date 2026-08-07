# Android release workflow

Yfuse production APKs are built, signed, and uploaded by
`.github/workflows/publish-android.yml`. A version change on the default branch
publishes automatically; a manual dispatch remains available as a fallback. Both
paths run in the `production` environment.

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
| `DEPLOY_KNOWN_HOSTS` | Verified `known_hosts` entry for the deployment server on SSH port 22 |

The public signing-certificate SHA-256 fingerprint is pinned directly in the
workflow and must match the currently published APK. Changing it requires an
explicit signing-key migration; it is not a secret.

The workflow defaults to the existing deployment server. These repository or
environment variables can override it:

| Variable | Default |
| --- | --- |
| `DEPLOY_HOST` | `47.112.219.60` |
| `DEPLOY_USER` | `yfuse-deploy` |
| `DEPLOY_PORT` | `22` |
| `DEPLOY_REMOTE_DIR` | `/srv/yfuse-update/yfuse` |
| `UPDATE_BASE_URL` | `https://47.112.219.60/yfuse` |
| `WATCH_BASE_URL` | `https://47.112.219.60` |

`UPDATE_BASE_URL` and `WATCH_BASE_URL` must remain HTTPS URLs; the workflow rejects
an insecure production override. SSH deliberately continues to use the origin IP so
deployment does not depend on public DNS or a future CDN; its pinned host key is a
separate trust decision from the domain's TLS certificate.

The legacy update origin is intentionally not configurable. Existing clients keep
reading `http://47.112.219.60/yfuse/update.json`, whose `apkUrl` points to the APK on
that same HTTP origin. New clients read
`https://47.112.219.60/yfuse/update-v2.json`, whose `apkUrl` is HTTPS.

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
ssh-keyscan -p 22 47.112.219.60 | ssh-keygen -lf -
ssh-keyscan -p 22 47.112.219.60
```

During the one-time move from SSH port 443 to 22, the workflow also accepts the
previous verified line beginning with `[47.112.219.60]:443`. Only when the configured
target is exactly `47.112.219.60:22`, the runner rewrites that host token to
`47.112.219.60` while preserving the key type and public-key bytes. The two ports'
host keys were compared out of band before enabling this compatibility path; the
workflow never calls `ssh-keyscan` or learns a replacement key from the live network.
Regenerate the secret in the port-22 form above when convenient, after which the
compatibility branch becomes a no-op.

Use a deployment-only SSH key for the unprivileged `yfuse-deploy` account. The
account must own the update directory, must not have sudo access, and its
authorized key should disable forwarding and interactive terminals. Do not
reuse a personal SSH key.

## One-time HTTPS deployment

The production templates are:

- `watchTogetherServer/deploy/Caddyfile`: TLS termination and reverse proxy on
  `yfuse.zhuiyun.site`.
- `watchTogetherServer/deploy/yfuse-watch.service`: combined Ktor watch/update
  backend on port 8080.

Install both templates, point the domain's A record at the origin, and expose only
22, 80, and 443 publicly. Port 8080 must be blocked from the public Internet because
the backend trusts Caddy's forwarded client address. Validate the deployment before
publishing:

The current production origin is an Alibaba Cloud mainland-China server. Complete
ICP filing (or Alibaba Cloud access filing if the domain was filed through another
provider) before publishing the DNS cutover. Otherwise Alibaba Cloud blocks domain
traffic on ports 80/443, commonly returning a filing 403 page or resetting the TLS
handshake even when Caddy already has a valid certificate.

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
curl --fail https://47.112.219.60/health
curl --fail https://47.112.219.60/watch/version
curl --fail --output /dev/null http://47.112.219.60/yfuse/update.json
# Run this after the first dual-manifest publication creates v2:
curl --fail --output /dev/null https://47.112.219.60/yfuse/update-v2.json
```

The initial HSTS policy is intentionally limited to `max-age=86400` and does not
cover subdomains. After DNS, certificate renewal, and release traffic have remained
stable, it can be raised to one year (`31536000`).

The Caddyfile temporarily proxies `http://47.112.219.60` to the same backend so
already-installed builds can still check for updates and reconnect to watch rooms.
`update.json` deliberately keeps its APK URL on that unencrypted origin, while
`update-v2.json` is the HTTPS contract for all new builds. The workflow verifies both
origins on every release. Remove the compatibility block and stop producing the old
manifest only after affected app versions have aged out; never send account
credentials over the legacy origin.

## Publishing

### Automatic production release

The normal release path is a push to the default branch that changes
`version.properties`:

1. Update `VERSION_CODE` and `VERSION_NAME` in `version.properties`.
2. Write the in-app update text in `release-notes.txt`.
3. Commit both files with the feature changes and push the default branch.
4. GitHub Actions automatically builds, signs, uploads, and verifies the APK.

Ordinary pushes that do not change `version.properties` do not publish an APK.
The workflow still rejects a duplicate or older `VERSION_CODE`, so every release
must advance the code stored in the repository. It reads `update-v2.json` first for
this version gate. A 404 is treated as the one-time migration case and falls back to
the legacy `update.json`; other v2 errors fail the gate instead of silently using an
older source.

### Manual fallback

1. Open **Actions → Publish Android update → Run workflow**.
2. Select the repository default branch.
3. Enter a new positive `version_code`, a numeric `version_name` such as
   `0.2.01`, and the release notes.
4. Review and approve the `production` deployment when required reviewers are
   available for the repository plan.
5. Wait for the final server and public-download verification.

The workflow refuses duplicate or older versions when the current update
manifest is reachable. It verifies the APK metadata, signing certificate,
server-side SHA-256, public APK SHA-256, and both update manifests before reporting
success. The APK, `update.json`, and `update-v2.json` are uploaded to temporary names
first. Only after all three files are present are they installed and atomically
renamed on the same filesystem, with both manifests activated last. Each manifest is
then checked independently for version, APK SHA-256, and its exact HTTP or HTTPS
`apkUrl`; the legacy HTTP APK download is also checked against the expected SHA-256.

The generated APK, `update.json`, and `update-v2.json` are also retained as a GitHub
Actions artifact for 30 days. Both manifests describe the same release metadata;
only their `apkUrl` values differ.

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
