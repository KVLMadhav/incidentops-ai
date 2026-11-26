# IncidentOps AI — Days 1–4

Java 21, Spring Boot 3.5.16, PostgreSQL 17, Kafka, Redis, and a React/Vite client. This publishing edition extends the existing Days 1–2 repository with reliability and SLA features.

## Included

- **Days 1–2:** authentication, rotating refresh sessions, team roles, service catalog, alerts, incident workflow, assignments, notes, timeline, audit, and optimistic locking.
- **Day 3:** exact alert retry detection, durable conflict evidence, explicit alert grouping, transactional outbox publication, owner leases, bounded retries/dead letters, audited replay, versioned Kafka events, transactional consumer receipts, and Redis cache aside with SQL fallback.
- **Day 4:** versioned SLA policies, immutable incident deadline snapshots, acknowledgement/mitigation/resolution targets, breach detection and escalation, durable in-app notification delivery with retries and attempt history, health/metrics, and an optional Grafana dashboard.
- React screens for SLA policies, deadline countdowns, breach evidence, and notification delivery history. Team data refreshes every 15 seconds.

See [Day 3 and Day 4 code guide](docs/reliability-and-sla.md) and [current verification](docs/reliability-and-sla-verification.md). The [earlier publishing verification](docs/publishing-verification.md) records the original Days 1–2 edition.

## Run locally

Start Docker Desktop with Linux containers, then run from this repository:

```powershell
./scripts/start.ps1
./scripts/smoke-auth-catalog.ps1
./scripts/smoke-incidents.ps1
```

Open [IncidentOps](http://localhost:5173), [health](http://localhost:8080/actuator/health), or [Swagger](http://localhost:8080/swagger-ui.html). Read the bootstrap administrator credentials in the generated, ignored `.env`. The setup script preserves an existing `.env` and generates random secrets for a new installation.

The application uses ports 8080 and 5173; PostgreSQL is published on 55432, Kafka on 9094, and Redis on 6379. The Compose project name and PostgreSQL volume from Days 1–2 are preserved. Flyway adds V5, V6, V9, and V10 without changing V1–V4. The version gaps preserve the source migration numbering; V7/V8 belong to later work and are excluded. Existing incidents receive historical SLA snapshots; new incidents use the current policies.

`docker compose down` stops the stack and preserves its data. After initial bootstrap, disable `BOOTSTRAP_ADMIN_ENABLED`. Changing `.env` does not rotate a password in an existing database. The localhost Kafka/Redis setup is intended for local learning.

Optional monitoring:

```powershell
docker compose --profile observability up -d
```

Open [Grafana](http://localhost:3000) and [Prometheus](http://localhost:9090). Grafana uses username `admin` and `GRAFANA_ADMIN_PASSWORD`, falling back to the initial bootstrap password. The private API management port 9091 is accessible within Compose; `/actuator/prometheus` is denied on the public API port.

## Verify

Set `JAVA_HOME` to JDK 21. Run in `backend`:

```powershell
./mvnw.cmd -B package
./mvnw.cmd -B -Ppostgres-it verify
```

The second command requires Docker and runs real PostgreSQL/Kafka/Redis integration tests. Infrastructure absence fails the checks rather than silently skipping them. Linux/macOS use `sh ./mvnw`.

From the repository root, PowerShell helpers support focused verification:

```powershell
./scripts/verify-reliability.ps1 -Check All
./scripts/verify-sla.ps1 -Check Clock
./scripts/verify-sla.ps1 -Check All
```

In `frontend`, run `npm ci --no-audit --no-fund`, `npm test`, and `npm run build`. Host development uses Node 22. Host backend startup needs `DB_URL=jdbc:postgresql://localhost:55432/incident_ops`, `KAFKA_BOOTSTRAP_SERVERS=localhost:9094`, and the secrets from `.env`; Maven does not load Compose's environment file automatically.

## Push your changes

The Day 3–4 changes are left uncommitted for review:

```powershell
Set-Location 'D:\ChatGPT\GitHub\incidentops-ai'
git status --short
git add .
git commit -m "Add reliable event delivery and SLA notification operations"
git push origin main
```

The security boundary is a team. ADMIN and INCIDENT_MANAGER manage policies and assignments; RESPONDER works assigned incidents; VIEWER reads only. Deadlines use elapsed UTC time and survive policy changes, severity edits, reopening, and scheduler downtime. Delivery is at least once with idempotent database effects. Only local in-app notifications are implemented. Dashboard analytics, WebSockets, Python/AI features, and production hardening remain outside this edition.
