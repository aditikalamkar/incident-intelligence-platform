package com.opspilot.incident;

public enum IncidentStatus {
    TRIGGERED, ACKNOWLEDGED, RESOLVED;

    public boolean canTransitionTo(IncidentStatus target) {
        return switch (this) {
            case TRIGGERED -> target == ACKNOWLEDGED || target == RESOLVED;
            case ACKNOWLEDGED -> target == RESOLVED;
            case RESOLVED -> false;
        };
    }
}
