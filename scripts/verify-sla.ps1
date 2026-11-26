param([ValidateSet('All','Clock','Postgres','Kafka')][string]$Check = 'All')
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    throw 'Set JAVA_HOME to JDK 21 before running verification.'
}
if ($Check -ne 'Clock') {
    & docker version --format '{{.Server.Version}}'
    if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop with Linux containers before running infrastructure checks.' }
}
Push-Location -LiteralPath (Join-Path (Split-Path $PSScriptRoot) 'backend')
try {
    switch ($Check) {
        'Clock' { & ./mvnw.cmd -B '-Dtest=SlaOperationsIntegrationTest' test }
        'Postgres' { & ./mvnw.cmd -B -Ppostgres-it '-Dtest=SlaOperationsIntegrationTest' '-Dit.test=PostgresSlaIT' verify }
        'Kafka' { & ./mvnw.cmd -B -Ppostgres-it '-Dtest=SlaOperationsIntegrationTest' '-Dit.test=InfrastructureIT,KafkaDeliveryIT' verify }
        'All' { & ./mvnw.cmd -B -Ppostgres-it verify }
    }
    if ($LASTEXITCODE -ne 0) { throw "SLA verification failed: Maven exit $LASTEXITCODE" }
} finally { Pop-Location }
