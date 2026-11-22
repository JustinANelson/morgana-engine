@echo off
rem @DESCRIPTION@
rem Part of the Morgana Engine SDK @VERSION@. Runs from the SDK folder, so relative paths (games\, worlds\, server-data\)
rem always mean the folders next to bin\. Automatically downloads Eclipse Temurin JRE 17 into .jre\jre-17 if Java 17+ is missing.
rem Extra JVM options can be passed in MORGANA_JAVA_OPTS (for example -Xmx2g).
setlocal EnableExtensions
set "MORGANA_HOME=%~dp0.."
cd /d "%MORGANA_HOME%"

set "LOCAL_JRE=%MORGANA_HOME%\.jre\jre-17"
set "JAVA_EXE="

if exist "%LOCAL_JRE%\bin\java.exe" (
    set "JAVA_EXE=%LOCAL_JRE%\bin\java.exe"
    goto :run_app
)

powershell -NoProfile -Command "$j = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java -ErrorAction SilentlyContinue).Source }; if ($j) { $out = & $j -version 2>&1 | Out-String; if ($out -match '\"(\d+)' -and [int]$Matches[1] -ge 17) { exit 0 } }; exit 1"
if not errorlevel 1 (
    if defined JAVA_HOME (
        set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
    ) else (
        set "JAVA_EXE=java.exe"
    )
    goto :run_app
)

echo Java 17+ was not found. Downloading Eclipse Temurin JRE 17 into .jre\jre-17 ...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop'; " ^
    "$arch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'aarch64' } else { 'x64' }; " ^
    "$url = \"https://api.adoptium.net/v3/binary/latest/17/ga/windows/$arch/jre/hotspot/normal/eclipse\"; " ^
    "$jr = Join-Path '%MORGANA_HOME%' '.jre'; " ^
    "New-Item -ItemType Directory -Force -Path $jr | Out-Null; " ^
    "$zip = Join-Path $jr 'temurin-jre17.zip'; " ^
    "$tmp = Join-Path $jr 'extract-tmp'; " ^
    "if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }; " ^
    "Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing; " ^
    "Expand-Archive -Path $zip -DestinationPath $tmp -Force; " ^
    "$inner = Get-ChildItem -Directory $tmp | Select-Object -First 1; " ^
    "$dest = Join-Path $jr 'jre-17'; " ^
    "if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }; " ^
    "Move-Item -Path $inner.FullName -Destination $dest; " ^
    "Remove-Item -Force $zip; " ^
    "Remove-Item -Recurse -Force $tmp"
if errorlevel 1 (
    echo Morgana needs Java 17 or newer. Install Eclipse Temurin 17 from https://adoptium.net/ and set JAVA_HOME or add java to PATH. 1>&2
    exit /b 1
)
set "JAVA_EXE=%LOCAL_JRE%\bin\java.exe"

:run_app
"%JAVA_EXE%" %MORGANA_JAVA_OPTS% -cp "%MORGANA_HOME%\lib\*" @MAIN@ %*
exit /b %ERRORLEVEL%
