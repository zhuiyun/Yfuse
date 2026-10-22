param(
    [Parameter(Mandatory = $true)]
    [long]$Revision,
    [ValidateRange(1, 31)]
    [int]$ValidDays = 7,
    [ValidateSet("ycore.all", "ycore.demux", "ycore.gpu", "mpv", "mdk")]
    [string[]]$Disable = @(),
    # A deploy account that may write $RemoteDir, e.g. yfuse-deploy@47.112.219.60. There is no
    # default: publishing used to go to root@ unless told otherwise.
    [Parameter(Mandatory = $true)]
    [string]$Server,
    [string]$RemoteDir = "/srv/yfuse-update/yfuse",
    # Only for a box without a deploy account yet; logging in as root is otherwise refused.
    [switch]$AllowRoot
)

$ErrorActionPreference = "Stop"
if ($Revision -le 0) { throw "Revision must be positive and greater than the currently published revision" }
if ($Server -notmatch "^[A-Za-z0-9._-]+@[A-Za-z0-9.:\[\]-]+$") {
    throw "Server must be user@host"
}
if ($Server -match "^root@" -and -not $AllowRoot) {
    throw "Refusing to publish as root; use a deploy account with write access to $RemoteDir (or pass -AllowRoot)"
}
if (
    $RemoteDir -notmatch "^/srv/yfuse-update(?:/[A-Za-z0-9._-]+)+$" -or
    $RemoteDir -match "(?:^|/)\.{1,2}(?:/|$)"
) {
    throw "RemoteDir must stay inside /srv/yfuse-update and must not contain . or .. path segments"
}

# Clients keep the highest revision they have seen and ignore anything lower, so a revision that
# does not exceed the published one would silently never apply. Read what is live and refuse.
$published = & ssh $Server "cat '$RemoteDir/playback-policy-v1.json' 2>/dev/null || true"
if ($LASTEXITCODE -ne 0) { throw "Unable to read the published playback policy" }
$publishedText = ($published -join "`n").Trim()
if ($publishedText) {
    $current = ($publishedText | ConvertFrom-Json).revision
    if ($null -eq $current) { throw "The published playback policy has no revision; fix it by hand first" }
    if ($Revision -le [long]$current) {
        throw "Revision $Revision must be greater than the published revision $current"
    }
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
