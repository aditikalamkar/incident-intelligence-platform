import { TimelineEvent } from '../core/api.models';

type NameOf = (userId: string | null | undefined) => string;

/** One line describing a timeline event; comment bodies are shown separately. */
export function describeEvent(event: TimelineEvent, nameOf: NameOf): string {
  const details = event.details ?? {};
  const actor = nameOf(event.actorId);
  switch (event.type) {
    case 'INCIDENT_CREATED':
      return 'Incident opened by an alert';
    case 'ALERT_GROUPED':
      return 'Another alert was grouped into this incident';
    case 'ALERT_RESOLVED':
      return 'An alert cleared';
    case 'ACKNOWLEDGED':
      return `${actor} acknowledged`;
    case 'ASSIGNED': {
      const assignee = details['assigneeId'] as string | undefined;
      const previous = details['previousAssigneeId'] as string | undefined;
      const assigned =
        event.actorId === null
          ? `Assigned to ${nameOf(assignee)}`
          : assignee === event.actorId
            ? `${actor} took the incident`
            : `${actor} assigned it to ${nameOf(assignee)}`;
      return previous ? `${assigned} (was ${nameOf(previous)})` : assigned;
    }
    case 'COMMENT':
      return `${actor} commented`;
    case 'ESCALATED': {
      const notified = (details['notifiedUserIds'] as string[] | undefined) ?? [];
      const paged = notified.length > 0 ? `: paged ${notified.map(nameOf).join(', ')}` : '';
      return `Escalated to level ${details['level']}${paged}`;
    }
    case 'RESOLVED':
      return event.actorId === null ? 'Resolved automatically: all alerts cleared' : `${actor} resolved`;
  }
}
