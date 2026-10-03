# Android release workflow

Yfuse production APKs are built, signed, and uploaded by
`.github/workflows/publish-android.yml`. A version change on the default branch
publishes automatically unless the pushed commit message contains `[artifact only]`,
which builds and retains the signed APK without publishing. A manual dispatch remains
available as a fallback. Both
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
Both manifests point to the same immutable versioned filename, for example
`Yfuse-132-0.2.55.apk`; only the URL scheme differs.

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

## Optional update-manifest signing key

Update-manifest signing with Ed25519 is optional. With no public key configured, production
APK/AAB packaging, signed CI artifact jobs, and update publishing use the standard update path.
No extra Gradle opt-in is needed. The app still verifies the downloaded APK's hash, size,
package identity, and signing certificate before installation.

When a public key is configured, the app requires a valid matching manifest signature.
CI derives the embedded public key from `UPDATE_MANIFEST_SIGNING_KEY`; an optional
`yfuse.updateManifestPublicKey` property or `YFUSE_UPDATE_MANIFEST_PUBLIC_KEY` repository variable
must match it. Configured keys must be valid Ed25519. Artifact-only jobs (including branch,
repair, repackage, and TV builds) can use the public key without the private signing key.

The publish workflow supports these configurations:

| Public key | Private signing key | Published manifest |
| --- | --- | --- |
| Empty | Empty | Standard `update.json` and `update-v2.json`, with no `signature` field |
| Empty or matching | Configured | Signed manifests; CI derives and embeds the matching public key |
| Configured | Empty | Rejected as inconsistent: the new APK would require a signature the publisher cannot create |

Production APK signing and certificate checks remain required in every configuration.
Existing installed versions retain their own update policy; an older version that rejects
updates without a pinned key needs a one-time manual installation of the new signed APK.

First export the public key from the **existing update-manifest private key**. An APK signing
certificate, a calendar-feed key, or a newly generated unrelated key cannot verify existing
update manifests. With OpenSSL available in PowerShell:

```powershell
$updateKeyPath = 'D:\secure\update-manifest.pem'
$publicDerPath = Join-Path $env:TEMP ('yfuse-update-public-' + [guid]::NewGuid() + '.der')
try {
    & openssl pkey -in $updateKeyPath -pubout -outform DER -out $publicDerPath
    if ($LASTEXITCODE -ne 0) { throw 'Could not export the existing update public key' }
    $updatePublicKey = [Convert]::ToBase64String([IO.File]::ReadAllBytes($publicDerPath))
    $updatePublicKey # Public SPKI only; put it in yfuse.updateManifestPublicKey or the CI variable.
} finally {
    if (Test-Path -LiteralPath $publicDerPath) { Remove-Item -LiteralPath $publicDerPath }
}
```

Only for a first-time setup where no update-manifest signing key has ever been used, create
the pair in a private directory. This command refuses to replace an existing file; then export
the public key as above and configure future manifests to use this same private key:

```powershell
$updateKeyPath = 'D:\secure\update-manifest.pem'
if (Test-Path -LiteralPath $updateKeyPath) { throw 'Keep the existing update signing key; export its public key instead' }
& openssl genpkey -algorithm ed25519 -out $updateKeyPath
if ($LASTEXITCODE -ne 0) { throw 'Could not create the first update signing key' }
```

Equivalent first-time setup in Bash:

```bash
test ! -e update-manifest.pem || { echo 'Use the existing key'; exit 1; }
umask 077
openssl genpkey -algorithm ed25519 -out update-manifest.pem
openssl pkey -in update-manifest.pem -pubout -outform DER | base64 -w0   # optional local pin
```

Store the PEM as the repository secret `UPDATE_MANIFEST_SIGNING_KEY`; the release workflow derives
and embeds its public key automatically. You may also commit the derived public key as
`yfuse.updateManifestPublicKey` for an additional consistency check. Keep the PEM somewhere the keystore also
lives. If it is lost, existing installations cannot trust a replacement key automatically;
they need a manually installed package with the new public key and the same APK certificate.
Planned rotation must distribute the new trust anchor through a package authorized by the old
manifest key before the old private key is retired. Removing a pin from future builds does
not change the verification policy of already-installed pinned clients.

The signed payload is the manifest's `versionCode`, `versionName`, `apkUrl`, `sha256`, `size`
and `notes` joined by newlines, in that order. `UpdateManifest.signedPayload()` in the app and
`sign_manifest` in the workflow both build it; change both or neither.

Independently of the manifest, the app compares the downloaded package's signing
certificates with its own before handing it to the installer, and discards a mismatch.

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

