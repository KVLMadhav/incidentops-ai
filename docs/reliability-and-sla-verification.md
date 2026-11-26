# Day 3–4 publishing verification

Prepared on 2026-11-26 (Asia/Calcutta) at `D:\ChatGPT\GitHub\incidentops-ai`. The existing Days 1–2 Git history and origin are preserved. Changes remain uncommitted; no push was attempted.

## Executed checks

| Check | Result |
|---|---|
| Java 21 backend package | PASS: 74 tests, zero failures/errors/skips; BUILD SUCCESS |
| Existing V4 database upgrade | PASS: one additional `PublishingMigrationTest`; existing incident/version/history/outbox retained; historical deadline/completion backfill; repeated migration is a no-op |
| Embedded real Kafka | PASS: `KafkaDeliveryIT`; broker publication, duplicate delivery, poison-record retry and DLT recovery |
| Frontend auth client | PASS: 7 tests |
| React production bundle | PASS: Vite build, 26 modules, 245.51 kB JS before gzip |
| Compose configuration | PASS: `docker compose --profile observability config --quiet` with placeholder environment values |
| PowerShell helper syntax | PASS: all scripts parsed |
| V1–V4 migration compatibility | PASS: no Git changes to the four existing migration files |
| Full PostgreSQL/Redis Testcontainers suites | NOT TESTED: Docker Desktop Linux engine pipe unavailable |
| Live Compose startup, smoke scripts and Grafana/PromQL | NOT TESTED: Docker engine unavailable |
| Browser interaction/visual inspection | NOT TESTED; frontend build and existing client tests passed |

There are **75 ordinary tests plus one Kafka integration test** in the executed results. The initial package ran all 74 existing ordinary tests. The second command ran only the newly added migration test plus the Kafka integration test; the first suites were not redundantly rerun. H2 compatibility tests and an embedded broker do not establish PostgreSQL locking or actual Redis recovery. Those checks remain in CI and the Docker verification helpers.

## Commands

From `backend`, using JDK 21:

```powershell
./mvnw.cmd -B package
./mvnw.cmd -B -Ppostgres-it '-Dtest=PublishingMigrationTest' '-Dit.test=KafkaDeliveryIT' verify
```

From `frontend`:

```powershell
npm test
npm run build
```

Verification logs are outside the repository in `D:\Codex-temp\incidentops-days34`. Generated dependencies/build outputs, private environment files and logs are excluded from publishing and the source archive. No external email or Slack integration is included.

## Scope

The publisher/consumer/cache/SLA/delivery implementation and relevant tests were adapted from the evolving learning project. Dashboard analytics/search, WebSocket classes, Python/AI code and their migrations/dependencies were excluded. Backend monitoring and the SLA Grafana dashboard remain because they support the Day 4 operations features. Java and PowerShell filenames use feature names, consistent with the existing publishing edition.

The Compose project name and PostgreSQL volume are retained to allow an additive V4 upgrade. The source migration numbering is retained (V5, V6, V9, V10); omitted V7/V8 are later features, not missing prerequisites in this edition. Historical V1–V4 SQL is unchanged. PostgreSQL upgrade behavior still requires the real-database checks above.
