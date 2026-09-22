$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Users\app-inkbird\.jdks\ms-21.0.9'
$apk = 'D:\Demo\Yfuse\composeApp\build\outputs\apk\debug\composeApp-debug.apk'
$previous = 'D:\Demo\Yfuse\artifacts\releases\merged-1.0.68-230\Yfuse-1.0.68-230-full-arm64-signed.apk'
$config = Get-Content version.properties | ConvertFrom-StringData
$aapt = 'D:\AndroidSDK\build-tools\36.0.0\aapt.exe'
$signer = 'D:\AndroidSDK\build-tools\36.0.0\apksigner.bat'
$badging = & $aapt dump badging $apk
if ($LASTEXITCODE -ne 0) { throw 'APK metadata read failed' }
$metadata = $badging | Select-Object -First 1
if ($metadata -notmatch "name='com.yfuse.softfeedback' versionCode='(\d+)' versionName='([^']+)'") { throw 'Unexpected verification package' }
$code = [int]$Matches[1]
$name = $Matches[2]
$priorBadging = & $aapt dump badging $previous
if ($LASTEXITCODE -ne 0) { throw 'Previous APK metadata read failed' }
$priorMetadata = $priorBadging | Select-Object -First 1
if ($priorMetadata -notmatch "name='com.yfuse' versionCode='(\d+)' versionName='([^']+)'") { throw 'Unexpected previous package' }
if ($code -le [int]$Matches[1] -or [version]$name -le [version]$Matches[2]) { throw 'Version did not increase' }
if ($code -ne [int]$config.VERSION_CODE -or $name -ne $config.VERSION_NAME) { throw 'Version configuration mismatch' }
if ((Get-Content release-notes.txt -TotalCount 1) -ne $name) { throw 'Release notes version mismatch' }
$signature = & $signer verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
$priorSignature = & $signer verify --print-certs $previous
if ($LASTEXITCODE -ne 0) { throw 'Previous APK signature verification failed' }
$certificate = ($signature | Select-String '^Signer #1 certificate SHA-256 digest:').Line
$priorCertificate = ($priorSignature | Select-String '^Signer #1 certificate SHA-256 digest:').Line
if (!$certificate -or $certificate -ne $priorCertificate) { throw 'Production signing certificate mismatch' }
$metadata | Set-Content audit/soft-feedback-20260917/apk-metadata.txt
$signature | Set-Content audit/soft-feedback-20260917/apk-signature.txt
Get-FileHash $apk -Algorithm SHA256 | Format-List | Out-File audit/soft-feedback-20260917/apk-sha256.txt
Write-Output "Verified internal APK: $name ($code), matching production certificate."
