$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath (Split-Path $PSScriptRoot)
try {
    if (-not (Test-Path -LiteralPath '.env')) { & ./scripts/setup-local.ps1 }
    & docker compose up --build -d --wait --wait-timeout 360
    if ($LASTEXITCODE -ne 0) { throw 'Startup failed. Check Docker Desktop and docker compose logs.' }
    Write-Output 'Open http://localhost:5173. Read initial admin credentials in the ignored .env file.'
} finally { Pop-Location }
