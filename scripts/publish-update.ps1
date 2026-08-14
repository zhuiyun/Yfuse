param(
    [string]$Server = "admin@47.112.219.60",
    [int]$SshPort = 22,
    [string]$RemoteDir = "/srv/yfuse-update/yfuse",
    [string]$UpdateBaseUrl = "https://47.112.219.60/yfuse",
    [string]$WatchBaseUrl = "https://47.112.219.60",
    [int]$ExpectedWatchProtocol = 6,
    [int]$ExpectedMinimumWatchProtocol = 5,
    [string]$ReleaseNotes = "Playback and interface improvements",
    [long]$ApkSizeBudgetBytes = 30000000
)

$ErrorActionPreference = "Stop"
$legacyUpdateBaseUrl = "http://47.112.219.60/yfuse"
if ($SshPort -lt 1 -or $SshPort -gt 65535) {
    throw "SshPort must be a valid TCP port"
}
if ($Server -notmatch "^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+$") {
    throw "Server must use the user@host form without SSH options"
}
if (
    $RemoteDir -notmatch "^/srv/yfuse-update(?:/[A-Za-z0-9._-]+)+$" -or
    $RemoteDir -match "(?:^|/)\.{1,2}(?:/|$)"
) {
    throw "RemoteDir must stay inside /srv/yfuse-update and must not contain . or .. path segments"
}
if ($ApkSizeBudgetBytes -le 0) {
    throw "ApkSizeBudgetBytes must be positive"
}
$updateUri = $null
if (
    -not [Uri]::TryCreate($UpdateBaseUrl, [UriKind]::Absolute, [ref]$updateUri) -or
    $updateUri.Scheme -ne "https"
) {
    throw "UpdateBaseUrl must be an absolute HTTPS URL"
}
$normalizedUpdateBaseUrl = $UpdateBaseUrl.TrimEnd("/")
$watchUri = $null
if (
    -not [Uri]::TryCreate($WatchBaseUrl, [UriKind]::Absolute, [ref]$watchUri) -or
    $watchUri.Scheme -ne "https"
) {
    throw "WatchBaseUrl must be an absolute HTTPS URL"
}
if (
    $ExpectedWatchProtocol -le 0 -or
    $ExpectedMinimumWatchProtocol -le 0 -or
    $ExpectedMinimumWatchProtocol -gt $ExpectedWatchProtocol
) {
    throw "Expected watch protocol range is invalid"
}
$normalizedWatchBaseUrl = $WatchBaseUrl.TrimEnd("/")
$projectRoot = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $projectRoot "composeApp/build/outputs/apk/release/composeApp-release.apk"
$versionFile = Join-Path $projectRoot "version.properties"
if (!(Test-Path $apk)) { throw "Release APK not found: $apk" }

$versionCode = [int]((Get-Content $versionFile | Select-String "VERSION_CODE=(\d+)").Matches[0].Groups[1].Value)
$versionName = (Get-Content $versionFile |
    Select-String "VERSION_NAME=([0-9]+\.[0-9]+\.[0-9]+)").Matches[0].Groups[1].Value
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item $apk).Length
if ($size -gt $ApkSizeBudgetBytes) {
    throw "Release APK is $size bytes; budget is $ApkSizeBudgetBytes bytes"
}
$apkFileName = "Yfuse-$versionCode-$versionName.apk"

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

