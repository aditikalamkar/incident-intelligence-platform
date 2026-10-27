package com.opspilot.incident;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    Optional<Incident> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Incident> findByTenantIdOrderByOpenedAtDesc(UUID tenantId, Limit limit);

    List<Incident> findByTenantIdAndStatusOrderByOpenedAtDesc(UUID tenantId, IncidentStatus status, Limit limit);
}
