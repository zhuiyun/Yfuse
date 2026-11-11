# Automatic Android builds in GitHub Releases

Every push to `master` starts **Publish Android update**, even when `version.properties`
does not change. Each push has its own concurrency group, as do its Android quality gates.
A newer pending push therefore cannot displace an older one. Other branches and pull
requests do not receive production signing or publish packages.

The automatic path builds the `ycore-senplayer` profile, waits for that exact commit's
quality gates, signs with the existing production certificate, and installs/starts the
signed APK on Android 35, 36 and 37. Only then may it publish a GitHub Release.
The build, signing, version-allocation and public-upload jobs remain separate. The
production keystore is not available to Gradle or the release-upload tooling.

## Version identities

`config/automatic-release-baseline.json` records the last known delivery floor, including
the privately delivered **1.9.5 (347)**, which is newer than the anchor commit's checked-in
1.9.4 (346). Each subsequent first-parent master commit advances both numbers; an explicit
larger version in source is respected. The first automatic delivery after this anchor is
therefore **1.9.6 (348)**. A retry of the same commit computes the same identity.

Version names retain three one-digit components: 1.9.9 becomes 2.0.0. Exhausting 9.9.9
fails with an explicit request for a new policy rather than generating an invalid version.
The baseline must remain on the source's first-parent history. Deliberately rebasing or
rewriting release history may require an explicitly reviewed new baseline.

The request job saves the exact effective `version.properties` and `release-notes.txt`.
The build installs both files before Gradle starts, then checks neither was changed during
the build. It does not rely on Gradle version overrides. These files and their hashes are
included in the release. They are not committed back automatically, avoiding bot-trigger
loops. Release notes name the actual source commit and its subject; older source notes
are retained as history, not claimed as new work in every build.

Public delivery reserves two immutable tags, `build-<versionCode>` and
`build-version-<versionName>`, for the source SHA. Both manual server publication and
automatic GitHub publication use this gate. Existing identities belonging to another
commit are refused, not overwritten. A later source cannot publish below a known higher
public delivery. An older build that merely finishes late keeps its lower identity, and
automatic releases never change **Latest**. Unknown private deliveries must be recorded
in the baseline before enabling a conflicting source range; the workflow cannot discover
an APK that was never recorded or published.

## Public release contents

The release is created as a draft, receives only these explicit files, is downloaded and
compared byte for byte, and becomes public only after successful verification:

- The production-signed `Yfuse-<code>-<name>.apk`.
- `SHA256SUMS.txt` and `build-info.json` with source, version and certificate identity.
- The effective `version.properties` and `release-notes.txt`.

Existing public attachments are never overwritten. A failed upload leaves a draft which
an identical retry can finish. Raw SenPlayer APKs, input download URLs, keystores and other
workspace files are never selected for upload.

## Inputs required for a successful build

The `production` environment must provide the original signing secrets already used by
the repository: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
and `ANDROID_KEY_PASSWORD`. Their certificate must match the existing pinned SHA-256;
neither a new key nor a debug signature is an acceptable delivery substitute.

It must also provide authorized HTTPS locations through `YCORE_NATIVE_AAR_URL` and
`SENPLAYER_SOURCE_APK_URL`. The input-preparation step enforces the fixed hashes and exact
original asset inventory in `scripts/playback-runtime-inputs.json`. Private URLs are not
logged or published. There is no fallback to obsolete engines, fabricated assets or a
YCore-only package. Existing license checks and native input pins are unchanged.

At the time this automation was added, the latest remote build failed because these
inputs were unavailable. Configuration tests do not establish a successful APK delivery;
the first end-to-end release remains blocked until the verified inputs are available.

## Manual server deployment remains explicit

Automatic pushes publish to GitHub only. They do not contact the update server, modify
its manifests, or turn a commit's `[artifact only]` text into server permission. Use the
manual dispatch with `publish=true` for server deployment. Its requested version must
match the deterministic identity for that source; commit an intentional version jump
first instead of borrowing a future commit's identity. Manual workflows remain serialized
from request validation through deployment, avoiding out-of-order live-feed downgrades.
Manual `publish=false` requests remain artifact-only and make no public version claims.

For local configuration verification:

```sh
python3 -m unittest discover -s scripts/tests -p 'test_automatic_github_release.py' -v
python3 -m unittest discover -s scripts/tests -p 'test_publish*.py' -v
python3 -m unittest discover -s scripts/tests -p 'test_update_manifest_key.py' -v
```
