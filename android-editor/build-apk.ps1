param(
    [string]$SdkPath,
    [string]$JavaPath,
    [string]$GradleCache,
    [switch]$SkipChecks
)
$ErrorActionPreference = 'Stop'
if ($SdkPath) {
    $env:ANDROID_HOME = $SdkPath
    $escapedSdkPath = $SdkPath.Replace('\', '/').Replace(':', '\:')
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'local.properties'), "sdk.dir=$escapedSdkPath`n", [Text.UTF8Encoding]::new($false))
}
if ($JavaPath) { $env:JAVA_HOME = $JavaPath }
if ($GradleCache) { $env:GRADLE_USER_HOME = $GradleCache }
Push-Location $PSScriptRoot
try {
    $tasks = @('assembleDebug')
    if (-not $SkipChecks) { $tasks += @('testDebugUnitTest', 'lintDebug') }
    & .\gradlew.bat @tasks --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Android build failed ($LASTEXITCODE)." }
    $outputDir = Join-Path $PSScriptRoot 'releases'
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
    $outputFile = Join-Path $outputDir 'STORY-GPT-Editor.apk'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination $outputFile -Force
    Write-Output "APK: $outputFile"
    Get-FileHash -LiteralPath $outputFile -Algorithm SHA256
} finally { Pop-Location }
