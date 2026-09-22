param(
    [switch]$Format,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Files
)
$ErrorActionPreference = 'Stop'
$cache = 'C:/Users/app-inkbird/.gradle/caches/modules-2/files-2.1'
$groups = @('com.pinterest.ktlint', 'com.github.ajalt.clikt', 'com.github.ajalt.mordant', 'com.github.ajalt.colormath', 'org.ec4j.core', 'org.jetbrains.intellij.deps', 'ch.qos.logback')
$jars = foreach ($group in $groups) {
    Get-ChildItem -LiteralPath (Join-Path $cache $group) -Recurse -File -Filter '*.jar' |
        Where-Object Name -NotMatch 'sources|javadoc' |
        ForEach-Object FullName
}
$dependencies = @(
    'org.jetbrains.kotlin/kotlin-compiler-embeddable/1.9.24',
    'org.jetbrains.kotlin/kotlin-stdlib/2.2.0',
    'org.jetbrains.kotlin/kotlin-reflect/2.0.21',
    'org.jetbrains.kotlin/kotlin-script-runtime/2.2.0',
    'org.jetbrains/annotations/13.0',
    'org.slf4j/slf4j-api/2.0.7',
    'net.java.dev.jna/jna/5.14.0',
    'io.github.oshai/kotlin-logging-jvm/6.0.9'
)
foreach ($dependency in $dependencies) {
    $jars += Get-ChildItem -LiteralPath (Join-Path $cache $dependency) -Recurse -File -Filter '*.jar' |
        Where-Object Name -NotMatch 'sources|javadoc' |
        ForEach-Object FullName
}
if (!$Files) { throw 'Pass explicit Kotlin file paths; broad format runs are not permitted by this helper.' }
$arguments = @()
if ($Format) { $arguments += '--format' }
$arguments += $Files
& java '-cp' ($jars -join [IO.Path]::PathSeparator) 'com.pinterest.ktlint.Main' @arguments
exit $LASTEXITCODE
