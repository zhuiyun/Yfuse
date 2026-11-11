# Automatic Android builds in GitHub Releases

Every push to `master` starts **Android quality gates**, including a source-built YCore,
even when `version.properties` does not change. A successful master-push quality run then
starts **Publish Android update** automatically through `workflow_run`. Each push and its
release continuation have their own concurrency groups, so a newer pending push cannot
displace an older one. There is no release runner waiting through the native/Android jobs.
Other branches and pull requests do not receive production signing or publish packages.

The automatic path builds YCore from the exact source commit in the Android quality
workflow, then tests the `ycore-senplayer` profile using that immutable native artifact.
Only after the quality workflow succeeds does the release build consume the same AAR,
sign with the existing production certificate, and install/start the signed APK on
Android 35, 36 and 37. Only then may it publish a GitHub Release.
The build, signing, version-allocation and public-upload jobs remain separate. The
production keystore is not available to Gradle or the release-upload tooling.

## Version identities

`config/automatic-release-baseline.json` records the last known delivery floor, including
the privately delivered **1.9.5 (347)**, which is newer than the anchor commit's checked-in
1.9.4 (346). Each subsequent first-parent master commit advances both numbers; an explicit
larger version in source is respected. The first automatic identity after this anchor is
therefore **1.9.6 (348)**; failed commits need not produce a delivered package. A retry of the same commit computes the same identity.

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

The reviewed SCore runtime (display name for SenPlayer) is committed under
`senplayerBridge/src/main/assets/senplayer`: one DEX, the original license configuration,
and exactly three ARM64 libraries. A clean checkout packages those existing files directly;
it does not compile SCore or download the original APK. Quality and release builds no longer
reference `SENPLAYER_SOURCE_APK_URL`. The original APK is not in the repository or release.
The preparation and final-APK checks retain the fixed original source identity and each
asset's exact size/SHA-256. Corrupt existing assets fail instead of being silently replaced.
The importer remains available to reproduce the same files from the reviewed local APK.
There is no fallback to obsolete engines, fabricated assets or a YCore-only package.
Fork pull requests can read the public runtime files but receive no production credentials.

YCore no longer needs a download URL in the automatic quality/release path. The reusable
`build-ycore-source.yml` job builds the current Android JNI/Vulkan sources and pinned
native dependency chain without signing secrets or SenPlayer inputs. It verifies the
standalone AAR, records the source commit/file hashes and the new AAR/provenance hashes,
and uploads only the explicit native artifact files. This is a new source build, not a
claim to reproduce the historical `10873c...` AAR byte for byte. The old pin remains intact
for existing local/legacy callers; only an explicit, complete source-build receipt opts
into the new path. Other auxiliary workflows which still use the historical input route
need separate migration before they can consume source-built artifacts.

Both Android quality jobs download the same immutable artifact ID. After all quality
jobs pass, `approved-native` records the exact ID, hashes, source commit and run attempt.
The release build accepts that record only from a successful master push quality run for
its exact source SHA. Every checkout, embedded build revision, version request and release
tag stays bound to that completed run, even if master advances before publication. Failed-job retries retain the selected artifact identity. Signature,
DEX, native dependency closure, 16 KiB alignment and signed-APK startup checks remain in
place. Startup checks are not a substitute for real-media playback/device regression.

The cold source job has a 240-minute timeout, not a measured duration guarantee. It still
uses the existing dependency/carrier build tooling internally, but MPV/MDK libraries and
carrier artifacts are not shipped as YCore. FFmpeg's configured GPL/version3 license and
all native notices remain applicable; rebuilding does not remove distribution obligations.

The initial automation runs failed because playback inputs were unavailable. The automatic
path now builds YCore from source and obtains SCore from the checkout. Those input changes
are not evidence of a successful signed APK delivery: the full quality, signing, device
startup and publication gates must still pass for the exact source commit.

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
