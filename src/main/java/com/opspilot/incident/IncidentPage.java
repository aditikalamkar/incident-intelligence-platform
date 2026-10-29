package com.opspilot.incident;

import java.util.List;

/** @param nextCursor pass as {@code cursor} to get the next page; null on the last page */
public record IncidentPage(List<IncidentResponse> items, String nextCursor) {
}
