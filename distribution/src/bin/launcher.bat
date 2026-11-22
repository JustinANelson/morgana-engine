@echo off
rem @DESCRIPTION@
rem Part of the Morgana ORPG SDK @VERSION@. Runs from the SDK folder, so relative paths (games\, worlds\, server-data\)
rem always mean the folders next to bin\. Needs Java 17 or newer: set JAVA_HOME, or put java on PATH.
rem Extra JVM options can be passed in MORGANA_JAVA_OPTS (for example -Xmx2g).
setlocal
set "MORGANA_HOME=%~dp0.."
cd /d "%MORGANA_HOME%"

if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 (
    echo Morgana needs Java 17 or newer. Install a JDK or JRE ^(for example Eclipse Temurin 17^) and set JAVA_HOME or add java to PATH. 1>&2
    exit /b 1
)

"%JAVA_EXE%" %MORGANA_JAVA_OPTS% -cp "%MORGANA_HOME%\lib\*" @MAIN@ %*
exit /b %ERRORLEVEL%
