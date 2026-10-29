import { TimelineEvent } from '../core/api.models';
import { describeEvent } from './timeline-text';

const NAMES: Record<string, string> = { alice: 'Alice', bob: 'Bob' };
const nameOf = (id: string | null | undefined) => (id ? NAMES[id] : 'Nobody');

function event(type: TimelineEvent['type'], actorId: string | null, details = {}): TimelineEvent {
  return { id: 'e', type, actorId, details, createdAt: '2026-10-01T10:00:00Z' };
}

describe('describeEvent', () => {
  it('names who did what', () => {
    expect(describeEvent(event('ACKNOWLEDGED', 'alice'), nameOf)).toBe('Alice acknowledged');
    expect(describeEvent(event('RESOLVED', 'bob'), nameOf)).toBe('Bob resolved');
    expect(describeEvent(event('COMMENT', 'alice', { body: 'x' }), nameOf)).toBe('Alice commented');
  });

  it('distinguishes taking an incident from assigning it', () => {
    expect(describeEvent(event('ASSIGNED', 'alice', { assigneeId: 'alice' }), nameOf)).toBe(
      'Alice took the incident',
    );
    expect(
      describeEvent(
        event('ASSIGNED', 'alice', { assigneeId: 'bob', previousAssigneeId: 'alice' }),
        nameOf,
      ),
    ).toBe('Alice assigned it to Bob (was Alice)');
  });

  it('describes automatic events', () => {
    expect(
      describeEvent(event('ESCALATED', null, { level: 2, notifiedUserIds: ['alice', 'bob'] }), nameOf),
    ).toBe('Escalated to level 2: paged Alice, Bob');
    expect(describeEvent(event('RESOLVED', null, { reason: 'ALL_ALERTS_RESOLVED' }), nameOf)).toBe(
      'Resolved automatically: all alerts cleared',
    );
  });
});
