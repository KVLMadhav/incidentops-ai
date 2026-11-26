# Day 3 and Day 4 code guide

This edition is assembled from the completed implementation in `D:\ChatGPT\Incident OPS\incidentops-ai-final`, extending the existing publishing repository. It is a current scoped edition, not a reconstruction of historical Git commits. The original learning project is unchanged.

## Day 3 — reliable delivery and caching

| Feature | Files under `backend/src/main/java/com/incidentops` |
|---|---|
| Alert identity, exact retries and grouping | `incident/IncidentService.java`, `AlertGrouping.java`, `AlertConflictException.java` |
| Outbox claims, Kafka ACK, retries, final failures | `events/OutboxPublisher.java` |
| Versioned event and dead-letter configuration | `events/IncidentEvent.java`, `config/EventsConfig.java` |
| Receipt and notification in one database transaction | `events/NotificationListener.java`, `NotificationService.java` |
| Team-manager failure inspection and audited replay | `events/DeliveryOperations.java` |
| Ten-second cache, authorization before read, post-commit invalidation | `incident/ReliabilityCache.java`, `TeamReadService.java`, `IncidentService.java` |

The event schema is `docs/events/incident-v1.schema.json`. Alert identity is `(team, source, externalAlertId)`. An equivalent retry returns the original alert; conflicting immutable fields return 409 and record only differing field names. Grouping requires explicit `metadata.alertType` and `metadata.stableLabels`, and exactly one matching unresolved incident. Similar titles do not merge incidents.

Business changes and outbox intent commit together. A publisher claims committed work using owner/lease checks and marks it published after Kafka acknowledgement. Five failed claim attempts end in a dead letter. A crash after Kafka accepts a send can cause redelivery with the same event ID. Consumers commit their receipt and notification effect together before acknowledging the offset. Consumer exhaustion uses a Kafka DLT; outbox exhaustion stays in durable PostgreSQL rows. These are separate recovery mechanisms.

Redis holds temporary copies of incident detail and team summary. Membership checks use SQL before the cache is consulted. Redis failure falls back to PostgreSQL; it cannot erase delivery identity or incident truth. A failed invalidation can leave a copy stale until the ten-second TTL expires.

Migrations: V6 and V9. Shared command receipts and SLA foundations are in V5. Test coverage: `IncidentLifecycleIntegrationTest`, `InfrastructureIT`, and `KafkaDeliveryIT`.

## Day 4 — SLA and notification operations

| Feature | Files under `backend/src/main/java/com/incidentops` |
|---|---|
| Versioned policies and immutable instance snapshots | `incident/SLAPolicyService.java`, `SLAPolicy.java`, `SLAInstance.java` |
| Completion tracking, breach scan and escalation | `incident/SlaService.java` |
| Scoped policy, SLA and delivery-history APIs | `incident/SLAController.java`, `TeamReadController.java` |
| Bounded delivery leases, retries and terminal states | `events/NotificationDeliveryWorker.java`, `InAppDeliveryAdapter.java` |
| Correlated timeline/audit evidence | `incident/OperationalEvidence.java` |
| Health and bounded metrics | `config/DependenciesHealthIndicator.java`, `OperationsMetrics.java`, `SlaNotificationMetrics.java` |

The React policy forms, countdowns and notification history are in `frontend/src/SLAViews.jsx` and `App.jsx`.

| Severity | Acknowledge | Resolve |
|---|---|---|
| SEV1 | 5 minutes | 1 hour |
| SEV2 | 15 minutes | 4 hours |
| SEV3 | 1 hour | 12 hours |
| SEV4 | 4 hours | 48 hours |

Deadline = incident creation time + stored duration. Historical V5 targets retain their original policy through migration. Completion exactly at the deadline is on time; later completion remains a breach even if it precedes the next scan. Reopening preserves the lifetime SLA instance and first completion timestamps. Concurrent scans cannot create duplicate breach effects. Policies edited later apply to new incidents.

The in-app adapter stores delivery state with a stable delivery ID and three total attempts. Retry and terminal failures are inspectable. Escalation routing is local metadata; no external Slack or email is sent. Team inbox records are shared with authorized team viewers.

Migrations: V5 and V10. Controlled-clock tests are in `SlaOperationsIntegrationTest`; `PostgresSlaIT` replays them on PostgreSQL. `InfrastructureIT` covers the outbox/Kafka/delivery path. The optional Grafana dashboard is `ops/grafana/dashboards/sla-notifications.json`.

## Useful APIs

All paths below start with `/api/v1/teams/{teamId}` and require a bearer token:

- `GET /summary`, `GET /notifications`
- `GET /sla/policies`, `PUT /sla/policies/{severity}`
- `GET /incidents/{id}/sla`, `GET /incidents/{id}/sla-instance`
- `GET /sla/active`, `GET /sla/breaches`
- `GET /notifications/{notificationId}/deliveries`, `GET /notifications/{notificationId}/attempts`
- `GET /delivery/dead-letters`, `GET /delivery/alert-conflicts`
- `POST /delivery/outbox/{id}/replay` with a nonempty `reason`

Replay retains the original event ID and consumer receipts. It does not create a second business notification for a previously processed event.
