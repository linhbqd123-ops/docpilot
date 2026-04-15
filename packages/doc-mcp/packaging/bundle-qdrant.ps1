param(
    [Parameter(Mandatory = $true)]
    [string]$AppImageDir,
    [string]$QdrantZipPath = $env:QDRANT_WINDOWS_ZIP,
    [string]$QdrantDownloadUrl = $env:QDRANT_DOWNLOAD_URL
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $AppImageDir)) {
    throw "App image directory not found: $AppImageDir"
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$templateConfig = Join-Path $scriptRoot "qdrant\config.local.yaml"
$templateLauncherPs1 = Join-Path $scriptRoot "qdrant\launch-docpilot-with-qdrant.ps1"
$templateLauncherBat = Join-Path $scriptRoot "qdrant\launch-docpilot-with-qdrant.bat"

if (-not $QdrantZipPath -and -not $QdrantDownloadUrl) {
    throw "Provide QDRANT_WINDOWS_ZIP or QDRANT_DOWNLOAD_URL to bundle a Windows Qdrant binary."
}

$downloadedZip = $null
if (-not $QdrantZipPath) {
    $downloadedZip = Join-Path ([System.IO.Path]::GetTempPath()) ("qdrant-bundle-" + [guid]::NewGuid().ToString("N") + ".zip")
    Invoke-WebRequest -Uri $QdrantDownloadUrl -OutFile $downloadedZip
    $QdrantZipPath = $downloadedZip
}

if (-not (Test-Path $QdrantZipPath)) {
    throw "Qdrant zip not found: $QdrantZipPath"
}

$extractDir = Join-Path ([System.IO.Path]::GetTempPath()) ("qdrant-extract-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $extractDir | Out-Null

try {
    Expand-Archive -LiteralPath $QdrantZipPath -DestinationPath $extractDir -Force
    $qdrantExe = Get-ChildItem -Path $extractDir -Recurse -Filter "qdrant.exe" | Select-Object -First 1
    if (-not $qdrantExe) {
        throw "Unable to locate qdrant.exe inside archive: $QdrantZipPath"
    }

    $bundleRoot = Join-Path $AppImageDir "qdrant"
    $binDir = Join-Path $bundleRoot "bin"
    $configDir = Join-Path $bundleRoot "config"
    $storageDir = Join-Path $bundleRoot "storage"
    $logDir = Join-Path $bundleRoot "logs"

    New-Item -ItemType Directory -Path $binDir -Force | Out-Null
    New-Item -ItemType Directory -Path $configDir -Force | Out-Null
    New-Item -ItemType Directory -Path $storageDir -Force | Out-Null
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null

    Copy-Item -Path (Join-Path $qdrantExe.Directory.FullName "*") -Destination $binDir -Recurse -Force
    Copy-Item -LiteralPath $templateConfig -Destination (Join-Path $configDir "local.yaml") -Force
    Copy-Item -LiteralPath $templateLauncherPs1 -Destination (Join-Path $AppImageDir "launch-docpilot-with-qdrant.ps1") -Force
    Copy-Item -LiteralPath $templateLauncherBat -Destination (Join-Path $AppImageDir "launch-docpilot-with-qdrant.bat") -Force
} finally {
    if ($downloadedZip -and (Test-Path $downloadedZip)) {
        Remove-Item -LiteralPath $downloadedZip -Force
    }
    if (Test-Path $extractDir) {
        Remove-Item -LiteralPath $extractDir -Recurse -Force
    }
}