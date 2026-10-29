package com.opspilot.incident;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live incident updates over Server-Sent Events. Each instance serves its own connections and hears
 * every change, from any instance, through {@link IncidentChangeListener}.
 * <p>
 * Every connection has its own sender thread and a bounded queue, so a slow client never delays the
 * others: if its queue fills up, its stream is closed and the client reconnects and reloads. Streams
 * carry no history; a client loads the current state when it receives {@code ready}, and again on
 * {@code resync}.
 */
@Component
class IncidentStream implements SmartLifecycle {

    record Update(IncidentResponse incident, TimelineEventResponse event) {
    }

    private static final Logger log = LoggerFactory.getLogger(IncidentStream.class);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(25);
    private static final int MAX_QUEUED_EVENTS = 256;

    private final Map<UUID, Set<Subscription>> subscriptions = new ConcurrentHashMap<>();
    private final IncidentService incidents;
    private final IncidentTimeline timeline;
    private final JsonMapper json;
    private volatile boolean running;

    IncidentStream(IncidentService incidents, IncidentTimeline timeline, JsonMapper json, MeterRegistry registry) {
        this.incidents = incidents;
        this.timeline = timeline;
        this.json = json;
        Gauge.builder("opspilot.incident.streams.open", subscriptions,
                        all -> all.values().stream().mapToInt(Set::size).sum())
                .description("Live update connections on this instance")
                .register(registry);
    }

    /** Opens a stream of the tenant's incident changes; it ends after {@code lifetime}. */
    SseEmitter subscribe(UUID tenantId, Duration lifetime) {
        SseEmitter emitter = new SseEmitter(lifetime.toMillis());
        if (!running) {
            emitter.complete(); // shutting down: the client reconnects to another instance
            return emitter;
        }
        Subscription subscription = new Subscription(tenantId, emitter);
        subscriptions.compute(tenantId, (id, tenantSubscriptions) -> {
            Set<Subscription> set = tenantSubscriptions != null ? tenantSubscriptions : ConcurrentHashMap.newKeySet();
            set.add(subscription);
            return set;
        });
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> subscription.finished = true);
        emitter.onCompletion(() -> subscription.finished = true);
        subscription.offer(signal("ready"));
        Thread.ofVirtual().name("incident-stream").start(subscription::run);
        return emitter;
    }

    /** Called for every committed change. Loads the incident once, however many clients are watching. */
    void publish(IncidentChange change) {
        Set<Subscription> watching = subscriptions.get(change.tenantId());
        if (watching == null || watching.isEmpty()) {
            return;
        }
        Set<DataWithMediaType> event;
        try {
            IncidentResponse incident = incidents.get(change.tenantId(), change.incidentId());
            TimelineEventResponse timelineEvent = timeline.find(change.tenantId(), change.eventId()).orElseThrow();
            // Serialised once for all clients; JSON is always UTF-8
            event = SseEmitter.event()
                    .name("incident")
                    .data(json.writeValueAsString(new Update(incident, timelineEvent)), MediaType.APPLICATION_JSON)
                    .build();
        } catch (RuntimeException e) {
            log.warn("Could not load incident change {}; asking clients to reload", change, e);
            event = signal("resync");
        }
        for (Subscription subscription : watching) {
            subscription.offer(event);
        }
    }

    /** Changes may have been missed (e.g. the listener reconnected): every client reloads. */
    void resync() {
        subscriptions.values().forEach(tenantSubscriptions -> tenantSubscriptions.forEach(s -> s.offer(signal("resync"))));
    }

    @Override
    public void start() {
        running = true;
    }

    /**
     * Ends every stream before the web server's graceful shutdown starts, which would otherwise wait
     * for these long-lived requests until it times out. Clients reconnect to another instance.
     */
    @Override
    public void stop() {
        running = false;
        subscriptions.values().forEach(tenantSubscriptions -> tenantSubscriptions.forEach(s -> s.ending = true));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** A control event; {@code data} is needed because browsers ignore events without it. */
    private static Set<DataWithMediaType> signal(String name) {
        return SseEmitter.event().name(name).data("{}").build();
    }

    private void remove(Subscription subscription) {
        subscriptions.computeIfPresent(subscription.tenantId, (id, tenantSubscriptions) -> {
            tenantSubscriptions.remove(subscription);
            return tenantSubscriptions.isEmpty() ? null : tenantSubscriptions;
        });
    }

    private final class Subscription {

        private final UUID tenantId;
        private final SseEmitter emitter;
        private final BlockingQueue<Set<DataWithMediaType>> queue = new ArrayBlockingQueue<>(MAX_QUEUED_EVENTS);
        /** The request ended: the client went away or the stream timed out. */
        volatile boolean finished;
        /** We end the stream: the client fell too far behind, or this instance is shutting down. */
        volatile boolean ending;

        Subscription(UUID tenantId, SseEmitter emitter) {
            this.tenantId = tenantId;
            this.emitter = emitter;
        }

        void offer(Set<DataWithMediaType> event) {
            if (!queue.offer(event)) {
                ending = true;
            }
        }

        /** Wakes every second to notice {@code finished}/{@code ending}; sends a heartbeat when idle. */
        void run() {
            long lastSent = System.nanoTime();
            try {
                while (!finished) {
                    if (ending) {
                        emitter.complete();
                        return;
                    }
                    Set<DataWithMediaType> event = queue.poll(1, TimeUnit.SECONDS);
                    if (event == null && System.nanoTime() - lastSent >= HEARTBEAT_INTERVAL.toNanos()) {
                        // Keeps proxies from closing an idle connection, and detects clients that went away
                        event = SseEmitter.event().comment("keepalive").build();
                    }
                    if (event != null) {
                        emitter.send(event);
                        lastSent = System.nanoTime();
                    }
                }
            } catch (IOException | IllegalStateException e) {
                // The client went away; the servlet container reports it and ends the request
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                remove(this);
            }
        }
    }
}
