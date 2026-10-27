package com.opspilot.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Rows are inserted by {@link IncidentIngestion} (SQL upsert); JPA is used for reads and state transitions.
 * {@code @DynamicUpdate} keeps a transition from overwriting {@code alert_count}, which ingestion
 * increments concurrently without touching {@code version}.
 */
@Entity
@Table(name = "incident")
@DynamicUpdate
public class Incident {

    @Id
    private UUID id;

    @Column(updatable = false)
    private UUID tenantId;

    @Column(updatable = false)
    private UUID serviceId;

    @Column(updatable = false)
    private String fingerprint;

    private String title;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    private IncidentStatus status;

    private UUID assigneeId;

    @Column(insertable = false, updatable = false)
    private int alertCount;

    @Version
    private long version;

    @Column(updatable = false)
    private Instant openedAt;

    private Instant acknowledgedAt;

    private Instant resolvedAt;

    protected Incident() {
    }

    public void acknowledge(Instant now) {
        transitionTo(IncidentStatus.ACKNOWLEDGED);
        acknowledgedAt = now;
    }

    public void resolve(Instant now) {
        transitionTo(IncidentStatus.RESOLVED);
        resolvedAt = now;
    }

    private void transitionTo(IncidentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalTransitionException(id, status, target);
        }
        status = target;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getTitle() {
        return title;
    }

    public Severity getSeverity() {
        return severity;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public UUID getAssigneeId() {
        return assigneeId;
    }

    public int getAlertCount() {
        return alertCount;
    }

    public long getVersion() {
        return version;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
