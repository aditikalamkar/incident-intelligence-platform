package com.opspilot.common;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Gauges whose value is a SQL query, evaluated when Prometheus scrapes. Used for backlogs that
 * must reflect every instance's work, which in-memory counters on one instance cannot.
 */
@Component
public class DatabaseGauges {

    private final MeterRegistry registry;
    private final JdbcClient jdbc;

    public DatabaseGauges(MeterRegistry registry, JdbcClient jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
    }

    /** @param sql a query returning a single number */
    public void register(String name, String description, String sql, String... tags) {
        Gauge.builder(name, () -> jdbc.sql(sql).query(Double.class).single())
                .description(description)
                .tags(tags)
                .register(registry);
    }

    public void registerSeconds(String name, String description, String sql) {
        Gauge.builder(name, () -> jdbc.sql(sql).query(Double.class).single())
                .description(description)
                .baseUnit("seconds")
                .register(registry);
    }
}
