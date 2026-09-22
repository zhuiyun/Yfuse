$ErrorActionPreference = 'Stop'
$apk = Join-Path $PSScriptRoot 'Yfuse-1.0.48-signed-arm64.apk'
if (!(Test-Path -LiteralPath $apk)) { throw 'The signed release APK has not been built.' }
$java = 'C:\Users\app-inkbird\.jdks\ms-21.0.9\bin\java.exe'
$androidTools = 'D:\AndroidSDK\build-tools\36.0.0'
& $java -jar (Join-Path $androidTools 'lib/apksigner.jar') verify --verbose --print-certs $apk |
    Set-Content -LiteralPath (Join-Path $PSScriptRoot 'signature.txt') -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
& (Join-Path $androidTools 'aapt.exe') dump badging $apk |
    Set-Content -LiteralPath (Join-Path $PSScriptRoot 'badging.txt') -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect APK metadata.' }
& (Join-Path $androidTools 'zipalign.exe') -c -P 16 -v 4 $apk |
    Set-Content -LiteralPath (Join-Path $PSScriptRoot 'alignment.txt') -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw 'APK 16 KB ZIP alignment verification failed.' }
& 'C:\Users\app-inkbird\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' `
    (Join-Path $PSScriptRoot 'verify-package.py')
if ($LASTEXITCODE -ne 0) { throw 'Package evidence verification failed.' }
