<#
.SYNOPSIS
    Builds Morgana Engine distributables locally and publishes them as a GitHub Release via gh CLI.
.EXAMPLE
    .\release.ps1 -Tag v0.1.0
    .\release.ps1 -Tag v0.2.0-beta.1 -Prerelease
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Tag,
    [switch]$Prerelease,
    [switch]$SkipCheck
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Error "GitHub CLI (gh) is required. Install from https://cli.github.com/ and run 'gh auth login'."
    exit 1
}

$tasks = if ($SkipCheck) { @('distributables') } else { @('check', 'distributables') }
Write-Host "Building distributables ($($tasks -join ' '))..." -ForegroundColor Cyan
& .\morgana.bat @tasks
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$files = Get-ChildItem -Path 'build\distributables' -File
if ($files.Count -eq 0) {
    Write-Error "No distributable files found in build\distributables\"
    exit 1
}

$notes = @"
## Morgana Engine $Tag

### Downloads
- **``morgana-sdk-*.zip``** — Standalone cross-platform SDK (Windows, macOS, Linux). Unzip and run ``bin\morgana-client.bat`` (Windows) or ``bin/morgana-client`` (macOS/Linux). Java 17 is downloaded automatically if not installed.
- **``client-android-debug.apk``** — Android client APK.
- **``morgana-realms-client-html.zip``** — Standalone TeaVM browser client for static hosting or itch.io.
"@

$exists = $false
& gh release view $Tag *> $null
if ($LASTEXITCODE -eq 0) { $exists = $true }

$paths = $files | ForEach-Object { $_.FullName }
if ($exists) {
    Write-Host "Updating existing GitHub Release $Tag..." -ForegroundColor Cyan
    & gh release upload $Tag @paths --clobber
} else {
    Write-Host "Creating GitHub Release $Tag..." -ForegroundColor Cyan
    $ghArgs = @('release', 'create', $Tag) + $paths + @('--title', "Morgana Engine $Tag", '--notes', $notes)
    if ($Prerelease) { $ghArgs += '--prerelease' }
    & gh @ghArgs
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Release $Tag published successfully." -ForegroundColor Green
