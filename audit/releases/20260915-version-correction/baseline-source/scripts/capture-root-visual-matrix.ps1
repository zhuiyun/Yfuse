param(
    [string]$ApkPath,
    [string]$OutputDirectory = "build/reports/visual/root-matrix",
    [string]$DeviceSerial
)

$ErrorActionPreference = "Stop"
$appPackage = "com.yfuse"
$launcherActivity = "$appPackage/.MainActivity"
$outputRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\$OutputDirectory"))
[System.IO.Directory]::CreateDirectory($outputRoot) | Out-Null

function Resolve-Adb {
    $candidates = [System.Collections.Generic.List[string]]::new()
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) {
        $candidates.Add($command.Source)
    }
    foreach ($sdkRoot in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($sdkRoot) {
            $candidates.Add((Join-Path $sdkRoot "platform-tools\adb.exe"))
        }
    }
    $localProperties = Join-Path $PSScriptRoot "..\local.properties"
    if (Test-Path $localProperties) {
        $sdkLine = Get-Content $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($sdkLine) {
            $sdkRoot = $sdkLine.Substring("sdk.dir=".Length).Replace('\:', ':').Replace('\\', '\')
            $candidates.Add((Join-Path $sdkRoot "platform-tools\adb.exe"))
        }
    }
    $resolved = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
    if (-not $resolved) {
        throw "adb was not found in PATH, ANDROID_SDK_ROOT, ANDROID_HOME, or local.properties."
    }
    return $resolved
}

$adb = Resolve-Adb

$devicesOutput = & $adb devices
if ($LASTEXITCODE -ne 0) {
    throw "adb devices failed"
}
$connected =
    @(
        $devicesOutput -split "`r?`n" |
            Where-Object { $_ -match "\tdevice$" } |
            ForEach-Object { ($_ -split "\s+")[0] }
    )
if ($DeviceSerial) {
    if ($DeviceSerial -notin $connected) {
        throw "Android device '$DeviceSerial' is not ready. Ready devices: $($connected -join ', ')."
    }
} elseif ($connected.Count -eq 1) {
    $DeviceSerial = $connected[0]
} else {
    throw "Specify -DeviceSerial when the number of ready Android devices is not one; found $($connected.Count)."
}
$adbTarget = @("-s", $DeviceSerial)

function Invoke-AdbText {
    param([string[]]$Arguments)
    $result = & $adb @adbTarget @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed"
    }
    return ($result | Out-String).Trim()
}

function Save-AdbScreenshot {
    param([string]$Path)
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $adb
    $start.ArgumentList.Add("-s")
    $start.ArgumentList.Add($DeviceSerial)
    $start.ArgumentList.Add("exec-out")
    $start.ArgumentList.Add("screencap")
    $start.ArgumentList.Add("-p")
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    [void]$process.Start()
    $file = [System.IO.File]::Create($Path)
    try {
        $process.StandardOutput.BaseStream.CopyTo($file)
    } finally {
        $file.Dispose()
    }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "Screenshot failed: $($process.StandardError.ReadToEnd())"
    }
}

function Read-WindowHierarchy {
    Invoke-AdbText @("shell", "uiautomator", "dump", "/sdcard/yfuse-visual.xml") | Out-Null
    $xmlText = Invoke-AdbText @("exec-out", "cat", "/sdcard/yfuse-visual.xml")
    $hierarchy = [xml]$xmlText
    if ($null -eq $hierarchy.SelectSingleNode("//node[@package='$appPackage']")) {
        $foregroundPackages =
            @($hierarchy.SelectNodes("//node[@package]") | ForEach-Object { $_.package } | Sort-Object -Unique)
        throw "Yfuse is not in the foreground. Visible packages: $($foregroundPackages -join ', ')."
    }
    return $hierarchy
}

function Start-Yfuse {
    Invoke-AdbText @("shell", "am", "force-stop", $appPackage) | Out-Null
    $launchResult = Invoke-AdbText @("shell", "am", "start", "-W", "-n", $launcherActivity)
    if ($launchResult -notmatch "Status:\s+ok") {
        throw "Yfuse launch did not report success: $launchResult"
    }
    Start-Sleep -Seconds 3
    Read-WindowHierarchy | Out-Null
}

