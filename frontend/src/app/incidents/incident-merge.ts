import { Incident, IncidentStatus, Severity } from '../core/api.models';

export interface IncidentFilters {
  statuses: IncidentStatus[];
  severities: Severity[];
  assignee: 'anyone' | 'me' | 'none';
  q: string;
}

/**
 * An update and a reload can arrive in either order, so an older copy must never replace a newer
 * one. Both {@code version} and {@code alertCount} only ever increase.
 */
export function isOlder(candidate: Incident, current: Incident): boolean {
  return candidate.version < current.version || candidate.alertCount < current.alertCount;
}

/** The filters the browser can check itself; text search can also match comments, which it cannot. */
export function matchesFilters(incident: Incident, filters: IncidentFilters, me: string | null): boolean {
  return (
    (filters.statuses.length === 0 || filters.statuses.includes(incident.status)) &&
    (filters.severities.length === 0 || filters.severities.includes(incident.severity)) &&
    (filters.assignee === 'anyone' ||
      (filters.assignee === 'none' && incident.assigneeId === null) ||
      (filters.assignee === 'me' && incident.assigneeId !== null && incident.assigneeId === me))
  );
}

export function titleMatches(incident: Incident, q: string): boolean {
  const title = incident.title.toLowerCase();
  return q
    .toLowerCase()
    .split(/\s+/)
    .filter((term) => term !== '')
    .every((term) => title.includes(term));
}

/** The API's order: newest first, ties broken by ID (descending). */
export function compareIncidents(a: Incident, b: Incident): number {
  if (a.openedAt !== b.openedAt) {
    return Date.parse(b.openedAt) - Date.parse(a.openedAt);
  }
  return a.id < b.id ? 1 : a.id > b.id ? -1 : 0;
}

/**
 * Applies a live update to the loaded part of a search result.
 * - A listed incident is replaced, or removed if it no longer matches (e.g. resolved while viewing open ones).
 * - An unlisted one is inserted in order if it matches, but only within the loaded range: beyond it,
 *   loading the next page will bring it.
 *
 * @param complete true when every page is loaded
 */
export function mergeUpdate(
  items: Incident[],
  update: Incident,
  filters: IncidentFilters,
  me: string | null,
  complete: boolean,
): Incident[] {
  const index = items.findIndex((item) => item.id === update.id);
  if (index !== -1) {
    if (isOlder(update, items[index])) {
      return items;
    }
    if (!matchesFilters(update, filters, me)) {
      return items.filter((item) => item.id !== update.id);
    }
    return items.map((item, i) => (i === index ? update : item));
  }
  if (!matchesFilters(update, filters, me) || !titleMatches(update, filters.q)) {
    return items;
  }
  const position = items.findIndex((item) => compareIncidents(update, item) < 0);
  if (position === -1) {
    return complete ? [...items, update] : items;
  }
  return [...items.slice(0, position), update, ...items.slice(position)];
}

/** Appends a page, skipping incidents already listed (e.g. inserted by a live update). */
export function appendPage(items: Incident[], page: Incident[]): Incident[] {
  const listed = new Set(items.map((item) => item.id));
  return [...items, ...page.filter((item) => !listed.has(item.id))];
}
