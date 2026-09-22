$ErrorActionPreference = 'Stop'
$publicationDir = Join-Path $PSScriptRoot 'publication'
$deliveryDir = 'D:/Demo/Yfuse/artifacts/releases/kernel-fixes-1.0.74-236'
$plan = Get-Content -LiteralPath (Join-Path $publicationDir 'plan.json') -Raw | ConvertFrom-Json
$publicationLog = Get-Content -LiteralPath (Join-Path $publicationDir 'publish.log') -Raw
if (-not $publicationLog.Contains('Published Yfuse 1.0.74 (236)')) { throw 'Successful publication log missing' }
$verifiedManifests = @()
foreach ($endpoint in @(
    @{ name = 'update-v2.json'; url = 'https://47.112.219.60/yfuse/update-v2.json' },
    @{ name = 'update.json'; url = 'http://47.112.219.60/yfuse/update.json' }
)) {
    $expected = Get-Content -LiteralPath (Join-Path $publicationDir $endpoint.name) -Raw | ConvertFrom-Json
    $actual = Invoke-RestMethod -Uri $endpoint.url -TimeoutSec 30 -Headers @{ 'Cache-Control' = 'no-cache' }
    foreach ($field in @('versionName', 'versionCode', 'apkUrl', 'sha256', 'size', 'notes')) {
        if ($actual.$field -cne $expected.$field) { throw "Public $($endpoint.name) differs in $field" }
    }
    if ($actual.versionName -ne '1.0.74' -or $actual.versionCode -ne 236) { throw 'Unexpected public version' }
    $actual | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $publicationDir ('public-' + $endpoint.name)) -Encoding UTF8
    $verifiedManifests += @{ url = $endpoint.url; allReleaseFieldsMatch = $true }
}
$protocol = Invoke-RestMethod -Uri 'https://47.112.219.60/watch/version' -TimeoutSec 30
if ($protocol.protocolVersion -ne 6 -or $protocol.minProtocolVersion -ne 5) { throw 'Unexpected server protocol' }
$record = [ordered]@{
    published = $true
    verifiedAt = [DateTimeOffset]::Now.ToString('o')
    versionName = $plan.versionName
    versionCode = $plan.versionCode
    apkUrl = $plan.target
    sha256 = $plan.apkSha256
    bytes = $plan.bytes
    manifests = $verifiedManifests
    publicHttpsAndLegacyApkDownloadHashAndSizeVerifiedByPublisher = $true
    publisherLog = 'audit/releases/20260920-kernel-fixes/publication/publish.log'
    signingCertificateSha256 = '373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84'
    previousPublicVersion = '1.0.11 (173)'
    previousPublicApkSignatureMatches = $true
    serverProtocolVersion = $protocol.protocolVersion
    serverMinProtocolVersion = $protocol.minProtocolVersion
    historicalApksRetained = $true
    sourcePushed = $false
    devicePlaybackTested = $false
}
$recordJson = $record | ConvertTo-Json -Depth 10
$recordJson | Set-Content -LiteralPath (Join-Path $publicationDir 'verification.json') -Encoding UTF8
$recordJson | Set-Content -LiteralPath (Join-Path $deliveryDir 'publication.json') -Encoding UTF8
$readmePath = Join-Path $deliveryDir 'README.md'
$readme = [IO.File]::ReadAllText($readmePath)
$readme = $readme.Replace('未安装到设备、未验证真机播放或 FPS 准确性，未推送或上传发布。', '已发布到正式应用内更新渠道，未推送源代码；未安装到设备、未验证真机播放或 FPS 准确性。')
if (-not $readme.Contains('publication.json')) {
    $readme += "`n发布验证：正式更新清单已切换至 1.0.74（236），HTTPS 与旧版 HTTP 公开下载的 SHA-256、大小均与本签名包一致，上一线上版本的签名证书一致，历史安装包保留。公开 APK：https://47.112.219.60/yfuse/Yfuse-236-1.0.74.apk 。详见 publication.json；verification.json 为打包时的验证记录。`n"
}
[IO.File]::WriteAllText($readmePath, $readme, [Text.UTF8Encoding]::new($false))
$recordJson
