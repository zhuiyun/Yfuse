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
| `ANDROID_CERT_SHA256` | SHA-256 fingerprint of the expected signing certificate |
| `DEPLOY_SSH_PRIVATE_KEY` | Private key for the restricted deployment account |
| `DEPLOY_KNOWN_HOSTS` | Verified `known_hosts` entry for the deployment server on port 443 |

The workflow defaults to the existing deployment server. These repository or
environment variables can override it:

| Variable | Default |
| --- | --- |
| `DEPLOY_HOST` | `47.112.219.60` |
| `DEPLOY_USER` | `admin` |
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

Use a deployment-only SSH key with access limited to the update directory. Do
not reuse a personal SSH key.

## Publishing

1. Open **Actions → Publish Android update → Run workflow**.
2. Select the repository default branch.
3. Enter a new positive `version_code` and the release notes.
4. Review and approve the `production` deployment when required reviewers are
   available for the repository plan.
5. Wait for the final server and public-download verification.

The workflow refuses duplicate or older versions when the current update
manifest is reachable. It verifies the APK metadata, signing certificate,
server-side SHA-256, public APK SHA-256, and public update manifest before
reporting success.

The generated APK and `update.json` are also retained as a GitHub Actions
artifact for 30 days.
