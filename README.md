# IncidentOps AI — Days 1 and 2

A Java 21 / Spring Boot 3.5.16 application with PostgreSQL and a React/Vite client.

This publishing edition includes **only the Day 1 and Day 2 feature scope**. It was separated from the evolving learning workspace on 2026-11-23; that workspace remains unchanged. This is a verified publishing edition, not a claim to recover an exact historical Git commit (the learning workspace had no commits).

## Included

- **Day 1:** BCrypt login, short-lived JWT access tokens, hashed rotating refresh tokens, logout/reuse revocation, safe local admin bootstrap, team memberships and roles, team-scoped service catalog, validation, correlation IDs, safe JSON errors, health, and OpenAPI.
- **Day 2:** alerts, incidents, sequence-backed incident numbers, alert links, assignment history, notes, a controlled status lifecycle, timeline/audit, optimistic locking, and atomic incident/history/audit/link/outbox persistence.
- React login/logout with coordinated token refresh, service catalog, alert and incident lists, incident details, assignment, notes, lifecycle actions, filters, and conflict/error handling.
- Flyway **V1–V4 only**; backend/security tests, a PostgreSQL Testcontainers profile, frontend client tests, and GitHub Actions CI.

The Day 2 outbox stores a durable promise for future publication. This edition has no Kafka publisher, Redis cache, SLA jobs, dashboard analytics, WebSocket delivery, Python/AI service, vector extension, or later-day migrations.

## Run locally

Start Docker Desktop, then run from the repository root in PowerShell:

```powershell
./scripts/start.ps1
./scripts/smoke-auth-catalog.ps1
./scripts/smoke-incidents.ps1
```

The first command creates `.env` with random local secrets if missing, builds the API and frontend, and starts PostgreSQL 17. Existing `.env` files are preserved. First builds need network access and can take several minutes. Ports 8080, 5173 and 55432 must be available.

Open [the client](http://localhost:5173). Read `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` in the ignored `.env`, then sign in. The smoke scripts create demo teams/services/incidents; reload and sign in again to inspect them. No raw token or password is printed by the scripts. Page reload loses the in-memory browser session and requires login.

- [API health](http://localhost:8080/actuator/health)
- [Swagger UI](http://localhost:8080/swagger-ui.html)
- [OpenAPI JSON](http://localhost:8080/v3/api-docs)

Stop with `docker compose down`; this preserves the database. Use this edition's separate Compose database volume, not the evolving application's later-day database. After first bootstrap, disable `BOOTSTRAP_ADMIN_ENABLED` and keep your login password privately. Changing `.env` does not rotate an already initialized database password.

## Verify

Set `JAVA_HOME` to a JDK 21 installation, then:

```powershell
cd backend
./mvnw.cmd -B package
./mvnw.cmd -B -Ppostgres-it verify
cd ../frontend
npm ci --no-audit --no-fund
npm test
npm run build
```

The PostgreSQL profile requires a working Docker engine and fails rather than silently skipping database coverage. Linux/macOS use `sh ./mvnw` instead of `./mvnw.cmd`.

See [publishing verification](docs/publishing-verification.md) for this edition's exact executed results and limitations. The API Docker build compiles/packages with tests skipped; the explicit Maven verification command and CI run the tests separately.

## Model and permissions

The security boundary is a **team**. Each team membership is ADMIN, INCIDENT_MANAGER, RESPONDER, or VIEWER. ADMIN manages memberships; ADMIN and INCIDENT_MANAGER manage catalog data and assignments. Responders can submit alerts/create incidents and work assigned incidents. VIEWER reads only. Every backend read/write checks membership; guessing another team's IDs returns 404. An account-level ADMIN role does not bypass team service permissions.

An incident follows `OPEN → ACKNOWLEDGED → INVESTIGATING → MITIGATED → RESOLVED → CLOSED`, with `RESOLVED → REOPENED → INVESTIGATING` for recurrence. SEV1 is most urgent. Changes require the current version; stale edits return HTTP 409. Incident creation saves history, audit, alert links and the pending outbox record in one transaction.

Study `AuthService.java`, `TeamService.java`, `ServiceCatalogService.java`, `IncidentService.java`, `IncidentLifecycleService.java`, `V3__teams_and_service_catalog.sql`, `V4__incident_lifecycle.sql`, and `IncidentLifecycleIntegrationTest.java` first.

This is a local learning application. Production hardening, MFA, password recovery and cleanup jobs are outside these two milestones.
