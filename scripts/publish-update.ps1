param(
    [string]$Server = "admin@47.112.219.60",
    [int]$SshPort = 22,
    [string]$RemoteDir = "/srv/yfuse-update/yfuse",
    [string]$UpdateBaseUrl = "https://47.112.219.60/yfuse",
    [string]$ReleaseNotes = "Playback and interface improvements"
)

$ErrorActionPreference = "Stop"
$legacyUpdateBaseUrl = "http://47.112.219.60/yfuse"
if ($SshPort -lt 1 -or $SshPort -gt 65535) {
    throw "SshPort must be a valid TCP port"
}
if ($RemoteDir -notmatch "^/srv/yfuse-update(?:/[A-Za-z0-9._-]+)+$") {
    throw "RemoteDir must stay inside /srv/yfuse-update"
}
$updateUri = $null
if (
    -not [Uri]::TryCreate($UpdateBaseUrl, [UriKind]::Absolute, [ref]$updateUri) -or
    $updateUri.Scheme -ne "https"
) {
    throw "UpdateBaseUrl must be an absolute HTTPS URL"
}
$normalizedUpdateBaseUrl = $UpdateBaseUrl.TrimEnd("/")
$projectRoot = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $projectRoot "composeApp/build/outputs/apk/release/composeApp-release.apk"
$versionFile = Join-Path $projectRoot "version.properties"
if (!(Test-Path $apk)) { throw "Release APK not found: $apk" }

$versionCode = [int]((Get-Content $versionFile | Select-String "VERSION_CODE=(\d+)").Matches[0].Groups[1].Value)
$versionName = (Get-Content $versionFile |
    Select-String "VERSION_NAME=([0-9]+\.[0-9]+\.[0-9]+)").Matches[0].Groups[1].Value
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item $apk).Length

function Get-HttpStatusCodeFromError {
    param([System.Management.Automation.ErrorRecord]$ErrorRecord)

    if ($null -eq $ErrorRecord.Exception.Response) {
        return $null
    }
    try {
        return [int]$ErrorRecord.Exception.Response.StatusCode
    } catch {
        return $null
    }
}

function Get-CurrentPublishedManifest {
    try {
        return [pscustomobject]@{
            Manifest = Invoke-RestMethod `
                -Uri "$normalizedUpdateBaseUrl/update-v2.json" `
                -Method Get `
                -TimeoutSec 15
            Source = "$normalizedUpdateBaseUrl/update-v2.json"
        }
    } catch {
        if ((Get-HttpStatusCodeFromError $_) -ne 404) {
            throw
        }
        Write-Host "update-v2.json is not published yet; checking the legacy manifest"
    }

    try {
        return [pscustomobject]@{
            Manifest = Invoke-RestMethod `
                -Uri "$legacyUpdateBaseUrl/update.json" `
                -Method Get `
                -TimeoutSec 15
            Source = "$legacyUpdateBaseUrl/update.json"
        }
    } catch {
        if ((Get-HttpStatusCodeFromError $_) -eq 404) {
            Write-Warning "Neither update-v2.json nor the legacy update.json exists; treating this as the first release"
            return $null
        }
        throw
    }
}

$currentPublication = Get-CurrentPublishedManifest
if ($null -ne $currentPublication) {
    [long]$publishedVersionCode = 0
    if (-not [long]::TryParse(
        [string]$currentPublication.Manifest.versionCode,
        [ref]$publishedVersionCode
    )) {
        throw "Published manifest has an invalid versionCode: $($currentPublication.Source)"
    }
    if ($versionCode -le $publishedVersionCode) {
        throw "Version $versionCode is not newer than published version $publishedVersionCode from $($currentPublication.Source)"
    }
}

$stage = Join-Path $projectRoot "build/update"
New-Item -ItemType Directory -Force $stage | Out-Null
$publishedApk = Join-Path $stage "Yfuse-latest.apk"
Copy-Item $apk $publishedApk -Force

$legacyManifest = [ordered]@{
    versionCode = $versionCode
    versionName = $versionName
    apkUrl = "$legacyUpdateBaseUrl/Yfuse-latest.apk"
    sha256 = $sha
    size = $size
    notes = $ReleaseNotes
} | ConvertTo-Json
$v2Manifest = [ordered]@{
    versionCode = $versionCode
    versionName = $versionName
    apkUrl = "$normalizedUpdateBaseUrl/Yfuse-latest.apk"
    sha256 = $sha
    size = $size
    notes = $ReleaseNotes
} | ConvertTo-Json
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
$legacyManifestPath = Join-Path $stage "update.json"
$v2ManifestPath = Join-Path $stage "update-v2.json"
[System.IO.File]::WriteAllText(
    $legacyManifestPath,
    $legacyManifest,
    $utf8WithoutBom
)
[System.IO.File]::WriteAllText(
    $v2ManifestPath,
    $v2Manifest,
    $utf8WithoutBom
)

& ssh -p $SshPort $Server "sudo -n mkdir -p '$RemoteDir' && sudo -n chown `$USER '$RemoteDir'"
if ($LASTEXITCODE -ne 0) { throw "Unable to prepare the remote update directory" }

