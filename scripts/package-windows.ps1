param(
    [ValidateSet('app-image', 'exe', 'msi')]
    [string]$Type = 'app-image'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$frontendTarget = Join-Path $projectRoot 'frontend\target'
$packageRoot = Join-Path $frontendTarget 'jpackage'
$inputDir = Join-Path $packageRoot 'input'
$libraryDir = Join-Path $inputDir 'lib'
$outputDir = Join-Path $packageRoot ('output\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$dependencyDir = Join-Path $packageRoot 'dependencies'
$frontendJarName = 'landrop-frontend-0.1.0-SNAPSHOT.jar'

New-Item -ItemType Directory -Force -Path $libraryDir, $outputDir, $dependencyDir | Out-Null
Push-Location $projectRoot
try {
    & mvn -pl frontend -am '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }
    & mvn -pl frontend -am dependency:copy-dependencies '-DincludeScope=runtime' "-DoutputDirectory=$dependencyDir"
    if ($LASTEXITCODE -ne 0) { throw 'Could not collect runtime dependencies.' }
} finally {
    Pop-Location
}

Copy-Item -LiteralPath (Join-Path $frontendTarget $frontendJarName) -Destination (Join-Path $inputDir $frontendJarName) -Force
Get-ChildItem -LiteralPath $dependencyDir -Filter '*.jar' -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $libraryDir $_.Name) -Force
}

if ($Type -ne 'app-image' -and -not (Get-Command wix -ErrorAction SilentlyContinue)) {
    throw 'WiX Toolset is required for Windows exe/msi installers. Use app-image for a portable build.'
}

$packageArgs = @('--type', $Type, '--input', $inputDir, '--dest', $outputDir,
    '--name', 'LANDrop', '--main-jar', $frontendJarName,
    '--main-class', 'com.landrop.ui.DesktopLauncher', '--app-version', '0.1.0',
    '--vendor', 'LANDrop', '--add-modules', 'java.desktop,java.net.http,java.sql,java.naming,java.management,jdk.crypto.ec')
if ($Type -ne 'app-image') {
    $packageArgs += @('--win-shortcut', '--win-menu')
}
& jpackage @packageArgs
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }
Write-Host "Package created in $outputDir"
