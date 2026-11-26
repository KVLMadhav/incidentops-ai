package com.incidentops.incident;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface AuditLogRepository extends JpaRepository<AuditLog,String> {
 List<AuditLog> findByTeamIdAndIncidentIdOrderByCreatedAtAscIdAsc(String teamId, String incidentId);
}
