package com.opspilot.ai;

import com.opspilot.common.ResourceNotFoundException;
import com.opspilot.common.TooManyRequestsException;
import com.opspilot.incident.IncidentService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InvestigationService {

    /** Longer than an investigation can take, API timeouts and retries included: one still running was lost. */
    static final Duration STALE_AFTER = Duration.ofMinutes(15);
    static final String INTERRUPTED = "The investigation was interrupted, probably by a server restart. Try again.";

    private final InvestigationStore store;
    private final IncidentService incidents;
    private final Optional<Investigator> investigator;
    private final AiProperties properties;
    private final ApplicationEventPublisher events;

    InvestigationService(InvestigationStore store, IncidentService incidents, Optional<Investigator> investigator,
            AiProperties properties, ApplicationEventPublisher events) {
        this.store = store;
        this.incidents = incidents;
        this.investigator = investigator;
        this.properties = properties;
        this.events = events;
    }

    public AssistantStatus status() {
        return investigator.map(model -> new AssistantStatus(true, model.model()))
                .orElse(new AssistantStatus(false, null));
    }

    /**
     * Starts an investigation, or returns the incident's running one: asking twice, or two people asking
     * at once, costs one investigation.
     */
    @Transactional
    public InvestigationResponse start(UUID tenantId, UUID incidentId, UUID userId) {
        if (investigator.isEmpty()) {
            throw new ResourceNotFoundException("The assistant is not enabled on this server");
        }
        incidents.get(tenantId, incidentId);
        store.failStale(tenantId, incidentId, Instant.now().minus(STALE_AFTER), INTERRUPTED);
        Optional<InvestigationResponse> running = store.running(tenantId, incidentId);
        if (running.isPresent()) {
            return running.get();
        }
        if (store.countSince(tenantId, Instant.now().minus(Duration.ofDays(1))) >= properties.dailyLimitPerTenant()) {
            throw new TooManyRequestsException("Your organisation has used its " + properties.dailyLimitPerTenant()
                    + " assistant investigations for the last 24 hours. Try again later.");
        }
        UUID id = UUID.randomUUID();
        if (!store.insertRunning(id, tenantId, incidentId, userId)) {
            // Someone else's request started one between our check and our insert
            return store.latest(tenantId, incidentId).orElseThrow();
        }
        events.publishEvent(new InvestigationRequested(id, tenantId, incidentId, userId));
        return store.running(tenantId, incidentId).orElseThrow();
    }

    /** The incident's most recent investigation, whatever its outcome. */
    public Optional<InvestigationResponse> latest(UUID tenantId, UUID incidentId) {
        incidents.get(tenantId, incidentId);
        return store.latest(tenantId, incidentId).map(InvestigationService::presented);
    }

    /** Stores the result and announces it on the incident's timeline, in one transaction. */
    @Transactional
    void complete(InvestigationRequested request, InvestigationResult result, Investigator.Answer answer) {
        if (store.complete(request.id(), result, answer.model(), answer.inputTokens(), answer.outputTokens())) {
            incidents.recordInvestigation(request.tenantId(), request.incidentId(), request.requestedBy(),
                    request.id(), true);
        }
    }

    @Transactional
    void fail(InvestigationRequested request, String failure) {
        if (store.fail(request.id(), failure)) {
            incidents.recordInvestigation(request.tenantId(), request.incidentId(), request.requestedBy(),
                    request.id(), false);
        }
    }

    /** One still "running" long after it could have finished is shown as what it is: lost. */
    private static InvestigationResponse presented(InvestigationResponse investigation) {
        if (investigation.status() != InvestigationStatus.RUNNING
                || investigation.createdAt().isAfter(Instant.now().minus(STALE_AFTER))) {
            return investigation;
        }
        return new InvestigationResponse(investigation.id(), investigation.incidentId(), InvestigationStatus.FAILED,
                investigation.requestedBy(), investigation.model(), investigation.createdAt(), null, null,
                INTERRUPTED);
    }
}
