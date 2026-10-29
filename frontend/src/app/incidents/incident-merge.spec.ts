import { Incident } from '../core/api.models';
import {
  appendPage,
  compareIncidents,
  IncidentFilters,
  isOlder,
  matchesFilters,
  mergeUpdate,
  titleMatches,
} from './incident-merge';

function incident(id: string, minute: number, changes: Partial<Incident> = {}): Incident {
  return {
    id,
    serviceId: 'svc',
    title: `Incident ${id}`,
    severity: 'HIGH',
    status: 'TRIGGERED',
    assigneeId: null,
    alertCount: 1,
    version: 0,
    openedAt: `2026-10-01T10:${String(minute).padStart(2, '0')}:00Z`,
    acknowledgedAt: null,
    resolvedAt: null,
    ...changes,
  };
}

const OPEN: IncidentFilters = {
  statuses: ['TRIGGERED', 'ACKNOWLEDGED'],
  severities: [],
  assignee: 'anyone',
  q: '',
};
const ME = 'user-me';

describe('isOlder', () => {
  it('compares version and alert count, which only ever grow', () => {
    const current = incident('a', 1, { version: 2, alertCount: 3 });

    expect(isOlder(incident('a', 1, { version: 1, alertCount: 3 }), current)).toBe(true);
    expect(isOlder(incident('a', 1, { version: 2, alertCount: 2 }), current)).toBe(true);
    expect(isOlder(incident('a', 1, { version: 2, alertCount: 3 }), current)).toBe(false);
    expect(isOlder(incident('a', 1, { version: 3, alertCount: 3 }), current)).toBe(false);
  });
});

describe('matchesFilters', () => {
  it('checks status, severity and assignee', () => {
    const mine = incident('a', 1, { assigneeId: ME, severity: 'CRITICAL' });

    expect(matchesFilters(mine, OPEN, ME)).toBe(true);
    expect(matchesFilters(mine, { ...OPEN, statuses: ['RESOLVED'] }, ME)).toBe(false);
    expect(matchesFilters(mine, { ...OPEN, severities: ['LOW'] }, ME)).toBe(false);
    expect(matchesFilters(mine, { ...OPEN, assignee: 'me' }, ME)).toBe(true);
    expect(matchesFilters(mine, { ...OPEN, assignee: 'me' }, 'someone-else')).toBe(false);
    expect(matchesFilters(mine, { ...OPEN, assignee: 'none' }, ME)).toBe(false);
  });
});

describe('titleMatches', () => {
  it('requires every word, ignoring case', () => {
    const latency = incident('a', 1, { title: 'HighLatency on checkout-api' });

    expect(titleMatches(latency, '')).toBe(true);
    expect(titleMatches(latency, 'latency  CHECKOUT')).toBe(true);
    expect(titleMatches(latency, 'latency payment')).toBe(false);
  });
});

describe('mergeUpdate', () => {
  const list = [incident('c', 30), incident('b', 20), incident('a', 10)];

  it('replaces a listed incident with a newer copy', () => {
    const acknowledged = incident('b', 20, { status: 'ACKNOWLEDGED', version: 1 });

    expect(mergeUpdate(list, acknowledged, OPEN, ME, false)).toEqual([list[0], acknowledged, list[2]]);
  });

  it('ignores an older copy', () => {
    const current = [incident('b', 20, { version: 2 })];

    expect(mergeUpdate(current, incident('b', 20, { version: 1 }), OPEN, ME, true)).toBe(current);
  });

  it('removes an incident that no longer matches the filters', () => {
    const resolved = incident('b', 20, { status: 'RESOLVED', version: 1 });

    expect(mergeUpdate(list, resolved, OPEN, ME, false).map((i) => i.id)).toEqual(['c', 'a']);
  });

  it('inserts a new incident in order', () => {
    expect(mergeUpdate(list, incident('new', 40), OPEN, ME, false).map((i) => i.id)).toEqual([
      'new',
      'c',
      'b',
      'a',
    ]);
    expect(mergeUpdate(list, incident('mid', 25), OPEN, ME, false).map((i) => i.id)).toEqual([
      'c',
      'mid',
      'b',
      'a',
    ]);
  });

  it('leaves an incident beyond the loaded pages to the next page, unless all are loaded', () => {
    const older = incident('old', 5);

    expect(mergeUpdate(list, older, OPEN, ME, false)).toBe(list);
    expect(mergeUpdate(list, older, OPEN, ME, true).map((i) => i.id)).toEqual(['c', 'b', 'a', 'old']);
  });

  it('does not insert an incident that does not match', () => {
    expect(mergeUpdate(list, incident('x', 40, { severity: 'LOW' }), { ...OPEN, severities: ['HIGH'] }, ME, true)).toBe(list);
    expect(mergeUpdate(list, incident('x', 40), { ...OPEN, q: 'database' }, ME, true)).toBe(list);
  });
});

describe('compareIncidents', () => {
  it('orders newest first, then by ID descending like the API', () => {
    const sorted = [incident('a', 10), incident('b', 10), incident('c', 20)].sort(compareIncidents);

    expect(sorted.map((i) => i.id)).toEqual(['c', 'b', 'a']);
  });
});

describe('appendPage', () => {
  it('skips incidents already listed', () => {
    const listed = [incident('b', 20), incident('a', 10)];

    expect(appendPage(listed, [incident('a', 10), incident('z', 5)]).map((i) => i.id)).toEqual([
      'b',
      'a',
      'z',
    ]);
  });
});
