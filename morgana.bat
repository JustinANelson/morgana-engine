@echo off
rem Zero-setup launcher for Morgana Engine (Windows).
rem Automatically downloads a local Eclipse Temurin JDK 17 into .toolchain\jdk-17 if Java 17+ is not installed,
rem then runs gradlew.bat. With no arguments, launches the Desktop Client (:client-desktop:run).
setlocal EnableExtensions

set "REPO_ROOT=%~dp0"
if "%REPO_ROOT:~-1%"=="\" set "REPO_ROOT=%REPO_ROOT:~0,-1%"
cd /d "%REPO_ROOT%"

set "LOCAL_JDK=%REPO_ROOT%\.toolchain\jdk-17"

rem 1. Prefer an existing local cached JDK 17 if present
if exist "%LOCAL_JDK%\bin\java.exe" (
    set "JAVA_HOME=%LOCAL_JDK%"
    goto :run_gradle
)

rem 2. Check JAVA_HOME or PATH for Java 17+
powershell -NoProfile -Command "$j = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java -ErrorAction SilentlyContinue).Source }; if ($j) { $out = & $j -version 2>&1 | Out-String; if ($out -match '\"(\d+)' -and [int]$Matches[1] -ge 17) { exit 0 } }; exit 1"
if not errorlevel 1 goto :run_gradle

rem 3. Auto-download Eclipse Temurin JDK 17 from Adoptium into .toolchain\jdk-17
echo Java 17+ was not found. Downloading Eclipse Temurin JDK 17 into .toolchain\jdk-17 ...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop'; " ^
    "$arch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'aarch64' } else { 'x64' }; " ^
    "$url = \"https://api.adoptium.net/v3/binary/latest/17/ga/windows/$arch/jdk/hotspot/normal/eclipse\"; " ^
    "$tc = Join-Path '%REPO_ROOT%' '.toolchain'; " ^
    "New-Item -ItemType Directory -Force -Path $tc | Out-Null; " ^
    "$zip = Join-Path $tc 'temurin-jdk17.zip'; " ^
    "$tmp = Join-Path $tc 'extract-tmp'; " ^
    "if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }; " ^
    "Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing; " ^
    "Expand-Archive -Path $zip -DestinationPath $tmp -Force; " ^
    "$inner = Get-ChildItem -Directory $tmp | Select-Object -First 1; " ^
    "$dest = Join-Path $tc 'jdk-17'; " ^
    "if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }; " ^
    "Move-Item -Path $inner.FullName -Destination $dest; " ^
    "Remove-Item -Force $zip; " ^
    "Remove-Item -Recurse -Force $tmp"
if errorlevel 1 (
    echo Failed to download JDK 17 automatically. Please install Eclipse Temurin 17 from https://adoptium.net/ 1>&2
    exit /b 1
)
set "JAVA_HOME=%LOCAL_JDK%"

:run_gradle
if "%~1"=="" (
    call "%REPO_ROOT%\gradlew.bat" :client-desktop:run
) else (
    call "%REPO_ROOT%\gradlew.bat" %*
)
exit /b %ERRORLEVEL%
