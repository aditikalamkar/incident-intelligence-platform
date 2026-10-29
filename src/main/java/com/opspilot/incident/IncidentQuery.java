package com.opspilot.incident;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Filters for incident search. Every filter is optional; those given are combined with AND.
 *
 * @param serviceIds   null means any service; an empty set matches nothing
 * @param assigneeId   only incidents assigned to this user
 * @param unassigned   only incidents nobody is assigned to
 * @param openedAfter  inclusive
 * @param openedBefore exclusive
 * @param text         words that must each appear in the title or in a comment (case-insensitive substring)
 * @param cursor       from the previous page's {@code nextCursor}
 */
public record IncidentQuery(
        Set<IncidentStatus> statuses,
        Set<Severity> severities,
        Set<UUID> serviceIds,
        UUID assigneeId,
        boolean unassigned,
        Instant openedAfter,
        Instant openedBefore,
        String text,
        String cursor,
        int limit) {
}