$watchVersion = Invoke-RestMethod `
    -Uri "$normalizedWatchBaseUrl/watch/version" `
    -Method Get `
    -TimeoutSec 15
[int]$actualWatchProtocol = 0
[int]$actualMinimumWatchProtocol = 0
if (
    -not [int]::TryParse(
        [string]$watchVersion.protocolVersion,
        [ref]$actualWatchProtocol
    ) -or
    -not [int]::TryParse(
        [string]$watchVersion.minProtocolVersion,
        [ref]$actualMinimumWatchProtocol
    ) -or
    $actualWatchProtocol -ne $ExpectedWatchProtocol -or
    $actualMinimumWatchProtocol -ne $ExpectedMinimumWatchProtocol
) {
    throw "Watch server protocol range is $actualMinimumWatchProtocol..$actualWatchProtocol; expected $ExpectedMinimumWatchProtocol..$ExpectedWatchProtocol. Deploy the compatible server first."
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
$publishedApk = Join-Path $stage $apkFileName
Copy-Item $apk $publishedApk -Force

$legacyManifest = [ordered]@{
    versionCode = $versionCode
    versionName = $versionName
    apkUrl = "$legacyUpdateBaseUrl/$apkFileName"
    sha256 = $sha
    size = $size
    notes = $ReleaseNotes
} | ConvertTo-Json
$v2Manifest = [ordered]@{
    versionCode = $versionCode
    versionName = $versionName
    apkUrl = "$normalizedUpdateBaseUrl/$apkFileName"
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

function Invoke-RemoteBash {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Script,
        [string[]]$ArgumentList = @(),
        [Parameter(Mandatory = $true)]
        [string]$FailureMessage
    )

    $output = @($Script | & ssh -p $SshPort $Server bash -s -- @ArgumentList 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        $details = ($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
        throw "$FailureMessage (exit $exitCode)`n$details"
    }
    return $output
}

