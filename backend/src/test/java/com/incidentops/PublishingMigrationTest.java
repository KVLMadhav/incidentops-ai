package com.incidentops;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the upgrade with existing V4 business rows, not just an empty database. */
class PublishingMigrationTest {
    @Test void existingIncidentsAndOutboxSurviveReliabilityAndSlaUpgrade() {
        var database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:upgrade_" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        var legacy = Flyway.configure().dataSource(database).target("4").load();
        legacy.migrate();
        var jdbc = new JdbcTemplate(database);
        String user = UUID.randomUUID().toString(), team = UUID.randomUUID().toString();
        String service = UUID.randomUUID().toString(), incident = UUID.randomUUID().toString();
        String event = UUID.randomUUID().toString();
        var created = Timestamp.from(Instant.parse("2026-11-01T00:00:00Z"));
        var acknowledged = Timestamp.from(created.toInstant().plusSeconds(301));
        jdbc.update("""
            INSERT INTO users(id,email,display_name,password_hash,role,created_at)
            VALUES (?,'upgrade@example.test','Upgrade','unused','ADMIN',?)
            """, user, created);
        jdbc.update("INSERT INTO teams(id,name,name_key,created_at,updated_at) VALUES (?,'Upgrade','upgrade',?,?)",
            team, created, created);
        jdbc.update("""
            INSERT INTO monitored_services(id,team_id,name,name_key,created_at,updated_at)
            VALUES (?,?,'Checkout','checkout',?,?)
            """, service, team, created, created);
        jdbc.update("""
            INSERT INTO incidents(id,team_id,service_id,incident_number,title,severity,status,version,
                creator_user_id,created_at,updated_at)
            VALUES (?,?,?,'INC-000001','Existing incident','SEV1','ACKNOWLEDGED',7,?,?,?)
            """, incident, team, service, user, created, acknowledged);
        jdbc.update("""
            INSERT INTO incident_status_history(id,team_id,incident_id,actor_user_id,old_status,new_status,changed_at)
            VALUES (?,?,?,?,'OPEN','ACKNOWLEDGED',?)
            """, UUID.randomUUID().toString(), team, incident, user, acknowledged);
        jdbc.update("""
            INSERT INTO outbox_events(id,team_id,aggregate_id,event_type,payload,created_at)
            VALUES (?,?,?,'INCIDENT_CREATED','{}',?)
            """, event, team, incident, created);

        var current = Flyway.configure().dataSource(database).load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT version FROM incidents WHERE id=?", Long.class, incident)).isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sla_instances WHERE incident_id=?", Long.class, incident)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_sla_targets WHERE incident_id=?", Long.class, incident)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT due_at FROM incident_sla_targets WHERE incident_id=? AND target='RESOLVE'",
            Timestamp.class, incident).toInstant()).isEqualTo(created.toInstant().plusSeconds(14400));
        assertThat(jdbc.queryForObject("SELECT completed_at FROM incident_sla_targets WHERE incident_id=? AND target='ACKNOWLEDGE'",
            Timestamp.class, incident).toInstant()).isEqualTo(acknowledged.toInstant());
        assertThat(jdbc.queryForObject("SELECT pending FROM incident_sla_targets WHERE incident_id=? AND target='ACKNOWLEDGE'",
            Boolean.class, incident)).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id=?", Integer.class, event)).isZero();
        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_events WHERE id=?", Timestamp.class, event)).isNull();
        assertThat(current.migrate().migrationsExecuted).isZero();
    }
}
