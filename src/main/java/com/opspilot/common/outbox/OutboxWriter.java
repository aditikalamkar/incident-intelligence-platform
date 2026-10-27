package com.opspilot.common.outbox;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Joins the caller's transaction: the event is committed if and only if the change it describes is. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID tenantId, UUID aggregateId, String type, Object payload) {
        jdbc.sql("""
                INSERT INTO outbox_event (tenant_id, aggregate_id, type, payload)
                VALUES (:tenantId, :aggregateId, :type, CAST(:payload AS jsonb))
                """)
                .param("tenantId", tenantId)
                .param("aggregateId", aggregateId)
                .param("type", type)
                .param("payload", json.writeValueAsString(payload))
                .update();
    }
}
