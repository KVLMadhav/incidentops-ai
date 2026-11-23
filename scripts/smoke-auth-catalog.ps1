param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$HealthBaseUrl = ''
)
$ErrorActionPreference = 'Stop'
$PSDefaultParameterValues['Invoke-RestMethod:TimeoutSec'] = 15
$base = $BaseUrl.TrimEnd('/') + '/api/v1'
$taskRoot = Split-Path $PSScriptRoot

function Get-LocalSetting([string]$Name) {
    $setting = [Environment]::GetEnvironmentVariable($Name)
    if ($setting) { return $setting }
    $line = Get-Content -LiteralPath (Join-Path $taskRoot '.env') |
        Where-Object { $_.StartsWith($Name + '=') } | Select-Object -First 1
    if (-not $line) { throw "Missing $Name. Run scripts/setup-local.ps1 first." }
    return $line.Substring($line.IndexOf('=') + 1)
}
function Send-Json([string]$Path, [object]$Payload, [string]$Token = '', [string]$Method = 'POST') {
    $headers = @{'X-Correlation-ID' = [Guid]::NewGuid().ToString()}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    Invoke-RestMethod "$base$Path" -Method $Method -Headers $headers -ContentType 'application/json' `
        -Body ($Payload | ConvertTo-Json -Depth 5)
}
function Read-Json([string]$Path, [string]$Token = '') {
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    Invoke-RestMethod "$base$Path" -Headers $headers
}
function Assert-Denied([int]$Status, [string]$Code, [scriptblock]$Action) {
    try { & $Action | Out-Null; throw "Expected HTTP $Status" }
    catch {
        if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne $Status) { throw }
        $errorBody = $_.ErrorDetails.Message | ConvertFrom-Json
        if ($errorBody.code -ne $Code -or -not $errorBody.correlationId -or -not $errorBody.message) {
            throw 'Error response does not match the safe API contract'
        }
    }
}

$healthOrigin = if ($HealthBaseUrl) { $HealthBaseUrl } else { $BaseUrl }
$health = Invoke-RestMethod ($healthOrigin.TrimEnd('/') + '/actuator/health')
if ($health.status -ne 'UP') { throw 'Application is not healthy' }
$admin = Send-Json '/auth/login' @{email=(Get-LocalSetting 'BOOTSTRAP_ADMIN_EMAIL'); password=(Get-LocalSetting 'BOOTSTRAP_ADMIN_PASSWORD')}
$suffix = [Guid]::NewGuid().ToString('N')
# Random disposable credentials remain in process memory and are never printed.
$password = [Guid]::NewGuid().ToString('N') + '!Aa1'
$email = "day1-$suffix@example.test"
$viewer = Send-Json '/auth/register' @{email=$email; displayName='Day 1 Viewer'; password=$password}
$login = Send-Json '/auth/login' @{email=$email; password=$password}
$teamA = Send-Json '/teams' @{name="Day 1 Payments $suffix"} $admin.accessToken
$teamB = Send-Json '/teams' @{name="Day 1 Logistics $suffix"} $admin.accessToken
Send-Json "/teams/$($teamA.id)/members" @{userId=$viewer.id; role='VIEWER'} $admin.accessToken | Out-Null
$serviceA = Send-Json "/teams/$($teamA.id)/services" @{name='Checkout API'; description='Day 1 service'; ownerUserId=$viewer.id; externalIdentifier='checkout'} $admin.accessToken
$serviceB = Send-Json "/teams/$($teamB.id)/services" @{name='Checkout API'; externalIdentifier='checkout'} $admin.accessToken
$read = Read-Json "/services/$($serviceA.id)" $login.accessToken
if ($read.teamId -ne $teamA.id) { throw 'Permitted read returned the wrong team' }
$page = Read-Json "/teams/$($teamA.id)/services?page=0&size=1" $login.accessToken
if ($page.items.Count -ne 1 -or $page.totalElements -ne 1) { throw 'Scoped catalog list failed' }
Assert-Denied 401 'UNAUTHORIZED' { Read-Json "/services/$($serviceA.id)" }
Assert-Denied 403 'FORBIDDEN' { Send-Json "/services/$($serviceA.id)" @{name='Forbidden'} $login.accessToken 'PUT' }
Assert-Denied 403 'FORBIDDEN' { Send-Json "/services/$($serviceA.id)/deactivate" @{} $login.accessToken }
foreach ($guessed in @($serviceB.id, [Guid]::NewGuid().ToString())) {
    Assert-Denied 404 'SERVICE_NOT_FOUND' { Read-Json "/services/$guessed" $login.accessToken }
    Assert-Denied 404 'SERVICE_NOT_FOUND' { Send-Json "/services/$guessed" @{name='Guessed'} $login.accessToken 'PUT' }
    Assert-Denied 404 'SERVICE_NOT_FOUND' { Send-Json "/services/$guessed/deactivate" @{} $login.accessToken }
}
Assert-Denied 404 'TEAM_NOT_FOUND' { Read-Json "/teams/$($teamB.id)/services" $login.accessToken }
Assert-Denied 400 'INVALID_REQUEST' { Send-Json "/teams/$($teamA.id)/services" @{name=' a '} $admin.accessToken }
Assert-Denied 409 'CONFLICT' { Send-Json "/teams/$($teamA.id)/services" @{name='checkout api'} $admin.accessToken }
$updated = Send-Json "/services/$($serviceA.id)" @{name='Checkout Gateway'; description='Updated via Day 1 smoke'} $admin.accessToken 'PUT'
if ($updated.name -ne 'Checkout Gateway') { throw 'Administrator update failed' }
$inactive = Send-Json "/services/$($serviceA.id)/deactivate" @{} $admin.accessToken
if ($inactive.active) { throw 'Administrator deactivation failed' }
$rotated = Send-Json '/auth/refresh' @{refreshToken=$login.refreshToken}
Read-Json '/users/me' $rotated.accessToken | Out-Null
Assert-Denied 401 'INVALID_REFRESH' { Send-Json '/auth/refresh' @{refreshToken=$login.refreshToken} }
Assert-Denied 401 'INVALID_REFRESH' { Send-Json '/auth/refresh' @{refreshToken=$rotated.refreshToken} }
Assert-Denied 401 'UNAUTHORIZED' { Read-Json '/users/me' $rotated.accessToken }
$fresh = Send-Json '/auth/login' @{email=$email; password=$password}
Send-Json '/auth/logout' @{refreshToken=$fresh.refreshToken} | Out-Null
Assert-Denied 401 'INVALID_REFRESH' { Send-Json '/auth/refresh' @{refreshToken=$fresh.refreshToken} }
Assert-Denied 401 'UNAUTHORIZED' { Read-Json '/users/me' $fresh.accessToken }
Send-Json '/auth/logout' @{refreshToken=$admin.refreshToken} | Out-Null
Write-Output 'PASS: health, login, catalog CRUD, team-local uniqueness, viewer denial, guessed-ID isolation, validation, refresh rotation/reuse and logout.'
Write-Output 'Demo records remain in the Day 1 database; the script prints no passwords or tokens.'
