$ErrorActionPreference = "Stop"

$bundleRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$appExe = Join-Path $bundleRoot "DocPilotMcp.exe"
$qdrantExe = Join-Path $bundleRoot "qdrant\bin\qdrant.exe"
$qdrantConfig = Join-Path $bundleRoot "qdrant\config\local.yaml"
$qdrantLog = Join-Path $bundleRoot "qdrant\logs\qdrant.log"

if (-not (Test-Path $appExe)) {
    throw "DocPilotMcp.exe not found in bundle root: $bundleRoot"
}

if (-not (Test-Path $qdrantExe)) {
    throw "Bundled qdrant.exe not found. Rebuild the app image with --bundle-qdrant."
}

if (-not $env:PERSONALIZATION_PROVIDER) { $env:PERSONALIZATION_PROVIDER = "qdrant" }
if (-not $env:QDRANT_URL) { $env:QDRANT_URL = "http://127.0.0.1:6333" }
if (-not $env:QDRANT_COLLECTION) { $env:QDRANT_COLLECTION = "docpilot_personalization" }
if (-not $env:EMBEDDING_PROVIDER) { $env:EMBEDDING_PROVIDER = "hashing" }
if (-not $env:PERSONALIZATION_REQUEST_TIMEOUT_MS) { $env:PERSONALIZATION_REQUEST_TIMEOUT_MS = "15000" }

$qdrantProcess = Start-Process -FilePath $qdrantExe -ArgumentList "--config-path", $qdrantConfig -PassThru -RedirectStandardOutput $qdrantLog -RedirectStandardError $qdrantLog

try {
    $healthy = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:6333/healthz" -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                $healthy = $true
                break
            }
        } catch {
        }
        Start-Sleep -Seconds 1
    }

    if (-not $healthy) {
        throw "Bundled Qdrant did not become healthy within 30 seconds."
    }

    & $appExe
} finally {
    if ($qdrantProcess -and -not $qdrantProcess.HasExited) {
        Stop-Process -Id $qdrantProcess.Id -Force
    }
}