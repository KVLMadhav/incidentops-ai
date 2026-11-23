$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot
$envPath = Join-Path $taskRoot '.env'
if (Test-Path -LiteralPath $envPath) { throw '.env already exists; preserve or edit it explicitly.' }
function New-HexSecret {
    $bytes = New-Object byte[] 24
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}
$key = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($key) } finally { $rng.Dispose() }
$lines = @(
    "DB_PASSWORD=$(New-HexSecret)"
    "JWT_SECRET_BASE64=$([Convert]::ToBase64String($key))"
    'BOOTSTRAP_ADMIN_ENABLED=true'
    'BOOTSTRAP_ADMIN_EMAIL=admin@incidentops.local'
    "BOOTSTRAP_ADMIN_PASSWORD=$(New-HexSecret)"
    'CORS_ALLOWED_ORIGINS=http://localhost:5173'
)
[IO.File]::WriteAllLines($envPath, $lines)
Write-Output 'Created .env with unique local credentials. Read the admin password there; do not commit it.'