$prepareScript = @'
set -euo pipefail
remote_dir="$1"
case "$remote_dir" in
  /srv/yfuse-update/*) ;;
  *) echo "Unsafe remote directory" >&2; exit 2 ;;
esac
[[ "$remote_dir" =~ ^/srv/yfuse-update(/[A-Za-z0-9._-]+)+$ ]]
[[ ! "$remote_dir" =~ (^|/)\.\.?(/|$) ]]
sudo -n mkdir -p -- "$remote_dir"
sudo -n chown "$(id -un)" "$remote_dir"
'@
$null = Invoke-RemoteBash `
    -Script $prepareScript `
    -ArgumentList @($RemoteDir) `
    -FailureMessage "Unable to prepare the remote update directory"

$rollbackToken = "$PID-$versionCode"
$remotePrefix = "/tmp/yfuse-$rollbackToken"
$remoteApk = "$remotePrefix.apk"
$remoteLegacyManifest = "$remotePrefix-v1.json"
$remoteV2Manifest = "$remotePrefix-v2.json"
$cleanupUploadsScript = @'
set -euo pipefail
for path in "$@"; do
  [[ "$path" =~ ^/tmp/yfuse-[1-9][0-9]*-[1-9][0-9]*(\.apk|-v[12]\.json)$ ]]
  rm -f -- "$path"
done
'@

function Remove-StagedUploads {
    try {
        $null = Invoke-RemoteBash `
            -Script $cleanupUploadsScript `
            -ArgumentList @(
                $remoteApk,
                $remoteLegacyManifest,
                $remoteV2Manifest
            ) `
            -FailureMessage "Unable to clean staged upload files"
    } catch {
        Write-Warning "Staged upload cleanup failed: $_"
    }
}

$legacyManifestSha = (Get-FileHash $legacyManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
$v2ManifestSha = (Get-FileHash $v2ManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()

try {
    & scp -P $SshPort $publishedApk "${Server}:$remoteApk"
    if ($LASTEXITCODE -ne 0) { throw "Unable to upload the APK" }
    & scp -P $SshPort $legacyManifestPath "${Server}:$remoteLegacyManifest"
    if ($LASTEXITCODE -ne 0) { throw "Unable to upload update.json" }
    & scp -P $SshPort $v2ManifestPath "${Server}:$remoteV2Manifest"
    if ($LASTEXITCODE -ne 0) { throw "Unable to upload update-v2.json" }
} catch {
    $uploadError = $_
    Remove-StagedUploads
    throw $uploadError
}

$activationScript = @'
set -euo pipefail
remote_dir="$1"
apk_tmp="$2"
legacy_manifest_tmp="$3"
v2_manifest_tmp="$4"
apk_file_name="$5"
expected_sha="$6"
expected_size="$7"
legacy_manifest_sha="$8"
v2_manifest_sha="$9"
rollback_token="${10}"

apk_final="$remote_dir/$apk_file_name"
apk_next="$remote_dir/.$apk_file_name.next-$rollback_token"
legacy_manifest="$remote_dir/update.json"
v2_manifest="$remote_dir/update-v2.json"
legacy_manifest_next="$remote_dir/.update.json.next-$rollback_token"
v2_manifest_next="$remote_dir/.update-v2.json.next-$rollback_token"
backup_legacy="$remote_dir/.rollback-$rollback_token-update.json"
backup_v2="$remote_dir/.rollback-$rollback_token-update-v2.json"
had_legacy="$remote_dir/.rollback-$rollback_token-had-update.json"
had_v2="$remote_dir/.rollback-$rollback_token-had-update-v2.json"
missing_legacy="$remote_dir/.rollback-$rollback_token-missing-update.json"
missing_v2="$remote_dir/.rollback-$rollback_token-missing-update-v2.json"
created_apk="$remote_dir/.rollback-$rollback_token-created-apk"
switched=0

cleanup_staging() {
  rm -f -- \
    "$apk_tmp" "$legacy_manifest_tmp" "$v2_manifest_tmp" \
    "$apk_next" "$legacy_manifest_next" "$v2_manifest_next"
}

cleanup_rollback() {
  rm -f -- \
    "$backup_legacy" "$backup_v2" \
    "$had_legacy" "$had_v2" \
    "$missing_legacy" "$missing_v2" \
    "$created_apk"
}

restore_one() {
  local canonical="$1"
  local backup="$2"
  local had_marker="$3"
  local missing_marker="$4"
  local restore_next="$canonical.rollback-$rollback_token"
  if [[ -f "$had_marker" ]]; then
    test -s "$backup"
    install -m 0644 "$backup" "$restore_next"
    mv -fT "$restore_next" "$canonical"
  elif [[ -f "$missing_marker" ]]; then
    rm -f -- "$canonical"
  else
    echo "Missing rollback state for $canonical" >&2
    return 1
  fi
}

restore_manifests() {
  local restore_status=0
  restore_one \
    "$legacy_manifest" "$backup_legacy" "$had_legacy" "$missing_legacy" || \
    restore_status=1
  restore_one \
    "$v2_manifest" "$backup_v2" "$had_v2" "$missing_v2" || \
    restore_status=1
  return "$restore_status"
}

on_exit() {
  local status="$?"
  local restored=0
  trap - EXIT
  set +e
  if (( status != 0 && switched == 1 )); then
    if restore_manifests; then
      restored=1
    else
      echo "Automatic manifest rollback failed; preserving rollback files" >&2
    fi
  fi
  if (( status != 0 && (switched == 0 || restored == 1) )); then
    if [[ -f "$created_apk" ]]; then
      rm -f -- "$apk_final"
    fi
    cleanup_rollback
  fi
  cleanup_staging
  exit "$status"
}
trap on_exit EXIT

case "$remote_dir" in
  /srv/yfuse-update/*) ;;
  *) echo "Unsafe remote directory" >&2; exit 2 ;;
esac
[[ "$remote_dir" =~ ^/srv/yfuse-update(/[A-Za-z0-9._-]+)+$ ]]
[[ ! "$remote_dir" =~ (^|/)\.\.?(/|$) ]]
[[ "$apk_file_name" =~ ^Yfuse-[1-9][0-9]*-[0-9]+\.[0-9]+\.[0-9]+\.apk$ ]]
[[ "$expected_sha" =~ ^[0-9a-f]{64}$ ]]
[[ "$expected_size" =~ ^[1-9][0-9]*$ ]]
[[ "$legacy_manifest_sha" =~ ^[0-9a-f]{64}$ ]]
[[ "$v2_manifest_sha" =~ ^[0-9a-f]{64}$ ]]
[[ "$rollback_token" =~ ^[1-9][0-9]*-[1-9][0-9]*$ ]]

test -d "$remote_dir"
test -w "$remote_dir"
test -s "$apk_tmp"
test -s "$legacy_manifest_tmp"
test -s "$v2_manifest_tmp"
[[ "$(sha256sum "$apk_tmp" | awk '{print $1}')" == "$expected_sha" ]]
[[ "$(stat -c '%s' "$apk_tmp")" == "$expected_size" ]]
[[ "$(sha256sum "$legacy_manifest_tmp" | awk '{print $1}')" == "$legacy_manifest_sha" ]]
[[ "$(sha256sum "$v2_manifest_tmp" | awk '{print $1}')" == "$v2_manifest_sha" ]]

cleanup_rollback
install -m 0644 "$apk_tmp" "$apk_next"
[[ "$(sha256sum "$apk_next" | awk '{print $1}')" == "$expected_sha" ]]
[[ "$(stat -c '%s' "$apk_next")" == "$expected_size" ]]
if [[ -e "$apk_final" ]]; then
  [[ "$(sha256sum "$apk_final" | awk '{print $1}')" == "$expected_sha" ]] || {
    echo "Immutable APK name already exists with different content" >&2
    exit 3
  }
  [[ "$(stat -c '%s' "$apk_final")" == "$expected_size" ]]
  rm -f -- "$apk_next"
else
  : > "$created_apk"
  ln "$apk_next" "$apk_final"
  rm -f -- "$apk_next"
fi

if [[ -e "$legacy_manifest" ]]; then
  cp -p -- "$legacy_manifest" "$backup_legacy"
  : > "$had_legacy"
else
  : > "$missing_legacy"
fi
if [[ -e "$v2_manifest" ]]; then
  cp -p -- "$v2_manifest" "$backup_v2"
  : > "$had_v2"
else
  : > "$missing_v2"
fi

install -m 0644 "$legacy_manifest_tmp" "$legacy_manifest_next"
install -m 0644 "$v2_manifest_tmp" "$v2_manifest_next"
[[ "$(sha256sum "$legacy_manifest_next" | awk '{print $1}')" == "$legacy_manifest_sha" ]]
[[ "$(sha256sum "$v2_manifest_next" | awk '{print $1}')" == "$v2_manifest_sha" ]]

switched=1
mv -fT "$legacy_manifest_next" "$legacy_manifest"
mv -fT "$v2_manifest_next" "$v2_manifest"
[[ "$(sha256sum "$legacy_manifest" | awk '{print $1}')" == "$legacy_manifest_sha" ]]
[[ "$(sha256sum "$v2_manifest" | awk '{print $1}')" == "$v2_manifest_sha" ]]
'@

$rollbackScript = @'
set -euo pipefail
remote_dir="$1"
failed_apk="$2"
rollback_token="$3"
case "$remote_dir" in
  /srv/yfuse-update/*) ;;
  *) exit 2 ;;
esac
[[ "$remote_dir" =~ ^/srv/yfuse-update(/[A-Za-z0-9._-]+)+$ ]]
[[ ! "$remote_dir" =~ (^|/)\.\.?(/|$) ]]
[[ "$failed_apk" =~ ^Yfuse-[1-9][0-9]*-[0-9]+\.[0-9]+\.[0-9]+\.apk$ ]]
[[ "$rollback_token" =~ ^[1-9][0-9]*-[1-9][0-9]*$ ]]

restore_one() {
  local canonical="$1"
  local backup="$2"
  local had_marker="$3"
  local missing_marker="$4"
  local restore_next="$canonical.rollback-$rollback_token"
  if [[ -f "$had_marker" ]]; then
    test -s "$backup"
    install -m 0644 "$backup" "$restore_next"
    mv -fT "$restore_next" "$canonical"
  elif [[ -f "$missing_marker" ]]; then
    rm -f -- "$canonical"
  else
    echo "Missing rollback state for $canonical" >&2
    return 1
  fi
}

restore_status=0
restore_one \
  "$remote_dir/update.json" \
  "$remote_dir/.rollback-$rollback_token-update.json" \
  "$remote_dir/.rollback-$rollback_token-had-update.json" \
  "$remote_dir/.rollback-$rollback_token-missing-update.json" || \
  restore_status=1
restore_one \
  "$remote_dir/update-v2.json" \
  "$remote_dir/.rollback-$rollback_token-update-v2.json" \
  "$remote_dir/.rollback-$rollback_token-had-update-v2.json" \
  "$remote_dir/.rollback-$rollback_token-missing-update-v2.json" || \
  restore_status=1
(( restore_status == 0 )) || exit 4

if [[ -f "$remote_dir/.rollback-$rollback_token-created-apk" ]]; then
  rm -f -- "$remote_dir/$failed_apk"
fi
rm -f -- \
  "$remote_dir/.rollback-$rollback_token-update.json" \
  "$remote_dir/.rollback-$rollback_token-update-v2.json" \
  "$remote_dir/.rollback-$rollback_token-had-update.json" \
  "$remote_dir/.rollback-$rollback_token-had-update-v2.json" \
  "$remote_dir/.rollback-$rollback_token-missing-update.json" \
  "$remote_dir/.rollback-$rollback_token-missing-update-v2.json" \
  "$remote_dir/.rollback-$rollback_token-created-apk"
'@

$finalizeScript = @'
set -euo pipefail
remote_dir="$1"
current_apk="$2"
rollback_token="$3"
case "$remote_dir" in
  /srv/yfuse-update/*) ;;
  *) exit 2 ;;
esac
[[ "$remote_dir" =~ ^/srv/yfuse-update(/[A-Za-z0-9._-]+)+$ ]]
[[ ! "$remote_dir" =~ (^|/)\.\.?(/|$) ]]
[[ "$current_apk" =~ ^Yfuse-[1-9][0-9]*-[0-9]+\.[0-9]+\.[0-9]+\.apk$ ]]
[[ "$rollback_token" =~ ^[1-9][0-9]*-[1-9][0-9]*$ ]]
test -s "$remote_dir/$current_apk"

mapfile -t release_apks < <(
  find "$remote_dir" -maxdepth 1 -type f -name 'Yfuse-*.apk' \
    -printf '%T@|%p\n' | sort -t '|' -k1,1nr | cut -d '|' -f2-
)
older_retained=0
for apk_path in "${release_apks[@]}"; do
  apk_name="${apk_path##*/}"
  if [[ "$apk_name" != "Yfuse-latest.apk" && \
    ! "$apk_name" =~ ^Yfuse-[1-9][0-9]*-[0-9]+\.[0-9]+\.[0-9]+\.apk$ ]]; then
    continue
  fi
  [[ "$apk_name" == "$current_apk" ]] && continue
  (( older_retained += 1 ))
  if (( older_retained > 2 )); then
    rm -f -- "$apk_path"
  fi