The Caddyfile temporarily keeps `http://47.112.219.60` only so already-installed builds
can still check for updates. It explicitly returns `426` for `/api/*` and `/watch`; account
credentials and watch-room WebSockets are never forwarded over the legacy origin.
`update.json` deliberately keeps its APK URL on that unencrypted origin, while
`update-v2.json` is the HTTPS contract for all new builds. The workflow verifies both
update origins on every release. Remove the compatibility block and stop producing the old
manifest only after affected app versions have aged out.

## Publishing

### Automatic production release

The normal release path is a push to the default branch that changes
`version.properties`:

For a watch protocol change, deploy and verify the matching server first. The v6 server
keeps the authenticated v5 wire shape and advertises the supported range `5..6`, so
installed v5 clients continue to work during rollout while v4 remains rejected. Both
protocol versions require a valid Yfuse account access token before room access. The
Android and PowerShell publish paths check production `/watch/version` and refuse to
publish until it reports `protocolVersion: 6` and `minProtocolVersion: 5`.

1. Explicitly update and validate `version.properties`. The helper increments the
   current code unless `--version-code` is provided:

   ```bash
   ./gradlew :composeApp:bumpVersion --version-name 0.2.55
   # Or choose both values explicitly:
   ./gradlew :composeApp:bumpVersion --version-code 132 --version-name 0.2.55
   ```

   The task rejects non-positive, non-increasing, overflowing codes and malformed
   names. No assemble, bundle, or package task invokes it.
2. Write the in-app update text in `release-notes.txt`.
3. Review the diff, then commit both files with the feature changes and push the
   default branch.
4. GitHub Actions automatically builds, signs, uploads, and verifies the APK.

Ordinary pushes that do not change `version.properties` do not publish an APK.
The workflow still rejects a duplicate or older `VERSION_CODE`, so every release
must advance the code stored in the repository. It reads `update-v2.json` first for
this version gate. A 404 is treated as the one-time migration case and falls back to
the legacy `update.json`; other v2 errors fail the gate instead of silently using an
older source.

Release builds are intentionally side-effect free: without `-PyfuseVersionCode` and
`-PyfuseVersionName`, every build uses exactly the committed values. Explicit Gradle
properties override APK metadata for CI/manual fallback but never write the source
file. After any release build, `git diff --exit-code -- version.properties` should be
empty.

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
ZIP alignment, package name, server-side SHA-256/size, public APK SHA-256/size, and
both update manifests before reporting success. The APK is published under an
immutable `Yfuse-<versionCode>-<versionName>.apk` name. The APK and manifests are
uploaded to temporary names and checked before either canonical manifest changes.
Each `update.json`/`update-v2.json` switch is an atomic same-filesystem rename and
occurs only after the immutable APK is available.

The old canonical manifests remain in rollback files until both public manifests and
both HTTP/HTTPS APK downloads have passed verification. A failed switch or public
verification restores both manifests automatically and removes the failed immutable
APK. After success, pruning retains the active APK plus the two newest prior APKs.
During migration, the old `Yfuse-latest.apk` participates in that same three-file
retention window instead of being overwritten, so a cached legacy manifest continues
to work while immutable releases take over. This does not widen the public HTTP
policy: only the legacy update manifest and its APK remain available over HTTP.

The generated APK, `update.json`, and `update-v2.json` are also retained as a GitHub
Actions artifact for 7 days. Both manifests describe the same release metadata;
only their `apkUrl` values differ.

### Package without publishing

For automatic package-only builds, update `version.properties` and `release-notes.txt`,
then include `[artifact only]` in the commit message pushed to `master`. When merging a
pull request, keep this marker in the final merge or squash commit message; a marker
only on an earlier commit is not sufficient. The version-file path filter still applies,
so a source-only push does not request a package. A retry of an undelivered version keeps
its existing version numbers in accordance with `AGENTS.md`.

The marker selects package-only mode instead of skipping the entire job. It retains the
same production environment, exact-commit quality gate, signing certificate check, and
APK verification used by a manual package-only run. A normal version push without the
marker retains its existing publishing behavior.

Run the manual fallback above with **publish** turned off to get a production-signed APK that
nobody is offered yet. The run, named `Package Yfuse <versionName> (<versionCode>) without
publishing`, passes the same quality-gate, version, test, lint, MDK, signing, and APK checks,
then saves the APK and both manifests as its `Yfuse-<versionName>` artifact for 7 days. It
skips the watch-server check and every step that reaches the deployment server, so the update
host and installed apps are untouched, and `release.yml` creates a GitHub release only for a
run whose "Publish update atomically" step succeeded.

