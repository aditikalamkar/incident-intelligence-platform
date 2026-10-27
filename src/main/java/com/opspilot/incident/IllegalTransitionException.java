package com.opspilot.incident;

import com.opspilot.common.ConflictException;
import java.util.UUID;

public class IllegalTransitionException extends ConflictException {

    public IllegalTransitionException(UUID incidentId, IncidentStatus from, IncidentStatus to) {
        super("Incident " + incidentId + " cannot move from " + from + " to " + to);
    }
}
