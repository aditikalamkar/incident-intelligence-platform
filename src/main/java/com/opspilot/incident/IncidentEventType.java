package com.opspilot.incident;

public enum IncidentEventType {
    INCIDENT_CREATED, ALERT_GROUPED, ALERT_RESOLVED, ACKNOWLEDGED, ASSIGNED, COMMENT, ESCALATED, RESOLVED,
    /** The AI assistant finished an investigation; the actor is who asked for it. */
    INVESTIGATION_COMPLETED,
    /** The AI assistant could not finish an investigation; the actor is who asked for it. */
    INVESTIGATION_FAILED
}