$remoteSuffix = "$PID-$versionCode"
$remoteApk = "/tmp/Yfuse-latest-$remoteSuffix.apk"
$remoteLegacyManifest = "/tmp/yfuse-update-$remoteSuffix.json"
$remoteV2Manifest = "/tmp/yfuse-update-v2-$remoteSuffix.json"
& scp -P $SshPort $publishedApk "${Server}:$remoteApk"
if ($LASTEXITCODE -ne 0) { throw "Unable to upload the APK" }
& scp -P $SshPort $legacyManifestPath "${Server}:$remoteLegacyManifest"
if ($LASTEXITCODE -ne 0) { throw "Unable to upload update.json" }
& scp -P $SshPort $v2ManifestPath "${Server}:$remoteV2Manifest"
if ($LASTEXITCODE -ne 0) { throw "Unable to upload update-v2.json" }

$remoteCommand = @(
    "set -eu"
    "trap 'rm -f $remoteApk $remoteLegacyManifest $remoteV2Manifest $RemoteDir/Yfuse-latest.apk.new $RemoteDir/update.json.new $RemoteDir/update-v2.json.new' EXIT"
    "test -s '$remoteApk'"
    "test -s '$remoteLegacyManifest'"
    "test -s '$remoteV2Manifest'"
    "install -m 0644 '$remoteApk' '$RemoteDir/Yfuse-latest.apk.new'"
    "install -m 0644 '$remoteLegacyManifest' '$RemoteDir/update.json.new'"
    "install -m 0644 '$remoteV2Manifest' '$RemoteDir/update-v2.json.new'"
    "mv -f '$RemoteDir/Yfuse-latest.apk.new' '$RemoteDir/Yfuse-latest.apk'"
    "mv -f '$RemoteDir/update.json.new' '$RemoteDir/update.json'"
    "mv -f '$RemoteDir/update-v2.json.new' '$RemoteDir/update-v2.json'"
    "find '$RemoteDir' -maxdepth 1 -type f -name '*.apk' ! -name 'Yfuse-latest.apk' -delete"
) -join "; "
& ssh -p $SshPort $Server $remoteCommand
if ($LASTEXITCODE -ne 0) { throw "Unable to activate the staged update package" }

function Assert-UpdateManifest {
    param(
        [object]$Manifest,
        [string]$ExpectedApkUrl,
        [string]$Label
    )

    if ([string]$Manifest.versionCode -ne [string]$versionCode) {
        throw "$Label has an unexpected versionCode"
    }
    if ([string]$Manifest.sha256 -ne $sha) {
        throw "$Label has an unexpected APK SHA-256"
    }
    if ([string]$Manifest.apkUrl -ne $ExpectedApkUrl) {
        throw "$Label has an unexpected apkUrl"
    }
}

$remoteShaOutput = (& ssh -p $SshPort $Server "sha256sum '$RemoteDir/Yfuse-latest.apk'") -join "`n"
if ($LASTEXITCODE -ne 0) { throw "Unable to verify the remote APK" }
$remoteSha = ($remoteShaOutput -split "\s+")[0].ToLowerInvariant()
if ($remoteSha -ne $sha) { throw "Remote APK SHA-256 does not match the local release" }

$remoteLegacyJson = (& ssh -p $SshPort $Server "cat '$RemoteDir/update.json'") -join "`n"
if ($LASTEXITCODE -ne 0) { throw "Unable to read remote update.json" }
$remoteV2Json = (& ssh -p $SshPort $Server "cat '$RemoteDir/update-v2.json'") -join "`n"
if ($LASTEXITCODE -ne 0) { throw "Unable to read remote update-v2.json" }
Assert-UpdateManifest `
    ($remoteLegacyJson | ConvertFrom-Json) `
    "$legacyUpdateBaseUrl/Yfuse-latest.apk" `
    "Remote update.json"
Assert-UpdateManifest `
    ($remoteV2Json | ConvertFrom-Json) `
    "$normalizedUpdateBaseUrl/Yfuse-latest.apk" `
    "Remote update-v2.json"

$publicLegacyManifest = Invoke-RestMethod `
    -Uri "$legacyUpdateBaseUrl/update.json" `
    -Method Get `
    -TimeoutSec 30
$publicV2Manifest = Invoke-RestMethod `
    -Uri "$normalizedUpdateBaseUrl/update-v2.json" `
    -Method Get `
    -TimeoutSec 30
Assert-UpdateManifest `
    $publicLegacyManifest `
    "$legacyUpdateBaseUrl/Yfuse-latest.apk" `
    "Public legacy update.json"
Assert-UpdateManifest `
    $publicV2Manifest `
    "$normalizedUpdateBaseUrl/Yfuse-latest.apk" `
    "Public update-v2.json"

Write-Host "Published Yfuse $versionName ($versionCode)"
Write-Host "Legacy manifest: $legacyUpdateBaseUrl/update.json"
Write-Host "HTTPS manifest: $normalizedUpdateBaseUrl/update-v2.json"
