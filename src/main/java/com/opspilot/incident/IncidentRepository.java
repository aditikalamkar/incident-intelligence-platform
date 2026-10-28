package com.opspilot.incident;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    Optional<Incident> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Incident> findByTenantIdOrderByOpenedAtDesc(UUID tenantId, Limit limit);

    List<Incident> findByTenantIdAndStatusOrderByOpenedAtDesc(UUID tenantId, IncidentStatus status, Limit limit);

    /** {@code SELECT ... FOR SHARE}: blocks concurrent updates of the row until the caller's transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select i from Incident i where i.id = :id and i.tenantId = :tenantId")
    Optional<Incident> findForShare(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    /** {@code SELECT ... FOR UPDATE}: serializes writers of this incident until the caller's transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Incident i where i.id = :id and i.tenantId = :tenantId")
    Optional<Incident> findForUpdate(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
