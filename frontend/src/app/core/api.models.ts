/** Types mirroring the OpsPilot REST API (see the backend README). */

export type IncidentStatus = 'TRIGGERED' | 'ACKNOWLEDGED' | 'RESOLVED';
export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';

export const SEVERITIES: readonly Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

export interface Incident {
  id: string;
  serviceId: string | null;
  title: string;
  severity: Severity;
  status: IncidentStatus;
  assigneeId: string | null;
  alertCount: number;
  version: number;
  openedAt: string;
  acknowledgedAt: string | null;
  resolvedAt: string | null;
}

export interface IncidentPage {
  items: Incident[];
  nextCursor: string | null;
}

export type TimelineEventType =
  | 'INCIDENT_CREATED'
  | 'ALERT_GROUPED'
  | 'ALERT_RESOLVED'
  | 'ACKNOWLEDGED'
  | 'ASSIGNED'
  | 'COMMENT'
  | 'ESCALATED'
  | 'RESOLVED';

export interface TimelineEvent {
  id: string;
  type: TimelineEventType;
  actorId: string | null;
  details: Record<string, unknown> | null;
  createdAt: string;
}

/** Data of an {@code incident} event on the live stream. */
export interface IncidentUpdate {
  incident: Incident;
  event: TimelineEvent;
}

export interface User {
  id: string;
  email: string;
  name: string;
  role: 'ADMIN' | 'RESPONDER';
}

export interface Service {
  id: string;
  name: string;
  teamId: string | null;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}

/** RFC 9457 error body returned by the API. */
export interface ProblemDetail {
  status: number;
  detail?: string;
  requestId?: string;
}