A missing update-manifest key only warns in such a run, as it does in a local build; a
publishing CI run still refuses it. Publishing the same version later is a separate manual
run with **publish** on.

## Reproducible dependencies and native artifacts

Module dependency lockfiles are committed. Whenever a dependency changes, refresh
all of them explicitly and review the resulting version diff:

```bash
./gradlew \
  :composeApp:dependencies \
  :mdkAndroid:dependencies \
  :watchTogetherProtocol:dependencies \
  :watchTogetherServer:dependencies \
  --write-locks
```

The Gradle 8.13 wrapper distribution is pinned with the SHA-256 published at the
[official Gradle distribution checksum URL](https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256).
Dependency repositories are limited to Google Maven and Maven Central. JitPack was
removed after resolving every committed module lock and confirming that native MDK
and libmpv artifacts come from the separately checksummed engine download script.

Full Gradle dependency-verification metadata is intentionally not generated by a
single developer platform: doing so can omit artifacts resolved only by Linux CI or
other target variants and create a false-complete policy. The committed module locks,
wrapper checksum, dependency-review gate, and pinned native checksums remain the
enforced controls until metadata is generated and reviewed across all CI variants.

`scripts/fetch-engines.sh` permits HTTPS downloads and HTTPS redirects only, downloads
to a temporary file, verifies the pinned SHA-256 in `scripts/engine-checksums.sha256`,
then replaces the local artifact. A version bump must update the URL and digest in the
same reviewed change; never infer or guess a digest. The current hashes were measured
from the exact locally cached upstream v1.0.0/v0.37.0 archives.

Quality CI rejects new ktlint violations using committed per-module baselines, runs
Android and watch-server tests plus `:watchTogetherProtocol:jvmTest`, assembles the
R8/resource-shrunk release with an explicitly non-distributable debug signature, and
checks its signature, ZIP alignment, package name, byte budget, and DEX code (below). It also checks
dependency locks and runs dependency review. CodeQL runs on changes and weekly. The
dependency submission workflow archives an SPDX SBOM; retain it with each production
release and complete the native-license checklist in
`docs/third-party-licenses/README.md`.

Production packaging also requires the release owner to acknowledge the exact MDK distribution
rights for the intended release.

For a package-only push, the owner's explicit confirmation may be recorded in
`.github/mdk-distribution-approval.json`. The workflow accepts that record only when
the version name, integer version code, and pinned MDK archive SHA-256 all match the
current source, `confirmed` is the boolean `true`, and the scope is `package-only`.
This record is an acknowledgement, not a license grant. It does not authorize publishing
or carry over to a different version or MDK artifact. Update it only after the owner
has explicitly confirmed the intended delivery. Manual inputs and the existing
`MDK_DISTRIBUTION_CONFIRMED` repository variable remain available as before.

The equivalent Gradle invocation is:

```bash
./gradlew :composeApp:assembleRelease -PconfirmMdkDistributionRights=true
```

This property is an auditable acknowledgement, not a license key and not a substitute for the
underlying agreement. It is deliberately absent from `gradle.properties`: it must be passed per
release from the entry point (`-PconfirmMdkDistributionRights=true` in the signed CI workflows,
`-ConfirmMdkDistributionRights` for `scripts/build-release-packages.ps1`, which refuses a
production-signed full build without it). Debug-signed `-PallowDebugSigning` release builds remain
non-distributable verification artifacts and do not satisfy the production gate. The same entry
points pass `-Pkotlin.incremental=false` so signed packages are recompiled from the checked-out
source; ordinary development builds keep incremental compilation.

Two production profiles are available. The default/full profile contains Exo, MPV, and MDK; the
compact profile contains Exo and MPV only. Build both signed APKs with:

```powershell
.\scripts\build-release-packages.ps1 -ConfirmMdkDistributionRights
```

The script writes versioned artifacts to `composeApp/build/outputs/distribution`. To build only the
compact profile, pass `-PyfuseIncludeMdk=false` to Gradle. MDK is then a compile-only adapter API:
its Java facade and native libraries are not packaged, it is removed from engine selection, and any
persisted MDK lock fails closed to automatic routing. The full profile remains the default so an
ordinary release command preserves the existing three-engine product.

## DEX verification

R8 can emit a method that ART's verifier refuses, for instance one that reads an object from a
register where an int belongs. ART then rejects the whole class, and it throws
`java.lang.VerifyError` the first time it is used, on every device. That can be long after
startup, so the build, the unit tests and a launch all pass. 1.0.97 (259) was packaged like this:
the player crashed as it opened.

