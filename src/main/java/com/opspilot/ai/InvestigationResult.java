package com.opspilot.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A finished investigation, as stored and returned by the API.
 *
 * @param asOf when the incident's data was read; timeline events after it were not seen
 */
public record InvestigationResult(String summary, List<Item> findings, List<Item> nextSteps, Instant asOf) {

    /** A finding or a next step, with the items it relies on. */
    public record Item(String text, List<Source> sources) {
    }

    /**
     * An item the assistant was shown: a timeline event, an alert, or an earlier incident on the same service.
     *
     * @param at when the event happened, the alert arrived, or the earlier incident opened
     */
    public record Source(Kind kind, UUID id, String label, Instant at) {

        public enum Kind {
            EVENT, ALERT, INCIDENT
        }
    }
}
