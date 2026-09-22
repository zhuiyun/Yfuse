$ErrorActionPreference = 'Stop'
Set-Location 'D:/Demo/Yfuse'
$releasePython = 'C:/Users/app-inkbird/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
& $releasePython -B (Join-Path $PSScriptRoot 'source-manifest.py') verify
if ($LASTEXITCODE -ne 0) { throw 'Source inputs changed since release verification.' }
& $releasePython -B (Join-Path $PSScriptRoot 'verify-release.py')
if ($LASTEXITCODE -ne 0) { throw 'The signed APK failed final verification.' }
$publicationNotes = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'publication/release-notes.txt')).Trim()
& (Join-Path $PSScriptRoot 'publish-verified-update.ps1') -ReleaseNotes $publicationNotes *> (Join-Path $PSScriptRoot 'publication/publish.log')
if ($LASTEXITCODE -ne 0) { throw "Publication failed: $LASTEXITCODE" }
Get-Content -LiteralPath (Join-Path $PSScriptRoot 'publication/publish.log') -Tail 30