done

rm -f -- \
  "$remote_dir/.rollback-$rollback_token-update.json" \
  "$remote_dir/.rollback-$rollback_token-update-v2.json" \
  "$remote_dir/.rollback-$rollback_token-had-update.json" \
  "$remote_dir/.rollback-$rollback_token-had-update-v2.json" \
  "$remote_dir/.rollback-$rollback_token-missing-update.json" \
  "$remote_dir/.rollback-$rollback_token-missing-update-v2.json" \
  "$remote_dir/.rollback-$rollback_token-created-apk"
'@

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
    if ([string]$Manifest.size -ne [string]$size) {
        throw "$Label has an unexpected APK size"
    }
    if ([string]$Manifest.apkUrl -ne $ExpectedApkUrl) {
        throw "$Label has an unexpected apkUrl"
    }
}

function Assert-PublishedApk {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if (!(Test-Path $Path)) {
        throw "$Label was not downloaded"
    }
    $actualSha = (Get-FileHash $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    $actualSize = (Get-Item $Path).Length
    if ($actualSha -ne $sha) {
        throw "$Label SHA-256 does not match the local release"
    }
    if ($actualSize -ne $size) {
        throw "$Label size does not match the local release"
    }
}

function Invoke-JsonDownload {
    param([Parameter(Mandatory = $true)][string]$Uri)

    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            return Invoke-RestMethod -Uri $Uri -Method Get -TimeoutSec 30
        } catch {
            if ($attempt -eq 3) { throw }
            Start-Sleep -Seconds 2
        }
    }
}