function Tap-SemanticLabel {
    param([string]$Label)
    $hierarchy = Read-WindowHierarchy
    $escaped = $Label.Replace("'", "&apos;")
    $node = $hierarchy.SelectSingleNode("//node[@text='$escaped' or @content-desc='$escaped']")
    if ($null -eq $node) {
        $node = $hierarchy.SelectSingleNode("//node[contains(@content-desc, '$escaped')]")
    }
    if ($null -eq $node) {
        throw "Cannot find root navigation target '$Label'. The test account may not be signed in."
    }
    $match = [regex]::Match($node.bounds, '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (-not $match.Success) {
        throw "Invalid bounds for '$Label': $($node.bounds)"
    }
    $x = ([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2
    $y = ([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2
    Invoke-AdbText @("shell", "input", "tap", [int]$x, [int]$y) | Out-Null
    Start-Sleep -Milliseconds 1200
}

function Save-VisualState {
    param(
        [string]$Variant,
        [string]$RootName
    )
    $safeName = $RootName -replace '[^a-zA-Z0-9_-]', '_'
    $folder = Join-Path $outputRoot $Variant
    [System.IO.Directory]::CreateDirectory($folder) | Out-Null
    Save-AdbScreenshot (Join-Path $folder "$safeName.png")
    $hierarchy = Read-WindowHierarchy
    $hierarchy.Save((Join-Path $folder "$safeName.xml"))
}

if ($ApkPath) {
    $resolvedApk = [System.IO.Path]::GetFullPath($ApkPath)
    if (-not [System.IO.File]::Exists($resolvedApk)) {
        throw "APK not found: $resolvedApk"
    }
    Invoke-AdbText @("install", "-r", $resolvedApk) | Out-Null
}

$originalSize = Invoke-AdbText @("shell", "wm", "size")
$originalDensity = Invoke-AdbText @("shell", "wm", "density")
$originalFontScale = Invoke-AdbText @("shell", "settings", "get", "system", "font_scale")
$originalSizeOverride = [regex]::Match($originalSize, 'Override size: (\d+x\d+)')
$originalDensityOverride = [regex]::Match($originalDensity, 'Override density: (\d+)')

$viewports = @(
    @{ Name = "phone-small"; Size = "360x640" },
    @{ Name = "phone-tall"; Size = "412x915" },
    @{ Name = "tablet"; Size = "840x1180" }
)
$fontScales = @(1.0, 1.3, 2.0)
$roots = @(
    @{ Name = "home"; Label = "首页" },
    @{ Name = "library"; Label = "库" },
    @{ Name = "servers"; Label = "服务器" },
    @{ Name = "profile"; Label = "我的" },
    @{ Name = "search"; Label = "搜索" }
)

try {
    foreach ($viewport in $viewports) {
        Invoke-AdbText @("shell", "wm", "size", $viewport.Size) | Out-Null
        Invoke-AdbText @("shell", "wm", "density", "160") | Out-Null
        foreach ($fontScale in $fontScales) {
            Invoke-AdbText @("shell", "settings", "put", "system", "font_scale", $fontScale) | Out-Null
            Start-Yfuse
            $variant = "$($viewport.Name)-font-$fontScale"
            foreach ($root in $roots) {
                Tap-SemanticLabel $root.Label
                Save-VisualState $variant $root.Name
            }
        }
    }
} finally {
    if ($originalSizeOverride.Success) {
        Invoke-AdbText @("shell", "wm", "size", $originalSizeOverride.Groups[1].Value) | Out-Null
    } else {
        Invoke-AdbText @("shell", "wm", "size", "reset") | Out-Null
    }
    if ($originalDensityOverride.Success) {
        Invoke-AdbText @("shell", "wm", "density", $originalDensityOverride.Groups[1].Value) | Out-Null
    } else {
        Invoke-AdbText @("shell", "wm", "density", "reset") | Out-Null
    }
    Invoke-AdbText @("shell", "settings", "put", "system", "font_scale", $originalFontScale) | Out-Null
    try {
        Start-Yfuse
    } catch {
        Write-Warning "Display settings were restored, but Yfuse could not be relaunched: $_"
    }
}

Write-Host "Visual matrix captured at $outputRoot"
