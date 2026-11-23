package com.incidentops;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
/** Replays the Day 2 API, rollback, sequence-concurrency, and security proofs on PostgreSQL 17. */
@Testcontainers
@ActiveProfiles(value="postgres-it",inheritProfiles=false)
class PostgresIncidentIT extends IncidentLifecycleIntegrationTest {
 @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine")
  .withDatabaseName("incident_ops");
 @DynamicPropertySource static void datasource(DynamicPropertyRegistry registry){
  registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
  registry.add("spring.datasource.username",POSTGRES::getUsername);
  registry.add("spring.datasource.password",POSTGRES::getPassword);
  registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
 }
}