function Invoke-ApkDownload {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$OutFile
    )

    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Invoke-WebRequest -Uri $Uri -OutFile $OutFile -TimeoutSec 120 | Out-Null
            return
        } catch {
            Remove-Item -LiteralPath $OutFile -Force -ErrorAction SilentlyContinue
            if ($attempt -eq 3) { throw }
            Start-Sleep -Seconds 2
        }
    }
}

$verifyRemoteApkScript = @'
set -euo pipefail
remote_dir="$1"
apk_file_name="$2"
case "$remote_dir" in
  /srv/yfuse-update/*) ;;
  *) exit 2 ;;
esac
[[ "$remote_dir" =~ ^/srv/yfuse-update(/[A-Za-z0-9._-]+)+$ ]]
[[ ! "$remote_dir" =~ (^|/)\.\.?(/|$) ]]
[[ "$apk_file_name" =~ ^Yfuse-[1-9][0-9]*-[0-9]+\.[0-9]+\.[0-9]+\.apk$ ]]
apk_path="$remote_dir/$apk_file_name"
printf 'YFUSE_APK %s %s\n' \
  "$(sha256sum "$apk_path" | awk '{print $1}')" \
  "$(stat -c '%s' "$apk_path")"
'@

$readRemoteManifestScript = @'
set -euo pipefail
remote_dir="$1"
manifest_name="$2"
case "$remote_dir" in
  /srv/yfuse-update/*) ;;
  *) exit 2 ;;
esac
[[ "$remote_dir" =~ ^/srv/yfuse-update(/[A-Za-z0-9._-]+)+$ ]]
[[ ! "$remote_dir" =~ (^|/)\.\.?(/|$) ]]
case "$manifest_name" in
  update.json|update-v2.json) ;;
  *) exit 3 ;;
esac
cat "$remote_dir/$manifest_name"
'@

$publicHttpsApk = Join-Path $stage "Yfuse-public-https.apk"
$publicLegacyApk = Join-Path $stage "Yfuse-public-legacy.apk"
$activationComplete = $false

try {
    Assert-UpdateManifest `
        ($legacyManifest | ConvertFrom-Json) `
        "$legacyUpdateBaseUrl/$apkFileName" `
        "Local update.json"
    Assert-UpdateManifest `
        ($v2Manifest | ConvertFrom-Json) `
        "$normalizedUpdateBaseUrl/$apkFileName" `
        "Local update-v2.json"

    $null = Invoke-RemoteBash `
        -Script $activationScript `
        -ArgumentList @(
            $RemoteDir,
            $remoteApk,
            $remoteLegacyManifest,
            $remoteV2Manifest,
            $apkFileName,
            $sha,
            [string]$size,
            $legacyManifestSha,
            $v2ManifestSha,
            $rollbackToken
        ) `
        -FailureMessage "Unable to activate the staged update package"
    $activationComplete = $true

    $remoteApkOutput = Invoke-RemoteBash `
        -Script $verifyRemoteApkScript `
        -ArgumentList @($RemoteDir, $apkFileName) `
        -FailureMessage "Unable to verify the remote APK"
    $remoteApkLine = $remoteApkOutput |
        ForEach-Object { [string]$_ } |
        Where-Object { $_ -match "^YFUSE_APK [0-9a-f]{64} [1-9][0-9]*$" } |
        Select-Object -Last 1
    if ($null -eq $remoteApkLine) {
        throw "Remote APK verification returned no parseable result"
    }
    $remoteApkFields = $remoteApkLine -split " "
    if ($remoteApkFields[1] -ne $sha -or [long]$remoteApkFields[2] -ne $size) {
        throw "Remote APK does not match the local release"
    }

    $remoteLegacyJson = (
        Invoke-RemoteBash `
            -Script $readRemoteManifestScript `
            -ArgumentList @($RemoteDir, "update.json") `
            -FailureMessage "Unable to read remote update.json"
    ) -join "`n"
    $remoteV2Json = (
        Invoke-RemoteBash `
            -Script $readRemoteManifestScript `
            -ArgumentList @($RemoteDir, "update-v2.json") `
            -FailureMessage "Unable to read remote update-v2.json"
    ) -join "`n"
    Assert-UpdateManifest `
        ($remoteLegacyJson | ConvertFrom-Json) `
        "$legacyUpdateBaseUrl/$apkFileName" `
        "Remote update.json"
    Assert-UpdateManifest `
        ($remoteV2Json | ConvertFrom-Json) `
        "$normalizedUpdateBaseUrl/$apkFileName" `
        "Remote update-v2.json"

    $publicLegacyManifest = Invoke-JsonDownload "$legacyUpdateBaseUrl/update.json"
    $publicV2Manifest = Invoke-JsonDownload "$normalizedUpdateBaseUrl/update-v2.json"
    Assert-UpdateManifest `
        $publicLegacyManifest `
        "$legacyUpdateBaseUrl/$apkFileName" `
        "Public legacy update.json"
    Assert-UpdateManifest `
        $publicV2Manifest `
        "$normalizedUpdateBaseUrl/$apkFileName" `
        "Public update-v2.json"

    Invoke-ApkDownload `
        "$normalizedUpdateBaseUrl/$apkFileName" `
        $publicHttpsApk
    Assert-PublishedApk $publicHttpsApk "Public HTTPS APK"
    Invoke-ApkDownload `
        "$legacyUpdateBaseUrl/$apkFileName" `
        $publicLegacyApk
    Assert-PublishedApk $publicLegacyApk "Public legacy HTTP APK"

    $null = Invoke-RemoteBash `
        -Script $finalizeScript `
        -ArgumentList @($RemoteDir, $apkFileName, $rollbackToken) `
        -FailureMessage "Unable to finalize APK retention and rollback state"
    $activationComplete = $false
} catch {
    $publicationError = $_
    if ($activationComplete) {
        try {
            $null = Invoke-RemoteBash `
                -Script $rollbackScript `
                -ArgumentList @($RemoteDir, $apkFileName, $rollbackToken) `
                -FailureMessage "Unable to roll back the canonical manifests"
            $activationComplete = $false
            Write-Warning "Publication verification failed; canonical manifests were rolled back"
        } catch {
            throw "Publication failed: $publicationError`nManifest rollback also failed: $_"
        }
    } else {
        Remove-StagedUploads
    }
    throw $publicationError
} finally {
    Remove-Item -LiteralPath $publicHttpsApk -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $publicLegacyApk -Force -ErrorAction SilentlyContinue
}

Write-Host "Published Yfuse $versionName ($versionCode)"
Write-Host "APK: $normalizedUpdateBaseUrl/$apkFileName"
Write-Host "Legacy manifest: $legacyUpdateBaseUrl/update.json"
Write-Host "HTTPS manifest: $normalizedUpdateBaseUrl/update-v2.json"
Write-Host "Retained the current and two previous APK versions"
