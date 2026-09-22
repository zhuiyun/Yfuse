param(
    [Parameter(Mandatory = $true)]
    [long]$Revision,
    [ValidateRange(1, 31)]
    [int]$ValidDays = 7,
    [ValidateSet("ycore.all", "ycore.demux", "ycore.gpu", "mpv", "mdk")]
    [string[]]$Disable = @(),
    [string]$Server = "root@47.112.219.60",
    [string]$RemoteDir = "/srv/yfuse-update/yfuse"
)

$ErrorActionPreference = "Stop"
if ($Revision -le 0) { throw "Revision must be positive and greater than the currently published revision" }
if (
    $RemoteDir -notmatch "^/srv/yfuse-update(?:/[A-Za-z0-9._-]+)+$" -or
    $RemoteDir -match "(?:^|/)\.{1,2}(?:/|$)"
) {
    throw "RemoteDir must stay inside /srv/yfuse-update and must not contain . or .. path segments"
}

$stage = Join-Path $PSScriptRoot "../build/playback-policy"
New-Item -ItemType Directory -Force $stage | Out-Null
$policyPath = Join-Path $stage "playback-policy-v1.json"
$expiresAtEpochMs = [DateTimeOffset]::UtcNow.AddDays($ValidDays).ToUnixTimeMilliseconds()
$document = [ordered]@{
    revision = $Revision
    expiresAtEpochMs = $expiresAtEpochMs
    disabledPaths = @($Disable | Sort-Object -Unique)
} | ConvertTo-Json
[System.IO.File]::WriteAllText(
    $policyPath,
    $document,
    (New-Object System.Text.UTF8Encoding($false))
)

$remoteNext = "$RemoteDir/.playback-policy-v1.json.next-$Revision"
& scp $policyPath "${Server}:$remoteNext"
if ($LASTEXITCODE -ne 0) { throw "Unable to upload playback policy" }
& ssh $Server "install -m 0644 '$remoteNext' '$RemoteDir/playback-policy-v1.json' && rm -f '$remoteNext'"
if ($LASTEXITCODE -ne 0) { throw "Unable to publish playback policy" }

Write-Host "Published playback policy revision $Revision until $expiresAtEpochMs"
Write-Host "Disabled paths: $($Disable -join ', ')"
