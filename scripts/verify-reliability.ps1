param([ValidateSet('All','AlertRetry','KafkaReplay','RedisOutage')][string]$Check = 'All')
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    throw 'Set JAVA_HOME to JDK 21 before running verification.'
}
& docker version --format '{{.Server.Version}}'
if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop with Linux containers before running infrastructure checks.' }
$mavenArgs = @('-B','-Ppostgres-it')
switch ($Check) {
    'AlertRetry' {
        $mavenArgs += '-Dtest=IncidentLifecycleIntegrationTest#exactAlertRetriesAreEquivalentAndConflictsAreDurablyAudited'
        $mavenArgs += '-Dit.test=PostgresIncidentIT#exactAlertRetriesAreEquivalentAndConflictsAreDurablyAudited'
    }
    'KafkaReplay' {
        $mavenArgs += '-Dtest=IncidentLifecycleIntegrationTest#notificationRedeliveryCommitsOneReceiptAndOneEffect'
        $mavenArgs += '-Dit.test=InfrastructureIT#committedOutboxReachesBrokerAndDuplicateHasOneEffect'
    }
    'RedisOutage' {
        $mavenArgs += '-Dtest=IncidentLifecycleIntegrationTest#redisFailureFallsBackAndAuthorizationPrecedesCacheRead'
        $mavenArgs += '-Dit.test=InfrastructureIT#stoppedRedisPreservesIncidentDetailAndSummaryThenRecovers'
    }
    'All' { $mavenArgs += '-Dit.test=PostgresIncidentIT,InfrastructureIT,KafkaDeliveryIT' }
}
$mavenArgs += 'verify'
Push-Location -LiteralPath (Join-Path (Split-Path $PSScriptRoot) 'backend')
try {
    & ./mvnw.cmd @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Reliability verification failed: Maven exit $LASTEXITCODE" }
} finally { Pop-Location }
