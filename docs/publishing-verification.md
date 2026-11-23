# Day 1–2 publishing verification

Prepared on 2026-11-23 (Asia/Calcutta) at `D:\ChatGPT\GitHub\incidentops-ai`.

## Provenance and scope

The source workspace is `D:\ChatGPT\Incident OPS`. It had no commits and its evolving application already included later milestones. This publishing edition preserves the current Day 1 identity/catalog security fixes and separates the V4 incident models/lifecycle from the existing delivery source. Later infrastructure, UI, migrations, dependencies and tests are excluded. The original workspace is unchanged.

This is a newly verified Day 1–2 edition, not an exact historical source snapshot. No migration in an existing database was rewritten; this edition is for its own fresh PostgreSQL volume and runs the unchanged V1–V4 migration files.

## Checks

| Check | Result | Evidence |
|---|---|---|
| Day 1–2 source scope | PASS | Only identity/catalog and V4 incident runtime code; V1–V4 migrations; no later infrastructure |
| Backend Java 21 package and tests | PASS | `./mvnw.cmd -B package`: 41 tests, 0 failures/errors/skips; BUILD SUCCESS; 1:17 minutes |
| Frontend auth client tests | PASS | `npm test`: 7 passed, 0 failed |
| Frontend production build | PASS | `npm run build`: Vite built the production client, including the service catalog and incident screens |
| PostgreSQL integration tests | NOT TESTED | Docker Linux engine pipe unavailable on this host; CI includes four PostgreSQL suites |
| Live Docker Compose/demo scripts | NOT TESTED | Requires Docker engine; syntax checks and unit/API verification recorded separately |
| Secret and generated-file staging audit | PASS | Index audited: 103 source files, four migrations; no day-numbered filenames, generated outputs, private keys, token-shaped strings, or configured local secrets |
| GitHub push | FAIL | Noninteractive git push could not obtain GitHub credentials; connected GitHub app also returned repository-not-found for the configured origin |

Backend breakdown: AdminBootstrapTest 3; AuthIntegrationTest 15; CatalogSecurityIntegrationTest 11; RefreshIntegrationTest 8; IncidentLifecycleIntegrationTest 4. Day 2 checks cover the complete lifecycle/reopening, invalid transitions, stale versions, assignments/notes/timeline/audit, role/team restrictions, forced transactional rollback, and concurrent incident-number allocation.

GitHub Actions is scoped to this publishing edition: the same ordinary suites plus 38 PostgreSQL tests (15 auth, 11 catalog, 8 refresh, 4 lifecycle), and frontend tests/build. PostgreSQL/CI results remain NOT TESTED until actually run; the older full application's PostgreSQL results are not reused as proof for this edition.

## Exact commands

From `D:\ChatGPT\GitHub\incidentops-ai\backend`:

```powershell
$env:JAVA_HOME='D:\ChatGPT\Incident OPS\.tools\jdk21\jdk-21.0.12.1+1'
./mvnw.cmd -B package
```

From `D:\ChatGPT\GitHub\incidentops-ai\frontend`:

```powershell
npm install --package-lock-only --ignore-scripts --offline --no-audit --no-fund
npm ci --no-audit --no-fund
npm test
npm run build
```

Verification logs are task-owned under `D:\Codex-temp`, outside the repository. No `.env`, credentials, database contents, logs, `target`, `node_modules`, or `dist` files belong in the publishing commit.

## Script names

Smoke commands use feature-based filenames: `scripts/smoke-auth-catalog.ps1` and `scripts/smoke-incidents.ps1`. No publishing source filename contains a day number.

The renamed smoke scripts were parsed successfully, all README references were updated, and `git diff --cached --check` passed. Staging audit command: `node D:\Codex-temp\audit-incidentops-publishing.cjs`. Its result is stored outside the repository at `D:\Codex-temp\incidentops-publishing-audit.json`.

## Publishing status

The code and renamed scripts are committed locally on `main`. The configured origin is `https://github.com/KVLMadhav/incidentops-ai.git`.

Executed from the publishing root:

```powershell
git -c credential.interactive=never -c http.lowSpeedLimit=1 -c http.lowSpeedTime=20 push -u origin main
```

Observed: exit 1, `Cannot prompt because user interactivity has been disabled` and `unable to get password from user`. Interactive credential prompts were disabled for the automated attempt; no password was requested, stored, or printed. The GitHub connector and the earlier `git ls-remote origin` could not access the configured repository. A valid accessible repository and local GitHub sign-in are required before publishing can finish. No remote commit is claimed.