`scripts/verify-release-dex.sh --mapping <mapping.txt> <apk>` runs every method through the
register-type rules ART applies and fails on such a method. It needs Java 11 or newer and fetches
dexlib2 and two Guava jars, pinned by SHA-256 in `scripts/dex-verify/tools.sha256`, from Maven
Central. Quality CI, TV CI, the packaging workflow and `build-release-packages.ps1` run it on every
R8 release APK, with the mapping R8 wrote for it (`<module>/build/outputs/mapping/release/mapping.txt`).

It also fails a method of the app's own code that needs more than 256 registers: classes the
mapping traces back to `com.yfuse`, or without `--mapping` only those R8 left unrenamed. A
method's parameters occupy its highest registers, so in such a method some sit above v255, out of
reach of the 8-bit register operands most instructions have, and R8 compiles it on a separate
path that copies them down to low registers. In 1.0.97 that path overwrote a copy still in use.
Split a method that fails the limit. For a large composable, the player moves the body into an extension of a class holding the
values it reads (`PlayerRuntimeSession`, `PlayerControlsInputs`): its lambdas then capture that
object once instead of each value. `--list-registers-over N` lists every method above N registers,
the app's and the libraries', to see how close the largest are.

ART's rejection names the class, method, code offset and register, for example
`[0x23EB] register v1 has type Reference: dv7 but expected Integer`. To see the instructions
involved, disassemble with baksmali 2.5.2 (`org.smali:baksmali`, plus `org.smali:util` and
`com.beust:jcommander` next to the jars above) and run:

```bash
python3 scripts/diagnostics/inspect_dex_method.py --baksmali-classpath "$CP" --apk <apk> \
  --class 'Lcom/yfuse/feature/player/PlayerRootKt;' --method 'PlayerRoot$lambda$152' \
  --offset 0x23EB --register v1 --describe 'Ldv7;'
```

It prints the rejected instruction with the type of each operand, the instructions before it,
every definition of the register that reaches it, and the fields and methods of the classes named
with `--describe`.

To see whether another R8 release compiles the same input correctly, make R8 dump its input and
replay the dump. R8 dumps only into a directory that exists, and the property must not be set for
the whole build, because AGP also runs D8 for lint:

```bash
./gradlew :composeApp:assembleRelease -PallowDebugSigning=true
mkdir -p build/r8-dump
JAVA_TOOL_OPTIONS=-Dcom.android.tools.r8.dumpinputtodirectory=$PWD/build/r8-dump \
  ./gradlew :composeApp:minifyReleaseWithR8 --rerun -PallowDebugSigning=true --no-daemon
curl -fsSLo r8.jar https://storage.googleapis.com/r8-releases/raw/<version>/r8lib.jar
python3 scripts/diagnostics/replay_r8_dump.py --dump build/r8-dump/<dump>.zip --r8 r8.jar --output-dir out
scripts/verify-release-dex.sh out/*.zip
```

The replay writes `out/base.zip` and one `out/feature-N.zip` per feature split. Replay with the
R8 release AGP used first: its output must have the APK's classes and findings, or the replay is
not faithful.

`scripts/diagnostics/art_verify_on_emulator.sh <output-dir> <apk>...` installs each APK on a
running emulator and lets ART verify every class from scratch; it fails when dex2oat rejects a
method.

## APK size

The build is already configured for a small package: R8 with resource
shrinking, `arm64-v8a` only, legacy (deflated) `jniLibs` packaging so the
download stays compact, and META-INF exclusions. What remains is dominated by
the native players rather than by anything in the app's own code.
Both quality and production publish workflows enforce a `30,000,000` byte APK
budget. Raise it only in a reviewed change that explains the measured increase.

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
small. It ships only in the full profile. The compact profile removes MDK while
retaining Exo and MPV, which materially lowers its download size without
duplicating ISO/BDMV/P7 responsibilities in Exo.

To see the real breakdown of a build rather than an estimate:

```bash
./gradlew :composeApp:assembleRelease
unzip -l composeApp/build/outputs/apk/release/*.apk | sort -k1 -nr | head -30
```

Two things that look like savings and are not: turning off
`useLegacyPackaging` makes the APK *larger* (uncompressed `.so`, in exchange
for a smaller install footprint), and the `.so` files in both engines are
already stripped, so there is nothing for `strip` to remove.

Unrelated to the APK: the former `mpvaar/` directory was a 24 MB extracted libmpv
AAR that no build file referenced. It was removed from the current Git tree on
2026-08-21; `composeApp` continues to consume `libs/libmpv-release.aar`, which
`scripts/fetch-engines.sh` downloads and verifies. The removal requires no runtime
or Gradle migration.
