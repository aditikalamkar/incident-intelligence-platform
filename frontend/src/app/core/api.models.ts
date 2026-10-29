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
  | 'RESOLVED'
  | 'INVESTIGATION_COMPLETED'
  | 'INVESTIGATION_FAILED';

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

/** GET /api/v1/assistant */
export interface AssistantStatus {
  enabled: boolean;
  model: string | null;
}

/** Something the assistant was shown: a timeline event, an alert, or an earlier incident. */
export interface InvestigationSource {
  kind: 'EVENT' | 'ALERT' | 'INCIDENT';
  id: string;
  label: string;
  at: string;
}

export interface InvestigationItem {
  text: string;
  sources: InvestigationSource[];
}

export interface InvestigationResult {
  summary: string;
  findings: InvestigationItem[];
  nextSteps: InvestigationItem[];
  /** When the incident's data was read; later timeline events were not seen. */
  asOf: string;
}

export interface Investigation {
  id: string;
  incidentId: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  requestedBy: string;
  model: string | null;
  createdAt: string;
  completedAt: string | null;
  result: InvestigationResult | null;
  failure: string | null;
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
