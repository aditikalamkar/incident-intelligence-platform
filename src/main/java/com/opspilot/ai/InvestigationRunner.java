package com.opspilot.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs investigations in the background, each on its own virtual thread, at most
 * {@link AiProperties#maxConcurrent()} at a time; the rest wait their turn. Metrics are never tagged by
 * tenant, to keep the number of time series bounded.
 */
@Component
class InvestigationRunner {

    private static final Logger log = LoggerFactory.getLogger(InvestigationRunner.class);
    private static final String UNEXPECTED = "The assistant ran into an unexpected error. Try again.";

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    private final Briefings briefings;
    private final ObjectProvider<Investigator> investigator;
    private final InvestigationService investigations;
    private final MeterRegistry registry;
    private volatile boolean stopping;

    InvestigationRunner(AiProperties properties, Briefings briefings, ObjectProvider<Investigator> investigator,
            InvestigationService investigations, MeterRegistry registry) {
        this.permits = new Semaphore(properties.maxConcurrent());
        this.briefings = briefings;
        this.investigator = investigator;
        this.investigations = investigations;
        this.registry = registry;
    }

    /** Once the investigation is committed, so it exists for whoever reads the result. */
    @TransactionalEventListener
    void onRequested(InvestigationRequested request) {
        Map<String, String> logContext = MDC.getCopyOfContextMap(); // keeps the request's X-Request-Id in the logs
        executor.execute(() -> {
            if (logContext != null) {
                MDC.setContextMap(logContext);
            }
            run(request);
        });
    }

    private void run(InvestigationRequested request) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "FAILED";
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(request, InvestigationService.INTERRUPTED);
            return;
        }
        try {
            Briefing briefing = briefings.prepare(request.tenantId(), request.incidentId());
            Investigator.Answer answer = investigator.getObject().investigate(briefing);
            investigations.complete(request, briefing.resolve(answer.analysis()), answer);
            countTokens(answer);
            outcome = "COMPLETED";
        } catch (InvestigationFailedException e) {
            log.warn("Investigation {} of incident {} failed: {}", request.id(), request.incidentId(), e.getMessage(),
                    e.getCause());
            fail(request, stopping ? InvestigationService.INTERRUPTED : e.getMessage());
        } catch (RuntimeException e) {
            log.error("Investigation {} of incident {} failed", request.id(), request.incidentId(), e);
            fail(request, stopping ? InvestigationService.INTERRUPTED : UNEXPECTED);
        } finally {
            permits.release();
            registry.counter("opspilot.ai.investigations", "outcome", outcome).increment();
            sample.stop(Timer.builder("opspilot.ai.investigation.duration")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofSeconds(1))
                    .maximumExpectedValue(Duration.ofMinutes(15))
                    .register(registry));
        }
    }

    private void fail(InvestigationRequested request, String failure) {
        try {
            investigations.fail(request, failure);
        } catch (RuntimeException e) {
            log.error("Could not record the failure of investigation {}", request.id(), e);
        }
    }

    /** Tagged by model: there are few, and the cost per token differs between them. */
    private void countTokens(Investigator.Answer answer) {
        registry.counter("opspilot.ai.tokens", "type", "input", "model", answer.model()).increment(answer.inputTokens());
        registry.counter("opspilot.ai.tokens", "type", "output", "model", answer.model()).increment(answer.outputTokens());
    }

    /** Runs before the database pool closes, so interrupted investigations are recorded as failed. */
    @PreDestroy
    void stop() throws InterruptedException {
        stopping = true;
        executor.shutdownNow();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            log.warn("Investigations still running at shutdown; they will show as interrupted");
        }
    }
}
