#!/usr/bin/env pwsh
# 刷相册 PhotoFeed —— 离线一键构建脚本
# 用法：
#   .\build.ps1 assembleRelease     构建发布包
#   .\build.ps1 assembleDebug       构建调试包
#   .\build.ps1 installRelease      构建并安装到已连接手机
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Tasks)

$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$T = Join-Path $Root "toolchain"
$Proj = Join-Path $Root "PhotoFeed"

if (-not $Tasks -or $Tasks.Count -eq 0) { $Tasks = @("assembleRelease") }

$env:JAVA_HOME = Join-Path $T "jdk-17.0.9+8"
$env:GRADLE_USER_HOME = Join-Path $T "gradle-home"
$env:ANDROID_HOME = Join-Path $T "android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $T "android-home"
Remove-Item Env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
Remove-Item Env:ANDROID_SDK_HOME -ErrorAction SilentlyContinue

$env:TMP = Join-Path $T "tmp"
$env:TEMP = $env:TMP
$env:GRADLE_OPTS = "-Dorg.gradle.daemon=false -Djava.io.tmpdir=$($env:TMP)"
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
New-Item -ItemType Directory -Force -Path $env:TMP | Out-Null
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME | Out-Null

if (-not (Test-Path (Join-Path $env:ANDROID_USER_HOME "debug.keystore"))) {
    Copy-Item (Join-Path $T "debug.keystore") (Join-Path $env:ANDROID_USER_HOME "debug.keystore") -Force
}

# 生成 local.properties，指向随包附带的 SDK（用正斜杠，避免 Windows 转义问题）
$sdkPath = ($env:ANDROID_HOME -replace '\\', '/')
if ([string]::IsNullOrWhiteSpace($sdkPath)) {
    $sdkPath = ((Join-Path $T "android-sdk") -replace '\\', '/')
}
"sdk.dir=$sdkPath" | Set-Content -Path (Join-Path $Proj "local.properties") -Encoding ascii
Write-Host "local.properties -> sdk.dir=$sdkPath"

$G = Join-Path $T "gradle-8.7\bin\gradle.bat"
Push-Location $Proj
& $G @Tasks --no-daemon --console=plain --no-watch-fs "-Dorg.gradle.vfs.watch=false"
$code = $LASTEXITCODE
Pop-Location

$apkDir = Join-Path $Proj "app\build\outputs\apk"
if ($code -eq 0 -and (Test-Path $apkDir)) {
    Get-ChildItem $apkDir -Recurse -Filter *.apk | ForEach-Object {
        "APK: $($_.FullName)  ($([math]::Round($_.Length/1MB,2)) MB)"
    }
}
exit $code
