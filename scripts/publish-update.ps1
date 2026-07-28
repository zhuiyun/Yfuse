param(
    [string]$Server = "admin@47.112.219.60",
    [int]$SshPort = 443,
    [string]$RemoteDir = "/srv/yfuse-update/yfuse",
    [string]$ReleaseNotes = "界面与播放体验优化"
)

$ErrorActionPreference = "Stop"
if ($RemoteDir -notmatch "^/srv/yfuse-update(?:/[A-Za-z0-9._-]+)+$") {
    throw "RemoteDir must stay inside /srv/yfuse-update"
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $projectRoot "composeApp/build/outputs/apk/release/composeApp-release.apk"
$versionFile = Join-Path $projectRoot "version.properties"
if (!(Test-Path $apk)) { throw "Release APK not found: $apk" }

$versionCode = [int]((Get-Content $versionFile | Select-String "VERSION_CODE=(\d+)").Matches[0].Groups[1].Value)
$versionName = "0.1.$versionCode"
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item $apk).Length
$stage = Join-Path $projectRoot "build/update"
New-Item -ItemType Directory -Force $stage | Out-Null
$publishedApk = Join-Path $stage "Yfuse-latest.apk"
Copy-Item $apk $publishedApk -Force

[ordered]@{
    versionCode = $versionCode
    versionName = $versionName
    apkUrl = "http://47.112.219.60/yfuse/Yfuse-latest.apk"
    sha256 = $sha
    size = $size
    notes = $ReleaseNotes
} | ConvertTo-Json | Set-Content (Join-Path $stage "update.json") -Encoding utf8NoBOM

ssh -p $SshPort $Server "sudo -n mkdir -p '$RemoteDir' && sudo -n chown `$USER '$RemoteDir'"
scp -P $SshPort $publishedApk "${Server}:/tmp/Yfuse-latest.apk.new"
scp -P $SshPort (Join-Path $stage "update.json") "${Server}:/tmp/yfuse-update.json.new"
ssh -p $SshPort $Server "mv /tmp/Yfuse-latest.apk.new '$RemoteDir/Yfuse-latest.apk.new' && chmod 644 '$RemoteDir/Yfuse-latest.apk.new' && mv -f '$RemoteDir/Yfuse-latest.apk.new' '$RemoteDir/Yfuse-latest.apk' && find '$RemoteDir' -maxdepth 1 -type f -name '*.apk' ! -name 'Yfuse-latest.apk' -delete && mv -f /tmp/yfuse-update.json.new '$RemoteDir/update.json' && chmod 644 '$RemoteDir/update.json'"

Write-Host "Published Yfuse $versionName ($versionCode)"
