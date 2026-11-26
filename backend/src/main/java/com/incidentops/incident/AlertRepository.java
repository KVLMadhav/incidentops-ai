package com.incidentops.incident;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
public interface AlertRepository extends JpaRepository<Alert,String>, JpaSpecificationExecutor<Alert> {
 Optional<Alert> findByTeamIdAndSourceAndExternalAlertId(String team,String source,String externalId);
 Optional<Alert> findByIdAndTeamIdAndServiceId(String id, String teamId, String serviceId);
}
