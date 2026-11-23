package com.incidentops.incident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
public interface AlertRepository extends JpaRepository<Alert,String>, JpaSpecificationExecutor<Alert> { java.util.Optional<Alert> findByIdAndTeamIdAndServiceId(String id,String teamId,String serviceId);
}
