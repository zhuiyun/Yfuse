$ErrorActionPreference = 'Stop'
Set-Location 'D:/Demo/Yfuse'
$releasePython = 'C:/Users/app-inkbird/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$auditDirectory = 'audit/releases/20260922-dialog-restore'
$deliveryDirectory = 'artifacts/releases/dialog-restore-1.0.79-241'
if ((Get-Content "$auditDirectory/build.log" -Raw) -notmatch 'BUILD SUCCESSFUL') {
    throw 'Release build has not completed successfully.'
}
& $releasePython -B "$auditDirectory/source-manifest.py" verify
if ($LASTEXITCODE -ne 0) { throw 'Release source verification failed.' }
New-Item -ItemType Directory -Force $deliveryDirectory | Out-Null
Copy-Item '.gradle-tmp/kernel-fixes-build/composeApp/build/outputs/apk/release/composeApp-release.apk' "$deliveryDirectory/Yfuse-1.0.79-241-full-arm64-signed.apk"
Copy-Item version.properties,release-notes.txt $deliveryDirectory
Copy-Item "$auditDirectory/source-hashes.json","$auditDirectory/test-summary.json" $deliveryDirectory
Copy-Item '.gradle-tmp/kernel-fixes-build/composeApp/build/outputs/mapping/release/mapping.txt' $deliveryDirectory
& $releasePython -B "$auditDirectory/verify-release.py"
if ($LASTEXITCODE -ne 0) { throw 'Final APK verification failed; do not deliver.' }
