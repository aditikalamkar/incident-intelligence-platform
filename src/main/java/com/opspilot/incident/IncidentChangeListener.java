package com.opspilot.incident;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives every committed {@link IncidentChange}, from all instances, through PostgreSQL
 * LISTEN/NOTIFY, and hands it to {@link IncidentStream}. Holds one pooled connection for as long as
 * the application runs.
 * <p>
 * NOTIFY is not stored: changes made while this connection is down are lost. After reconnecting,
 * the listener therefore tells every client to reload.
 */
@Component
class IncidentChangeListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IncidentChangeListener.class);
    private static final int POLL_MILLIS = 1000;
    /** A connection that silently died (e.g. a network failure) is only noticed by using it. */
    private static final Duration HEALTH_CHECK_INTERVAL = Duration.ofSeconds(30);
    private static final Duration MAX_RECONNECT_DELAY = Duration.ofSeconds(30);

    private final DataSource dataSource;
    private final IncidentStream stream;
    private final JsonMapper json;
    private volatile boolean running;
    private volatile int backendPid;
    private Thread thread;

    IncidentChangeListener(DataSource dataSource, IncidentStream stream, JsonMapper json) {
        this.dataSource = dataSource;
        this.stream = stream;
        this.json = json;
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("incident-change-listener").daemon().start(this::run);
    }

    @Override
    public void stop() {
        running = false;
        try {
            thread.join(POLL_MILLIS * 3L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** The database session listening for changes; tests terminate it to simulate a lost connection. */
    int backendPid() {
        return backendPid;
    }

    private void run() {
        Duration reconnectDelay = Duration.ofSeconds(1);
        boolean reconnecting = false;
        while (running) {
            try (Connection connection = dataSource.getConnection()) {
                listen(connection);
                if (reconnecting) {
                    stream.resync();
                }
                reconnecting = false;
                reconnectDelay = Duration.ofSeconds(1);
                receive(connection);
            } catch (SQLException | RuntimeException e) {
                if (!running) {
                    return;
                }
                log.warn("Incident change listener lost its connection; reconnecting in {}", reconnectDelay, e);
                reconnecting = true;
                sleep(reconnectDelay);
                Duration doubled = reconnectDelay.multipliedBy(2);
                reconnectDelay = doubled.compareTo(MAX_RECONNECT_DELAY) < 0 ? doubled : MAX_RECONNECT_DELAY;
            }
        }
    }

    private void listen(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("LISTEN " + IncidentChange.CHANNEL);
            try (ResultSet rs = statement.executeQuery("SELECT pg_backend_pid()")) {
                rs.next();
                backendPid = rs.getInt(1);
            }
        }
        log.info("Listening for incident changes (database session {})", backendPid);
    }

    private void receive(Connection connection) throws SQLException {
        PGConnection postgres = connection.unwrap(PGConnection.class);
        long nextHealthCheck = System.nanoTime() + HEALTH_CHECK_INTERVAL.toNanos();
        while (running) {
            PGNotification[] notifications = postgres.getNotifications(POLL_MILLIS);
            if (notifications != null) {
                for (PGNotification notification : notifications) {
                    stream.publish(json.readValue(notification.getParameter(), IncidentChange.class));
                }
            }
            if (System.nanoTime() >= nextHealthCheck) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT 1");
                }
                nextHealthCheck = System.nanoTime() + HEALTH_CHECK_INTERVAL.toNanos();
            }
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
