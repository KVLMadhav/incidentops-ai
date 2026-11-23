param([string]$BaseUrl = 'http://localhost:8080')
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
function Send-Json([string]$Path, [object]$Payload, [string]$Token = '') {
    $headers = @{'X-Correlation-ID' = [Guid]::NewGuid().ToString()}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    Invoke-RestMethod "$base$Path" -Method Post -Headers $headers -ContentType 'application/json' `
        -Body ($Payload | ConvertTo-Json -Depth 6)
}
function Assert-Denied([int]$Status, [string]$Code, [scriptblock]$Action) {
    try { & $Action | Out-Null; throw "Expected HTTP $Status" }
    catch {
        if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne $Status) { throw }
        $errorBody = $_.ErrorDetails.Message | ConvertFrom-Json
        if ($errorBody.code -ne $Code -or -not $errorBody.correlationId) { throw 'Unexpected API error format' }
    }
}
$session = Send-Json '/auth/login' @{email=(Get-LocalSetting 'BOOTSTRAP_ADMIN_EMAIL'); password=(Get-LocalSetting 'BOOTSTRAP_ADMIN_PASSWORD')}
$headers = @{Authorization="Bearer $($session.accessToken)"}
$suffix = [Guid]::NewGuid().ToString('N')
$temporaryPassword = [Guid]::NewGuid().ToString('N') + '!Aa1'
$responder = Send-Json '/users' @{email="responder-$suffix@example.test"; displayName='Demo Responder'; password=$temporaryPassword; role='RESPONDER'} $session.accessToken
$viewer = Send-Json '/auth/register' @{email="viewer-$suffix@example.test"; displayName='Demo Viewer'; password=$temporaryPassword}
$viewerSession = Send-Json '/auth/login' @{email=$viewer.email; password=$temporaryPassword}
$team = Send-Json '/teams' @{name="Day 2 Demo $suffix"} $session.accessToken
Send-Json "/teams/$($team.id)/members" @{userId=$responder.id; role='RESPONDER'} $session.accessToken | Out-Null
Send-Json "/teams/$($team.id)/members" @{userId=$viewer.id; role='VIEWER'} $session.accessToken | Out-Null
$service = Send-Json "/teams/$($team.id)/services" @{name='Checkout API'; ownerUserId=$responder.id} $session.accessToken
$teamPath = "/teams/$($team.id)"
$alert = Send-Json "$teamPath/alerts" @{serviceId=$service.id; source='demo'; externalAlertId=$suffix; title='Checkout errors'; severity='SEV1'; occurredAt=[DateTime]::UtcNow.ToString('o'); metadata=@{region='local'}} $session.accessToken
$incident = Send-Json "$teamPath/incidents" @{serviceId=$service.id; title='Checkout unavailable'; severity='SEV1'; alertIds=@($alert.id)} $session.accessToken
$incidentPath = "$teamPath/incidents/$($incident.id)"
Assert-Denied 409 'INVALID_TRANSITION' {
    Send-Json "$incidentPath/transitions" @{version=$incident.version; status='CLOSED'} $session.accessToken
}
Assert-Denied 403 'FORBIDDEN' {
    Send-Json "$incidentPath/notes" @{body='Forbidden viewer note'} $viewerSession.accessToken
}
$assigned = Send-Json "$incidentPath/assignment" @{version=$incident.version; assigneeUserId=$responder.id; reason='On call'} $session.accessToken
Assert-Denied 409 'STALE_VERSION' {
    Send-Json "$incidentPath/assignment" @{version=$incident.version; assigneeUserId=$responder.id} $session.accessToken
}
Send-Json "$incidentPath/notes" @{body='Checking the checkout deployment'} $session.accessToken | Out-Null
$current = $assigned
foreach ($next in @('ACKNOWLEDGED','INVESTIGATING','MITIGATED','RESOLVED','REOPENED','INVESTIGATING','MITIGATED','RESOLVED','CLOSED')) {
    $current = Send-Json "$incidentPath/transitions" @{version=$current.version; status=$next; reason='Verified demo step'} $session.accessToken
    if ($current.status -ne $next) { throw "Transition to $next failed" }
}
$timeline = Invoke-RestMethod "$base$incidentPath/timeline" -Headers $headers
$audit = Invoke-RestMethod "$base$incidentPath/audit" -Headers $headers
if (@($timeline | Where-Object { $_.type -eq 'STATUS' }).Count -ne 10) { throw 'Expected complete status history' }
if (@($timeline | Where-Object { $_.type -eq 'ALERT_LINK' }).Count -ne 1) { throw 'Expected original alert link' }
if (@($audit).Count -lt 10) { throw 'Expected lifecycle audit entries' }
Send-Json '/auth/logout' @{refreshToken=$viewerSession.refreshToken} | Out-Null
Send-Json '/auth/logout' @{refreshToken=$session.refreshToken} | Out-Null
Write-Output 'PASS: alert, linked incident, assignment, note, full lifecycle, timeline, audit, viewer denial, invalid transition and stale version.'
Write-Output "Demo incident: $($incident.incidentNumber). Records remain available; no credentials or tokens were printed."
